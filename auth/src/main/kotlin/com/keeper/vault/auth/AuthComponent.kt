package com.keeper.vault.auth

import android.app.Application
import android.content.Context
import com.keeper.vault.contracts.auth.AttemptLimiter
import com.keeper.vault.contracts.auth.AuthManager
import com.keeper.vault.contracts.auth.LockController
import com.keeper.vault.contracts.platform.Clock
import com.keeper.vault.crypto.AndroidKeystoreEnvelope
import com.keeper.vault.crypto.DefaultKeyDerivation
import com.keeper.vault.crypto.DefaultSecureRandomSource
import com.keeper.vault.crypto.DefaultVaultKeyManager
import com.keeper.vault.crypto.FileEnvelopeStore
import java.io.File
import kotlinx.coroutines.Dispatchers

/** Manual-DI bundle consumed by the auth screens and, after merge, by the app container. */
interface AuthComponent {
    val authManager: AuthManager
    val biometricAuth: BiometricAuth
    val attemptLimiter: AttemptLimiter
    val lockController: LockController
    val sessions: AuthSessionStore
    val secretTypeStore: SecretTypeStore

    /** Idempotent: installs process background and screen-off hooks. */
    fun installLifecycle()
}

/**
 * Default composition of the auth feature.
 *
 * The app's manual-DI root can construct this once and call [AuthFeature.install] to share the
 * same manager and lock controller with every screen.
 */
class DefaultAuthComponent private constructor(
    private val application: Application,
    override val authManager: AuthManager,
    override val biometricAuth: BiometricAuth,
    override val attemptLimiter: AttemptLimiter,
    override val lockController: LockController,
    override val sessions: AuthSessionStore,
    override val secretTypeStore: SecretTypeStore,
) : AuthComponent {
    private val installer = LockLifecycleInstaller(application, lockController)

    override fun installLifecycle() {
        installer.install()
    }

    companion object {
        fun create(context: Context): DefaultAuthComponent {
            val application = context.applicationContext as Application
            val clock = Clock { System.currentTimeMillis() }

            val envelopeStore = FileEnvelopeStore(File(application.filesDir, "auth"))
            val keyManager = DefaultVaultKeyManager(
                keyDerivation = DefaultKeyDerivation(),
                keystore = AndroidKeystoreEnvelope(),
                envelopeStore = envelopeStore,
                random = DefaultSecureRandomSource(),
            )

            val attemptPreferences = application.getSharedPreferences(
                SharedPreferencesAttemptStateStore.PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            )
            val attemptLimiter = PersistentAttemptLimiter(
                store = SharedPreferencesAttemptStateStore(attemptPreferences),
                clock = clock,
            )

            val sessions = AuthSessionStore()
            val biometricStore = AndroidBiometricVaultKeyStore(application)
            val biometricEnvelopes = SharedPreferencesBiometricEnvelopeStore(
                application.getSharedPreferences(
                    SharedPreferencesBiometricEnvelopeStore.PREFERENCES_NAME,
                    Context.MODE_PRIVATE,
                ),
            )
            val secretTypeStore = SharedPreferencesSecretTypeStore(
                application.getSharedPreferences(
                    SharedPreferencesSecretTypeStore.PREFERENCES_NAME,
                    Context.MODE_PRIVATE,
                ),
            )

            val authManager = DefaultAuthManager(
                keyManager = keyManager,
                validator = DefaultSecretValidator(),
                attemptLimiter = attemptLimiter,
                sessions = sessions,
                clock = clock,
                biometricStore = biometricStore,
                biometricEnvelopes = biometricEnvelopes,
                biometricAvailability = BiometricAvailability { biometricStore.canUseBiometrics() },
                secretTypeStore = secretTypeStore,
                dispatcher = Dispatchers.Default,
            )
            val lockController = DefaultLockController(sessions, clock)

            return DefaultAuthComponent(
                application = application,
                authManager = authManager,
                biometricAuth = authManager,
                attemptLimiter = attemptLimiter,
                lockController = lockController,
                sessions = sessions,
                secretTypeStore = secretTypeStore,
            )
        }
    }
}

/**
 * Process-wide auth component holder.
 *
 * This is the minimal bridge for the phase-3 screens, which must keep their frozen FQCNs without
 * editing the app module. After merge, `KeeperApplication` can construct and install its own
 * component; the holder only owns the default instance.
 */
object AuthFeature {
    @Volatile
    private var component: AuthComponent? = null

    fun install(value: AuthComponent): AuthComponent {
        component = value
        value.installLifecycle()
        return value
    }

    fun component(context: Context): AuthComponent {
        component?.let { return it }
        return synchronized(this) {
            component ?: DefaultAuthComponent.create(context).also {
                it.installLifecycle()
                component = it
            }
        }
    }

    fun resetForTests() {
        component = null
    }
}
