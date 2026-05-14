package com.claudemobile.feature.sessions

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import app.cash.turbine.test
import com.claudemobile.core.domain.providers.AuthHeaderStyle
import com.claudemobile.core.domain.providers.PresetReference
import com.claudemobile.core.domain.providers.ProviderProfile
import com.claudemobile.core.domain.providers.ProviderProfileStore
import com.claudemobile.core.domain.providers.usecase.GetActiveProfileUseCase
import com.claudemobile.core.domain.usecase.CreateSessionUseCase
import com.claudemobile.core.domain.usecase.DeleteSessionUseCase
import com.claudemobile.core.domain.usecase.GetSessionsUseCase
import com.claudemobile.core.domain.usecase.RenameSessionUseCase
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Tests for the Active_Profile gate in [SessionsViewModel].
 *
 * Covers both branches of R5 AC5 / R11 AC5:
 *  1. Active_Profile present → session creation proceeds normally.
 *  2. No Active_Profile → navigation effect to ProviderSelectionScreen is emitted;
 *     session creation is NOT invoked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModelActiveProfileTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var fakeRepository: FakeSessionsRepository
    private lateinit var getSessionsUseCase: GetSessionsUseCase
    private lateinit var createSessionUseCase: CreateSessionUseCase
    private lateinit var deleteSessionUseCase: DeleteSessionUseCase
    private lateinit var renameSessionUseCase: RenameSessionUseCase
    private lateinit var mockContext: Context
    private lateinit var mockContentResolver: ContentResolver

    /** Mutable active-profile state shared across tests. */
    private val activeProfileFlow = MutableStateFlow<ProviderProfile?>(null)
    private lateinit var fakeProfileStore: ProviderProfileStore
    private lateinit var getActiveProfileUseCase: GetActiveProfileUseCase

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeSessionsRepository()
        fakeProfileStore = FakeProviderProfileStore(activeProfileFlow)
        getSessionsUseCase = GetSessionsUseCase(fakeRepository)
        createSessionUseCase = CreateSessionUseCase(fakeRepository, fakeProfileStore)
        deleteSessionUseCase = DeleteSessionUseCase(fakeRepository)
        renameSessionUseCase = RenameSessionUseCase(fakeRepository)

        mockContext = mockk(relaxed = true)
        mockContentResolver = mockk(relaxed = true)
        every { mockContext.contentResolver } returns mockContentResolver

        getActiveProfileUseCase = GetActiveProfileUseCase(fakeProfileStore)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): SessionsViewModel = SessionsViewModel(
        getSessionsUseCase = getSessionsUseCase,
        createSessionUseCase = createSessionUseCase,
        deleteSessionUseCase = deleteSessionUseCase,
        renameSessionUseCase = renameSessionUseCase,
        getActiveProfileUseCase = getActiveProfileUseCase,
        context = mockContext,
    )

    // -------------------------------------------------------------------------
    // canStartNewSession StateFlow
    // -------------------------------------------------------------------------

    @Test
    fun `canStartNewSession is false when no active profile`() = runTest {
        activeProfileFlow.value = null
        val viewModel = createViewModel()

        viewModel.canStartNewSession.test {
            // Initial value is false (no profile)
            awaitItem() shouldBe false
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `canStartNewSession is true when active profile is set`() = runTest {
        activeProfileFlow.value = buildProfile()
        val viewModel = createViewModel()
        // Advance so the Eagerly-started stateIn coroutine can collect from the upstream flow.
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.canStartNewSession.test {
            awaitItem() shouldBe true
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `canStartNewSession updates reactively when profile changes`() = runTest {
        activeProfileFlow.value = null
        val viewModel = createViewModel()

        viewModel.canStartNewSession.test {
            awaitItem() shouldBe false

            // Set an active profile
            activeProfileFlow.value = buildProfile()
            awaitItem() shouldBe true

            // Clear the active profile
            activeProfileFlow.value = null
            awaitItem() shouldBe false

            cancelAndIgnoreRemainingEvents()
        }
    }

    // -------------------------------------------------------------------------
    // Branch 1: Active_Profile present → session creation proceeds
    // -------------------------------------------------------------------------

    @Test
    fun `CreateSession proceeds when active profile is set`() = runTest {
        activeProfileFlow.value = buildProfile()
        fakeRepository.sessionsFlow.emit(emptyList())
        val viewModel = createViewModel()
        val mockUri = mockk<Uri>(relaxed = true)

        viewModel.navigateToProviderSelectionEvents.test {
            viewModel.onAction(
                SessionsAction.CreateSession(
                    title = "My Session",
                    workspaceUri = mockUri,
                    workspacePath = "/some/path",
                )
            )
            testDispatcher.scheduler.advanceUntilIdle()

            // No navigation-to-provider event should be emitted
            expectNoEvents()
        }

        // Session was created
        fakeRepository.createdSessions.size shouldBe 1
        fakeRepository.createdSessions[0].first shouldBe "My Session"
    }

    @Test
    fun `newSessionEvents emitted when active profile is set and session created`() = runTest {
        activeProfileFlow.value = buildProfile()
        fakeRepository.sessionsFlow.emit(emptyList())
        val viewModel = createViewModel()
        val mockUri = mockk<Uri>(relaxed = true)

        viewModel.newSessionEvents.test {
            viewModel.onAction(
                SessionsAction.CreateSession(
                    title = "Test Session",
                    workspaceUri = mockUri,
                    workspacePath = "/path",
                )
            )
            testDispatcher.scheduler.advanceUntilIdle()

            // A new-session navigation event should be emitted
            val sessionId = awaitItem()
            sessionId.value.isNotBlank() shouldBe true
        }
    }

    // -------------------------------------------------------------------------
    // Branch 2: No Active_Profile → navigate to provider selection
    // -------------------------------------------------------------------------

    @Test
    fun `CreateSession emits navigateToProviderSelection when no active profile`() = runTest {
        activeProfileFlow.value = null
        fakeRepository.sessionsFlow.emit(emptyList())
        val viewModel = createViewModel()
        val mockUri = mockk<Uri>(relaxed = true)

        viewModel.navigateToProviderSelectionEvents.test {
            viewModel.onAction(
                SessionsAction.CreateSession(
                    title = "My Session",
                    workspaceUri = mockUri,
                    workspacePath = "/some/path",
                )
            )
            testDispatcher.scheduler.advanceUntilIdle()

            // Navigation effect must be emitted
            awaitItem() // Unit
        }
    }

    @Test
    fun `CreateSession does NOT create session when no active profile`() = runTest {
        activeProfileFlow.value = null
        fakeRepository.sessionsFlow.emit(emptyList())
        val viewModel = createViewModel()
        val mockUri = mockk<Uri>(relaxed = true)

        viewModel.onAction(
            SessionsAction.CreateSession(
                title = "My Session",
                workspaceUri = mockUri,
                workspacePath = "/some/path",
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // No session should have been created
        fakeRepository.createdSessions.size shouldBe 0
    }

    @Test
    fun `newSessionEvents NOT emitted when no active profile`() = runTest {
        activeProfileFlow.value = null
        fakeRepository.sessionsFlow.emit(emptyList())
        val viewModel = createViewModel()
        val mockUri = mockk<Uri>(relaxed = true)

        viewModel.newSessionEvents.test {
            viewModel.onAction(
                SessionsAction.CreateSession(
                    title = "My Session",
                    workspaceUri = mockUri,
                    workspacePath = "/some/path",
                )
            )
            testDispatcher.scheduler.advanceUntilIdle()

            // No new-session event should be emitted
            expectNoEvents()
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun buildProfile(
        profileId: String = "test-profile-id",
        displayName: String = "Test Provider",
    ): ProviderProfile = ProviderProfile(
        profileId = profileId,
        displayName = displayName,
        presetReference = PresetReference.Custom,
        baseUrl = "https://api.example.com",
        apiKey = "sk-test-key",
        model = "test-model",
        smallFastModel = null,
        authHeaderStyle = AuthHeaderStyle.ApiKey,
        createdAt = Instant.now().toEpochMilli(),
        updatedAt = Instant.now().toEpochMilli(),
    )
}

/**
 * Minimal fake [ProviderProfileStore] that delegates [observeActiveProfile] to a
 * [MutableStateFlow] so tests can control the active profile reactively.
 */
private class FakeProviderProfileStore(
    private val activeProfileFlow: MutableStateFlow<ProviderProfile?>,
) : ProviderProfileStore {

    override fun observeProfiles(): Flow<List<ProviderProfile>> = flowOf(emptyList())

    override fun observeActiveProfile(): Flow<ProviderProfile?> = activeProfileFlow

    override suspend fun list(): List<ProviderProfile> = emptyList()

    override suspend fun get(profileId: String): ProviderProfile? = null

    override suspend fun getActive(): ProviderProfile? = activeProfileFlow.value

    override suspend fun upsert(profile: ProviderProfile): Result<Unit> = Result.success(Unit)

    override suspend fun delete(profileId: String): Result<Unit> = Result.success(Unit)

    override suspend fun setActive(profileId: String?): Result<Unit> {
        activeProfileFlow.value = null
        return Result.success(Unit)
    }

    override suspend fun deleteAll(): Result<Unit> {
        activeProfileFlow.value = null
        return Result.success(Unit)
    }
}
