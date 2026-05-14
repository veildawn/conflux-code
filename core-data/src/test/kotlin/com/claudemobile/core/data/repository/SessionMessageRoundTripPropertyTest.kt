package com.claudemobile.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.claudemobile.core.common.TimeProvider
import com.claudemobile.core.common.UuidGenerator
import com.claudemobile.core.data.database.AppDatabase
import com.claudemobile.core.data.database.dao.MessageDao
import com.claudemobile.core.data.database.dao.SessionDao
import com.claudemobile.core.domain.model.Message
import com.claudemobile.core.domain.model.MessageId
import com.claudemobile.core.domain.model.MessageRole
import com.claudemobile.core.domain.model.MessageStatus
import com.claudemobile.core.domain.model.Session
import com.claudemobile.core.domain.model.SessionId
import com.claudemobile.core.domain.model.ToolCallMetadata
import com.claudemobile.core.domain.model.ToolCallStatus
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Property-based test for Session/Message persistence round-trip using Room in-memory database.
 *
 * Tags: Feature: android-claude-termux-client, Property 10: Session/Message persistence round-trip
 *
 * **Validates: Requirements 5.10**
 *
 * For any valid Session `s` and associated Message list `ms`, writing through
 * ConversationRepository and reading back should yield the same values
 * (id, title, workspacePath, timestamps, role, position, content fields all equal).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SessionMessageRoundTripPropertyTest {

    private lateinit var database: AppDatabase
    private lateinit var sessionDao: SessionDao
    private lateinit var messageDao: MessageDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sessionDao = database.sessionDao()
        messageDao = database.messageDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    /**
     * Property 10: Session/Message persistence round-trip
     *
     * **Validates: Requirements 5.10**
     *
     * For any valid Session and associated Message list, writing through
     * ConversationRepository and reading back yields the same values for all fields.
     */
    @Test
    fun `Feature android-claude-termux-client, Property 10 Session-Message persistence round-trip`() = runTest {
        checkAll(PropTestConfig(iterations = 100), arbRoundTripTestData()) { testData ->
            // Clear tables for each iteration to ensure isolation
            database.clearAllTables()

            val uuidGenerator = mockk<UuidGenerator>()
            val timeProvider = mockk<TimeProvider>()
            every { uuidGenerator.generate() } returns testData.session.id.value
            every { timeProvider.now() } returns testData.session.createdAt

            val repository = ConversationRepositoryImpl(
                sessionDao = sessionDao,
                messageDao = messageDao,
                uuidGenerator = uuidGenerator,
                timeProvider = timeProvider,
            )

            // Write session via createSession
            val createdSession = repository.createSession(
                testData.session.title,
                testData.session.workspacePath
            )

            // Read session back
            val readSession = repository.getSession(createdSession.id)

            // Verify session round-trip
            readSession shouldBe createdSession
            readSession!!.id shouldBe createdSession.id
            readSession.title shouldBe testData.session.title
            readSession.workspacePath shouldBe testData.session.workspacePath
            readSession.createdAt shouldBe testData.session.createdAt
            readSession.lastActivityAt shouldBe testData.session.createdAt

            // Write messages associated with this session
            val messagesWithSessionId = testData.messages.map { msg ->
                msg.copy(sessionId = createdSession.id)
            }

            messagesWithSessionId.forEach { message ->
                repository.insertMessage(message)
            }

            // Read messages back
            val readMessages = repository.getMessages(createdSession.id)

            // Verify message count
            readMessages shouldHaveSize messagesWithSessionId.size

            // Verify each message round-trip (messages come back sorted by position)
            val sortedWritten = messagesWithSessionId.sortedBy { it.position }
            readMessages.forEachIndexed { index, readMsg ->
                val writtenMsg = sortedWritten[index]
                readMsg.id shouldBe writtenMsg.id
                readMsg.sessionId shouldBe writtenMsg.sessionId
                readMsg.role shouldBe writtenMsg.role
                readMsg.createdAt shouldBe writtenMsg.createdAt
                readMsg.position shouldBe writtenMsg.position
                readMsg.content shouldBe writtenMsg.content
                readMsg.status shouldBe writtenMsg.status
                readMsg.toolCallMetadata shouldBe writtenMsg.toolCallMetadata
            }
        }
    }
}

// --- Generators ---

/**
 * Test data pairing a Session with its associated Messages for round-trip property testing.
 */
private data class RoundTripTestData(
    val session: Session,
    val messages: List<Message>
)

/**
 * Generates a Session with a list of 0-5 associated Messages.
 * Messages have unique IDs and sequential positions.
 */
private fun arbRoundTripTestData(): Arb<RoundTripTestData> = arbitrary {
    val sessionId = arbRoundTripAlphanumericId(8..24).bind()
    val title = arbRoundTripSafeString(1..80).bind()
    val workspacePath = arbRoundTripWorkspacePath().bind()
    val createdAtMillis = Arb.long(1_000_000_000_000L..3_000_000_000_000L).bind()

    val session = Session(
        id = SessionId(sessionId),
        title = title,
        workspacePath = workspacePath,
        createdAt = Instant.ofEpochMilli(createdAtMillis),
        lastActivityAt = Instant.ofEpochMilli(createdAtMillis),
        messageCount = 0
    )

    val messageCount = Arb.int(0..5).bind()
    val messages = (0 until messageCount).map { position ->
        val msgId = arbRoundTripAlphanumericId(8..24).bind()
        val role = Arb.enum<MessageRole>().bind()
        val msgCreatedAtMillis = Arb.long(createdAtMillis..4_000_000_000_000L).bind()
        val content = arbRoundTripSafeString(0..200).bind()
        val status = Arb.enum<MessageStatus>().bind()
        val hasToolMetadata = Arb.boolean().bind()
        val toolMetadata = if (hasToolMetadata) {
            val toolName = arbRoundTripSafeString(1..30).bind()
            val arguments = arbRoundTripSafeString(0..100).bind()
            val hasResult = Arb.boolean().bind()
            val result = if (hasResult) arbRoundTripSafeString(0..100).bind() else null
            val toolStatus = Arb.enum<ToolCallStatus>().bind()
            ToolCallMetadata(
                toolName = toolName,
                arguments = arguments,
                result = result,
                status = toolStatus
            )
        } else null

        Message(
            id = MessageId(msgId),
            sessionId = SessionId(sessionId),
            role = role,
            createdAt = Instant.ofEpochMilli(msgCreatedAtMillis),
            position = position,
            content = content,
            status = status,
            toolCallMetadata = toolMetadata
        )
    }

    RoundTripTestData(session, messages)
}

/**
 * Generates alphanumeric ID strings that are guaranteed non-blank.
 */
private fun arbRoundTripAlphanumericId(range: IntRange): Arb<String> = arbitrary {
    val length = Arb.int(range).bind()
    val charPool = ('a'..'z') + ('A'..'Z') + ('0'..'9')
    buildString {
        repeat(length) {
            append(charPool[Arb.int(0 until charPool.size).bind()])
        }
    }
}

/**
 * Generates strings safe for storage (no control characters that could cause issues).
 */
private fun arbRoundTripSafeString(range: IntRange): Arb<String> = arbitrary {
    val length = Arb.int(range).bind()
    val charPool = ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf(' ', '-', '_', '.', ',', ':', '(', ')', '[', ']')
    buildString {
        repeat(length) {
            append(charPool[Arb.int(0 until charPool.size).bind()])
        }
    }
}

/**
 * Generates workspace path strings.
 */
private fun arbRoundTripWorkspacePath(): Arb<String> = arbitrary {
    val segments = Arb.int(1..4).bind()
    val pathChars = ('a'..'z') + ('0'..'9') + listOf('-', '_')
    buildString {
        append("/")
        repeat(segments) { idx ->
            if (idx > 0) append("/")
            val segLen = Arb.int(1..12).bind()
            repeat(segLen) {
                append(pathChars[Arb.int(0 until pathChars.size).bind()])
            }
        }
    }
}
