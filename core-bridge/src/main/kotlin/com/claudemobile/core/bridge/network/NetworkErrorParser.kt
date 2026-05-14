package com.claudemobile.core.bridge.network

/**
 * Parses CLI stderr output to detect known network error signatures.
 *
 * When the Claude CLI process encounters a network error, it typically
 * outputs recognizable error patterns to stderr. This parser identifies
 * those patterns so the UI can render them as structured system messages
 * with a retry action, rather than raw error text.
 *
 * Known network error patterns:
 * - ECONNREFUSED: Connection refused (server not reachable)
 * - ETIMEDOUT: Connection timed out
 * - ENETUNREACH: Network unreachable
 * - getaddrinfo: DNS resolution failure
 * - socket hang up: Connection dropped unexpectedly
 * - network error: Generic network error from HTTP clients
 */
public class NetworkErrorParser {

    /**
     * Result of parsing a stderr line for network errors.
     */
    public data class NetworkError(
        /** The specific error pattern that was matched. */
        val errorType: NetworkErrorType,
        /** A user-friendly description of the error. */
        val userMessage: String,
        /** The original stderr content that triggered the match. */
        val rawOutput: String,
    )

    /**
     * Classification of recognized network error types.
     */
    public enum class NetworkErrorType {
        CONNECTION_REFUSED,
        CONNECTION_TIMEOUT,
        NETWORK_UNREACHABLE,
        DNS_FAILURE,
        CONNECTION_DROPPED,
        GENERIC_NETWORK_ERROR,
    }

    /**
     * Attempts to parse the given [stderrLine] for known network error patterns.
     *
     * @param stderrLine A line of stderr output from the CLI process.
     * @return A [NetworkError] if a known pattern is detected, or `null` if the
     *         line does not contain a recognized network error.
     */
    public fun parse(stderrLine: String): NetworkError? {
        for (pattern in KNOWN_PATTERNS) {
            if (pattern.regex.containsMatchIn(stderrLine)) {
                return NetworkError(
                    errorType = pattern.errorType,
                    userMessage = pattern.userMessage,
                    rawOutput = stderrLine,
                )
            }
        }
        return null
    }

    /**
     * Checks whether the given [stderrLine] contains any known network error pattern.
     *
     * This is a lightweight check that avoids constructing a full [NetworkError] object.
     *
     * @param stderrLine A line of stderr output from the CLI process.
     * @return `true` if the line matches a known network error pattern.
     */
    public fun isNetworkError(stderrLine: String): Boolean {
        return KNOWN_PATTERNS.any { it.regex.containsMatchIn(stderrLine) }
    }

    private companion object {
        val KNOWN_PATTERNS: List<ErrorPattern> = listOf(
            ErrorPattern(
                regex = Regex("ECONNREFUSED", RegexOption.IGNORE_CASE),
                errorType = NetworkErrorType.CONNECTION_REFUSED,
                userMessage = "Connection refused. The server may be unavailable.",
            ),
            ErrorPattern(
                regex = Regex("ETIMEDOUT", RegexOption.IGNORE_CASE),
                errorType = NetworkErrorType.CONNECTION_TIMEOUT,
                userMessage = "Connection timed out. Please check your network and try again.",
            ),
            ErrorPattern(
                regex = Regex("ENETUNREACH", RegexOption.IGNORE_CASE),
                errorType = NetworkErrorType.NETWORK_UNREACHABLE,
                userMessage = "Network unreachable. Please check your connection.",
            ),
            ErrorPattern(
                regex = Regex("getaddrinfo", RegexOption.IGNORE_CASE),
                errorType = NetworkErrorType.DNS_FAILURE,
                userMessage = "DNS resolution failed. Please check your network connection.",
            ),
            ErrorPattern(
                regex = Regex("socket hang up", RegexOption.IGNORE_CASE),
                errorType = NetworkErrorType.CONNECTION_DROPPED,
                userMessage = "Connection was dropped unexpectedly. Please try again.",
            ),
            ErrorPattern(
                regex = Regex("network error", RegexOption.IGNORE_CASE),
                errorType = NetworkErrorType.GENERIC_NETWORK_ERROR,
                userMessage = "A network error occurred. Please check your connection and try again.",
            ),
        )
    }
}

/**
 * Internal representation of a known network error pattern.
 */
internal data class ErrorPattern(
    val regex: Regex,
    val errorType: NetworkErrorParser.NetworkErrorType,
    val userMessage: String,
)
