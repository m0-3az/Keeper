package com.keeper.vault.auth.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.keeper.vault.contracts.settings.ThemeMode

private val Blue = Color(0xFF9DB2FF)

private val Dark = darkColorScheme(
    primary = Blue,
    onPrimary = Color(0xFF001158),
    background = Color(0xFF050505),
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF111111),
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF1C1C1C),
    onSurfaceVariant = Color(0xFFC7C7C7),
    error = Color(0xFFFFB4AB),
)

private val Light = lightColorScheme(
    primary = Color(0xFF3F51B5),
    onPrimary = Color.White,
    background = Color(0xFFFAFAF7),
    onBackground = Color(0xFF101010),
    surface = Color.White,
    onSurface = Color(0xFF101010),
    surfaceVariant = Color(0xFFEDEDE6),
    onSurfaceVariant = Color(0xFF44443E),
    error = Color(0xFFB3261E),
)

private val Amoled = darkColorScheme(
    primary = Blue,
    onPrimary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF0A0A0A),
    onSurfaceVariant = Color(0xFFD0D0D0),
    error = Color(0xFFFFB4AB),
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
    error = Color(0xFFFF8A80),
)

/**
 * Bridge for the app-owned theme choice. The app's ThemeStore is the source of truth; this value
 * only carries the current mode into the frozen-FQCN auth screens.
 */
object AuthThemeState {
    @Volatile
    var mode: ThemeMode = ThemeMode.DARK
}

/** Auth screens use the same five themes as the shell. */
@Composable
fun KeeperAuthTheme(content: @Composable () -> Unit) {
    val colors = when (AuthThemeState.mode) {
        ThemeMode.DARK -> Dark
        ThemeMode.LIGHT -> Light
        ThemeMode.AMOLED -> Amoled
        ThemeMode.MIDNIGHT -> Midnight
        ThemeMode.FOREST -> Forest
    }
    MaterialTheme(colorScheme = colors, content = content)
}
