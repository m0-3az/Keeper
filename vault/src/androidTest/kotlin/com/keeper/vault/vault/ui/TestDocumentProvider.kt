package com.keeper.vault.vault.ui

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * Minimal DocumentsProvider backed by a cache directory.
 *
 * The AOSP ATD emulator has no DocumentsUI, so the SAF picker cannot be driven end-to-end there.
 * This provider lets the delete path run against the real platform implementation of
 * [android.provider.DocumentsContract.deleteDocument].
 */
class TestDocumentProvider : DocumentsProvider() {
    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = (projection ?: arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS,
            Root.COLUMN_MIME_TYPES,
        )).map { it }.toTypedArray()
        return MatrixCursor(columns).apply {
            newRow()
                .add(Root.COLUMN_ROOT_ID)
                .add(ROOT_ID)
                .add("Keeper test documents")
                .add(Root.FLAG_LOCAL_ONLY)
                .add("*/*")
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val file = resolve(documentId)
        if (!file.exists()) throw FileNotFoundException(documentId)
        return documentCursor(file, documentId, projection)
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val parent = resolve(parentDocumentId)
        val columns = (projection ?: DEFAULT_DOCUMENT_COLUMNS).map { it }.toTypedArray()
        val cursor = MatrixCursor(columns)
        parent.listFiles()?.sortedBy { it.name }?.forEach { file ->
            val row = cursor.newRow()
            columns.forEach { column ->
                row.add(valueFor(file, file.name, column))
            }
        }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val file = resolve(documentId)
        if (!file.isFile) throw FileNotFoundException(documentId)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        val parent = resolve(parentDocumentId)
        val file = File(parent, displayName)
        if (!file.createNewFile()) throw FileNotFoundException(displayName)
        return displayName
    }

    override fun deleteDocument(documentId: String) {
        val file = resolve(documentId)
        android.util.Log.i(
            "TestDocProvider",
            "deleteDocument(id=$documentId) root=$rootDir file=$file exists=${file.exists()}",
        )
        if (!file.isFile || !file.delete()) throw FileNotFoundException(documentId)
        android.util.Log.i("TestDocProvider", "deleteDocument deleted=${!file.exists()}")
    }

    private fun documentCursor(
        file: File,
        documentId: String,
        projection: Array<out String>?,
    ): Cursor {
        val columns = (projection ?: DEFAULT_DOCUMENT_COLUMNS).map { it }.toTypedArray()
        return MatrixCursor(columns).apply {
            val row = newRow()
            columns.forEach { column -> row.add(valueFor(file, documentId, column)) }
        }
    }

    private fun valueFor(file: File, documentId: String, column: String): Any? = when (column) {
        Document.COLUMN_DOCUMENT_ID -> documentId
        Document.COLUMN_DISPLAY_NAME -> file.name
        Document.COLUMN_MIME_TYPE -> if (file.isDirectory) Document.MIME_TYPE_DIR else "text/plain"
        Document.COLUMN_FLAGS -> if (file.isDirectory) 0 else Document.FLAG_SUPPORTS_DELETE
        Document.COLUMN_SIZE -> file.length()
        Document.COLUMN_LAST_MODIFIED -> file.lastModified()
        else -> null
    }

    private fun resolve(documentId: String): File {
        val root = rootDir ?: throw FileNotFoundException("Test provider root is not configured")
        return if (documentId.isEmpty() || documentId == ROOT_ID) root else File(root, documentId)
    }

    companion object {
        const val AUTHORITY: String = "com.keeper.vault.vault.test.docs"
        private const val ROOT_ID = "test-root"
        private val DEFAULT_DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )

        @Volatile
        var rootDir: File? = null
    }
}
