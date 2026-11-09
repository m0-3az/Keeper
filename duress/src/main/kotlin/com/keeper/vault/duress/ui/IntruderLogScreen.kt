package com.keeper.vault.duress.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.keeper.vault.auth.ui.SecretTextField
import com.keeper.vault.auth.ui.rememberSecretTextState
import com.keeper.vault.contracts.intruder.IntruderEvent
import com.keeper.vault.duress.IntruderCopy
import java.text.DateFormat
import java.util.Date

/**
 * Reauthenticated intruder viewer.
 *
 * States: vault locked -> a message and no data; vault unlocked but not reauthenticated -> only
 * the secret field; verified -> metadata list and RAM-only image display. The activity owns the
 * decrypted bytes and zeroes them after decoding.
 */
@Composable
fun IntruderLogScreen(
    vaultUnlocked: Boolean,
    verified: Boolean,
    events: List<IntruderEvent>,
    selectedImage: Bitmap?,
    busy: Boolean,
    error: String?,
    onVerify: (CharArray) -> Unit,
    onView: (IntruderEvent) -> Unit,
    onDelete: (IntruderEvent) -> Unit,
    onDeleteAll: () -> Unit,
    onCloseImage: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(IntruderCopy.TITLE, style = MaterialTheme.typography.headlineSmall)

            when {
                !vaultUnlocked -> {
                    Text(IntruderCopy.LOCKED_MESSAGE, style = MaterialTheme.typography.bodyLarge)
                }

                !verified -> {
                    Text(IntruderCopy.REAUTH_MESSAGE, style = MaterialTheme.typography.bodyMedium)
                    val secret = rememberSecretTextState()
                    SecretTextField(
                        state = secret,
                        label = IntruderCopy.SECRET_LABEL,
                        testTag = "intruder_reauth_secret",
                        enabled = !busy,
                    )
                    if (error != null) {
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(
                        onClick = {
                            val chars = secret.copyChars()
                            secret.clear()
                            onVerify(chars)
                        },
                        enabled = !busy,
                    ) {
                        Text(IntruderCopy.REAUTH_BUTTON)
                    }
                }

                selectedImage != null -> {
                    Image(
                        bitmap = selectedImage.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(onClick = onCloseImage) {
                        Text(IntruderCopy.CLOSE_IMAGE)
                    }
                }

                else -> {
                    if (error != null) {
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onDeleteAll, enabled = events.isNotEmpty()) {
                            Text(IntruderCopy.DELETE_ALL)
                        }
                    }
                    HorizontalDivider()
                    if (events.isEmpty()) {
                        Text(IntruderCopy.EMPTY, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(events, key = { it.id }) { event ->
                                EventRow(
                                    event = event,
                                    enabled = !busy,
                                    onView = { onView(event) },
                                    onDelete = { onDelete(event) },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventRow(
    event: IntruderEvent,
    enabled: Boolean,
    onView: () -> Unit,
    onDelete: () -> Unit,
) {
    val formatter = remember { DateFormat.getDateTimeInstance() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                formatter.format(Date(event.capturedAtMillis)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(event.trigger.name, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = onView, enabled = enabled) {
            Text(IntruderCopy.VIEW)
        }
        OutlinedButton(onClick = onDelete, enabled = enabled) {
            Text(IntruderCopy.DELETE)
        }
    }
}
