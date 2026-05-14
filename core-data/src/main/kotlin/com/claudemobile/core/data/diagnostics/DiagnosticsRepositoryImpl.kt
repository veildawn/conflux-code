package com.claudemobile.core.data.diagnostics

import com.claudemobile.core.common.TimeProvider
import com.claudemobile.core.common.UuidGenerator
import com.claudemobile.core.common.toEpochMillis
import com.claudemobile.core.data.database.dao.DiagnosticsLogDao
import com.claudemobile.core.data.database.entity.DiagnosticsLogEntity
import com.claudemobile.core.domain.repository.CredentialStore
import com.claudemobile.core.domain.repository.DiagnosticsEntry
import com.claudemobile.core.domain.repository.DiagnosticsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.LinkedList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maximum number of stderr lines kept per session in the in-memory ring buffer.
 */
private const val STDERR_RING_BUFFER_SIZE: Int = 256

/**
 * Implementation of [DiagnosticsRepository] backed by Room database with:
 * - Per-session stderr ring buffer (256 lines in-memory)
 * - LRU eviction at 10,000 entries via DAO
 * - API key redaction on export (base-spec single key + ai-provider-presets
 *   multi-profile defence-in-depth, see [redactProviderSecrets])
 */
@Singleton
public class DiagnosticsRepositoryImpl @Inject constructor(
    private val diagnosticsLogDao: DiagnosticsLogDao,
    private val credentialStore: CredentialStore,
    private val uuidGenerator: UuidGenerator,
    private val timeProvider: TimeProvider,
    private val providerProfileSnapshot: ProviderProfileSnapshot,
) : DiagnosticsRepository {

    /**
     * Legacy four-arg constructor retained so that base-spec tests written
     * before the `ai-provider-presets` delta (notably
     * `DiagnosticsRedactionPropertyTest`) continue to compile unchanged.
     * The defaulted [ProviderProfileSnapshot] is a no-op that returns an
     * empty list, so behaviour for callers that do not wire the snapshot
     * is identical to the pre-delta implementation.
     */
    public constructor(
        diagnosticsLogDao: DiagnosticsLogDao,
        credentialStore: CredentialStore,
        uuidGenerator: UuidGenerator,
        timeProvider: TimeProvider,
    ) : this(
        diagnosticsLogDao = diagnosticsLogDao,
        credentialStore = credentialStore,
        uuidGenerator = uuidGenerator,
        timeProvider = timeProvider,
        providerProfileSnapshot = ProviderProfileSnapshot { emptyList() },
    )

    /**
     * In-memory ring buffer for stderr lines per session.
     * Key: sessionId, Value: bounded LinkedList of stderr lines (max 256).
     */
    private val stderrBuffers: MutableMap<String, LinkedList<String>> = mutableMapOf()
    private val bufferMutex: Mutex = Mutex()

    override suspend fun logEvent(
        sessionId: String?,
        eventType: String,
        message: String,
        details: String?
    ) {
        // If this is a stderr event, also track in the ring buffer
        if (eventType == "stderr" && sessionId != null) {
            addToStderrBuffer(sessionId, message)
        }

        val entity = DiagnosticsLogEntity(
            id = 0,
            sessionId = sessionId,
            eventType = eventType,
            timestamp = timeProvider.now().toEpochMillis(),
            message = message,
            details = details
        )

        diagnosticsLogDao.insert(entity)

        // Evict old entries to maintain the 10,000 cap
        diagnosticsLogDao.deleteOldest()
    }

    override fun getSessionLogs(sessionId: String): Flow<List<DiagnosticsEntry>> {
        return diagnosticsLogDao.getBySessionId(sessionId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getRecentLogs(limit: Int): List<DiagnosticsEntry> {
        return diagnosticsLogDao.getRecentLogs(limit).map { it.toDomain() }
    }

    override suspend fun exportRedacted(sessionId: String): String {
        val apiKey = credentialStore.getApiKey()
        val entries = getSessionEntriesSnapshot(sessionId)

        val builder = StringBuilder()
        builder.appendLine("=== Diagnostics Log ===")
        builder.appendLine("Session: $sessionId")
        builder.appendLine("Exported: ${timeProvider.now()}")
        builder.appendLine()

        for (entry in entries) {
            builder.appendLine("[${entry.timestamp}] [${entry.eventType}] ${entry.message}")
            if (entry.details != null) {
                builder.appendLine("  Details: ${entry.details}")
            }
        }

        // Append stderr ring buffer content if available
        val stderrLines = getStderrBuffer(sessionId)
        if (stderrLines.isNotEmpty()) {
            builder.appendLine()
            builder.appendLine("=== Stderr (last ${stderrLines.size} lines) ===")
            for (line in stderrLines) {
                builder.appendLine(line)
            }
        }

        var result = builder.toString()

        // Redact API key if one is stored
        if (apiKey != null && apiKey.isNotEmpty()) {
            result = result.replace(apiKey, "[REDACTED]")
        }

        // Defence-in-depth: redact every ai-provider-presets ProviderProfile
        // apiKey / baseUrl userinfo currently tracked by the store. In a
        // well-behaved system this never rewrites anything because the
        // source-site logging rules (R10 AC4) already prevent writing the
        // raw values; this step is the second line of defence (design §9.3).
        val profiles = providerProfileSnapshot.list()
        if (profiles.isNotEmpty()) {
            result = redactProviderSecrets(result, profiles)
        }

        return result
    }

    override suspend fun clearOldEntries() {
        diagnosticsLogDao.deleteOldest()
    }

    // --- Stderr Ring Buffer ---

    private suspend fun addToStderrBuffer(sessionId: String, line: String) {
        bufferMutex.withLock {
            val buffer = stderrBuffers.getOrPut(sessionId) { LinkedList() }
            buffer.addLast(line)
            while (buffer.size > STDERR_RING_BUFFER_SIZE) {
                buffer.removeFirst()
            }
        }
    }

    private suspend fun getStderrBuffer(sessionId: String): List<String> {
        bufferMutex.withLock {
            return stderrBuffers[sessionId]?.toList() ?: emptyList()
        }
    }

    // --- Internal Helpers ---

    /**
     * Gets a snapshot of session entries from the database (non-reactive, for export).
     */
    private suspend fun getSessionEntriesSnapshot(sessionId: String): List<DiagnosticsEntry> {
        return diagnosticsLogDao.getBySessionId(sessionId)
            .first()
            .map { it.toDomain() }
    }

    private fun DiagnosticsLogEntity.toDomain(): DiagnosticsEntry = DiagnosticsEntry(
        id = id.toString(),
        sessionId = sessionId,
        eventType = eventType,
        timestamp = timestamp,
        message = message,
        details = details
    )
}
