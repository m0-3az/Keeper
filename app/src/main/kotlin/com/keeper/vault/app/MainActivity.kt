package com.keeper.vault.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.keeper.vault.app.ui.KeeperTheme
import com.keeper.vault.app.ui.ThemePicker
import com.keeper.vault.auth.SecretSetupActivity
import com.keeper.vault.auth.UnlockActivity
import com.keeper.vault.contracts.isolation.IsolationStatus
import com.keeper.vault.contracts.platform.SecretZeroizer
import com.keeper.vault.contracts.settings.ThemeMode
import com.keeper.vault.duress.IntruderLogActivity
import com.keeper.vault.duress.IntruderSettingsActivity
import com.keeper.vault.isolation.TamperLogActivity
import com.keeper.vault.isolation.ui.TamperWarningBanner
import com.keeper.vault.presence.PresenceEnrollmentActivity
import com.keeper.vault.vault.VaultActivity
import com.keeper.vault.vault.session.VaultSession
import com.keeper.vault.vault.ui.SettingsButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/** Neutral isolation state used when the advisory detector itself is unavailable. */
private val SAFE_ISOLATION = IsolationStatus(
    signals = emptyList(),
    backupsDisabled = true,
    debuggable = false,
)

/**
 * App shell. It owns navigation between setup, unlock, vault, and tamper log, and mounts the
 * advisory isolation banner. All dependencies come from [KeeperApplication.container].
 */
class MainActivity : ComponentActivity() {
    private val keeperApplication: KeeperApplication
        get() = application as KeeperApplication

    private val container: AppContainer?
        get() = keeperApplication.container

    private var configured by mutableStateOf(false)
    private var duressConfigured by mutableStateOf(false)
    private var showDuressDialog by mutableStateOf(false)
    private var duressSecret by mutableStateOf("")
    private var duressError by mutableStateOf<String?>(null)
    private lateinit var termsStore: TermsStore
    private var termsAccepted by mutableStateOf(false)

    private val authLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val active = container
        if (result.resultCode == RESULT_OK && active != null) {
            active.syncVaultSession()
            active.reloadPresence()
            startActivity(Intent(this, VaultActivity::class.java))
        }
    }

    private val enrollmentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { _ ->
        // Restart the shield on both outcomes: enrollment stops it before taking the camera.
        container?.reloadPresence()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* The camera foreground service still runs if this is denied. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )

        // AppContainer catches failures of optional subsystems itself (presence, duress,
        // tamper detection). Reaching this branch means the vault's essential storage or auth
        // wiring failed; explain it instead of letting the process die in Application.onCreate.
        val active = container
        if (active == null) {
            setContent {
                KeeperTheme(theme = ThemeMode.DARK) {
                    StartupFailureScreen(
                        error = keeperApplication.startupError,
                        onRetry = ::restartApplication,
                    )
                }
            }
            return
        }

        termsStore = TermsStore(this)
        termsAccepted = termsStore.isAccepted()
        if (termsAccepted) requestNotificationPermission()
        enableEdgeToEdge()
        setContent {
            val theme by active.themeStore.theme.collectAsStateWithLifecycle()
            val unlocked by VaultSession.unlocked.collectAsStateWithLifecycle()
            val isolationFlow = remember(active) {
                active.tamperDetector?.observe()?.flowOn(Dispatchers.Default)
                    ?: flowOf(SAFE_ISOLATION)
            }
            val isolation by isolationFlow.collectAsStateWithLifecycle(
                initialValue = SAFE_ISOLATION,
            )

            KeeperTheme(theme = theme) {
                if (!termsAccepted) {
                    BackHandler { finishAndRemoveTask() }
                    TermsScreen(
                        onAgree = {
                            termsStore.accept()
                            termsAccepted = true
                            requestNotificationPermission()
                            refreshState()
                        },
                        onDecline = { finishAndRemoveTask() },
                    )
                } else {
                HomeScreen(
                    theme = theme,
                    configured = configured,
                    unlocked = unlocked,
                    duressConfigured = duressConfigured,
                    isolation = isolation,
                    onThemeChange = { mode ->
                        lifecycleScope.launch { active.themeStore.setTheme(mode) }
                    },
                    onOpenSettings = {
                        startActivity(Intent(this, SettingsActivity::class.java))
                    },
                    onSetup = {
                        authLauncher.launch(Intent(this, SecretSetupActivity::class.java))
                    },
                    onUnlock = {
                        authLauncher.launch(Intent(this, UnlockActivity::class.java))
                    },
                    onOpenVault = {
                        startActivity(Intent(this, VaultActivity::class.java))
                    },
                    onLock = { active.lockNow() },
                    onEnrollOwner = {
                        active.suspendPresenceForEnrollment()
                        enrollmentLauncher.launch(
                            Intent(this, PresenceEnrollmentActivity::class.java),
                        )
                    },
                    duressAvailable = active.duress != null,
                    onSetDuress = {
                        if (active.duress != null) {
                            duressSecret = ""
                            duressError = null
                            showDuressDialog = true
                        }
                    },
                    onIntruderSettings = {
                        startActivity(Intent(this, IntruderSettingsActivity::class.java))
                    },
                    onIntruderLog = {
                        startActivity(Intent(this, IntruderLogActivity::class.java))
                    },
                    onTamperLog = {
                        startActivity(Intent(this, TamperLogActivity::class.java))
                    },
                    onBannerDetails = {
                        startActivity(Intent(this, TamperLogActivity::class.java))
                    },
                )
                if (showDuressDialog) {
                    DuressSecretDialog(
                        secret = duressSecret,
                        error = duressError,
                        onSecretChange = { duressSecret = it },
                        onCancel = {
                            duressSecret = ""
                            showDuressDialog = false
                        },
                        onConfirm = {
                            val chars = duressSecret.toCharArray()
                            duressSecret = ""
                            lifecycleScope.launch {
                                val controller = active.duress?.duressController
                                if (controller == null) {
                                    duressError = "Duress is unavailable on this device."
                                    SecretZeroizer.zero(chars)
                                    return@launch
                                }
                                try {
                                    controller.configure(chars)
                                    duressConfigured = true
                                    showDuressDialog = false
                                    duressError = null
                                } catch (error: Exception) {
                                    duressError = error.message ?: "Could not set the duress secret."
                                } finally {
                                    SecretZeroizer.zero(chars)
                                }
                            }
                        },
                    )
                }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!termsAccepted) return
        refreshState()
    }

    private fun refreshState() {
        val active = container ?: return
        lifecycleScope.launch {
            configured = runCatching {
                active.auth.authManager.isConfigured()
            }.getOrDefault(false)
            duressConfigured = runCatching {
                active.duress?.duressController?.isConfigured() ?: false
            }.getOrDefault(false)
            if (VaultSession.isUnlocked()) {
                active.syncVaultSession()
                active.reloadPresence()
            }
        }
    }

    /**
     * Notification denial is expected on Android 13+. The camera foreground service still starts;
     * the system simply hides its notification. Never let the permission dialog or a security
     * exception take the activity down.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = try {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } catch (_: SecurityException) {
            false
        }
        if (granted) return
        try {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } catch (_: Exception) {
            // A missing activity result registry or a restricted device: continue without it.
        }
    }

    private fun restartApplication() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        try {
            startActivity(intent)
        } finally {
            Process.killProcess(Process.myPid())
        }
    }
}

@Composable
private fun HomeScreen(
    theme: ThemeMode,
    configured: Boolean,
    unlocked: Boolean,
    duressConfigured: Boolean,
    duressAvailable: Boolean,
    isolation: IsolationStatus,
    onThemeChange: (ThemeMode) -> Unit,
    onOpenSettings: () -> Unit,
    onSetup: () -> Unit,
    onUnlock: () -> Unit,
    onOpenVault: () -> Unit,
    onLock: () -> Unit,
    onEnrollOwner: () -> Unit,
    onSetDuress: () -> Unit,
    onIntruderSettings: () -> Unit,
    onIntruderLog: () -> Unit,
    onTamperLog: () -> Unit,
    onBannerDetails: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("home_screen"),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsButton(onClick = onOpenSettings)
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "KEEPER",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    letterSpacing = 4.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.app_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))
            TamperWarningBanner(
                status = isolation,
                onDetailsClick = onBannerDetails,
            )

            Spacer(Modifier.height(24.dp))
            VaultActions(
                configured = configured,
                unlocked = unlocked,
                onSetup = onSetup,
                onUnlock = onUnlock,
                onOpenVault = onOpenVault,
                onLock = onLock,
                onEnrollOwner = onEnrollOwner,
                onSetDuress = onSetDuress,
                duressConfigured = duressConfigured,
                duressAvailable = duressAvailable,
                onIntruderSettings = onIntruderSettings,
                onIntruderLog = onIntruderLog,
                onTamperLog = onTamperLog,
            )

            Spacer(Modifier.height(28.dp))
            StatusCard(
                configured = configured,
                unlocked = unlocked,
                duressConfigured = duressConfigured,
            )

            Spacer(Modifier.height(28.dp))
            Text(
                text = stringResource(R.string.theme_label),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(12.dp))
            ThemePicker(theme = theme, onThemeChange = onThemeChange)

            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.integration_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VaultActions(
    configured: Boolean,
    unlocked: Boolean,
    onSetup: () -> Unit,
    onUnlock: () -> Unit,
    onOpenVault: () -> Unit,
    onLock: () -> Unit,
    onEnrollOwner: () -> Unit,
    onSetDuress: () -> Unit,
    duressConfigured: Boolean,
    duressAvailable: Boolean,
    onIntruderSettings: () -> Unit,
    onIntruderLog: () -> Unit,
    onTamperLog: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            !configured -> {
                Button(onClick = onSetup, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.setup_vault))
                }
            }

            unlocked -> {
                Button(onClick = onOpenVault, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.open_vault))
                }
                OutlinedButton(onClick = onEnrollOwner, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.enroll_owner_face))
                }
                if (duressAvailable && !duressConfigured) {
                    OutlinedButton(onClick = onSetDuress, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.set_duress_secret))
                    }
                }
                if (duressAvailable) {
                    OutlinedButton(onClick = onIntruderLog, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.intruder_log))
                    }
                }
                OutlinedButton(onClick = onLock, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.lock_vault))
                }
            }

            else -> {
                Button(onClick = onUnlock, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.unlock_vault))
                }
            }
        }
        if (duressAvailable) {
            OutlinedButton(onClick = onIntruderSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.intruder_settings))
            }
        }
        OutlinedButton(onClick = onTamperLog, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.tamper_log))
        }
    }
}

@Composable
private fun StatusCard(
    configured: Boolean,
    unlocked: Boolean,
    duressConfigured: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.security_posture),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(16.dp))
            PostureRow(
                number = "01",
                text = if (configured) {
                    stringResource(R.string.vault_configured)
                } else {
                    stringResource(R.string.vault_not_configured)
                },
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
            )
            PostureRow(
                number = "02",
                text = if (unlocked) {
                    stringResource(R.string.vault_unlocked)
                } else {
                    stringResource(R.string.vault_locked)
                },
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
            )
            PostureRow(
                number = "03",
                text = stringResource(R.string.encryption_active),
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
            )
            PostureRow(
                number = "04",
                text = stringResource(R.string.internet_permission_absent),
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
            )
            PostureRow(
                number = "05",
                text = if (duressConfigured) {
                    stringResource(R.string.duress_configured)
                } else {
                    stringResource(R.string.duress_not_configured)
                },
            )
        }
    }
}

@Composable
private fun PostureRow(number: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = number,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DuressSecretDialog(
    secret: String,
    error: String?,
    onSecretChange: (String) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.duress_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.duress_dialog_body))
                OutlinedTextField(
                    value = secret,
                    onValueChange = onSecretChange,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text(stringResource(R.string.duress_secret_label)) },
                )
                if (error != null) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = secret.isNotEmpty()) {
                Text(stringResource(R.string.set))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/**
 * Shown when an essential subsystem (auth or vault storage) failed during Application.onCreate.
 * The recovery path restarts the process so AppContainer is rebuilt rather than reusing a half
 * initialized graph.
 */
@Composable
private fun StartupFailureScreen(
    error: StartupError?,
    onRetry: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "KEEPER",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                letterSpacing = 4.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.startup_failure_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(
                    R.string.startup_failure_body,
                    error?.component ?: "application",
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (error != null) {
                Spacer(Modifier.height(20.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = stringResource(R.string.startup_failure_reason),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = error.detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = error.technical,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.startup_failure_retry))
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.startup_failure_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
