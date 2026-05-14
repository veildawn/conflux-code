package com.claudemobile.features.settings.providers.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claudemobile.core.common.AppError
import com.claudemobile.core.common.AppResult
import com.claudemobile.core.common.ErrorCode
import com.claudemobile.core.domain.providers.AuthHeaderStyle
import com.claudemobile.core.domain.providers.ConnectionTestResult
import com.claudemobile.core.domain.providers.ConnectionTester
import com.claudemobile.core.domain.providers.PresetReference
import com.claudemobile.core.domain.providers.ProviderPreset
import com.claudemobile.core.domain.providers.ProviderProfile
import com.claudemobile.core.domain.providers.ProviderProfileDraft
import com.claudemobile.core.domain.providers.ProviderProfileStore
import com.claudemobile.core.domain.providers.ProviderProfileStoreError
import com.claudemobile.core.domain.providers.ProviderRegistry
import com.claudemobile.core.domain.providers.ValidationError
import com.claudemobile.core.domain.providers.ValidationField
import com.claudemobile.core.domain.providers.usecase.CreateCustomUseCase
import com.claudemobile.core.domain.providers.usecase.CreateFromPresetUseCase
import com.claudemobile.core.domain.providers.usecase.SetActiveProfileUseCase
import com.claudemobile.core.domain.providers.usecase.UpdateProfileUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Editor mode passed to [ProviderEditorScreen] / [ProviderEditorViewModel].
 *
 * Mirrors `com.claudemobile.app.navigation.EditorModeArg` but lives in
 * `feature-settings` so the editor module does not have to depend on the
 * `app` module's nav package. Conversion happens at the navigation
 * destination call site (see `AppNavGraph`).
 *
 * Requirements: 2.1, 3.1, 4.2.
 */
public sealed class EditorMode {
    /** Create a new profile from a built-in preset (baseUrl is locked). */
    public data class Preset(val presetId: String) : EditorMode()

    /** Create a new fully custom profile. */
    public data object Custom : EditorMode()

    /** Edit an existing stored profile. */
    public data class Edit(val profileId: String) : EditorMode()
}

/**
 * Per-field UI state — the typed value, validation outcome, and the most
 * recent error. Distinct from "submitEnabled" so the screen can show a
 * field-level ✓ / ✗ independently of the global Save button state
 * (R3 AC3).
 */
public data class Field<T>(
    val value: T,
    val valid: Boolean = false,
    val error: ValidationError? = null,
)

/**
 * Form state held by [ProviderEditorViewModel] and consumed by the
 * editor screen. Each field carries its own validation state per design
 * §6.2.
 *
 * @property baseUrlReadOnly `true` for preset-derived profiles, where
 *   the field is rendered disabled. The flag also disables Save-side
 *   re-validation of `baseUrl` because the value is supplied by the
 *   preset and is by construction valid.
 */
public data class FormState(
    val displayName: Field<String> = Field(""),
    val baseUrl: Field<String> = Field(""),
    val apiKey: Field<String> = Field(""),
    val model: Field<String> = Field(""),
    val smallFastModel: Field<String> = Field("", valid = true),
    val authHeaderStyle: AuthHeaderStyle = AuthHeaderStyle.ApiKey,
    val baseUrlReadOnly: Boolean = false,
) {

    /**
     * `true` iff every blocking field is currently valid.
     *
     * Defined per design §6.2 `FormState.submitEnabled`. Distinct from
     * the per-field [Field.valid] flags so a single still-invalid field
     * disables the Save button while the others continue to display
     * their own ✓ indicators (R3 AC3).
     */
    public val submitEnabled: Boolean
        get() = displayName.valid && baseUrl.valid && apiKey.valid && model.valid
}

/**
 * Sealed hierarchy of editor screen states. Mirrors the hierarchy
 * declared in design §6.2.
 */
public sealed class ProviderEditorUiState {

    /** Initial state before [ProviderEditorIntent.Initialize]. */
    public data object Loading : ProviderEditorUiState()

    /**
     * The user is interacting with the form.
     *
     * @property mode the original editor mode the screen was opened in.
     * @property preset the resolved [ProviderPreset] for [EditorMode.Preset]
     *   and edit-of-preset modes; `null` for [EditorMode.Custom] or for
     *   edit modes whose underlying profile is custom.
     * @property form the editable field state.
     * @property testResult the most recent Connection_Test outcome to
     *   display below the form, or `null` if none has been requested
     *   since the form was last edited.
     * @property isSaving `true` while a Save use-case call is in flight.
     * @property isTesting `true` while a Connection_Test call is in flight.
     */
    public data class Editing(
        val mode: EditorMode,
        val preset: ProviderPreset?,
        val form: FormState,
        val testResult: ConnectionTestResult? = null,
        val isSaving: Boolean = false,
        val isTesting: Boolean = false,
    ) : ProviderEditorUiState()

    /**
     * Save succeeded; the form's `apiKey` field is reset to the empty
     * string so a subsequent navigation back to this screen does not
     * leak the just-persisted key (R9 AC3, R2 AC5).
     */
    public data class Saved(
        val profileId: String,
        val form: FormState,
    ) : ProviderEditorUiState()

    /**
     * Unrecoverable error (preset / profile not found, keystore
     * unavailable on initial load). The screen typically displays a
     * message and offers a Cancel button.
     */
    public data class Error(val reason: String) : ProviderEditorUiState()
}

/**
 * Intents the editor screen dispatches into the ViewModel.
 *
 * Matches design §6.2 `ProviderEditorIntent` plus an explicit
 * [Initialize] used at first composition because the project does not
 * (yet) use [androidx.lifecycle.SavedStateHandle].
 */
public sealed interface ProviderEditorIntent {
    /** Load form state for the supplied [mode]. Idempotent for the same mode. */
    public data class Initialize(val mode: EditorMode) : ProviderEditorIntent

    /** User edited a single text field. */
    public data class UpdateField(val field: ValidationField, val value: String) : ProviderEditorIntent

    /** User toggled the auth-header-style segmented control (Custom mode only). */
    public data class UpdateAuthStyle(val style: AuthHeaderStyle) : ProviderEditorIntent

    /** User pressed the "Test Connection" button. */
    public data object TestConnection : ProviderEditorIntent

    /** User pressed the "Save" button. */
    public data object Save : ProviderEditorIntent

    /** User pressed Back / Cancel. */
    public data object Cancel : ProviderEditorIntent
}

/**
 * ViewModel backing [ProviderEditorScreen] (design §6.2).
 *
 * Manages the form state for the three editor modes
 * ([EditorMode.Preset] / [EditorMode.Custom] / [EditorMode.Edit]),
 * runs per-field validation as the user types, dispatches Connection_Test
 * probes, and on Save delegates to the appropriate use case
 * ([CreateFromPresetUseCase] / [CreateCustomUseCase] /
 * [UpdateProfileUseCase]).
 *
 * Per-field validation is independent (R3 AC3): editing one field
 * never re-validates another, so the screen can show a green ✓ for
 * a satisfied field while a different field still blocks Save.
 *
 * Requirements: 2.4, 2.5, 3.2, 3.3, 3.4, 3.5, 3.6, 4.4, 7.1, 7.3, 7.4,
 * 9.3.
 */
@HiltViewModel
public class ProviderEditorViewModel @Inject constructor(
    private val registry: ProviderRegistry,
    private val store: ProviderProfileStore,
    private val createFromPreset: CreateFromPresetUseCase,
    private val createCustom: CreateCustomUseCase,
    private val updateProfile: UpdateProfileUseCase,
    private val setActiveProfile: SetActiveProfileUseCase,
    private val connectionTester: ConnectionTester,
) : ViewModel() {

    private val _uiState: MutableStateFlow<ProviderEditorUiState> =
        MutableStateFlow(ProviderEditorUiState.Loading)

    public val uiState: StateFlow<ProviderEditorUiState> = _uiState.asStateFlow()

    /** Dispatch entry point for the screen. */
    public fun onIntent(intent: ProviderEditorIntent) {
        when (intent) {
            is ProviderEditorIntent.Initialize -> initialize(intent.mode)
            is ProviderEditorIntent.UpdateField -> updateField(intent.field, intent.value)
            is ProviderEditorIntent.UpdateAuthStyle -> updateAuthStyle(intent.style)
            ProviderEditorIntent.TestConnection -> testConnection()
            ProviderEditorIntent.Save -> save()
            ProviderEditorIntent.Cancel -> cancel()
        }
    }

    // ---------------------------------------------------------------------
    // Initialize.
    // ---------------------------------------------------------------------

    private fun initialize(mode: EditorMode) {
        // Avoid re-initializing if already on the same mode.
        val current = _uiState.value
        if (current is ProviderEditorUiState.Editing && current.mode == mode) return

        viewModelScope.launch {
            when (mode) {
                is EditorMode.Preset -> {
                    val preset = registry.findById(mode.presetId)
                    if (preset == null) {
                        _uiState.value = ProviderEditorUiState.Error(
                            "Preset not found: ${mode.presetId}",
                        )
                        return@launch
                    }
                    _uiState.value = ProviderEditorUiState.Editing(
                        mode = mode,
                        preset = preset,
                        form = formForNewPreset(preset),
                    )
                }

                EditorMode.Custom -> {
                    _uiState.value = ProviderEditorUiState.Editing(
                        mode = mode,
                        preset = null,
                        form = formForNewCustom(),
                    )
                }

                is EditorMode.Edit -> {
                    val profile = store.get(mode.profileId)
                    if (profile == null) {
                        _uiState.value = ProviderEditorUiState.Error(
                            "Profile not found: ${mode.profileId}",
                        )
                        return@launch
                    }
                    val preset = (profile.presetReference as? PresetReference.Preset)
                        ?.let { registry.findById(it.presetId) }
                    _uiState.value = ProviderEditorUiState.Editing(
                        mode = mode,
                        preset = preset,
                        form = formForEdit(profile, isPresetDerived = preset != null),
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Field updates.
    // ---------------------------------------------------------------------

    private fun updateField(field: ValidationField, value: String) {
        val state = _uiState.value as? ProviderEditorUiState.Editing ?: return
        val updated = when (field) {
            ValidationField.DisplayName ->
                state.form.copy(displayName = validateDisplayName(value))
            ValidationField.BaseUrl -> {
                if (state.form.baseUrlReadOnly) state.form // ignore — preset baseUrl is fixed
                else state.form.copy(baseUrl = validateBaseUrl(value, state.preset))
            }
            ValidationField.ApiKey ->
                state.form.copy(apiKey = validateApiKey(value))
            ValidationField.Model ->
                state.form.copy(model = validateModel(value))
            ValidationField.SmallFastModel ->
                state.form.copy(smallFastModel = Field(value, valid = true))
        }
        _uiState.value = state.copy(
            form = updated,
            // A field edit invalidates the previous Connection_Test outcome.
            testResult = null,
        )
    }

    private fun updateAuthStyle(style: AuthHeaderStyle) {
        val state = _uiState.value as? ProviderEditorUiState.Editing ?: return
        // Preset-derived profiles inherit auth style from the preset; ignore changes.
        if (state.form.baseUrlReadOnly) return
        _uiState.value = state.copy(
            form = state.form.copy(authHeaderStyle = style),
            testResult = null,
        )
    }

    // ---------------------------------------------------------------------
    // Test connection.
    // ---------------------------------------------------------------------

    private fun testConnection() {
        val state = _uiState.value as? ProviderEditorUiState.Editing ?: return
        if (state.isTesting || state.isSaving) return
        viewModelScope.launch {
            _uiState.value = state.copy(isTesting = true, testResult = null)
            val probeProfile = state.form.toProbeProfile(profileId = "probe")
            val result = connectionTester.test(probeProfile)
            // Re-read latest state in case the user typed during the await.
            val latest = _uiState.value as? ProviderEditorUiState.Editing ?: return@launch
            _uiState.value = latest.copy(isTesting = false, testResult = result)
        }
    }

    // ---------------------------------------------------------------------
    // Save.
    // ---------------------------------------------------------------------

    private fun save() {
        val state = _uiState.value as? ProviderEditorUiState.Editing ?: return
        if (state.isSaving) return
        // Validation gate: if any blocking field is invalid, surface the
        // current form state unchanged. The button is already disabled
        // in the UI; this is defence-in-depth so programmatic Save calls
        // (tests, accessibility actions) cannot bypass validation.
        if (!state.form.submitEnabled) {
            _uiState.value = state.copy()
            return
        }

        viewModelScope.launch {
            _uiState.value = state.copy(isSaving = true)
            val saveResult: AppResult<ProviderProfile> = when (val mode = state.mode) {
                is EditorMode.Preset -> {
                    val preset = state.preset
                    if (preset == null) {
                        AppResult.Failure(AppError("Preset not loaded.", ErrorCode.UNKNOWN))
                    } else {
                        createFromPreset(
                            preset = preset,
                            apiKey = state.form.apiKey.value,
                            displayNameOverride = state.form.displayName.value
                                .takeUnless { it.isBlank() },
                            modelOverride = state.form.model.value
                                .takeUnless { it.isBlank() },
                            smallFastModelOverride = state.form.smallFastModel.value
                                .takeUnless { it.isBlank() }
                                ?: preset.defaultSmallFastModel,
                        )
                    }
                }
                EditorMode.Custom -> {
                    createCustom(
                        ProviderProfileDraft(
                            displayName = state.form.displayName.value,
                            baseUrl = state.form.baseUrl.value,
                            apiKey = state.form.apiKey.value,
                            model = state.form.model.value,
                            smallFastModel = state.form.smallFastModel.value
                                .takeIf { it.isNotEmpty() },
                            authHeaderStyle = state.form.authHeaderStyle,
                            presetReference = PresetReference.Custom,
                        ),
                    )
                }
                is EditorMode.Edit -> {
                    updateProfile(
                        profileId = mode.profileId,
                        displayName = state.form.displayName.value,
                        apiKey = state.form.apiKey.value,
                        model = state.form.model.value,
                        smallFastModel = state.form.smallFastModel.value,
                        baseUrl = if (state.form.baseUrlReadOnly) null else state.form.baseUrl.value,
                        authHeaderStyle = if (state.form.baseUrlReadOnly) null else state.form.authHeaderStyle,
                    )
                }
            }

            when (saveResult) {
                is AppResult.Success -> {
                    // When creating a new profile (Preset or Custom mode), also
                    // set it as the Active_Profile so the user can immediately
                    // start a session. Edit mode leaves the active pointer
                    // unchanged — the user may be editing a non-active profile.
                    if (state.mode is EditorMode.Preset || state.mode is EditorMode.Custom) {
                        setActiveProfile(saveResult.value.profileId)
                    }
                    val cleared = state.form.copy(apiKey = Field("", valid = false))
                    _uiState.value = ProviderEditorUiState.Saved(
                        profileId = saveResult.value.profileId,
                        form = cleared,
                    )
                }
                is AppResult.Failure -> {
                    val updated = applyErrorToForm(state, saveResult.error)
                    _uiState.value = updated
                }
            }
        }
    }

    /**
     * Maps an [AppError] from a save use case onto the form state so
     * the screen can render an inline error. Specifically:
     *
     * - [ProviderProfileStoreError.BaseUrlLocked] → mark `baseUrl` field
     *   with [ValidationError.BaseUrlPresetLocked] (R4 AC4 surfaces).
     * - [ErrorCode.INVALID_ARGUMENT] from validation → clear `isSaving`
     *   and leave the form untouched (the field-level validators have
     *   already painted the relevant fields).
     * - other errors → drop into the form unchanged with `isSaving = false`
     *   and surface the `error.message` via the editor's snack-bar
     *   surface (the screen reads this via [ProviderEditorUiState.Editing]).
     */
    private fun applyErrorToForm(
        state: ProviderEditorUiState.Editing,
        error: AppError,
    ): ProviderEditorUiState {
        val baseUrlLocked = error.cause is ProviderProfileStoreError.BaseUrlLocked
        val newForm = if (baseUrlLocked) {
            state.form.copy(
                baseUrl = state.form.baseUrl.copy(
                    valid = false,
                    error = ValidationError.BaseUrlPresetLocked,
                ),
            )
        } else {
            state.form
        }
        return state.copy(
            form = newForm,
            isSaving = false,
            // Preserve any prior testResult; it remains useful even after
            // a save failure on a different field.
        )
    }

    // ---------------------------------------------------------------------
    // Cancel.
    // ---------------------------------------------------------------------

    private fun cancel() {
        // Wipe the in-memory apiKey (defence in depth) before the screen
        // is removed from composition.
        val state = _uiState.value as? ProviderEditorUiState.Editing ?: return
        _uiState.value = state.copy(
            form = state.form.copy(apiKey = Field("", valid = false)),
        )
    }

    // ---------------------------------------------------------------------
    // Form construction helpers.
    // ---------------------------------------------------------------------

    private fun formForNewPreset(preset: ProviderPreset): FormState = FormState(
        displayName = Field("", valid = false, error = ValidationError.DisplayNameBlank),
        baseUrl = Field(preset.baseUrl, valid = true),
        apiKey = Field("", valid = false, error = ValidationError.ApiKeyEmpty),
        model = Field(preset.defaultModel, valid = true),
        smallFastModel = Field(preset.defaultSmallFastModel ?: "", valid = true),
        authHeaderStyle = preset.authHeaderStyle,
        baseUrlReadOnly = true,
    )

    private fun formForNewCustom(): FormState = FormState(
        displayName = Field("", valid = false, error = ValidationError.DisplayNameBlank),
        baseUrl = Field("", valid = false, error = ValidationError.BaseUrlInvalid),
        apiKey = Field("", valid = false, error = ValidationError.ApiKeyEmpty),
        model = Field("", valid = false, error = ValidationError.ModelBlank),
        smallFastModel = Field("", valid = true),
        authHeaderStyle = AuthHeaderStyle.ApiKey,
        baseUrlReadOnly = false,
    )

    private fun formForEdit(profile: ProviderProfile, isPresetDerived: Boolean): FormState = FormState(
        displayName = validateDisplayName(profile.displayName),
        baseUrl = Field(profile.baseUrl, valid = true),
        apiKey = validateApiKey(profile.apiKey),
        model = validateModel(profile.model),
        smallFastModel = Field(profile.smallFastModel ?: "", valid = true),
        authHeaderStyle = profile.authHeaderStyle,
        baseUrlReadOnly = isPresetDerived,
    )

    // ---------------------------------------------------------------------
    // Per-field validators (pure).
    // ---------------------------------------------------------------------

    private fun validateDisplayName(value: String): Field<String> {
        val error = when {
            value.trim().isEmpty() -> ValidationError.DisplayNameBlank
            value.length > ProviderProfile.DISPLAY_NAME_MAX_LENGTH -> ValidationError.DisplayNameTooLong
            else -> null
        }
        return Field(value, valid = error == null, error = error)
    }

    private fun validateBaseUrl(value: String, preset: ProviderPreset?): Field<String> {
        val syntaxOk = isValidHttpsUrl(value)
        val error = when {
            !syntaxOk -> ValidationError.BaseUrlInvalid
            preset != null && value != preset.baseUrl -> ValidationError.BaseUrlPresetLocked
            else -> null
        }
        return Field(value, valid = error == null, error = error)
    }

    private fun validateApiKey(value: String): Field<String> {
        val error = if (value.isEmpty()) ValidationError.ApiKeyEmpty else null
        return Field(value, valid = error == null, error = error)
    }

    private fun validateModel(value: String): Field<String> {
        val error = if (value.trim().isEmpty()) ValidationError.ModelBlank else null
        return Field(value, valid = error == null, error = error)
    }

    private fun isValidHttpsUrl(s: String): Boolean {
        if (s.isEmpty()) return false
        val uri = try {
            java.net.URI(s)
        } catch (_: java.net.URISyntaxException) {
            return false
        }
        if (uri.scheme != "https") return false
        val host = uri.host ?: return false
        return host.isNotBlank()
    }

    // ---------------------------------------------------------------------
    // Connection-test probe profile builder.
    // ---------------------------------------------------------------------

    private fun FormState.toProbeProfile(profileId: String): ProviderProfile = ProviderProfile(
        profileId = profileId,
        displayName = displayName.value.ifBlank { "probe" },
        presetReference = PresetReference.Custom,
        baseUrl = baseUrl.value,
        apiKey = apiKey.value,
        model = model.value,
        smallFastModel = smallFastModel.value.takeIf { it.isNotBlank() },
        authHeaderStyle = authHeaderStyle,
        createdAt = 0L,
        updatedAt = 0L,
    )
}
