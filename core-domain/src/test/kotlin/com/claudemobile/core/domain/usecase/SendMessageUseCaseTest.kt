package com.claudemobile.core.domain.usecase

import com.claudemobile.core.common.AppResult
import com.claudemobile.core.common.ErrorCode
import com.claudemobile.core.common.TimeProvider
import com.claudemobile.core.common.UuidGenerator
import com.claudemobile.core.domain.bridge.CliBridge
import com.claudemobile.core.domain.bridge.ProcessHandle
import com.claudemobile.core.domain.bridge.SpawnConfig
import com.claudemobile.core.domain.model.Message
import com.claudemobile.core.domain.model.MessageId
import com.claudemobile.core.domain.model.MessageRole
import com.claudemobile.core.domain.model.MessageStatus
import com.claudemobile.core.domain.model.Session
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
    val prootEnvironmentProvider = object : ProotEnvironmentProvider {
        override fun buildSpawnConfig(workspacePath: String, apiKey: String): SpawnConfig =
            SpawnConfig(
                command = "/proot",
                args = listOf("/usr/bin/claude"),
                envVars = mapOf("ANTHROPIC_API_KEY" to apiKey),
                workingDir = workspacePath,
            )
    }

    val useCase = SendMessageUseCase(
        conversationRepository = conversationRepository,
        cliBridge = cliBridge,
        prootEnvironmentProvider = prootEnvironmentProvider,
        timeProvider = timeProvider,
        uuidGenerator = uuidGenerator,
    )

    val testSessionId = SessionId("session-123")
    val testTime = Instant.parse("2024-01-15T10:30:00Z")
    val testUuid = "msg-uuid-001"

    beforeEach {
        every { timeProvider.now() } returns testTime
        every { uuidGenerator.generate() } returns testUuid
        coEvery { conversationRepository.getSession(testSessionId) } returns Session(
            id = testSessionId,
            title = "Session",
            workspacePath = "/workspace",
            createdAt = testTime,
            lastActivityAt = testTime,
            messageCount = 0,
        )
        coEvery { conversationRepository.insertMessage(any()) } answers { firstArg() }
        coEvery { cliBridge.spawn(any()) } returns Result.success(
            ProcessHandle(pid = 1L, startedAt = testTime),
        )
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

        it("persists the optimistic message before spawning Claude") {
            coEvery { conversationRepository.getMessages(testSessionId) } returns emptyList()

            useCase(testSessionId, "Hello Claude")

            coVerifyOrder {
                conversationRepository.insertMessage(any())
                cliBridge.terminate()
                cliBridge.spawn(any())
            }
        }

        it("creates the first turn with --session-id") {
            coEvery { conversationRepository.getMessages(testSessionId) } returns emptyList()

            useCase(testSessionId, "Hello Claude")

            coVerify {
                cliBridge.spawn(match { config ->
                    config.args.containsAll(
                        listOf("--session-id", "session-123", "-p", "Hello Claude"),
                    )
                })
            }
        }

        it("resumes existing Claude transcripts on later turns") {
            coEvery { conversationRepository.getMessages(testSessionId) } returns listOf(
                createTestMessage(position = 0),
            )

            useCase(testSessionId, "Continue")

            coVerify {
                cliBridge.spawn(match { config ->
                    config.args.containsAll(
                        listOf("--resume", "session-123", "-p", "Continue"),
                    )
                })
            }
        }

        it("marks message as COMPLETE after successful spawn") {
            coEvery { conversationRepository.getMessages(testSessionId) } returns emptyList()

            val result = useCase(testSessionId, "Hello Claude")

            result.shouldBeInstanceOf<AppResult.Success<Message>>()
            result.value.status shouldBe MessageStatus.COMPLETE
            coVerify {
                conversationRepository.updateMessageStatus(MessageId(testUuid), MessageStatus.COMPLETE)
            }
        }

        it("marks message as ERROR when spawn fails") {
            coEvery { conversationRepository.getMessages(testSessionId) } returns emptyList()
            coEvery { cliBridge.spawn(any()) } returns Result.failure(RuntimeException("spawn failed"))

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
