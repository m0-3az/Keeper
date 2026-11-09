package com.keeper.vault.auth.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Compose text-entry state.
 *
 * Compose text input is inherently String-based at the IME boundary. The auth API, key
 * derivation, and persistence only ever see `CharArray`; callers copy out at submission and zero
 * the copy and this state immediately afterwards.
 */
@Stable
class SecretTextState {
    var value by mutableStateOf(TextFieldValue(""))

    fun copyChars(): CharArray = value.text.toCharArray()

    fun clear() {
        value = TextFieldValue("")
    }
}

@Composable
fun rememberSecretTextState(): SecretTextState = remember { SecretTextState() }

@Composable
fun SecretTextField(
    state: SecretTextState,
    label: String,
    testTag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    numeric: Boolean = false,
    mask: Boolean = true,
) {
    val transformation =
        if (mask) PasswordVisualTransformation() else VisualTransformation.None
    OutlinedTextField(
        value = state.value,
        onValueChange = { next ->
            val filtered = if (numeric) {
                next.copy(text = next.text.filter { it in '0'..'9' })
            } else {
                next
            }
            state.value = filtered
        },
        modifier = modifier.testTag(testTag),
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = transformation,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.NumberPassword else KeyboardType.Password,
        ),
    )
}
