package com.keeper.vault.crypto

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.crypto.CryptoConstants
import com.keeper.vault.contracts.crypto.KeyDerivation
import com.keeper.vault.contracts.crypto.KeyEnvelope
import com.keeper.vault.contracts.crypto.KeystoreEnvelope
import com.keeper.vault.contracts.crypto.VaultKey
import com.keeper.vault.contracts.platform.KeeperException
import com.keeper.vault.contracts.platform.SecureRandomSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultVaultKeyManagerTest {
    private val store = InMemoryEnvelopeStore()
    private val derivation = FakeKeyDerivation()
    private val keystore = FakeKeystoreEnvelope()
    private val random = CountingRandom()

    private fun manager(
        iterations: Int = CryptoConstants.MIN_PBKDF2_ITERATIONS,
    ): DefaultVaultKeyManager = DefaultVaultKeyManager(
        keyDerivation = derivation,
        keystore = keystore,
        envelopeStore = store,
        random = random,
        defaultDispatcher = UnconfinedTestDispatcher(),
        ioDispatcher = UnconfinedTestDispatcher(),
        iterations = iterations,
    )

    @Test
    fun `creates a vault and returns a 32 byte key`() = runTest {
        val subject = manager()
        val created = subject.create("correct horse".toCharArray())
        val createdBytes = created.copyBytes()
        created.close()

        assertThat(createdBytes).hasLength(CryptoConstants.VAULT_KEY_BYTES)
        assertThat(subject.exists()).isTrue()
        assertThat(store.envelope).isNotNull()
        assertThat(store.envelope!!.salt).hasLength(CryptoConstants.SALT_BYTES)
        assertThat(store.envelope!!.iterations)
            .isEqualTo(CryptoConstants.MIN_PBKDF2_ITERATIONS)
        assertThat(derivation.lastIterations).isEqualTo(CryptoConstants.MIN_PBKDF2_ITERATIONS)
    }

    @Test
    fun `unlocks with the same secret`() = runTest {
        val subject = manager()
        val created = subject.create("correct horse".toCharArray())
        val expected = created.copyBytes()
        created.close()

        val unlocked = subject.unlock("correct horse".toCharArray())
        val actual = unlocked.copyBytes()
        unlocked.close()

        assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun `wrong secret fails with a uniform crypto error`() = runTest {
        val subject = manager()
        subject.create("correct horse".toCharArray()).close()

        val error = runCatching {
            subject.unlock("wrong secret".toCharArray())
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(KeeperException.Crypto::class.java)
        assertThat(error!!.message).isEqualTo("Unlock failed")
    }

    @Test
    fun `changing the secret invalidates the old one and accepts the new one`() = runTest {
        val subject = manager()
        val created = subject.create("old secret".toCharArray())
        val expected = created.copyBytes()
        created.close()

        subject.changeSecret("old secret".toCharArray(), "new secret".toCharArray())

        val oldError = runCatching {
            subject.unlock("old secret".toCharArray())
        }.exceptionOrNull()
        assertThat(oldError).isInstanceOf(KeeperException.Crypto::class.java)

        val unlocked = subject.unlock("new secret".toCharArray())
        assertThat(unlocked.copyBytes()).isEqualTo(expected)
        unlocked.close()
    }

    @Test
    fun `changing the secret with a wrong old secret leaves the old secret valid`() = runTest {
        val subject = manager()
        val created = subject.create("old secret".toCharArray())
        val expected = created.copyBytes()
        created.close()

        val error = runCatching {
            subject.changeSecret("wrong old".toCharArray(), "new secret".toCharArray())
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(KeeperException.Crypto::class.java)

        val unlocked = subject.unlock("old secret".toCharArray())
        assertThat(unlocked.copyBytes()).isEqualTo(expected)
        unlocked.close()

        val newError = runCatching {
            subject.unlock("new secret".toCharArray())
        }.exceptionOrNull()
        assertThat(newError).isInstanceOf(KeeperException.Crypto::class.java)
    }

    @Test
    fun `envelope returns the persisted envelope`() = runTest {
        val subject = manager()
        subject.create("secret".toCharArray()).close()

        assertThat(subject.envelope()).isNotNull()
        assertThat(subject.envelope()!!.iterations)
            .isEqualTo(CryptoConstants.MIN_PBKDF2_ITERATIONS)
    }

    @Test
    fun `empty secret fails uniformly`() = runTest {
        val subject = manager()

        val error = runCatching {
            subject.unlock(CharArray(0))
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(KeeperException.Crypto::class.java)
    }

    @Test
    fun `destroy propagates coroutine cancellation`() = runTest {
        val cancellingStore = object : EnvelopeStore {
            override fun load(): KeyEnvelope? = null
            override fun save(envelope: KeyEnvelope) = Unit
            override fun clear() {
                throw kotlinx.coroutines.CancellationException("cancelled")
            }
        }
        val subject = DefaultVaultKeyManager(
            keyDerivation = derivation,
            keystore = keystore,
            envelopeStore = cancellingStore,
            random = random,
            defaultDispatcher = UnconfinedTestDispatcher(),
            ioDispatcher = UnconfinedTestDispatcher(),
        )

        val error = runCatching { subject.destroy() }.exceptionOrNull()

        assertThat(error).isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
    }

    @Test
    fun `destroy removes the envelope and the keystore key`() = runTest {
        val subject = manager()
        subject.create("secret".toCharArray()).close()

        subject.destroy()

        assertThat(subject.exists()).isFalse()
        assertThat(keystore.deleted).isTrue()
        val error = runCatching {
            subject.unlock("secret".toCharArray())
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(KeeperException.Crypto::class.java)
    }

    @Test
    fun `refuses to create a second vault`() = runTest {
        val subject = manager()
        subject.create("secret".toCharArray()).close()

        val error = runCatching {
            subject.create("other".toCharArray())
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(KeeperException.Crypto::class.java)
    }

    @Test
    fun `unlock without a vault fails uniformly`() = runTest {
        val subject = manager()

        val error = runCatching {
            subject.unlock("secret".toCharArray())
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(KeeperException.Crypto::class.java)
    }

    @Test
    fun `tampered wrapped layer A fails unlock`() = runTest {
        val subject = manager()
        subject.create("secret".toCharArray()).close()
        val original = store.envelope!!
        val tampered = original.wrappedLayerA.copyOf()
        tampered[0] = (tampered[0] + 1).toByte()
        store.envelope = KeyEnvelope(
            version = original.version,
            salt = original.salt,
            iterations = original.iterations,
            wrappedLayerA = tampered,
        )

        val error = runCatching {
            subject.unlock("secret".toCharArray())
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(KeeperException.Crypto::class.java)
    }

    @Test
    fun `stores neither the KEK nor the vault key in the envelope`() = runTest {
        val subject = manager()
        val created = subject.create("secret".toCharArray())
        val vaultKey = created.copyBytes()
        created.close()

        val envelope = store.envelope!!
        val kek = derivation.lastDerived!!

        assertThat(envelope.wrappedLayerA.containsSubsequence(kek)).isFalse()
        assertThat(envelope.wrappedLayerA.containsSubsequence(vaultKey)).isFalse()
        assertThat(envelope.salt.containsSubsequence(kek)).isFalse()
    }

    @Test
    fun `forwards a higher iteration count`() = runTest {
        val subject = manager(iterations = 700_000)
        subject.create("secret".toCharArray()).close()

        assertThat(derivation.lastIterations).isEqualTo(700_000)
        assertThat(store.envelope!!.iterations).isEqualTo(700_000)
    }

    private class FakeKeyDerivation : KeyDerivation {
        var lastIterations: Int = 0
        var lastDerived: ByteArray? = null

        override fun derive(secret: CharArray, salt: ByteArray, iterations: Int): ByteArray {
            lastIterations = iterations
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(secret.concatToString().toByteArray(Charsets.UTF_8))
            digest.update(salt)
            digest.update(iterations.toString().toByteArray(Charsets.UTF_8))
            return digest.digest().also { lastDerived = it.copyOf() }
        }
    }

    private class FakeKeystoreEnvelope : KeystoreEnvelope {
        private val wrappingKey = ByteArray(32) { (it + 11).toByte() }
        private val box = AesGcmBox(DefaultSecureRandomSource())
        var deleted: Boolean = false

        override fun wrap(layerA: ByteArray): ByteArray = box.seal(wrappingKey, layerA)

        override fun unwrap(wrapped: ByteArray): ByteArray {
            if (deleted) throw KeeperException.Crypto("Key deleted")
            return box.open(wrappingKey, wrapped)
        }

        override fun deleteKey() {
            deleted = true
        }
    }

    private class InMemoryEnvelopeStore : EnvelopeStore {
        var envelope: KeyEnvelope? = null

        override fun load(): KeyEnvelope? = envelope

        override fun save(envelope: KeyEnvelope) {
            this.envelope = envelope
        }

        override fun clear() {
            envelope = null
        }
    }

    private class CountingRandom : SecureRandomSource {
        private var next: Int = 1

        override fun nextBytes(size: Int): ByteArray = ByteArray(size) {
            (next++).toByte()
        }
    }

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        outer@ for (i in 0..(size - needle.size)) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }
}
