package com.claudemobile.core.domain.providers

import androidx.annotation.StringRes

/**
 * A built-in, code-defined description of an Anthropic-compatible provider
 * shipped with the app.
 *
 * Provider presets are pure data: they carry no user input (API key, chosen
 * model override) and are immutable across the lifetime of an installed
 * build. A user-owned [ProviderProfile] may reference a preset to derive
 * defaults while still permitting per-profile overrides (see design §2).
 *
 * @property presetId Stable identifier used to round-trip
 *   `PresetReference.Preset(presetId)` through serialization. Must be unique
 *   within [ProviderRegistry].
 * @property displayNameResId Localized display name, resolved via
 *   `stringResource(...)` at the UI layer. `core-domain` ships default
 *   translations for the three initial presets (see `values/strings.xml`).
 * @property baseUrl Fully-qualified HTTPS base URL **without** trailing
 *   slash. Forms the value of the `ANTHROPIC_BASE_URL` environment variable
 *   when this preset is the source of an Active_Profile.
 * @property defaultModel Default value copied into [ProviderProfile.model]
 *   when the user creates a profile from this preset; forms the value of
 *   the `ANTHROPIC_MODEL` environment variable.
 * @property defaultSmallFastModel Optional default value copied into
 *   [ProviderProfile.smallFastModel]. When `null`, no
 *   `ANTHROPIC_SMALL_FAST_MODEL` is injected at spawn time.
 * @property authHeaderStyle Authentication header style the provider
 *   accepts; determines which of `ANTHROPIC_API_KEY` /
 *   `ANTHROPIC_AUTH_TOKEN` is set by `buildClaudeEnv`.
 *
 * Requirements: 1.1, 1.2, 1.5.
 */
public data class ProviderPreset(
    val presetId: String,
    @field:StringRes val displayNameResId: Int,
    val baseUrl: String,
    val defaultModel: String,
    val defaultSmallFastModel: String? = null,
    val authHeaderStyle: AuthHeaderStyle,
)
