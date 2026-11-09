package com.keeper.vault.contracts.crypto

import com.keeper.vault.contracts.platform.SecretZeroizer
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.Cipher

/**
 * Constants shared by the crypto implementation and its tests.
 *
 * These are protocol constants, not tunables, except for [MIN_PBKDF2_ITERATIONS]. The iteration
 * floor follows current OWASP guidance for PBKDF2-HMAC-SHA256 and may be raised per device, never
 * lowered.
 */
object CryptoConstants {
    const val VAULT_KEY_BYTES: Int = 32
    const val SALT_BYTES: Int = 16
    const val MIN_PBKDF2_ITERATIONS: Int = 600_000
    const val GCM_NONCE_BYTES: Int = 12
    const val GCM_TAG_BITS: Int = 128
    const val STREAM_CHUNK_BYTES: Int = 64 * 1024

    /** Four-byte magic at the start of every encrypted file. */
    const val CONTAINER_MAGIC: String = "KPR1"

    /** Container format version. Bump only with a migration path. */
    const val CONTAINER_VERSION: Int = 1
}

/**
 * The 32-byte vault key.
 *
 * Instances are short-lived and must be wrapped in `use { }`. [close] zeroes the backing array.
 * [copyBytes] exists for the crypto implementation and tests; callers must zero the copy.
 *
 * This type is never persisted. The corrected hierarchy stores only a Keystore-wrapped Layer A.
 */
class VaultKey private constructor(
    private val bytes: ByteArray,
) : AutoCloseable {
    /** Returns a defensive copy. The caller owns it and must zero it. */
    fun copyBytes(): ByteArray = bytes.copyOf()

    /** Zeroes the key material. Safe to call more than once. */
    override fun close() {
        SecretZeroizer.zero(bytes)
    }

    override fun toString(): String = "VaultKey([redacted])"

    companion object {
        /** Wraps an existing 32-byte key. The input is copied and is not zeroed by this call. */
        fun from(bytes: ByteArray): VaultKey {
            require(bytes.size == CryptoConstants.VAULT_KEY_BYTES) {
                "VaultKey must be ${CryptoConstants.VAULT_KEY_BYTES} bytes"
            }
            return VaultKey(bytes.copyOf())
        }
    }
}

/**
 * Persisted key envelope. Contains no secret material in plaintext.
 *
 * @property version envelope format version.
 * @property salt 16-byte PBKDF2 salt. Not secret, but must be unique per vault.
 * @property iterations PBKDF2 iteration count actually used. Must be >= 600,000.
 * @property wrappedLayerA `Keystore.encrypt(AES-GCM(KEK, VaultKey))`. The Keystore layer is
 * hardware-bound; the inner GCM tag is what makes the user's secret mandatory.
 */
class KeyEnvelope(
    val version: Int,
    val salt: ByteArray,
    val iterations: Int,
    val wrappedLayerA: ByteArray,
) {
    init {
        require(version >= 1) { "Unsupported envelope version" }
        require(salt.size == CryptoConstants.SALT_BYTES) { "Salt must be 16 bytes" }
        require(iterations >= CryptoConstants.MIN_PBKDF2_ITERATIONS) {
            "PBKDF2 iterations must be at least ${CryptoConstants.MIN_PBKDF2_ITERATIONS}"
        }
        require(wrappedLayerA.isNotEmpty()) { "Wrapped Layer A must not be empty" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KeyEnvelope) return false
        return version == other.version &&
            iterations == other.iterations &&
            salt.contentEquals(other.salt) &&
            wrappedLayerA.contentEquals(other.wrappedLayerA)
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + iterations
        result = 31 * result + salt.contentHashCode()
        result = 31 * result + wrappedLayerA.contentHashCode()
        return result
    }

    override fun toString(): String =
        "KeyEnvelope(version=$version, iterations=$iterations, salt=[redacted], wrappedLayerA=[redacted])"
}

/**
 * PBKDF2-HMAC-SHA256. Synchronous and CPU-bound; callers run it on `Dispatchers.Default` and
 * benchmark it on low-end devices.
 *
 * The returned array is a raw KEK. The caller must use it immediately and zero it. This contract
 * never stores the result.
 */
fun interface KeyDerivation {
    /**
     * @param secret user-entered secret; never converted to [String].
     * @param salt 16 random bytes from the vault envelope.
     * @param iterations at least [CryptoConstants.MIN_PBKDF2_ITERATIONS].
     * @return a 32-byte KEK. Caller owns and must zero it.
     */
    fun derive(secret: CharArray, salt: ByteArray, iterations: Int): ByteArray
}

/**
 * Hardware-backed wrapping of Layer A. Production prefers StrongBox and falls back to TEE.
 *
 * This layer stops offline extraction of app files. It is **not** a substitute for the user's
 * secret: the inner AES-GCM tag fails when the secret is wrong.
 */
interface KeystoreEnvelope {
    /** Encrypts Layer A with a non-auth Keystore key. Returns the blob to persist. */
    fun wrap(layerA: ByteArray): ByteArray

    /** Decrypts the persisted blob. Throws [com.keeper.vault.contracts.platform.KeeperException.Crypto] on failure. */
    fun unwrap(wrapped: ByteArray): ByteArray

    /** Deletes the Keystore key. Existing envelopes become undecryptable. */
    fun deleteKey()
}

/**
 * Envelope produced by the biometric convenience path.
 *
 * The ciphertext is the VaultKey wrapped by a separate, auth-bound Keystore key. The IV is stored
 * alongside it. Neither value is secret without the Keystore key.
 */
class BiometricEnvelope(
    val iv: ByteArray,
    val ciphertext: ByteArray,
) {
    init {
        require(iv.isNotEmpty()) { "IV must not be empty" }
        require(ciphertext.isNotEmpty()) { "Ciphertext must not be empty" }
    }

    override fun toString(): String = "BiometricEnvelope(iv=[redacted], ciphertext=[redacted])"
}

/**
 * Separate Keystore key for the biometric convenience path.
 *
 * The key must require user authentication (`setUserAuthenticationRequired(true)`) and must be
 * invalidated when biometrics change. This path is additive: the password path must remain
 * authoritative and usable if biometrics are unavailable, removed, or re-enrolled.
 */
interface BiometricVaultKeyStore {
    /** True when a Class 3 biometric and a usable auth-bound key exist. */
    fun isAvailable(): Boolean

    /** Wraps the VaultKey for later BiometricPrompt-gated decryption. */
    fun wrap(vaultKey: ByteArray): BiometricEnvelope

    /**
     * Creates and initializes the decrypt cipher for BiometricPrompt. The caller passes the
     * returned cipher to `BiometricPrompt.authenticate(CryptoObject(cipher))`.
     */
    fun createDecryptCipher(envelope: BiometricEnvelope): Cipher

    /** Unwraps the VaultKey after a successful BiometricPrompt authentication. */
    fun unwrap(cipher: Cipher, envelope: BiometricEnvelope): ByteArray

    /** Deletes the auth-bound key. */
    fun deleteKey()
}

/**
 * Streaming AES-256-GCM file cipher.
 *
 * Container format v1 (all integers big-endian):
 * `magic[4] | version[1] | nonce[12] | ciphertext[...] | tag[16]`.
 *
 * The implementation must:
 * - generate a fresh 12-byte nonce per file from a CSPRNG;
 * - authenticate the header as AAD;
 * - stream in [CryptoConstants.STREAM_CHUNK_BYTES] chunks;
 * - throw on tag failure and delete/leave no plaintext output;
 * - never write plaintext to a temporary file or cache.
 */
interface VaultCipher {
    /** Encrypts [plaintext] into [ciphertext]. Does not close either stream. */
    fun encrypt(plaintext: InputStream, ciphertext: OutputStream, key: VaultKey)

    /** Decrypts [ciphertext] into [plaintext]. Does not close either stream. */
    fun decrypt(ciphertext: InputStream, plaintext: OutputStream, key: VaultKey)
}

/**
 * Owns the corrected key hierarchy.
 *
 * Setup:
 * 1. VaultKey = 32 random bytes.
 * 2. KEK = PBKDF2(secret, salt, >= 600_000).
 * 3. Layer A = AES-GCM(KEK, VaultKey).
 * 4. Stored = Keystore.wrap(Layer A).
 * 5. Persist only salt, iterations, and Stored; zero every buffer.
 *
 * Unlock:
 * 1. Keystore.unwrap(Stored) -> Layer A.
 * 2. KEK = PBKDF2(secret, salt, iterations).
 * 3. AES-GCM decrypt(Layer A) -> VaultKey.
 * 4. Wrong secret produces a uniform tag failure, never a distinguishable oracle.
 *
 * The KEK is never stored. The VaultKey is never stored unwrapped.
 */
interface VaultKeyManager {
    /** True when an envelope exists. */
    suspend fun exists(): Boolean

    /** Creates the vault and returns the live VaultKey. Zeroes all intermediate buffers. */
    suspend fun create(secret: CharArray): VaultKey

    /** Unlocks and returns the live VaultKey. Zeroes all intermediate buffers. */
    suspend fun unlock(secret: CharArray): VaultKey

    /** Re-wraps the same VaultKey under a new secret. Requires the old secret. */
    suspend fun changeSecret(oldSecret: CharArray, newSecret: CharArray)

    /** Destroys the envelope and the Keystore key. Does not delete encrypted files by itself. */
    suspend fun destroy()

    /** Returns a copy of the persisted envelope, or null if no vault exists. */
    fun envelope(): KeyEnvelope?
}
