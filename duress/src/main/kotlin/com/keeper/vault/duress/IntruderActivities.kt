package com.keeper.vault.duress

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.keeper.vault.auth.AuthFeature
import com.keeper.vault.contracts.auth.LockState
import com.keeper.vault.contracts.auth.SecretType
import com.keeper.vault.contracts.auth.UnlockResult
import com.keeper.vault.contracts.intruder.IntruderEvent
import com.keeper.vault.contracts.intruder.IntruderSettings
import com.keeper.vault.contracts.platform.SecretZeroizer
import com.keeper.vault.duress.settings.IntruderConsentStore
import com.keeper.vault.duress.settings.IntruderEnableDecision
import com.keeper.vault.duress.settings.decideIntruderEnable
import com.keeper.vault.duress.ui.IntruderConsentDialog
import com.keeper.vault.duress.ui.IntruderLogScreen
import com.keeper.vault.duress.ui.IntruderSettingsScreen
import kotlinx.coroutines.launch

/**
 * Explicit opt-in and retention screen.
 *
 * `FLAG_SECURE` is set before content. Capture defaults to OFF; the disclosure is shown before
 * the switch. Turning the feature on asks for the camera permission first; denial leaves the
 * feature OFF and says so rather than pretending capture will work.
 */
class IntruderSettingsActivity : ComponentActivity() {
    private val component by lazy { DuressFeature.component(this) }
    private val consentStore by lazy { IntruderConsentStore(this) }

    private var cameraGranted by mutableStateOf(false)
    private var eventCount by mutableIntStateOf(0)
    private var error by mutableStateOf<String?>(null)
    private var showConsent by mutableStateOf(false)

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraGranted = granted
        if (granted && consentStore.isAccepted()) {
            updateSettings { it.copy(enabled = true) }
        } else if (!granted) {
            error = IntruderCopy.CAMERA_MISSING
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secure()
        cameraGranted = hasCameraPermission()
        if (component.intruderSettings.settings.value.enabled && !consentStore.isAccepted()) {
            // A restored/legacy enabled value must not bypass the new activation consent.
            showConsent = true
        }
        setContent {
            MaterialTheme {
                val settings = component.intruderSettings.settings
                    .collectAsStateWithLifecycle()
                IntruderSettingsScreen(
                    settings = settings.value,
                    cameraGranted = cameraGranted,
                    eventCount = eventCount,
                    error = error,
                    onEnabledChange = ::setEnabled,
                    onThresholdChange = { threshold ->
                        updateSettings { it.copy(failureThreshold = threshold) }
                    },
                    onRetentionChange = { days ->
                        updateSettings { it.copy(retentionDays = days) }
                    },
                    onOpenLog = {
                        startActivity(Intent(this, IntruderLogActivity::class.java))
                    },
                    onDeleteAll = ::deleteAll,
                    onGrantCamera = {
                        if (consentStore.isAccepted()) {
                            cameraPermission.launch(Manifest.permission.CAMERA)
                        } else {
                            showConsent = true
                        }
                    },
                )
                if (showConsent) {
                    IntruderConsentDialog(
                        onAccept = {
                            consentStore.accept()
                            showConsent = false
                            setEnabled(true)
                        },
                        onDecline = {
                            showConsent = false
                            if (component.intruderSettings.settings.value.enabled) {
                                updateSettings {
                                    it.copy(enabled = false, captureOnFaceMismatch = false)
                                }
                            }
                        },
                    )
                }
            }
        }
        lifecycleScope.launch {
            component.intruderLog.observe().collect { events -> eventCount = events.size }
        }
    }

    override fun onResume() {
        super.onResume()
        cameraGranted = hasCameraPermission()
    }

    private fun setEnabled(enabled: Boolean) {
        when (
            decideIntruderEnable(
                enabled = enabled,
                consentAccepted = consentStore.isAccepted(),
                cameraGranted = hasCameraPermission(),
            )
        ) {
            IntruderEnableDecision.DISABLE -> updateSettings {
                it.copy(enabled = false, captureOnFaceMismatch = false)
            }

            IntruderEnableDecision.SHOW_CONSENT -> showConsent = true

            IntruderEnableDecision.REQUEST_CAMERA ->
                cameraPermission.launch(Manifest.permission.CAMERA)

            IntruderEnableDecision.ENABLE -> updateSettings { it.copy(enabled = true) }
        }
    }

    private fun updateSettings(transform: (IntruderSettings) -> IntruderSettings) {
        lifecycleScope.launch {
            try {
                component.intruderSettings.update(transform)
                error = null
            } catch (e: Exception) {
                error = e.message ?: "Could not save the setting."
            }
        }
    }

    private fun deleteAll() {
        lifecycleScope.launch {
            try {
                component.deleteAllCaptures()
                error = null
            } catch (e: Exception) {
                error = e.message ?: "Could not delete the captures."
            }
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun secure() {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
    }
}

/**
 * Reauthenticated intruder viewer.
 *
 * The frozen window class name is retained. `FLAG_SECURE` is set before content. Viewing requires
 * two gates:
 * 1. the real vault is currently unlocked (`LockState.Unlocked`), and
 * 2. the user re-enters the vault secret and [AuthManager.unlock] succeeds.
 *
 * **Documented seam:** the frozen `AuthManager` has no verify-only call. This screen reuses
 * `unlock`, which re-opens a real session and resets the attempt counter. If a future auth
 * version adds `verify(secret)`, this activity should switch to it without changing the UI. The
 * image bytes are decrypted into a local array, decoded, and zeroed immediately; only the
 * `Bitmap` remains in process memory.
 */
class IntruderLogActivity : ComponentActivity() {
    private val component by lazy { DuressFeature.component(this) }
    private val auth by lazy { AuthFeature.component(this) }

    private var vaultUnlocked by mutableStateOf(false)
    private var verified by mutableStateOf(false)
    private var events by mutableStateOf<List<IntruderEvent>>(emptyList())
    private var selectedImage by mutableStateOf<Bitmap?>(null)
    private var busy by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secure()
        setContent {
            MaterialTheme {
                IntruderLogScreen(
                    vaultUnlocked = vaultUnlocked,
                    verified = verified,
                    events = events,
                    selectedImage = selectedImage,
                    busy = busy,
                    error = error,
                    onVerify = ::verify,
                    onView = ::view,
                    onDelete = ::delete,
                    onDeleteAll = ::deleteAll,
                    onCloseImage = { selectedImage = null },
                )
            }
        }
        lifecycleScope.launch {
            auth.lockController.state.collect { state ->
                val unlocked = state is LockState.Unlocked
                vaultUnlocked = unlocked
                if (!unlocked) {
                    verified = false
                    selectedImage = null
                }
            }
        }
        lifecycleScope.launch {
            component.intruderLog.observe().collect { events = it }
        }
    }

    override fun onDestroy() {
        selectedImage = null
        super.onDestroy()
    }

    private fun verify(chars: CharArray) {
        val owned = chars.copyOf()
        busy = true
        lifecycleScope.launch {
            try {
                if (!vaultUnlocked) {
                    error = IntruderCopy.LOCKED_MESSAGE
                    return@launch
                }
                val type = auth.secretTypeStore.load() ?: SecretType.PASSWORD
                when (val result = auth.authManager.unlock(type, owned)) {
                    is UnlockResult.Success -> {
                        verified = true
                        error = null
                    }

                    is UnlockResult.WrongSecret -> error = "That secret did not match."
                    is UnlockResult.LockedOut -> error = "Too many attempts. Try again later."
                    is UnlockResult.Rejected -> error = result.reason
                }
            } catch (_: Exception) {
                error = "Reauthentication failed."
            } finally {
                SecretZeroizer.zero(owned)
                busy = false
            }
        }
    }

    private fun view(event: IntruderEvent) {
        busy = true
        lifecycleScope.launch {
            try {
                val bytes = component.intruderLog.readImage(event.id)
                try {
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap == null) {
                        error = "Could not decode this capture."
                    } else {
                        selectedImage = bitmap
                        error = null
                    }
                } finally {
                    SecretZeroizer.zero(bytes)
                }
            } catch (_: Exception) {
                error = "Could not decrypt this capture."
            } finally {
                busy = false
            }
        }
    }

    private fun delete(event: IntruderEvent) {
        busy = true
        lifecycleScope.launch {
            try {
                (component.intruderLog as? com.keeper.vault.duress.intruder.IntruderLogAdmin)
                    ?.delete(event.id)
                error = null
            } catch (_: Exception) {
                error = "Could not delete this capture."
            } finally {
                busy = false
            }
        }
    }

    private fun deleteAll() {
        busy = true
        lifecycleScope.launch {
            try {
                component.deleteAllCaptures()
                selectedImage = null
                error = null
            } catch (_: Exception) {
                error = "Could not delete the captures."
            } finally {
                busy = false
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
