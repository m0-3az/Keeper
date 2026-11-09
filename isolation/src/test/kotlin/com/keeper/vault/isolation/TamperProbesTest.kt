package com.keeper.vault.isolation

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.isolation.TamperKind
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TamperProbesTest {

    @Test
    fun `root probe reports su paths test keys and root packages`() = runTest {
        val probe = RootTamperProbe(
            pathExists = { it == "/system/xbin/su" || it == "/data/adb/magisk" },
            buildTags = "release-keys,test-keys",
            installedPackages = setOf("com.topjohnwu.magisk", "com.example.unrelated"),
        )

        val findings = probe.probe()

        assertThat(findings.map { it.kind }).containsExactly(
            TamperKind.ROOT,
            TamperKind.ROOT,
            TamperKind.ROOT,
            TamperKind.ROOT,
        )
        assertThat(findings.map { it.detail }).containsAtLeast(
            "root path: /system/xbin/su",
            "root path: /data/adb/magisk",
            "test-keys build",
            "root package: com.topjohnwu.magisk",
        )
        assertThat(findings.map { it.detail }).doesNotContain("root package: com.example.unrelated")
    }

    @Test
    fun `root probe is quiet on a clean device`() = runTest {
        val probe = RootTamperProbe(
            pathExists = { false },
            buildTags = "release-keys",
            installedPackages = emptySet(),
        )

        assertThat(probe.probe()).isEmpty()
    }

    @Test
    fun `root probe tolerates a null build tag and blank package names`() = runTest {
        val probe = RootTamperProbe(
            pathExists = { false },
            buildTags = null,
            installedPackages = setOf("", "com.example.unrelated"),
        )

        assertThat(probe.probe()).isEmpty()
    }

    @Test
    fun `debugger probe reports a connected debugger`() = runTest {
        val probe = DebuggerTamperProbe(isDebuggerConnected = { true }, debuggable = false)

        val findings = probe.probe()

        assertThat(findings).containsExactly(ProbeFinding(TamperKind.DEBUGGER, "debugger attached"))
    }

    @Test
    fun `debugger probe reports a debuggable build`() = runTest {
        val probe = DebuggerTamperProbe(isDebuggerConnected = { false }, debuggable = true)

        assertThat(probe.probe()).containsExactly(ProbeFinding(TamperKind.DEBUGGER, "debuggable build"))
    }

    @Test
    fun `debugger probe reports both conditions with distinct details`() = runTest {
        val probe = DebuggerTamperProbe(isDebuggerConnected = { true }, debuggable = true)

        assertThat(probe.probe().map { it.detail }).containsExactly("debugger attached", "debuggable build")
    }

    @Test
    fun `debugger probe is quiet when neither condition holds`() = runTest {
        val probe = DebuggerTamperProbe(isDebuggerConnected = { false }, debuggable = false)

        assertThat(probe.probe()).isEmpty()
    }

    @Test
    fun `accessibility probe reports every enabled service`() = runTest {
        val probe = AccessibilityTamperProbe {
            listOf("com.example.talkback/.Service", " ", "com.example.switch/.Service")
        }

        val findings = probe.probe()

        assertThat(findings.map { it.kind }).containsExactly(
            TamperKind.ACCESSIBILITY_SERVICE,
            TamperKind.ACCESSIBILITY_SERVICE,
        )
        assertThat(findings.map { it.detail }).containsExactly(
            "accessibility service: com.example.talkback/.Service",
            "accessibility service: com.example.switch/.Service",
        )
    }

    @Test
    fun `accessibility probe is quiet when no service is enabled`() = runTest {
        assertThat(AccessibilityTamperProbe { emptyList() }.probe()).isEmpty()
    }

    @Test
    fun `emulator probe reports fingerprint model and hardware indicators`() = runTest {
        val probe = EmulatorTamperProbe(
            fingerprint = "generic/sdk_gphone64_x86_64/emu64xa:14/UQ1A/123:userdebug/test-keys",
            model = "sdk_gphone64_x86_64",
            hardware = "ranchu",
            product = "sdk_gphone64_x86_64",
            manufacturer = "Google",
            brand = "google",
            device = "emu64xa",
        )

        val findings = probe.probe()

        assertThat(findings.map { it.kind }.distinct()).containsExactly(TamperKind.EMULATOR)
        assertThat(findings.map { it.detail }).containsAtLeast(
            "emulator fingerprint: generic/sdk_gphone64_x86_64/emu64xa:14/UQ1A/123:userdebug/test-keys",
            "emulator model: sdk_gphone64_x86_64",
            "emulator hardware: ranchu",
        )
    }

    @Test
    fun `emulator probe recognizes generic brand and device`() = runTest {
        val probe = EmulatorTamperProbe(
            fingerprint = "google/panther/panther:14/UQ1A/1:user/release-keys",
            model = "Pixel 7",
            hardware = "panther",
            product = "panther",
            manufacturer = "Google",
            brand = "generic",
            device = "generic_x86",
        )

        assertThat(probe.probe().map { it.detail }).containsExactly(
            "emulator brand: generic",
            "emulator device: generic_x86",
        )
    }

    @Test
    fun `emulator probe is quiet on a physical device`() = runTest {
        val probe = EmulatorTamperProbe(
            fingerprint = "google/panther/panther:14/UQ1A/1:user/release-keys",
            model = "Pixel 7",
            hardware = "panther",
            product = "panther",
            manufacturer = "Google",
            brand = "google",
            device = "panther",
        )

        assertThat(probe.probe()).isEmpty()
    }

    @Test
    fun `hook probe reports loaded hooking libraries`() = runTest {
        val probe = HookTamperProbe(
            loadedLibraries = {
                listOf(
                    "/data/app/libc.so",
                    "/data/local/tmp/libfrida-agent.so",
                    "/data/local/tmp/libsubstrate.so",
                )
            },
            installedPackages = emptySet(),
        )

        val findings = probe.probe()

        assertThat(findings.map { it.kind }).containsExactly(
            TamperKind.HOOK_FRAMEWORK,
            TamperKind.HOOK_FRAMEWORK,
        )
        assertThat(findings.map { it.detail }).containsAtLeast(
            "hook library: /data/local/tmp/libfrida-agent.so",
            "hook library: /data/local/tmp/libsubstrate.so",
        )
    }

    @Test
    fun `hook probe reports hooking packages`() = runTest {
        val probe = HookTamperProbe(
            loadedLibraries = { emptyList() },
            installedPackages = setOf("de.robv.android.xposed.installer", "com.example.friend"),
        )

        assertThat(probe.probe().map { it.detail }).containsExactly(
            "hook package: de.robv.android.xposed.installer",
        )
    }

    @Test
    fun `hook probe reports hook classes present in the process`() = runTest {
        val probe = HookTamperProbe(
            loadedLibraries = { emptyList() },
            installedPackages = emptySet(),
            classExists = { it == "de.robv.android.xposed.XposedBridge" },
        )

        assertThat(probe.probe().map { it.detail }).containsExactly(
            "hook class: de.robv.android.xposed.XposedBridge",
        )
    }

    @Test
    fun `hook probe is quiet without hook indicators`() = runTest {
        val probe = HookTamperProbe(
            loadedLibraries = { listOf("/data/app/libc.so", "/system/lib64/libart.so") },
            installedPackages = setOf("com.example.friend"),
            classExists = { false },
        )

        assertThat(probe.probe()).isEmpty()
    }

    @Test
    fun `backup policy is disabled when the allow backup flag is absent`() {
        assertThat(BackupPolicy.isDisabled(applicationFlags = 0)).isTrue()
    }

    @Test
    fun `backup policy is enabled when the allow backup flag is present`() {
        assertThat(
            BackupPolicy.isDisabled(applicationFlags = android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP),
        ).isFalse()
    }
}
