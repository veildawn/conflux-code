package com.claudemobile.core.data.diagnostics

import android.content.Context
import com.claudemobile.core.common.TimeProvider
import com.claudemobile.core.common.UuidGenerator
import com.claudemobile.core.common.toEpochMillis
import com.claudemobile.core.domain.repository.CredentialStore
import com.claudemobile.core.domain.repository.DiagnosticsEntry
import com.claudemobile.core.domain.repository.DiagnosticsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.LinkedList
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Maximum number of stderr lines kept per session in the in-memory ring buffer.
 */
private const val STDERR_RING_BUFFER_SIZE: Int = 256

private const val MAX_PERSISTED_ENTRIES: Int = 10_000

/**
 * File-backed diagnostics repository.
 *
 * Diagnostics are intentionally separate from Claude transcripts, but no longer
 * require Room. They live in one compact NDJSON file under app-private storage.
 */
@Singleton
public class DiagnosticsRepositoryImpl internal constructor(
    private val logFile: File,
    private val credentialStore: CredentialStore,
    private val uuidGenerator: UuidGenerator,
    private val timeProvider: TimeProvider,
    private val providerProfileSnapshot: ProviderProfileSnapshot,
) : DiagnosticsRepository {

    @Inject
    public constructor(
        @ApplicationContext context: Context,
        credentialStore: CredentialStore,
        uuidGenerator: UuidGenerator,
        timeProvider: TimeProvider,
        providerProfileSnapshot: ProviderProfileSnapshot,
    ) : this(
        logFile = File(context.filesDir, "diagnostics/logs.jsonl"),
        credentialStore = credentialStore,
        uuidGenerator = uuidGenerator,
        timeProvider = timeProvider,
        providerProfileSnapshot = providerProfileSnapshot,
    )

    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val mutex: Mutex = Mutex()
    private val entries: MutableStateFlow<List<DiagnosticsEntry>> =
        MutableStateFlow(readEntriesFromDisk())
    private val stderrBuffers: MutableMap<String, LinkedList<String>> = mutableMapOf()

    override suspend fun logEvent(
        sessionId: String?,
        eventType: String,
        message: String,
        details: String?,
    ) {
        mutex.withLock {
            if (eventType == "stderr" && sessionId != null) {
                addToStderrBuffer(sessionId, message)
            }

            val entry = DiagnosticsEntry(
                id = uuidGenerator.generate(),
                sessionId = sessionId,
                eventType = eventType,
                timestamp = timeProvider.now().toEpochMillis(),
                message = message,
                details = details,
            )

            val updated = (entries.value + entry)
                .takeLast(MAX_PERSISTED_ENTRIES)
            entries.value = updated
            rewriteFile(updated)
        }
    }

    override fun getSessionLogs(sessionId: String): Flow<List<DiagnosticsEntry>> =
        entries.map { current ->
            current
                .filter { entry -> entry.sessionId == sessionId }
                .sortedByDescending(DiagnosticsEntry::timestamp)
        }

    override suspend fun getRecentLogs(limit: Int): List<DiagnosticsEntry> =
        entries.value
            .sortedByDescending(DiagnosticsEntry::timestamp)
            .take(limit)

    override suspend fun exportRedacted(sessionId: String): String {
        val apiKey = credentialStore.getApiKey()
        val sessionEntries = entries.value
            .filter { entry -> entry.sessionId == sessionId }
            .sortedBy(DiagnosticsEntry::timestamp)

        val builder = StringBuilder()
        builder.appendLine("=== Diagnostics Log ===")
        builder.appendLine("Session: $sessionId")
        builder.appendLine("Exported: ${timeProvider.now()}")
        builder.appendLine()

        for (entry in sessionEntries) {
            builder.appendLine("[${entry.timestamp}] [${entry.eventType}] ${entry.message}")
            if (entry.details != null) {
                builder.appendLine("  Details: ${entry.details}")
            }
        }

        val stderrLines = getStderrBuffer(sessionId)
        if (stderrLines.isNotEmpty()) {
            builder.appendLine()
            builder.appendLine("=== Stderr (last ${stderrLines.size} lines) ===")
            stderrLines.forEach(builder::appendLine)
        }

        var result = builder.toString()
        if (!apiKey.isNullOrEmpty()) {
            result = result.replace(apiKey, "[REDACTED]")
        }

        val profiles = providerProfileSnapshot.list()
        if (profiles.isNotEmpty()) {
            result = redactProviderSecrets(result, profiles)
        }

        return result
    }

    override suspend fun clearOldEntries() {
        mutex.withLock {
            val trimmed = entries.value.takeLast(MAX_PERSISTED_ENTRIES)
            entries.value = trimmed
            rewriteFile(trimmed)
        }
    }

    private fun readEntriesFromDisk(): List<DiagnosticsEntry> {
        if (!logFile.exists()) return emptyList()
        return logFile.readLines()
            .mapNotNull(::parseEntry)
            .takeLast(MAX_PERSISTED_ENTRIES)
    }

    private fun parseEntry(line: String): DiagnosticsEntry? {
        val objectValue = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
            ?: return null
        return DiagnosticsEntry(
            id = objectValue.string("id") ?: return null,
            sessionId = objectValue.string("sessionId"),
            eventType = objectValue.string("eventType") ?: return null,
            timestamp = objectValue.string("timestamp")?.toLongOrNull() ?: return null,
            message = objectValue.string("message") ?: return null,
            details = objectValue.string("details"),
        )
    }

    private fun rewriteFile(entriesToPersist: List<DiagnosticsEntry>) {
        logFile.parentFile?.mkdirs()
        logFile.writeText(
            entriesToPersist.joinToString(
                separator = "\n",
                postfix = if (entriesToPersist.isEmpty()) "" else "\n",
            ) { entry -> entry.toJsonLine() },
        )
    }

    private fun DiagnosticsEntry.toJsonLine(): String =
        buildJsonObject {
            put("id", id)
            if (sessionId != null) put("sessionId", sessionId)
            put("eventType", eventType)
            put("timestamp", timestamp.toString())
            put("message", message)
            if (details != null) put("details", details)
        }.toString()

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun addToStderrBuffer(sessionId: String, line: String) {
        val buffer = stderrBuffers.getOrPut(sessionId) { LinkedList() }
        buffer.addLast(line)
        while (buffer.size > STDERR_RING_BUFFER_SIZE) {
            buffer.removeFirst()
        }
    }

    private fun getStderrBuffer(sessionId: String): List<String> =
        stderrBuffers[sessionId]?.toList().orEmpty()
}
