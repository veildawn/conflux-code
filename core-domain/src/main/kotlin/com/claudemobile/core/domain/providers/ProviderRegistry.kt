package com.claudemobile.core.domain.providers

import com.claudemobile.core.domain.R

/**
 * Source of truth for built-in [ProviderPreset]s shipped with the app.
 *
 * The registry is populated entirely from in-app resources; no network
 * request is ever made to obtain preset definitions (R1.3).
 *
 * Requirements: 1.1, 1.2, 1.3.
 */
public interface ProviderRegistry {

    /**
     * Returns every built-in preset shipped with the current build,
     * in a stable presentation order. The list is immutable.
     */
    public fun allPresets(): List<ProviderPreset>

    /**
     * Looks up a preset by its stable [ProviderPreset.presetId].
     *
     * Returns `null` when no preset with the given id exists in this build,
     * which typically indicates the caller is dereferencing a
     * `PresetReference.Preset` persisted by an older build that shipped a
     * preset since removed.
     */
    public fun findById(presetId: String): ProviderPreset?

    /**
     * Default registry implementation backed by the in-code [BUILTIN_PRESETS]
     * list. Safe to access from any thread; all data is immutable.
     */
    public object Default : ProviderRegistry {
        override fun allPresets(): List<ProviderPreset> = BUILTIN_PRESETS

        override fun findById(presetId: String): ProviderPreset? =
            BUILTIN_PRESETS.firstOrNull { it.presetId == presetId }
    }
}

// ---------------------------------------------------------------------------
// Built-in preset literals (finalized in design §1).
//
// These are `internal` to prevent UI / data code from referencing individual
// presets directly — all lookups must go through [ProviderRegistry] so that
// tests can substitute registries and so that future additions do not force
// call-site changes.
// ---------------------------------------------------------------------------

internal val GLM_CODING_PLAN: ProviderPreset = ProviderPreset(
    presetId = "glm_coding_plan",
    displayNameResId = R.string.provider_preset_glm_coding_plan,
    baseUrl = "https://open.bigmodel.cn/api/anthropic",
    defaultModel = "glm-4.6",
    defaultSmallFastModel = null,
    authHeaderStyle = AuthHeaderStyle.AuthToken,
)

internal val MINIMAX_TOKEN_PLAN: ProviderPreset = ProviderPreset(
    presetId = "minimax_token_plan",
    displayNameResId = R.string.provider_preset_minimax_token_plan,
    baseUrl = "https://api.minimaxi.com/anthropic",
    defaultModel = "MiniMax-M2",
    defaultSmallFastModel = null,
    authHeaderStyle = AuthHeaderStyle.AuthToken,
)

internal val KIMI_CODE_PLAN: ProviderPreset = ProviderPreset(
    presetId = "kimi_code_plan",
    displayNameResId = R.string.provider_preset_kimi_code_plan,
    baseUrl = "https://api.moonshot.cn/anthropic",
    defaultModel = "kimi-k2-turbo-preview",
    defaultSmallFastModel = null,
    authHeaderStyle = AuthHeaderStyle.AuthToken,
)

internal val BUILTIN_PRESETS: List<ProviderPreset> = listOf(
    GLM_CODING_PLAN,
    MINIMAX_TOKEN_PLAN,
    KIMI_CODE_PLAN,
)
