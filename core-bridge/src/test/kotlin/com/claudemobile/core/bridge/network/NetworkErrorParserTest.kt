package com.claudemobile.core.bridge.network

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [NetworkErrorParser].
 *
 * Validates: Requirements 11.2 (parse known network-error signatures from CLI stderr)
 */
class NetworkErrorParserTest {

    private lateinit var parser: NetworkErrorParser

    @BeforeEach
    fun setup() {
        parser = NetworkErrorParser()
    }

    @Nested
    @DisplayName("ECONNREFUSED detection")
    inner class EconnRefused {

        @Test
        fun `detects ECONNREFUSED in stderr`() {
            val line = "Error: connect ECONNREFUSED 127.0.0.1:443"
            val result = parser.parse(line)

            result.shouldNotBeNull()
            result.errorType shouldBe NetworkErrorParser.NetworkErrorType.CONNECTION_REFUSED
            result.rawOutput shouldBe line
        }

        @Test
        fun `detects ECONNREFUSED case-insensitively`() {
            val line = "error: econnrefused on api.anthropic.com"
            val result = parser.parse(line)

            result.shouldNotBeNull()
            result.errorType shouldBe NetworkErrorParser.NetworkErrorType.CONNECTION_REFUSED
        }
    }

    @Nested
    @DisplayName("ETIMEDOUT detection")
    inner class Etimedout {

        @Test
        fun `detects ETIMEDOUT in stderr`() {
            val line = "Error: connect ETIMEDOUT 104.18.6.192:443"
            val result = parser.parse(line)

            result.shouldNotBeNull()
            result.errorType shouldBe NetworkErrorParser.NetworkErrorType.CONNECTION_TIMEOUT
            result.rawOutput shouldBe line
        }

        @Test
        fun `detects ETIMEDOUT case-insensitively`() {
            val line = "request failed: etimedout"
            val result = parser.parse(line)

            result.shouldNotBeNull()
            result.errorType shouldBe NetworkErrorParser.NetworkErrorType.CONNECTION_TIMEOUT
        }
    }

    @Nested
    @DisplayName("ENETUNREACH detection")
    inner class Enetunreach {

        @Test
        fun `detects ENETUNREACH in stderr`() {
            val line = "Error: connect ENETUNREACH 2606:4700::6812:6c0:443"
            val result = parser.parse(line)

            result.shouldNotBeNull()
            result.errorType shouldBe NetworkErrorParser.NetworkErrorType.NETWORK_UNREACHABLE
            result.rawOutput shouldBe line
        }
    }

    @Nested
    @DisplayName("getaddrinfo detection")
    inner class Getaddrinfo {

        @Test
        fun `detects getaddrinfo failure in stderr`() {
            val line = "Error: getaddrinfo ENOTFOUND api.anthropic.com"
            val result = parser.parse(line)

            result.shouldNotBeNull()
            result.errorType shouldBe NetworkErrorParser.NetworkErrorType.DNS_FAILURE
            result.rawOutput shouldBe line
        }

        @Test
        fun `detects getaddrinfo EAI_AGAIN`() {
            val line = "getaddrinfo EAI_AGAIN api.anthropic.com"
            val result = parser.parse(line)

            result.shouldNotBeNull()
            result.errorType shouldBe NetworkErrorParser.NetworkErrorType.DNS_FAILURE
        }
    }

    @Nested
    @DisplayName("socket hang up detection")
    inner class SocketHangUp {

        @Test
        fun `detects socket hang up in stderr`() {
            val line = "Error: socket hang up"
            val result = parser.parse(line)

            result.shouldNotBeNull()
            result.errorType shouldBe NetworkErrorParser.NetworkErrorType.CONNECTION_DROPPED
            result.rawOutput shouldBe line
        }

        @Test
        fun `detects socket hang up in longer message`() {
            val line = "FetchError: request to https://api.anthropic.com failed, reason: socket hang up"
            val result = parser.parse(line)

            result.shouldNotBeNull()
            result.errorType shouldBe NetworkErrorParser.NetworkErrorType.CONNECTION_DROPPED
        }
    }

    @Nested
    @DisplayName("generic network error detection")
    inner class GenericNetworkError {

        @Test
        fun `detects generic network error in stderr`() {
            val line = "TypeError: Network Error"
            val result = parser.parse(line)

            result.shouldNotBeNull()
            result.errorType shouldBe NetworkErrorParser.NetworkErrorType.GENERIC_NETWORK_ERROR
            result.rawOutput shouldBe line
        }

        @Test
        fun `detects network error in fetch failure`() {
            val line = "FetchError: network error when attempting to fetch resource"
            val result = parser.parse(line)

            result.shouldNotBeNull()
            result.errorType shouldBe NetworkErrorParser.NetworkErrorType.GENERIC_NETWORK_ERROR
        }
    }

    @Nested
    @DisplayName("Non-network errors")
    inner class NonNetworkErrors {

        @Test
        fun `returns null for non-network error`() {
            val line = "SyntaxError: Unexpected token < in JSON at position 0"
            val result = parser.parse(line)

            result.shouldBeNull()
        }

        @Test
        fun `returns null for empty string`() {
            val result = parser.parse("")
            result.shouldBeNull()
        }

        @Test
        fun `returns null for normal output`() {
            val line = "Claude: I'll help you with that task."
            val result = parser.parse(line)

            result.shouldBeNull()
        }

        @Test
        fun `returns null for permission errors`() {
            val line = "Error: EACCES: permission denied, open '/etc/passwd'"
            val result = parser.parse(line)

            result.shouldBeNull()
        }

        @Test
        fun `returns null for file system errors`() {
            val line = "Error: ENOENT: no such file or directory"
            val result = parser.parse(line)

            result.shouldBeNull()
        }
    }

    @Nested
    @DisplayName("isNetworkError convenience method")
    inner class IsNetworkError {

        @Test
        fun `returns true for known network error`() {
            parser.isNetworkError("Error: connect ECONNREFUSED 127.0.0.1:443") shouldBe true
        }

        @Test
        fun `returns false for non-network error`() {
            parser.isNetworkError("Error: file not found") shouldBe false
        }

        @Test
        fun `returns false for empty string`() {
            parser.isNetworkError("") shouldBe false
        }
    }

    @Nested
    @DisplayName("User messages")
    inner class UserMessages {

        @Test
        fun `provides user-friendly message for each error type`() {
            val testCases = mapOf(
                "ECONNREFUSED" to NetworkErrorParser.NetworkErrorType.CONNECTION_REFUSED,
                "ETIMEDOUT" to NetworkErrorParser.NetworkErrorType.CONNECTION_TIMEOUT,
                "ENETUNREACH" to NetworkErrorParser.NetworkErrorType.NETWORK_UNREACHABLE,
                "getaddrinfo" to NetworkErrorParser.NetworkErrorType.DNS_FAILURE,
                "socket hang up" to NetworkErrorParser.NetworkErrorType.CONNECTION_DROPPED,
                "network error" to NetworkErrorParser.NetworkErrorType.GENERIC_NETWORK_ERROR,
            )

            testCases.forEach { (input, expectedType) ->
                val result = parser.parse("Error: $input")
                result.shouldNotBeNull()
                result.errorType shouldBe expectedType
                result.userMessage.isNotBlank() shouldBe true
            }
        }
    }
}
