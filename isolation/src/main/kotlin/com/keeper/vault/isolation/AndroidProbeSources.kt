package com.keeper.vault.isolation

/** Build fields used by [EmulatorTamperProbe]. Grouped so the adapter is a single seam. */
data class BuildFields(
    val fingerprint: String?,
    val model: String?,
    val hardware: String?,
    val product: String?,
    val manufacturer: String?,
    val brand: String?,
    val device: String?,
)

/**
 * Platform data required to build the probe list.
 *
 * This seam exists so [AndroidProbeFactory] can be unit-tested with fakes. The Android adapter
 * ([ContextProbeSources]) is then small and does nothing except call framework APIs.
 */
interface AndroidProbeSources {
    fun fileExists(path: String): Boolean

    fun installedPackages(): Set<String>

    fun debuggable(): Boolean

    fun buildTags(): String?

    fun debuggerConnected(): Boolean

    fun accessibilityServices(): List<String>

    fun buildFields(): BuildFields

    fun processMappings(): List<String>

    fun classExists(className: String): Boolean
}

/**
 * Builds the production probe list from [sources].
 *
 * Each probe stays independently injectable; this class only proves the wiring. A source failure
 * that escapes here propagates to [DefaultTamperDetector], which turns it into an UNKNOWN warning
 * rather than a silent pass.
 */
class AndroidProbeFactory(
    private val sources: AndroidProbeSources,
) {
    fun create(): List<TamperProbe> {
        val installedPackages = sources.installedPackages()
        val buildFields = sources.buildFields()

        return listOf(
            RootTamperProbe(
                pathExists = sources::fileExists,
                buildTags = sources.buildTags(),
                installedPackages = installedPackages,
            ),
            DebuggerTamperProbe(
                isDebuggerConnected = sources::debuggerConnected,
                debuggable = sources.debuggable(),
            ),
            AccessibilityTamperProbe(
                enabledServices = sources::accessibilityServices,
            ),
            EmulatorTamperProbe(
                fingerprint = buildFields.fingerprint,
                model = buildFields.model,
                hardware = buildFields.hardware,
                product = buildFields.product,
                manufacturer = buildFields.manufacturer,
                brand = buildFields.brand,
                device = buildFields.device,
            ),
            HookTamperProbe(
                loadedLibraries = sources::processMappings,
                installedPackages = installedPackages,
                classExists = sources::classExists,
            ),
        )
    }
}
