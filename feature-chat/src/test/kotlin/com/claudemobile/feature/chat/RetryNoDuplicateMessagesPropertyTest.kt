package com.claudemobile.feature.chat

import com.claudemobile.core.common.AppResult
import com.claudemobile.core.domain.bridge.BridgeEvent
import com.claudemobile.core.domain.bridge.CliBridge
import com.claudemobile.core.domain.bridge.PosixSignal
import com.claudemobile.core.domain.bridge.ProcessHandle
import com.claudemobile.core.domain.bridge.ProcessState
import com.claudemobile.core.domain.bridge.SpawnConfig
import com.claudemobile.core.domain.model.Message
import com.claudemobile.core.domain.model.MessageId
import com.claudemobile.core.domain.model.MessageRole
import com.claudemobile.core.domain.model.MessageStatus
import com.claudemobile.core.domain.model.Session
import com.claudemobile.core.domain.model.SessionId
import com.claudemobile.core.domain.repository.ConversationRepository
import com.claudemobile.core.domain.usecase.RetryFailedTurnUseCase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.kotest.common.ExperimentalKotest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import java.time.Instant
import java.util.UUID

/**
 * Property-based test for Retry Does Not Duplicate Messages.
 *
 * **Validates: Requirements 11.3**
 *
 * Property 17: For any failed message and retry operation, the total number of user messages
 * in the session remains unchanged after retry (no duplication).
 *
 * This test uses a fake ConversationRepository to track actual message insertions,
 * verifying that RetryFailedTurnUseCase never creates duplicate user messages.
 */
@OptIn(ExperimentalKotest::class)
class RetryNoDuplicateMessagesPropertyTest : FunSpec({

    tags(
        io.kotest.core.Tag("Feature: android-claude-termux-client"),
        io.kotest.core.Tag("Property 17: Retry does not duplicate messages")
    )

    test("Feature: android-claude-termux-client, Property 17: Retry does not duplicate messages") {
        checkAll(
            PropTestConfig(iterations = 100),
            arbSessionWithFailedTurn()
        ) { scenario ->
            // Create a fake repository pre-populated with the scenario's messages
            val fakeRepository = FakeConversationRepository(scenario.messages.toMutableList())
            val fakeCliBridge = FakeCliBridge()

            val useCase = RetryFailedTurnUseCase(
                conversationRepository = fakeRepository,
                cliBridge = fakeCliBridge,
            )

            // Count user messages before retry
            val userMessageCountBefore = scenario.messages.count { it.role == MessageRole.USER }

            // Execute retry
            val result = useCase(scenario.sessionId)

            // The retry should succeed (we have a valid failed turn)
            (result is AppResult.Success) shouldBe true

            // PROPERTY: After retry, the total number of user messages in the session
            // remains unchanged — no duplication occurred
            val messagesAfterRetry = fakeRepository.getMessages(scenario.sessionId)
            val userMessageCountAfter = messagesAfterRetry.count { it.role == MessageRole.USER }

            userMessageCountAfter shouldBe userMessageCountBefore

            // Additionally verify no user message was inserted
            fakeRepository.insertedMessages.none { it.role == MessageRole.USER } shouldBe true
        }
    }
})

// ===== Test Data Generators =====

/**
 * Scenario representing a session with messages ending in a failed assistant turn.
 */
private data class SessionWithFailedTurn(
    val sessionId: SessionId,
    val messages: List<Message>,
)

/**
 * Generates random sessions with 1-5 user/assistant message pairs,
 * where the last assistant message has a failed status (ERROR or CANCELLED).
 */
private fun arbSessionWithFailedTurn(): Arb<SessionWithFailedTurn> = arbitrary {
    val sessionId = SessionId("session-" + UUID.randomUUID().toString().take(8))
    val messageCount = Arb.int(1..5).bind()
    val failedStatus = Arb.of(MessageStatus.ERROR, MessageStatus.CANCELLED).bind()

    val messages = mutableListOf<Message>()
    val baseTime = Instant.parse("2024-01-15T10:00:00Z")
    var position = 0

    for (i in 0 until messageCount) {
        val userContent = Arb.string(1..100).bind().let { if (it.isBlank()) "message $i" else it }

        // Add user message
        messages.add(
            Message(
                id = MessageId("user-$i-${UUID.randomUUID().toString().take(4)}"),
                sessionId = sessionId,
                role = MessageRole.USER,
                createdAt = baseTime.plusSeconds(position.toLong()),
                position = position,
                content = userContent,
                status = MessageStatus.COMPLETE,
                toolCallMetadata = null,
            )
        )
        position++

        // Add assistant message — last one is failed, others are complete
        val assistantStatus = if (i == messageCount - 1) failedStatus else MessageStatus.COMPLETE
        messages.add(
            Message(
                id = MessageId("assistant-$i-${UUID.randomUUID().toString().take(4)}"),
                sessionId = sessionId,
                role = MessageRole.ASSISTANT,
                createdAt = baseTime.plusSeconds(position.toLong()),
                position = position,
                content = "Response $i",
                status = assistantStatus,
                toolCallMetadata = null,
            )
        )
        position++
    }

    SessionWithFailedTurn(sessionId = sessionId, messages = messages)
}

// ===== Fake Implementations =====

/**
 * A fake ConversationRepository that stores messages in memory and tracks insertions.
 * This allows verifying that no duplicate user messages are created during retry.
 */
private class FakeConversationRepository(
    private val messages: MutableList<Message>,
) : ConversationRepository {

    /** Tracks all messages that were inserted via [insertMessage]. */
    val insertedMessages = mutableListOf<Message>()

    override fun getSessions(): Flow<List<Session>> = flowOf(emptyList())

    override suspend fun getSession(id: SessionId): Session? = null

    override suspend fun getMessages(sessionId: SessionId): List<Message> {
        return messages.filter { it.sessionId == sessionId }.sortedBy { it.position }
    }

    override fun getMessagesFlow(sessionId: SessionId): Flow<List<Message>> {
        return flowOf(messages.filter { it.sessionId == sessionId }.sortedBy { it.position })
    }

    override suspend fun createSession(title: String, workspacePath: String): Session {
        return Session(
            id = SessionId(UUID.randomUUID().toString()),
            title = title,
            workspacePath = workspacePath,
            createdAt = Instant.now(),
            lastActivityAt = Instant.now(),
            messageCount = 0,
        )
    }

    override suspend fun updateSessionTitle(id: SessionId, title: String) {}

    override suspend fun deleteSession(id: SessionId) {
        messages.removeAll { it.sessionId == id }
    }

    override suspend fun insertMessage(message: Message): Message {
        insertedMessages.add(message)
        messages.add(message)
        return message
    }

    override suspend fun updateMessageContent(id: MessageId, content: String) {
        val index = messages.indexOfFirst { it.id == id }
        if (index >= 0) {
            messages[index] = messages[index].copy(content = content)
        }
    }

    override suspend fun updateMessageStatus(id: MessageId, status: MessageStatus) {
        val index = messages.indexOfFirst { it.id == id }
        if (index >= 0) {
            messages[index] = messages[index].copy(status = status)
        }
    }
}

/**
 * A fake CliBridge that records write calls without performing actual I/O.
 */
private class FakeCliBridge : CliBridge {
    val writtenBytes = mutableListOf<ByteArray>()

    override val outputFlow: Flow<BridgeEvent> = flowOf()
    override val processState: StateFlow<ProcessState> = MutableStateFlow(ProcessState.RUNNING)

    override suspend fun spawn(config: SpawnConfig): Result<ProcessHandle> {
        return Result.success(ProcessHandle(pid = 1234, startedAt = Instant.now()))
    }

    override suspend fun write(bytes: ByteArray) {
        writtenBytes.add(bytes)
    }

    override suspend fun sendSignal(signal: PosixSignal) {}

    override suspend fun terminate() {}
}
