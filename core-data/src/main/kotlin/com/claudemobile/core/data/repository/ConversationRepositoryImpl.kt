package com.claudemobile.core.data.repository

import com.claudemobile.core.common.TimeProvider
import com.claudemobile.core.common.UuidGenerator
import com.claudemobile.core.common.parseInstantOrNull
import com.claudemobile.core.data.transcript.ClaudeTranscriptStore
import com.claudemobile.core.domain.model.Message
import com.claudemobile.core.domain.model.MessageId
import com.claudemobile.core.domain.model.MessageRole
import com.claudemobile.core.domain.model.MessageStatus
import com.claudemobile.core.domain.model.Session
import com.claudemobile.core.domain.model.SessionId
import com.claudemobile.core.domain.repository.ConversationRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Conversation repository backed by Claude Code transcript files.
 *
 * Claude's own JSONL transcripts are the durable source of truth. The small
 * in-memory overlay below only holds live UI state (pending user messages and
 * streaming assistant text) until Claude flushes the same turn into its
 * transcript.
 */
@Singleton
public class ConversationRepositoryImpl @Inject constructor(
    private val transcriptStore: ClaudeTranscriptStore,
    private val uuidGenerator: UuidGenerator,
    private val timeProvider: TimeProvider,
) : ConversationRepository {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val overlayMutex: Mutex = Mutex()
    private val overlayMessages: MutableMap<String, LinkedHashMap<String, Message>> = mutableMapOf()
    private val refreshSignal: MutableStateFlow<Long> = MutableStateFlow(0L)

    override fun getSessions(): Flow<List<Session>> =
        combine(pollTicker(), refreshSignal) { _, _ ->
            loadSessions()
        }.distinctUntilChanged()

    override suspend fun getSession(id: SessionId): Session? =
        loadSessions().firstOrNull { session -> session.id == id }

    override suspend fun getMessages(sessionId: SessionId): List<Message> =
        mergeWithOverlay(sessionId, loadPersistedMessages(sessionId))

    override fun getMessagesFlow(sessionId: SessionId): Flow<List<Message>> =
        combine(pollTicker(), refreshSignal) { _, _ ->
            mergeWithOverlay(sessionId, loadPersistedMessages(sessionId))
        }.distinctUntilChanged()

    override suspend fun createSession(title: String, workspacePath: String): Session {
        val now = timeProvider.now()
        val sessionId = SessionId(uuidGenerator.generate())
        transcriptStore.appendLine(
            sessionId = sessionId.value,
            jsonLine = buildJsonObject {
                put("type", "custom-title")
                put("customTitle", title)
                put("workspacePath", workspacePath)
                put("sessionId", sessionId.value)
                put("timestamp", now.toString())
            }.toString(),
        )
        bumpRefresh()

        return Session(
            id = sessionId,
            title = title,
            workspacePath = workspacePath,
            createdAt = now,
            lastActivityAt = now,
            messageCount = 0,
        )
    }

    override suspend fun updateSessionTitle(id: SessionId, title: String) {
        transcriptStore.appendLine(
            sessionId = id.value,
            jsonLine = buildJsonObject {
                put("type", "custom-title")
                put("customTitle", title)
                put("sessionId", id.value)
                put("timestamp", timeProvider.now().toString())
            }.toString(),
        )
        bumpRefresh()
    }

    override suspend fun deleteSession(id: SessionId) {
        overlayMutex.withLock {
            overlayMessages.remove(id.value)
        }
        transcriptStore.deleteSession(id.value)
        bumpRefresh()
    }

    override suspend fun insertMessage(message: Message): Message {
        overlayMutex.withLock {
            overlayMessages
                .getOrPut(message.sessionId.value) { linkedMapOf() }[message.id.value] = message
        }
        bumpRefresh()
        return message
    }

    override suspend fun updateMessageContent(id: MessageId, content: String) {
        overlayMutex.withLock {
            updateOverlayMessage(id) { message -> message.copy(content = content) }
        }
        bumpRefresh()
    }

    override suspend fun updateMessageStatus(id: MessageId, status: MessageStatus) {
        overlayMutex.withLock {
            updateOverlayMessage(id) { message -> message.copy(status = status) }
        }
        bumpRefresh()
    }

    private fun pollTicker(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun loadSessions(): List<Session> =
        transcriptStore.listSessionFiles()
            .mapNotNull { file -> parseSnapshot(file.readLines().asSequence(), file.lastModified()) }
            .sortedByDescending(SessionSnapshot::lastActivityAt)
            .map(SessionSnapshot::toDomain)

    private fun loadPersistedMessages(sessionId: SessionId): List<Message> {
        val file = transcriptStore.transcriptFile(sessionId.value)
        if (!file.exists()) return emptyList()
        return parseSnapshot(file.readLines().asSequence(), file.lastModified())
            ?.messages
            .orEmpty()
    }

    private suspend fun mergeWithOverlay(
        sessionId: SessionId,
        persistedMessages: List<Message>,
    ): List<Message> {
        val persistedPositions = persistedMessages
            .map { message -> message.position to message.role }
            .toSet()

        val overlays = overlayMutex.withLock {
            val sessionOverlay = overlayMessages[sessionId.value] ?: return@withLock emptyList()
            val suppressedIds = sessionOverlay.values
                .filter { message -> (message.position to message.role) in persistedPositions }
                .map { message -> message.id.value }
            suppressedIds.forEach(sessionOverlay::remove)
            sessionOverlay.values.toList()
        }

        return (persistedMessages + overlays)
            .sortedBy(Message::position)
    }

    private fun updateOverlayMessage(
        id: MessageId,
        transform: (Message) -> Message,
    ) {
        overlayMessages.values.forEach { sessionMessages ->
            val existing = sessionMessages[id.value]
            if (existing != null) {
                sessionMessages[id.value] = transform(existing)
                return
            }
        }
    }

    private fun parseSnapshot(
        lines: Sequence<String>,
        fileLastModified: Long,
    ): SessionSnapshot? {
        var sessionId: String? = null
        var title: String? = null
        var aiTitle: String? = null
        var workspacePath: String? = null
        var firstUserPrompt: String? = null
        var createdAt: Instant? = null
        var lastActivityAt: Instant? = null
        val messages = mutableListOf<Message>()
        val assistantIndexesByClaudeMessageId = mutableMapOf<String, Int>()

        lines.forEach { line ->
            val event = parseObject(line) ?: return@forEach
            val eventSessionId = event.string("sessionId") ?: event.string("session_id")
            if (sessionId == null && eventSessionId != null) {
                sessionId = eventSessionId
            }

            when (event.string("type")) {
                "custom-title" -> {
                    title = event.string("customTitle") ?: title
                    workspacePath = event.string("workspacePath") ?: workspacePath
                }
                "ai-title" -> aiTitle = event.string("aiTitle") ?: aiTitle
                "user" -> {
                    val messageObject = event.objectOrNull("message") ?: return@forEach
                    val content = messageObject.string("content") ?: return@forEach
                    val timestamp = event.timestampOrFallback(fileLastModified)
                    val id = event.string("uuid") ?: uuidGenerator.generate()

                    if (firstUserPrompt == null) {
                        firstUserPrompt = content
                    }
                    workspacePath = event.string("cwd") ?: workspacePath
                    createdAt = minOfNullable(createdAt, timestamp)
                    lastActivityAt = maxOfNullable(lastActivityAt, timestamp)
                    messages += Message(
                        id = MessageId(id),
                        sessionId = SessionId(eventSessionId ?: sessionId ?: return@forEach),
                        role = MessageRole.USER,
                        createdAt = timestamp,
                        position = messages.size,
                        content = content,
                        status = MessageStatus.COMPLETE,
                        toolCallMetadata = null,
                    )
                }
                "assistant" -> {
                    val messageObject = event.objectOrNull("message") ?: return@forEach
                    val contentBlocks = messageObject["content"] as? JsonArray ?: return@forEach
                    val text = extractAssistantText(contentBlocks)
                    if (text.isBlank()) return@forEach

                    val timestamp = event.timestampOrFallback(fileLastModified)
                    val id = event.string("uuid") ?: uuidGenerator.generate()
                    val claudeMessageId = messageObject.string("id")
                    workspacePath = event.string("cwd") ?: workspacePath
                    createdAt = minOfNullable(createdAt, timestamp)
                    lastActivityAt = maxOfNullable(lastActivityAt, timestamp)

                    val existingIndex = claudeMessageId?.let(assistantIndexesByClaudeMessageId::get)
                    if (existingIndex != null) {
                        val existing = messages[existingIndex]
                        val mergedContent = mergeAssistantContent(existing.content, text)
                        messages[existingIndex] = existing.copy(content = mergedContent)
                    } else {
                        val message = Message(
                            id = MessageId(id),
                            sessionId = SessionId(eventSessionId ?: sessionId ?: return@forEach),
                            role = MessageRole.ASSISTANT,
                            createdAt = timestamp,
                            position = messages.size,
                            content = text,
                            status = MessageStatus.COMPLETE,
                            toolCallMetadata = null,
                        )
                        messages += message
                        if (claudeMessageId != null) {
                            assistantIndexesByClaudeMessageId[claudeMessageId] = messages.lastIndex
                        }
                    }
                }
            }
        }

        val id = sessionId ?: return null
        val fallbackInstant = Instant.ofEpochMilli(fileLastModified)
        return SessionSnapshot(
            id = SessionId(id),
            title = title
                ?: aiTitle
                ?: firstUserPrompt?.lineSequence()?.firstOrNull()?.take(TITLE_FALLBACK_LIMIT)
                ?: DEFAULT_TITLE,
            workspacePath = workspacePath ?: DEFAULT_WORKSPACE,
            createdAt = createdAt ?: fallbackInstant,
            lastActivityAt = lastActivityAt ?: fallbackInstant,
            messages = messages.mapIndexed { index, message -> message.copy(position = index) },
        )
    }

    private fun parseObject(line: String): JsonObject? =
        runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.objectOrNull(key: String): JsonObject? =
        this[key]?.takeIf { element -> element is JsonObject }?.jsonObject

    private fun JsonObject.timestampOrFallback(fileLastModified: Long): Instant =
        string("timestamp")?.parseInstantOrNull() ?: Instant.ofEpochMilli(fileLastModified)

    private fun extractAssistantText(contentBlocks: JsonArray): String =
        contentBlocks
            .mapNotNull { block ->
                val obj = block as? JsonObject ?: return@mapNotNull null
                if (obj.string("type") != "text") return@mapNotNull null
                obj.string("text")
            }
            .joinToString(separator = "")

    private fun mergeAssistantContent(existing: String, next: String): String =
        when {
            next.isBlank() -> existing
            existing.isBlank() -> next
            existing.contains(next) -> existing
            next.contains(existing) -> next
            else -> existing + next
        }

    private fun minOfNullable(current: Instant?, next: Instant): Instant =
        current?.takeIf { it <= next } ?: next

    private fun maxOfNullable(current: Instant?, next: Instant): Instant =
        current?.takeIf { it >= next } ?: next

    private fun bumpRefresh() {
        refreshSignal.value = refreshSignal.value + 1
    }

    private data class SessionSnapshot(
        val id: SessionId,
        val title: String,
        val workspacePath: String,
        val createdAt: Instant,
        val lastActivityAt: Instant,
        val messages: List<Message>,
    ) {
        fun toDomain(): Session = Session(
            id = id,
            title = title,
            workspacePath = workspacePath,
            createdAt = createdAt,
            lastActivityAt = lastActivityAt,
            messageCount = messages.size,
        )
    }

    private companion object {
        const val POLL_INTERVAL_MS: Long = 750L
        const val TITLE_FALLBACK_LIMIT: Int = 80
        const val DEFAULT_TITLE: String = "New Session"
        const val DEFAULT_WORKSPACE: String = "/root"
    }
}
