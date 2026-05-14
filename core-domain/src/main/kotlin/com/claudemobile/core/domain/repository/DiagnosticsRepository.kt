package com.claudemobile.core.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Domain model representing a single diagnostics log entry.
 */
public data class DiagnosticsEntry(
    val id: String,
    val sessionId: String?,
    val eventType: String,
    val timestamp: Long,
    val message: String,
    val details: String?
)

/**
 * Repository for managing diagnostics logs including bootstrap events,
 * bridge lifecycle events, and per-session stderr output.
 *
 * Provides export functionality with API key redaction to ensure
 * credentials are never leaked through shared diagnostics.
 */
public interface DiagnosticsRepository {

    /**
     * Logs a diagnostics event.
     *
     * @param sessionId Optional session this event is associated with
     * @param eventType Type of event: "bootstrap", "bridge_lifecycle", "stderr", "crash"
     * @param message Human-readable event description
     * @param details Optional additional details (e.g., stack trace, stderr output)
     */
    public suspend fun logEvent(
        sessionId: String?,
        eventType: String,
        message: String,
        details: String? = null
    )

    /**
     * Returns a reactive Flow of diagnostics entries for a given session,
     * ordered by timestamp descending.
     */
    public fun getSessionLogs(sessionId: String): Flow<List<DiagnosticsEntry>>

    /**
     * Returns the most recent diagnostics entries across all sessions,
     * limited to [limit] entries, ordered by timestamp descending.
     *
     * @param limit Maximum number of entries to return (default 256)
     */
    public suspend fun getRecentLogs(limit: Int = 256): List<DiagnosticsEntry>

    /**
     * Exports all diagnostics entries for a session as a redacted text string.
     * Any occurrence of the stored API key is replaced with "[REDACTED]".
     *
     * @param sessionId The session to export logs for
     * @return Redacted text content suitable for sharing
     */
    public suspend fun exportRedacted(sessionId: String): String

    /**
     * Removes old entries to keep the diagnostics log within the 10,000 entry cap.
     * Uses LRU eviction (oldest entries removed first).
     */
    public suspend fun clearOldEntries()
}
