package com.claudemobile.core.common

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Abstraction for time/instant access to enable deterministic testing.
 */
public interface TimeProvider {
    /** Returns the current instant (UTC). */
    public fun now(): Instant
}

/**
 * Default implementation using [Instant.now].
 */
public class DefaultTimeProvider : TimeProvider {
    override fun now(): Instant = Instant.now()
}

// --- Instant extension helpers ---

/** Converts an [Instant] to epoch milliseconds. */
public fun Instant.toEpochMillis(): Long = toEpochMilli()

/** Creates an [Instant] from epoch milliseconds. */
public fun Long.toInstant(): Instant = Instant.ofEpochMilli(this)

/** Formats an [Instant] as an ISO-8601 UTC string (e.g., "2024-01-15T10:30:00Z"). */
public fun Instant.toIsoString(): String =
    DateTimeFormatter.ISO_INSTANT.format(this)

/** Parses an ISO-8601 string into an [Instant]. Returns `null` if parsing fails. */
public fun String.parseInstantOrNull(): Instant? =
    try {
        Instant.parse(this)
    } catch (_: Exception) {
        null
    }

/** Formats an [Instant] for display using the given [pattern] and [zoneId]. */
public fun Instant.formatForDisplay(
    pattern: String = "MMM dd, yyyy HH:mm",
    zoneId: ZoneId = ZoneOffset.UTC,
    locale: java.util.Locale = java.util.Locale.getDefault(),
): String = DateTimeFormatter.ofPattern(pattern, locale)
    .withZone(zoneId)
    .format(this)

/** Returns `true` if this instant is before [other]. */
public fun Instant.isBefore(other: Instant): Boolean = this < other

/** Returns `true` if this instant is after [other]. */
public fun Instant.isAfter(other: Instant): Boolean = this > other

/** Returns the duration in milliseconds between this instant and [other]. */
public fun Instant.millisSince(other: Instant): Long =
    this.toEpochMilli() - other.toEpochMilli()
