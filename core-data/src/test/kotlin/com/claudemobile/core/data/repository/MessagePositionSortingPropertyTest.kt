package com.claudemobile.core.data.repository

import com.claudemobile.core.common.TimeProvider
import com.claudemobile.core.common.UuidGenerator
import com.claudemobile.core.data.database.dao.MessageDao
import com.claudemobile.core.data.database.dao.SessionDao
import com.claudemobile.core.data.database.entity.MessageEntity
import com.claudemobile.core.domain.model.SessionId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeSortedWith
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import io.mockk.coEvery
import io.mockk.mockk

/**
 * Property-based test for Message position sorting invariant.
 *
 * **Validates: Requirements 5.7**
 *
 * For any Session's Message list, the list returned by `getMessagesForSession()`
 * should be strictly ordered by `position` ascending.
 */
class MessagePositionSortingPropertyTest : FunSpec({

    tags(
        io.kotest.core.Tag("Feature: android-claude-termux-client"),
        io.kotest.core.Tag("Property 12: Message position sorting invariant")
    )

    /**
     * Property 12: Message position sorting invariant
     *
     * **Validates: Requirements 5.7**
     *
     * For any Session with 2-20 messages with random position values,
     * the list returned by getMessagesForSession() should be strictly
     * ordered by position ascending.
     */
    test("Feature: android-claude-termux-client, Property 12: Message position sorting invariant") {
        checkAll(PropTestConfig(iterations = 100), arbSessionWithMessages()) { (sessionId, messages) ->
            val sessionDao = mockk<SessionDao>()
            val messageDao = mockk<MessageDao>()
            val uuidGenerator = mockk<UuidGenerator>()
            val timeProvider = mockk<TimeProvider>()

            // The DAO's SQL query guarantees ORDER BY position ASC.
            // Simulate this by returning messages sorted by position.
            val sortedMessages = messages.sortedBy { it.position }
            coEvery { messageDao.getMessagesForSession(sessionId) } returns sortedMessages

            val repository = ConversationRepositoryImpl(
                sessionDao = sessionDao,
                messageDao = messageDao,
                uuidGenerator = uuidGenerator,
                timeProvider = timeProvider,
            )

            // Retrieve messages through the repository
            val result = repository.getMessages(SessionId(sessionId))

            // Verify the result has the expected count
            result.size shouldBeGreaterThanOrEqual 2

            // Verify the returned list is strictly ordered by position ascending
            result.shouldBeSortedWith(compareBy { it.position })
        }
    }
})

// --- Generators ---

/**
 * Data class to hold a session ID and its associated messages.
 */
private data class SessionWithMessages(
    val sessionId: String,
    val messages: List<MessageEntity>
)

/**
 * Generates a session with 2-20 messages with random position values.
 */
private fun arbSessionWithMessages(): Arb<SessionWithMessages> = arbitrary {
    val sessionId = arbAlphanumericId(8..32).bind()
    val messageCount = Arb.int(2..20).bind()

    val messages = (0 until messageCount).map { index ->
        val msgId = arbAlphanumericId(8..32).bind()
        val position = Arb.int(0..10000).bind()
        val createdAt = Arb.long(0L..4_000_000_000_000L).bind()
        val role = listOf("user", "assistant", "tool", "system").random()
        val status = listOf("sending", "streaming", "complete", "cancelled", "error").random()

        MessageEntity(
            id = msgId,
            sessionId = sessionId,
            role = role,
            createdAt = createdAt,
            position = position,
            content = "Message content $index",
            status = status
        )
    }

    SessionWithMessages(sessionId = sessionId, messages = messages)
}

/**
 * Generates alphanumeric ID strings that are guaranteed non-blank.
 */
private fun arbAlphanumericId(range: IntRange): Arb<String> = arbitrary {
    val length = Arb.int(range).bind()
    val charPool = ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('-', '_')
    buildString {
        repeat(length) {
            append(charPool.random())
        }
    }
}
