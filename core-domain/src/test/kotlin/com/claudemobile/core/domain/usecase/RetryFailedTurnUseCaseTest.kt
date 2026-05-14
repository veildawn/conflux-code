package com.claudemobile.core.domain.usecase

import com.claudemobile.core.common.AppResult
import com.claudemobile.core.common.ErrorCode
import com.claudemobile.core.domain.bridge.CliBridge
import com.claudemobile.core.domain.model.Message
import com.claudemobile.core.domain.model.MessageId
import com.claudemobile.core.domain.model.MessageRole
import com.claudemobile.core.domain.model.MessageStatus
import com.claudemobile.core.domain.model.SessionId
import com.claudemobile.core.domain.repository.ConversationRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant

class RetryFailedTurnUseCaseTest : DescribeSpec({

    val conversationRepository = mockk<ConversationRepository>(relaxed = true)
    val cliBridge = mockk<CliBridge>(relaxed = true)

    val useCase = RetryFailedTurnUseCase(
        conversationRepository = conversationRepository,
        cliBridge = cliBridge,
    )

    val testSessionId = SessionId("session-123")

    describe("RetryFailedTurnUseCase") {

        it("returns failure when session ID is blank") {
            val result = useCase(SessionId(""))

            result.shouldBeInstanceOf<AppResult.Failure>()
            result.error.code shouldBe ErrorCode.INVALID_ARGUMENT
        }

        it("returns failure when no user message for failed turn exists") {
            coEvery { conversationRepository.getMessages(testSessionId) } returns listOf(
                createMessage(MessageRole.USER, MessageStatus.COMPLETE, position = 0),
                createMessage(MessageRole.ASSISTANT, MessageStatus.COMPLETE, position = 1),
            )

            val result = useCase(testSessionId)

            result.shouldBeInstanceOf<AppResult.Failure>()
            result.error.code shouldBe ErrorCode.NOT_FOUND
        }

        it("resends user message when assistant message has ERROR status") {
            val userMessage = createMessage(
                role = MessageRole.USER,
                status = MessageStatus.COMPLETE,
                id = "user-msg-1",
                content = "Hello Claude",
                position = 0,
            )
            val failedAssistant = createMessage(
                role = MessageRole.ASSISTANT,
                status = MessageStatus.ERROR,
                id = "assistant-msg-1",
                position = 1,
            )
            coEvery { conversationRepository.getMessages(testSessionId) } returns listOf(
                userMessage,
                failedAssistant,
            )

            val result = useCase(testSessionId)

            result.shouldBeInstanceOf<AppResult.Success<Message>>()
            result.value shouldBe userMessage
            coVerify {
                cliBridge.write("Hello Claude\n".toByteArray(Charsets.UTF_8))
            }
        }

        it("resends user message when assistant message has CANCELLED status") {
            val userMessage = createMessage(
                role = MessageRole.USER,
                status = MessageStatus.COMPLETE,
                id = "user-msg-2",
                content = "Tell me about Kotlin",
                position = 0,
            )
            val cancelledAssistant = createMessage(
                role = MessageRole.ASSISTANT,
                status = MessageStatus.CANCELLED,
                id = "assistant-msg-2",
                position = 1,
            )
            coEvery { conversationRepository.getMessages(testSessionId) } returns listOf(
                userMessage,
                cancelledAssistant,
            )

            val result = useCase(testSessionId)

            result.shouldBeInstanceOf<AppResult.Success<Message>>()
            result.value shouldBe userMessage
        }

        it("does not create a new message in the conversation history") {
            val userMessage = createMessage(
                role = MessageRole.USER,
                status = MessageStatus.COMPLETE,
                id = "user-msg-3",
                content = "Retry this",
                position = 0,
            )
            val failedAssistant = createMessage(
                role = MessageRole.ASSISTANT,
                status = MessageStatus.ERROR,
                id = "assistant-msg-3",
                position = 1,
            )
            coEvery { conversationRepository.getMessages(testSessionId) } returns listOf(
                userMessage,
                failedAssistant,
            )

            useCase(testSessionId)

            coVerify(exactly = 0) {
                conversationRepository.insertMessage(any())
            }
        }

        it("returns failure when bridge write fails") {
            val userMessage = createMessage(
                role = MessageRole.USER,
                status = MessageStatus.COMPLETE,
                id = "user-msg-4",
                content = "Retry this",
                position = 0,
            )
            val failedAssistant = createMessage(
                role = MessageRole.ASSISTANT,
                status = MessageStatus.ERROR,
                id = "assistant-msg-4",
                position = 1,
            )
            coEvery { conversationRepository.getMessages(testSessionId) } returns listOf(
                userMessage,
                failedAssistant,
            )
            coEvery { cliBridge.write(any()) } throws RuntimeException("Bridge disconnected")

            val result = useCase(testSessionId)

            result.shouldBeInstanceOf<AppResult.Failure>()
            result.error.code shouldBe ErrorCode.PROCESS_ERROR
        }

        it("finds user message when assistant never responded") {
            // Reset mock to relaxed behavior after previous test configured it to throw
            io.mockk.clearMocks(cliBridge, answers = true)

            val userMessage = createMessage(
                role = MessageRole.USER,
                status = MessageStatus.COMPLETE,
                id = "user-msg-5",
                content = "No response yet",
                position = 0,
            )
            coEvery { conversationRepository.getMessages(testSessionId) } returns listOf(userMessage)

            val result = useCase(testSessionId)

            result.shouldBeInstanceOf<AppResult.Success<Message>>()
            result.value shouldBe userMessage
        }
    }
}) {
    companion object {
        fun createMessage(
            role: MessageRole,
            status: MessageStatus,
            id: String = "msg-${role.name.lowercase()}",
            content: String = "Content for $role",
            position: Int = 0,
        ): Message = Message(
            id = MessageId(id),
            sessionId = SessionId("session-123"),
            role = role,
            createdAt = Instant.parse("2024-01-15T10:00:00Z"),
            position = position,
            content = content,
            status = status,
            toolCallMetadata = null,
        )
    }
}
