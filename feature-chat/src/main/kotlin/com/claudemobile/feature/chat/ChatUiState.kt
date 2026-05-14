package com.claudemobile.feature.chat

import com.claudemobile.core.domain.model.Message
import com.claudemobile.core.domain.model.MessageId
import com.claudemobile.core.domain.model.SessionId
import com.claudemobile.core.domain.model.ToolCallMetadata

/**
 * Represents the complete UI state for the chat screen.
 * Follows unidirectional data flow — the ViewModel exposes this as a StateFlow.
 */
public data class ChatUiState(
    val sessionId: SessionId? = null,
    val sessionTitle: String = "",
    val workspacePath: String = "",
    val messages: List<Message> = emptyList(),
    val isStreaming: Boolean = false,
    val errorMessage: String? = null,
    val inputText: String = "",
    /**
     * Active tool calls being rendered during streaming.
     * Maps message ID to the list of tool calls associated with that message.
     * Used to render structured tool-call blocks inside assistant messages.
     */
    val activeToolCalls: Map<MessageId, List<ToolCallMetadata>> = emptyMap(),
)
