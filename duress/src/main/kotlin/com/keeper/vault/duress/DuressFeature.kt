package com.keeper.vault.duress

import com.keeper.vault.contracts.duress.DuressController
import com.keeper.vault.contracts.intruder.IntruderCapture
import com.keeper.vault.contracts.intruder.IntruderLog
import com.keeper.vault.contracts.intruder.IntruderSettingsStore
import com.keeper.vault.contracts.isolation.TamperLog
import com.keeper.vault.contracts.platform.Clock
import com.keeper.vault.duress.duress.DecoyUnlocker
import com.keeper.vault.duress.duress.DecoyVault
import com.keeper.vault.duress.intruder.IntruderCoordinator
import com.keeper.vault.duress.intruder.IntruderRecorder
import com.keeper.vault.duress.intruder.IntruderRetention
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Process-wide Phase 6 composition.
 *
 * The app installs one component with [DuressFeature.install]; the intruder screens fall back to
 * the same default factory when launched directly (for example by an instrumented test). Nothing
 * here is reachable without an explicit install or screen launch.
 */
interface DuressComponent {
    val intruderSettings: IntruderSettingsStore
    val intruderLog: IntruderLog
    val intruderRecorder: IntruderRecorder
    val intruderCapture: IntruderCapture
    val intruderCoordinator: IntruderCoordinator
    val duressController: DuressController
    val decoyVault: DecoyVault
    val tamperLog: TamperLog?

    /** Purges according to the current retention setting. Returns the number removed. */
    suspend fun purgeExpired(): Int

    suspend fun deleteAllCaptures()

    /** Opens the decoy for the app after it locks the real vault. False for a wrong secret. */
    suspend fun openDecoy(candidate: CharArray): Boolean

    /** Schedules the daily purge and runs one purge now (module start). Idempotent enough. */
    fun start()
}

/** Default [DuressComponent]. Pure wiring; the logic classes are unit-tested separately. */
class DefaultDuressComponent(
    override val intruderSettings: IntruderSettingsStore,
    override val intruderLog: IntruderLog,
    override val intruderRecorder: IntruderRecorder,
    override val intruderCapture: IntruderCapture,
    override val intruderCoordinator: IntruderCoordinator,
    override val duressController: DuressController,
    override val decoyVault: DecoyVault,
    override val tamperLog: TamperLog?,
    private val clock: Clock,
    private val purgeScheduler: () -> Unit,
    private val scope: CoroutineScope,
) : DuressComponent {

    override suspend fun purgeExpired(): Int = IntruderRetention.purge(
        log = intruderLog,
        settings = intruderSettings.settings.value,
        nowMillis = clock.nowMillis(),
    )

    override suspend fun deleteAllCaptures() {
        intruderLog.deleteAll()
    }

    override suspend fun openDecoy(candidate: CharArray): Boolean {
        val unlocker = duressController as? DecoyUnlocker ?: return false
        return unlocker.openDecoy(candidate)
    }

    override fun start() {
        try {
            purgeScheduler()
        } catch (_: Exception) {
            // WorkManager may be unavailable in a restricted test process; the start purge below
            // still runs, and the next app start retries the schedule.
        }
        scope.launch {
            try {
                purgeExpired()
            } catch (_: Exception) {
                // Retention is retried by the periodic worker; module start never crashes.
            }
        }
    }
}

/** Process-wide holder for the Phase 6 component, mirroring `AuthFeature` and `VaultFeature`. */
object DuressFeature {
    @Volatile
    private var installed: DuressComponent? = null

    /** Installs and starts the component. Called from the app's manual-DI container. */
    fun install(component: DuressComponent): DuressComponent {
        installed = component
        component.start()
        return component
    }

    fun componentOrNull(): DuressComponent? = installed

    /**
     * Returns the installed component, or builds the default Android wiring on first use.
     *
     * This exists so the frozen-FQCN screens still work when launched directly by an
     * instrumented test. The app itself should call [install] from its manual-DI root.
     */
    fun component(context: android.content.Context): DuressComponent {
        installed?.let { return it }
        return synchronized(this) {
            installed ?: AndroidDuressWiring.create(context).also {
                installed = it
                it.start()
            }
        }
    }

    fun resetForTests() {
        installed = null
    }
}
