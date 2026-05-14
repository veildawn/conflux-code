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

class RenameSessionUseCaseTest : DescribeSpec({

    val conversationRepository = mockk<ConversationRepository>()
    val useCase = RenameSessionUseCase(conversationRepository)

    describe("RenameSessionUseCase") {

        it("returns failure when session ID is blank") {
            val result = useCase(SessionId(""), "New Title")

            result.shouldBeInstanceOf<AppResult.Failure>()
            result.error.code shouldBe ErrorCode.INVALID_ARGUMENT
        }

        it("returns failure when new title is blank") {
            val result = useCase(SessionId("session-1"), "")

            result.shouldBeInstanceOf<AppResult.Failure>()
            result.error.code shouldBe ErrorCode.INVALID_ARGUMENT
        }

        it("returns failure when new title is whitespace") {
            val result = useCase(SessionId("session-1"), "   ")

            result.shouldBeInstanceOf<AppResult.Failure>()
            result.error.code shouldBe ErrorCode.INVALID_ARGUMENT
        }

        it("renames session successfully with valid inputs") {
            val sessionId = SessionId("session-1")
            coEvery { conversationRepository.updateSessionTitle(sessionId, "Updated Title") } returns Unit

            val result = useCase(sessionId, "Updated Title")

            result.shouldBeInstanceOf<AppResult.Success<Unit>>()
            coVerify { conversationRepository.updateSessionTitle(sessionId, "Updated Title") }
        }
    }
})
