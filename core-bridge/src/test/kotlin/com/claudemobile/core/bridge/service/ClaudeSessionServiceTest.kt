package com.claudemobile.core.bridge.service

import com.claudemobile.core.domain.model.Message
import com.claudemobile.core.domain.model.MessageId
import com.claudemobile.core.domain.model.MessageRole
import com.claudemobile.core.domain.model.MessageStatus
import com.claudemobile.core.domain.model.Session
import com.claudemobile.core.domain.model.SessionId
import com.claudemobile.core.domain.repository.ConversationRepository
import com.claudemobile.core.domain.repository.DiagnosticsEntry
import com.claudemobile.core.domain.repository.DiagnosticsRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class ClaudeSessionServiceTest {

    private lateinit var conversationRepository: FakeConversationRepository
    private lateinit var diagnosticsRepository: FakeDiagnosticsRepo

    @BeforeEach
    fun setup() {
        conversationRepository = FakeConversationRepository()
        diagnosticsRepository = FakeDiagnosticsRepo()
    }

    @Test
    fun `detectAndHandleOsKill marks streaming messages as error`() = runTest {
        val sessionId = "session-1"
        val streamingMessage = createMessage(
            id = "msg-1",
            sessionId = sessionId,
            status = MessageStatus.STREAMING,
            content = "Partial response..."
        )
        conversationRepository.messagesMap[sessionId] = mutableListOf(streamingMessage)

        val prefsStore = FakeServicePreferencesStore(
            killedSessions = setOf(sessionId)
        )

        ClaudeSessionService.detectAndHandleOsKillInternal(
            preferencesStore = prefsStore,
            conversationRepository = conversationRepository,
            diagnosticsRepository = diagnosticsRepository,
        )

        // Verify message status was updated to ERROR
        val updatedStatus = conversationRepository.statusUpdates["msg-1"]
        updatedStatus shouldBe MessageStatus.ERROR

        // Verify content was updated with kill note
        val updatedContent = conversationRepository.contentUpdates["msg-1"]
        updatedContent?.shouldContain("killed by OS")
    }

    @Test
    fun `detectAndHandleOsKill marks sending messages as error`() = runTest {
        val sessionId = "session-2"
        val sendingMessage = createMessage(
            id = "msg-2",
            sessionId = sessionId,
            status = MessageStatus.SENDING,
            content = "User message being sent"
        )
        conversationRepository.messagesMap[sessionId] = mutableListOf(sendingMessage)

        val prefsStore = FakeServicePreferencesStore(
            killedSessions = setOf(sessionId)
        )

        ClaudeSessionService.detectAndHandleOsKillInternal(
            preferencesStore = prefsStore,
            conversationRepository = conversationRepository,
            diagnosticsRepository = diagnosticsRepository,
        )

        conversationRepository.statusUpdates["msg-2"] shouldBe MessageStatus.ERROR
    }

    @Test
    fun `detectAndHandleOsKill does not modify complete messages`() = runTest {
        val sessionId = "session-3"
        val completeMessage = createMessage(
            id = "msg-3",
            sessionId = sessionId,
            status = MessageStatus.COMPLETE,
            content = "Completed response"
        )
        conversationRepository.messagesMap[sessionId] = mutableListOf(completeMessage)

        val prefsStore = FakeServicePreferencesStore(
            killedSessions = setOf(sessionId)
        )

        ClaudeSessionService.detectAndHandleOsKillInternal(
            preferencesStore = prefsStore,
            conversationRepository = conversationRepository,
            diagnosticsRepository = diagnosticsRepository,
        )

        // Complete messages should not be modified
        conversationRepository.statusUpdates.containsKey("msg-3") shouldBe false
        conversationRepository.contentUpdates.containsKey("msg-3") shouldBe false
    }

    @Test
    fun `detectAndHandleOsKill handles multiple sessions`() = runTest {
        val session1 = "session-a"
        val session2 = "session-b"

        conversationRepository.messagesMap[session1] = mutableListOf(
            createMessage("msg-a1", session1, MessageStatus.STREAMING, "Response A")
        )
        conversationRepository.messagesMap[session2] = mutableListOf(
            createMessage("msg-b1", session2, MessageStatus.STREAMING, "Response B")
        )

        val prefsStore = FakeServicePreferencesStore(
            killedSessions = setOf(session1, session2)
        )

        ClaudeSessionService.detectAndHandleOsKillInternal(
            preferencesStore = prefsStore,
            conversationRepository = conversationRepository,
            diagnosticsRepository = diagnosticsRepository,
        )

        conversationRepository.statusUpdates["msg-a1"] shouldBe MessageStatus.ERROR
        conversationRepository.statusUpdates["msg-b1"] shouldBe MessageStatus.ERROR
    }

    @Test
    fun `detectAndHandleOsKill logs diagnostics event`() = runTest {
        val sessionId = "session-diag"
        conversationRepository.messagesMap[sessionId] = mutableListOf(
            createMessage("msg-d1", sessionId, MessageStatus.STREAMING, "Partial")
        )

        val prefsStore = FakeServicePreferencesStore(
            killedSessions = setOf(sessionId)
        )

        ClaudeSessionService.detectAndHandleOsKillInternal(
            preferencesStore = prefsStore,
            conversationRepository = conversationRepository,
            diagnosticsRepository = diagnosticsRepository,
        )

        diagnosticsRepository.loggedEvents.any { event ->
            event.sessionId == sessionId &&
                event.eventType == "bridge_lifecycle" &&
                event.message.contains("killed by OS")
        } shouldBe true
    }

    @Test
    fun `detectAndHandleOsKill does nothing when no killed sessions`() = runTest {
        val prefsStore = FakeServicePreferencesStore(killedSessions = emptySet())

        ClaudeSessionService.detectAndHandleOsKillInternal(
            preferencesStore = prefsStore,
            conversationRepository = conversationRepository,
            diagnosticsRepository = diagnosticsRepository,
        )

        conversationRepository.statusUpdates shouldBe emptyMap()
        conversationRepository.contentUpdates shouldBe emptyMap()
        diagnosticsRepository.loggedEvents shouldBe emptyList()
    }

    @Test
    fun `detectAndHandleOsKill clears killed sessions preference`() = runTest {
        val sessionId = "session-clear"
        conversationRepository.messagesMap[sessionId] = mutableListOf(
            createMessage("msg-c1", sessionId, MessageStatus.STREAMING, "Partial")
        )

        val prefsStore = FakeServicePreferencesStore(
            killedSessions = setOf(sessionId)
        )

        ClaudeSessionService.detectAndHandleOsKillInternal(
            preferencesStore = prefsStore,
            conversationRepository = conversationRepository,
            diagnosticsRepository = diagnosticsRepository,
        )

        // After handling, the killed sessions should be cleared
        prefsStore.wasCleared shouldBe true
    }

    @Test
    fun `companion object constants are correctly defined`() {
        ClaudeSessionService.ACTION_START_SESSION shouldBe "com.claudemobile.action.START_SESSION"
        ClaudeSessionService.ACTION_STOP_SESSION shouldBe "com.claudemobile.action.STOP_SESSION"
        ClaudeSessionService.ACTION_UPDATE_STATUS shouldBe "com.claudemobile.action.UPDATE_STATUS"
        ClaudeSessionService.EXTRA_SESSION_ID shouldBe "extra_session_id"
        ClaudeSessionService.EXTRA_SESSION_TITLE shouldBe "extra_session_title"
        ClaudeSessionService.EXTRA_TURN_STATUS shouldBe "extra_turn_status"
        ClaudeSessionService.EXTRA_OPEN_SESSION_ID shouldBe "extra_open_session_id"
    }

    // --- Helper functions ---

    private fun createMessage(
        id: String,
        sessionId: String,
        status: MessageStatus,
        content: String
    ): Message = Message(
        id = MessageId(id),
        sessionId = SessionId(sessionId),
        role = MessageRole.ASSISTANT,
        createdAt = Instant.now(),
        position = 0,
        content = content,
        status = status,
        toolCallMetadata = null
    )
}

/**
 * Fake implementation of ServicePreferencesStore for testing.
 */
class FakeServicePreferencesStore(
    private val killedSessions: Set<String>
) : ServicePreferencesStore {
    var wasCleared = false

    override fun getKilledSessions(): Set<String>? {
        return if (killedSessions.isEmpty()) null else killedSessions
    }

    override fun clearKilledSessions() {
        wasCleared = true
    }

    override fun setActiveSessions(sessionIds: Set<String>) {}
    override fun getActiveSessions(): Set<String>? = null
    override fun clearActiveSessions() {}
    override fun setKilledSessions(sessionIds: Set<String>) {}
}

/**
 * Fake ConversationRepository for testing OS kill detection.
 */
class FakeConversationRepository : ConversationRepository {
    val messagesMap = mutableMapOf<String, MutableList<Message>>()
    val statusUpdates = mutableMapOf<String, MessageStatus>()
    val contentUpdates = mutableMapOf<String, String>()

    override fun getSessions(): Flow<List<Session>> = flowOf(emptyList())
    override suspend fun getSession(id: SessionId): Session? = null
    override suspend fun getMessages(sessionId: SessionId): List<Message> {
        return messagesMap[sessionId.value] ?: emptyList()
    }
    override fun getMessagesFlow(sessionId: SessionId): Flow<List<Message>> = flowOf(emptyList())
    override suspend fun createSession(title: String, workspacePath: String): Session {
        throw UnsupportedOperationException()
    }
    override suspend fun updateSessionTitle(id: SessionId, title: String) {}
    override suspend fun deleteSession(id: SessionId) {}
    override suspend fun insertMessage(message: Message): Message = message
    override suspend fun updateMessageContent(id: MessageId, content: String) {
        contentUpdates[id.value] = content
    }
    override suspend fun updateMessageStatus(id: MessageId, status: MessageStatus) {
        statusUpdates[id.value] = status
    }
}

/**
 * Fake DiagnosticsRepository for testing.
 */
class FakeDiagnosticsRepo : DiagnosticsRepository {
    val loggedEvents = mutableListOf<LoggedEvent>()

    data class LoggedEvent(
        val sessionId: String?,
        val eventType: String,
        val message: String,
        val details: String?,
    )

    override suspend fun logEvent(
        sessionId: String?,
        eventType: String,
        message: String,
        details: String?,
    ) {
        loggedEvents.add(LoggedEvent(sessionId, eventType, message, details))
    }

    override fun getSessionLogs(sessionId: String): Flow<List<DiagnosticsEntry>> =
        flowOf(emptyList())

    override suspend fun getRecentLogs(limit: Int): List<DiagnosticsEntry> = emptyList()

    override suspend fun exportRedacted(sessionId: String): String = ""
    override suspend fun clearOldEntries() {}
}
