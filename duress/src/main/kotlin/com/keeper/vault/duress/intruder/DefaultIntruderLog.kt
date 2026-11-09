package com.keeper.vault.duress.intruder

import com.keeper.vault.contracts.intruder.IntruderEvent
import com.keeper.vault.contracts.intruder.IntruderKeyStore
import com.keeper.vault.contracts.intruder.IntruderLog
import com.keeper.vault.contracts.intruder.IntruderTrigger
import com.keeper.vault.contracts.platform.Clock
import com.keeper.vault.contracts.platform.IdGenerator
import com.keeper.vault.contracts.platform.KeeperException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Writes one encrypted capture and its metadata row. Implemented by [DefaultIntruderLog]. */
interface IntruderRecorder {
    suspend fun record(trigger: IntruderTrigger, plaintextImage: ByteArray): IntruderEvent

    /**
     * Persists an event whose identity was reserved before the foreground service started.
     *
     * The service path uses this so the coordinator can rate-limit a dispatched capture even
     * though the camera result arrives asynchronously. If the service never runs, no metadata row
     * and no ciphertext exist.
     */
    suspend fun recordReserved(event: IntruderEvent, plaintextImage: ByteArray): IntruderEvent
}

/** Destructive operations used by the viewer and settings screens. */
interface IntruderLogAdmin {
    suspend fun delete(eventId: String): Boolean
}

/**
 * Default [IntruderLog].
 *
 * Capture path: seal with the dedicated IntruderKey, write ciphertext, then insert exactly one
 * metadata row. The image is never written in plaintext and never leaves the app.
 *
 * Read path: decrypt into a caller-owned RAM array. The caller must zero it.
 *
 * Purge path: delete ciphertext first, then metadata. If a file cannot be deleted the row is kept
 * so the next daily purge retries; a missing file still clears its row (metadata without
 * ciphertext is useless and must not accumulate).
 */
class DefaultIntruderLog(
    private val events: IntruderEventStore,
    private val images: IntruderImageStore,
    private val keys: IntruderKeyStore,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : IntruderLog, IntruderRecorder, IntruderLogAdmin {

    override fun observe(): Flow<List<IntruderEvent>> = events.observe()

    override suspend fun readImage(eventId: String): ByteArray = withContext(ioDispatcher) {
        val event = events.find(eventId)
            ?: throw KeeperException.Vault("Intruder event not found")
        val ciphertext = images.read(event.encryptedFileName)
        keys.decrypt(ciphertext)
    }

    override suspend fun purgeBefore(cutoffMillis: Long): Int = withContext(ioDispatcher) {
        var removed = 0
        for (event in events.olderThan(cutoffMillis)) {
            try {
                images.delete(event.encryptedFileName)
                if (events.delete(event.id)) removed++
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep the row; the next purge retries the ciphertext deletion.
            }
        }
        removed
    }

    override suspend fun deleteAll() {
        withContext(ioDispatcher) {
            for (event in events.all()) {
                try {
                    images.delete(event.encryptedFileName)
                    events.delete(event.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A failing row must not prevent the remaining captures from being cleared.
                }
            }
            images.deleteAll()
        }
    }

    override suspend fun delete(eventId: String): Boolean = withContext(ioDispatcher) {
        val event = events.find(eventId) ?: return@withContext false
        images.delete(event.encryptedFileName)
        events.delete(event.id)
    }

    override suspend fun record(
        trigger: IntruderTrigger,
        plaintextImage: ByteArray,
    ): IntruderEvent = withContext(ioDispatcher) {
        val id = idGenerator.newId()
        val event = IntruderEvent(
            id = id,
            capturedAtMillis = clock.nowMillis(),
            trigger = trigger,
            encryptedFileName = "$id.enc",
        )
        writeAndInsert(event, plaintextImage)
        event
    }

    override suspend fun recordReserved(
        event: IntruderEvent,
        plaintextImage: ByteArray,
    ): IntruderEvent = withContext(ioDispatcher) {
        writeAndInsert(event, plaintextImage)
        event
    }

    private suspend fun writeAndInsert(event: IntruderEvent, plaintextImage: ByteArray) {
        require(plaintextImage.isNotEmpty()) { "Intruder image must not be empty" }
        require(event.id.isNotBlank()) { "Intruder event id must not be blank" }
        require(event.encryptedFileName == "${event.id}.enc") {
            "Intruder file name must derive from the event id"
        }
        val ciphertext = keys.encrypt(plaintextImage)
        images.write(event.encryptedFileName, ciphertext)
        try {
            events.insert(event)
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            try {
                images.delete(event.encryptedFileName)
            } catch (_: Exception) {
                // The original metadata failure is the useful error; the orphan is ciphertext.
            }
            throw failure
        }
    }
}
