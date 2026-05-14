package com.claudemobile.core.domain.providers

/**
 * A user-owned configuration record binding a provider endpoint
 * (either a built-in [ProviderPreset] or a Custom one) to a user-supplied
 * API key and model overrides.
 *
 * All fields are immutable; updates are performed via
 * [kotlin.copy][copy] and persisted through
 * `ProviderProfileStore.upsert` which is responsible for recomputing
 * [updatedAt].
 *
 * The [apiKey] field holds the **plaintext** key in memory; it is
 * encrypted at rest by the store (EncryptedSharedPreferences +
 * Android Keystore, see R9.1) and must never be written to logs,
 * diagnostics, or un-masked UI (R6.7, R7.5, R10.1).
 *
 * Requirements: 2.1, 2.2, 3.1, 4.1, 4.2, 4.3, 9.2.
 *
 * @property profileId UUIDv4, unique within the `ProviderProfileStore`.
 * @property displayName User-editable label, non-blank and ≤ 80 chars.
 * @property presetReference Origin of the profile; preset id or
 *   [PresetReference.Custom].
 * @property baseUrl `https://…` endpoint URL, without a trailing slash.
 *   For preset-derived profiles the store enforces equality with
 *   `preset.baseUrl` (R4.4).
 * @property apiKey Plaintext API key; see class-level doc for handling
 *   rules.
 * @property model Anthropic model identifier copied into
 *   `ANTHROPIC_MODEL` at spawn time.
 * @property smallFastModel Optional identifier copied into
 *   `ANTHROPIC_SMALL_FAST_MODEL`; `null` or blank disables injection
 *   (R6.6).
 * @property authHeaderStyle Selects which of `ANTHROPIC_API_KEY` /
 *   `ANTHROPIC_AUTH_TOKEN` carries [apiKey] at spawn time (R6.3, R6.4).
 * @property createdAt Epoch millis at which the profile was first
 *   persisted.
 * @property updatedAt Epoch millis at which the profile was last
 *   persisted; monotonically non-decreasing under successive edits
 *   (see Property 5).
 */
public data class ProviderProfile(
    val profileId: String,
    val displayName: String,
    val presetReference: PresetReference,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val smallFastModel: String? = null,
    val authHeaderStyle: AuthHeaderStyle,
    val createdAt: Long,
    val updatedAt: Long,
) {
    /**
     * Masked representation of [apiKey] suitable for display in any UI
     * surface.
     *
     * Implements R9.2: reveals **at most** the last four characters,
     * prefixed by a four-bullet mask (`••••`). Keys of length 4 or less
     * are fully masked so that no substring of the raw key is leaked.
     *
     * Pure function; deterministic in [apiKey]. Never logs, never throws.
     *
     * Requirements: 9.2.
     */
    public fun maskedApiKey(): String =
        if (apiKey.length <= 4) MASK else MASK + apiKey.takeLast(LAST_VISIBLE)

    public companion object {
        private const val MASK: String = "\u2022\u2022\u2022\u2022"
        private const val LAST_VISIBLE: Int = 4

        /**
         * Maximum allowed length of [ProviderProfile.displayName] per
         * design §2's validation table. Measured on the raw string,
         * **not** on the trimmed value, so "80 spaces + real name" is
         * correctly rejected.
         */
        public const val DISPLAY_NAME_MAX_LENGTH: Int = 80

        /**
         * Runs design §2's validation rules against a
         * [ProviderProfileDraft] and returns the aggregated outcome.
         *
         * This overload is the **pure-syntactic** validator: it enforces
         * every rule that depends only on the draft's own fields. It
         * does **not** enforce R4.4's "preset baseUrl is locked" rule
         * (because that requires a [ProviderRegistry] lookup); for that,
         * use the two-argument overload below.
         *
         * ### Rules (per design §2 table)
         *
         * | Field | Rule | Error when violated |
         * |---|---|---|
         * | `displayName` | trimmed non-blank | [ValidationError.DisplayNameBlank] |
         * | `displayName` | raw length ≤ [DISPLAY_NAME_MAX_LENGTH] | [ValidationError.DisplayNameTooLong] |
         * | `baseUrl` | well-formed URL with `https` scheme | [ValidationError.BaseUrlInvalid] |
         * | `apiKey` | length ≥ 1 | [ValidationError.ApiKeyEmpty] |
         * | `model` | trimmed non-blank | [ValidationError.ModelBlank] |
         * | `smallFastModel` | null OR empty OR trimmed non-blank | *(no error — warning only, never a blocking field)* |
         * | `authHeaderStyle` | one of [AuthHeaderStyle] variants | *(enum-guaranteed)* |
         *
         * Rules are evaluated independently per field (R3.3); a failure
         * on one field never suppresses the validator from reporting
         * failures on another. Within a single field, the first failing
         * rule wins — e.g. a blank `displayName` surfaces
         * [ValidationError.DisplayNameBlank] regardless of length.
         *
         * ### URL validation strategy
         *
         * Design §2 describes the baseUrl rule as "parseable by
         * `okhttp3.HttpUrl.parse()` **and** scheme `https`", but
         * `core-domain` is a pure-Kotlin module with no okhttp3
         * dependency (by module boundary — it cannot depend on
         * `core-data`). The implementation therefore uses
         * [java.net.URI] to check:
         *
         *   1. the input parses successfully,
         *   2. the scheme equals `"https"` exactly (case-sensitive, as
         *      per RFC 3986 §3.1 schemes are ASCII and the canonical
         *      form is lowercase; Anthropic endpoints always ship
         *      `https://`),
         *   3. the authority component contains a non-empty host.
         *
         * This is intentionally stricter than okhttp3's permissive
         * parser on edge cases (e.g. `https://`-only with no host),
         * which is the right default for a URL we're about to set as
         * `ANTHROPIC_BASE_URL`. The data-layer
         * [ConnectionTester] performs the authoritative okhttp-backed
         * check at test time.
         *
         * ### Purity
         *
         * Deterministic, total, and side-effect-free: two calls with
         * equal drafts return equal results. No I/O, no logging, never
         * throws (malformed URIs are caught internally).
         *
         * Requirements: 2.4, 3.2, 3.3, 3.4, 3.5, 3.6.
         */
        @JvmStatic
        public fun validate(draft: ProviderProfileDraft): ValidationResult =
            validate(draft, registry = null)

        /**
         * Runs the pure-syntactic rules (see the single-argument
         * overload) **plus** the preset-baseUrl-lock rule from R4.4.
         *
         * Supply a [ProviderRegistry] when calling from a context that
         * has one (ViewModels, use cases, the store). The registry is
         * consulted only when [ProviderProfileDraft.presetReference] is
         * [PresetReference.Preset] and the referenced preset is
         * currently known to the registry. When the preset id cannot be
         * resolved (the user's build shipped a preset that has since
         * been removed), the check is skipped and the draft is treated
         * as if it were a [PresetReference.Custom] for baseUrl
         * purposes.
         *
         * Requirements: 2.4, 3.2, 3.3, 3.4, 3.5, 3.6, 4.4.
         */
        @JvmStatic
        public fun validate(
            draft: ProviderProfileDraft,
            registry: ProviderRegistry?,
        ): ValidationResult {
            val errors = mutableMapOf<ValidationField, ValidationError>()

            // --- displayName ---
            val rawName = draft.displayName
            when {
                rawName.trim().isEmpty() ->
                    errors[ValidationField.DisplayName] = ValidationError.DisplayNameBlank
                rawName.length > DISPLAY_NAME_MAX_LENGTH ->
                    errors[ValidationField.DisplayName] = ValidationError.DisplayNameTooLong
            }

            // --- baseUrl ---
            val baseUrl = draft.baseUrl
            val urlWellFormed = isValidHttpsUrl(baseUrl)
            if (!urlWellFormed) {
                errors[ValidationField.BaseUrl] = ValidationError.BaseUrlInvalid
            } else if (registry != null && draft.presetReference is PresetReference.Preset) {
                val preset = registry.findById(draft.presetReference.presetId)
                if (preset != null && baseUrl != preset.baseUrl) {
                    errors[ValidationField.BaseUrl] = ValidationError.BaseUrlPresetLocked
                }
            }

            // --- apiKey ---
            if (draft.apiKey.isEmpty()) {
                errors[ValidationField.ApiKey] = ValidationError.ApiKeyEmpty
            }

            // --- model ---
            if (draft.model.trim().isEmpty()) {
                errors[ValidationField.Model] = ValidationError.ModelBlank
            }

            // --- smallFastModel: null/empty allowed; no blocking errors ---

            return if (errors.isEmpty()) ValidationResult.VALID
            else ValidationResult(errors.toMap())
        }

        /**
         * Pure-Kotlin HTTPS URL check used in place of
         * `okhttp3.HttpUrl.parse()` (see [validate] KDoc for rationale).
         *
         * Returns `true` iff [s]:
         *   - parses as a [java.net.URI] without throwing, and
         *   - has scheme exactly `"https"`, and
         *   - has a non-blank host component.
         *
         * All other inputs (including `null`-equivalents like empty
         * strings, opaque URIs like `mailto:…`, `http://…`, URIs with
         * no authority) return `false`.
         */
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
    }
}
