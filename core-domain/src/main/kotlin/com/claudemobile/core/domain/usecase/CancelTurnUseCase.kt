package com.claudemobile.core.domain.usecase

import com.claudemobile.core.common.AppError
import com.claudemobile.core.common.AppResult
import com.claudemobile.core.common.ErrorCode
import com.claudemobile.core.common.asFailure
import com.claudemobile.core.common.asSuccess
import com.claudemobile.core.domain.bridge.CliBridge
import com.claudemobile.core.domain.bridge.PosixSignal
import com.claudemobile.core.domain.model.MessageId
import com.claudemobile.core.domain.model.MessageRole
import com.claudemobile.core.domain.model.MessageStatus
import com.claudemobile.core.domain.model.SessionId
import com.claudemobile.core.domain.repository.ConversationRepository
import javax.inject.Inject

/**
 * Cancels the current in-flight assistant turn by sending SIGINT to the CLI process
 * and marking the active assistant message as cancelled.
 */
public class CancelTurnUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val cliBridge: CliBridge,
) {

    /**
     * Cancels the active assistant turn for the given [sessionId].
     *
     * Returns the [MessageId] of the cancelled message on success, or [AppResult.Failure] if:
     * - The session ID is blank (ErrorCode.INVALID_ARGUMENT)
     * - No active assistant message is found (ErrorCode.NOT_FOUND)
     * - Sending the signal fails (ErrorCode.PROCESS_ERROR)
     */
    public suspend operator fun invoke(sessionId: SessionId): AppResult<MessageId> {
        if (sessionId.value.isBlank()) {
            return AppError(
                message = "Session ID must not be empty.",
                code = ErrorCode.INVALID_ARGUMENT,
            ).asFailure()
        }

        // Find the active assistant message (STREAMING or SENDING status)
        val messages = conversationRepository.getMessages(sessionId)
        val activeAssistantMessage = messages.lastOrNull { message ->
            message.role == MessageRole.ASSISTANT &&
                (message.status == MessageStatus.STREAMING || message.status == MessageStatus.SENDING)
        }

        if (activeAssistantMessage == null) {
            return AppError(
                message = "No active assistant message found to cancel.",
                code = ErrorCode.NOT_FOUND,
            ).asFailure()
        }

        return try {
            // Send SIGINT to the CLI process (Requirement 2.8)
            cliBridge.sendSignal(PosixSignal.SIGINT)

            // Mark the assistant message as cancelled
            conversationRepository.updateMessageStatus(
                activeAssistantMessage.id,
                MessageStatus.CANCELLED,
            )

            activeAssistantMessage.id.asSuccess()
        } catch (e: Exception) {
            AppError(
                message = "Failed to cancel turn: ${e.message}",
                code = ErrorCode.PROCESS_ERROR,
                cause = e,
            ).asFailure()
        }
    }
}
