package com.keeper.vault.isolation

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.keeper.vault.contracts.isolation.TamperDetector
import com.keeper.vault.contracts.isolation.TamperLog
import com.keeper.vault.contracts.platform.BuildInfo
import com.keeper.vault.contracts.platform.Clock
import kotlinx.coroutines.CoroutineDispatcher
import java.io.File

/**
 * Android wiring for the isolation layer.
 *
 * No new permissions are required:
 * - app-private file existence is readable by the app;
 * - `Build` fields are public;
 * - enabled accessibility services are readable through [AccessibilityManager] and `Settings.Secure`;
 * - installed packages are limited to the known root-manager packages declared in the app
 *   manifest's `<queries>`; Keeper does not request QUERY_ALL_PACKAGES.
 *
 * [BuildInfo] is supplied by the app's manual DI container. The isolation module deliberately does
 * not read `BuildConfig` (it is disabled here) or duplicate app identity logic.
 *
 * The detector is advisory. Nothing in this file can or should block the user.
 */
object AndroidIsolation {
    const val PREFERENCES_NAME: String = "keeper_tamper_log"

    /**
     * Creates the durable log over app-private SharedPreferences.
     *
     * [dispatcher] is injected so callers use their central dispatcher provider and tests never
     * touch real disk IO.
     */
    fun createTamperLog(
        context: Context,
        dispatcher: CoroutineDispatcher,
    ): TamperLog {
        val preferences = context.applicationContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
        return DurableTamperLog(
            store = SharedPreferencesTamperLogStore(preferences),
            dispatcher = dispatcher,
        )
    }

    fun createDetector(
        context: Context,
        buildInfo: BuildInfo,
        clock: Clock = Clock { System.currentTimeMillis() },
    ): TamperDetector {
        val applicationInfo = context.applicationContext.applicationInfo
        return DefaultTamperDetector(
            probes = createProbes(context),
            backupPolicy = BackupPolicyProbe { BackupPolicy.isDisabled(applicationInfo.flags) },
            buildInfo = buildInfo,
            clock = clock,
        )
    }

    fun createProbes(context: Context): List<TamperProbe> =
        AndroidProbeFactory(ContextProbeSources(context.applicationContext)).create()
}

/**
 * The only Android-framework-touching probe source. Kept separate from [AndroidProbeFactory] so
 * the wiring is testable without a device.
 */
private class ContextProbeSources(
    private val context: Context,
) : AndroidProbeSources {

    override fun fileExists(path: String): Boolean = File(path).exists()

    override fun installedPackages(): Set<String> = buildSet {
        // Query only the root-manager packages declared in the manifest's <queries>. This works
        // under Android 11+ package visibility without QUERY_ALL_PACKAGES, which would expose
        // every installed app to Keeper.
        for (packageName in RootTamperProbe.ROOT_PACKAGES) {
            try {
                context.packageManager.getPackageInfo(packageName, 0)
                add(packageName)
            } catch (_: PackageManager.NameNotFoundException) {
                // Not installed, or hidden by an OEM's package-visibility policy.
            } catch (_: Exception) {
                // A hostile platform can fail the query; treat the package as unknown.
            }
        }
    }

    override fun debuggable(): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    override fun buildTags(): String? = Build.TAGS

    override fun debuggerConnected(): Boolean =
        Debug.isDebuggerConnected() || Debug.waitingForDebugger()

    override fun accessibilityServices(): List<String> {
        val manager = context.getSystemService(AccessibilityManager::class.java)
        val fromManager = manager
            ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?.mapNotNull { info ->
                val serviceInfo = info.resolveInfo?.serviceInfo ?: return@mapNotNull null
                "${serviceInfo.packageName}/${serviceInfo.name}"
            }
            .orEmpty()

        val fromSettings = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
            ?.split(':')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            .orEmpty()

        return (fromManager + fromSettings).distinct()
    }

    override fun buildFields(): BuildFields = BuildFields(
        fingerprint = Build.FINGERPRINT,
        model = Build.MODEL,
        hardware = Build.HARDWARE,
        product = Build.PRODUCT,
        manufacturer = Build.MANUFACTURER,
        brand = Build.BRAND,
        device = Build.DEVICE,
    )

    /**
     * Reads the process's own memory map. It is not a permission-protected file, but a hostile
     * platform could still hide it; a failure propagates to the detector and becomes an UNKNOWN
     * warning rather than a silent pass.
     */
    override fun processMappings(): List<String> = File("/proc/self/maps").useLines { lines ->
        lines.toList()
    }

    override fun classExists(className: String): Boolean = try {
        Class.forName(className, false, AndroidIsolation::class.java.classLoader)
        true
    } catch (_: ClassNotFoundException) {
        false
    } catch (_: LinkageError) {
        false
    }
}
