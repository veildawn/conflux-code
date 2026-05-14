package com.claudemobile.core.domain.usecase

import app.cash.turbine.test
import com.claudemobile.core.domain.model.Session
import com.claudemobile.core.domain.model.SessionId
import com.claudemobile.core.domain.repository.ConversationRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import java.time.Instant

class GetSessionsUseCaseTest : DescribeSpec({

    val conversationRepository = mockk<ConversationRepository>()
    val useCase = GetSessionsUseCase(conversationRepository)

    describe("GetSessionsUseCase") {

        it("returns flow of sessions from repository") {
            val sessions = listOf(
                Session(
                    id = SessionId("session-1"),
                    title = "Recent Session",
                    workspacePath = "/workspace/a",
                    createdAt = Instant.parse("2024-01-01T00:00:00Z"),
                    lastActivityAt = Instant.parse("2024-01-02T00:00:00Z"),
                    messageCount = 5,
                ),
                Session(
                    id = SessionId("session-2"),
                    title = "Older Session",
                    workspacePath = "/workspace/b",
                    createdAt = Instant.parse("2024-01-01T00:00:00Z"),
                    lastActivityAt = Instant.parse("2024-01-01T12:00:00Z"),
                    messageCount = 3,
                ),
            )
            every { conversationRepository.getSessions() } returns flowOf(sessions)

            useCase().test {
                awaitItem() shouldBe sessions
                awaitComplete()
            }
        }

        it("returns empty list when no sessions exist") {
            every { conversationRepository.getSessions() } returns flowOf(emptyList())

            useCase().test {
                awaitItem() shouldBe emptyList()
                awaitComplete()
            }
        }
    }
})
