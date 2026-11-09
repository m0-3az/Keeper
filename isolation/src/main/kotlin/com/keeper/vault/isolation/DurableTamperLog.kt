package com.keeper.vault.isolation

import com.keeper.vault.contracts.isolation.TamperKind
import com.keeper.vault.contracts.isolation.TamperLog
import com.keeper.vault.contracts.isolation.TamperSignal
import com.keeper.vault.contracts.platform.KeeperException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Base64

/**
 * Durable, bounded, append-only tamper log.
 *
 * Format (`KPRT1`):
 * ```
 * KPRT1
 * KIND<TAB>timestampMillis<TAB>base64(detail)
 * ```
 * Base64 keeps tabs, newlines, and non-ASCII detail from breaking the record format. The decoder
 * skips malformed records rather than discarding the whole log, so one bad line cannot erase
 * evidence.
 *
 * The log is bounded at [DEFAULT_MAX_ENTRIES] entries. The oldest entries are dropped first; an
 * unbounded log in SharedPreferences would eventually become a denial-of-service on app start.
 *
 * All operations are serialized with a mutex. Append writes to storage before publishing the new
 * list, so an observer never sees an entry that was not persisted. Failures are surfaced as
 * [KeeperException.Isolation]; they are not logged with secret data and they do not silently drop
 * the in-memory state.
 */
class DurableTamperLog(
    private val store: TamperLogStore,
    private val dispatcher: CoroutineDispatcher,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) : TamperLog {

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    private val mutex = Mutex()
    private val entries = MutableStateFlow<List<TamperSignal>>(emptyList())
    private var loaded: Boolean = false

    override suspend fun append(signal: TamperSignal) {
        withContext(dispatcher) {
            mutex.withLock {
                val current = loadLocked()
                val next = (current + normalize(signal)).takeLast(maxEntries)
                persist(next)
                entries.value = next
            }
        }
    }

    override fun observe(): Flow<List<TamperSignal>> = flow {
        load()
        emitAll(entries)
    }

    override suspend fun clear() {
        withContext(dispatcher) {
            mutex.withLock {
                try {
                    store.clear()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    throw KeeperException.Isolation("Could not clear the tamper log", error)
                }
                loaded = true
                entries.value = emptyList()
            }
        }
    }

    private suspend fun load(): List<TamperSignal> = withContext(dispatcher) {
        mutex.withLock { loadLocked() }
    }

    private suspend fun loadLocked(): List<TamperSignal> {
        if (loaded) return entries.value

        val raw = try {
            store.read()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw KeeperException.Isolation("Could not read the tamper log", error)
        }

        val decoded = TamperLogCodec.decode(raw).takeLast(maxEntries)
        entries.value = decoded
        loaded = true
        return decoded
    }

    private suspend fun persist(value: List<TamperSignal>) {
        try {
            store.write(TamperLogCodec.encode(value))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw KeeperException.Isolation("Could not write the tamper log", error)
        }
    }

    private fun normalize(signal: TamperSignal): TamperSignal =
        signal.copy(detail = signal.detail?.take(MAX_DETAIL_LENGTH))

    companion object {
        const val DEFAULT_MAX_ENTRIES: Int = 500
        const val MAX_DETAIL_LENGTH: Int = 256
    }
}

/**
 * Line-oriented codec for [DurableTamperLog].
 *
 * It uses `java.util.Base64` (available on minSdk 26) instead of `android.util.Base64` so the
 * codec is exercised by fast JVM tests.
 */
internal object TamperLogCodec {
    private const val HEADER = "KPRT1"
    private const val FIELD_SEPARATOR = '\t'
    private const val FIELD_LIMIT = 3

    fun encode(entries: List<TamperSignal>): String = buildString {
        append(HEADER).append('\n')
        for (entry in entries) {
            append(entry.kind.name)
            append(FIELD_SEPARATOR)
            append(entry.detectedAtMillis)
            append(FIELD_SEPARATOR)
            entry.detail?.let { detail ->
                append(Base64.getEncoder().encodeToString(detail.toByteArray(Charsets.UTF_8)))
            }
            append('\n')
        }
    }

    fun decode(raw: String?): List<TamperSignal> {
        if (raw.isNullOrBlank()) return emptyList()

        val lines = raw.lineSequence().filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return emptyList()
        val records = if (lines.first() == HEADER) lines.drop(1) else lines
        return records.mapNotNull(::decodeLine)
    }

    private fun decodeLine(line: String): TamperSignal? = runCatching {
        val fields = line.split(FIELD_SEPARATOR, limit = FIELD_LIMIT)
        if (fields.size != FIELD_LIMIT) return@runCatching null

        val kind = TamperKind.entries.firstOrNull { it.name == fields[0] } ?: return@runCatching null
        val detectedAtMillis = fields[1].toLongOrNull() ?: return@runCatching null
        val detail = if (fields[2].isEmpty()) {
            null
        } else {
            String(Base64.getDecoder().decode(fields[2]), Charsets.UTF_8)
        }

        TamperSignal(
            kind = kind,
            detectedAtMillis = detectedAtMillis,
            detail = detail,
        )
    }.getOrNull()
}
