package com.claudemobile.core.domain.usecase

import com.claudemobile.core.domain.bridge.CliBridge
import com.claudemobile.core.domain.model.Message
import com.claudemobile.core.domain.model.MessageId
import com.claudemobile.core.domain.model.MessageRole
import com.claudemobile.core.domain.model.MessageStatus
import com.claudemobile.core.domain.model.SessionId
import com.claudemobile.core.domain.repository.ConversationRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant

/**
 * Property-based test for Retry Does Not Duplicate Messages.
 *
 * **Validates: Requirements 11.3**
 *
 * Property 13: For any failed turn followed by any number of retry actions (1-5),
 * the conversation history SHALL contain exactly one user Message for that turn
 * (the original), and no duplicate user Messages SHALL be created.
 *
 * Tags: Feature: android-claude-termux-client, Property 13: Retry Does Not Duplicate Messages
 */
class RetryNoDuplicationPropertyTest : FunSpec({

    tags(
        io.kotest.core.Tag("Feature: android-claude-termux-client"),
        io.kotest.core.Tag("Property 13: Retry Does Not Duplicate Messages")
    )

    test("retry never calls insertMessage regardless of retry count") {
        checkAll(PropTestConfig(iterations = 100), arbRetryScenario()) { scenario ->
            val conversationRepository = mockk<ConversationRepository>(relaxed = true)
            val cliBridge = mockk<CliBridge>(relaxed = true)

            val useCase = RetryFailedTurnUseCase(
                conversationRepository = conversationRepository,
                cliBridge = cliBridge,
            )

            // Set up the conversation state: one user message followed by a failed assistant message
            val messages = listOf(
                Message(
                    id = MessageId("user-msg-${scenario.sessionId}"),
                    sessionId = SessionId(scenario.sessionId),
                    role = MessageRole.USER,
                    createdAt = Instant.parse("2024-01-15T10:00:00Z"),
                    position = 0,
                    content = scenario.userMessageContent,
                    status = MessageStatus.COMPLETE,
                    toolCallMetadata = null,
                ),
                Message(
                    id = MessageId("assistant-msg-${scenario.sessionId}"),
                    sessionId = SessionId(scenario.sessionId),
                    role = MessageRole.ASSISTANT,
                    createdAt = Instant.parse("2024-01-15T10:00:01Z"),
                    position = 1,
                    content = "Failed response",
                    status = MessageStatus.ERROR,
                    toolCallMetadata = null,
                ),
            )

            coEvery {
                conversationRepository.getMessages(SessionId(scenario.sessionId))
            } returns messages

            // Execute retries
            repeat(scenario.retryCount) {
                useCase(SessionId(scenario.sessionId))
            }

            // Verify: insertMessage is never called — no duplicate messages created
            coVerify(exactly = 0) {
                conversationRepository.insertMessage(any())
            }
        }
    }

    test("retry resends the same user message content each time") {
        checkAll(PropTestConfig(iterations = 100), arbRetryScenario()) { scenario ->
            val conversationRepository = mockk<ConversationRepository>(relaxed = true)
            val cliBridge = mockk<CliBridge>(relaxed = true)

            val useCase = RetryFailedTurnUseCase(
                conversationRepository = conversationRepository,
                cliBridge = cliBridge,
            )

            val messages = listOf(
                Message(
                    id = MessageId("user-msg-${scenario.sessionId}"),
                    sessionId = SessionId(scenario.sessionId),
                    role = MessageRole.USER,
                    createdAt = Instant.parse("2024-01-15T10:00:00Z"),
                    position = 0,
                    content = scenario.userMessageContent,
                    status = MessageStatus.COMPLETE,
                    toolCallMetadata = null,
                ),
                Message(
                    id = MessageId("assistant-msg-${scenario.sessionId}"),
                    sessionId = SessionId(scenario.sessionId),
                    role = MessageRole.ASSISTANT,
                    createdAt = Instant.parse("2024-01-15T10:00:01Z"),
                    position = 1,
                    content = "Error occurred",
                    status = MessageStatus.ERROR,
                    toolCallMetadata = null,
                ),
            )

            coEvery {
                conversationRepository.getMessages(SessionId(scenario.sessionId))
            } returns messages

            // Execute retries
            repeat(scenario.retryCount) {
                useCase(SessionId(scenario.sessionId))
            }

            // Verify: the same content is written to the bridge each time
            val expectedBytes = (scenario.userMessageContent + "\n").toByteArray(Charsets.UTF_8)
            coVerify(exactly = scenario.retryCount) {
                cliBridge.write(expectedBytes)
            }
        }
    }

    test("conversation history user message count remains exactly one after retries") {
        checkAll(PropTestConfig(iterations = 100), arbRetryScenario()) { scenario ->
            val conversationRepository = mockk<ConversationRepository>(relaxed = true)
            val cliBridge = mockk<CliBridge>(relaxed = true)

            val useCase = RetryFailedTurnUseCase(
                conversationRepository = conversationRepository,
                cliBridge = cliBridge,
            )

            val messages = listOf(
                Message(
                    id = MessageId("user-msg-${scenario.sessionId}"),
                    sessionId = SessionId(scenario.sessionId),
                    role = MessageRole.USER,
                    createdAt = Instant.parse("2024-01-15T10:00:00Z"),
                    position = 0,
                    content = scenario.userMessageContent,
                    status = MessageStatus.COMPLETE,
                    toolCallMetadata = null,
                ),
                Message(
                    id = MessageId("assistant-msg-${scenario.sessionId}"),
                    sessionId = SessionId(scenario.sessionId),
                    role = MessageRole.ASSISTANT,
                    createdAt = Instant.parse("2024-01-15T10:00:01Z"),
                    position = 1,
                    content = "Network error",
                    status = MessageStatus.ERROR,
                    toolCallMetadata = null,
                ),
            )

            coEvery {
                conversationRepository.getMessages(SessionId(scenario.sessionId))
            } returns messages

            // Execute retries
            repeat(scenario.retryCount) {
                useCase(SessionId(scenario.sessionId))
            }

            // Since insertMessage is never called, the user message count stays at 1
            val userMessages = messages.filter { it.role == MessageRole.USER }
            userMessages.size shouldBe 1

            // Confirm no insertMessage calls were made
            coVerify(exactly = 0) {
                conversationRepository.insertMessage(any())
            }
        }
    }
})

/**
 * Data class representing a retry scenario with random parameters.
 */
private data class RetryScenario(
    val sessionId: String,
    val userMessageContent: String,
    val retryCount: Int,
)

/**
 * Generates random retry scenarios with:
 * - A non-blank session ID
 * - Non-empty user message content
 * - A retry count between 1 and 5
 */
private fun arbRetryScenario(): Arb<RetryScenario> = arbitrary {
    val sessionId = "session-" + Arb.string(minSize = 4, maxSize = 16).bind()
        .filterNot { it.isWhitespace() }
        .ifEmpty { "default" }
    val content = Arb.string(minSize = 1, maxSize = 200).bind()
        .let { if (it.isBlank()) "retry message" else it }
    val retryCount = Arb.int(1, 5).bind()

    RetryScenario(
        sessionId = sessionId,
        userMessageContent = content,
        retryCount = retryCount,
    )
}
