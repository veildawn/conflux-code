package com.claudemobile.core.domain.model

import java.time.Instant

/**
 * Unique identifier for a message within a session.
 */
@JvmInline
public value class MessageId(public val value: String)

/**
 * The role of a message sender in a conversation.
 */
public enum class MessageRole {
    USER,
    ASSISTANT,
    TOOL,
    SYSTEM
}

/**
 * The lifecycle status of a message.
 */
public enum class MessageStatus {
    SENDING,
    STREAMING,
    COMPLETE,
    CANCELLED,
    ERROR
}

/**
 * A single turn in a Session, attributed to either the User, Claude CLI, or the System.
 */
public data class Message(
    val id: MessageId,
    val sessionId: SessionId,
    val role: MessageRole,
    val createdAt: Instant,
    val position: Int,
    val content: String,
    val status: MessageStatus,
    val toolCallMetadata: ToolCallMetadata?
)

/**
 * Metadata describing a tool call within a message.
 */
public data class ToolCallMetadata(
    val toolName: String,
    val arguments: String,
    val result: String?,
    val status: ToolCallStatus
)

/**
 * The lifecycle status of a tool call.
 */
public enum class ToolCallStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED
}
