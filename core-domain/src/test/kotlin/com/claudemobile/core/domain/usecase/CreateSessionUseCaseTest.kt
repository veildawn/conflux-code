package com.claudemobile.core.domain.usecase

import com.claudemobile.core.common.AppResult
import com.claudemobile.core.common.ErrorCode
import com.claudemobile.core.domain.model.Session
import com.claudemobile.core.domain.model.SessionId
import com.claudemobile.core.domain.providers.AuthHeaderStyle
import com.claudemobile.core.domain.providers.PresetReference
import com.claudemobile.core.domain.providers.ProviderProfile
import com.claudemobile.core.domain.providers.ProviderProfileStore
import com.claudemobile.core.domain.repository.ConversationRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant

class CreateSessionUseCaseTest : DescribeSpec({

    val conversationRepository = mockk<ConversationRepository>()
    val providerProfileStore = mockk<ProviderProfileStore>()
    val useCase = CreateSessionUseCase(conversationRepository, providerProfileStore)

    val activeProfile = ProviderProfile(
        profileId = "profile-1",
        displayName = "GLM Coding",
        baseUrl = "https://open.bigmodel.cn/api/anthropic",
        apiKey = "test-key-123",
        model = "glm-4.6",
        smallFastModel = null,
        authHeaderStyle = AuthHeaderStyle.AuthToken,
        presetReference = PresetReference.Preset("glm_coding_plan"),
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    describe("CreateSessionUseCase") {

        it("returns failure when no active provider profile is configured") {
            coEvery { providerProfileStore.getActive() } returns null

            val result = useCase("Test Session", "/workspace/path")

            result.shouldBeInstanceOf<AppResult.Failure>()
            result.error.code shouldBe ErrorCode.PERMISSION_DENIED
        }

        it("returns failure when workspace path is blank") {
            coEvery { providerProfileStore.getActive() } returns activeProfile

            val result = useCase("Test Session", "   ")

            result.shouldBeInstanceOf<AppResult.Failure>()
            result.error.code shouldBe ErrorCode.INVALID_ARGUMENT
        }

        it("returns failure when workspace path is empty") {
            coEvery { providerProfileStore.getActive() } returns activeProfile

            val result = useCase("Test Session", "")

            result.shouldBeInstanceOf<AppResult.Failure>()
            result.error.code shouldBe ErrorCode.INVALID_ARGUMENT
        }

        it("creates session successfully with valid inputs") {
            val expectedSession = Session(
                id = SessionId("session-1"),
                title = "Test Session",
                workspacePath = "/workspace/path",
                createdAt = Instant.now(),
                lastActivityAt = Instant.now(),
                messageCount = 0,
            )
            coEvery { providerProfileStore.getActive() } returns activeProfile
            coEvery { conversationRepository.createSession("Test Session", "/workspace/path") } returns expectedSession

            val result = useCase("Test Session", "/workspace/path")

            result.shouldBeInstanceOf<AppResult.Success<Session>>()
            result.value shouldBe expectedSession
        }

        it("uses default title when title is blank") {
            val expectedSession = Session(
                id = SessionId("session-2"),
                title = "New Session",
                workspacePath = "/workspace",
                createdAt = Instant.now(),
                lastActivityAt = Instant.now(),
                messageCount = 0,
            )
            coEvery { providerProfileStore.getActive() } returns activeProfile
            coEvery { conversationRepository.createSession("New Session", "/workspace") } returns expectedSession

            val result = useCase("", "/workspace")

            result.shouldBeInstanceOf<AppResult.Success<Session>>()
            coVerify { conversationRepository.createSession("New Session", "/workspace") }
        }
    }
})
