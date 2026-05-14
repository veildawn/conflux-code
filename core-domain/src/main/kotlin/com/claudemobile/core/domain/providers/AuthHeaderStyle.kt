package com.claudemobile.core.domain.providers

/**
 * Which HTTP header style a Provider expects for Anthropic-compatible
 * authentication.
 *
 * - [ApiKey]: sent as `x-api-key: <value>` header; mapped to the
 *   `ANTHROPIC_API_KEY` environment variable when a Claude CLI process
 *   is spawned.
 * - [AuthToken]: sent as `Authorization: Bearer <value>` header; mapped
 *   to the `ANTHROPIC_AUTH_TOKEN` environment variable when a Claude CLI
 *   process is spawned.
 *
 * At most one of the two environment variables is ever set for a given
 * Active_Profile; see `buildClaudeEnv` and design §5.
 *
 * Requirements: 6.3, 6.4.
 */
public enum class AuthHeaderStyle {
    ApiKey,
    AuthToken,
}
