package com.claudemobile.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claudemobile.core.common.AppResult
import com.claudemobile.core.common.CoroutineDispatchers
import com.claudemobile.core.common.getOrNull
import com.claudemobile.core.domain.model.Message
import com.claudemobile.core.domain.model.MessageId
import com.claudemobile.core.domain.model.MessageStatus
import com.claudemobile.core.domain.model.SessionId
import com.claudemobile.core.domain.model.ToolCallMetadata
import com.claudemobile.core.domain.model.ToolCallStatus
import com.claudemobile.core.domain.repository.ConversationRepository
import com.claudemobile.core.domain.usecase.CancelTurnUseCase
import com.claudemobile.core.domain.usecase.RetryFailedTurnUseCase
import com.claudemobile.core.domain.usecase.SendMessageUseCase
import com.claudemobile.core.domain.usecase.SpawnCliUseCase
import com.claudemobile.core.domain.usecase.StreamEvent
import com.claudemobile.core.domain.usecase.StreamResponseUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the chat screen, orchestrating message sending, streaming,
 * cancellation, and retry logic following unidirectional data flow.
 *
 * Exposes [uiState] as a StateFlow and accepts [ChatAction] values via [onAction].
 *
 * Coordinates:
 * - [SendMessageUseCase]: persists user message then forwards to bridge
 * - [StreamResponseUseCase]: collects bridge output, parses events, updates messages
 * - [CancelTurnUseCase]: sends SIGINT to cancel in-flight turn
 * - [RetryFailedTurnUseCase]: resends last user message without duplication
 */
@HiltViewModel
public class ChatViewModel @Inject constructor(
    private val sendMessageUseCase: SendMessageUseCase,
    private val cancelTurnUseCase: CancelTurnUseCase,
    private val retryFailedTurnUseCase: RetryFailedTurnUseCase,
    private val streamResponseUseCase: StreamResponseUseCase,
    private val spawnCliUseCase: SpawnCliUseCase,
    private val conversationRepository: ConversationRepository,
    private val clipboardManager: ClipboardManager,
    private val accessibilityStateProvider: AccessibilityStateProvider,
    private val dispatchers: CoroutineDispatchers,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())

    /**
     * The current UI state for the chat screen.
     */
    public val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var streamingJob: Job? = null
    private var messagesCollectionJob: Job? = null

    /**
     * Timestamp of the last TalkBack announcement, used for rate limiting.
     * Announcements are limited to once every 2 seconds when TalkBack is enabled.
     */
    private var lastAnnouncementTimeMs: Long = 0L

    /**
     * Dispatches a [ChatAction] to update the UI state.
     */
    public fun onAction(action: ChatAction) {
        when (action) {
            is ChatAction.LoadSession -> loadSession(action.sessionId)
            is ChatAction.SendMessage -> sendMessage(action.content)
            is ChatAction.Cancel -> cancelTurn()
            is ChatAction.Retry -> retryTurn()
            is ChatAction.CopyMessage -> copyMessage(action.messageId)
            is ChatAction.CopyCodeBlock -> copyCodeBlock(action.content)
            is ChatAction.UpdateInput -> updateInput(action.text)
            is ChatAction.DismissError -> dismissError()
        }
    }

    private fun loadSession(sessionId: SessionId) {
        // Cancel any existing collection
        messagesCollectionJob?.cancel()
        streamingJob?.cancel()

        viewModelScope.launch(dispatchers.io) {
            val session = conversationRepository.getSession(sessionId)
            if (session == null) {
                _uiState.update { it.copy(errorMessage = "Session not found.") }
                return@launch
            }

            _uiState.update {
                it.copy(
                    sessionId = sessionId,
                    sessionTitle = session.title,
                    workspacePath = session.workspacePath,
                    errorMessage = null,
                )
            }

            // Spawn the Claude CLI process for this session's workspace.
            // This is idempotent — if already running, it's a no-op.
            val spawnResult = spawnCliUseCase(session.workspacePath)
            if (spawnResult is AppResult.Failure) {
                _uiState.update {
                    it.copy(errorMessage = spawnResult.error.message)
                }
            }

            // Observe messages reactively
            messagesCollectionJob = viewModelScope.launch(dispatchers.io) {
                conversationRepository.getMessagesFlow(sessionId).collect { messages ->
                    val isStreaming = messages.any { it.status == MessageStatus.STREAMING }
                    _uiState.update { state ->
                        state.copy(
                            messages = messages,
                            isStreaming = isStreaming,
                        )
                    }
                }
            }
        }
    }

    private fun sendMessage(content: String) {
        val sessionId = _uiState.value.sessionId ?: return
        if (content.isBlank()) return

        // Clear input immediately for responsive UX
        _uiState.update { it.copy(inputText = "", errorMessage = null) }

        viewModelScope.launch(dispatchers.io) {
            when (val result = sendMessageUseCase(sessionId, content)) {
                is AppResult.Success -> {
                    startStreaming(sessionId)
                }
                is AppResult.Failure -> {
                    _uiState.update {
                        it.copy(errorMessage = result.error.message)
                    }
                }
            }
        }
    }

    private fun cancelTurn() {
        val sessionId = _uiState.value.sessionId ?: return

        viewModelScope.launch(dispatchers.io) {
            when (val result = cancelTurnUseCase(sessionId)) {
                is AppResult.Success -> {
                    streamingJob?.cancel()
                    streamingJob = null
                    _uiState.update {
                        it.copy(
                            isStreaming = false,
                            activeToolCalls = emptyMap(),
                        )
                    }
                }
                is AppResult.Failure -> {
                    _uiState.update {
                        it.copy(errorMessage = result.error.message)
                    }
                }
            }
        }
    }

    private fun retryTurn() {
        val sessionId = _uiState.value.sessionId ?: return

        _uiState.update { it.copy(errorMessage = null) }

        viewModelScope.launch(dispatchers.io) {
            when (val result = retryFailedTurnUseCase(sessionId)) {
                is AppResult.Success -> {
                    startStreaming(sessionId)
                }
                is AppResult.Failure -> {
                    _uiState.update {
                        it.copy(errorMessage = result.error.message)
                    }
                }
            }
        }
    }

    private fun startStreaming(sessionId: SessionId) {
        // Cancel any existing streaming job
        streamingJob?.cancel()

        val flowResult = streamResponseUseCase(sessionId)
        val streamFlow = flowResult.getOrNull() ?: return

        _uiState.update {
            it.copy(
                isStreaming = true,
                activeToolCalls = emptyMap(),
            )
        }

        streamingJob = viewModelScope.launch(dispatchers.io) {
            streamFlow.collect { event ->
                handleStreamEvent(event)
            }
        }
    }

    private fun handleStreamEvent(event: StreamEvent) {
        when (event) {
            is StreamEvent.MessageCreated -> {
                // Message will appear via the messagesFlow collection
            }

            is StreamEvent.ContentUpdated -> {
                // Update the message content in the local state for immediate UI feedback
                // This ensures text chunks are appended within 100ms (Requirement 3.1)
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages.map { message ->
                            if (message.id == event.messageId) {
                                message.copy(content = event.content)
                            } else {
                                message
                            }
                        }
                    )
                }
                maybeAnnounceForAccessibility(event.content)
            }

            is StreamEvent.TurnCompleted -> {
                _uiState.update {
                    it.copy(
                        isStreaming = false,
                        activeToolCalls = emptyMap(),
                    )
                }
                streamingJob = null
            }

            is StreamEvent.Error -> {
                // Requirement 3.8: append a system message describing the error
                // and leave conversation in a state that permits the next user turn
                _uiState.update {
                    it.copy(
                        isStreaming = false,
                        errorMessage = event.reason,
                        activeToolCalls = emptyMap(),
                    )
                }
                streamingJob = null
            }

            is StreamEvent.ToolCallStarted -> {
                // Requirement 3.3: render tool-call as structured block
                _uiState.update { state ->
                    val currentCalls = state.activeToolCalls[event.messageId].orEmpty()
                    val newToolCall = ToolCallMetadata(
                        toolName = event.toolName,
                        arguments = event.arguments,
                        result = null,
                        status = ToolCallStatus.RUNNING,
                    )
                    state.copy(
                        activeToolCalls = state.activeToolCalls + (event.messageId to (currentCalls + newToolCall))
                    )
                }
            }

            is StreamEvent.ToolCallCompleted -> {
                // Update the tool call status and result
                _uiState.update { state ->
                    val currentCalls = state.activeToolCalls[event.messageId].orEmpty()
                    val updatedCalls = currentCalls.map { toolCall ->
                        if (toolCall.toolName == event.toolName && toolCall.result == null) {
                            toolCall.copy(
                                result = event.result,
                                status = if (event.success) ToolCallStatus.COMPLETED else ToolCallStatus.FAILED,
                            )
                        } else {
                            toolCall
                        }
                    }
                    state.copy(
                        activeToolCalls = state.activeToolCalls + (event.messageId to updatedCalls)
                    )
                }
            }

            is StreamEvent.PromptReceived -> {
                // Prompt events are reflected via message content updates
            }

            is StreamEvent.ProcessExited -> {
                _uiState.update {
                    it.copy(
                        isStreaming = false,
                        activeToolCalls = emptyMap(),
                    )
                }
                streamingJob = null
            }
        }
    }

    /**
     * Rate-limits TalkBack announcements to at most once every 2 seconds
     * when accessibility services are enabled (Requirement 15.5).
     *
     * When TalkBack is disabled, no rate limiting is applied and no announcements are made.
     */
    private fun maybeAnnounceForAccessibility(content: String) {
        if (!accessibilityStateProvider.isTalkBackEnabled()) return

        val now = System.currentTimeMillis()
        if (now - lastAnnouncementTimeMs >= TALKBACK_ANNOUNCEMENT_INTERVAL_MS) {
            lastAnnouncementTimeMs = now
            accessibilityStateProvider.announce(content)
        }
    }

    private fun copyMessage(messageId: MessageId) {
        val message = _uiState.value.messages.find { it.id == messageId } ?: return
        clipboardManager.copyToClipboard(message.content)
    }

    private fun copyCodeBlock(content: String) {
        clipboardManager.copyToClipboard(content)
    }

    private fun updateInput(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    private fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        streamingJob?.cancel()
        messagesCollectionJob?.cancel()
    }

    internal companion object {
        /** TalkBack announcement rate limit interval in milliseconds. */
        const val TALKBACK_ANNOUNCEMENT_INTERVAL_MS: Long = 2_000L
    }
}
