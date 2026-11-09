package com.keeper.vault.vault.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Settings entry point shared by the vault screen and the app shell.
 *
 * The gear is drawn with Canvas instead of pulling the deprecated material-icons artifact, so the
 * dependency surface stays small. The canvas carries the accessible label and the IconButton
 * supplies the 48 dp touch target.
 */
@Composable
fun SettingsButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.testTag("settings_button"),
    ) {
        val tint = MaterialTheme.colorScheme.onSurface
        Canvas(
            modifier = Modifier
                .size(24.dp)
                .semantics { contentDescription = "Settings" },
        ) {
            val stroke = 2.dp.toPx()
            val ring = 7.2.dp.toPx()
            val toothInner = 8.dp.toPx()
            val toothOuter = 10.4.dp.toPx()
            val hole = 2.6.dp.toPx()
            drawCircle(tint, radius = ring, style = Stroke(width = stroke))
            drawCircle(tint, radius = hole, style = Stroke(width = stroke))
            repeat(8) { index ->
                rotate(degrees = index * 45f) {
                    drawLine(
                        color = tint,
                        start = Offset(center.x, center.y - toothInner),
                        end = Offset(center.x, center.y - toothOuter),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}
