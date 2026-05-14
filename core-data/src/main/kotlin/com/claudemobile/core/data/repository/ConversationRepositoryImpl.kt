package com.claudemobile.core.data.repository

import com.claudemobile.core.common.TimeProvider
import com.claudemobile.core.common.UuidGenerator
import com.claudemobile.core.common.toEpochMillis
import com.claudemobile.core.common.toInstant
import com.claudemobile.core.data.database.dao.MessageDao
import com.claudemobile.core.data.database.dao.SessionDao
import com.claudemobile.core.data.database.entity.MessageEntity
import com.claudemobile.core.data.database.entity.SessionEntity
import com.claudemobile.core.domain.model.Message
import com.claudemobile.core.domain.model.MessageId
import com.claudemobile.core.domain.model.MessageRole
import com.claudemobile.core.domain.model.MessageStatus
import com.claudemobile.core.domain.model.Session
import com.claudemobile.core.domain.model.SessionId
import com.claudemobile.core.domain.model.ToolCallMetadata
import com.claudemobile.core.domain.model.ToolCallStatus
import com.claudemobile.core.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
public class ConversationRepositoryImpl @Inject constructor(
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
    private val uuidGenerator: UuidGenerator,
    private val timeProvider: TimeProvider,
) : ConversationRepository {

    override fun getSessions(): Flow<List<Session>> {
        return sessionDao.getAllSessionsFlow().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getSession(id: SessionId): Session? {
        return sessionDao.getById(id.value)?.toDomain()
    }

    override suspend fun getMessages(sessionId: SessionId): List<Message> {
        return messageDao.getMessagesForSession(sessionId.value).map { it.toDomain() }
    }

    override fun getMessagesFlow(sessionId: SessionId): Flow<List<Message>> {
        return messageDao.getMessagesForSessionFlow(sessionId.value).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun createSession(title: String, workspacePath: String): Session {
        val now = timeProvider.now()
        val session = Session(
            id = SessionId(uuidGenerator.generate()),
            title = title,
            workspacePath = workspacePath,
            createdAt = now,
            lastActivityAt = now,
            messageCount = 0
        )
        sessionDao.insert(session.toEntity())
        return session
    }

    override suspend fun updateSessionTitle(id: SessionId, title: String) {
        val existing = sessionDao.getById(id.value) ?: return
        val now = timeProvider.now()
        sessionDao.update(
            existing.copy(
                title = title,
                lastActivityAt = now.toEpochMillis()
            )
        )
    }

    override suspend fun deleteSession(id: SessionId) {
        // Messages are cascade-deleted via foreign key when session is deleted
        sessionDao.deleteById(id.value)
    }

    override suspend fun insertMessage(message: Message): Message {
        messageDao.insert(message.toEntity())
        return message
    }

    override suspend fun updateMessageContent(id: MessageId, content: String) {
        val entity = messageDao.getById(id.value) ?: return
        messageDao.update(entity.copy(content = content))
    }

    override suspend fun updateMessageStatus(id: MessageId, status: MessageStatus) {
        val entity = messageDao.getById(id.value) ?: return
        messageDao.update(entity.copy(status = status.toEntityString()))
    }

    // --- Entity ↔ Domain Mapping ---

    private fun SessionEntity.toDomain(): Session = Session(
        id = SessionId(id),
        title = title,
        workspacePath = workspacePath,
        createdAt = createdAt.toInstant(),
        lastActivityAt = lastActivityAt.toInstant(),
        messageCount = messageCount
    )

    private fun Session.toEntity(): SessionEntity = SessionEntity(
        id = id.value,
        title = title,
        workspacePath = workspacePath,
        createdAt = createdAt.toEpochMillis(),
        lastActivityAt = lastActivityAt.toEpochMillis(),
        messageCount = messageCount
    )

    private fun MessageEntity.toDomain(): Message = Message(
        id = MessageId(id),
        sessionId = SessionId(sessionId),
        role = role.toMessageRole(),
        createdAt = createdAt.toInstant(),
        position = position,
        content = content,
        status = status.toMessageStatus(),
        toolCallMetadata = toToolCallMetadata()
    )

    private fun MessageEntity.toToolCallMetadata(): ToolCallMetadata? {
        if (toolName == null) return null
        return ToolCallMetadata(
            toolName = toolName,
            arguments = toolArguments ?: "",
            result = toolResult,
            status = toolStatus?.toToolCallStatus() ?: ToolCallStatus.PENDING
        )
    }

    private fun Message.toEntity(): MessageEntity = MessageEntity(
        id = id.value,
        sessionId = sessionId.value,
        role = role.toEntityString(),
        createdAt = createdAt.toEpochMillis(),
        position = position,
        content = content,
        status = status.toEntityString(),
        toolName = toolCallMetadata?.toolName,
        toolArguments = toolCallMetadata?.arguments,
        toolResult = toolCallMetadata?.result,
        toolStatus = toolCallMetadata?.status?.toEntityString()
    )

    // --- Enum ↔ String Mapping ---

    private fun String.toMessageRole(): MessageRole = when (this) {
        "user" -> MessageRole.USER
        "assistant" -> MessageRole.ASSISTANT
        "tool" -> MessageRole.TOOL
        "system" -> MessageRole.SYSTEM
        else -> MessageRole.SYSTEM
    }

    private fun MessageRole.toEntityString(): String = when (this) {
        MessageRole.USER -> "user"
        MessageRole.ASSISTANT -> "assistant"
        MessageRole.TOOL -> "tool"
        MessageRole.SYSTEM -> "system"
    }

    private fun String.toMessageStatus(): MessageStatus = when (this) {
        "sending" -> MessageStatus.SENDING
        "streaming" -> MessageStatus.STREAMING
        "complete" -> MessageStatus.COMPLETE
        "cancelled" -> MessageStatus.CANCELLED
        "error" -> MessageStatus.ERROR
        else -> MessageStatus.ERROR
    }

    private fun MessageStatus.toEntityString(): String = when (this) {
        MessageStatus.SENDING -> "sending"
        MessageStatus.STREAMING -> "streaming"
        MessageStatus.COMPLETE -> "complete"
        MessageStatus.CANCELLED -> "cancelled"
        MessageStatus.ERROR -> "error"
    }

    private fun String.toToolCallStatus(): ToolCallStatus = when (this) {
        "pending" -> ToolCallStatus.PENDING
        "running" -> ToolCallStatus.RUNNING
        "completed" -> ToolCallStatus.COMPLETED
        "failed" -> ToolCallStatus.FAILED
        else -> ToolCallStatus.PENDING
    }

    private fun ToolCallStatus.toEntityString(): String = when (this) {
        ToolCallStatus.PENDING -> "pending"
        ToolCallStatus.RUNNING -> "running"
        ToolCallStatus.COMPLETED -> "completed"
        ToolCallStatus.FAILED -> "failed"
    }
}
