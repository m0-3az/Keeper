package com.keeper.vault.vault.data

import com.keeper.vault.contracts.crypto.VaultCipher
import com.keeper.vault.contracts.crypto.VaultKey
import com.keeper.vault.contracts.platform.AppFileStore
import com.keeper.vault.contracts.platform.KeeperException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Encrypted file store.
 *
 * Ciphertext only: every byte written under `filesDir/vault` has passed through [VaultCipher].
 * Reads decrypt into a zeroable RAM buffer; no cache or temporary plaintext file is ever created.
 */
class EncryptedVaultFileStore(
    private val files: AppFileStore,
    private val cipher: VaultCipher,
) : VaultFileStore {
    override fun write(id: String, plaintext: InputStream, key: VaultKey): Long {
        val target = fileFor(id)
        val directory = ensureVaultDirectory()
        if (target.parentFile?.canonicalFile != directory.canonicalFile) {
            throw KeeperException.Vault("Invalid entry identifier")
        }
        val counting = CountingInputStream(plaintext)
        try {
            // KEEPER_ENCRYPTED_WRITE: content is encrypted by VaultCipher before it reaches disk.
            // There is no plaintext temporary file or cache write in this path.
            FileOutputStream(target).use { output ->
                cipher.encrypt(counting, output, key)
                output.fd.sync()
            }
            return counting.count
        } catch (e: KeeperException) {
            target.delete()
            throw e
        } catch (e: IOException) {
            target.delete()
            throw KeeperException.Vault("Could not write encrypted entry", e)
        } catch (e: RuntimeException) {
            target.delete()
            throw e
        }
    }

    override fun read(id: String, key: VaultKey): ByteArray {
        val file = fileFor(id)
        if (!file.isFile) {
            throw KeeperException.Vault("Encrypted entry not found")
        }
        val output = ZeroableByteArrayOutputStream()
        try {
            file.inputStream().use { input ->
                cipher.decrypt(input, output, key)
            }
            val plaintext = output.toByteArray()
            output.zero()
            return plaintext
        } catch (e: KeeperException) {
            output.zero()
            throw e
        } catch (e: IOException) {
            output.zero()
            throw KeeperException.Vault("Could not read encrypted entry", e)
        } catch (e: RuntimeException) {
            output.zero()
            throw e
        }
    }

    override fun delete(id: String): Boolean {
        val file = fileFor(id)
        return !file.exists() || file.delete()
    }

    override fun exists(id: String): Boolean = fileFor(id).isFile

    private fun fileFor(id: String): File {
        if (id.isEmpty() || id == "." || id == ".." || id.any { it == '/' || it == '\\' || it.isISOControl() }) {
            throw KeeperException.Vault("Invalid entry identifier")
        }
        return File(files.vaultDirectory(), "$id.enc")
    }

    private fun ensureVaultDirectory(): File {
        val directory = files.vaultDirectory()
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw KeeperException.Vault("Could not create the vault directory")
        }
        return directory
    }

    /** Counts plaintext bytes without buffering them; the cipher streams to disk. */
    private class CountingInputStream(
        private val delegate: InputStream,
    ) : InputStream() {
        var count: Long = 0L
            private set

        override fun read(): Int {
            val value = delegate.read()
            if (value >= 0) count++
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = delegate.read(buffer, offset, length)
            if (read > 0) count += read
            return read
        }

        override fun close() {
            delegate.close()
        }
    }

    /** ByteArrayOutputStream whose internal buffer can be zeroed after the copy is handed out. */
    private class ZeroableByteArrayOutputStream : ByteArrayOutputStream() {
        fun zero() {
            buf.fill(0)
            reset()
        }
    }
}
