package com.claudemobile.core.domain.usecase

import com.claudemobile.core.common.AppResult
import com.claudemobile.core.common.ErrorCode
import com.claudemobile.core.domain.bridge.CliBridge
import com.claudemobile.core.domain.bridge.PosixSignal
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
import io.mockk.coVerifyOrder
import io.mockk.mockk
import java.time.Instant

class CancelTurnUseCaseTest : DescribeSpec({

    val conversationRepository = mockk<ConversationRepository>(relaxed = true)
    val cliBridge = mockk<CliBridge>(relaxed = true)

    val useCase = CancelTurnUseCase(
        conversationRepository = conversationRepository,
        cliBridge = cliBridge,
    )

    val testSessionId = SessionId("session-123")

    describe("CancelTurnUseCase") {

        it("returns failure when session ID is blank") {
            val result = useCase(SessionId(""))

            result.shouldBeInstanceOf<AppResult.Failure>()
            result.error.code shouldBe ErrorCode.INVALID_ARGUMENT
        }

        it("returns failure when no active assistant message exists") {
            coEvery { conversationRepository.getMessages(testSessionId) } returns listOf(
                createMessage(MessageRole.USER, MessageStatus.COMPLETE),
                createMessage(MessageRole.ASSISTANT, MessageStatus.COMPLETE, position = 1),
            )

            val result = useCase(testSessionId)

            result.shouldBeInstanceOf<AppResult.Failure>()
            result.error.code shouldBe ErrorCode.NOT_FOUND
        }

        it("sends SIGINT and marks streaming message as cancelled") {
            val streamingMessage = createMessage(
                role = MessageRole.ASSISTANT,
                status = MessageStatus.STREAMING,
                id = "assistant-msg-1",
                position = 1,
            )
            coEvery { conversationRepository.getMessages(testSessionId) } returns listOf(
                createMessage(MessageRole.USER, MessageStatus.COMPLETE),
                streamingMessage,
            )

            val result = useCase(testSessionId)

            result.shouldBeInstanceOf<AppResult.Success<MessageId>>()
            result.value shouldBe MessageId("assistant-msg-1")

            coVerifyOrder {
                cliBridge.sendSignal(PosixSignal.SIGINT)
                conversationRepository.updateMessageStatus(
                    MessageId("assistant-msg-1"),
                    MessageStatus.CANCELLED,
                )
            }
        }

        it("cancels message with SENDING status") {
            val sendingMessage = createMessage(
                role = MessageRole.ASSISTANT,
                status = MessageStatus.SENDING,
                id = "assistant-msg-2",
                position = 1,
            )
            coEvery { conversationRepository.getMessages(testSessionId) } returns listOf(
                createMessage(MessageRole.USER, MessageStatus.COMPLETE),
                sendingMessage,
            )

            val result = useCase(testSessionId)

            result.shouldBeInstanceOf<AppResult.Success<MessageId>>()
            result.value shouldBe MessageId("assistant-msg-2")
        }

        it("returns failure when signal sending fails") {
            val streamingMessage = createMessage(
                role = MessageRole.ASSISTANT,
                status = MessageStatus.STREAMING,
                id = "assistant-msg-3",
                position = 1,
            )
            coEvery { conversationRepository.getMessages(testSessionId) } returns listOf(
                createMessage(MessageRole.USER, MessageStatus.COMPLETE),
                streamingMessage,
            )
            coEvery { cliBridge.sendSignal(any()) } throws RuntimeException("Process not found")

            val result = useCase(testSessionId)

            result.shouldBeInstanceOf<AppResult.Failure>()
            result.error.code shouldBe ErrorCode.PROCESS_ERROR
        }

        it("finds the last active assistant message when multiple exist") {
            // Reset mock to relaxed behavior after previous test configured it to throw
            io.mockk.clearMocks(cliBridge, answers = true)

            coEvery { conversationRepository.getMessages(testSessionId) } returns listOf(
                createMessage(MessageRole.USER, MessageStatus.COMPLETE, position = 0),
                createMessage(MessageRole.ASSISTANT, MessageStatus.COMPLETE, id = "old-msg", position = 1),
                createMessage(MessageRole.USER, MessageStatus.COMPLETE, position = 2),
                createMessage(MessageRole.ASSISTANT, MessageStatus.STREAMING, id = "active-msg", position = 3),
            )

            val result = useCase(testSessionId)

            result.shouldBeInstanceOf<AppResult.Success<MessageId>>()
            result.value shouldBe MessageId("active-msg")
        }
    }
}) {
    companion object {
        fun createMessage(
            role: MessageRole,
            status: MessageStatus,
            id: String = "msg-${role.name.lowercase()}-${status.name.lowercase()}",
            position: Int = 0,
        ): Message = Message(
            id = MessageId(id),
            sessionId = SessionId("session-123"),
            role = role,
            createdAt = Instant.parse("2024-01-15T10:00:00Z"),
            position = position,
            content = "Content for $role",
            status = status,
            toolCallMetadata = null,
        )
    }
}
