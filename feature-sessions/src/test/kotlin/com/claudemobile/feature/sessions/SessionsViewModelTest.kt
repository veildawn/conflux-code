package com.claudemobile.feature.sessions

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import app.cash.turbine.test
import com.claudemobile.core.common.AppError
import com.claudemobile.core.common.AppResult
import com.claudemobile.core.common.ErrorCode
import com.claudemobile.core.domain.model.Message
import com.claudemobile.core.domain.model.MessageId
import com.claudemobile.core.domain.model.MessageStatus
import com.claudemobile.core.domain.model.Session
import com.claudemobile.core.domain.model.SessionId
import com.claudemobile.core.domain.repository.ConversationRepository
import com.claudemobile.core.domain.usecase.CreateSessionUseCase
import com.claudemobile.core.domain.usecase.DeleteSessionUseCase
import com.claudemobile.core.domain.usecase.GetSessionsUseCase
import com.claudemobile.core.domain.usecase.RenameSessionUseCase
import com.claudemobile.core.domain.providers.AuthHeaderStyle
import com.claudemobile.core.domain.providers.PresetReference
import com.claudemobile.core.domain.providers.ProviderProfile
import com.claudemobile.core.domain.providers.ProviderProfileStore
import com.claudemobile.core.domain.providers.usecase.GetActiveProfileUseCase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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

@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepository: FakeSessionsRepository
    private lateinit var getSessionsUseCase: GetSessionsUseCase
    private lateinit var createSessionUseCase: CreateSessionUseCase
    private lateinit var deleteSessionUseCase: DeleteSessionUseCase
    private lateinit var renameSessionUseCase: RenameSessionUseCase
    private lateinit var getActiveProfileUseCase: GetActiveProfileUseCase
    private lateinit var mockContext: Context
    private lateinit var mockContentResolver: ContentResolver

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeSessionsRepository()
        getSessionsUseCase = GetSessionsUseCase(fakeRepository)
        createSessionUseCase = CreateSessionUseCase(fakeRepository, FakeAlwaysActiveProfileStore())
        deleteSessionUseCase = DeleteSessionUseCase(fakeRepository)
        renameSessionUseCase = RenameSessionUseCase(fakeRepository)
        // Default: active profile is set so existing session-creation tests pass.
        getActiveProfileUseCase = GetActiveProfileUseCase(
            FakeAlwaysActiveProfileStore()
        )
        mockContext = mockk(relaxed = true)
        mockContentResolver = mockk(relaxed = true)
        every { mockContext.contentResolver } returns mockContentResolver
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): SessionsViewModel {
        return SessionsViewModel(
            getSessionsUseCase = getSessionsUseCase,
            createSessionUseCase = createSessionUseCase,
            deleteSessionUseCase = deleteSessionUseCase,
            renameSessionUseCase = renameSessionUseCase,
            getActiveProfileUseCase = getActiveProfileUseCase,
            context = mockContext,
        )
    }

    @Test
    fun `initial state is Loading`() = runTest {
        val viewModel = createViewModel()
        viewModel.uiState.value.shouldBeInstanceOf<SessionsUiState.Loading>()
    }

    @Test
    fun `emits Empty state when no sessions exist`() = runTest {
        fakeRepository.sessionsFlow.emit(emptyList())
        val viewModel = createViewModel()

        viewModel.uiState.test {
            // Skip Loading
            awaitItem().shouldBeInstanceOf<SessionsUiState.Loading>()
            awaitItem().shouldBeInstanceOf<SessionsUiState.Empty>()
        }
    }

    @Test
    fun `emits Success state with sessions ordered by lastActivityAt desc`() = runTest {
        val sessions = listOf(
            createSession("1", "Session A", Instant.parse("2024-01-02T00:00:00Z")),
            createSession("2", "Session B", Instant.parse("2024-01-03T00:00:00Z")),
        )
        fakeRepository.sessionsFlow.emit(sessions)
        val viewModel = createViewModel()

        viewModel.uiState.test {
            awaitItem().shouldBeInstanceOf<SessionsUiState.Loading>()
            val success = awaitItem().shouldBeInstanceOf<SessionsUiState.Success>()
            success.sessions.size shouldBe 2
            success.filteredSessions.size shouldBe 2
            success.sessions[0].title shouldBe "Session A"
            success.sessions[1].title shouldBe "Session B"
        }
    }

    @Test
    fun `emits Error state when repository throws`() = runTest {
        fakeRepository.shouldThrow = true
        val viewModel = createViewModel()

        viewModel.uiState.test {
            awaitItem().shouldBeInstanceOf<SessionsUiState.Loading>()
            val error = awaitItem().shouldBeInstanceOf<SessionsUiState.Error>()
            error.message shouldBe "Test error"
        }
    }

    @Test
    fun `Retry action reloads sessions`() = runTest {
        fakeRepository.shouldThrow = true
        val viewModel = createViewModel()

        viewModel.uiState.test {
            awaitItem().shouldBeInstanceOf<SessionsUiState.Loading>()
            awaitItem().shouldBeInstanceOf<SessionsUiState.Error>()

            // Fix the error and retry
            fakeRepository.shouldThrow = false
            fakeRepository.sessionsFlow.emit(emptyList())
            viewModel.onAction(SessionsAction.Retry)

            awaitItem().shouldBeInstanceOf<SessionsUiState.Loading>()
            awaitItem().shouldBeInstanceOf<SessionsUiState.Empty>()
        }
    }

    @Test
    fun `DeleteSession action removes session`() = runTest {
        val sessions = listOf(createSession("1", "Session A"))
        fakeRepository.sessionsFlow.emit(sessions)
        val viewModel = createViewModel()

        viewModel.uiState.test {
            awaitItem() // Loading
            awaitItem() // Success

            viewModel.onAction(SessionsAction.DeleteSession(SessionId("1")))
            testDispatcher.scheduler.advanceUntilIdle()

            fakeRepository.deletedSessionIds.contains("1") shouldBe true
        }
    }

    @Test
    fun `RenameSession action updates session title`() = runTest {
        val sessions = listOf(createSession("1", "Old Title"))
        fakeRepository.sessionsFlow.emit(sessions)
        val viewModel = createViewModel()

        viewModel.uiState.test {
            awaitItem() // Loading
            awaitItem() // Success

            viewModel.onAction(SessionsAction.RenameSession(SessionId("1"), "New Title"))
            testDispatcher.scheduler.advanceUntilIdle()

            fakeRepository.renamedSessions["1"] shouldBe "New Title"
        }
    }

    @Test
    fun `CreateSession action with valid data creates session`() = runTest {
        fakeRepository.sessionsFlow.emit(emptyList())
        val viewModel = createViewModel()
        val mockUri = mockk<Uri>(relaxed = true)

        viewModel.uiState.test {
            awaitItem() // Loading
            awaitItem() // Empty

            viewModel.onAction(
                SessionsAction.CreateSession(
                    title = "My Session",
                    workspaceUri = mockUri,
                    workspacePath = "/some/path",
                )
            )
            testDispatcher.scheduler.advanceUntilIdle()

            fakeRepository.createdSessions.size shouldBe 1
            fakeRepository.createdSessions[0].first shouldBe "My Session"
            fakeRepository.createdSessions[0].second shouldBe "/some/path"
        }
    }

    @Test
    fun `CreateSession attempts to take persistable URI permission`() = runTest {
        fakeRepository.sessionsFlow.emit(emptyList())
        val viewModel = createViewModel()
        val mockUri = mockk<Uri>(relaxed = true)

        viewModel.uiState.test {
            awaitItem() // Loading
            awaitItem() // Empty

            viewModel.onAction(
                SessionsAction.CreateSession(
                    title = "Test",
                    workspaceUri = mockUri,
                    workspacePath = "/path",
                )
            )
            testDispatcher.scheduler.advanceUntilIdle()

            verify {
                mockContentResolver.takePersistableUriPermission(mockUri, any())
            }
        }
    }

    @Test
    fun `CreateSession handles takePersistableUriPermission failure gracefully`() = runTest {
        every {
            mockContentResolver.takePersistableUriPermission(any(), any())
        } throws SecurityException("Permission denied")

        fakeRepository.sessionsFlow.emit(emptyList())
        val viewModel = createViewModel()
        val mockUri = mockk<Uri>(relaxed = true)

        viewModel.uiState.test {
            awaitItem() // Loading
            awaitItem() // Empty

            viewModel.onAction(
                SessionsAction.CreateSession(
                    title = "Test",
                    workspaceUri = mockUri,
                    workspacePath = "/path",
                )
            )
            testDispatcher.scheduler.advanceUntilIdle()

            // Session should still be created despite permission failure
            fakeRepository.createdSessions.size shouldBe 1
        }
    }

    @Test
    fun `actionError emits error message on failed create`() = runTest {
        fakeRepository.sessionsFlow.emit(emptyList())
        // Use a provider store that reports no active profile.
        createSessionUseCase = CreateSessionUseCase(fakeRepository, FakeNoActiveProfileStore())
        val viewModel = SessionsViewModel(
            getSessionsUseCase = getSessionsUseCase,
            createSessionUseCase = createSessionUseCase,
            deleteSessionUseCase = deleteSessionUseCase,
            renameSessionUseCase = renameSessionUseCase,
            getActiveProfileUseCase = getActiveProfileUseCase,
            context = mockContext,
        )
        val mockUri = mockk<Uri>(relaxed = true)

        viewModel.actionError.test {
            awaitItem() shouldBe null

            viewModel.onAction(
                SessionsAction.CreateSession(
                    title = "Test",
                    workspaceUri = mockUri,
                    workspacePath = "/path",
                )
            )
            testDispatcher.scheduler.advanceUntilIdle()

            val error = awaitItem()
            error shouldBe "No API key configured. Please set an API key before creating a session."
        }
    }

    @Test
    fun `DismissError clears action error`() = runTest {
        fakeRepository.sessionsFlow.emit(emptyList())
        val viewModel = createViewModel()

        viewModel.onAction(SessionsAction.DismissError)
        viewModel.actionError.value shouldBe null
    }

    @Test
    fun `Search action filters sessions by title`() = runTest {
        val sessions = listOf(
            createSession("1", "Android Project", Instant.parse("2024-01-03T00:00:00Z")),
            createSession("2", "iOS Project", Instant.parse("2024-01-02T00:00:00Z")),
            createSession("3", "Web App", Instant.parse("2024-01-01T00:00:00Z")),
        )
        fakeRepository.sessionsFlow.emit(sessions)
        val viewModel = createViewModel()

        viewModel.uiState.test {
            awaitItem().shouldBeInstanceOf<SessionsUiState.Loading>()
            val initial = awaitItem().shouldBeInstanceOf<SessionsUiState.Success>()
            initial.filteredSessions.size shouldBe 3

            viewModel.onAction(SessionsAction.Search("project"))
            val filtered = awaitItem().shouldBeInstanceOf<SessionsUiState.Success>()
            filtered.searchQuery shouldBe "project"
            filtered.filteredSessions.size shouldBe 2
            filtered.filteredSessions[0].title shouldBe "Android Project"
            filtered.filteredSessions[1].title shouldBe "iOS Project"
        }
    }

    @Test
    fun `Search action filters sessions by workspace path`() = runTest {
        val sessions = listOf(
            createSession("1", "Session A", workspacePath = "/home/user/android"),
            createSession("2", "Session B", workspacePath = "/home/user/ios"),
        )
        fakeRepository.sessionsFlow.emit(sessions)
        val viewModel = createViewModel()

        viewModel.uiState.test {
            awaitItem().shouldBeInstanceOf<SessionsUiState.Loading>()
            awaitItem().shouldBeInstanceOf<SessionsUiState.Success>()

            viewModel.onAction(SessionsAction.Search("android"))
            val filtered = awaitItem().shouldBeInstanceOf<SessionsUiState.Success>()
            filtered.filteredSessions.size shouldBe 1
            filtered.filteredSessions[0].title shouldBe "Session A"
        }
    }

    @Test
    fun `Search with empty query shows all sessions`() = runTest {
        val sessions = listOf(
            createSession("1", "Alpha Project"),
            createSession("2", "Beta Project"),
        )
        fakeRepository.sessionsFlow.emit(sessions)
        val viewModel = createViewModel()

        viewModel.uiState.test {
            awaitItem().shouldBeInstanceOf<SessionsUiState.Loading>()
            awaitItem().shouldBeInstanceOf<SessionsUiState.Success>()

            // Filter first
            viewModel.onAction(SessionsAction.Search("Alpha"))
            val filtered = awaitItem().shouldBeInstanceOf<SessionsUiState.Success>()
            filtered.filteredSessions.size shouldBe 1

            // Clear search
            viewModel.onAction(SessionsAction.Search(""))
            val all = awaitItem().shouldBeInstanceOf<SessionsUiState.Success>()
            all.filteredSessions.size shouldBe 2
        }
    }

    @Test
    fun `Search is case insensitive`() = runTest {
        val sessions = listOf(
            createSession("1", "My Android App"),
            createSession("2", "Web Project"),
        )
        fakeRepository.sessionsFlow.emit(sessions)
        val viewModel = createViewModel()

        viewModel.uiState.test {
            awaitItem().shouldBeInstanceOf<SessionsUiState.Loading>()
            awaitItem().shouldBeInstanceOf<SessionsUiState.Success>()

            viewModel.onAction(SessionsAction.Search("ANDROID"))
            val filtered = awaitItem().shouldBeInstanceOf<SessionsUiState.Success>()
            filtered.filteredSessions.size shouldBe 1
            filtered.filteredSessions[0].title shouldBe "My Android App"
        }
    }

    @Test
    fun `offline operations - delete works without network`() = runTest {
        // Delete operates on local database, no network needed
        val sessions = listOf(createSession("1", "Session A"))
        fakeRepository.sessionsFlow.emit(sessions)
        val viewModel = createViewModel()

        viewModel.uiState.test {
            awaitItem() // Loading
            awaitItem() // Success

            viewModel.onAction(SessionsAction.DeleteSession(SessionId("1")))
            testDispatcher.scheduler.advanceUntilIdle()

            fakeRepository.deletedSessionIds.contains("1") shouldBe true
        }
    }

    @Test
    fun `offline operations - search works without network`() = runTest {
        // Search operates on cached local data, no network needed
        val sessions = listOf(
            createSession("1", "Offline Session"),
            createSession("2", "Other Session"),
        )
        fakeRepository.sessionsFlow.emit(sessions)
        val viewModel = createViewModel()

        viewModel.uiState.test {
            awaitItem().shouldBeInstanceOf<SessionsUiState.Loading>()
            awaitItem().shouldBeInstanceOf<SessionsUiState.Success>()

            viewModel.onAction(SessionsAction.Search("Offline"))
            val filtered = awaitItem().shouldBeInstanceOf<SessionsUiState.Success>()
            filtered.filteredSessions.size shouldBe 1
            filtered.filteredSessions[0].title shouldBe "Offline Session"
        }
    }

    // --- Helpers ---

    private fun createSession(
        id: String,
        title: String,
        lastActivityAt: Instant = Instant.now(),
        workspacePath: String = "/workspace",
    ): Session = Session(
        id = SessionId(id),
        title = title,
        workspacePath = workspacePath,
        createdAt = Instant.parse("2024-01-01T00:00:00Z"),
        lastActivityAt = lastActivityAt,
        messageCount = 5,
    )
}

/**
 * Fake ConversationRepository for ViewModel testing.
 */
class FakeSessionsRepository : ConversationRepository {
    val sessionsFlow = MutableSharedFlow<List<Session>>(replay = 1)
    val deletedSessionIds = mutableListOf<String>()
    val renamedSessions = mutableMapOf<String, String>()
    val createdSessions = mutableListOf<Pair<String, String>>()
    var shouldThrow = false

    override fun getSessions(): Flow<List<Session>> {
        if (shouldThrow) {
            return kotlinx.coroutines.flow.flow { throw RuntimeException("Test error") }
        }
        return sessionsFlow
    }

    override suspend fun getSession(id: SessionId): Session? = null

    override suspend fun getMessages(sessionId: SessionId): List<Message> = emptyList()

    override fun getMessagesFlow(sessionId: SessionId): Flow<List<Message>> = flowOf(emptyList())

    override suspend fun createSession(title: String, workspacePath: String): Session {
        createdSessions.add(title to workspacePath)
        return Session(
            id = SessionId("new-${createdSessions.size}"),
            title = title,
            workspacePath = workspacePath,
            createdAt = Instant.now(),
            lastActivityAt = Instant.now(),
            messageCount = 0,
        )
    }

    override suspend fun updateSessionTitle(id: SessionId, title: String) {
        renamedSessions[id.value] = title
    }

    override suspend fun deleteSession(id: SessionId) {
        deletedSessionIds.add(id.value)
    }

    override suspend fun insertMessage(message: Message): Message = message

    override suspend fun updateMessageContent(id: MessageId, content: String) {}

    override suspend fun updateMessageStatus(id: MessageId, status: MessageStatus) {}
}

/**
 * Fake [ProviderProfileStore] that always reports an active profile.
 * Used in [SessionsViewModelTest] so existing session-creation tests continue to pass
 * after [GetActiveProfileUseCase] was injected into [SessionsViewModel].
 */
class FakeAlwaysActiveProfileStore : ProviderProfileStore {
    private val profile = ProviderProfile(
        profileId = "default-test-profile",
        displayName = "Test Provider",
        presetReference = PresetReference.Custom,
        baseUrl = "https://api.example.com",
        apiKey = "sk-test-key",
        model = "test-model",
        smallFastModel = null,
        authHeaderStyle = AuthHeaderStyle.ApiKey,
        createdAt = Instant.now().toEpochMilli(),
        updatedAt = Instant.now().toEpochMilli(),
    )

    override fun observeProfiles(): Flow<List<ProviderProfile>> = flowOf(listOf(profile))
    override fun observeActiveProfile(): Flow<ProviderProfile?> = flowOf(profile)
    override suspend fun list(): List<ProviderProfile> = listOf(profile)
    override suspend fun get(profileId: String): ProviderProfile? = profile
    override suspend fun getActive(): ProviderProfile? = profile
    override suspend fun upsert(profile: ProviderProfile): Result<Unit> = Result.success(Unit)
    override suspend fun delete(profileId: String): Result<Unit> = Result.success(Unit)
    override suspend fun setActive(profileId: String?): Result<Unit> = Result.success(Unit)
    override suspend fun deleteAll(): Result<Unit> = Result.success(Unit)
}

/**
 * Fake [ProviderProfileStore] that reports no active profile.
 */
class FakeNoActiveProfileStore : ProviderProfileStore {
    override fun observeProfiles(): Flow<List<ProviderProfile>> = flowOf(emptyList())
    override fun observeActiveProfile(): Flow<ProviderProfile?> = flowOf(null)
    override suspend fun list(): List<ProviderProfile> = emptyList()
    override suspend fun get(profileId: String): ProviderProfile? = null
    override suspend fun getActive(): ProviderProfile? = null
    override suspend fun upsert(profile: ProviderProfile): Result<Unit> = Result.success(Unit)
    override suspend fun delete(profileId: String): Result<Unit> = Result.success(Unit)
    override suspend fun setActive(profileId: String?): Result<Unit> = Result.success(Unit)
    override suspend fun deleteAll(): Result<Unit> = Result.success(Unit)
}
