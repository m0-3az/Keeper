package com.keeper.vault.vault.ui

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keeper.vault.contracts.vault.VaultEntry
import com.keeper.vault.contracts.vault.VaultReadSession
import com.keeper.vault.contracts.vault.VaultRepository
import com.keeper.vault.vault.session.VaultSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale

/** Connects the process-wide lock state to the vault screen. */
@Composable
fun VaultRoute(
    repository: VaultRepository,
    onSettingsClick: () -> Unit = {},
) {
    val isUnlocked by VaultSession.unlocked.collectAsStateWithLifecycle()
    VaultScreen(
        repository = repository,
        isUnlocked = isUnlocked,
        onSettingsClick = onSettingsClick,
    )
}

/**
 * Vault list/content UI.
 *
 * Content is materialized into RAM only: text becomes a `String`, images are decoded to a
 * `Bitmap`, and each [VaultReadSession] is closed as soon as those values exist.
 */
@Composable
fun VaultScreen(
    repository: VaultRepository,
    isUnlocked: Boolean,
    onSettingsClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entriesFlow = remember(repository, isUnlocked) {
        if (isUnlocked) repository.observeEntries() else flowOf(emptyList())
    }
    val entries by entriesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var selected by remember { mutableStateOf<VaultEntry?>(null) }
    var pendingDelete by remember { mutableStateOf<VaultEntry?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var manualDeleteName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(isUnlocked) {
        if (!isUnlocked) {
            selected = null
            pendingDelete = null
        }
    }

    val openDocument = rememberLauncherForActivityResult(OpenDocumentForMove()) { uri ->
        if (uri != null) {
            importing = true
            scope.launch {
                try {
                    val resolver = context.contentResolver
                    val displayName = resolver.displayName(uri) ?: "Imported file"
                    val mimeType = resolver.getType(uri) ?: "application/octet-stream"
                    val input = resolver.openInputStream(uri)
                        ?: throw IOException("The selected document has no stream")
                    // The stream is fully copied and closed before the source is touched. If the
                    // import fails, the original document is deliberately left untouched.
                    input.use { repository.importFile(displayName, mimeType, it) }
                    errorMessage = null
                    if (deleteSourceDocument(context, resolver, uri)) {
                        Toast.makeText(
                            context,
                            "File moved into the vault.",
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        manualDeleteName = displayName
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    errorMessage = if (VaultSession.isUnlocked()) {
                        "Import failed. The file was not added."
                    } else {
                        "The vault locked before the import finished. Unlock Keeper and try again."
                    }
                } finally {
                    importing = false
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag("vault_screen")
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingsButton(onClick = onSettingsClick)
            Spacer(Modifier.width(4.dp))
            Text(
                text = "Keeper Vault",
                style = MaterialTheme.typography.headlineMedium,
            )
        }

        if (isUnlocked) {
            Button(
                onClick = {
                    try {
                        openDocument.launch(arrayOf("*/*"))
                    } catch (_: ActivityNotFoundException) {
                        // Minimal AOSP images and some vendor ROMs ship without a
                        // DocumentsUI/SAF picker. Import is unavailable, but the vault is not.
                        errorMessage =
                            "No system file picker is available on this device. Install a " +
                                "Files/Documents app and try again."
                    } catch (_: SecurityException) {
                        errorMessage = "The system file picker could not be opened."
                    }
                },
                enabled = !importing,
                modifier = Modifier.testTag("import_button"),
            ) {
                Text(if (importing) "Importing..." else "Import file")
            }
        }

        errorMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("vault_error"),
            )
        }

        when {
            !isUnlocked -> LockedState()
            entries.isEmpty() -> EmptyState()
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
            ) {
                items(entries, key = { it.id }) { entry ->
                    EntryRow(
                        entry = entry,
                        onOpen = { selected = entry },
                        onDelete = { pendingDelete = entry },
                    )
                }
            }
        }
    }

    selected?.let { entry ->
        ViewerDialog(
            entry = entry,
            repository = repository,
            onClose = { selected = null },
        )
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete entry?") },
            text = {
                Text(
                    "This removes the encrypted file and its metadata from Keeper. " +
                        "The original file outside the vault is unchanged.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        scope.launch {
                            try {
                                repository.delete(entry.id)
                                errorMessage = null
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                errorMessage = "Delete failed. The entry is still in the vault."
                            }
                        }
                    },
                    modifier = Modifier.testTag("confirm_delete"),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    manualDeleteName?.let { name ->
        AlertDialog(
            onDismissRequest = { manualDeleteName = null },
            modifier = Modifier.testTag("manual_delete_dialog"),
            title = { Text("File imported") },
            text = {
                Text(
                    "\"$name\" is now encrypted in the vault, but Android did not allow Keeper " +
                        "to delete the original document. Please delete it manually from your " +
                        "file manager.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { manualDeleteName = null },
                    modifier = Modifier.testTag("manual_delete_ack"),
                ) {
                    Text("OK")
                }
            },
        )
    }
}

/**
 * Opens a document with both read and write access.
 *
 * [ActivityResultContracts.OpenDocument] only asks for read access, so a provider is allowed to
 * reject [DocumentsContract.deleteDocument] even when it supports deletion. Requesting the write
 * grant up front lets "Import" behave as a move for local providers.
 */
private class OpenDocumentForMove : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
}

/**
 * Deletes the imported source document.
 *
 * Returns false when the provider is read-only, does not implement deletion, or revokes the
 * write grant. Callers must tell the user to delete the original manually; the vault copy is
 * already committed at this point.
 */
internal fun deleteSourceDocument(
    context: Context,
    resolver: ContentResolver,
    uri: Uri,
): Boolean = try {
    if (DocumentsContract.isDocumentUri(context, uri)) {
        DocumentsContract.deleteDocument(resolver, uri)
    } else {
        resolver.delete(uri, null, null) > 0
    }
} catch (error: Exception) {
    // Read-only providers (cloud drives, media stores) legitimately refuse deletion. Log the
    // reason so a device report can distinguish that from a missing write grant or a bug.
    Log.w(TAG, "Source document not deleted (${error.javaClass.simpleName})", error)
    false
}

@Composable
private fun LockedState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("vault_locked"),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Vault is locked", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Unlock Keeper to view your files.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("vault_empty"),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Vault is empty", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Import a file to encrypt it into this vault.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun EntryRow(
    entry: VaultEntry,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("entry_${entry.id}")
                .clickable(onClick = onOpen)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${formatSize(entry.sizeBytes)}  ·  ${entry.mimeType}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = onDelete,
                modifier = Modifier.semantics {
                    contentDescription = "Delete ${entry.displayName}"
                },
            ) {
                Text("Delete")
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun ViewerDialog(
    entry: VaultEntry,
    repository: VaultRepository,
    onClose: () -> Unit,
) {
    var state by remember(entry.id) { mutableStateOf<ViewerState>(ViewerState.Loading) }
    var openSession by remember(entry.id) { mutableStateOf<VaultReadSession?>(null) }

    DisposableEffect(entry.id) {
        onDispose {
            openSession?.close()
            openSession = null
            (state as? ViewerState.Image)?.bitmap?.recycle()
        }
    }

    LaunchedEffect(entry.id) {
        try {
            val session = repository.open(entry.id)
            openSession = session
            state = try {
                withContext(Dispatchers.Default) { materialize(entry, session) }
            } finally {
                session.close()
                if (openSession === session) {
                    openSession = null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            state = ViewerState.Error
        }
    }

    Dialog(onDismissRequest = onClose) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("viewer_dialog"),
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = entry.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                when (val current = state) {
                    ViewerState.Loading -> Box(
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }

                    is ViewerState.Text -> Text(
                        text = current.value,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .heightIn(max = 480.dp)
                            .verticalScroll(rememberScrollState())
                            .testTag("viewer_text"),
                    )

                    is ViewerState.Image -> Image(
                        bitmap = current.bitmap.asImageBitmap(),
                        contentDescription = entry.displayName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 480.dp)
                            .testTag("image_preview"),
                    )

                    ViewerState.Unsupported -> Text(
                        "This file type has no in-app preview.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("viewer_unsupported"),
                    )

                    ViewerState.Error -> Text(
                        "This entry could not be opened.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("viewer_error"),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = onClose,
                        modifier = Modifier.testTag("close_viewer"),
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

private fun materialize(entry: VaultEntry, session: VaultReadSession): ViewerState {
    val mime = entry.mimeType.lowercase(Locale.US)
    return when {
        mime.startsWith("image/") -> {
            val bitmap = BitmapFactory.decodeStream(session.plaintext)
            if (bitmap == null) ViewerState.Error else ViewerState.Image(bitmap)
        }

        mime.startsWith("text/") || mime == "application/json" || mime == "application/xml" -> {
            val text = session.plaintext.bufferedReader(Charsets.UTF_8).use { it.readText() }
            ViewerState.Text(text)
        }

        else -> ViewerState.Unsupported
    }
}

private sealed interface ViewerState {
    data object Loading : ViewerState
    data class Text(val value: String) : ViewerState
    data class Image(val bitmap: Bitmap) : ViewerState
    data object Unsupported : ViewerState
    data object Error : ViewerState
}

private fun ContentResolver.displayName(uri: Uri): String? =
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) {
            cursor.getString(index)
        } else {
            null
        }
    }

private const val TAG: String = "VaultImport"

private fun formatSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
