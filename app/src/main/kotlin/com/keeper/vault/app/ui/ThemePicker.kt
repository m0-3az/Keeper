package com.keeper.vault.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.keeper.vault.app.R
import com.keeper.vault.contracts.settings.ThemeMode

/**
 * One theme picker for the home screen and for Settings.
 *
 * Five themes do not fit in one row on a phone, so the picker uses two rows. Every option is a
 * [FilterChip] with a stable test tag; the selected chip is the only source of visual state.
 */
@Composable
fun ThemePicker(
    theme: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(
        ThemeMode.DARK to stringResource(R.string.theme_dark),
        ThemeMode.LIGHT to stringResource(R.string.theme_light),
        ThemeMode.AMOLED to stringResource(R.string.theme_amoled),
        ThemeMode.MIDNIGHT to stringResource(R.string.theme_midnight),
        ThemeMode.FOREST to stringResource(R.string.theme_forest),
    )
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.take(3).forEach { (mode, label) ->
                ThemeOption(
                    mode = mode,
                    label = label,
                    selected = theme == mode,
                    onClick = { onThemeChange(mode) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.drop(3).forEach { (mode, label) ->
                ThemeOption(
                    mode = mode,
                    label = label,
                    selected = theme == mode,
                    onClick = { onThemeChange(mode) },
                    modifier = Modifier.weight(1f),
                )
            }
            // Keeps the second row aligned with the three-column grid above it.
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun ThemeOption(
    mode: ThemeMode,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = modifier.testTag("theme_${mode.name.lowercase()}"),
    )
}
