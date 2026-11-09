package com.keeper.vault.duress.settings

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.intruder.IntruderSettings
import com.keeper.vault.contracts.platform.KeeperException
import com.keeper.vault.duress.InMemoryIntruderSettingsPersistence
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

class IntruderSettingsTest {

    @Test
    fun `defaults are off and retain for thirty days`() {
        val settings = IntruderSettings()

        assertThat(settings.enabled).isFalse()
        assertThat(settings.captureOnFaceMismatch).isFalse()
        assertThat(settings.failureThreshold).isEqualTo(5)
        assertThat(settings.retentionDays).isEqualTo(30)
    }

    @Test
    fun `codec round trips every allowed combination`() {
        val combinations = listOf(
            IntruderSettings(),
            IntruderSettings(enabled = true),
            IntruderSettings(enabled = true, failureThreshold = 10, retentionDays = 7),
            IntruderSettings(
                enabled = true,
                captureOnFaceMismatch = true,
                failureThreshold = 15,
                retentionDays = null,
            ),
            IntruderSettings(enabled = true, failureThreshold = 5, retentionDays = 90),
        )

        for (expected in combinations) {
            val encoded = IntruderSettingsCodec.encode(expected)
            assertThat(IntruderSettingsCodec.decode(encoded)).isEqualTo(expected)
        }
    }

    @Test
    fun `forever retention is encoded explicitly and survives a round trip`() {
        val encoded = IntruderSettingsCodec.encode(
            IntruderSettings(enabled = true, failureThreshold = 5, retentionDays = null),
        )

        assertThat(encoded).contains("forever")
        assertThat(IntruderSettingsCodec.decode(encoded).retentionDays).isNull()
    }

    @Test
    fun `corrupt payloads fall back to the safe default instead of enabling capture`() {
        val corrupt = listOf(
            null,
            "",
            "garbage",
            "KIS1",
            "KIS1|enabled=1|mismatch=0|threshold=7|retention=30",
            "KIS1|enabled=1|mismatch=0|threshold=5|retention=15",
            "KIS1|enabled=0|mismatch=1|threshold=5|retention=30",
            "KIS1|enabled=yes|mismatch=0|threshold=5|retention=30",
            "KIS2|enabled=1|mismatch=0|threshold=5|retention=30",
        )

        for (raw in corrupt) {
            val decoded = IntruderSettingsCodec.decode(raw)
            assertThat(decoded).isEqualTo(IntruderSettings())
            assertThat(decoded.enabled).isFalse()
        }
    }

    @Test
    fun `unknown fields are ignored for forward compatibility`() {
        val decoded = IntruderSettingsCodec.decode(
            "KIS1|enabled=1|mismatch=0|threshold=10|retention=90|future=ignored",
        )

        assertThat(decoded).isEqualTo(
            IntruderSettings(enabled = true, failureThreshold = 10, retentionDays = 90),
        )
    }

    @Test
    fun `update persists the change and emits it`() = runTest {
        val persistence = InMemoryIntruderSettingsPersistence()
        val store = PersistentIntruderSettingsStore(
            persistence = persistence,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        store.update { it.copy(enabled = true, failureThreshold = 10) }

        assertThat(store.settings.value.enabled).isTrue()
        assertThat(store.settings.value.failureThreshold).isEqualTo(10)
        assertThat(persistence.raw).isNotNull()

        val reopened = PersistentIntruderSettingsStore(
            persistence = persistence,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        assertThat(reopened.settings.value).isEqualTo(store.settings.value)
    }

    @Test
    fun `stored face mismatch is migrated to false`() {
        val persistence = InMemoryIntruderSettingsPersistence(
            IntruderSettingsCodec.encode(
                IntruderSettings(enabled = true, captureOnFaceMismatch = true),
            ),
        )
        val store = PersistentIntruderSettingsStore(
            persistence = persistence,
            dispatcher = UnconfinedTestDispatcher(),
        )

        assertThat(store.settings.value.enabled).isTrue()
        assertThat(store.settings.value.captureOnFaceMismatch).isFalse()
        assertThat(
            IntruderSettingsCodec.decode(persistence.raw).captureOnFaceMismatch,
        ).isFalse()
    }

    @Test
    fun `updates cannot re-enable face mismatch capture`() = runTest {
        val store = PersistentIntruderSettingsStore(
            persistence = InMemoryIntruderSettingsPersistence(),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        store.update { it.copy(enabled = true, captureOnFaceMismatch = true) }

        assertThat(store.settings.value.enabled).isTrue()
        assertThat(store.settings.value.captureOnFaceMismatch).isFalse()
    }

    @Test
    fun `invalid updates are rejected and leave the previous state intact`() = runTest {
        val store = PersistentIntruderSettingsStore(
            persistence = InMemoryIntruderSettingsPersistence(),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        val thresholdFailure = runCatching {
            store.update { it.copy(enabled = true, failureThreshold = 7) }
        }.exceptionOrNull()
        val mismatchFailure = runCatching {
            store.update { it.copy(captureOnFaceMismatch = true) }
        }.exceptionOrNull()
        val retentionFailure = runCatching {
            store.update { it.copy(enabled = true, retentionDays = 365) }
        }.exceptionOrNull()

        assertThat(thresholdFailure).isInstanceOf(KeeperException.Duress::class.java)
        assertThat(mismatchFailure).isInstanceOf(KeeperException.Duress::class.java)
        assertThat(retentionFailure).isInstanceOf(KeeperException.Duress::class.java)
        assertThat(store.settings.value).isEqualTo(IntruderSettings())
    }

    @Test
    fun `a persistence failure does not publish the new state`() = runTest {
        val persistence = InMemoryIntruderSettingsPersistence().apply { failWrites = true }
        val store = PersistentIntruderSettingsStore(
            persistence = persistence,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        val failure = runCatching {
            store.update { it.copy(enabled = true) }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(KeeperException.Duress::class.java)
        assertThat(store.settings.value.enabled).isFalse()
        assertThat(persistence.writes).isEqualTo(0)
    }

    @Test
    fun `stored corrupt settings are read as the safe default`() {
        val store = PersistentIntruderSettingsStore(
            persistence = InMemoryIntruderSettingsPersistence("KIS1|enabled=1|threshold=99"),
            dispatcher = UnconfinedTestDispatcher(),
        )

        assertThat(store.settings.value).isEqualTo(IntruderSettings())
    }
}
