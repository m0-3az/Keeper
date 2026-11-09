package com.keeper.vault.crypto

import com.keeper.vault.contracts.crypto.CryptoConstants
import com.keeper.vault.contracts.crypto.VaultCipher
import com.keeper.vault.contracts.crypto.VaultKey
import com.keeper.vault.contracts.platform.KeeperException
import com.keeper.vault.contracts.platform.SecretZeroizer
import com.keeper.vault.contracts.platform.SecureRandomSource
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Streaming AES-256-GCM file cipher.
 *
 * Container v1: `KPR1 | version[1] | nonce[12] | ciphertext | tag[16]`.
 *
 * Encryption streams ciphertext to the caller. Decryption buffers plaintext in a zeroable
 * in-memory buffer and writes it to the caller only after the GCM tag verifies. This prevents
 * unauthenticated plaintext from escaping on tamper, at the cost of holding the plaintext in RAM
 * (which the brief requires anyway). The 256 MiB cap is a safety valve, not a vault limit.
 */
class AesGcmVaultCipher(
    private val random: SecureRandomSource = DefaultSecureRandomSource(),
) : VaultCipher {
    override fun encrypt(plaintext: InputStream, ciphertext: OutputStream, key: VaultKey) {
        val keyBytes = key.copyBytes()
        try {
            val nonce = random.nextBytes(CryptoConstants.GCM_NONCE_BYTES)
            val header = header(nonce)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                GCMParameterSpec(CryptoConstants.GCM_TAG_BITS, nonce),
            )
            cipher.updateAAD(header)
            ciphertext.write(header)

            val buffer = ByteArray(CryptoConstants.STREAM_CHUNK_BYTES)
            while (true) {
                val read = plaintext.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                val chunk = cipher.update(buffer, 0, read)
                if (chunk != null && chunk.isNotEmpty()) {
                    ciphertext.write(chunk)
                }
            }
            val tail = cipher.doFinal()
            if (tail.isNotEmpty()) {
                ciphertext.write(tail)
            }
        } catch (e: GeneralSecurityException) {
            throw KeeperException.Crypto("Encryption failed", e)
        } finally {
            SecretZeroizer.zero(keyBytes)
        }
    }

    override fun decrypt(ciphertext: InputStream, plaintext: OutputStream, key: VaultKey) {
        val keyBytes = key.copyBytes()
        val buffer = ZeroableBuffer()
        try {
            val header = ByteArray(HEADER_SIZE)
            if (!readFully(ciphertext, header)) {
                throw KeeperException.Crypto("Invalid container")
            }
            if (!header.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
                throw KeeperException.Crypto("Invalid container magic")
            }
            if (header[MAGIC.size].toInt() != CryptoConstants.CONTAINER_VERSION) {
                throw KeeperException.Crypto("Unsupported container version")
            }
            val nonce = header.copyOfRange(MAGIC.size + 1, HEADER_SIZE)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                GCMParameterSpec(CryptoConstants.GCM_TAG_BITS, nonce),
            )
            cipher.updateAAD(header)

            val chunk = ByteArray(CryptoConstants.STREAM_CHUNK_BYTES)
            while (true) {
                val read = ciphertext.read(chunk)
                if (read < 0) break
                if (read == 0) continue
                val decrypted = cipher.update(chunk, 0, read)
                if (decrypted != null && decrypted.isNotEmpty()) {
                    append(buffer, decrypted)
                }
            }
            val tail = cipher.doFinal()
            if (tail.isNotEmpty()) {
                append(buffer, tail)
            }

            val result = buffer.toByteArray()
            try {
                plaintext.write(result)
            } finally {
                SecretZeroizer.zero(result)
            }
        } catch (e: AEADBadTagException) {
            throw KeeperException.Crypto("Authentication failed")
        } catch (e: GeneralSecurityException) {
            throw KeeperException.Crypto("Authentication failed")
        } finally {
            SecretZeroizer.zero(keyBytes)
            buffer.zero()
        }
    }

    private fun append(buffer: ZeroableBuffer, chunk: ByteArray) {
        if (buffer.size + chunk.size > MAX_PLAINTEXT_BYTES) {
            SecretZeroizer.zero(chunk)
            throw KeeperException.Crypto("Plaintext exceeds the in-memory decrypt limit")
        }
        buffer.write(chunk)
        SecretZeroizer.zero(chunk)
    }

    private fun header(nonce: ByteArray): ByteArray {
        val header = ByteArray(HEADER_SIZE)
        MAGIC.copyInto(header, 0)
        header[MAGIC.size] = CryptoConstants.CONTAINER_VERSION.toByte()
        nonce.copyInto(header, MAGIC.size + 1)
        return header
    }

    private fun readFully(input: InputStream, target: ByteArray): Boolean {
        var offset = 0
        while (offset < target.size) {
            val read = input.read(target, offset, target.size - offset)
            if (read < 0) return false
            if (read == 0) continue
            offset += read
        }
        return true
    }

    /** Zeroable in-memory accumulator. Never writes to disk. */
    private class ZeroableBuffer {
        private var data = ByteArray(CryptoConstants.STREAM_CHUNK_BYTES)
        var size: Int = 0
            private set

        fun write(source: ByteArray) {
            ensureCapacity(size + source.size)
            source.copyInto(data, size)
            size += source.size
        }

        fun toByteArray(): ByteArray = data.copyOf(size)

        fun zero() {
            data.fill(0)
            size = 0
        }

        private fun ensureCapacity(required: Int) {
            if (required <= data.size) return
            var next = data.size
            while (next < required) {
                next *= 2
            }
            data = data.copyOf(next)
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val HEADER_SIZE = 4 + 1 + CryptoConstants.GCM_NONCE_BYTES
        const val MAX_PLAINTEXT_BYTES = 256 * 1024 * 1024
        val MAGIC = CryptoConstants.CONTAINER_MAGIC.toByteArray(Charsets.US_ASCII)
    }
}
