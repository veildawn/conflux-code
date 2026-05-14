package com.claudemobile.feature.chat

import com.claudemobile.core.domain.model.MessageId
import com.claudemobile.core.domain.model.SessionId

/**
 * Actions that the chat UI can dispatch to the ChatViewModel.
 * Follows the unidirectional data flow pattern (Action → ViewModel → State).
 */
public sealed interface ChatAction {

    /**
     * Initialize the chat screen with the given session.
     */
    public data class LoadSession(val sessionId: SessionId) : ChatAction

    /**
     * Send the current input text as a user message.
     */
    public data class SendMessage(val content: String) : ChatAction

    /**
     * Cancel the current in-flight assistant turn.
     */
    public data object Cancel : ChatAction

    /**
     * Retry the last failed turn.
     */
    public data object Retry : ChatAction

    /**
     * Copy the full content of a message to the clipboard.
     */
    public data class CopyMessage(val messageId: MessageId) : ChatAction

    /**
     * Copy a specific code block content to the clipboard.
     */
    public data class CopyCodeBlock(val content: String) : ChatAction

    /**
     * Update the input text field value.
     */
    public data class UpdateInput(val text: String) : ChatAction

    /**
     * Dismiss the current error message.
     */
    public data object DismissError : ChatAction
}
