package com.keeper.vault.vault.data

import com.keeper.vault.contracts.crypto.VaultKey
import com.keeper.vault.contracts.platform.AppFileStore
import com.keeper.vault.contracts.platform.DispatcherProvider
import com.keeper.vault.contracts.vault.VaultEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** Test-only file store; never writes plaintext. Keeps both contract directories under one root. */
internal class TestAppFileStore(private val root: File) : AppFileStore {
    override fun vaultDirectory(): File = File(root, "vault")

    override fun intruderDirectory(): File = File(root, "intruder")
}

/** In-memory metadata store. Deliberately accepts only [VaultEntry], never content. */
internal class FakeVaultMetadataStore(
    initial: List<VaultEntry> = emptyList(),
) : VaultMetadataStore {
    private val state = MutableStateFlow(initial)

    val entries: List<VaultEntry>
        get() = state.value

    override fun observeEntries(): Flow<List<VaultEntry>> = state

    override suspend fun find(id: String): VaultEntry? =
        state.value.firstOrNull { it.id == id }

    override suspend fun insert(entry: VaultEntry) {
        state.value = state.value.filterNot { it.id == entry.id } + entry
    }

    override suspend fun delete(id: String): Boolean {
        val before = state.value
        state.value = before.filterNot { it.id == id }
        return before.size != state.value.size
    }

    override suspend fun rename(
        id: String,
        displayName: String,
        modifiedAtMillis: Long,
    ): Boolean {
        val existing = state.value.firstOrNull { it.id == id } ?: return false
        state.value = state.value.map {
            if (it.id == id) existing.copy(displayName = displayName, modifiedAtMillis = modifiedAtMillis) else it
        }
        return true
    }
}

internal class TestDispatcherProvider(
    private val dispatcher: CoroutineDispatcher,
) : DispatcherProvider {
    override val io: CoroutineDispatcher = dispatcher
    override val default: CoroutineDispatcher = dispatcher
    override val main: CoroutineDispatcher = dispatcher
}

internal fun testKey(seed: Int = 1): VaultKey =
    VaultKey.from(ByteArray(32) { (it + seed).toByte() })

internal fun vaultFile(root: File, id: String): File = File(File(root, "vault"), "$id.enc")

internal fun markerBytes(size: Int, marker: String = "KEEPER_TEST_MARKER"): ByteArray {
    val data = ByteArray(size) { index -> (index % 251).toByte() }
    marker.toByteArray(Charsets.US_ASCII).copyInto(data, 0, 0, minOf(marker.length, size))
    return data
}
