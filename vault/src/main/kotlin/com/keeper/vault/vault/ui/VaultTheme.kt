package com.keeper.vault.vault.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.keeper.vault.contracts.settings.ThemeMode

private val Green = Color(0xFFD7FF3F)

private val Dark = darkColorScheme(
    primary = Green,
    onPrimary = Color(0xFF050505),
    background = Color(0xFF0B0B0B),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF1D1D1D),
    onSurfaceVariant = Color(0xFFC9C9C9),
    outline = Color(0xFF5A5A5A),
    error = Color(0xFFFF6B6B),
)

private val Light = lightColorScheme(
    primary = Color(0xFF3A5A00),
    onPrimary = Color.White,
    background = Color(0xFFFAFAF7),
    onBackground = Color(0xFF101010),
    surface = Color.White,
    onSurface = Color(0xFF101010),
    surfaceVariant = Color(0xFFEDEDE6),
    onSurfaceVariant = Color(0xFF44443E),
    outline = Color(0xFF76766C),
    error = Color(0xFFB3261E),
)

private val Amoled = darkColorScheme(
    primary = Green,
    onPrimary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF0A0A0A),
    onSurfaceVariant = Color(0xFFD0D0D0),
    outline = Color(0xFF4A4A4A),
    error = Color(0xFFFF6B6B),
)

private val Midnight = darkColorScheme(
    primary = Color(0xFF6EA8FF),
    onPrimary = Color(0xFF001A3D),
    background = Color(0xFF050A18),
    onBackground = Color(0xFFE6EFFF),
    surface = Color(0xFF0B1428),
    onSurface = Color(0xFFE6EFFF),
    surfaceVariant = Color(0xFF101C36),
    onSurfaceVariant = Color(0xFFA9BEDB),
    outline = Color(0xFF3E5C8A),
    error = Color(0xFFFF7A8A),
)

private val Forest = darkColorScheme(
    primary = Color(0xFF8FE388),
    onPrimary = Color(0xFF04210A),
    background = Color(0xFF07120B),
    onBackground = Color(0xFFE7F5E8),
    surface = Color(0xFF0D1B12),
    onSurface = Color(0xFFE7F5E8),
    surfaceVariant = Color(0xFF12261A),
    onSurfaceVariant = Color(0xFFB4CDB8),
    outline = Color(0xFF476B4F),
    error = Color(0xFFFF8A80),
)

/**
 * Bridge for the app-owned theme choice.
 *
 * The app's manual-DI container writes this before launching a vault screen. It is a plain
 * volatile value, not a second settings store: the app's ThemeStore remains the source of truth.
 */
object VaultThemeState {
    @Volatile
    var mode: ThemeMode = ThemeMode.DARK
}

/** Vault screens use the same five themes as the shell. */
@Composable
fun KeeperVaultTheme(content: @Composable () -> Unit) {
    val colors = when (VaultThemeState.mode) {
        ThemeMode.DARK -> Dark
        ThemeMode.LIGHT -> Light
        ThemeMode.AMOLED -> Amoled
        ThemeMode.MIDNIGHT -> Midnight
        ThemeMode.FOREST -> Forest
    }
    MaterialTheme(colorScheme = colors, content = content)
}
