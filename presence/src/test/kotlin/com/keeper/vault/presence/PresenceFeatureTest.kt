package com.keeper.vault.presence

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.presence.FaceEmbedding
import com.keeper.vault.contracts.presence.FaceEnrollmentStore
import com.keeper.vault.contracts.presence.PresenceEvent
import com.keeper.vault.contracts.presence.PresenceShield
import com.keeper.vault.presence.shield.MutablePresenceGate
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.junit.After
import org.junit.Test

class PresenceFeatureTest {
    @After
    fun cleanup() {
        PresenceFeature.resetForTests()
    }

    @Test
    fun installExposesTheThreeInjectedCollaborators() {
        val store = NoopStore()
        val gate = MutablePresenceGate(true)
        val shield = NoopShield()

        PresenceFeature.install(store, gate, shield)

        assertThat(PresenceFeature.enrollmentStoreOrNull()).isSameInstanceAs(store)
        assertThat(PresenceFeature.gateOrNull()).isSameInstanceAs(gate)
        assertThat(PresenceFeature.shieldOrNull()).isSameInstanceAs(shield)
        assertThat(PresenceFeature.componentOrNull()).isNotNull()
    }

    @Test
    fun nothingIsInstalledByDefault() {
        assertThat(PresenceFeature.componentOrNull()).isNull()
        assertThat(PresenceFeature.enrollmentStoreOrNull()).isNull()
        assertThat(PresenceFeature.gateOrNull()).isNull()
        assertThat(PresenceFeature.shieldOrNull()).isNull()
    }

    @Test
    fun resetForTestsClearsTheHolder() {
        PresenceFeature.install(NoopStore(), MutablePresenceGate(true), NoopShield())

        PresenceFeature.resetForTests()

        assertThat(PresenceFeature.componentOrNull()).isNull()
    }

    private class NoopStore : FaceEnrollmentStore {
        override suspend fun save(embedding: FaceEmbedding) = Unit

        override suspend fun load(): FaceEmbedding? = null

        override suspend fun clear() = Unit
    }

    private class NoopShield : PresenceShield {
        override val events: SharedFlow<PresenceEvent> = MutableSharedFlow()

        override fun start() = Unit

        override fun stop() = Unit

        override fun updateEnrolled(embedding: FaceEmbedding?) = Unit
    }
}
