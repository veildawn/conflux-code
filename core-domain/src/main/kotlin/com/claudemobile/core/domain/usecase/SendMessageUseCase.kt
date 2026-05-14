package com.claudemobile.core.domain.usecase

import com.claudemobile.core.common.AppError
import com.claudemobile.core.common.AppResult
import com.claudemobile.core.common.ErrorCode
import com.claudemobile.core.common.TimeProvider
import com.claudemobile.core.common.UuidGenerator
import com.claudemobile.core.common.asFailure
import com.claudemobile.core.common.asSuccess
import com.claudemobile.core.domain.bridge.CliBridge
import com.claudemobile.core.domain.model.Message
import com.claudemobile.core.domain.model.MessageId
import com.claudemobile.core.domain.model.MessageRole
import com.claudemobile.core.domain.model.MessageStatus
import com.claudemobile.core.domain.model.SessionId
import com.claudemobile.core.domain.repository.ConversationRepository
import javax.inject.Inject

/**
 * Sends a user message in a conversation session.
 *
 * Persists the message with SENDING status before forwarding it to the CLI bridge,
 * then marks it as COMPLETE. The input should be cleared by the caller after invocation.
 */
public class SendMessageUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val cliBridge: CliBridge,
    private val prootEnvironmentProvider: ProotEnvironmentProvider,
    private val timeProvider: TimeProvider,
    private val uuidGenerator: UuidGenerator,
) {

    /**
     * Sends [content] as a user message in the session identified by [sessionId].
     *
     * Returns the persisted [Message] on success, or [AppResult.Failure] if:
     * - The content is blank (ErrorCode.INVALID_ARGUMENT)
     * - The session ID is blank (ErrorCode.INVALID_ARGUMENT)
     * - Writing to the bridge fails (ErrorCode.PROCESS_ERROR)
     */
    public suspend operator fun invoke(sessionId: SessionId, content: String): AppResult<Message> {
        if (sessionId.value.isBlank()) {
            return AppError(
                message = "Session ID must not be empty.",
                code = ErrorCode.INVALID_ARGUMENT,
            ).asFailure()
        }

        if (content.isBlank()) {
            return AppError(
                message = "Message content must not be empty.",
                code = ErrorCode.INVALID_ARGUMENT,
            ).asFailure()
        }

        val messages = conversationRepository.getMessages(sessionId)
        val nextPosition = if (messages.isEmpty()) 0 else messages.maxOf { it.position } + 1

        val message = Message(
            id = MessageId(uuidGenerator.generate()),
            sessionId = sessionId,
            role = MessageRole.USER,
            createdAt = timeProvider.now(),
            position = nextPosition,
            content = content,
            status = MessageStatus.SENDING,
            toolCallMetadata = null,
        )

        // Persist before forwarding to bridge (Requirement 5.3)
        val persistedMessage = conversationRepository.insertMessage(message)

        // Resolve workspace path from the session for the spawn config
        val session = conversationRepository.getSession(sessionId)
        val workspacePath = session?.workspacePath ?: "/workspace"

        return try {
            // In --print mode, each user turn spawns a fresh CLI process.
            // Terminate any existing process first, then spawn with -p <content>.
            cliBridge.terminate()

            val config = prootEnvironmentProvider.buildSpawnConfig(
                workspacePath = workspacePath,
                apiKey = "PLACEHOLDER_OVERWRITTEN_BY_SPAWN_ENV_ADAPTER",
            )
            // Append "-p" and the user's message to the args list
            val configWithPrompt = config.copy(
                args = config.args + listOf("-p", content)
            )
            val spawnResult = cliBridge.spawn(configWithPrompt)
            if (spawnResult.isFailure) {
                conversationRepository.updateMessageStatus(persistedMessage.id, MessageStatus.ERROR)
                return AppError(
                    message = "Failed to spawn CLI: ${spawnResult.exceptionOrNull()?.message}",
                    code = ErrorCode.PROCESS_ERROR,
                    cause = spawnResult.exceptionOrNull(),
                ).asFailure()
            }

            // Mark as complete after successful spawn
            conversationRepository.updateMessageStatus(persistedMessage.id, MessageStatus.COMPLETE)

            persistedMessage.copy(status = MessageStatus.COMPLETE).asSuccess()
        } catch (e: Exception) {
            // Mark as error if bridge write fails
            conversationRepository.updateMessageStatus(persistedMessage.id, MessageStatus.ERROR)

            AppError(
                message = "Failed to send message to CLI bridge: ${e.message}",
                code = ErrorCode.PROCESS_ERROR,
                cause = e,
            ).asFailure()
        }
    }

    internal companion object {
        /**
         * Escapes a string for safe embedding in a JSON value (including surrounding quotes).
         */
        fun escapeJsonString(value: String): String {
            val sb = StringBuilder(value.length + 2)
            sb.append('"')
            for (ch in value) {
                when (ch) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    else -> {
                        if (ch.code < 0x20) {
                            sb.append("\\u%04x".format(ch.code))
                        } else {
                            sb.append(ch)
                        }
                    }
                }
            }
            sb.append('"')
            return sb.toString()
        }
    }
}
