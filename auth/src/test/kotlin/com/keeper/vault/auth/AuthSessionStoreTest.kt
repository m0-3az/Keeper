package com.keeper.vault.auth

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.auth.VaultMode
import com.keeper.vault.contracts.crypto.VaultKey
import org.junit.Test

class AuthSessionStoreTest {
    private val keyBytes = ByteArray(32) { (it + 1).toByte() }
    private val store = AuthSessionStore()

    @Test
    fun `opening a session exposes the live key`() {
        val key = VaultKey.from(keyBytes)

        val session = store.open(key, VaultMode.REAL, openedAtMillis = 7L)

        assertThat(store.current()).isSameInstanceAs(session)
        assertThat(session.mode).isEqualTo(VaultMode.REAL)
        assertThat(session.openedAtMillis).isEqualTo(7L)
        assertThat(session.copyKeyBytes()).isEqualTo(keyBytes)
        assertThat(session.isClosed).isFalse()
    }

    @Test
    fun `replacing a session closes and zeroes the previous key`() {
        val first = store.open(VaultKey.from(keyBytes), VaultMode.REAL, 1L)
        val second = store.open(VaultKey.from(keyBytes), VaultMode.DECOY, 2L)

        assertThat(first.isClosed).isTrue()
        assertThat(first.vaultKey.copyBytes()).isEqualTo(ByteArray(32))
        assertThat(store.current()).isSameInstanceAs(second)
        assertThat(second.isClosed).isFalse()
    }

    @Test
    fun `closing clears the store and zeroes the key`() {
        val session = store.open(VaultKey.from(keyBytes), VaultMode.REAL, 1L)

        store.close()

        assertThat(store.current()).isNull()
        assertThat(session.isClosed).isTrue()
        assertThat(session.vaultKey.copyBytes()).isEqualTo(ByteArray(32))
    }

    @Test
    fun `closing twice is safe`() {
        val session = store.open(VaultKey.from(keyBytes), VaultMode.REAL, 1L)

        session.close()
        session.close()
        store.close()

        assertThat(session.isClosed).isTrue()
        assertThat(session.vaultKey.copyBytes()).isEqualTo(ByteArray(32))
    }

    @Test
    fun `session toString never contains key material`() {
        val session = store.open(VaultKey.from(keyBytes), VaultMode.REAL, 1L)

        assertThat(session.toString()).doesNotContain(keyBytes.joinToString())
        assertThat(session.toString()).contains("redacted")
    }
}
