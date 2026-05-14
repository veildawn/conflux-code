package com.claudemobile.core.domain.providers

/**
 * Identifier for a single editable field on a [ProviderProfileDraft].
 *
 * The ordering matches the editor screen's visual top-to-bottom layout
 * (see design §6.1) so that a consumer iterating [ValidationResult.errors]
 * for summary display preserves a natural reading order.
 *
 * Requirements: 3.3.
 */
public enum class ValidationField {
    /** Maps to [ProviderProfileDraft.displayName]. */
    DisplayName,

    /** Maps to [ProviderProfileDraft.baseUrl]. */
    BaseUrl,

    /** Maps to [ProviderProfileDraft.apiKey]. */
    ApiKey,

    /** Maps to [ProviderProfileDraft.model]. */
    Model,

    /** Maps to [ProviderProfileDraft.smallFastModel]. */
    SmallFastModel,
}

/**
 * Reason a field on a [ProviderProfileDraft] failed validation.
 *
 * Modelled as an enum rather than a free-form message so that the UI
 * layer is responsible for localization (R3.3 requires per-field
 * validation state, not a specific wording). The set of errors is
 * intentionally closed so that exhaustive `when` expressions in the
 * ViewModel catch new kinds at compile time.
 *
 * Requirements: 2.4, 3.2, 3.3, 3.4, 3.5, 3.6, 4.4.
 */
public enum class ValidationError {

    /**
     * `displayName` is blank (empty or whitespace-only) after trimming.
     *
     * Triggered per design §2's "trimmed non-empty" rule.
     */
    DisplayNameBlank,

    /**
     * `displayName` exceeds the 80-character cap.
     *
     * Length is measured on the raw (un-trimmed) string; a
     * 90-character name with surrounding whitespace still fails.
     */
    DisplayNameTooLong,

    /**
     * `baseUrl` is not a well-formed `https://…` URL.
     *
     * Covers both malformed inputs (URI parsing failure) and well-formed
     * URLs whose scheme is not `https` (e.g. `http://`, `ftp://`).
     * A separate [BaseUrlPresetLocked] variant is produced only when
     * the URL *is* well-formed but conflicts with a preset's fixed value.
     */
    BaseUrlInvalid,

    /**
     * `baseUrl` is well-formed but differs from the
     * [ProviderPreset.baseUrl] that the owning draft's
     * [PresetReference.Preset] points at.
     *
     * Distinct from [BaseUrlInvalid] so the UI layer can explain that
     * the baseUrl is fixed for preset-derived profiles (R4.4), rather
     * than simply "invalid URL".
     *
     * This check is only performed when a [ProviderRegistry] is passed
     * to [ProviderProfile.validate]; pure-syntactic callers that don't
     * have registry access will never see this variant.
     */
    BaseUrlPresetLocked,

    /**
     * `apiKey` is the empty string (length 0).
     *
     * Whitespace-only keys are intentionally **not** flagged because
     * design §2's rule is "length ≥ 1", not "non-blank" — some
     * providers accept keys containing whitespace as part of the token.
     */
    ApiKeyEmpty,

    /**
     * `model` is blank (empty or whitespace-only) after trimming.
     */
    ModelBlank,
}

/**
 * Outcome of running [ProviderProfile.validate] against a
 * [ProviderProfileDraft].
 *
 * Pure value type: equal drafts produce equal [ValidationResult]s.
 * An [errors] map that is empty means every rule in design §2 held;
 * otherwise the map associates each failing field with the specific
 * [ValidationError] that fired (at most one per field — validation
 * stops at the first error per field).
 *
 * The [isValid] shortcut exists so that ViewModel `submitEnabled`
 * bindings (see design §6.2 `FormState.submitEnabled`) can be
 * expressed in a single property access rather than `errors.isEmpty()`
 * at each call site.
 *
 * Requirements: 2.4, 3.2, 3.3.
 *
 * @property errors Per-field validation errors. Keys are
 *   [ValidationField]s; values are the (first) [ValidationError] that
 *   fired for that field. Fields that passed validation are **absent**
 *   from the map, not present with a `null` value.
 */
public data class ValidationResult(
    val errors: Map<ValidationField, ValidationError>,
) {
    /**
     * `true` iff no field reported a blocking [ValidationError].
     *
     * Equivalent to `errors.isEmpty()`.
     */
    public val isValid: Boolean
        get() = errors.isEmpty()

    /**
     * Convenience accessor returning the (first) error recorded for
     * [field], or `null` if that field passed validation.
     */
    public fun errorFor(field: ValidationField): ValidationError? = errors[field]

    public companion object {
        /** Singleton representing "everything passed". */
        public val VALID: ValidationResult = ValidationResult(emptyMap())
    }
}
