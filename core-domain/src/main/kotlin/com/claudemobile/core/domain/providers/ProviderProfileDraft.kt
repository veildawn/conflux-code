package com.claudemobile.core.domain.providers

/**
 * Mutable-by-copy snapshot of the fields that the Provider editor form
 * collects from the user before a [ProviderProfile] is persisted.
 *
 * A [ProviderProfileDraft] carries **only** what the UI form has in hand:
 * raw, un-trimmed string values typed by the user plus the two
 * non-textual selections ([authHeaderStyle] and [presetReference]).
 * Fields that are assigned by the store (`profileId`, `createdAt`,
 * `updatedAt`) are intentionally absent — they are generated at
 * `upsert` time by the data layer (see design §3).
 *
 * The draft is a pure value type. Validation is performed by
 * [ProviderProfile.validate] and does **not** mutate the draft.
 *
 * Requirements: 2.4, 3.2, 3.3, 3.4, 3.5, 3.6, 4.4.
 *
 * @property displayName User-typed label. Raw value; [ProviderProfile.validate]
 *   trims before applying the non-blank ≤ 80 rule.
 * @property baseUrl User-typed endpoint. Raw value; validated against a
 *   pure-Kotlin `https://` check (see [ProviderProfile.validate]).
 *   For preset-derived drafts this field is UI-readonly and expected to
 *   already equal the preset's baseUrl.
 * @property apiKey User-typed API key. **Plaintext** while the draft is in
 *   memory; the data layer encrypts it on persist (R9.1). Must never be
 *   logged or echoed (R6.7, R7.5, R10.1).
 * @property model User-typed model identifier. Raw value; trimmed before
 *   the non-blank rule is applied.
 * @property smallFastModel Optional user-typed small-fast-model identifier.
 *   `null` and empty strings both disable injection of
 *   `ANTHROPIC_SMALL_FAST_MODEL` at spawn time; see design §5.
 * @property authHeaderStyle Which Anthropic header style the profile
 *   should use. Preset-derived drafts inherit this from the preset and
 *   do not expose a chooser.
 * @property presetReference Origin of the draft. For preset-derived
 *   drafts validation additionally enforces that [baseUrl] matches the
 *   preset's fixed baseUrl (R4.4).
 */
public data class ProviderProfileDraft(
    val displayName: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val smallFastModel: String? = null,
    val authHeaderStyle: AuthHeaderStyle,
    val presetReference: PresetReference,
)
