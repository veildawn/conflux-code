package com.claudemobile.core.domain.usecase

import com.claudemobile.core.common.AppResult
import com.claudemobile.core.domain.bridge.CliBridge
import com.claudemobile.core.domain.model.Message
import com.claudemobile.core.domain.model.MessageId
import com.claudemobile.core.domain.model.MessageRole
import com.claudemobile.core.domain.model.MessageStatus
import com.claudemobile.core.domain.model.SessionId
import com.claudemobile.core.domain.repository.ConversationRepository
import io.kotest.common.ExperimentalKotest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.of
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
 * Property 17: For any failed message and retry operation, the total number of user messages
 * in the session should remain unchanged after retry (no duplicate user messages are created).
 */
@OptIn(ExperimentalKotest::class)
class RetryFailedTurnUseCasePropertyTest : FunSpec({

    tags(
        io.kotest.core.Tag("Feature: android-claude-termux-client"),
        io.kotest.core.Tag("Property 17: Retry does not duplicate messages")
    )

    test("Feature: android-claude-termux-client, Property 17: Retry does not duplicate messages") {
        // Arbitrary: generate a list of 1-5 user messages with unique positions
        val userMessageCountArb = Arb.int(1..5)
        val userContentArb = Arb.string(1..100)
        val failedStatusArb = Arb.of(MessageStatus.ERROR, MessageStatus.CANCELLED)

        checkAll(
            PropTestConfig(iterations = 100),
            userMessageCountArb,
            userContentArb,
            failedStatusArb,
        ) { userMsgCount, lastUserContent, failedStatus ->
            // Setup mocks for each iteration
            val conversationRepository = mockk<ConversationRepository>(relaxed = true)
            val cliBridge = mockk<CliBridge>(relaxed = true)
            val useCase = RetryFailedTurnUseCase(conversationRepository, cliBridge)

            val sessionId = SessionId("session-prop-test")

            // Build a conversation with alternating user/assistant messages
            // ending with a user message followed by a failed assistant message
            val messages = mutableListOf<Message>()
            var position = 0

            for (i in 0 until userMsgCount) {
                // Add user message
                val content = if (i == userMsgCount - 1) lastUserContent else "User message $i"
                messages.add(
                    Message(
                        id = MessageId("user-$i"),
                        sessionId = sessionId,
                        role = MessageRole.USER,
                        createdAt = Instant.parse("2024-01-15T10:00:00Z").plusSeconds(position.toLong()),
                        position = position,
                        content = content,
                        status = MessageStatus.COMPLETE,
                        toolCallMetadata = null,
                    )
                )
                position++

                // Add assistant message (complete for all but the last one)
                val assistantStatus = if (i == userMsgCount - 1) failedStatus else MessageStatus.COMPLETE
                messages.add(
                    Message(
                        id = MessageId("assistant-$i"),
                        sessionId = sessionId,
                        role = MessageRole.ASSISTANT,
                        createdAt = Instant.parse("2024-01-15T10:00:00Z").plusSeconds(position.toLong()),
                        position = position,
                        content = "Assistant response $i",
                        status = assistantStatus,
                        toolCallMetadata = null,
                    )
                )
                position++
            }

            // Count user messages before retry
            val userMessageCountBefore = messages.count { it.role == MessageRole.USER }
            userMessageCountBefore shouldBe userMsgCount
            userMessageCountBefore shouldBeGreaterThan 0

            // Mock repository to return our messages
            coEvery { conversationRepository.getMessages(sessionId) } returns messages

            // Invoke retry
            val result = useCase(sessionId)

            // The retry should succeed (we have a valid failed turn)
            result shouldBe AppResult.Success(messages.last { it.role == MessageRole.USER })

            // PROPERTY: No new user message was inserted into the repository
            // This ensures user message count remains unchanged
            coVerify(exactly = 0) {
                conversationRepository.insertMessage(match { it.role == MessageRole.USER })
            }

            // Additionally verify that no insertMessage was called at all
            // (retry should only resend, not create new messages)
            coVerify(exactly = 0) {
                conversationRepository.insertMessage(any())
            }
        }
    }
})
