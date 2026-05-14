package com.claudemobile.core.data.repository

import app.cash.turbine.test
import com.claudemobile.core.common.TimeProvider
import com.claudemobile.core.common.UuidGenerator
import com.claudemobile.core.data.database.dao.MessageDao
import com.claudemobile.core.data.database.dao.SessionDao
import com.claudemobile.core.data.database.entity.SessionEntity
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeSortedWith
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf

/**
 * Property-based test for Session list sorting invariant.
 *
 * **Validates: Requirements 5.6**
 *
 * For any database state containing multiple Sessions, the list returned by
 * `getAllSessionsFlow()` should be strictly ordered by `lastActivityAt` descending.
 */
class SessionListSortingPropertyTest : FunSpec({

    tags(
        io.kotest.core.Tag("Feature: android-claude-termux-client"),
        io.kotest.core.Tag("Property 11: Session list sorting invariant")
    )

    /**
     * Property 11: Session list sorting invariant
     *
     * **Validates: Requirements 5.6**
     *
     * For any database state containing multiple Sessions with random lastActivityAt
     * timestamps, the list returned by getAllSessionsFlow() (via the repository's
     * getSessions()) should be strictly ordered by lastActivityAt descending.
     *
     * The DAO's SQL query guarantees ORDER BY last_activity_at DESC. This test verifies
     * that the repository layer correctly preserves that ordering when mapping entities
     * to domain models.
     */
    test("Feature: android-claude-termux-client, Property 11: Session list sorting invariant") {
        checkAll(PropTestConfig(iterations = 100), arbSessionEntityList()) { sessionEntities ->
            val sessionDao = mockk<SessionDao>()
            val messageDao = mockk<MessageDao>()
            val uuidGenerator = mockk<UuidGenerator>()
            val timeProvider = mockk<TimeProvider>()

            // Simulate the DAO returning sessions sorted by lastActivityAt DESC
            // (as the SQL query ORDER BY last_activity_at DESC guarantees)
            val sortedEntities = sessionEntities.sortedByDescending { it.lastActivityAt }

            every { sessionDao.getAllSessionsFlow() } returns flowOf(sortedEntities)

            val repository = ConversationRepositoryImpl(
                sessionDao = sessionDao,
                messageDao = messageDao,
                uuidGenerator = uuidGenerator,
                timeProvider = timeProvider,
            )

            // Verify the returned sessions maintain descending lastActivityAt order
            repository.getSessions().test {
                val sessions = awaitItem()

                sessions.size shouldBeGreaterThanOrEqual 2

                // Verify strict descending order by lastActivityAt
                sessions.shouldBeSortedWith(compareByDescending { it.lastActivityAt })

                cancelAndConsumeRemainingEvents()
            }
        }
    }
})

// --- Generators ---

/**
 * Generates a list of 2-10 SessionEntity objects with random lastActivityAt timestamps.
 * Each session has a unique ID to simulate a realistic database state.
 */
private fun arbSessionEntityList(): Arb<List<SessionEntity>> = arbitrary {
    val count = Arb.int(2..10).bind()
    val sessions = mutableListOf<SessionEntity>()

    repeat(count) { index ->
        val id = "session-${index}-${Arb.long(1000L..999999L).bind()}"
        val createdAt = Arb.long(1_000_000_000_000L..3_000_000_000_000L).bind()
        val lastActivityAt = Arb.long(createdAt..4_000_000_000_000L).bind()
        val messageCount = Arb.int(0..100).bind()

        sessions.add(
            SessionEntity(
                id = id,
                title = "Session $index",
                workspacePath = "/workspace/$index",
                createdAt = createdAt,
                lastActivityAt = lastActivityAt,
                messageCount = messageCount
            )
        )
    }

    sessions
}
