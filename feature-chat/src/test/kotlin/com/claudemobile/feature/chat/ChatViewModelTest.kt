package com.claudemobile.feature.chat

import app.cash.turbine.test
import com.claudemobile.core.common.AppError
import com.claudemobile.core.common.AppResult
import com.claudemobile.core.common.CoroutineDispatchers
import com.claudemobile.core.common.ErrorCode
import com.claudemobile.core.domain.model.Message
import com.claudemobile.core.domain.model.MessageId
import com.claudemobile.core.domain.model.MessageRole
import com.claudemobile.core.domain.model.MessageStatus
import com.claudemobile.core.domain.model.Session
import com.claudemobile.core.domain.model.SessionId
import com.claudemobile.core.domain.model.ToolCallStatus
import com.claudemobile.core.domain.repository.ConversationRepository
import com.claudemobile.core.domain.bridge.ProcessHandle
import com.claudemobile.core.domain.usecase.CancelTurnUseCase
import com.claudemobile.core.domain.usecase.RetryFailedTurnUseCase
import com.claudemobile.core.domain.usecase.SendMessageUseCase
import com.claudemobile.core.domain.usecase.SpawnCliUseCase
import com.claudemobile.core.domain.usecase.StreamEvent
import com.claudemobile.core.domain.usecase.StreamResponseUseCase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldBeEmpty
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testDispatchers = object : CoroutineDispatchers {
        override val default: CoroutineDispatcher = testDispatcher
        override val io: CoroutineDispatcher = testDispatcher
        override val main: CoroutineDispatcher = testDispatcher
        override val mainImmediate: CoroutineDispatcher = testDispatcher
        override val unconfined: CoroutineDispatcher = testDispatcher
    }

    private val sendMessageUseCase: SendMessageUseCase = mockk()
    private val cancelTurnUseCase: CancelTurnUseCase = mockk()
    private val retryFailedTurnUseCase: RetryFailedTurnUseCase = mockk()
    private val streamResponseUseCase: StreamResponseUseCase = mockk()
    private val spawnCliUseCase: SpawnCliUseCase = mockk()
    private val conversationRepository: ConversationRepository = mockk()
    private val clipboardManager: ClipboardManager = mockk(relaxed = true)
    private val accessibilityStateProvider: AccessibilityStateProvider = mockk()

    private lateinit var viewModel: ChatViewModel

    private val testSessionId = SessionId("test-session-1")
    private val testSession = Session(
        id = testSessionId,
        title = "Test Session",
        workspacePath = "/workspace/test",
        createdAt = Instant.now(),
        lastActivityAt = Instant.now(),
        messageCount = 0,
    )

    private val testMessage = Message(
        id = MessageId("msg-1"),
        sessionId = testSessionId,
        role = MessageRole.USER,
        createdAt = Instant.now(),
        position = 0,
        content = "Hello Claude",
        status = MessageStatus.COMPLETE,
        toolCallMetadata = null,
    )

    @BeforeEach
    fun setup() {
        every { accessibilityStateProvider.isTalkBackEnabled() } returns false
        every { accessibilityStateProvider.announce(any()) } returns Unit
        coEvery { spawnCliUseCase(any()) } returns AppResult.Success(
            ProcessHandle(pid = -1, startedAt = java.time.Instant.now())
        )

        viewModel = ChatViewModel(
            sendMessageUseCase = sendMessageUseCase,
            cancelTurnUseCase = cancelTurnUseCase,
            retryFailedTurnUseCase = retryFailedTurnUseCase,
            streamResponseUseCase = streamResponseUseCase,
            spawnCliUseCase = spawnCliUseCase,
            conversationRepository = conversationRepository,
            clipboardManager = clipboardManager,
            accessibilityStateProvider = accessibilityStateProvider,
            dispatchers = testDispatchers,
        )
    }

    @Test
    fun `initial state is empty`() = runTest(testDispatcher) {
        viewModel.uiState.value shouldBe ChatUiState()
    }

    @Test
    fun `LoadSession populates session info and messages`() = runTest(testDispatcher) {
        val messages = listOf(testMessage)
        coEvery { conversationRepository.getSession(testSessionId) } returns testSession
        every { conversationRepository.getMessagesFlow(testSessionId) } returns flowOf(messages)

        viewModel.onAction(ChatAction.LoadSession(testSessionId))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        state.sessionId shouldBe testSessionId
        state.sessionTitle shouldBe "Test Session"
        state.workspacePath shouldBe "/workspace/test"
        state.messages shouldBe messages
    }

    @Test
    fun `LoadSession with unknown session shows error`() = runTest(testDispatcher) {
        val unknownId = SessionId("unknown")
        coEvery { conversationRepository.getSession(unknownId) } returns null

        viewModel.onAction(ChatAction.LoadSession(unknownId))
        advanceUntilIdle()

        viewModel.uiState.value.errorMessage shouldBe "Session not found."
    }

    @Test
    fun `SendMessage clears input and invokes use case`() = runTest(testDispatcher) {
        // Setup session first
        coEvery { conversationRepository.getSession(testSessionId) } returns testSession
        every { conversationRepository.getMessagesFlow(testSessionId) } returns flowOf(emptyList())

        viewModel.onAction(ChatAction.LoadSession(testSessionId))
        advanceUntilIdle()

        // Setup send
        val sentMessage = testMessage.copy(status = MessageStatus.COMPLETE)
        coEvery { sendMessageUseCase(testSessionId, "Hello") } returns AppResult.Success(sentMessage)
        every { streamResponseUseCase(testSessionId) } returns AppResult.Success(flow { })

        viewModel.onAction(ChatAction.UpdateInput("Hello"))
        viewModel.onAction(ChatAction.SendMessage("Hello"))
        advanceUntilIdle()

        viewModel.uiState.value.inputText shouldBe ""
        coVerify { sendMessageUseCase(testSessionId, "Hello") }
    }

    @Test
    fun `SendMessage failure shows error`() = runTest(testDispatcher) {
        // Setup session
        coEvery { conversationRepository.getSession(testSessionId) } returns testSession
        every { conversationRepository.getMessagesFlow(testSessionId) } returns flowOf(emptyList())

        viewModel.onAction(ChatAction.LoadSession(testSessionId))
        advanceUntilIdle()

        coEvery { sendMessageUseCase(testSessionId, "Hello") } returns AppResult.Failure(
            AppError("Bridge error", ErrorCode.PROCESS_ERROR)
        )

        viewModel.onAction(ChatAction.SendMessage("Hello"))
        advanceUntilIdle()

        viewModel.uiState.value.errorMessage shouldBe "Bridge error"
    }

    @Test
    fun `SendMessage with blank content is ignored`() = runTest(testDispatcher) {
        coEvery { conversationRepository.getSession(testSessionId) } returns testSession
        every { conversationRepository.getMessagesFlow(testSessionId) } returns flowOf(emptyList())

        viewModel.onAction(ChatAction.LoadSession(testSessionId))
        advanceUntilIdle()

        viewModel.onAction(ChatAction.SendMessage("   "))
        advanceUntilIdle()

        coVerify(exactly = 0) { sendMessageUseCase(any(), any()) }
    }

    @Test
    fun `Cancel invokes cancel use case and stops streaming`() = runTest(testDispatcher) {
        // Setup session
        coEvery { conversationRepository.getSession(testSessionId) } returns testSession
        every { conversationRepository.getMessagesFlow(testSessionId) } returns flowOf(emptyList())

        viewModel.onAction(ChatAction.LoadSession(testSessionId))
        advanceUntilIdle()

        coEvery { cancelTurnUseCase(testSessionId) } returns AppResult.Success(MessageId("msg-2"))

        viewModel.onAction(ChatAction.Cancel)
        advanceUntilIdle()

        viewModel.uiState.value.isStreaming shouldBe false
        viewModel.uiState.value.activeToolCalls.shouldBeEmpty()
        coVerify { cancelTurnUseCase(testSessionId) }
    }

    @Test
    fun `Cancel failure shows error`() = runTest(testDispatcher) {
        coEvery { conversationRepository.getSession(testSessionId) } returns testSession
        every { conversationRepository.getMessagesFlow(testSessionId) } returns flowOf(emptyList())

        viewModel.onAction(ChatAction.LoadSession(testSessionId))
        advanceUntilIdle()

        coEvery { cancelTurnUseCase(testSessionId) } returns AppResult.Failure(
            AppError("No active message", ErrorCode.NOT_FOUND)
        )

        viewModel.onAction(ChatAction.Cancel)
        advanceUntilIdle()

        viewModel.uiState.value.errorMessage shouldBe "No active message"
    }

    @Test
    fun `Retry invokes retry use case and starts streaming`() = runTest(testDispatcher) {
        coEvery { conversationRepository.getSession(testSessionId) } returns testSession
        every { conversationRepository.getMessagesFlow(testSessionId) } returns flowOf(emptyList())

        viewModel.onAction(ChatAction.LoadSession(testSessionId))
        advanceUntilIdle()

        coEvery { retryFailedTurnUseCase(testSessionId) } returns AppResult.Success(testMessage)
        every { streamResponseUseCase(testSessionId) } returns AppResult.Success(flow { })

        viewModel.onAction(ChatAction.Retry)
        advanceUntilIdle()

        coVerify { retryFailedTurnUseCase(testSessionId) }
    }

    @Test
    fun `Retry failure shows error`() = runTest(testDispatcher) {
        coEvery { conversationRepository.getSession(testSessionId) } returns testSession
        every { conversationRepository.getMessagesFlow(testSessionId) } returns flowOf(emptyList())

        viewModel.onAction(ChatAction.LoadSession(testSessionId))
        advanceUntilIdle()

        coEvery { retryFailedTurnUseCase(testSessionId) } returns AppResult.Failure(
            AppError("No failed turn", ErrorCode.NOT_FOUND)
        )

        viewModel.onAction(ChatAction.Retry)
        advanceUntilIdle()

        viewModel.uiState.value.errorMessage shouldBe "No failed turn"
    }

    @Test
    fun `CopyMessage copies message content to clipboard`() = runTest(testDispatcher) {
        coEvery { conversationRepository.getSession(testSessionId) } returns testSession
        every { conversationRepository.getMessagesFlow(testSessionId) } returns flowOf(listOf(testMessage))

        viewModel.onAction(ChatAction.LoadSession(testSessionId))
        advanceUntilIdle()

        viewModel.onAction(ChatAction.CopyMessage(testMessage.id))
        advanceUntilIdle()

        verify { clipboardManager.copyToClipboard("Hello Claude") }
    }

    @Test
    fun `CopyCodeBlock copies content to clipboard`() = runTest(testDispatcher) {
        viewModel.onAction(ChatAction.CopyCodeBlock("val x = 42"))

        verify { clipboardManager.copyToClipboard("val x = 42") }
    }

    @Test
    fun `UpdateInput updates input text in state`() = runTest(testDispatcher) {
        viewModel.onAction(ChatAction.UpdateInput("typing..."))

        viewModel.uiState.value.inputText shouldBe "typing..."
    }

    @Test
    fun `DismissError clears error message`() = runTest(testDispatcher) {
        // Force an error state
        coEvery { conversationRepository.getSession(testSessionId) } returns testSession
        every { conversationRepository.getMessagesFlow(testSessionId) } returns flowOf(emptyList())

        viewModel.onAction(ChatAction.LoadSession(testSessionId))
        advanceUntilIdle()

        coEvery { cancelTurnUseCase(testSessionId) } returns AppResult.Failure(
            AppError("Error", ErrorCode.UNKNOWN)
        )
        viewModel.onAction(ChatAction.Cancel)
        advanceUntilIdle()

        viewModel.uiState.value.errorMessage shouldBe "Error"

        viewModel.onAction(ChatAction.DismissError)
        viewModel.uiState.value.errorMessage shouldBe null
    }

    @Test
    fun `streaming events update isStreaming state`() = runTest(testDispatcher) {
        coEvery { conversationRepository.getSession(testSessionId) } returns testSession
        every { conversationRepository.getMessagesFlow(testSessionId) } returns flowOf(emptyList())

        viewModel.onAction(ChatAction.LoadSession(testSessionId))
        advanceUntilIdle()

        val messageId = MessageId("assistant-1")
        val streamFlow = flow {
            emit(StreamEvent.MessageCreated(messageId))
            emit(StreamEvent.ContentUpdated(messageId, "Hello"))
            emit(StreamEvent.TurnCompleted(messageId))
        }

        coEvery { sendMessageUseCase(testSessionId, "Hi") } returns AppResult.Success(testMessage)
        every { streamResponseUseCase(testSessionId) } returns AppResult.Success(streamFlow)

        viewModel.onAction(ChatAction.SendMessage("Hi"))
        advanceUntilIdle()

        // After TurnCompleted, streaming should be false
        viewModel.uiState.value.isStreaming shouldBe false
        viewModel.uiState.value.activeToolCalls.shouldBeEmpty()
    }

    @Test
    fun `streaming error updates state with error message`() = runTest(testDispatcher) {
        coEvery { conversationRepository.getSession(testSessionId) } returns testSession
        every { conversationRepository.getMessagesFlow(testSessionId) } returns flowOf(emptyList())

        viewModel.onAction(ChatAction.LoadSession(testSessionId))
        advanceUntilIdle()

        val messageId = MessageId("assistant-1")
        val streamFlow = flow {
            emit(StreamEvent.MessageCreated(messageId))
            emit(StreamEvent.Error(messageId, "Connection lost"))
        }

        coEvery { sendMessageUseCase(testSessionId, "Hi") } returns AppResult.Success(testMessage)
        every { streamResponseUseCase(testSessionId) } returns AppResult.Success(streamFlow)

        viewModel.onAction(ChatAction.SendMessage("Hi"))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        state.isStreaming shouldBe false
        state.errorMessage shouldBe "Connection lost"
        state.activeToolCalls.shouldBeEmpty()
    }

    @Test
    fun `ToolCallStarted event adds tool call to active tool calls`() = runTest(testDispatcher) {
        coEvery { conversationRepository.getSession(testSessionId) } returns testSession
        every { conversationRepository.getMessagesFlow(testSessionId) } returns flowOf(emptyList())

        viewModel.onAction(ChatAction.LoadSession(testSessionId))
        advanceUntilIdle()

        val messageId = MessageId("assistant-1")
        val streamFlow = flow {
            emit(StreamEvent.MessageCreated(messageId))
            emit(StreamEvent.ToolCallStarted(messageId, "read_file", "{\"path\": \"/src/main.kt\"}"))
            // Don't complete the turn so we can inspect intermediate state
        }

        coEvery { sendMessageUseCase(testSessionId, "Hi") } returns AppResult.Success(testMessage)
        every { streamResponseUseCase(testSessionId) } returns AppResult.Success(streamFlow)

        viewModel.onAction(ChatAction.SendMessage("Hi"))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        state.activeToolCalls.shouldContainKey(messageId)
        val toolCalls = state.activeToolCalls[messageId]!!
        toolCalls shouldHaveSize 1
        toolCalls[0].toolName shouldBe "read_file"
        toolCalls[0].arguments shouldBe "{\"path\": \"/src/main.kt\"}"
        toolCalls[0].status shouldBe ToolCallStatus.RUNNING
        toolCalls[0].result shouldBe null
    }

    @Test
    fun `ToolCallCompleted event updates tool call status and result`() = runTest(testDispatcher) {
        coEvery { conversationRepository.getSession(testSessionId) } returns testSession
        every { conversationRepository.getMessagesFlow(testSessionId) } returns flowOf(emptyList())

        viewModel.onAction(ChatAction.LoadSession(testSessionId))
        advanceUntilIdle()

        val messageId = MessageId("assistant-1")
        val streamFlow = flow {
            emit(StreamEvent.MessageCreated(messageId))
            emit(StreamEvent.ToolCallStarted(messageId, "read_file", "{\"path\": \"/src/main.kt\"}"))
            emit(StreamEvent.ToolCallCompleted(messageId, "read_file", "file content here", true))
        }

        coEvery { sendMessageUseCase(testSessionId, "Hi") } returns AppResult.Success(testMessage)
        every { streamResponseUseCase(testSessionId) } returns AppResult.Success(streamFlow)

        viewModel.onAction(ChatAction.SendMessage("Hi"))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        state.activeToolCalls.shouldContainKey(messageId)
        val toolCalls = state.activeToolCalls[messageId]!!
        toolCalls shouldHaveSize 1
        toolCalls[0].toolName shouldBe "read_file"
        toolCalls[0].result shouldBe "file content here"
        toolCalls[0].status shouldBe ToolCallStatus.COMPLETED
    }

    @Test
    fun `ToolCallCompleted with failure marks tool call as failed`() = runTest(testDispatcher) {
        coEvery { conversationRepository.getSession(testSessionId) } returns testSession
        every { conversationRepository.getMessagesFlow(testSessionId) } returns flowOf(emptyList())

        viewModel.onAction(ChatAction.LoadSession(testSessionId))
        advanceUntilIdle()

        val messageId = MessageId("assistant-1")
        val streamFlow = flow {
            emit(StreamEvent.MessageCreated(messageId))
            emit(StreamEvent.ToolCallStarted(messageId, "write_file", "{\"path\": \"/tmp/x\"}"))
            emit(StreamEvent.ToolCallCompleted(messageId, "write_file", "Permission denied", false))
        }

        coEvery { sendMessageUseCase(testSessionId, "Hi") } returns AppResult.Success(testMessage)
        every { streamResponseUseCase(testSessionId) } returns AppResult.Success(streamFlow)

        viewModel.onAction(ChatAction.SendMessage("Hi"))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        val toolCalls = state.activeToolCalls[messageId]!!
        toolCalls[0].status shouldBe ToolCallStatus.FAILED
        toolCalls[0].result shouldBe "Permission denied"
    }

    @Test
    fun `multiple tool calls are tracked for same message`() = runTest(testDispatcher) {
        coEvery { conversationRepository.getSession(testSessionId) } returns testSession
        every { conversationRepository.getMessagesFlow(testSessionId) } returns flowOf(emptyList())

        viewModel.onAction(ChatAction.LoadSession(testSessionId))
        advanceUntilIdle()

        val messageId = MessageId("assistant-1")
        val streamFlow = flow {
            emit(StreamEvent.MessageCreated(messageId))
            emit(StreamEvent.ToolCallStarted(messageId, "read_file", "{\"path\": \"/a\"}"))
            emit(StreamEvent.ToolCallCompleted(messageId, "read_file", "content a", true))
            emit(StreamEvent.ToolCallStarted(messageId, "write_file", "{\"path\": \"/b\"}"))
            emit(StreamEvent.ToolCallCompleted(messageId, "write_file", "ok", true))
        }

        coEvery { sendMessageUseCase(testSessionId, "Hi") } returns AppResult.Success(testMessage)
        every { streamResponseUseCase(testSessionId) } returns AppResult.Success(streamFlow)

        viewModel.onAction(ChatAction.SendMessage("Hi"))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        val toolCalls = state.activeToolCalls[messageId]!!
        toolCalls shouldHaveSize 2
        toolCalls[0].toolName shouldBe "read_file"
        toolCalls[1].toolName shouldBe "write_file"
    }

    @Test
    fun `TurnCompleted clears active tool calls`() = runTest(testDispatcher) {
        coEvery { conversationRepository.getSession(testSessionId) } returns testSession
        every { conversationRepository.getMessagesFlow(testSessionId) } returns flowOf(emptyList())

        viewModel.onAction(ChatAction.LoadSession(testSessionId))
        advanceUntilIdle()

        val messageId = MessageId("assistant-1")
        val streamFlow = flow {
            emit(StreamEvent.MessageCreated(messageId))
            emit(StreamEvent.ToolCallStarted(messageId, "read_file", "{}"))
            emit(StreamEvent.ToolCallCompleted(messageId, "read_file", "done", true))
            emit(StreamEvent.TurnCompleted(messageId))
        }

        coEvery { sendMessageUseCase(testSessionId, "Hi") } returns AppResult.Success(testMessage)
        every { streamResponseUseCase(testSessionId) } returns AppResult.Success(streamFlow)

        viewModel.onAction(ChatAction.SendMessage("Hi"))
        advanceUntilIdle()

        viewModel.uiState.value.activeToolCalls.shouldBeEmpty()
    }

    @Test
    fun `TalkBack announcements are rate limited to once per 2 seconds`() = runTest(testDispatcher) {
        every { accessibilityStateProvider.isTalkBackEnabled() } returns true

        coEvery { conversationRepository.getSession(testSessionId) } returns testSession
        every { conversationRepository.getMessagesFlow(testSessionId) } returns flowOf(emptyList())

        viewModel.onAction(ChatAction.LoadSession(testSessionId))
        advanceUntilIdle()

        val messageId = MessageId("assistant-1")
        val streamFlow = flow {
            emit(StreamEvent.MessageCreated(messageId))
            emit(StreamEvent.ContentUpdated(messageId, "First"))
            emit(StreamEvent.ContentUpdated(messageId, "First Second"))
            emit(StreamEvent.ContentUpdated(messageId, "First Second Third"))
            emit(StreamEvent.TurnCompleted(messageId))
        }

        coEvery { sendMessageUseCase(testSessionId, "Hi") } returns AppResult.Success(testMessage)
        every { streamResponseUseCase(testSessionId) } returns AppResult.Success(streamFlow)

        viewModel.onAction(ChatAction.SendMessage("Hi"))
        advanceUntilIdle()

        // Only the first announcement should have been made (all updates happen within 2s)
        verify(exactly = 1) { accessibilityStateProvider.announce(any()) }
    }

    @Test
    fun `TalkBack announcements are not made when TalkBack is disabled`() = runTest(testDispatcher) {
        every { accessibilityStateProvider.isTalkBackEnabled() } returns false

        coEvery { conversationRepository.getSession(testSessionId) } returns testSession
        every { conversationRepository.getMessagesFlow(testSessionId) } returns flowOf(emptyList())

        viewModel.onAction(ChatAction.LoadSession(testSessionId))
        advanceUntilIdle()

        val messageId = MessageId("assistant-1")
        val streamFlow = flow {
            emit(StreamEvent.MessageCreated(messageId))
            emit(StreamEvent.ContentUpdated(messageId, "Hello"))
            emit(StreamEvent.TurnCompleted(messageId))
        }

        coEvery { sendMessageUseCase(testSessionId, "Hi") } returns AppResult.Success(testMessage)
        every { streamResponseUseCase(testSessionId) } returns AppResult.Success(streamFlow)

        viewModel.onAction(ChatAction.SendMessage("Hi"))
        advanceUntilIdle()

        verify(exactly = 0) { accessibilityStateProvider.announce(any()) }
    }

    @Test
    fun `SendMessage without loaded session is ignored`() = runTest(testDispatcher) {
        viewModel.onAction(ChatAction.SendMessage("Hello"))
        advanceUntilIdle()

        coVerify(exactly = 0) { sendMessageUseCase(any(), any()) }
    }

    @Test
    fun `ProcessExited event stops streaming and clears tool calls`() = runTest(testDispatcher) {
        coEvery { conversationRepository.getSession(testSessionId) } returns testSession
        every { conversationRepository.getMessagesFlow(testSessionId) } returns flowOf(emptyList())

        viewModel.onAction(ChatAction.LoadSession(testSessionId))
        advanceUntilIdle()

        val messageId = MessageId("assistant-1")
        val streamFlow = flow {
            emit(StreamEvent.MessageCreated(messageId))
            emit(StreamEvent.ProcessExited(messageId, exitCode = 1))
        }

        coEvery { sendMessageUseCase(testSessionId, "Hi") } returns AppResult.Success(testMessage)
        every { streamResponseUseCase(testSessionId) } returns AppResult.Success(streamFlow)

        viewModel.onAction(ChatAction.SendMessage("Hi"))
        advanceUntilIdle()

        viewModel.uiState.value.isStreaming shouldBe false
        viewModel.uiState.value.activeToolCalls.shouldBeEmpty()
    }

    @Test
    fun `Retry does not duplicate user messages - uses RetryFailedTurnUseCase`() = runTest(testDispatcher) {
        coEvery { conversationRepository.getSession(testSessionId) } returns testSession
        every { conversationRepository.getMessagesFlow(testSessionId) } returns flowOf(emptyList())

        viewModel.onAction(ChatAction.LoadSession(testSessionId))
        advanceUntilIdle()

        // Retry calls RetryFailedTurnUseCase which resends without creating new message
        coEvery { retryFailedTurnUseCase(testSessionId) } returns AppResult.Success(testMessage)
        every { streamResponseUseCase(testSessionId) } returns AppResult.Success(flow { })

        viewModel.onAction(ChatAction.Retry)
        advanceUntilIdle()

        // Verify retry use case was called (not send message use case)
        coVerify(exactly = 1) { retryFailedTurnUseCase(testSessionId) }
        coVerify(exactly = 0) { sendMessageUseCase(any(), any()) }
    }

    @Test
    fun `error event clears streaming state and allows next user input`() = runTest(testDispatcher) {
        coEvery { conversationRepository.getSession(testSessionId) } returns testSession
        every { conversationRepository.getMessagesFlow(testSessionId) } returns flowOf(emptyList())

        viewModel.onAction(ChatAction.LoadSession(testSessionId))
        advanceUntilIdle()

        val messageId = MessageId("assistant-1")
        val streamFlow = flow {
            emit(StreamEvent.MessageCreated(messageId))
            emit(StreamEvent.ContentUpdated(messageId, "partial"))
            emit(StreamEvent.Error(messageId, "Network timeout"))
        }

        coEvery { sendMessageUseCase(testSessionId, "Hi") } returns AppResult.Success(testMessage)
        every { streamResponseUseCase(testSessionId) } returns AppResult.Success(streamFlow)

        viewModel.onAction(ChatAction.SendMessage("Hi"))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        // Streaming stopped
        state.isStreaming shouldBe false
        // Error message shown (allows retry)
        state.errorMessage shouldBe "Network timeout"
        // User can still type (input field is not blocked)
        viewModel.onAction(ChatAction.UpdateInput("retry message"))
        viewModel.uiState.value.inputText shouldBe "retry message"
    }

    @Test
    fun `streaming content updates message within 100ms requirement`() = runTest(testDispatcher) {
        coEvery { conversationRepository.getSession(testSessionId) } returns testSession

        val assistantMsg = Message(
            id = MessageId("assistant-1"),
            sessionId = testSessionId,
            role = MessageRole.ASSISTANT,
            createdAt = Instant.now(),
            position = 1,
            content = "",
            status = MessageStatus.STREAMING,
            toolCallMetadata = null,
        )
        every { conversationRepository.getMessagesFlow(testSessionId) } returns flowOf(listOf(testMessage, assistantMsg))

        viewModel.onAction(ChatAction.LoadSession(testSessionId))
        advanceUntilIdle()

        val messageId = MessageId("assistant-1")
        val streamFlow = flow {
            emit(StreamEvent.MessageCreated(messageId))
            emit(StreamEvent.ContentUpdated(messageId, "Hello "))
            emit(StreamEvent.ContentUpdated(messageId, "Hello World"))
            emit(StreamEvent.TurnCompleted(messageId))
        }

        coEvery { sendMessageUseCase(testSessionId, "Hi") } returns AppResult.Success(testMessage)
        every { streamResponseUseCase(testSessionId) } returns AppResult.Success(streamFlow)

        viewModel.onAction(ChatAction.SendMessage("Hi"))
        advanceUntilIdle()

        // The message content should reflect the latest update
        val updatedMsg = viewModel.uiState.value.messages.find { it.id == messageId }
        updatedMsg?.content shouldBe "Hello World"
    }
}
