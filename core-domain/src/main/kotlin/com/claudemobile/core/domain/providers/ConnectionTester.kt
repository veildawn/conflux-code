package com.claudemobile.core.domain.providers

/**
 * Classification buckets for the outcome of a single Connection_Test probe
 * against a [ProviderProfile]'s endpoint.
 *
 * The enum is the **primary** consumer-facing output of
 * [ConnectionTester.test]; UI layers map enum values to localized strings
 * via their own resources, and diagnostics / telemetry key on the enum so
 * that the human-readable [ConnectionTestResult.userReason] never has to
 * be parsed.
 *
 * Classification follows the decision table in design §7.2:
 *
 * | Event                                                                 | Outcome          |
 * |----------------------------------------------------------------------|------------------|
 * | `HttpUrl.parse(baseUrl) == null` or scheme != `https`                 | [InvalidUrl]     |
 * | `UnknownHostException` / `ConnectException` / `SocketTimeoutException`| [Unreachable]    |
 * | HTTP 401 / 403                                                        | [Unauthorized]   |
 * | HTTP 2xx                                                              | [Ok]             |
 * | HTTP 400 + body has `"invalid_request_error"` and no `"authentication"`| [Ok]            |
 * | HTTP 404 or body `error.type == "not_found_error"` mentioning `model` | [InvalidModel]   |
 * | HTTP 5xx or any other response                                        | [UnknownError]   |
 *
 * Requirements: 7.2, 7.6, 7.7.
 */
public enum class ConnectionTestOutcome {
    /** Provider accepted the probe; credentials and model are plausible. */
    Ok,

    /** Provider rejected the credential (HTTP 401 / 403). */
    Unauthorized,

    /** Provider could not be reached (DNS, connect, or read timeout). */
    Unreachable,

    /**
     * `baseUrl` could not be parsed as an HTTPS URL. Signals that the
     * probe was **not** sent over the network.
     */
    InvalidUrl,

    /** Provider reached but the configured model is unknown to it. */
    InvalidModel,

    /**
     * Any other non-success response (HTTP 5xx, unexpected status, or an
     * exception that did not match the [Unreachable] classifier).
     */
    UnknownError,
}

/**
 * Outcome of a single Connection_Test probe.
 *
 * @property outcome the classified [ConnectionTestOutcome]; always the
 *   canonical consumer-facing value.
 * @property userReason short, user-facing description that is safe to
 *   display verbatim in UI and to include in exported diagnostics. The
 *   string is guaranteed to **never** contain the
 *   [ProviderProfile.apiKey] of the probed profile, nor any HTTP header
 *   value from the probe request (R7.5, R10.4).
 *
 * The reason carried here is intentionally terse and language-neutral;
 * UI layers that need full localization should key a resource lookup on
 * [outcome] and ignore [userReason] for presentation, treating this
 * field as a diagnostic / fallback.
 *
 * Requirements: 7.2, 7.3, 7.5.
 */
public data class ConnectionTestResult(
    val outcome: ConnectionTestOutcome,
    val userReason: String,
)

/**
 * Component that validates a [ProviderProfile]'s connectivity and
 * credential without opening a full CLI Session.
 *
 * The interface lives in `core-domain` rather than `core-data` so that
 * callers in the Bridge and feature layers can depend on network
 * behavior through an Android-free abstraction; the only implementation,
 * `ConnectionTesterImpl`, lives in `core-data` with its OkHttp dependency.
 *
 * ## Contract
 *
 * [test] **never** throws: every possible failure mode — including
 * malformed URLs, socket timeouts, TLS handshake failures, or HTTP 5xx
 * replies — must be reflected in the returned [ConnectionTestResult].
 *
 * [test] **never** surfaces the [ProviderProfile.apiKey] in the returned
 * [ConnectionTestResult.userReason], in any thrown exception, or in any
 * log sink that the implementation writes to (R7.5, R10.4).
 *
 * [test] must complete within the 15-second Connection_Test budget
 * defined in R7.2.
 *
 * Requirements: 7.1, 7.2, 7.3, 7.5, 7.6, 7.7.
 */
public interface ConnectionTester {

    /**
     * Probes the provider described by [profile] and returns a
     * classified [ConnectionTestResult].
     *
     * Suspending; must be called from a coroutine. The call dispatches
     * to an I/O dispatcher internally.
     *
     * @param profile the profile to probe; [ProviderProfile.apiKey] is
     *   the only authentication material the tester may read, and is
     *   never returned in the result.
     */
    public suspend fun test(profile: ProviderProfile): ConnectionTestResult
}
