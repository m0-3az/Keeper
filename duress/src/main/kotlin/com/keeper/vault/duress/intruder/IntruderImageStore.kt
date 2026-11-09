package com.keeper.vault.duress.intruder

import com.keeper.vault.contracts.platform.KeeperException
import java.io.File
import java.io.IOException

/**
 * Ciphertext-only storage for intruder stills.
 *
 * Every byte written here has already passed through [KeystoreIntruderKeyStore] AES-GCM sealing.
 * There is no plaintext temporary file and no cache write. File names are opaque identifiers.
 */
interface IntruderImageStore {
    fun write(fileName: String, ciphertext: ByteArray)

    fun read(fileName: String): ByteArray

    /** True when the file was deleted or did not exist. */
    fun delete(fileName: String): Boolean

    fun listCiphertext(): Set<String>

    /** Deletes every `*.enc` file, including orphans. Returns the number deleted. */
    fun deleteAll(): Int
}

/** File-backed [IntruderImageStore] under `filesDir/intruder`. */
class FileIntruderImageStore(
    private val directory: File,
) : IntruderImageStore {

    override fun write(fileName: String, ciphertext: ByteArray) {
        require(ciphertext.isNotEmpty()) { "Intruder ciphertext must not be empty" }
        val target = fileFor(fileName)
        ensureDirectory()
        try {
            // KEEPER_ENCRYPTED_WRITE: the caller has already sealed the image.
            target.writeBytes(ciphertext)
        } catch (e: IOException) {
            target.delete()
            throw KeeperException.Crypto("Could not write intruder ciphertext", e)
        }
    }

    override fun read(fileName: String): ByteArray {
        val file = fileFor(fileName)
        if (!file.isFile) {
            throw KeeperException.Crypto("Intruder ciphertext not found")
        }
        return try {
            file.readBytes()
        } catch (e: IOException) {
            throw KeeperException.Crypto("Could not read intruder ciphertext", e)
        }
    }

    override fun delete(fileName: String): Boolean {
        val file = fileFor(fileName)
        return !file.exists() || file.delete()
    }

    override fun listCiphertext(): Set<String> {
        val files = directory.listFiles { file ->
            file.isFile && file.name.endsWith(CAPTURE_SUFFIX)
        } ?: return emptySet()
        return files.mapTo(mutableSetOf()) { it.name }
    }

    override fun deleteAll(): Int {
        val files = directory.listFiles { file ->
            file.isFile && file.name.endsWith(CAPTURE_SUFFIX)
        } ?: return 0
        var deleted = 0
        for (file in files) {
            if (file.delete()) deleted++
        }
        return deleted
    }

    private fun ensureDirectory() {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw KeeperException.Crypto("Could not create the intruder directory")
        }
    }

    private fun fileFor(fileName: String): File {
        if (fileName.isEmpty() ||
            !fileName.endsWith(CAPTURE_SUFFIX) ||
            fileName == "." ||
            fileName == ".." ||
            fileName.any { it == '/' || it == '\\' || it.isISOControl() }
        ) {
            throw KeeperException.Crypto("Invalid intruder file name")
        }
        return File(directory, fileName)
    }

    private companion object {
        const val CAPTURE_SUFFIX = ".enc"
    }
}
