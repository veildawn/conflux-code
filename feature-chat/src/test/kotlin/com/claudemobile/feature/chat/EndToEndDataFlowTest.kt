package com.claudemobile.feature.chat

import com.claudemobile.core.common.CoroutineDispatchers
import com.claudemobile.core.common.TimeProvider
import com.claudemobile.core.common.UuidGenerator
import com.claudemobile.core.domain.bridge.BridgeEvent
import com.claudemobile.core.domain.bridge.CliBridge
import com.claudemobile.core.domain.bridge.ExitCause
import com.claudemobile.core.domain.bridge.PosixSignal
import com.claudemobile.core.domain.bridge.ProcessHandle
import com.claudemobile.core.domain.bridge.ProcessState
import com.claudemobile.core.domain.bridge.SpawnConfig
import com.claudemobile.core.domain.model.Message
import com.claudemobile.core.domain.model.MessageId
import com.claudemobile.core.domain.model.MessageRole
import com.claudemobile.core.domain.model.MessageStatus
import com.claudemobile.core.domain.model.Session
import com.claudemobile.core.domain.model.SessionId
import com.claudemobile.core.domain.parser.OutputParser
import com.claudemobile.core.domain.model.OutputEvent
import com.claudemobile.core.domain.model.ParseResult
import com.claudemobile.core.domain.repository.ConversationRepository
import com.claudemobile.core.domain.usecase.CancelTurnUseCase
import com.claudemobile.core.domain.usecase.RetryFailedTurnUseCase
import com.claudemobile.core.domain.usecase.SendMessageUseCase
import com.claudemobile.core.domain.usecase.SpawnCliUseCase
import com.claudemobile.core.domain.usecase.StreamResponseUseCase
import io.mockk.mockk
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end data flow integration tests that verify the complete wiring:
 *
 * - ChatViewModel → SendMessageUseCase → ConversationRepository + CliBridge
 * - CliBridge.outputFlow → StreamResponseUseCase → OutputParser → ChatViewModel
 * - CancelTurnUseCase → CliBridge.sendSignal
 * - Streaming messages persisted every 2 seconds
 * - Process exit events propagate to UI
 *
 * Validates Requirements: 2.8, 2.9, 3.1, 5.4, 5.5
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EndToEndDataFlowTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testDispatchers = object : CoroutineDispatchers {
        override val default: CoroutineDispatcher = testDispatcher
        override val io: CoroutineDispatcher = testDispatcher
        override val main: CoroutineDispatcher = testDispatcher
        override val mainImmediate: CoroutineDispatcher = testDispatcher
        override val unconfined: CoroutineDispatcher = testDispatcher
    }

    private lateinit var fakeCliBridge: FakeCliBridge
    private lateinit var fakeRepository: FakeConversationRepository
    private lateinit var fakeOutputParser: FakeOutputParser
    private lateinit var fakeTimeProvider: FakeTimeProvider
    private lateinit var fakeUuidGenerator: FakeUuidGenerator

    private lateinit var sendMessageUseCase: SendMessageUseCase
    private lateinit var streamResponseUseCase: StreamResponseUseCase
    private lateinit var cancelTurnUseCase: CancelTurnUseCase
    private lateinit var retryFailedTurnUseCase: RetryFailedTurnUseCase
    private val spawnCliUseCase: SpawnCliUseCase = mockk(relaxed = true)

    private lateinit var viewModel: ChatViewModel

    private val testSessionId = SessionId("session-1")
    private val testSession = Session(
        id = testSessionId,
        title = "Test Session",
        workspacePath = "/workspace",
        createdAt = Instant.ofEpochMilli(1000),
        lastActivityAt = Instant.ofEpochMilli(1000),
        messageCount = 0,
    )

    @BeforeEach
    fun setup() {
        fakeCliBridge = FakeCliBridge()
        fakeRepository = FakeConversationRepository()
        fakeOutputParser = FakeOutputParser()
        fakeTimeProvider = FakeTimeProvider()
        fakeUuidGenerator = FakeUuidGenerator()

        sendMessageUseCase = SendMessageUseCase(
            conversationRepository = fakeRepository,
            cliBridge = fakeCliBridge,
            timeProvider = fakeTimeProvider,
            uuidGenerator = fakeUuidGenerator,
        )

        streamResponseUseCase = StreamResponseUseCase(
            conversationRepository = fakeRepository,
            cliBridge = fakeCliBridge,
            outputParser = fakeOutputParser,
            timeProvider = fakeTimeProvider,
            uuidGenerator = fakeUuidGenerator,
        )

        cancelTurnUseCase = CancelTurnUseCase(
            conversationRepository = fakeRepository,
            cliBridge = fakeCliBridge,
        )

        retryFailedTurnUseCase = RetryFailedTurnUseCase(
            conversationRepository = fakeRepository,
            cliBridge = fakeCliBridge,
        )

        val clipboardManager = object : ClipboardManager {
            override fun copyToClipboard(text: String) {}
        }

        val accessibilityStateProvider = object : AccessibilityStateProvider {
            override fun isTalkBackEnabled(): Boolean = false
            override fun announce(text: String) {}
        }

        viewModel = ChatViewModel(
            sendMessageUseCase = sendMessageUseCase,
            cancelTurnUseCase = cancelTurnUseCase,
            retryFailedTurnUseCase = retryFailedTurnUseCase,
            streamResponseUseCase = streamResponseUseCase,
            spawnCliUseCase = spawnCliUseCase,
            conversationRepository = fakeRepository,
            clipboardManager = clipboardManager,
            accessibilityStateProvider = accessibilityStateProvider,
            dispatchers = testDispatchers,
        )

        // Pre-populate session
        fakeRepository.sessions[testSessionId] = testSession
    }

    @Nested
    @DisplayName("ChatViewModel → SendMessageUseCase → ConversationRepository + CliBridge")
    inner class SendMessageFlow {

        @Test
        fun `sending a message persists it then forwards to bridge`() = runTest(testDispatcher) {
            // Load session
            viewModel.onAction(ChatAction.LoadSession(testSessionId))
            advanceUntilIdle()

            // Send message
            viewModel.onAction(ChatAction.SendMessage("Hello Claude"))
            advanceUntilIdle()

            // Verify user message was persisted in repository
            val messages = fakeRepository.getMessages(testSessionId)
            val userMessages = messages.filter { it.role == MessageRole.USER }
            userMessages.size shouldBe 1
            userMessages[0].content shouldBe "Hello Claude"
            userMessages[0].status shouldBe MessageStatus.COMPLETE

            // Verify message was forwarded to bridge
            fakeCliBridge.writtenBytes.size shouldBe 1
            String(fakeCliBridge.writtenBytes[0]) shouldBe "Hello Claude\n"
        }

        @Test
        fun `message is persisted before being forwarded to bridge`() = runTest(testDispatcher) {
            // Track ordering of user message persist and bridge write only
            val operations = mutableListOf<String>()
            fakeRepository.onInsertMessage = { msg ->
                if (msg.role == MessageRole.USER) {
                    operations.add("persist")
                }
            }
            fakeCliBridge.onWrite = { operations.add("bridge_write") }

            viewModel.onAction(ChatAction.LoadSession(testSessionId))
            advanceUntilIdle()

            viewModel.onAction(ChatAction.SendMessage("Test"))
            advanceUntilIdle()

            // Persist must happen before bridge write (Requirement 5.3)
            // Filter to only the first two operations (persist + bridge_write)
            val relevantOps = operations.filter { it == "persist" || it == "bridge_write" }
            relevantOps.size shouldBe 2
            relevantOps[0] shouldBe "persist"
            relevantOps[1] shouldBe "bridge_write"
        }
    }

    @Nested
    @DisplayName("CliBridge.outputFlow → StreamResponseUseCase → OutputParser → ChatViewModel")
    inner class StreamResponseFlow {

        @Test
        fun `bridge output is parsed and updates UI state`() = runTest(testDispatcher) {
            // Configure parser to emit text events
            fakeOutputParser.parseResults = { bytes ->
                val text = String(bytes)
                ParseResult(
                    events = listOf(OutputEvent.Text(text, emptyList())),
                    remainingBuffer = byteArrayOf(),
                )
            }

            // Pre-schedule output emission so it arrives after streaming starts
            fakeCliBridge.scheduledOutputs.add("Hello ".toByteArray())

            viewModel.onAction(ChatAction.LoadSession(testSessionId))
            advanceUntilIdle()

            viewModel.onAction(ChatAction.SendMessage("Hi"))
            advanceUntilIdle()

            // Verify the streaming flow was started and an assistant message was created
            val state = viewModel.uiState.value
            state.isStreaming shouldBe true

            // Verify the assistant message exists in the repository (created by StreamResponseUseCase)
            val messages = fakeRepository.getMessages(testSessionId)
            val assistantMessages = messages.filter { it.role == MessageRole.ASSISTANT }
            assistantMessages.size shouldBe 1
            // Content is updated via ContentUpdated event in ViewModel local state
            // The repository content may not be persisted yet (2s interval)
            // but the message was created with STREAMING status
            assistantMessages[0].status shouldBe MessageStatus.STREAMING
        }

        @Test
        fun `turn complete event stops streaming and marks message complete`() = runTest(testDispatcher) {
            fakeOutputParser.parseResults = { bytes ->
                val text = String(bytes)
                if (text.contains("[turn_complete]")) {
                    ParseResult(
                        events = listOf(OutputEvent.TurnComplete),
                        remainingBuffer = byteArrayOf(),
                    )
                } else {
                    ParseResult(
                        events = listOf(OutputEvent.Text(text, emptyList())),
                        remainingBuffer = byteArrayOf(),
                    )
                }
            }

            // Schedule outputs that will be emitted once streaming starts
            fakeCliBridge.scheduledOutputs.add("Response text".toByteArray())
            fakeCliBridge.scheduledOutputs.add("[turn_complete]".toByteArray())

            viewModel.onAction(ChatAction.LoadSession(testSessionId))
            advanceUntilIdle()

            viewModel.onAction(ChatAction.SendMessage("Hi"))
            advanceUntilIdle()

            // Streaming should be stopped
            viewModel.uiState.value.isStreaming shouldBe false

            // Message should be marked complete in repository
            val messages = fakeRepository.getMessages(testSessionId)
            val assistantMsg = messages.find { it.role == MessageRole.ASSISTANT }
            assistantMsg?.status shouldBe MessageStatus.COMPLETE
        }
    }

    @Nested
    @DisplayName("CancelTurnUseCase → CliBridge.sendSignal")
    inner class CancelFlow {

        @Test
        fun `cancel sends SIGINT to bridge`() = runTest(testDispatcher) {
            fakeOutputParser.parseResults = { bytes ->
                ParseResult(
                    events = listOf(OutputEvent.Text(String(bytes), emptyList())),
                    remainingBuffer = byteArrayOf(),
                )
            }

            // Schedule output so streaming starts and creates an assistant message
            fakeCliBridge.scheduledOutputs.add("partial response".toByteArray())

            viewModel.onAction(ChatAction.LoadSession(testSessionId))
            advanceUntilIdle()

            viewModel.onAction(ChatAction.SendMessage("Hi"))
            advanceUntilIdle()

            // Cancel the turn
            viewModel.onAction(ChatAction.Cancel)
            advanceUntilIdle()

            // Verify SIGINT was sent to bridge
            fakeCliBridge.signalsSent.size shouldBe 1
            fakeCliBridge.signalsSent[0] shouldBe PosixSignal.SIGINT

            // Verify streaming stopped
            viewModel.uiState.value.isStreaming shouldBe false

            // Verify assistant message marked as cancelled
            val messages = fakeRepository.getMessages(testSessionId)
            val assistantMsg = messages.find { it.role == MessageRole.ASSISTANT }
            assistantMsg?.status shouldBe MessageStatus.CANCELLED
        }
    }

    @Nested
    @DisplayName("Incremental persistence every 2 seconds")
    inner class IncrementalPersistence {

        @Test
        fun `streaming content is persisted at least every 2 seconds`() = runTest(testDispatcher) {
            var persistCount = 0
            fakeRepository.onUpdateContent = { persistCount++ }

            fakeOutputParser.parseResults = { bytes ->
                ParseResult(
                    events = listOf(OutputEvent.Text(String(bytes), emptyList())),
                    remainingBuffer = byteArrayOf(),
                )
            }

            // Schedule outputs with time progression:
            // chunk1 at t=0, chunk2 at t=1000, chunk3 at t=2000 (triggers persist)
            // We use the FakeTimeProvider to control time
            fakeTimeProvider.currentTimeMs = 0L

            // Schedule all outputs to be emitted sequentially
            fakeCliBridge.scheduledOutputs.add("chunk1 ".toByteArray())
            fakeCliBridge.scheduledOutputs.add("chunk2 ".toByteArray())
            // The third chunk will be emitted dynamically after time advances

            viewModel.onAction(ChatAction.LoadSession(testSessionId))
            advanceUntilIdle()

            viewModel.onAction(ChatAction.SendMessage("Hi"))
            advanceUntilIdle()

            // At this point, chunks 1 and 2 were processed at t=0
            // The first persist happens immediately for the first chunk (t=0 - 0 >= 2000 is false)
            // So no persist yet from the time-based logic
            val persistCountBefore = persistCount

            // Now advance time past 2 seconds and emit another chunk
            fakeTimeProvider.currentTimeMs = 2500L
            fakeCliBridge.emitOutput("chunk3 ".toByteArray())
            advanceUntilIdle()

            // At least one persist should have happened after 2 seconds
            (persistCount - persistCountBefore) shouldBe 1
        }
    }

    @Nested
    @DisplayName("Process exit events propagate to UI")
    inner class ProcessExitPropagation {

        @Test
        fun `process exit event stops streaming and updates UI`() = runTest(testDispatcher) {
            fakeOutputParser.parseResults = { bytes ->
                ParseResult(
                    events = listOf(OutputEvent.Text(String(bytes), emptyList())),
                    remainingBuffer = byteArrayOf(),
                )
            }

            // Schedule output then process exit
            fakeCliBridge.scheduledOutputs.add("partial".toByteArray())

            viewModel.onAction(ChatAction.LoadSession(testSessionId))
            advanceUntilIdle()

            viewModel.onAction(ChatAction.SendMessage("Hi"))
            advanceUntilIdle()

            // Streaming should have started
            viewModel.uiState.value.isStreaming shouldBe true

            // Emit process exit event dynamically
            fakeCliBridge.emitProcessExited(exitCode = 1, cause = ExitCause.CRASH)
            advanceUntilIdle()

            // UI should reflect process exit
            viewModel.uiState.value.isStreaming shouldBe false
            viewModel.uiState.value.activeToolCalls shouldBe emptyMap()
        }

        @Test
        fun `normal process exit marks message as complete`() = runTest(testDispatcher) {
            fakeOutputParser.parseResults = { bytes ->
                ParseResult(
                    events = listOf(OutputEvent.Text(String(bytes), emptyList())),
                    remainingBuffer = byteArrayOf(),
                )
            }

            // Schedule output then normal exit
            fakeCliBridge.scheduledOutputs.add("response".toByteArray())

            viewModel.onAction(ChatAction.LoadSession(testSessionId))
            advanceUntilIdle()

            viewModel.onAction(ChatAction.SendMessage("Hi"))
            advanceUntilIdle()

            // Normal exit (code 0)
            fakeCliBridge.emitProcessExited(exitCode = 0, cause = ExitCause.NORMAL)
            advanceUntilIdle()

            // Message should be marked complete
            val messages = fakeRepository.getMessages(testSessionId)
            val assistantMsg = messages.find { it.role == MessageRole.ASSISTANT }
            assistantMsg?.status shouldBe MessageStatus.COMPLETE
        }

        @Test
        fun `abnormal process exit marks message as error`() = runTest(testDispatcher) {
            fakeOutputParser.parseResults = { bytes ->
                ParseResult(
                    events = listOf(OutputEvent.Text(String(bytes), emptyList())),
                    remainingBuffer = byteArrayOf(),
                )
            }

            // Schedule output then abnormal exit
            fakeCliBridge.scheduledOutputs.add("partial".toByteArray())

            viewModel.onAction(ChatAction.LoadSession(testSessionId))
            advanceUntilIdle()

            viewModel.onAction(ChatAction.SendMessage("Hi"))
            advanceUntilIdle()

            // Abnormal exit (code 137 = killed by OS)
            fakeCliBridge.emitProcessExited(exitCode = 137, cause = ExitCause.KILLED_BY_OS)
            advanceUntilIdle()

            // Message should be marked as error
            val messages = fakeRepository.getMessages(testSessionId)
            val assistantMsg = messages.find { it.role == MessageRole.ASSISTANT }
            assistantMsg?.status shouldBe MessageStatus.ERROR
        }
    }

    // ===== Fake Implementations =====

    private class FakeCliBridge : CliBridge {
        private val _outputFlow = MutableSharedFlow<BridgeEvent>(extraBufferCapacity = 64)
        override val outputFlow: Flow<BridgeEvent> get() = kotlinx.coroutines.flow.flow {
            // Emit any scheduled outputs first
            for (bytes in scheduledOutputs) {
                emit(BridgeEvent.Output(bytes))
            }
            // Then collect from the shared flow for dynamic emissions
            _outputFlow.collect { emit(it) }
        }

        private val _processState = MutableStateFlow(ProcessState.RUNNING)
        override val processState: StateFlow<ProcessState> = _processState

        val writtenBytes = mutableListOf<ByteArray>()
        val signalsSent = mutableListOf<PosixSignal>()
        var onWrite: ((ByteArray) -> Unit)? = null
        val scheduledOutputs = mutableListOf<ByteArray>()

        override suspend fun spawn(config: SpawnConfig): Result<ProcessHandle> {
            return Result.success(ProcessHandle(pid = 1234, startedAt = Instant.now()))
        }

        override suspend fun write(bytes: ByteArray) {
            writtenBytes.add(bytes)
            onWrite?.invoke(bytes)
        }

        override suspend fun sendSignal(signal: PosixSignal) {
            signalsSent.add(signal)
        }

        override suspend fun terminate() {
            _processState.value = ProcessState.TERMINATED
        }

        suspend fun emitOutput(bytes: ByteArray) {
            _outputFlow.emit(BridgeEvent.Output(bytes))
        }

        suspend fun emitProcessExited(exitCode: Int, cause: ExitCause) {
            _outputFlow.emit(BridgeEvent.ProcessExited(exitCode, cause))
        }
    }

    private class FakeConversationRepository : ConversationRepository {
        val sessions = mutableMapOf<SessionId, Session>()
        private val messages = mutableMapOf<SessionId, MutableList<Message>>()
        private val messageFlows = mutableMapOf<SessionId, MutableStateFlow<List<Message>>>()

        var onInsertMessage: ((Message) -> Unit)? = null
        var onUpdateContent: ((String) -> Unit)? = null

        override fun getSessions(): Flow<List<Session>> = flowOf(sessions.values.toList())

        override suspend fun getSession(id: SessionId): Session? = sessions[id]

        override suspend fun getMessages(sessionId: SessionId): List<Message> {
            return messages[sessionId]?.sortedBy { it.position } ?: emptyList()
        }

        override fun getMessagesFlow(sessionId: SessionId): Flow<List<Message>> {
            return messageFlows.getOrPut(sessionId) {
                MutableStateFlow(messages[sessionId]?.sortedBy { it.position } ?: emptyList())
            }
        }

        private fun notifyMessageFlow(sessionId: SessionId) {
            messageFlows[sessionId]?.value = messages[sessionId]?.sortedBy { it.position } ?: emptyList()
        }

        override suspend fun createSession(title: String, workspacePath: String): Session {
            val session = Session(
                id = SessionId("new-session"),
                title = title,
                workspacePath = workspacePath,
                createdAt = Instant.now(),
                lastActivityAt = Instant.now(),
                messageCount = 0,
            )
            sessions[session.id] = session
            return session
        }

        override suspend fun updateSessionTitle(id: SessionId, title: String) {
            sessions[id] = sessions[id]?.copy(title = title) ?: return
        }

        override suspend fun deleteSession(id: SessionId) {
            sessions.remove(id)
            messages.remove(id)
        }

        override suspend fun insertMessage(message: Message): Message {
            val list = messages.getOrPut(message.sessionId) { mutableListOf() }
            list.add(message)
            onInsertMessage?.invoke(message)
            notifyMessageFlow(message.sessionId)
            return message
        }

        override suspend fun updateMessageContent(id: MessageId, content: String) {
            for ((sessionId, msgList) in messages) {
                val idx = msgList.indexOfFirst { it.id == id }
                if (idx >= 0) {
                    msgList[idx] = msgList[idx].copy(content = content)
                    onUpdateContent?.invoke(content)
                    notifyMessageFlow(sessionId)
                    return
                }
            }
        }

        override suspend fun updateMessageStatus(id: MessageId, status: MessageStatus) {
            for ((sessionId, msgList) in messages) {
                val idx = msgList.indexOfFirst { it.id == id }
                if (idx >= 0) {
                    msgList[idx] = msgList[idx].copy(status = status)
                    notifyMessageFlow(sessionId)
                    return
                }
            }
        }
    }

    private class FakeOutputParser : OutputParser {
        var parseResults: ((ByteArray) -> ParseResult)? = null

        override fun parse(buffer: ByteArray): ParseResult {
            return parseResults?.invoke(buffer) ?: ParseResult(
                events = emptyList(),
                remainingBuffer = buffer,
            )
        }

        override fun reset() {}
    }

    private class FakeTimeProvider : TimeProvider {
        var currentTimeMs: Long = 0L

        override fun now(): Instant = Instant.ofEpochMilli(currentTimeMs)
    }

    private class FakeUuidGenerator : UuidGenerator {
        private val counter = AtomicInteger(0)
        override fun generate(): String = "uuid-${counter.incrementAndGet()}"
    }
}
