package com.claudemobile.core.domain.providers

/**
 * 将 Active_Profile 转换为 Anthropic 兼容环境变量。纯函数、无 I/O、不依赖 Android。
 *
 * Transforms the Active_Profile into Anthropic-compatible environment
 * variables.  Pure function — no I/O, no Android dependencies, no
 * access to global or mutable state.
 *
 * Relationship to the base spec: the [base] parameter corresponds to
 * the `{HOME, PATH, TERM, LANG}` environment inherited from the Termux
 * rootfs by base-spec R2 AC3. This function overlays the Anthropic
 * variables on top of it.
 *
 * Semantics (design §5):
 *
 * 1. The keys `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN`, and
 *    `ANTHROPIC_SMALL_FAST_MODEL` are always first removed from
 *    [base] so stale values cannot leak across spawns and the
 *    auth-header mutual-exclusion invariant is preserved.
 * 2. `ANTHROPIC_BASE_URL` is set to [ProviderProfile.baseUrl].
 * 3. Exactly one of `ANTHROPIC_API_KEY` or `ANTHROPIC_AUTH_TOKEN` is
 *    set to [ProviderProfile.apiKey], selected by
 *    [ProviderProfile.authHeaderStyle]:
 *    - [AuthHeaderStyle.ApiKey]    → `ANTHROPIC_API_KEY`
 *    - [AuthHeaderStyle.AuthToken] → `ANTHROPIC_AUTH_TOKEN`
 * 4. `ANTHROPIC_MODEL` is set to [ProviderProfile.model].
 * 5. `ANTHROPIC_SMALL_FAST_MODEL` is set to
 *    [ProviderProfile.smallFastModel] **iff** that field is non-null
 *    and non-blank; otherwise it is not present in the output.
 * 6. All other entries in [base] are preserved unchanged.
 *
 * This function underpins Property 10 (environment injection) and
 * Property 11 (determinism / purity).
 *
 * Requirements: 6.2, 6.3, 6.4, 6.5, 6.6, 12.5.
 *
 * @param base Existing environment variables (e.g. `HOME`, `PATH`,
 *   `TERM`, `LANG`). Must not be mutated by the caller during the
 *   call; the returned map is a fresh instance.
 * @param profile The currently active [ProviderProfile].
 * @return A new env map containing every non-conflicting key of [base]
 *   plus the Anthropic-compatible variables derived from [profile].
 */
public fun buildClaudeEnv(
    base: Map<String, String>,
    profile: ProviderProfile,
): Map<String, String> {
    val out = base.toMutableMap()

    // 移除可能遗留的旧值，保证互斥
    // Strip any stale values to preserve the auth-header mutual-exclusion invariant.
    out.remove("ANTHROPIC_API_KEY")
    out.remove("ANTHROPIC_AUTH_TOKEN")
    out.remove("ANTHROPIC_SMALL_FAST_MODEL")

    out["ANTHROPIC_BASE_URL"] = profile.baseUrl

    when (profile.authHeaderStyle) {
        AuthHeaderStyle.ApiKey    -> out["ANTHROPIC_API_KEY"]    = profile.apiKey
        AuthHeaderStyle.AuthToken -> out["ANTHROPIC_AUTH_TOKEN"] = profile.apiKey
    }

    out["ANTHROPIC_MODEL"] = profile.model

    profile.smallFastModel
        ?.takeIf { it.isNotBlank() }
        ?.let { out["ANTHROPIC_SMALL_FAST_MODEL"] = it }

    return out
}
