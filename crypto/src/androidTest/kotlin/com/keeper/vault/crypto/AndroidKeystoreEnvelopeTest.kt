package com.keeper.vault.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.platform.KeeperException
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-only tests for the Android Keystore adapter.
 *
 * StrongBox is preferred when available; the adapter falls back to TEE. These tests exercise the
 * real provider, not a JVM fake. They require a connected device/emulator with a Keystore.
 */
@RunWith(AndroidJUnit4::class)
class AndroidKeystoreEnvelopeTest {
    private val alias = "keeper.test.${System.nanoTime()}"
    private lateinit var subject: AndroidKeystoreEnvelope

    @Before
    fun setUp() {
        subject = AndroidKeystoreEnvelope(alias)
    }

    @After
    fun tearDown() {
        runCatching { subject.deleteKey() }
    }

    @Test
    fun wrapsAndUnwrapsLayerA() {
        val layerA = ByteArray(48) { (it + 3).toByte() }

        val wrapped = subject.wrap(layerA)

        assertThat(subject.unwrap(wrapped)).isEqualTo(layerA)
    }

    @Test
    fun rejectsTamperedWrappedBlob() {
        val wrapped = subject.wrap(ByteArray(48) { it.toByte() })
        val tampered = wrapped.copyOf().also { it[it.lastIndex] = (it[it.lastIndex] + 1).toByte() }

        assertThrows(KeeperException.Crypto::class.java) {
            subject.unwrap(tampered)
        }
    }

    @Test
    fun unwrapFailsAfterKeyDeletion() {
        val wrapped = subject.wrap(ByteArray(48) { it.toByte() })
        subject.deleteKey()

        assertThrows(KeeperException.Crypto::class.java) {
            subject.unwrap(wrapped)
        }
    }
}
