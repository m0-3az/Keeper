package com.keeper.vault.crypto

import com.keeper.vault.contracts.crypto.CryptoConstants
import com.keeper.vault.contracts.crypto.KeyEnvelope
import com.keeper.vault.contracts.platform.KeeperException
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * File-backed envelope store.
 *
 * Format: `KPE1 | version[1] | saltLen[1] | salt | iterations[4] | wrappedLen[4] | wrapped`.
 * The payload is Keystore-wrapped ciphertext, never vault content. The temp+rename write keeps a
 * crash from leaving a half-written envelope.
 */
class FileEnvelopeStore(
    private val baseDir: File,
) : EnvelopeStore {
    private val envelopeFile = File(baseDir, FILE_NAME)
    private val tempFile = File(baseDir, TEMP_NAME)

    override fun load(): KeyEnvelope? {
        if (!envelopeFile.exists()) return null
        try {
            DataInputStream(BufferedInputStream(FileInputStream(envelopeFile))).use { input ->
                val magic = ByteArray(MAGIC.size)
                input.readFully(magic)
                if (!magic.contentEquals(MAGIC)) {
                    throw KeeperException.Crypto("Corrupt envelope magic")
                }
                val version = input.readUnsignedByte()
                if (version != VERSION) {
                    throw KeeperException.Crypto("Unsupported envelope version")
                }
                val saltLength = input.readUnsignedByte()
                if (saltLength != CryptoConstants.SALT_BYTES) {
                    throw KeeperException.Crypto("Corrupt envelope salt")
                }
                val salt = ByteArray(saltLength)
                input.readFully(salt)
                val iterations = input.readInt()
                if (iterations < CryptoConstants.MIN_PBKDF2_ITERATIONS) {
                    throw KeeperException.Crypto("Corrupt envelope iterations")
                }
                val wrappedLength = input.readInt()
                if (wrappedLength <= 0 || wrappedLength > MAX_WRAPPED_BYTES) {
                    throw KeeperException.Crypto("Corrupt envelope length")
                }
                val wrapped = ByteArray(wrappedLength)
                input.readFully(wrapped)
                if (input.read() != -1) {
                    throw KeeperException.Crypto("Trailing envelope bytes")
                }
                return KeyEnvelope(version, salt, iterations, wrapped)
            }
        } catch (e: KeeperException.Crypto) {
            throw e
        } catch (e: EOFException) {
            throw KeeperException.Crypto("Truncated envelope", e)
        } catch (e: IOException) {
            throw KeeperException.Crypto("Could not read envelope", e)
        } catch (e: IllegalArgumentException) {
            throw KeeperException.Crypto("Invalid envelope", e)
        }
    }

    override fun save(envelope: KeyEnvelope) {
        if (!baseDir.exists() && !baseDir.mkdirs()) {
            throw KeeperException.Crypto("Could not create envelope directory")
        }
        // KEEPER_ENCRYPTED_WRITE: only the Keystore-wrapped envelope is written here.
        val stream = FileOutputStream(tempFile)
        try {
            val out = DataOutputStream(BufferedOutputStream(stream))
            out.write(MAGIC)
            out.writeByte(VERSION)
            out.writeByte(envelope.salt.size)
            out.write(envelope.salt)
            out.writeInt(envelope.iterations)
            out.writeInt(envelope.wrappedLayerA.size)
            out.write(envelope.wrappedLayerA)
            out.flush()
            stream.fd.sync()
            out.close()
        } catch (e: IOException) {
            stream.close()
            tempFile.delete()
            throw KeeperException.Crypto("Could not write envelope", e)
        }

        if (!tempFile.renameTo(envelopeFile)) {
            if (envelopeFile.exists() && !envelopeFile.delete()) {
                tempFile.delete()
                throw KeeperException.Crypto("Could not replace envelope")
            }
            if (!tempFile.renameTo(envelopeFile)) {
                tempFile.delete()
                throw KeeperException.Crypto("Could not install envelope")
            }
        }
    }

    override fun clear() {
        if (envelopeFile.exists() && !envelopeFile.delete()) {
            throw KeeperException.Crypto("Could not delete envelope")
        }
        if (tempFile.exists() && !tempFile.delete()) {
            throw KeeperException.Crypto("Could not delete envelope temp file")
        }
    }

    private companion object {
        const val FILE_NAME = "envelope.bin"
        const val TEMP_NAME = "envelope.tmp"
        const val VERSION = 1
        const val MAX_WRAPPED_BYTES = 1024 * 1024
        val MAGIC = "KPE1".toByteArray(Charsets.US_ASCII)
    }
}
