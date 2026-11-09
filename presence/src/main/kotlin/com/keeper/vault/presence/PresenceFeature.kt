package com.keeper.vault.presence

import com.keeper.vault.contracts.presence.FaceEnrollmentStore
import com.keeper.vault.contracts.presence.PresenceShield
import com.keeper.vault.presence.shield.PresenceGate

/** Everything the app installs once, shared by the service and the enrollment screen. */
interface PresenceComponent {
    val enrollmentStore: FaceEnrollmentStore
    val gate: PresenceGate
    val shield: PresenceShield
}

/**
 * Process-wide presence holder for manual DI.
 *
 * The app constructs the encrypted store, the gate over its lock state, and the shield, then calls
 * [install]. The foreground service and enrollment screen read the holder; neither receives a
 * `VaultKey`. If nothing is installed, both surfaces fail closed with a visible message.
 */
object PresenceFeature {
    @Volatile
    private var component: PresenceComponent? = null

    fun install(
        enrollmentStore: FaceEnrollmentStore,
        gate: PresenceGate,
        shield: PresenceShield,
    ): PresenceComponent {
        val installed = object : PresenceComponent {
            override val enrollmentStore: FaceEnrollmentStore = enrollmentStore
            override val gate: PresenceGate = gate
            override val shield: PresenceShield = shield
        }
        component = installed
        return installed
    }

    fun install(value: PresenceComponent): PresenceComponent {
        component = value
        return value
    }

    fun componentOrNull(): PresenceComponent? = component

    fun enrollmentStoreOrNull(): FaceEnrollmentStore? = component?.enrollmentStore

    fun gateOrNull(): PresenceGate? = component?.gate

    fun shieldOrNull(): PresenceShield? = component?.shield

    fun resetForTests() {
        component = null
    }
}
