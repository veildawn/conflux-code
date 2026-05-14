package com.claudemobile.core.data.repository

import app.cash.turbine.test
import com.claudemobile.core.common.TimeProvider
import com.claudemobile.core.common.UuidGenerator
import com.claudemobile.core.data.database.dao.MessageDao
import com.claudemobile.core.data.database.dao.SessionDao
import com.claudemobile.core.data.database.entity.MessageEntity
import com.claudemobile.core.data.database.entity.SessionEntity
import com.claudemobile.core.domain.model.Message
import com.claudemobile.core.domain.model.MessageId
import com.claudemobile.core.domain.model.MessageRole
import com.claudemobile.core.domain.model.MessageStatus
import com.claudemobile.core.domain.model.SessionId
import com.claudemobile.core.domain.model.ToolCallMetadata
import com.claudemobile.core.domain.model.ToolCallStatus
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

class ConversationRepositoryImplTest {

    private val sessionDao: SessionDao = mockk(relaxed = true)
    private val messageDao: MessageDao = mockk(relaxed = true)
    private val uuidGenerator: UuidGenerator = mockk()
    private val timeProvider: TimeProvider = mockk()

    private lateinit var repository: ConversationRepositoryImpl

    private val fixedInstant = Instant.ofEpochMilli(1700000000000L)
    private val fixedUuid = "test-uuid-1234"

    @BeforeEach
    fun setup() {
        every { uuidGenerator.generate() } returns fixedUuid
        every { timeProvider.now() } returns fixedInstant
        repository = ConversationRepositoryImpl(sessionDao, messageDao, uuidGenerator, timeProvider)
    }

    @Nested
    inner class CreateSession {

        @Test
        fun `createSession generates UUID and uses current timestamp`() = runTest {
            val sessionSlot = slot<SessionEntity>()
            coEvery { sessionDao.insert(capture(sessionSlot)) } returns Unit

            val session = repository.createSession("Test Session", "/workspace/path")

            session.id.value shouldBe fixedUuid
            session.title shouldBe "Test Session"
            session.workspacePath shouldBe "/workspace/path"
            session.createdAt shouldBe fixedInstant
            session.lastActivityAt shouldBe fixedInstant
            session.messageCount shouldBe 0
        }

        @Test
        fun `createSession persists entity to DAO`() = runTest {
            val sessionSlot = slot<SessionEntity>()
            coEvery { sessionDao.insert(capture(sessionSlot)) } returns Unit

            repository.createSession("My Session", "/path")

            coVerify(exactly = 1) { sessionDao.insert(any()) }
            sessionSlot.captured.id shouldBe fixedUuid
            sessionSlot.captured.title shouldBe "My Session"
            sessionSlot.captured.workspacePath shouldBe "/path"
            sessionSlot.captured.createdAt shouldBe fixedInstant.toEpochMilli()
            sessionSlot.captured.lastActivityAt shouldBe fixedInstant.toEpochMilli()
            sessionSlot.captured.messageCount shouldBe 0
        }
    }

    @Nested
    inner class GetSessions {

        @Test
        fun `getSessions maps entities to domain models`() = runTest {
            val entities = listOf(
                SessionEntity(
                    id = "session-1",
                    title = "First Session",
                    workspacePath = "/path/1",
                    createdAt = 1700000000000L,
                    lastActivityAt = 1700000001000L,
                    messageCount = 5
                ),
                SessionEntity(
                    id = "session-2",
                    title = "Second Session",
                    workspacePath = "/path/2",
                    createdAt = 1700000002000L,
                    lastActivityAt = 1700000003000L,
                    messageCount = 10
                )
            )
            every { sessionDao.getAllSessionsFlow() } returns flowOf(entities)

            repository.getSessions().test {
                val sessions = awaitItem()
                sessions shouldHaveSize 2

                sessions[0].id.value shouldBe "session-1"
                sessions[0].title shouldBe "First Session"
                sessions[0].workspacePath shouldBe "/path/1"
                sessions[0].createdAt shouldBe Instant.ofEpochMilli(1700000000000L)
                sessions[0].lastActivityAt shouldBe Instant.ofEpochMilli(1700000001000L)
                sessions[0].messageCount shouldBe 5

                sessions[1].id.value shouldBe "session-2"
                sessions[1].title shouldBe "Second Session"
                sessions[1].messageCount shouldBe 10

                cancelAndConsumeRemainingEvents()
            }
        }

        @Test
        fun `getSessions returns empty list when no sessions exist`() = runTest {
            every { sessionDao.getAllSessionsFlow() } returns flowOf(emptyList())

            repository.getSessions().test {
                val sessions = awaitItem()
                sessions.shouldBeEmpty()
                cancelAndConsumeRemainingEvents()
            }
        }
    }

    @Nested
    inner class GetSession {

        @Test
        fun `getSession returns domain model when found`() = runTest {
            coEvery { sessionDao.getById("session-1") } returns SessionEntity(
                id = "session-1",
                title = "Found Session",
                workspacePath = "/workspace",
                createdAt = 1700000000000L,
                lastActivityAt = 1700000001000L,
                messageCount = 3
            )

            val session = repository.getSession(SessionId("session-1"))

            session.shouldNotBeNull()
            session.id.value shouldBe "session-1"
            session.title shouldBe "Found Session"
            session.workspacePath shouldBe "/workspace"
            session.messageCount shouldBe 3
        }

        @Test
        fun `getSession returns null when not found`() = runTest {
            coEvery { sessionDao.getById("nonexistent") } returns null

            val session = repository.getSession(SessionId("nonexistent"))

            session.shouldBeNull()
        }
    }

    @Nested
    inner class DeleteSession {

        @Test
        fun `deleteSession calls DAO deleteById with correct id`() = runTest {
            coEvery { sessionDao.deleteById("session-to-delete") } returns Unit

            repository.deleteSession(SessionId("session-to-delete"))

            coVerify(exactly = 1) { sessionDao.deleteById("session-to-delete") }
        }
    }

    @Nested
    inner class UpdateSessionTitle {

        @Test
        fun `updateSessionTitle updates title and lastActivityAt via update`() = runTest {
            val existingEntity = SessionEntity(
                id = "session-1",
                title = "Old Title",
                workspacePath = "/path",
                createdAt = 1700000000000L,
                lastActivityAt = 1700000000000L,
                messageCount = 5
            )
            coEvery { sessionDao.getById("session-1") } returns existingEntity
            coEvery { sessionDao.update(any()) } returns Unit

            repository.updateSessionTitle(SessionId("session-1"), "New Title")

            val updateSlot = slot<SessionEntity>()
            coVerify(exactly = 1) { sessionDao.update(capture(updateSlot)) }
            updateSlot.captured.title shouldBe "New Title"
            updateSlot.captured.lastActivityAt shouldBe fixedInstant.toEpochMilli()
            // Other fields should remain unchanged
            updateSlot.captured.id shouldBe "session-1"
            updateSlot.captured.workspacePath shouldBe "/path"
            updateSlot.captured.messageCount shouldBe 5
        }
    }

    @Nested
    inner class InsertMessage {

        @Test
        fun `insertMessage maps domain model to entity and persists`() = runTest {
            val messageSlot = slot<MessageEntity>()
            coEvery { messageDao.insert(capture(messageSlot)) } returns Unit

            val message = Message(
                id = MessageId("msg-1"),
                sessionId = SessionId("session-1"),
                role = MessageRole.USER,
                createdAt = fixedInstant,
                position = 0,
                content = "Hello Claude",
                status = MessageStatus.COMPLETE,
                toolCallMetadata = null
            )

            val result = repository.insertMessage(message)

            result shouldBe message
            messageSlot.captured.id shouldBe "msg-1"
            messageSlot.captured.sessionId shouldBe "session-1"
            messageSlot.captured.role shouldBe "user"
            messageSlot.captured.createdAt shouldBe fixedInstant.toEpochMilli()
            messageSlot.captured.position shouldBe 0
            messageSlot.captured.content shouldBe "Hello Claude"
            messageSlot.captured.status shouldBe "complete"
            messageSlot.captured.toolName.shouldBeNull()
            messageSlot.captured.toolArguments.shouldBeNull()
            messageSlot.captured.toolResult.shouldBeNull()
            messageSlot.captured.toolStatus.shouldBeNull()
        }

        @Test
        fun `insertMessage maps assistant role correctly`() = runTest {
            val messageSlot = slot<MessageEntity>()
            coEvery { messageDao.insert(capture(messageSlot)) } returns Unit

            val message = Message(
                id = MessageId("msg-2"),
                sessionId = SessionId("session-1"),
                role = MessageRole.ASSISTANT,
                createdAt = fixedInstant,
                position = 1,
                content = "Hello! How can I help?",
                status = MessageStatus.STREAMING,
                toolCallMetadata = null
            )

            repository.insertMessage(message)

            messageSlot.captured.role shouldBe "assistant"
            messageSlot.captured.status shouldBe "streaming"
        }

        @Test
        fun `insertMessage maps tool call metadata to individual columns`() = runTest {
            val messageSlot = slot<MessageEntity>()
            coEvery { messageDao.insert(capture(messageSlot)) } returns Unit

            val message = Message(
                id = MessageId("msg-3"),
                sessionId = SessionId("session-1"),
                role = MessageRole.TOOL,
                createdAt = fixedInstant,
                position = 2,
                content = "Tool output",
                status = MessageStatus.COMPLETE,
                toolCallMetadata = ToolCallMetadata(
                    toolName = "read_file",
                    arguments = "{\"path\": \"/src/main.kt\"}",
                    result = "file contents here",
                    status = ToolCallStatus.COMPLETED
                )
            )

            repository.insertMessage(message)

            messageSlot.captured.toolName shouldBe "read_file"
            messageSlot.captured.toolArguments shouldBe "{\"path\": \"/src/main.kt\"}"
            messageSlot.captured.toolResult shouldBe "file contents here"
            messageSlot.captured.toolStatus shouldBe "completed"
        }
    }

    @Nested
    inner class GetMessages {

        @Test
        fun `getMessages returns messages in position order`() = runTest {
            val entities = listOf(
                MessageEntity(
                    id = "msg-1",
                    sessionId = "session-1",
                    role = "user",
                    createdAt = 1700000000000L,
                    position = 0,
                    content = "First message",
                    status = "complete"
                ),
                MessageEntity(
                    id = "msg-2",
                    sessionId = "session-1",
                    role = "assistant",
                    createdAt = 1700000001000L,
                    position = 1,
                    content = "Second message",
                    status = "complete"
                ),
                MessageEntity(
                    id = "msg-3",
                    sessionId = "session-1",
                    role = "user",
                    createdAt = 1700000002000L,
                    position = 2,
                    content = "Third message",
                    status = "complete"
                )
            )
            coEvery { messageDao.getMessagesForSession("session-1") } returns entities

            val messages = repository.getMessages(SessionId("session-1"))

            messages shouldHaveSize 3
            messages[0].position shouldBe 0
            messages[0].content shouldBe "First message"
            messages[0].role shouldBe MessageRole.USER
            messages[1].position shouldBe 1
            messages[1].content shouldBe "Second message"
            messages[1].role shouldBe MessageRole.ASSISTANT
            messages[2].position shouldBe 2
            messages[2].content shouldBe "Third message"
        }

        @Test
        fun `getMessages returns empty list for session with no messages`() = runTest {
            coEvery { messageDao.getMessagesForSession("empty-session") } returns emptyList()

            val messages = repository.getMessages(SessionId("empty-session"))

            messages.shouldBeEmpty()
        }
    }

    @Nested
    inner class GetMessagesFlow {

        @Test
        fun `getMessagesFlow emits updates when messages change`() = runTest {
            val messagesFlow = MutableSharedFlow<List<MessageEntity>>(replay = 1)
            every { messageDao.getMessagesForSessionFlow("session-1") } returns messagesFlow

            messagesFlow.emit(listOf(
                MessageEntity(
                    id = "msg-1",
                    sessionId = "session-1",
                    role = "user",
                    createdAt = 1700000000000L,
                    position = 0,
                    content = "Hello",
                    status = "complete"
                )
            ))

            repository.getMessagesFlow(SessionId("session-1")).test {
                val first = awaitItem()
                first shouldHaveSize 1
                first[0].content shouldBe "Hello"

                // Emit an update with a new message added
                messagesFlow.emit(listOf(
                    MessageEntity(
                        id = "msg-1",
                        sessionId = "session-1",
                        role = "user",
                        createdAt = 1700000000000L,
                        position = 0,
                        content = "Hello",
                        status = "complete"
                    ),
                    MessageEntity(
                        id = "msg-2",
                        sessionId = "session-1",
                        role = "assistant",
                        createdAt = 1700000001000L,
                        position = 1,
                        content = "Hi there!",
                        status = "complete"
                    )
                ))

                val second = awaitItem()
                second shouldHaveSize 2
                second[1].content shouldBe "Hi there!"
                second[1].role shouldBe MessageRole.ASSISTANT

                cancelAndConsumeRemainingEvents()
            }
        }
    }

    @Nested
    inner class GetSessionsFlow {

        @Test
        fun `getSessions flow emits updates when sessions change`() = runTest {
            val sessionsFlow = MutableSharedFlow<List<SessionEntity>>(replay = 1)
            every { sessionDao.getAllSessionsFlow() } returns sessionsFlow

            sessionsFlow.emit(listOf(
                SessionEntity(
                    id = "session-1",
                    title = "Session One",
                    workspacePath = "/path",
                    createdAt = 1700000000000L,
                    lastActivityAt = 1700000000000L,
                    messageCount = 0
                )
            ))

            repository.getSessions().test {
                val first = awaitItem()
                first shouldHaveSize 1
                first[0].title shouldBe "Session One"

                // Emit update with new session added
                sessionsFlow.emit(listOf(
                    SessionEntity(
                        id = "session-1",
                        title = "Session One",
                        workspacePath = "/path",
                        createdAt = 1700000000000L,
                        lastActivityAt = 1700000000000L,
                        messageCount = 0
                    ),
                    SessionEntity(
                        id = "session-2",
                        title = "Session Two",
                        workspacePath = "/path2",
                        createdAt = 1700000001000L,
                        lastActivityAt = 1700000001000L,
                        messageCount = 2
                    )
                ))

                val second = awaitItem()
                second shouldHaveSize 2
                second[1].title shouldBe "Session Two"

                cancelAndConsumeRemainingEvents()
            }
        }
    }

    @Nested
    inner class UpdateMessageContent {

        @Test
        fun `updateMessageContent retrieves entity and updates content`() = runTest {
            val existingEntity = MessageEntity(
                id = "msg-1",
                sessionId = "session-1",
                role = "user",
                createdAt = 1700000000000L,
                position = 0,
                content = "Original content",
                status = "complete"
            )
            coEvery { messageDao.getById("msg-1") } returns existingEntity
            coEvery { messageDao.update(any()) } returns Unit

            repository.updateMessageContent(MessageId("msg-1"), "Updated content")

            val updateSlot = slot<MessageEntity>()
            coVerify(exactly = 1) { messageDao.update(capture(updateSlot)) }
            updateSlot.captured.content shouldBe "Updated content"
            updateSlot.captured.id shouldBe "msg-1"
        }
    }

    @Nested
    inner class UpdateMessageStatus {

        @Test
        fun `updateMessageStatus retrieves entity and updates status`() = runTest {
            val existingEntity = MessageEntity(
                id = "msg-1",
                sessionId = "session-1",
                role = "assistant",
                createdAt = 1700000000000L,
                position = 1,
                content = "Hello",
                status = "streaming"
            )
            coEvery { messageDao.getById("msg-1") } returns existingEntity
            coEvery { messageDao.update(any()) } returns Unit

            repository.updateMessageStatus(MessageId("msg-1"), MessageStatus.CANCELLED)

            val updateSlot = slot<MessageEntity>()
            coVerify(exactly = 1) { messageDao.update(capture(updateSlot)) }
            updateSlot.captured.status shouldBe "cancelled"
            updateSlot.captured.id shouldBe "msg-1"
        }
    }

    @Nested
    inner class CascadeDelete {

        @Test
        fun `deleteSession relies on foreign key cascade for message deletion`() = runTest {
            coEvery { sessionDao.deleteById("session-1") } returns Unit

            repository.deleteSession(SessionId("session-1"))

            coVerify(exactly = 1) { sessionDao.deleteById("session-1") }
            // messageDao.deleteBySessionId should NOT be called explicitly
            // because CASCADE handles it at the database level
            coVerify(exactly = 0) { messageDao.deleteBySessionId(any()) }
        }
    }

    @Nested
    inner class ToolCallMetadataSerialization {

        @Test
        fun `message with null tool call result maps to null toolResult column`() = runTest {
            val messageSlot = slot<MessageEntity>()
            coEvery { messageDao.insert(capture(messageSlot)) } returns Unit

            val message = Message(
                id = MessageId("msg-tool"),
                sessionId = SessionId("session-1"),
                role = MessageRole.TOOL,
                createdAt = fixedInstant,
                position = 3,
                content = "Running tool...",
                status = MessageStatus.STREAMING,
                toolCallMetadata = ToolCallMetadata(
                    toolName = "write_file",
                    arguments = "{\"path\": \"/tmp/test.txt\", \"content\": \"hello\"}",
                    result = null,
                    status = ToolCallStatus.RUNNING
                )
            )

            repository.insertMessage(message)

            messageSlot.captured.toolName shouldBe "write_file"
            messageSlot.captured.toolArguments shouldBe "{\"path\": \"/tmp/test.txt\", \"content\": \"hello\"}"
            messageSlot.captured.toolResult.shouldBeNull()
            messageSlot.captured.toolStatus shouldBe "running"
        }

        @Test
        fun `getMessages deserializes tool call metadata from individual columns`() = runTest {
            coEvery { messageDao.getMessagesForSession("session-1") } returns listOf(
                MessageEntity(
                    id = "msg-1",
                    sessionId = "session-1",
                    role = "tool",
                    createdAt = 1700000000000L,
                    position = 0,
                    content = "Tool result",
                    status = "complete",
                    toolName = "read_file",
                    toolArguments = "{}",
                    toolResult = "content",
                    toolStatus = "completed"
                )
            )

            val messages = repository.getMessages(SessionId("session-1"))

            messages shouldHaveSize 1
            messages[0].toolCallMetadata.shouldNotBeNull()
            messages[0].toolCallMetadata!!.toolName shouldBe "read_file"
            messages[0].toolCallMetadata!!.arguments shouldBe "{}"
            messages[0].toolCallMetadata!!.result shouldBe "content"
            messages[0].toolCallMetadata!!.status shouldBe ToolCallStatus.COMPLETED
        }

        @Test
        fun `getMessages handles null toolName as no tool metadata`() = runTest {
            coEvery { messageDao.getMessagesForSession("session-1") } returns listOf(
                MessageEntity(
                    id = "msg-1",
                    sessionId = "session-1",
                    role = "user",
                    createdAt = 1700000000000L,
                    position = 0,
                    content = "Hello",
                    status = "complete",
                    toolName = null,
                    toolArguments = null,
                    toolResult = null,
                    toolStatus = null
                )
            )

            val messages = repository.getMessages(SessionId("session-1"))

            messages shouldHaveSize 1
            messages[0].toolCallMetadata.shouldBeNull()
        }
    }
}
