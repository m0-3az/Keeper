package com.keeper.vault.vault.ui

import android.content.ContentResolver
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device proof that importing is a move: the source document is deleted through the same
 * platform call VaultScreen uses, with a document URI returned by a real ContentProvider.
 */
@RunWith(AndroidJUnit4::class)
class SourceDocumentDeleterTest {
    private lateinit var context: android.content.Context
    private lateinit var directory: File
    private lateinit var resolver: ContentResolver

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().context
        resolver = context.contentResolver
        directory = File(context.cacheDir, "source-delete-test").apply {
            deleteRecursively()
            mkdirs()
        }
        TestDocumentProvider.rootDir = directory
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
        TestDocumentProvider.rootDir = null
    }

    @Test
    fun deletesTheSourceDocumentAfterImport() {
        val source = File(directory, "import-me.txt").apply { writeText("vault payload") }
        val uri = DocumentsContract.buildDocumentUri(
            TestDocumentProvider.AUTHORITY,
            source.name,
        )

        assertTrue(DocumentsContract.isDocumentUri(context, uri))
        assertTrue(deleteSourceDocument(context, resolver, uri))
        assertFalse(source.exists())
    }

    @Test
    fun reportsFailureWhenTheProviderCannotDelete() {
        // A document ID with no backing file makes the provider throw FileNotFoundException.
        val uri = DocumentsContract.buildDocumentUri(
            TestDocumentProvider.AUTHORITY,
            "does-not-exist.txt",
        )

        assertFalse(deleteSourceDocument(context, resolver, uri))
    }
}
