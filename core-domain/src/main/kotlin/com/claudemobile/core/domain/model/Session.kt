package com.claudemobile.core.domain.model

import java.time.Instant

/**
 * Unique identifier for a conversation session.
 */
@JvmInline
public value class SessionId(public val value: String)

/**
 * A single logical conversation with Claude CLI, consisting of an ordered list of Messages
 * and an associated Workspace.
 */
public data class Session(
    val id: SessionId,
    val title: String,
    val workspacePath: String,
    val createdAt: Instant,
    val lastActivityAt: Instant,
    val messageCount: Int
)
