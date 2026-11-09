package com.keeper.vault.isolation

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.isolation.TamperKind
import com.keeper.vault.contracts.isolation.TamperSignal
import com.keeper.vault.contracts.platform.KeeperException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test
import java.util.Base64

class DurableTamperLogTest {

    @Test
    fun `observe is empty before anything is appended`() = runTest {
        val log = log()

        assertThat(log.observe().first()).isEmpty()
    }

    @Test
    fun `append is visible to an existing observer`() = runTest {
        val log = log()
        val observed = mutableListOf<List<TamperSignal>>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            log.observe().collect { observed += it }
        }
        yield()
        assertThat(observed.last()).isEmpty()

        log.append(signal(TamperKind.ROOT, 10, "su"))

        assertThat(observed.last()).hasSize(1)
        assertThat(observed.last().single().kind).isEqualTo(TamperKind.ROOT)
        job.cancel()
    }

    @Test
    fun `append persists across log instances`() = runTest {
        val store = FakeStore()
        val first = log(store)
        first.append(signal(TamperKind.ROOT, 10, "su"))
        first.append(signal(TamperKind.EMULATOR, 20, "goldfish"))

        val reopened = log(store)
        val entries = reopened.observe().first()

        assertThat(entries).hasSize(2)
        assertThat(entries.map { it.kind }).containsExactly(TamperKind.ROOT, TamperKind.EMULATOR).inOrder()
        assertThat(entries.map { it.detectedAtMillis }).containsExactly(10L, 20L).inOrder()
        assertThat(entries.map { it.detail }).containsExactly("su", "goldfish").inOrder()
    }

    @Test
    fun `clear removes entries from memory and storage`() = runTest {
        val store = FakeStore()
        val log = log(store)
        log.append(signal(TamperKind.HOOK_FRAMEWORK, 10, "frida"))

        log.clear()

        assertThat(log.observe().first()).isEmpty()
        assertThat(store.value).isNull()
        assertThat(log(store).observe().first()).isEmpty()
    }

    @Test
    fun `clear works when the log was never loaded`() = runTest {
        val store = FakeStore(value = "KPRT1\nROOT\t10\t${b64("su")}\n")

        log(store).clear()

        assertThat(store.value).isNull()
    }

    @Test
    fun `append after clear starts from an empty log`() = runTest {
        val store = FakeStore()
        val log = log(store)
        log.append(signal(TamperKind.ROOT, 10, "su"))
        log.clear()
        log.append(signal(TamperKind.DEBUGGER, 30, "attached"))

        val entries = log(store).observe().first()
        assertThat(entries).hasSize(1)
        assertThat(entries.single().kind).isEqualTo(TamperKind.DEBUGGER)
    }

    @Test
    fun `malformed stored records are skipped instead of losing the whole log`() = runTest {
        val store = FakeStore(
            value = buildString {
                appendLine("KPRT1")
                appendLine("ROOT\t10\t${b64("su")}")
                appendLine("NOT_A_KIND\t20\t")
                appendLine("ROOT\tnot-a-timestamp\t")
                appendLine("EMULATOR\t30\t@@@not-base64@@@")
                appendLine("BROKEN")
                appendLine("")
                appendLine("HOOK_FRAMEWORK\t40\t${b64("frida")}")
            },
        )

        val entries = log(store).observe().first()

        assertThat(entries.map { it.kind }).containsExactly(TamperKind.ROOT, TamperKind.HOOK_FRAMEWORK).inOrder()
        assertThat(entries.map { it.detail }).containsExactly("su", "frida").inOrder()
    }

    @Test
    fun `detail with tabs and newlines survives a round trip`() = runTest {
        val store = FakeStore()
        val detail = "line one\nline two\twith tab"
        log(store).append(signal(TamperKind.ACCESSIBILITY_SERVICE, 10, detail))

        val entries = log(store).observe().first()

        assertThat(entries.single().detail).isEqualTo(detail)
    }

    @Test
    fun `null detail survives a round trip`() = runTest {
        val store = FakeStore()
        log(store).append(signal(TamperKind.UNKNOWN, 10, null))

        assertThat(log(store).observe().first().single().detail).isNull()
    }

    @Test
    fun `the log is bounded and keeps the newest entries`() = runTest {
        val log = log(maxEntries = 3)

        log.append(signal(TamperKind.ROOT, 1, "one"))
        log.append(signal(TamperKind.ROOT, 2, "two"))
        log.append(signal(TamperKind.ROOT, 3, "three"))
        log.append(signal(TamperKind.ROOT, 4, "four"))

        val entries = log.observe().first()
        assertThat(entries.map { it.detectedAtMillis }).containsExactly(2L, 3L, 4L).inOrder()
    }

    @Test
    fun `long details are truncated to metadata size`() = runTest {
        val store = FakeStore()
        log(store).append(signal(TamperKind.UNKNOWN, 10, "x".repeat(2_000)))

        assertThat(log(store).observe().first().single().detail).hasLength(DurableTamperLog.MAX_DETAIL_LENGTH)
    }

    @Test
    fun `observe loads the store only once`() = runTest {
        val store = FakeStore(value = "KPRT1\nROOT\t10\t${b64("su")}\n")
        val log = log(store)

        log.observe().first()
        log.observe().first()

        assertThat(store.readCount).isEqualTo(1)
    }

    @Test
    fun `read failures surface as isolation errors`() = runTest {
        val store = FakeStore(readFailure = IllegalStateException("disk"))

        val error = runCatching { log(store).observe().first() }.exceptionOrNull()

        assertThat(error).isInstanceOf(KeeperException.Isolation::class.java)
    }

    @Test
    fun `write failures surface as isolation errors and leave memory unchanged`() = runTest {
        val store = FakeStore()
        val log = log(store)
        store.writeFailure = IllegalStateException("disk")

        val error = runCatching { log.append(signal(TamperKind.ROOT, 10, "su")) }.exceptionOrNull()

        assertThat(error).isInstanceOf(KeeperException.Isolation::class.java)
        assertThat(log.observe().first()).isEmpty()
    }

    @Test
    fun `clear failures surface as isolation errors and keep entries`() = runTest {
        val store = FakeStore()
        val log = log(store)
        log.append(signal(TamperKind.ROOT, 10, "su"))
        store.clearFailure = IllegalStateException("disk")

        val error = runCatching { log.clear() }.exceptionOrNull()

        assertThat(error).isInstanceOf(KeeperException.Isolation::class.java)
        assertThat(log.observe().first()).hasSize(1)
    }

    @Test
    fun `stored payload contains metadata only and no raw detail`() = runTest {
        val store = FakeStore()
        log(store).append(signal(TamperKind.ROOT, 10, "super-secret-detail"))

        assertThat(store.value).doesNotContain("super-secret-detail")
    }

    private fun kotlinx.coroutines.test.TestScope.log(
        store: FakeStore = FakeStore(),
        maxEntries: Int = 500,
    ): DurableTamperLog = DurableTamperLog(
        store = store,
        dispatcher = UnconfinedTestDispatcher(testScheduler),
        maxEntries = maxEntries,
    )

    private fun signal(kind: TamperKind, at: Long, detail: String?) =
        TamperSignal(kind = kind, detectedAtMillis = at, detail = detail)

    private fun b64(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    private class FakeStore(
        var value: String? = null,
        var readFailure: Exception? = null,
        var writeFailure: Exception? = null,
        var clearFailure: Exception? = null,
    ) : TamperLogStore {
        var readCount: Int = 0
            private set

        override suspend fun read(): String? {
            readCount++
            readFailure?.let { throw it }
            return value
        }

        override suspend fun write(value: String) {
            writeFailure?.let { throw it }
            this.value = value
        }

        override suspend fun clear() {
            clearFailure?.let { throw it }
            value = null
        }
    }
}
