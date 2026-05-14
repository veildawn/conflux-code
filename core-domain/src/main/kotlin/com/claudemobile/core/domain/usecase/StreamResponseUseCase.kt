package com.claudemobile.core.domain.usecase

import com.claudemobile.core.common.AppError
import com.claudemobile.core.common.AppResult
import com.claudemobile.core.common.ErrorCode
import com.claudemobile.core.common.TimeProvider
import com.claudemobile.core.common.UuidGenerator
import com.claudemobile.core.common.asFailure
import com.claudemobile.core.common.asSuccess
import com.claudemobile.core.domain.bridge.BridgeEvent
import com.claudemobile.core.domain.bridge.CliBridge
import com.claudemobile.core.domain.model.Message
import com.claudemobile.core.domain.model.MessageId
import com.claudemobile.core.domain.model.MessageRole
import com.claudemobile.core.domain.model.MessageStatus
import com.claudemobile.core.domain.model.OutputEvent
import com.claudemobile.core.domain.model.SessionId
import com.claudemobile.core.domain.parser.OutputParser
import com.claudemobile.core.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

/**
 * Collects streaming output from the CLI bridge, parses it into structured events,
 * accumulates text content, persists incrementally (every 2 seconds), and marks
 * the assistant message as complete on turn_complete.
 *
 * Emits [StreamEvent] values to allow the UI layer to update incrementally.
 */
public class StreamResponseUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val cliBridge: CliBridge,
    private val outputParser: OutputParser,
    private val timeProvider: TimeProvider,
    private val uuidGenerator: UuidGenerator,
) {

    /**
     * Starts collecting the bridge output flow for the given [sessionId] and returns
     * a Flow of [StreamEvent] values representing incremental updates.
     *
     * Creates an assistant message with STREAMING status and updates it as content arrives.
     * Persists content at least every 2 seconds to minimize data loss on process death.
     *
     * Returns [AppResult.Failure] if the session ID is blank.
     */
    public operator fun invoke(sessionId: SessionId): AppResult<Flow<StreamEvent>> {
        if (sessionId.value.isBlank()) {
            return AppError(
                message = "Session ID must not be empty.",
                code = ErrorCode.INVALID_ARGUMENT,
            ).asFailure()
        }

        val responseFlow = flow {
            // Create the assistant message with STREAMING status
            val messages = conversationRepository.getMessages(sessionId)
            val nextPosition = if (messages.isEmpty()) 0 else messages.maxOf { it.position } + 1

            val assistantMessage = Message(
                id = MessageId(uuidGenerator.generate()),
                sessionId = sessionId,
                role = MessageRole.ASSISTANT,
                createdAt = timeProvider.now(),
                position = nextPosition,
                content = "",
                status = MessageStatus.STREAMING,
                toolCallMetadata = null,
            )

            conversationRepository.insertMessage(assistantMessage)
            emit(StreamEvent.MessageCreated(assistantMessage.id))

            val contentBuilder = StringBuilder()
            var lastPersistTime = timeProvider.now().toEpochMilli()
            var unconsumedBuffer = byteArrayOf()

            outputParser.reset()

            cliBridge.outputFlow.collect { bridgeEvent ->
                when (bridgeEvent) {
                    is BridgeEvent.Output -> {
                        // Prepend any unconsumed bytes from previous parse
                        val inputBytes = if (unconsumedBuffer.isNotEmpty()) {
                            unconsumedBuffer + bridgeEvent.bytes
                        } else {
                            bridgeEvent.bytes
                        }

                        val parseResult = outputParser.parse(inputBytes)
                        unconsumedBuffer = parseResult.remainingBuffer

                        for (event in parseResult.events) {
                            when (event) {
                                is OutputEvent.Text -> {
                                    contentBuilder.append(event.content)
                                    emit(StreamEvent.ContentUpdated(
                                        messageId = assistantMessage.id,
                                        content = contentBuilder.toString(),
                                    ))

                                    // Persist every 2 seconds (Requirement 5.5)
                                    val now = timeProvider.now().toEpochMilli()
                                    if (now - lastPersistTime >= PERSIST_INTERVAL_MS) {
                                        conversationRepository.updateMessageContent(
                                            assistantMessage.id,
                                            contentBuilder.toString(),
                                        )
                                        lastPersistTime = now
                                    }
                                }

                                is OutputEvent.TurnComplete -> {
                                    // Final persist and mark complete
                                    conversationRepository.updateMessageContent(
                                        assistantMessage.id,
                                        contentBuilder.toString(),
                                    )
                                    conversationRepository.updateMessageStatus(
                                        assistantMessage.id,
                                        MessageStatus.COMPLETE,
                                    )
                                    emit(StreamEvent.TurnCompleted(assistantMessage.id))
                                    return@collect
                                }

                                is OutputEvent.Error -> {
                                    conversationRepository.updateMessageContent(
                                        assistantMessage.id,
                                        contentBuilder.toString(),
                                    )
                                    conversationRepository.updateMessageStatus(
                                        assistantMessage.id,
                                        MessageStatus.ERROR,
                                    )
                                    emit(StreamEvent.Error(
                                        messageId = assistantMessage.id,
                                        reason = event.reason,
                                    ))
                                    return@collect
                                }

                                is OutputEvent.ToolCallStart -> {
                                    emit(StreamEvent.ToolCallStarted(
                                        messageId = assistantMessage.id,
                                        toolName = event.toolName,
                                        arguments = event.arguments,
                                    ))
                                }

                                is OutputEvent.ToolCallResult -> {
                                    emit(StreamEvent.ToolCallCompleted(
                                        messageId = assistantMessage.id,
                                        toolName = event.toolName,
                                        result = event.result,
                                        success = event.success,
                                    ))
                                }

                                is OutputEvent.Prompt -> {
                                    emit(StreamEvent.PromptReceived(
                                        messageId = assistantMessage.id,
                                        text = event.text,
                                    ))
                                }
                            }
                        }
                    }

                    is BridgeEvent.ProcessExited -> {
                        // Persist final content and mark based on exit cause
                        conversationRepository.updateMessageContent(
                            assistantMessage.id,
                            contentBuilder.toString(),
                        )
                        val finalStatus = when {
                            bridgeEvent.exitCode == 0 -> MessageStatus.COMPLETE
                            else -> MessageStatus.ERROR
                        }
                        conversationRepository.updateMessageStatus(
                            assistantMessage.id,
                            finalStatus,
                        )
                        emit(StreamEvent.ProcessExited(
                            messageId = assistantMessage.id,
                            exitCode = bridgeEvent.exitCode,
                        ))
                        return@collect
                    }

                    is BridgeEvent.Error -> {
                        conversationRepository.updateMessageContent(
                            assistantMessage.id,
                            contentBuilder.toString(),
                        )
                        conversationRepository.updateMessageStatus(
                            assistantMessage.id,
                            MessageStatus.ERROR,
                        )
                        emit(StreamEvent.Error(
                            messageId = assistantMessage.id,
                            reason = bridgeEvent.message,
                        ))
                        return@collect
                    }

                    is BridgeEvent.ProcessStarted -> {
                        // Process started event — no action needed during streaming
                    }
                }
            }
        }

        return responseFlow.asSuccess()
    }

    private companion object {
        /** Persist interval in milliseconds (2 seconds). */
        const val PERSIST_INTERVAL_MS: Long = 2_000L
    }
}

/**
 * Events emitted during response streaming, allowing the UI to update incrementally.
 */
public sealed interface StreamEvent {

    /** The assistant message was created and inserted into the repository. */
    public data class MessageCreated(val messageId: MessageId) : StreamEvent

    /** The message content was updated with new text. */
    public data class ContentUpdated(
        val messageId: MessageId,
        val content: String,
    ) : StreamEvent

    /** The turn completed successfully. */
    public data class TurnCompleted(val messageId: MessageId) : StreamEvent

    /** An error occurred during streaming. */
    public data class Error(
        val messageId: MessageId,
        val reason: String,
    ) : StreamEvent

    /** A tool call was started. */
    public data class ToolCallStarted(
        val messageId: MessageId,
        val toolName: String,
        val arguments: String,
    ) : StreamEvent

    /** A tool call completed. */
    public data class ToolCallCompleted(
        val messageId: MessageId,
        val toolName: String,
        val result: String,
        val success: Boolean,
    ) : StreamEvent

    /** A prompt was received from the CLI. */
    public data class PromptReceived(
        val messageId: MessageId,
        val text: String,
    ) : StreamEvent

    /** The CLI process exited. */
    public data class ProcessExited(
        val messageId: MessageId,
        val exitCode: Int,
    ) : StreamEvent
}
