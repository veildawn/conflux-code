package com.claudemobile.core.common

import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset

class TimeProviderTest {

    @Test
    fun `DefaultTimeProvider returns current time`() {
        val provider = DefaultTimeProvider()
        val before = Instant.now()
        val result = provider.now()
        val after = Instant.now()

        (result == before || result.isAfter(before)) shouldBe true
        (result == after || result.isBefore(after)) shouldBe true
    }

    @Test
    fun `TimeProvider interface can be implemented for testing`() {
        val fixedInstant = Instant.parse("2024-06-15T10:30:00Z")
        val testProvider = object : TimeProvider {
            override fun now(): Instant = fixedInstant
        }

        testProvider.now() shouldBe fixedInstant
    }

    @Test
    fun `toEpochMillis converts instant to millis`() {
        val instant = Instant.parse("2024-01-01T00:00:00Z")

        instant.toEpochMillis() shouldBe 1704067200000L
    }

    @Test
    fun `toInstant converts millis to instant`() {
        val millis = 1704067200000L

        millis.toInstant() shouldBe Instant.parse("2024-01-01T00:00:00Z")
    }

    @Test
    fun `toEpochMillis and toInstant are inverse operations`() {
        val original = Instant.parse("2024-06-15T10:30:45.123Z")
        val roundTripped = original.toEpochMillis().toInstant()

        roundTripped shouldBe original
    }

    @Test
    fun `toIsoString formats instant correctly`() {
        val instant = Instant.parse("2024-06-15T10:30:00Z")

        instant.toIsoString() shouldBe "2024-06-15T10:30:00Z"
    }

    @Test
    fun `parseInstantOrNull parses valid ISO string`() {
        val result = "2024-06-15T10:30:00Z".parseInstantOrNull()

        result shouldBe Instant.parse("2024-06-15T10:30:00Z")
    }

    @Test
    fun `parseInstantOrNull returns null for invalid string`() {
        val result = "not-a-date".parseInstantOrNull()

        result shouldBe null
    }

    @Test
    fun `parseInstantOrNull returns null for empty string`() {
        val result = "".parseInstantOrNull()

        result shouldBe null
    }

    @Test
    fun `formatForDisplay formats with default pattern`() {
        val instant = Instant.parse("2024-06-15T10:30:00Z")
        val formatted = instant.formatForDisplay(zoneId = ZoneOffset.UTC, locale = java.util.Locale.ENGLISH)

        formatted shouldBe "Jun 15, 2024 10:30"
    }

    @Test
    fun `formatForDisplay formats with custom pattern`() {
        val instant = Instant.parse("2024-06-15T10:30:00Z")
        val formatted = instant.formatForDisplay(
            pattern = "yyyy-MM-dd",
            zoneId = ZoneOffset.UTC,
            locale = java.util.Locale.ENGLISH
        )

        formatted shouldBe "2024-06-15"
    }

    @Test
    fun `isBefore returns true when instant is earlier`() {
        val earlier = Instant.parse("2024-01-01T00:00:00Z")
        val later = Instant.parse("2024-06-15T00:00:00Z")

        earlier.isBefore(later) shouldBe true
        later.isBefore(earlier) shouldBe false
    }

    @Test
    fun `isAfter returns true when instant is later`() {
        val earlier = Instant.parse("2024-01-01T00:00:00Z")
        val later = Instant.parse("2024-06-15T00:00:00Z")

        later.isAfter(earlier) shouldBe true
        earlier.isAfter(later) shouldBe false
    }

    @Test
    fun `millisSince calculates duration correctly`() {
        val earlier = Instant.parse("2024-01-01T00:00:00Z")
        val later = Instant.parse("2024-01-01T00:00:01Z")

        later.millisSince(earlier) shouldBe 1000L
        earlier.millisSince(later) shouldBe -1000L
    }
}
