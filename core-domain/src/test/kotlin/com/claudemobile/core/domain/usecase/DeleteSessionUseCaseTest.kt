package com.claudemobile.core.domain.usecase

import com.claudemobile.core.common.AppResult
import com.claudemobile.core.common.ErrorCode
import com.claudemobile.core.domain.model.SessionId
import com.claudemobile.core.domain.repository.ConversationRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk

class DeleteSessionUseCaseTest : DescribeSpec({

    val conversationRepository = mockk<ConversationRepository>()
    val useCase = DeleteSessionUseCase(conversationRepository)

    describe("DeleteSessionUseCase") {

        it("returns failure when session ID is blank") {
            val result = useCase(SessionId(""))

            result.shouldBeInstanceOf<AppResult.Failure>()
            result.error.code shouldBe ErrorCode.INVALID_ARGUMENT
        }

        it("returns failure when session ID is whitespace") {
            val result = useCase(SessionId("   "))

            result.shouldBeInstanceOf<AppResult.Failure>()
            result.error.code shouldBe ErrorCode.INVALID_ARGUMENT
        }

        it("deletes session successfully with valid ID") {
            val sessionId = SessionId("session-123")
            coEvery { conversationRepository.deleteSession(sessionId) } returns Unit

            val result = useCase(sessionId)

            result.shouldBeInstanceOf<AppResult.Success<Unit>>()
            coVerify { conversationRepository.deleteSession(sessionId) }
        }
    }
})
