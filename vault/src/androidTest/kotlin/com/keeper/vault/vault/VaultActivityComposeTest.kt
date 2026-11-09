package com.keeper.vault.vault

import android.util.Base64
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keeper.vault.contracts.crypto.VaultKey
import com.keeper.vault.contracts.platform.AppFileStore
import com.keeper.vault.contracts.platform.Clock
import com.keeper.vault.crypto.AesGcmVaultCipher
import com.keeper.vault.vault.data.EncryptedVaultFileStore
import com.keeper.vault.vault.data.EncryptedVaultRepository
import com.keeper.vault.vault.data.RoomVaultMetadataStore
import com.keeper.vault.vault.data.VaultDatabase
import com.keeper.vault.vault.platform.AndroidDispatcherProvider
import com.keeper.vault.vault.platform.ContextAppFileStore
import com.keeper.vault.vault.platform.UuidIdGenerator
import com.keeper.vault.vault.platform.VaultDependencies
import com.keeper.vault.vault.session.VaultSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Device-only vault UI checks. No device is attached in this phase, but this file compiles and
 * is ready for the emulator/device gate.
 */
@RunWith(AndroidJUnit4::class)
class VaultActivityComposeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<VaultActivity>()

    @Before
    fun clearVault() {
        runBlocking {
            repository.observeEntries().first().forEach { repository.delete(it.id) }
        }
    }

    @After
    fun clearOverride() {
        // Tests run in one process; keep the key available for the next test's Activity launch.
        // Do not lock here: the activity rule tears the screen down after this method, and a
        // transient lock can race the viewer's LaunchedEffect.
        VaultSession.setKey(VaultKey.from(ByteArray(32) { 7 }))
    }

    @Test
    fun flagSecureIsSetBeforeContent() {
        composeRule.activityRule.scenario.onActivity { activity ->
            val flags = activity.window.attributes.flags
            assertEquals(
                WindowManager.LayoutParams.FLAG_SECURE,
                flags and WindowManager.LayoutParams.FLAG_SECURE,
            )
        }
        composeRule.onNodeWithTag("vault_screen").assertIsDisplayed()
    }

    @Test
    fun emptyStateOffersImport() {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("vault_empty").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("vault_empty").assertIsDisplayed()
        composeRule.onNodeWithTag("import_button").assertIsDisplayed()
    }

    @Test
    fun importViewAndDeleteFlowWithTestSession() {
        val entry = runBlocking {
            repository.importFile(
                "note.txt",
                "text/plain",
                ByteArrayInputStream("hello from the vault".toByteArray()),
            )
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("note.txt").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("entry_${entry.id}").performClick()
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithTag("viewer_text").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("viewer_text").assertTextContains("hello from the vault")
        composeRule.onNodeWithTag("close_viewer").performClick()

        composeRule.onNodeWithContentDescription("Delete note.txt").performClick()
        composeRule.onNodeWithTag("confirm_delete").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("note.txt").fetchSemanticsNodes().isEmpty()
        }

        assertTrue(runBlocking { repository.observeEntries().first() }.isEmpty())
        assertFalse(File(root, "vault/${entry.id}.enc").exists())
    }

    @Test
    fun imageIsDecodedIntoRamPreview() {
        val png = Base64.decode(PNG_TWO_BY_TWO, Base64.DEFAULT)
        val entry = runBlocking {
            repository.importFile("pixel.png", "image/png", ByteArrayInputStream(png))
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("pixel.png").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("entry_${entry.id}").performClick()
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithTag("viewer_dialog").fetchSemanticsNodes().isNotEmpty()
        }
        // Decrypt and decode run on Dispatchers.IO/Default. Polling Compose semantics can starve
        // those dispatchers on a slow emulator, so yield briefly before asserting the preview.
        Thread.sleep(3_000)
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithTag("image_preview").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("image_preview").assertIsDisplayed()
        composeRule.onNodeWithTag("close_viewer").performClick()
    }

    private class TestAppFileStore(private val root: File) : AppFileStore {
        override fun vaultDirectory(): File = File(root, "vault")

        override fun intruderDirectory(): File = File(root, "intruder")
    }

    companion object {
        private lateinit var database: VaultDatabase
        private lateinit var repository: EncryptedVaultRepository
        private lateinit var root: File
        private lateinit var key: VaultKey

        // Small valid RGB PNG (2x2); avoids decoder edge cases with 1x1 RGBA fixtures.
        private const val PNG_TWO_BY_TWO =
            "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAEElEQVR42mP4z8AARAwQCgAf7gP9Y167WwAAAABJRU5ErkJggg=="

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            root = File(context.filesDir, "vault-instrumented-test").apply {
                deleteRecursively()
                mkdirs()
            }
            database = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            key = VaultKey.from(ByteArray(32) { 7 })
            VaultSession.setKey(key)
            repository = EncryptedVaultRepository(
                metadataStore = RoomVaultMetadataStore(database.vaultEntryDao()),
                fileStore = EncryptedVaultFileStore(TestAppFileStore(root), AesGcmVaultCipher()),
                sessionProvider = VaultSession.provider,
                clock = Clock { System.currentTimeMillis() },
                idGenerator = UuidIdGenerator(),
                dispatcherProvider = AndroidDispatcherProvider(),
            )
            VaultActivity.repositoryOverride = repository
        }

        @JvmStatic
        @AfterClass
        fun tearDownClass() {
            VaultActivity.repositoryOverride = null
            VaultSession.lock()
            database.close()
            root.deleteRecursively()
            key.close()
        }
    }
}

/** Activity launched with no key must show the locked state and offer no import. */
@RunWith(AndroidJUnit4::class)
class VaultActivityLockedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<VaultActivity>()

    @Test
    fun lockedStateHidesContentAndImport() {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("vault_locked").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("vault_locked").assertIsDisplayed()
        composeRule.onNodeWithText("Vault is locked").assertIsDisplayed()
        composeRule.onNodeWithTag("import_button").assertDoesNotExist()
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun lockSession() {
            VaultActivity.repositoryOverride = null
            VaultSession.lock()
        }

        @JvmStatic
        @AfterClass
        fun closeDatabase() {
            VaultSession.lock()
            VaultDependencies.closeDatabase()
        }
    }
}

/** Small device checks for the platform adapters excluded from JVM coverage. */
@RunWith(AndroidJUnit4::class)
class VaultPlatformInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun appFileStoreUsesPrivateFilesDirectory() {
        val store = ContextAppFileStore(context)

        assertEquals(File(context.filesDir, "vault"), store.vaultDirectory())
        assertEquals(File(context.filesDir, "intruder"), store.intruderDirectory())
    }

    @Test
    fun dependenciesBuildARepositoryAgainstThePrivateDatabase() {
        VaultSession.lock()

        val repository = VaultDependencies.repository(context, VaultSession.provider)

        assertNotNull(repository)
        VaultDependencies.closeDatabase()
    }

    @Test
    fun productionDispatchersAndIdsAreUsable() {
        val dispatchers = AndroidDispatcherProvider()
        val ids = UuidIdGenerator()

        assertNotNull(dispatchers.io)
        assertTrue(ids.newId().isNotEmpty())
    }
}
