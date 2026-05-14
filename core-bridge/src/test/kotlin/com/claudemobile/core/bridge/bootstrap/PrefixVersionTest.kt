package com.claudemobile.core.bridge.bootstrap

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class PrefixVersionTest {

    @Test
    fun `toJson produces valid JSON`() {
        val version = PrefixVersion(
            prefixVersion = "1.0.0",
            extractedAt = "2025-01-15T10:30:00Z",
            archHash = "sha256:abc123"
        )

        val json = version.toJson()
        json shouldNotBe null
        json.contains("1.0.0") shouldBe true
        json.contains("2025-01-15T10:30:00Z") shouldBe true
        json.contains("sha256:abc123") shouldBe true
    }

    @Test
    fun `fromJson parses valid JSON`() {
        val json = """
            {
                "prefixVersion": "2.0.0",
                "extractedAt": "2025-02-01T12:00:00Z",
                "archHash": "sha256:def456"
            }
        """.trimIndent()

        val version = PrefixVersion.fromJson(json)
        version shouldNotBe null
        version!!.prefixVersion shouldBe "2.0.0"
        version.extractedAt shouldBe "2025-02-01T12:00:00Z"
        version.archHash shouldBe "sha256:def456"
    }

    @Test
    fun `fromJson returns null for invalid JSON`() {
        val version = PrefixVersion.fromJson("not valid json")
        version shouldBe null
    }

    @Test
    fun `fromJson returns null for empty string`() {
        val version = PrefixVersion.fromJson("")
        version shouldBe null
    }

    @Test
    fun `fromJson returns null for JSON missing required fields`() {
        val json = """{"prefixVersion": "1.0.0"}"""
        val version = PrefixVersion.fromJson(json)
        version shouldBe null
    }

    @Test
    fun `toJson and fromJson round-trip`() {
        val original = PrefixVersion(
            prefixVersion = "3.1.4",
            extractedAt = "2025-03-14T15:09:26Z",
            archHash = "sha256:pi314159"
        )

        val json = original.toJson()
        val parsed = PrefixVersion.fromJson(json)

        parsed shouldBe original
    }

    @Test
    fun `toJson and fromJson round-trip with special characters`() {
        val original = PrefixVersion(
            prefixVersion = "1.0.0-beta.1",
            extractedAt = "2025-01-01T00:00:00+05:30",
            archHash = "sha256:0123456789abcdef"
        )

        val json = original.toJson()
        val parsed = PrefixVersion.fromJson(json)

        parsed shouldBe original
    }
}
