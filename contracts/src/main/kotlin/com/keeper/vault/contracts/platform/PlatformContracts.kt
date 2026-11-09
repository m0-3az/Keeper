package com.keeper.vault.contracts.platform

import kotlinx.coroutines.CoroutineDispatcher
import java.io.File

/**
 * Wall-clock source. Production uses [System.currentTimeMillis]; tests use a fake.
 *
 * Time is not a security boundary. Cooldowns and retention must tolerate clock changes.
 */
fun interface Clock {
    fun nowMillis(): Long
}

/**
 * Opaque identifier source. Identifiers are used for vault entries, intruder events, and
 * filenames. They must be unguessable and must not encode user data.
 */
fun interface IdGenerator {
    /** Returns a new opaque identifier. Implementations must use a cryptographic RNG. */
    fun newId(): String
}

/**
 * Cryptographic random source. Production must use [java.security.SecureRandom].
 *
 * Never use [kotlin.random.Random] for keys, salts, nonces, or identifiers that protect data.
 */
fun interface SecureRandomSource {
    /** Returns exactly [size] random bytes. The caller owns and must zero the array. */
    fun nextBytes(size: Int): ByteArray
}

/**
 * Dispatcher injection point. Logic modules must not hard-code `Dispatchers.IO` or
 * `Dispatchers.Default`; tests replace them with deterministic dispatchers.
 */
interface DispatcherProvider {
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
    val main: CoroutineDispatcher
}

/**
 * App-private file locations. Only these locations may hold vault data.
 *
 * There is deliberately no cache or temporary directory accessor: decrypted content must never
 * be written to disk. Vault files are ciphertext; intruder files are ciphertext under the
 * dedicated intruder key.
 */
interface AppFileStore {
    /** Directory for encrypted vault content. Never place plaintext here. */
    fun vaultDirectory(): File

    /** Directory for encrypted intruder images. Never place plaintext here. */
    fun intruderDirectory(): File
}

/**
 * Build facts used by the isolation warning layer.
 *
 * A debuggable build is a warning, not a hard block: the user may be a developer testing the app.
 */
interface BuildInfo {
    val debuggable: Boolean
    val versionName: String
}

/**
 * Best-effort zeroization helpers.
 *
 * JVM memory is copied by the GC, the JIT, decoders, and UI toolkits. These helpers reduce the
 * lifetime of secrets; they cannot guarantee that no copy remains. See `LIMITATIONS.md`.
 */
object SecretZeroizer {
    /** Overwrites every character with NUL. */
    fun zero(chars: CharArray) {
        chars.fill('\u0000')
    }

    /** Overwrites every byte with zero. */
    fun zero(bytes: ByteArray) {
        bytes.fill(0)
    }

    /** Overwrites every element with zero. */
    fun zero(floats: FloatArray) {
        floats.fill(0f)
    }
}

/**
 * Base type for failures that cross a module boundary.
 *
 * Callers must not branch on exception messages for security decisions. Messages are for logs
 * and tests only, and must never contain secret material.
 */
sealed class KeeperException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** Cryptographic failure: bad tag, bad envelope, missing key, or malformed container. */
    class Crypto(message: String, cause: Throwable? = null) : KeeperException(message, cause)

    /** Vault failure: missing entry, corrupt metadata, or storage failure. */
    class Vault(message: String, cause: Throwable? = null) : KeeperException(message, cause)

    /** Authentication failure: policy violation, wrong secret, or lockout. */
    class Auth(message: String, cause: Throwable? = null) : KeeperException(message, cause)

    /** Presence failure: camera unavailable, model failure, or analysis failure. */
    class Presence(message: String, cause: Throwable? = null) : KeeperException(message, cause)

    /** Isolation failure: detector or tamper-log failure. */
    class Isolation(message: String, cause: Throwable? = null) : KeeperException(message, cause)

    /** Duress failure: configuration or decoy-vault failure. */
    class Duress(message: String, cause: Throwable? = null) : KeeperException(message, cause)
}
