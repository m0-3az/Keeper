package com.keeper.vault.vault.session

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.crypto.VaultKey
import org.junit.After
import org.junit.Test

class VaultSessionTest {
    @After
    fun tearDown() {
        VaultSession.lock()
    }

    @Test
    fun processWideSessionDelegatesToTheHolder() {
        val key = VaultKey.from(ByteArray(32) { 3 })

        VaultSession.setKey(key)

        assertThat(VaultSession.isUnlocked()).isTrue()
        assertThat(VaultSession.unlocked.value).isTrue()
        assertThat(VaultSession.provider.currentKey()).isSameInstanceAs(key)

        VaultSession.lock()

        assertThat(VaultSession.isUnlocked()).isFalse()
        assertThat(VaultSession.unlocked.value).isFalse()
        assertThat(VaultSession.provider.currentKey()).isNull()
        assertThat(key.copyBytes()).isEqualTo(ByteArray(32))
    }
}
