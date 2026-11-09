package com.keeper.vault.isolation

import com.keeper.vault.contracts.isolation.TamperKind

/**
 * Common `su` binaries and root-manager paths.
 *
 * A path existing is only an indicator: a user may have installed a root manager without the app
 * running as root, and some ROMs ship a `su` binary that is never granted. False positives are
 * accepted; the warning is advisory.
 */
class RootTamperProbe(
    private val pathExists: (String) -> Boolean,
    private val buildTags: String?,
    private val installedPackages: Set<String>,
) : TamperProbe {
    override val id: String = "root"

    override suspend fun probe(): List<ProbeFinding> = buildList {
        for (path in ROOT_PATHS) {
            if (pathExists(path)) {
                add(ProbeFinding(TamperKind.ROOT, "root path: $path"))
            }
        }
        if (buildTags?.contains(TEST_KEYS) == true) {
            add(ProbeFinding(TamperKind.ROOT, "test-keys build"))
        }
        for (packageName in ROOT_PACKAGES) {
            if (packageName in installedPackages) {
                add(ProbeFinding(TamperKind.ROOT, "root package: $packageName"))
            }
        }
    }

    companion object {
        private const val TEST_KEYS = "test-keys"

        val ROOT_PATHS: List<String> = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/vendor/bin/su",
            "/data/local/su",
            "/data/local/bin/su",
            "/data/local/xbin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/adb/magisk",
            "/sbin/.magisk",
            "/system/app/Superuser.apk",
            "/dev/com.koushikdutta.superuser.daemon/",
        )

        val ROOT_PACKAGES: List<String> = listOf(
            "com.topjohnwu.magisk",
            "eu.chainfire.supersu",
            "com.koushikdutta.superuser",
            "com.thirdparty.superuser",
            "com.noshufou.android.su",
            "com.zachspong.temprootremovejb",
            "com.ramdroid.appquarantine",
        )
    }
}

/**
 * Debugger attachment and debuggable builds.
 *
 * A debuggable build is normal for a developer build; it is still a warning because it lets a
 * connected host inspect the app process.
 */
class DebuggerTamperProbe(
    private val isDebuggerConnected: () -> Boolean,
    private val debuggable: Boolean,
) : TamperProbe {
    override val id: String = "debugger"

    override suspend fun probe(): List<ProbeFinding> = buildList {
        if (isDebuggerConnected()) {
            add(ProbeFinding(TamperKind.DEBUGGER, "debugger attached"))
        }
        if (debuggable) {
            add(ProbeFinding(TamperKind.DEBUGGER, "debuggable build"))
        }
    }
}

/**
 * Enabled accessibility services.
 *
 * Legitimate screen readers, switch access, and password managers use this permission. Keeper must
 * not treat every service as hostile, but an accessibility service can read and act on the app's
 * UI, so the user deserves to know one is enabled.
 */
class AccessibilityTamperProbe(
    private val enabledServices: () -> List<String>,
) : TamperProbe {
    override val id: String = "accessibility"

    override suspend fun probe(): List<ProbeFinding> = buildList {
        for (service in enabledServices()) {
            val id = service.trim()
            if (id.isNotEmpty()) {
                add(ProbeFinding(TamperKind.ACCESSIBILITY_SERVICE, "accessibility service: $id"))
            }
        }
    }
}

/**
 * Emulator indicators from build fields.
 *
 * Custom AOSP builds and some vendor images look unusual. A match is a warning, not proof that the
 * device is an emulator.
 */
class EmulatorTamperProbe(
    private val fingerprint: String?,
    private val model: String?,
    private val hardware: String?,
    private val product: String?,
    private val manufacturer: String?,
    private val brand: String?,
    private val device: String?,
) : TamperProbe {
    override val id: String = "emulator"

    override suspend fun probe(): List<ProbeFinding> = buildList {
        if (fingerprint != null && (
                fingerprint.startsWith("generic", ignoreCase = true) ||
                    fingerprint.contains("generic/", ignoreCase = true) ||
                    fingerprint.contains("emulator", ignoreCase = true) ||
                    fingerprint.contains("sdk_gphone", ignoreCase = true)
                )
        ) {
            add(ProbeFinding(TamperKind.EMULATOR, "emulator fingerprint: $fingerprint"))
        }
        if (model != null && (
                model.contains("google_sdk", ignoreCase = true) ||
                    model.contains("emulator", ignoreCase = true) ||
                    model.contains("android sdk built for", ignoreCase = true) ||
                    model.contains("sdk_gphone", ignoreCase = true)
                )
        ) {
            add(ProbeFinding(TamperKind.EMULATOR, "emulator model: $model"))
        }
        if (hardware != null && (
                hardware.contains("goldfish", ignoreCase = true) ||
                    hardware.contains("ranchu", ignoreCase = true) ||
                    hardware.contains("vbox86", ignoreCase = true) ||
                    hardware.contains("qemu", ignoreCase = true)
                )
        ) {
            add(ProbeFinding(TamperKind.EMULATOR, "emulator hardware: $hardware"))
        }
        if (product != null && (
                product.contains("sdk", ignoreCase = true) ||
                    product.contains("emulator", ignoreCase = true) ||
                    product.contains("simulator", ignoreCase = true) ||
                    product.contains("vbox86p", ignoreCase = true)
                )
        ) {
            add(ProbeFinding(TamperKind.EMULATOR, "emulator product: $product"))
        }
        if (manufacturer != null && manufacturer.contains("genymotion", ignoreCase = true)) {
            add(ProbeFinding(TamperKind.EMULATOR, "emulator manufacturer: $manufacturer"))
        }
        if (brand != null && brand.startsWith("generic", ignoreCase = true)) {
            add(ProbeFinding(TamperKind.EMULATOR, "emulator brand: $brand"))
        }
        if (device != null && device.startsWith("generic", ignoreCase = true)) {
            add(ProbeFinding(TamperKind.EMULATOR, "emulator device: $device"))
        }
    }
}

/**
 * Hooking frameworks: Frida, Xposed/LSPosed, Substrate, and common inline-hook libraries.
 *
 * The checks look at the process's own memory map, installed hook-manager packages, and a few
 * well-known classes. They are intentionally narrow to avoid warning every user who has a
 * legitimate automation or accessibility app installed.
 */
class HookTamperProbe(
    private val loadedLibraries: () -> List<String>,
    private val installedPackages: Set<String>,
    private val classExists: (String) -> Boolean = { false },
) : TamperProbe {
    override val id: String = "hook"

    override suspend fun probe(): List<ProbeFinding> = buildList {
        for (mapping in loadedLibraries()) {
            if (LIBRARY_TOKENS.any { mapping.contains(it, ignoreCase = true) }) {
                add(ProbeFinding(TamperKind.HOOK_FRAMEWORK, "hook library: $mapping"))
            }
        }
        for (packageName in HOOK_PACKAGES) {
            if (packageName in installedPackages) {
                add(ProbeFinding(TamperKind.HOOK_FRAMEWORK, "hook package: $packageName"))
            }
        }
        for (className in HOOK_CLASSES) {
            if (classExists(className)) {
                add(ProbeFinding(TamperKind.HOOK_FRAMEWORK, "hook class: $className"))
            }
        }
    }

    companion object {
        private val LIBRARY_TOKENS = listOf(
            "frida",
            "xposed",
            "substrate",
            "lsposed",
            "edxposed",
            "sandhook",
            "inlinehook",
        )

        private val HOOK_PACKAGES = listOf(
            "de.robv.android.xposed.installer",
            "org.lsposed.manager",
            "com.saurik.substrate",
            "io.va.exposed",
            "org.meowcat.edxposed.manager",
        )

        private val HOOK_CLASSES = listOf(
            "de.robv.android.xposed.XposedBridge",
            "de.robv.android.xposed.XC_MethodHook",
            "com.saurik.substrate.MS",
        )
    }
}
