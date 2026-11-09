package com.keeper.vault.duress.intruder

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.intruder.IntruderTrigger
import com.keeper.vault.contracts.platform.IdGenerator
import com.keeper.vault.contracts.platform.KeeperException
import com.keeper.vault.crypto.DefaultSecureRandomSource
import com.keeper.vault.duress.FakeIntruderEventStore
import com.keeper.vault.duress.FakeKeystoreEnvelope
import com.keeper.vault.duress.MutableClock
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IntruderLogTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var directory: File
    private lateinit var events: FakeIntruderEventStore
    private lateinit var images: FileIntruderImageStore
    private lateinit var clock: MutableClock
    private lateinit var log: DefaultIntruderLog
    private var nextId = 0

    @Before
    fun setUp() {
        directory = temp.newFolder("intruder")
        events = FakeIntruderEventStore()
        images = FileIntruderImageStore(directory)
        clock = MutableClock(1_700_000_000_000L)
        log = DefaultIntruderLog(
            events = events,
            images = images,
            keys = KeystoreIntruderKeyStore(
                envelope = FakeKeystoreEnvelope(),
                keyFile = File(directory, "key.bin"),
                captureDirectory = directory,
                random = DefaultSecureRandomSource(),
                ioDispatcher = Dispatchers.Unconfined,
            ),
            clock = clock,
            idGenerator = IdGenerator { "event-${nextId++}" },
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @Test
    fun `record encrypts the image and persists exactly one metadata row`() = runTest {
        val plaintext = "JPEG-payload-marker".toByteArray()

        val event = log.record(IntruderTrigger.FAILED_UNLOCK, plaintext)

        assertThat(event.id).isEqualTo("event-0")
        assertThat(event.trigger).isEqualTo(IntruderTrigger.FAILED_UNLOCK)
        assertThat(event.capturedAtMillis).isEqualTo(clock.nowMillis)
        assertThat(event.encryptedFileName).isEqualTo("event-0.enc")
        assertThat(events.rows).containsExactly(event)

        val onDisk = images.read(event.encryptedFileName)
        assertThat(onDisk).isNotEqualTo(plaintext)
        assertThat(onDisk.toString(Charsets.ISO_8859_1)).doesNotContain("JPEG-payload-marker")
    }

    @Test
    fun `readImage decrypts into RAM and rejects unknown ids`() = runTest {
        val plaintext = ByteArray(256) { (it * 3).toByte() }
        val event = log.record(IntruderTrigger.FACE_MISMATCH, plaintext)

        val recovered = log.readImage(event.id)

        assertThat(recovered).isEqualTo(plaintext)

        val failure = runCatching { log.readImage("missing") }.exceptionOrNull()
        assertThat(failure).isInstanceOf(KeeperException.Vault::class.java)
    }

    @Test
    fun `a tampered ciphertext cannot be read`() = runTest {
        val event = log.record(IntruderTrigger.FAILED_UNLOCK, "image".toByteArray())
        val corrupted = images.read(event.encryptedFileName)
        corrupted[corrupted.lastIndex] = 0
        images.write(event.encryptedFileName, corrupted)

        val failure = runCatching { log.readImage(event.id) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(KeeperException.Crypto::class.java)
    }

    @Test
    fun `a metadata failure removes the orphan ciphertext`() = runTest {
        events.failInserts = true

        val failure = runCatching {
            log.record(IntruderTrigger.FAILED_UNLOCK, "image".toByteArray())
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(KeeperException.Vault::class.java)
        assertThat(images.listCiphertext()).isEmpty()
        assertThat(events.rows).isEmpty()
    }

    @Test
    fun `purgeBefore deletes only older ciphertext and metadata`() = runTest {
        clock.nowMillis = 1_000_000L
        val old = log.record(IntruderTrigger.FAILED_UNLOCK, "old".toByteArray())
        clock.nowMillis = 2_000_000L
        val recent = log.record(IntruderTrigger.FAILED_UNLOCK, "recent".toByteArray())

        val removed = log.purgeBefore(cutoffMillis = 1_500_000L)

        assertThat(removed).isEqualTo(1)
        assertThat(events.rows).containsExactly(recent)
        assertThat(images.listCiphertext()).containsExactly(recent.encryptedFileName)
        assertThat(File(directory, old.encryptedFileName).exists()).isFalse()
    }

    @Test
    fun `purgeBefore with no matches is a no-op`() = runTest {
        val event = log.record(IntruderTrigger.FAILED_UNLOCK, "image".toByteArray())

        assertThat(log.purgeBefore(0L)).isEqualTo(0)
        assertThat(events.rows).containsExactly(event)
        assertThat(images.listCiphertext()).containsExactly(event.encryptedFileName)
    }

    @Test
    fun `delete removes one capture and its ciphertext`() = runTest {
        val first = log.record(IntruderTrigger.FAILED_UNLOCK, "one".toByteArray())
        val second = log.record(IntruderTrigger.FAILED_UNLOCK, "two".toByteArray())

        assertThat(log.delete(first.id)).isTrue()

        assertThat(events.rows).containsExactly(second)
        assertThat(images.listCiphertext()).containsExactly(second.encryptedFileName)
        assertThat(log.delete("missing")).isFalse()
    }

    @Test
    fun `deleteAll clears metadata and every ciphertext, including orphans`() = runTest {
        log.record(IntruderTrigger.FAILED_UNLOCK, "one".toByteArray())
        log.record(IntruderTrigger.FACE_MISMATCH, "two".toByteArray())
        File(directory, "orphan.enc").writeBytes(byteArrayOf(1, 2, 3))

        log.deleteAll()

        assertThat(events.rows).isEmpty()
        assertThat(images.listCiphertext()).isEmpty()
    }

    @Test
    fun `recordReserved uses the pre-reserved identity`() = runTest {
        val event = com.keeper.vault.duress.testEvent(
            IntruderTrigger.FAILED_UNLOCK,
            clock.nowMillis,
            id = "reserved",
        )

        val stored = log.recordReserved(event, "reserved-image".toByteArray())

        assertThat(stored).isEqualTo(event)
        assertThat(events.rows).containsExactly(event)
        assertThat(images.listCiphertext()).containsExactly("reserved.enc")
        assertThat(log.readImage("reserved")).isEqualTo("reserved-image".toByteArray())
    }

    @Test
    fun `recordReserved rejects a mismatched file name`() = runTest {
        val event = com.keeper.vault.duress.testEvent(
            IntruderTrigger.FAILED_UNLOCK,
            clock.nowMillis,
            id = "reserved",
        ).copy(encryptedFileName = "somewhere-else.enc")

        val failure = runCatching {
            log.recordReserved(event, "image".toByteArray())
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(events.rows).isEmpty()
        assertThat(images.listCiphertext()).isEmpty()
    }

    @Test
    fun `an empty capture is rejected`() = runTest {
        val failure = runCatching {
            log.record(IntruderTrigger.FAILED_UNLOCK, ByteArray(0))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(events.rows).isEmpty()
    }
}
