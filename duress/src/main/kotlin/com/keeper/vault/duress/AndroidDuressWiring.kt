package com.keeper.vault.duress

import android.content.Context
import androidx.room.Room
import com.keeper.vault.auth.AuthFeature
import com.keeper.vault.contracts.auth.LockReason
import com.keeper.vault.contracts.platform.Clock
import com.keeper.vault.crypto.AndroidKeystoreEnvelope
import com.keeper.vault.crypto.DefaultKeyDerivation
import com.keeper.vault.crypto.DefaultSecureRandomSource
import com.keeper.vault.crypto.DefaultVaultKeyManager
import com.keeper.vault.crypto.FileEnvelopeStore
import com.keeper.vault.duress.duress.DefaultDuressController
import com.keeper.vault.duress.duress.EnvelopeRealVaultSecretProbe
import com.keeper.vault.duress.duress.RoomDecoyVault
import com.keeper.vault.duress.intruder.AndroidIntruderKeys
import com.keeper.vault.duress.intruder.DefaultIntruderLog
import com.keeper.vault.duress.intruder.FileIntruderImageStore
import com.keeper.vault.duress.intruder.IntruderCoordinator
import com.keeper.vault.duress.intruder.IntruderDatabase
import com.keeper.vault.duress.intruder.IntruderPurgeScheduler
import com.keeper.vault.duress.intruder.RoomIntruderEventStore
import com.keeper.vault.duress.intruder.ServiceIntruderCapture
import com.keeper.vault.duress.settings.PersistentIntruderSettingsStore
import com.keeper.vault.duress.settings.SharedPreferencesIntruderSettingsPersistence
import com.keeper.vault.isolation.AndroidIsolation
import com.keeper.vault.vault.platform.UuidIdGenerator
import com.keeper.vault.vault.session.VaultSession
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Android platform wiring for Phase 6.
 *
 * This is the only file that constructs Keystore adapters, Room databases, WorkManager and the
 * foreground-service dispatcher. It is excluded from JVM instruction coverage (see
 * `duress/build.gradle.kts`) because those adapters need a device; the behaviour they wire
 * (settings, coordinator, retention, log, duress controller) is unit-tested without Android.
 */
object AndroidDuressWiring {
    /** Frozen v1 key aliases. The real alias is the one `:auth` created. */
    const val REAL_KEY_ALIAS: String = "keeper.layer_a.v1"
    const val DECOY_KEY_ALIAS: String = "keeper.layer_a.decoy.v1"

    /** Matches `DefaultAuthComponent`'s real envelope location. */
    const val REAL_ENVELOPE_DIRECTORY: String = "auth"

    /** Dedicated decoy envelope directory: separate salt and separate Keystore key. */
    const val DECOY_ENVELOPE_DIRECTORY: String = "duress/decoy"

    fun create(context: Context): DuressComponent {
        val app = context.applicationContext
        val clock = Clock { System.currentTimeMillis() }
        val auth = AuthFeature.component(app)
        val tamperLog = AndroidIsolation.createTamperLog(app, Dispatchers.IO)

        val settings = PersistentIntruderSettingsStore(
            persistence = SharedPreferencesIntruderSettingsPersistence(
                app.getSharedPreferences(
                    SharedPreferencesIntruderSettingsPersistence.PREFERENCES_NAME,
                    Context.MODE_PRIVATE,
                ),
            ),
            dispatcher = Dispatchers.IO,
        )
        val keys = AndroidIntruderKeys.create(app, Dispatchers.IO)
        val intruderLog = DefaultIntruderLog(
            events = RoomIntruderEventStore(IntruderStorage.database(app).intruderEventDao()),
            images = FileIntruderImageStore(File(app.filesDir, AndroidIntruderKeys.IMAGE_DIRECTORY_NAME)),
            keys = keys,
            clock = clock,
            idGenerator = UuidIdGenerator(),
            ioDispatcher = Dispatchers.IO,
        )
        val capture = ServiceIntruderCapture(app, tamperLog, clock)
        val coordinator = IntruderCoordinator(
            settings = settings,
            capture = capture,
            tamperLog = tamperLog,
            clock = clock,
        )

        val realKeyManager = DefaultVaultKeyManager(
            keyDerivation = DefaultKeyDerivation(),
            keystore = AndroidKeystoreEnvelope(REAL_KEY_ALIAS),
            envelopeStore = FileEnvelopeStore(File(app.filesDir, REAL_ENVELOPE_DIRECTORY)),
            random = DefaultSecureRandomSource(),
        )
        val decoyKeyManager = DefaultVaultKeyManager(
            keyDerivation = DefaultKeyDerivation(),
            keystore = AndroidKeystoreEnvelope(DECOY_KEY_ALIAS),
            envelopeStore = FileEnvelopeStore(File(app.filesDir, DECOY_ENVELOPE_DIRECTORY)),
            random = DefaultSecureRandomSource(),
        )
        val decoyVault = RoomDecoyVault(app, Dispatchers.IO)
        val duressController = DefaultDuressController(
            isRealVaultUnlocked = { auth.sessions.current() != null },
            decoyKeyManager = decoyKeyManager,
            realSecretProbe = EnvelopeRealVaultSecretProbe(realKeyManager),
            decoyVault = decoyVault,
            tamperLog = tamperLog,
            clock = clock,
            ioDispatcher = Dispatchers.IO,
            onDuressUnlock = {
                auth.lockController.lock(LockReason.DURESS)
                VaultSession.lock()
            },
        )

        return DefaultDuressComponent(
            intruderSettings = settings,
            intruderLog = intruderLog,
            intruderRecorder = intruderLog,
            intruderCapture = capture,
            intruderCoordinator = coordinator,
            duressController = duressController,
            decoyVault = decoyVault,
            tamperLog = tamperLog,
            clock = clock,
            purgeScheduler = { IntruderPurgeScheduler.schedule(app) },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
    }
}

/** Room database for intruder metadata. Never holds image content. */
object IntruderStorage {
    const val DATABASE_NAME: String = "keeper-intruder.db"

    @Volatile
    private var database: IntruderDatabase? = null

    fun database(context: Context): IntruderDatabase {
        database?.let { return it }
        return synchronized(this) {
            database ?: Room.databaseBuilder(
                context.applicationContext,
                IntruderDatabase::class.java,
                DATABASE_NAME,
            ).build().also { database = it }
        }
    }

    fun closeDatabase() {
        synchronized(this) {
            database?.close()
            database = null
        }
    }
}
