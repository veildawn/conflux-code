package com.claudemobile.core.domain.usecase

import com.claudemobile.core.common.AppError
import com.claudemobile.core.common.AppResult
import com.claudemobile.core.common.ErrorCode
import com.claudemobile.core.common.asFailure
import com.claudemobile.core.common.asSuccess
import com.claudemobile.core.domain.bridge.CliBridge
import com.claudemobile.core.domain.model.Message
import com.claudemobile.core.domain.model.MessageRole
import com.claudemobile.core.domain.model.MessageStatus
import com.claudemobile.core.domain.model.SessionId
import com.claudemobile.core.domain.repository.ConversationRepository
import javax.inject.Inject

/**
 * Retries a failed turn by resending the same user message to the CLI bridge
 * without creating a duplicate message in the conversation history.
 *
 * This satisfies Requirement 11.3: retry does not duplicate user messages.
 */
public class RetryFailedTurnUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val cliBridge: CliBridge,
) {

    /**
     * Retries the failed turn in the given [sessionId] by resending the last user message.
     *
     * Returns the original user [Message] that was resent on success, or [AppResult.Failure] if:
     * - The session ID is blank (ErrorCode.INVALID_ARGUMENT)
     * - No user message is found for the failed turn (ErrorCode.NOT_FOUND)
     * - Writing to the bridge fails (ErrorCode.PROCESS_ERROR)
     */
    public suspend operator fun invoke(sessionId: SessionId): AppResult<Message> {
        if (sessionId.value.isBlank()) {
            return AppError(
                message = "Session ID must not be empty.",
                code = ErrorCode.INVALID_ARGUMENT,
            ).asFailure()
        }

        val messages = conversationRepository.getMessages(sessionId)

        // Find the last user message that precedes a failed/cancelled/error assistant message
        val lastUserMessage = findLastUserMessageForFailedTurn(messages)

        if (lastUserMessage == null) {
            return AppError(
                message = "No user message found for a failed turn to retry.",
                code = ErrorCode.NOT_FOUND,
            ).asFailure()
        }

        return try {
            // Remove the failed assistant message if present, so a new streaming response can take its place
            val failedAssistantMessage = messages.lastOrNull { message ->
                message.role == MessageRole.ASSISTANT &&
                    (message.status == MessageStatus.ERROR ||
                        message.status == MessageStatus.CANCELLED)
            }

            if (failedAssistantMessage != null &&
                failedAssistantMessage.position > lastUserMessage.position
            ) {
                conversationRepository.updateMessageStatus(
                    failedAssistantMessage.id,
                    MessageStatus.ERROR,
                )
            }

            // Resend the same user message content to the bridge as stream-json
            val jsonMessage = buildString {
                append("""{"type":"user_message","content":""")
                append(SendMessageUseCase.escapeJsonString(lastUserMessage.content))
                append("}\n")
            }
            cliBridge.write(jsonMessage.toByteArray(Charsets.UTF_8))

            lastUserMessage.asSuccess()
        } catch (e: Exception) {
            AppError(
                message = "Failed to retry turn: ${e.message}",
                code = ErrorCode.PROCESS_ERROR,
                cause = e,
            ).asFailure()
        }
    }

    private fun findLastUserMessageForFailedTurn(messages: List<Message>): Message? {
        // Walk backwards to find the last user message that is followed by a failed assistant message
        // or is the last message in the conversation (indicating the assistant never responded)
        val sortedMessages = messages.sortedBy { it.position }

        for (i in sortedMessages.indices.reversed()) {
            val message = sortedMessages[i]
            if (message.role == MessageRole.USER) {
                // Check if there's a subsequent assistant message that failed
                val nextAssistant = sortedMessages.subList(i + 1, sortedMessages.size)
                    .firstOrNull { it.role == MessageRole.ASSISTANT }

                if (nextAssistant == null ||
                    nextAssistant.status == MessageStatus.ERROR ||
                    nextAssistant.status == MessageStatus.CANCELLED
                ) {
                    return message
                }
            }
        }

        return null
    }
}
