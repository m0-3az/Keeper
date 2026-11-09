package com.keeper.vault.auth

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.keeper.vault.auth.ui.IntruderCaptureStatus
import com.keeper.vault.auth.ui.IntruderWarningDialog
import com.keeper.vault.auth.ui.KeeperAuthTheme
import com.keeper.vault.auth.ui.SecretSetupScreen
import com.keeper.vault.auth.ui.UnlockScreen
import com.keeper.vault.contracts.auth.LockReason
import com.keeper.vault.contracts.auth.SecretType
import com.keeper.vault.contracts.auth.UnlockResult
import com.keeper.vault.contracts.auth.VaultMode
import com.keeper.vault.contracts.platform.KeeperException
import com.keeper.vault.contracts.platform.SecretZeroizer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Creates the vault secret and the encrypted key envelope.
 *
 * The activity keeps its frozen fully-qualified class name. `FLAG_SECURE` is set before any
 * content is attached.
 */
class SecretSetupActivity : FragmentActivity() {
    private val component by lazy { AuthFeature.component(this) }

    private var busy by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var configured by mutableStateOf(false)
    private var biometricOptIn by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secure()
        refreshConfigured()
        setContent {
            KeeperAuthTheme {
                SecretSetupScreen(
                    configured = configured,
                    busy = busy,
                    error = error,
                    biometricAvailable = component.biometricAuth.canAuthenticate(),
                    biometricOptIn = biometricOptIn,
                    onBiometricOptInChange = { biometricOptIn = it },
                    onSetup = ::createVault,
                    onCancel = {
                        setResult(RESULT_CANCELED)
                        finish()
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshConfigured()
    }

    private fun refreshConfigured() {
        lifecycleScope.launch {
            configured = runCatching { component.authManager.isConfigured() }.getOrDefault(false)
        }
    }

    private fun createVault(type: SecretType, chars: CharArray) {
        // The screen zeroes its buffer as soon as this returns; own a copy now.
        val owned = chars.copyOf()
        busy = true
        error = null
        lifecycleScope.launch {
            try {
                component.authManager.setup(type, owned)
                component.lockController.unlock(VaultMode.REAL)
                offerBiometricEnrollment()
            } catch (e: KeeperException.Auth) {
                error = e.message ?: "Could not create the vault."
            } catch (e: Exception) {
                error = "Could not create the vault."
            } finally {
                SecretZeroizer.zero(owned)
                busy = false
            }
        }
    }

    private fun offerBiometricEnrollment() {
        if (!biometricOptIn || !component.biometricAuth.canAuthenticate()) {
            completeSetup()
            return
        }
        val cipher = component.biometricAuth.createEncryptCipher()
        if (cipher == null) {
            completeSetup()
            return
        }
        busy = true
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult,
                ) {
                    val authorizedCipher = result.cryptoObject?.cipher ?: cipher
                    lifecycleScope.launch {
                        try {
                            component.biometricAuth.enableWithCurrentSession(authorizedCipher)
                        } finally {
                            busy = false
                            completeSetup()
                        }
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    busy = false
                    completeSetup()
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Enable biometric unlock")
                .setSubtitle("Your secret remains required and always works.")
                .setNegativeButtonText("Not now")
                .setAllowedAuthenticators(
                    androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG,
                )
                .build(),
            BiometricPrompt.CryptoObject(cipher),
        )
    }

    private fun completeSetup() {
        setResult(RESULT_OK)
        finish()
    }

    private fun secure() {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
    }
}

/**
 * Authoritative secret unlock, with BiometricPrompt as an additional path.
 *
 * The activity keeps its frozen fully-qualified class name. `FLAG_SECURE` is set before any
 * content is attached.
 */
class UnlockActivity : FragmentActivity() {
    private val component by lazy { AuthFeature.component(this) }

    private var busy by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var configured by mutableStateOf(false)
    private var cooldownUntilMillis by mutableStateOf<Long?>(null)
    private var resetToken by mutableIntStateOf(0)
    private var captureWarning by mutableStateOf<UnlockCaptureWarning?>(null)
    private var captureUiState by mutableStateOf<UnlockCaptureUiState>(UnlockCaptureUiState.Idle)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secure()
        refreshState()
        val warningHost = IntruderWarningBridge.host
        if (warningHost != null) {
            lifecycleScope.launch {
                warningHost.warning.collect { captureWarning = it }
            }
            lifecycleScope.launch {
                warningHost.captureState.collect { captureUiState = it }
            }
        }
        setContent {
            KeeperAuthTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    UnlockScreen(
                        configured = configured,
                        busy = busy,
                        error = error,
                        cooldownUntilMillis = cooldownUntilMillis,
                        biometricEnabled = component.biometricAuth.isEnabled(),
                        biometricAvailable = component.biometricAuth.canAuthenticate(),
                        resetToken = resetToken,
                        onUnlock = ::performUnlock,
                        onBiometricClick = ::startBiometricUnlock,
                        onConfigure = {
                            startActivity(
                                Intent(this@UnlockActivity, SecretSetupActivity::class.java),
                            )
                        },
                    )
                    if (captureWarning != null && warningHost != null) {
                        IntruderWarningDialog(
                            onConfirm = warningHost::confirmCapture,
                            onCancel = warningHost::cancelWarning,
                            onDisable = warningHost::disableCapture,
                        )
                    }
                    IntruderCaptureStatus(captureUiState)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun refreshState() {
        lifecycleScope.launch {
            configured = runCatching { component.authManager.isConfigured() }.getOrDefault(false)
            cooldownUntilMillis = runCatching {
                component.attemptLimiter.current().takeIf { it.lockedOut }?.cooldownUntilMillis
            }.getOrNull()
        }
    }

    private fun performUnlock(type: SecretType, chars: CharArray) {
        if (busy) return
        val owned = chars.copyOf()
        busy = true
        error = null
        lifecycleScope.launch {
            try {
                val routed = try {
                    AuthUnlockRouter.interceptor?.invoke(type, owned)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                handleResult(routed ?: component.authManager.unlock(type, owned))
            } catch (e: Exception) {
                error = "Unlock failed."
            } finally {
                SecretZeroizer.zero(owned)
                busy = false
            }
        }
    }

    private fun startBiometricUnlock() {
        if (busy) return
        val cipher = component.biometricAuth.createDecryptCipher()
        if (cipher == null) {
            error = "Biometric unlock is unavailable. Use your secret."
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult,
                ) {
                    val authorizedCipher = result.cryptoObject?.cipher ?: cipher
                    busy = true
                    lifecycleScope.launch {
                        try {
                            handleResult(component.biometricAuth.completeUnlock(authorizedCipher))
                        } catch (e: Exception) {
                            error = "Biometric unlock failed. Use your secret."
                        } finally {
                            busy = false
                        }
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                        errorCode != BiometricPrompt.ERROR_USER_CANCELED
                    ) {
                        error = "Biometric unlock failed. Use your secret."
                    }
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Keeper")
                .setSubtitle("Use your secret if biometrics fail.")
                .setNegativeButtonText("Use secret")
                .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build(),
            BiometricPrompt.CryptoObject(cipher),
        )
    }

    private fun handleResult(result: UnlockResult) {
        when (result) {
            is UnlockResult.Success -> {
                component.lockController.unlock(result.mode)
                setResult(RESULT_OK)
                finish()
            }

            is UnlockResult.WrongSecret -> {
                error = "Incorrect secret. ${result.attemptsRemaining} attempts remaining."
                cooldownUntilMillis = result.cooldownUntilMillis
                if (result.cooldownUntilMillis != null) {
                    component.lockController.lock(LockReason.FAILED_ATTEMPTS)
                }
                resetToken++
            }

            is UnlockResult.LockedOut -> {
                error = "Too many attempts."
                cooldownUntilMillis = result.untilMillis
                component.lockController.lock(LockReason.FAILED_ATTEMPTS)
                resetToken++
            }

            is UnlockResult.Rejected -> {
                error = result.reason
                resetToken++
            }
        }
    }

    private fun secure() {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
    }
}
