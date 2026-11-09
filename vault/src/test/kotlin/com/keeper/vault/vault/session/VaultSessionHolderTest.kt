package com.keeper.vault.vault.session

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.crypto.VaultKey
import org.junit.Test

class VaultSessionHolderTest {
    @Test
    fun startsLocked() {
        val holder = VaultSessionHolder()

        assertThat(holder.isUnlocked).isFalse()
        assertThat(holder.currentKey()).isNull()
        assertThat(holder.unlocked.value).isFalse()
    }

    @Test
    fun setKeyPublishesUnlockedState() {
        val holder = VaultSessionHolder()
        val key = VaultKey.from(ByteArray(32) { 1 })

        holder.setKey(key)

        assertThat(holder.isUnlocked).isTrue()
        assertThat(holder.currentKey()).isSameInstanceAs(key)
        assertThat(holder.unlocked.value).isTrue()
    }

    @Test
    fun replacingKeyZeroesPreviousKeyMaterial() {
        val holder = VaultSessionHolder()
        val first = VaultKey.from(ByteArray(32) { 4 })

        holder.setKey(first)
        holder.setKey(VaultKey.from(ByteArray(32) { 5 }))

        assertThat(first.copyBytes()).isEqualTo(ByteArray(32))
        holder.lock()
    }

    @Test
    fun lockZeroesCurrentKeyAndPublishesLockedState() {
        val holder = VaultSessionHolder()
        val key = VaultKey.from(ByteArray(32) { 9 })
        holder.setKey(key)

        holder.lock()

        assertThat(key.copyBytes()).isEqualTo(ByteArray(32))
        assertThat(holder.currentKey()).isNull()
        assertThat(holder.isUnlocked).isFalse()
        assertThat(holder.unlocked.value).isFalse()
    }

    @Test
    fun clearingAnAlreadyLockedHolderIsSafe() {
        val holder = VaultSessionHolder()

        holder.lock()
        holder.lock()

        assertThat(holder.isUnlocked).isFalse()
    }
}
