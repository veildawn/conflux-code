package com.claudemobile.core.domain.usecase

import com.claudemobile.core.common.AppResult
import com.claudemobile.core.common.ErrorCode
import com.claudemobile.core.common.TimeProvider
import com.claudemobile.core.common.UuidGenerator
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
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import java.time.Instant

class SendMessageUseCaseTest : DescribeSpec({

    val conversationRepository = mockk<ConversationRepository>(relaxed = true)
    val cliBridge = mockk<CliBridge>(relaxed = true)
    val timeProvider = mockk<TimeProvider>()
    val uuidGenerator = mockk<UuidGenerator>()

    val useCase = SendMessageUseCase(
        conversationRepository = conversationRepository,
        cliBridge = cliBridge,
        timeProvider = timeProvider,
        uuidGenerator = uuidGenerator,
    )

    val testSessionId = SessionId("session-123")
    val testTime = Instant.parse("2024-01-15T10:30:00Z")
    val testUuid = "msg-uuid-001"

    beforeEach {
        every { timeProvider.now() } returns testTime
        every { uuidGenerator.generate() } returns testUuid
    }

    describe("SendMessageUseCase") {

        it("returns failure when session ID is blank") {
            val result = useCase(SessionId(""), "Hello")

            result.shouldBeInstanceOf<AppResult.Failure>()
            result.error.code shouldBe ErrorCode.INVALID_ARGUMENT
        }

        it("returns failure when content is blank") {
            val result = useCase(testSessionId, "   ")

            result.shouldBeInstanceOf<AppResult.Failure>()
            result.error.code shouldBe ErrorCode.INVALID_ARGUMENT
        }

        it("returns failure when content is empty") {
            val result = useCase(testSessionId, "")

            result.shouldBeInstanceOf<AppResult.Failure>()
            result.error.code shouldBe ErrorCode.INVALID_ARGUMENT
        }

        it("persists message before forwarding to bridge") {
            coEvery { conversationRepository.getMessages(testSessionId) } returns emptyList()
            coEvery { conversationRepository.insertMessage(any()) } answers { firstArg() }

            useCase(testSessionId, "Hello Claude")

            coVerifyOrder {
                conversationRepository.insertMessage(any())
                cliBridge.write(any())
            }
        }

        it("creates message with SENDING status and correct fields") {
            coEvery { conversationRepository.getMessages(testSessionId) } returns emptyList()
            coEvery { conversationRepository.insertMessage(any()) } answers { firstArg() }

            useCase(testSessionId, "Hello Claude")

            coVerify {
                conversationRepository.insertMessage(match { msg ->
                    msg.id == MessageId(testUuid) &&
                        msg.sessionId == testSessionId &&
                        msg.role == MessageRole.USER &&
                        msg.createdAt == testTime &&
                        msg.position == 0 &&
                        msg.content == "Hello Claude" &&
                        msg.status == MessageStatus.SENDING &&
                        msg.toolCallMetadata == null
                })
            }
        }

        it("assigns correct position based on existing messages") {
            val existingMessages = listOf(
                createTestMessage(position = 0),
                createTestMessage(position = 1),
                createTestMessage(position = 2),
            )
            coEvery { conversationRepository.getMessages(testSessionId) } returns existingMessages
            coEvery { conversationRepository.insertMessage(any()) } answers { firstArg() }

            useCase(testSessionId, "Hello")

            coVerify {
                conversationRepository.insertMessage(match { it.position == 3 })
            }
        }

        it("writes content with newline to bridge") {
            coEvery { conversationRepository.getMessages(testSessionId) } returns emptyList()
            coEvery { conversationRepository.insertMessage(any()) } answers { firstArg() }

            useCase(testSessionId, "Hello Claude")

            coVerify {
                cliBridge.write("Hello Claude\n".toByteArray(Charsets.UTF_8))
            }
        }

        it("marks message as COMPLETE after successful bridge write") {
            coEvery { conversationRepository.getMessages(testSessionId) } returns emptyList()
            coEvery { conversationRepository.insertMessage(any()) } answers { firstArg() }

            val result = useCase(testSessionId, "Hello Claude")

            result.shouldBeInstanceOf<AppResult.Success<Message>>()
            result.value.status shouldBe MessageStatus.COMPLETE
            coVerify {
                conversationRepository.updateMessageStatus(MessageId(testUuid), MessageStatus.COMPLETE)
            }
        }

        it("marks message as ERROR when bridge write fails") {
            coEvery { conversationRepository.getMessages(testSessionId) } returns emptyList()
            coEvery { conversationRepository.insertMessage(any()) } answers { firstArg() }
            coEvery { cliBridge.write(any()) } throws RuntimeException("Bridge disconnected")

            val result = useCase(testSessionId, "Hello Claude")

            result.shouldBeInstanceOf<AppResult.Failure>()
            result.error.code shouldBe ErrorCode.PROCESS_ERROR
            coVerify {
                conversationRepository.updateMessageStatus(MessageId(testUuid), MessageStatus.ERROR)
            }
        }
    }
}) {
    companion object {
        fun createTestMessage(position: Int): Message = Message(
            id = MessageId("existing-$position"),
            sessionId = SessionId("session-123"),
            role = MessageRole.USER,
            createdAt = Instant.parse("2024-01-15T10:00:00Z"),
            position = position,
            content = "Message $position",
            status = MessageStatus.COMPLETE,
            toolCallMetadata = null,
        )
    }
}
