package com.keeper.vault.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.keeper.vault.contracts.settings.ThemeMode

private val KeeperGreen = Color(0xFFD7FF3F)

private val DarkScheme = darkColorScheme(
    primary = KeeperGreen,
    onPrimary = Color(0xFF050505),
    primaryContainer = Color(0xFF2A3300),
    onPrimaryContainer = Color(0xFFE9FF9A),
    secondary = Color(0xFFB8D46A),
    onSecondary = Color(0xFF1A2200),
    background = Color(0xFF0B0B0B),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF1D1D1D),
    onSurfaceVariant = Color(0xFFC9C9C9),
    outline = Color(0xFF5A5A5A),
    error = Color(0xFFFF6B6B),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF3A5A00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9F99D),
    onPrimaryContainer = Color(0xFF102000),
    secondary = Color(0xFF5E7A1A),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFAFAF7),
    onBackground = Color(0xFF101010),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101010),
    surfaceVariant = Color(0xFFEDEDE6),
    onSurfaceVariant = Color(0xFF44443E),
    outline = Color(0xFF76766C),
    error = Color(0xFFB3261E),
)

private val AmoledScheme = darkColorScheme(
    primary = KeeperGreen,
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF1E2600),
    onPrimaryContainer = Color(0xFFE9FF9A),
    secondary = Color(0xFFB8D46A),
    onSecondary = Color(0xFF000000),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF0A0A0A),
    onSurfaceVariant = Color(0xFFD0D0D0),
    outline = Color(0xFF4A4A4A),
    error = Color(0xFFFF6B6B),
)

private val MidnightScheme = darkColorScheme(
    primary = Color(0xFF6EA8FF),
    onPrimary = Color(0xFF001A3D),
    primaryContainer = Color(0xFF0B2B55),
    onPrimaryContainer = Color(0xFFD9E8FF),
    secondary = Color(0xFF82D9FF),
    onSecondary = Color(0xFF00202E),
    background = Color(0xFF050A18),
    onBackground = Color(0xFFE6EFFF),
    surface = Color(0xFF0B1428),
    onSurface = Color(0xFFE6EFFF),
    surfaceVariant = Color(0xFF101C36),
    onSurfaceVariant = Color(0xFFA9BEDB),
    outline = Color(0xFF3E5C8A),
    error = Color(0xFFFF7A8A),
)

private val ForestScheme = darkColorScheme(
    primary = Color(0xFF8FE388),
    onPrimary = Color(0xFF04210A),
    primaryContainer = Color(0xFF1B3A22),
    onPrimaryContainer = Color(0xFFD8F5D2),
    secondary = Color(0xFFB7E36B),
    onSecondary = Color(0xFF152400),
    background = Color(0xFF07120B),
    onBackground = Color(0xFFE7F5E8),
    surface = Color(0xFF0D1B12),
    onSurface = Color(0xFFE7F5E8),
    surfaceVariant = Color(0xFF12261A),
    onSurfaceVariant = Color(0xFFB4CDB8),
    outline = Color(0xFF476B4F),
    error = Color(0xFFFF8A80),
)

/** Five themes, one theme function. No screen chooses its own colors. */
@Composable
fun KeeperTheme(
    theme: ThemeMode,
    content: @Composable () -> Unit,
) {
    val colors = when (theme) {
        ThemeMode.DARK -> DarkScheme
        ThemeMode.LIGHT -> LightScheme
        ThemeMode.AMOLED -> AmoledScheme
        ThemeMode.MIDNIGHT -> MidnightScheme
        ThemeMode.FOREST -> ForestScheme
    }
    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}
