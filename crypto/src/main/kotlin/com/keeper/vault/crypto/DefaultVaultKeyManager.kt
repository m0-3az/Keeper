package com.keeper.vault.crypto

import com.keeper.vault.contracts.crypto.CryptoConstants
import com.keeper.vault.contracts.crypto.KeyDerivation
import com.keeper.vault.contracts.crypto.KeyEnvelope
import com.keeper.vault.contracts.crypto.KeystoreEnvelope
import com.keeper.vault.contracts.crypto.VaultKey
import com.keeper.vault.contracts.crypto.VaultKeyManager
import com.keeper.vault.contracts.platform.KeeperException
import com.keeper.vault.contracts.platform.SecretZeroizer
import com.keeper.vault.contracts.platform.SecureRandomSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Corrected key hierarchy.
 *
 * Setup: VaultKey = 32 random bytes; KEK = PBKDF2(secret, 16-byte salt, >= 600k);
 * Layer A = AES-GCM(KEK, VaultKey); Stored = Keystore.wrap(Layer A). Only salt, iterations, and
 * Stored are persisted. The KEK is never stored. The VaultKey is never stored unwrapped.
 *
 * Unlock: Keystore.unwrap(Stored) -> Layer A; PBKDF2(secret, salt) -> KEK; AES-GCM open -> VaultKey.
 * A wrong secret is an indistinguishable GCM tag failure.
 */
class DefaultVaultKeyManager(
    private val keyDerivation: KeyDerivation,
    private val keystore: KeystoreEnvelope,
    private val envelopeStore: EnvelopeStore,
    private val random: SecureRandomSource,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val iterations: Int = CryptoConstants.MIN_PBKDF2_ITERATIONS,
) : VaultKeyManager {
    private val box = AesGcmBox(random)

    init {
        require(iterations >= CryptoConstants.MIN_PBKDF2_ITERATIONS) {
            "PBKDF2 iterations must be at least ${CryptoConstants.MIN_PBKDF2_ITERATIONS}"
        }
    }

    override suspend fun exists(): Boolean = withContext(ioDispatcher) {
        envelopeStore.load() != null
    }

    override suspend fun create(secret: CharArray): VaultKey {
        if (secret.isEmpty()) {
            throw KeeperException.Crypto("Secret must not be empty")
        }
        val existing = withContext(ioDispatcher) { envelopeStore.load() }
        if (existing != null) {
            throw KeeperException.Crypto("Vault already exists")
        }

        val vaultKeyBytes = random.nextBytes(CryptoConstants.VAULT_KEY_BYTES)
        val salt = random.nextBytes(CryptoConstants.SALT_BYTES)
        var kek: ByteArray? = null
        var layerA: ByteArray? = null
        try {
            val derivedKek = withContext(defaultDispatcher) {
                keyDerivation.derive(secret, salt, iterations)
            }
            kek = derivedKek
            val sealedLayerA = box.seal(derivedKek, vaultKeyBytes)
            layerA = sealedLayerA
            val wrapped = withContext(ioDispatcher) { keystore.wrap(sealedLayerA) }
            val envelope = KeyEnvelope(
                version = 1,
                salt = salt,
                iterations = iterations,
                wrappedLayerA = wrapped,
            )
            withContext(ioDispatcher) { envelopeStore.save(envelope) }
            return VaultKey.from(vaultKeyBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: KeeperException.Crypto) {
            throw e
        } catch (e: Exception) {
            throw KeeperException.Crypto("Could not create vault", e)
        } finally {
            kek?.let(SecretZeroizer::zero)
            layerA?.let(SecretZeroizer::zero)
            SecretZeroizer.zero(vaultKeyBytes)
        }
    }

    override suspend fun unlock(secret: CharArray): VaultKey {
        if (secret.isEmpty()) {
            throw KeeperException.Crypto("Unlock failed")
        }
        val envelope = withContext(ioDispatcher) { envelopeStore.load() }
            ?: throw KeeperException.Crypto("Unlock failed")

        var kek: ByteArray? = null
        var layerA: ByteArray? = null
        try {
            val derivedKek = withContext(defaultDispatcher) {
                keyDerivation.derive(secret, envelope.salt, envelope.iterations)
            }
            kek = derivedKek
            val unwrappedLayerA = withContext(ioDispatcher) {
                keystore.unwrap(envelope.wrappedLayerA)
            }
            layerA = unwrappedLayerA
            val vaultKeyBytes = box.open(derivedKek, unwrappedLayerA)
            return try {
                VaultKey.from(vaultKeyBytes)
            } finally {
                SecretZeroizer.zero(vaultKeyBytes)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: KeeperException.Crypto) {
            throw KeeperException.Crypto("Unlock failed")
        } catch (e: Exception) {
            throw KeeperException.Crypto("Unlock failed")
        } finally {
            kek?.let(SecretZeroizer::zero)
            layerA?.let(SecretZeroizer::zero)
        }
    }

    override suspend fun changeSecret(oldSecret: CharArray, newSecret: CharArray) {
        if (oldSecret.isEmpty() || newSecret.isEmpty()) {
            throw KeeperException.Crypto("Change secret failed")
        }
        val envelope = withContext(ioDispatcher) { envelopeStore.load() }
            ?: throw KeeperException.Crypto("Change secret failed")

        var oldKek: ByteArray? = null
        var layerA: ByteArray? = null
        var vaultKeyBytes: ByteArray? = null
        try {
            val derivedOldKek = withContext(defaultDispatcher) {
                keyDerivation.derive(oldSecret, envelope.salt, envelope.iterations)
            }
            oldKek = derivedOldKek
            val unwrappedLayerA = withContext(ioDispatcher) {
                keystore.unwrap(envelope.wrappedLayerA)
            }
            layerA = unwrappedLayerA
            val recoveredVaultKey = box.open(derivedOldKek, unwrappedLayerA)
            vaultKeyBytes = recoveredVaultKey

            val newSalt = random.nextBytes(CryptoConstants.SALT_BYTES)
            var newKek: ByteArray? = null
            var newLayerA: ByteArray? = null
            try {
                val derivedNewKek = withContext(defaultDispatcher) {
                    keyDerivation.derive(newSecret, newSalt, iterations)
                }
                newKek = derivedNewKek
                val sealedNewLayerA = box.seal(derivedNewKek, recoveredVaultKey)
                newLayerA = sealedNewLayerA
                val wrapped = withContext(ioDispatcher) { keystore.wrap(sealedNewLayerA) }
                val replacement = KeyEnvelope(
                    version = 1,
                    salt = newSalt,
                    iterations = iterations,
                    wrappedLayerA = wrapped,
                )
                withContext(ioDispatcher) { envelopeStore.save(replacement) }
            } finally {
                newKek?.let(SecretZeroizer::zero)
                newLayerA?.let(SecretZeroizer::zero)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: KeeperException.Crypto) {
            throw KeeperException.Crypto("Change secret failed")
        } catch (e: Exception) {
            throw KeeperException.Crypto("Change secret failed")
        } finally {
            oldKek?.let(SecretZeroizer::zero)
            layerA?.let(SecretZeroizer::zero)
            vaultKeyBytes?.let(SecretZeroizer::zero)
        }
    }

    override suspend fun destroy() {
        withContext(ioDispatcher) {
            var failure: Throwable? = null
            try {
                envelopeStore.clear()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
            }
            try {
                keystore.deleteKey()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (failure == null) failure = e
            }
            if (failure != null) {
                throw KeeperException.Crypto("Could not destroy vault", failure)
            }
        }
    }

    override fun envelope(): KeyEnvelope? = envelopeStore.load()
}
