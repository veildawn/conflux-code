package com.claudemobile.core.domain.repository

import com.claudemobile.core.domain.model.Message
import com.claudemobile.core.domain.model.MessageId
import com.claudemobile.core.domain.model.MessageStatus
import com.claudemobile.core.domain.model.Session
import com.claudemobile.core.domain.model.SessionId
import kotlinx.coroutines.flow.Flow

/**
 * Repository responsible for persisting and retrieving Sessions and Messages.
 */
public interface ConversationRepository {

    /**
     * Returns a reactive stream of all sessions ordered by last activity timestamp descending.
     */
    public fun getSessions(): Flow<List<Session>>

    /**
     * Returns a single session by its identifier, or null if not found.
     */
    public suspend fun getSession(id: SessionId): Session?

    /**
     * Returns all messages for a session in ascending position order.
     */
    public suspend fun getMessages(sessionId: SessionId): List<Message>

    /**
     * Returns a reactive stream of messages for a session in ascending position order.
     */
    public fun getMessagesFlow(sessionId: SessionId): Flow<List<Message>>

    /**
     * Creates a new session with the given title and workspace path.
     */
    public suspend fun createSession(title: String, workspacePath: String): Session

    /**
     * Updates the title of an existing session.
     */
    public suspend fun updateSessionTitle(id: SessionId, title: String)

    /**
     * Deletes a session and all associated messages in a single transaction.
     */
    public suspend fun deleteSession(id: SessionId)

    /**
     * Inserts a new message and returns it with any generated fields populated.
     */
    public suspend fun insertMessage(message: Message): Message

    /**
     * Updates the content of an existing message.
     */
    public suspend fun updateMessageContent(id: MessageId, content: String)

    /**
     * Updates the status of an existing message.
     */
    public suspend fun updateMessageStatus(id: MessageId, status: MessageStatus)
}
