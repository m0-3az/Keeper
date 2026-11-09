package com.keeper.vault.auth

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.auth.LockReason
import com.keeper.vault.contracts.auth.LockState
import com.keeper.vault.contracts.auth.VaultMode
import com.keeper.vault.contracts.crypto.VaultKey
import org.junit.Test

class DefaultLockControllerTest {
    private val clock = FakeClock(now = 5_000L)
    private val store = AuthSessionStore()
    private val controller = DefaultLockController(store, clock)
    private val keyBytes = ByteArray(32) { (it + 5).toByte() }

    @Test
    fun `starts locked`() {
        assertThat(controller.state.value).isEqualTo(LockState.Locked)
    }

    @Test
    fun `unlock publishes the mode and timestamp`() {
        controller.unlock(VaultMode.DECOY)

        assertThat(controller.state.value)
            .isEqualTo(LockState.Unlocked(VaultMode.DECOY, sinceMillis = 5_000L))
    }

    @Test
    fun `lock closes the session and zeroes the live key`() {
        val session = store.open(VaultKey.from(keyBytes), VaultMode.REAL, 5_000L)
        controller.unlock(VaultMode.REAL)

        controller.lock(LockReason.MANUAL)

        assertThat(controller.state.value).isEqualTo(LockState.Locked)
        assertThat(session.isClosed).isTrue()
        assertThat(session.vaultKey.copyBytes()).isEqualTo(ByteArray(32))
        assertThat(store.current()).isNull()
        assertThat(controller.lastLockReason.value).isEqualTo(LockReason.MANUAL)
    }

    @Test
    fun `lock is idempotent`() {
        store.open(VaultKey.from(keyBytes), VaultMode.REAL, 5_000L)
        controller.unlock(VaultMode.REAL)

        controller.lock(LockReason.MANUAL)
        controller.lock(LockReason.SCREEN_OFF)

        assertThat(controller.state.value).isEqualTo(LockState.Locked)
        assertThat(controller.lastLockReason.value).isEqualTo(LockReason.SCREEN_OFF)
    }

    @Test
    fun `backgrounding an unlocked vault locks it`() {
        store.open(VaultKey.from(keyBytes), VaultMode.REAL, 5_000L)
        controller.unlock(VaultMode.REAL)

        controller.onAppBackgrounded()

        assertThat(controller.state.value).isEqualTo(LockState.Locked)
        assertThat(controller.lastLockReason.value).isEqualTo(LockReason.APP_BACKGROUNDED)
    }

    @Test
    fun `screen off locks an unlocked vault`() {
        store.open(VaultKey.from(keyBytes), VaultMode.REAL, 5_000L)
        controller.unlock(VaultMode.REAL)

        controller.onScreenOff()

        assertThat(controller.state.value).isEqualTo(LockState.Locked)
        assertThat(controller.lastLockReason.value).isEqualTo(LockReason.SCREEN_OFF)
    }

    @Test
    fun `lifecycle hooks are no-ops when already locked`() {
        controller.onAppBackgrounded()
        controller.onScreenOff()

        assertThat(controller.state.value).isEqualTo(LockState.Locked)
        assertThat(controller.lastLockReason.value).isNull()
    }

    @Test
    fun `every lock reason zeroes the key`() {
        LockReason.entries.forEach { reason ->
            val session = store.open(VaultKey.from(keyBytes), VaultMode.REAL, 5_000L)
            controller.unlock(VaultMode.REAL)

            controller.lock(reason)

            assertThat(session.vaultKey.copyBytes()).isEqualTo(ByteArray(32))
            assertThat(controller.lastLockReason.value).isEqualTo(reason)
        }
    }
}
