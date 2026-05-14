package com.claudemobile.core.domain.bridge

/**
 * Domain-level error hierarchy for the CLI bridge layer.
 *
 * Represents recoverable / expected failure conditions that the bridge surfaces
 * to upstream callers (e.g., via `Result.failure(...)` from adapters or as the
 * payload of `BridgeEvent.Error`). These are distinct from generic [Throwable]s
 * because they describe *classified* bridge-domain conditions that UI layers
 * can react to deterministically (routing, localized messaging, etc.).
 *
 * NOTE: This file is an **initial placeholder** introduced by the
 * `ai-provider-presets` spec (task 1.12). The base-spec eventually owns the
 * full `BridgeError` hierarchy; when that lands, additional variants (e.g.,
 * process spawn failures, PTY errors, bootstrap failures) should be merged
 * into this same sealed class rather than forked into a parallel type. Keep
 * this type `public` and `sealed` so consumers can exhaustively match on it.
 *
 * Extends [Throwable] so instances can be carried inside [Result.failure],
 * matching the convention used by `ProviderProfileStoreError`. No state is
 * carried that could leak sensitive values; subclasses must remain free of
 * `apiKey` / `baseUrl` payloads (R6.7, R10.4).
 */
public sealed class BridgeError : Throwable() {

    /**
     * Emitted when a CLI spawn is attempted while no active AI provider
     * profile is selected (i.e., `ProviderProfileStore.getActive()` returned
     * `null`).
     *
     * The bridge aborts the spawn and upstream UI is expected to route the
     * user to the provider-selection screen rather than showing a generic
     * error.
     *
     * Related requirements: R5 AC5, R6 AC8, R11 AC5 (`ai-provider-presets`).
     */
    public data object NoActiveProfile : BridgeError()
}
