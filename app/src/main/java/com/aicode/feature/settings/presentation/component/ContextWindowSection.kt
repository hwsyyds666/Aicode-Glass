package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AppSwitch
import com.aicode.core.ui.AppTextField
import com.aicode.feature.settings.data.repository.ContextWindowOverridePolicy
import com.aicode.feature.settings.presentation.ContextWindowOverrideUiState
import compose.icons.FeatherIcons
import compose.icons.feathericons.Info

/** Unified context-window advanced settings; values persist automatically when a field loses focus. */
@Composable
internal fun ContextWindowSection(
    state: ContextWindowOverrideUiState,
    onToggleEnabled: (Boolean) -> Unit,
    onSave: (contextTokens: Int, inputTokens: Int?, outputTokens: Int?) -> Unit
) {
    var contextText by remember(state.contextTokens) {
        mutableStateOf(state.contextTokens.takeIf { it > 0 }?.toString() ?: "")
    }
    var inputText by remember(state.inputTokens) {
        mutableStateOf(state.inputTokens?.takeIf { it > 0 }?.toString() ?: "")
    }
    var outputText by remember(state.outputTokens) {
        mutableStateOf(state.outputTokens?.takeIf { it > 0 }?.toString() ?: "")
    }
    val focusManager = LocalFocusManager.current

    fun parsed(text: String): Int? = text.trim().toIntOrNull()
        ?.takeIf { it in 1..ContextWindowOverridePolicy.MAX_TOKEN_VALUE }

    val contextValue = parsed(contextText)
    val inputValue = inputText.takeIf(String::isNotBlank)?.let(::parsed)
    val outputValue = parsed(outputText)
    val valid = contextValue != null && outputValue != null &&
        (inputText.isBlank() || inputValue != null) &&
        (inputValue == null || inputValue.toLong() + outputValue.toLong() <= contextValue.toLong())

    fun commitIfValid() {
        if (valid) onSave(contextValue!!, inputValue, outputValue!!)
    }

    val validationMessage = when {
        contextText.isNotBlank() && contextValue == null -> stringResource(R.string.ctx_window_value_error)
        inputText.isNotBlank() && inputValue == null -> stringResource(R.string.ctx_window_value_error)
        outputText.isNotBlank() && outputValue == null -> stringResource(R.string.ctx_window_value_error)
        contextValue != null && outputValue != null && inputValue != null &&
            inputValue.toLong() + outputValue.toLong() > contextValue.toLong() -> stringResource(R.string.ctx_window_sum_error)
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroupHeader(text = stringResource(R.string.ctx_window_group))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Info,
                title = stringResource(R.string.ctx_window_enable),
                subtitle = stringResource(R.string.ctx_window_enable_desc),
                trailing = {
                    AppSwitch(checked = state.enabled, onCheckedChange = onToggleEnabled)
                }
            )
        }

        Text(
            text = stringResource(R.string.ctx_window_note_required),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.md)
        )

        SettingsGroupHeader(text = stringResource(R.string.ctx_window_values_group))
        SettingsGroup {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FieldLabel(text = stringResource(R.string.ctx_window_total), required = true)
                AppTextField(
                    value = contextText,
                    onValueChange = { contextText = it.filter(Char::isDigit).take(MAX_TOKEN_DIGITS) },
                    placeholder = stringResource(R.string.ctx_window_total_hint),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                    isError = contextText.isNotBlank() && contextValue == null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) commitIfValid() }
                )

                FieldLabel(text = stringResource(R.string.ctx_window_input), required = false)
                AppTextField(
                    value = inputText,
                    onValueChange = { inputText = it.filter(Char::isDigit).take(MAX_TOKEN_DIGITS) },
                    placeholder = stringResource(R.string.ctx_window_input_hint),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                    isError = inputText.isNotBlank() && inputValue == null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) commitIfValid() }
                )

                FieldLabel(text = stringResource(R.string.ctx_window_output), required = true)
                AppTextField(
                    value = outputText,
                    onValueChange = { outputText = it.filter(Char::isDigit).take(MAX_TOKEN_DIGITS) },
                    placeholder = stringResource(R.string.ctx_window_output_hint),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        commitIfValid()
                        focusManager.clearFocus()
                    }),
                    isError = outputText.isNotBlank() && outputValue == null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) commitIfValid() }
                )

                if (validationMessage != null) {
                    Text(
                        text = validationMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.ctx_window_auto_save),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (state.enabled) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = 4.dp)
            ) {
                Text(
                    text = stringResource(R.string.ctx_window_active_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** Field title and required marker. */
@Composable
private fun FieldLabel(text: String, required: Boolean) {
    Row {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface
        )
        if (required) {
            Text(
                text = " *",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

private const val MAX_TOKEN_DIGITS = 8
