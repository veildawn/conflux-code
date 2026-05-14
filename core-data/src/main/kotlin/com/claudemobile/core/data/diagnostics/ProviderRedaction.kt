/**
 * # ProviderRedaction — cross-reference note
 *
 * **Extends** `android-claude-termux-client` base-spec Requirement 13,
 * AC 3 and AC 5 (diagnostics redaction) to cover multi-profile
 * credential sets.
 *
 * The base spec required that the single stored Anthropic API key be
 * absent from diagnostics exports (R13 AC3) and that the diagnostics log
 * never record the key at write time (R13 AC5). This file generalises
 * both rules to every [com.claudemobile.core.domain.providers.ProviderProfile]
 * currently held in the [com.claudemobile.core.domain.providers.ProviderProfileStore]:
 *
 * - Every `p.apiKey` across all profiles is replaced with the redaction
 *   marker `•••REDACTED•••` in the exported text (extends R13 AC3).
 * - Every `p.baseUrl` that carries a `scheme://user:token@host` userinfo
 *   segment has that segment replaced with `REDACTED@` (new rule, no
 *   base-spec equivalent).
 *
 * The source-site non-logging rule (base-spec R13 AC5) is enforced at
 * the call sites (`CliBridgeImpl`, `ConnectionTesterImpl`,
 * `SpawnEnvAdapter.toSafeString`) and is not repeated here; this
 * function is the second line of defence (defence-in-depth).
 *
 * **Implements** `ai-provider-presets` Requirement 10 (Diagnostics
 * Redaction Across All Profiles), specifically AC 1–4; Properties 14
 * and 15.
 *
 * See also: `DiagnosticsRepositoryImpl` for the call site that wires
 * [redactProviderSecrets] into the export pipeline, and
 * [ProviderProfileSnapshot] for the functional interface that supplies
 * the profile list without a hard compile-time dependency on the store.
 */
package com.claudemobile.core.data.diagnostics

import com.claudemobile.core.domain.providers.ProviderProfile

/**
 * Redaction marker used in place of any matched `apiKey`. Kept distinct
 * from the base-spec single-key marker (`[REDACTED]`) so that diagnostic
 * readers can tell which layer stripped a given value.
 */
private const val API_KEY_REDACTION_MARKER: String = "\u2022\u2022\u2022REDACTED\u2022\u2022\u2022"

/**
 * Regex matching a `scheme://userinfo@` segment where `userinfo` contains
 * at least one `:` (i.e. the `user:token` form carried in URL userinfo).
 *
 * - Group 1 captures the scheme (for reconstruction).
 * - Group 2 captures the `user:token` segment that is to be redacted.
 *
 * The pattern is intentionally tight: it only matches the three known
 * userinfo-bearing URL shapes that can appear in a `ProviderProfile.baseUrl`
 * value (`https://u:t@host`, and the rare `http://` / `ws://` variants a
 * future preset could use). It deliberately stops at the first `/`, `?`,
 * `#`, whitespace, or `@`, so embedded colons in the token do not
 * over-consume.
 */
private val USERINFO_REGEX: Regex =
    Regex("""([A-Za-z][A-Za-z0-9+.\-]*://)([^/@\s?#]+:[^/@\s?#]*)@""")

/**
 * Pure defence-in-depth redaction for diagnostics exports covering every
 * [ProviderProfile] currently tracked by the caller.
 *
 * **Two-rule strategy (design §9.2):**
 *
 * 1. For each non-empty `p.apiKey`, every occurrence of the raw key in
 *    [text] is replaced with the literal marker [API_KEY_REDACTION_MARKER]
 *    (`•••REDACTED•••`).
 * 2. For each `p.baseUrl` that contains a `scheme://user:token@host` style
 *    userinfo segment, every occurrence of the matching `user:token@`
 *    substring in [text] is replaced with `REDACTED@`, preserving the
 *    scheme, host, and path of any URL the text contains.
 *
 * **Relationship to the base spec (R13 AC3 / AC5):**
 * This function is the second line of defence, complementary to the
 * source-site non-logging rules in R13 AC3 and R13 AC5 of the
 * `android-claude-termux-client` base spec and to R10 AC4 of this
 * spec. Logging sites (`CliBridgeImpl`, `ConnectionTester`,
 * `SpawnEnvAdapter.toSafeString`) are required to never write
 * `apiKey` / `baseUrl` to a diagnostics record in the first place, so a
 * well-behaved system never triggers any replacement here. The function
 * nevertheless operates as a safety net against future regressions or
 * accidental passthrough.
 *
 * **Purity contract:**
 * - No I/O, no clock reads, no global mutable state access.
 * - Deterministic: identical `(text, profiles)` inputs produce identical
 *   output.
 * - Safe for arbitrary input: empty `apiKey` is skipped (an empty-string
 *   replacement in Kotlin is a no-op but the early skip avoids
 *   surprising repeated-marker splices in pathological inputs).
 *
 * Requirements: 10.1, 10.2, 10.3, 10.4; Properties 14 and 15.
 *
 * @param text the original diagnostics export text.
 * @param profiles every profile the caller wants scrubbed; typically the
 *   full set returned by `ProviderProfileStore.list()`.
 * @return the redacted text.
 */
public fun redactProviderSecrets(
    text: String,
    profiles: List<ProviderProfile>,
): String {
    if (text.isEmpty() || profiles.isEmpty()) return text

    var redacted: String = text

    // Rule 1: replace each apiKey with the marker.
    for (profile in profiles) {
        val key: String = profile.apiKey
        if (key.isNotEmpty()) {
            redacted = redacted.replace(key, API_KEY_REDACTION_MARKER)
        }
    }

    // Rule 2: replace per-profile userinfo segments (if any) in the text.
    for (profile in profiles) {
        val userinfo: String? = extractUserinfo(profile.baseUrl)
        if (userinfo != null) {
            redacted = redacted.replace("$userinfo@", "REDACTED@")
        }
    }

    return redacted
}

/**
 * Extracts the `user:token` substring from a URL that carries userinfo.
 *
 * Returns `null` for URLs without userinfo or for strings that do not
 * parse as a `scheme://...` URL. Exposed as a pure helper so the
 * two-rule strategy above reads linearly.
 */
private fun extractUserinfo(baseUrl: String): String? {
    val match: MatchResult = USERINFO_REGEX.find(baseUrl) ?: return null
    return match.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }
}

/**
 * Minimal hook used by [DiagnosticsRepositoryImpl] to enumerate every
 * stored [ProviderProfile] at export time without taking a hard
 * compile-time dependency on `ProviderProfileStore` (whose implementation
 * and DI binding are introduced by sibling tasks in the
 * `ai-provider-presets` spec).
 *
 * Intended binding once the store lands:
 *
 * ```kotlin
 * // core-data/providers/di/ProviderProfileModule.kt (task 3.6)
 * @Provides
 * fun provideProviderProfileSnapshot(
 *     store: ProviderProfileStore,
 * ): ProviderProfileSnapshot = ProviderProfileSnapshot { store.list() }
 * ```
 *
 * Until then, [DiagnosticsModule] provides a no-op default that returns
 * an empty list, so the pre-existing base-spec diagnostics path behaves
 * exactly as before.
 *
 * Requirements: 10.1, 10.3.
 */
public fun interface ProviderProfileSnapshot {
    /**
     * Returns every profile currently tracked by the caller. Must not
     * throw for empty stores; return an empty list instead.
     */
    public suspend fun list(): List<ProviderProfile>
}
