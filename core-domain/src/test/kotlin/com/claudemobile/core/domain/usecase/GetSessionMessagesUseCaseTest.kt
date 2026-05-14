package com.claudemobile.core.domain.usecase

import app.cash.turbine.test
import com.claudemobile.core.domain.model.Message
import com.claudemobile.core.domain.model.MessageId
import com.claudemobile.core.domain.model.MessageRole
import com.claudemobile.core.domain.model.MessageStatus
import com.claudemobile.core.domain.model.SessionId
import com.claudemobile.core.domain.repository.ConversationRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import java.time.Instant

class GetSessionMessagesUseCaseTest : DescribeSpec({

    val conversationRepository = mockk<ConversationRepository>()
    val useCase = GetSessionMessagesUseCase(conversationRepository)

    describe("GetSessionMessagesUseCase") {

        describe("invoke (Flow)") {

            it("returns flow of messages in position order") {
                val sessionId = SessionId("session-1")
                val messages = listOf(
                    Message(
                        id = MessageId("msg-1"),
                        sessionId = sessionId,
                        role = MessageRole.USER,
                        createdAt = Instant.parse("2024-01-01T00:00:00Z"),
                        position = 0,
                        content = "Hello",
                        status = MessageStatus.COMPLETE,
                        toolCallMetadata = null,
                    ),
                    Message(
                        id = MessageId("msg-2"),
                        sessionId = sessionId,
                        role = MessageRole.ASSISTANT,
                        createdAt = Instant.parse("2024-01-01T00:00:01Z"),
                        position = 1,
                        content = "Hi there!",
                        status = MessageStatus.COMPLETE,
                        toolCallMetadata = null,
                    ),
                )
                every { conversationRepository.getMessagesFlow(sessionId) } returns flowOf(messages)

                useCase(sessionId).test {
                    awaitItem() shouldBe messages
                    awaitComplete()
                }
            }

            it("returns empty list flow when session has no messages") {
                val sessionId = SessionId("session-empty")
                every { conversationRepository.getMessagesFlow(sessionId) } returns flowOf(emptyList())

                useCase(sessionId).test {
                    awaitItem() shouldBe emptyList()
                    awaitComplete()
                }
            }
        }

        describe("getOnce") {

            it("returns messages in position order") {
                val sessionId = SessionId("session-1")
                val messages = listOf(
                    Message(
                        id = MessageId("msg-1"),
                        sessionId = sessionId,
                        role = MessageRole.USER,
                        createdAt = Instant.parse("2024-01-01T00:00:00Z"),
                        position = 0,
                        content = "Hello",
                        status = MessageStatus.COMPLETE,
                        toolCallMetadata = null,
                    ),
                    Message(
                        id = MessageId("msg-2"),
                        sessionId = sessionId,
                        role = MessageRole.ASSISTANT,
                        createdAt = Instant.parse("2024-01-01T00:00:01Z"),
                        position = 1,
                        content = "Hi there!",
                        status = MessageStatus.COMPLETE,
                        toolCallMetadata = null,
                    ),
                )
                coEvery { conversationRepository.getMessages(sessionId) } returns messages

                val result = useCase.getOnce(sessionId)

                result shouldBe messages
            }

            it("returns empty list when session has no messages") {
                val sessionId = SessionId("session-empty")
                coEvery { conversationRepository.getMessages(sessionId) } returns emptyList()

                val result = useCase.getOnce(sessionId)

                result shouldBe emptyList()
            }
        }
    }
})
