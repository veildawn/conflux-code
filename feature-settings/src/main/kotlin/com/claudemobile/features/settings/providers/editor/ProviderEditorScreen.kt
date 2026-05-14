package com.claudemobile.features.settings.providers.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claudemobile.core.domain.providers.AuthHeaderStyle
import com.claudemobile.core.domain.providers.ValidationError
import com.claudemobile.core.domain.providers.ValidationField
import com.claudemobile.core.ui.R as CoreUiR
import com.claudemobile.features.settings.providers.common.ConnectionTestResultCard

/**
 * Editor for [com.claudemobile.core.domain.providers.ProviderProfile]s
 * supporting all three [EditorMode]s (design §6.1).
 *
 * - [EditorMode.Preset]: `baseUrl` is rendered read-only with a lock
 *   indicator, [authHeaderStyle] is hidden (auto-set from preset), the
 *   remaining fields ([displayName], [model], [smallFastModel]) are
 *   editable and pre-populated from the preset, and `apiKey` is required.
 * - [EditorMode.Custom]: every field is editable; [authHeaderStyle] is a
 *   single-select segmented control with default `ApiKey` (R3 AC7).
 * - [EditorMode.Edit]: behaves like Custom for custom-derived profiles
 *   and like Preset for preset-derived profiles (the `baseUrlReadOnly`
 *   flag on [FormState] decides).
 *
 * Per-field validation state (`✓` / `✗` plus inline error text) is
 * displayed independently of the global Save button state per R3 AC3:
 * a single still-invalid field disables Save while the other fields
 * keep showing their own ✓ indicators.
 *
 * Requirements: 1.5, 2.1, 2.3, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 4.2,
 * 9.2.
 */
@Composable
public fun ProviderEditorScreen(
    mode: EditorMode,
    onSaved: (profileId: String) -> Unit,
    onBack: () -> Unit,
    viewModel: ProviderEditorViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(mode) {
        viewModel.onIntent(ProviderEditorIntent.Initialize(mode))
    }

    LaunchedEffect(uiState) {
        val saved = uiState as? ProviderEditorUiState.Saved
        if (saved != null) {
            onSaved(saved.profileId)
        }
    }

    ProviderEditorScreenContent(
        state = uiState,
        onIntent = viewModel::onIntent,
        onBack = onBack,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderEditorScreenContent(
    state: ProviderEditorUiState,
    onIntent: (ProviderEditorIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(CoreUiR.string.provider_selection_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        onIntent(ProviderEditorIntent.Cancel)
                        onBack()
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            when (state) {
                ProviderEditorUiState.Loading -> CenteredProgress()
                is ProviderEditorUiState.Editing -> EditingForm(
                    state = state,
                    onIntent = onIntent,
                )
                is ProviderEditorUiState.Saved -> CenteredProgress()
                is ProviderEditorUiState.Error -> ErrorContent(
                    reason = state.reason,
                    onBack = onBack,
                )
            }
        }
    }
}

@Composable
private fun CenteredProgress() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorContent(
    reason: String,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = reason,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onBack) {
            Text("Back")
        }
    }
}

// ---------------------------------------------------------------------------
// Editing form
// ---------------------------------------------------------------------------

@Composable
private fun EditingForm(
    state: ProviderEditorUiState.Editing,
    onIntent: (ProviderEditorIntent) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(PROVIDER_EDITOR_FORM_TAG),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // displayName -------------------------------------------------------
        ValidatedTextField(
            label = stringResource(CoreUiR.string.provider_field_displayname),
            field = state.form.displayName,
            onValueChange = {
                onIntent(ProviderEditorIntent.UpdateField(ValidationField.DisplayName, it))
            },
            singleLine = true,
            testTag = FIELD_DISPLAY_NAME_TAG,
        )

        // baseUrl -----------------------------------------------------------
        ValidatedTextField(
            label = stringResource(CoreUiR.string.provider_field_base_url),
            field = state.form.baseUrl,
            onValueChange = {
                onIntent(ProviderEditorIntent.UpdateField(ValidationField.BaseUrl, it))
            },
            singleLine = true,
            enabled = !state.form.baseUrlReadOnly,
            keyboardType = KeyboardType.Uri,
            trailingLockIcon = state.form.baseUrlReadOnly,
            testTag = FIELD_BASE_URL_TAG,
        )

        // apiKey ------------------------------------------------------------
        ApiKeyField(
            label = stringResource(CoreUiR.string.provider_field_api_key),
            field = state.form.apiKey,
            onValueChange = {
                onIntent(ProviderEditorIntent.UpdateField(ValidationField.ApiKey, it))
            },
            testTag = FIELD_API_KEY_TAG,
        )

        // model -------------------------------------------------------------
        ValidatedTextField(
            label = stringResource(CoreUiR.string.provider_field_model),
            field = state.form.model,
            onValueChange = {
                onIntent(ProviderEditorIntent.UpdateField(ValidationField.Model, it))
            },
            singleLine = true,
            testTag = FIELD_MODEL_TAG,
        )

        // smallFastModel ----------------------------------------------------
        ValidatedTextField(
            label = stringResource(CoreUiR.string.provider_field_small_fast_model),
            field = state.form.smallFastModel,
            onValueChange = {
                onIntent(ProviderEditorIntent.UpdateField(ValidationField.SmallFastModel, it))
            },
            singleLine = true,
            testTag = FIELD_SMALL_FAST_MODEL_TAG,
        )

        // authHeaderStyle (Custom-only) -------------------------------------
        if (!state.form.baseUrlReadOnly) {
            AuthHeaderStyleSelector(
                current = state.form.authHeaderStyle,
                onChange = { onIntent(ProviderEditorIntent.UpdateAuthStyle(it)) },
            )
        }

        Spacer(Modifier.height(8.dp))

        // Action row --------------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = { onIntent(ProviderEditorIntent.TestConnection) },
                enabled = !state.isTesting && !state.isSaving && state.form.submitEnabled,
                modifier = Modifier
                    .weight(1f)
                    .testTag(BUTTON_TEST_CONNECTION_TAG),
            ) {
                if (state.isTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text("Test Connection")
            }

            Button(
                onClick = { onIntent(ProviderEditorIntent.Save) },
                enabled = state.form.submitEnabled && !state.isSaving,
                modifier = Modifier
                    .weight(1f)
                    .testTag(BUTTON_SAVE_TAG),
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text("Save")
            }
        }

        // Connection_Test result --------------------------------------------
        state.testResult?.let { ConnectionTestResultCard(it) }

        Spacer(Modifier.height(16.dp))
    }
}

// ---------------------------------------------------------------------------
// Field composables
// ---------------------------------------------------------------------------

@Composable
private fun ValidatedTextField(
    label: String,
    field: Field<String>,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingLockIcon: Boolean = false,
    testTag: String? = null,
) {
    val errorMessage = field.error?.let { stringResourceForError(it) }
    val showError = !field.valid && field.value.isNotEmpty()

    val tagModifier = if (testTag != null) Modifier.testTag(testTag) else Modifier

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(tagModifier),
    ) {
        OutlinedTextField(
            value = field.value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = singleLine,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = visualTransformation,
            isError = showError,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                when {
                    trailingLockIcon -> Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Locked by preset",
                    )
                    field.valid -> Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    field.value.isNotEmpty() -> Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    else -> Unit
                }
            },
        )
        if (showError && errorMessage != null) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
            )
        }
    }
}

@Composable
private fun ApiKeyField(
    label: String,
    field: Field<String>,
    onValueChange: (String) -> Unit,
    testTag: String,
) {
    var revealed by remember { mutableStateOf(false) }
    val errorMessage = field.error?.let { stringResourceForError(it) }
    val showError = !field.valid && field.value.isNotEmpty()

    // When "revealed" is toggled, only the last 4 characters are exposed (R9 AC2):
    // we never display the entire key in plaintext on the form. The user
    // can copy or re-enter the key but cannot peek the leading bytes.
    val transformation: VisualTransformation =
        if (revealed) MaskAllButLast4Transformation() else PasswordVisualTransformation()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
    ) {
        OutlinedTextField(
            value = field.value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = transformation,
            isError = showError,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                Row {
                    if (field.valid) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    } else if (field.value.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                    IconButton(onClick = { revealed = !revealed }) {
                        Icon(
                            imageVector = if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (revealed) "Hide last 4" else "Reveal last 4",
                        )
                    }
                }
            },
        )
        if (showError && errorMessage != null) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
            )
        }
    }
}

/**
 * Visual transformation that displays only the **last 4 characters** of
 * the underlying API key in plaintext, masking everything else with
 * bullets. Used by [ApiKeyField] when the user toggles "reveal" — by
 * design we never expose the full key on the editor screen (R9 AC2).
 */
private class MaskAllButLast4Transformation : VisualTransformation {
    override fun filter(text: androidx.compose.ui.text.AnnotatedString):
        androidx.compose.ui.text.input.TransformedText {
        val raw = text.text
        val masked = if (raw.length <= 4) {
            "\u2022".repeat(raw.length)
        } else {
            "\u2022".repeat(raw.length - 4) + raw.takeLast(4)
        }
        return androidx.compose.ui.text.input.TransformedText(
            androidx.compose.ui.text.AnnotatedString(masked),
            androidx.compose.ui.text.input.OffsetMapping.Identity,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthHeaderStyleSelector(
    current: AuthHeaderStyle,
    onChange: (AuthHeaderStyle) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(CoreUiR.string.provider_field_auth_header_style),
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(Modifier.height(4.dp))
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(AUTH_STYLE_SELECTOR_TAG),
        ) {
            val options = AuthHeaderStyle.entries
            options.forEachIndexed { index, style ->
                SegmentedButton(
                    selected = current == style,
                    onClick = { onChange(style) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                ) {
                    Text(
                        text = when (style) {
                            AuthHeaderStyle.ApiKey ->
                                stringResource(CoreUiR.string.provider_auth_style_api_key)
                            AuthHeaderStyle.AuthToken ->
                                stringResource(CoreUiR.string.provider_auth_style_auth_token)
                        },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Localization helpers
// ---------------------------------------------------------------------------

@Composable
private fun stringResourceForError(error: ValidationError): String = when (error) {
    ValidationError.DisplayNameBlank ->
        stringResource(CoreUiR.string.provider_error_displayname_empty)
    ValidationError.DisplayNameTooLong ->
        stringResource(CoreUiR.string.provider_error_displayname_empty)
    ValidationError.BaseUrlInvalid ->
        stringResource(CoreUiR.string.provider_error_baseurl_invalid)
    ValidationError.BaseUrlPresetLocked ->
        stringResource(CoreUiR.string.provider_error_baseurl_locked)
    ValidationError.ApiKeyEmpty ->
        stringResource(CoreUiR.string.provider_error_apikey_empty)
    ValidationError.ModelBlank ->
        stringResource(CoreUiR.string.provider_error_model_empty)
}

// ---------------------------------------------------------------------------
// Test tags
// ---------------------------------------------------------------------------

public const val PROVIDER_EDITOR_FORM_TAG: String = "provider_editor_form"
public const val FIELD_DISPLAY_NAME_TAG: String = "provider_editor_field_displayname"
public const val FIELD_BASE_URL_TAG: String = "provider_editor_field_baseurl"
public const val FIELD_API_KEY_TAG: String = "provider_editor_field_apikey"
public const val FIELD_MODEL_TAG: String = "provider_editor_field_model"
public const val FIELD_SMALL_FAST_MODEL_TAG: String = "provider_editor_field_smallfastmodel"
public const val AUTH_STYLE_SELECTOR_TAG: String = "provider_editor_auth_selector"
public const val BUTTON_TEST_CONNECTION_TAG: String = "provider_editor_button_test"
public const val BUTTON_SAVE_TAG: String = "provider_editor_button_save"
