package com.claudemobile.core.data.diagnostics

import com.claudemobile.core.common.TimeProvider
import com.claudemobile.core.common.UuidGenerator
import com.claudemobile.core.data.database.dao.DiagnosticsLogDao
import com.claudemobile.core.data.database.entity.DiagnosticsLogEntity
import com.claudemobile.core.domain.repository.CredentialStore
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import java.time.Instant

class DiagnosticsRepositoryImplTest : DescribeSpec({

    lateinit var dao: DiagnosticsLogDao
    lateinit var credentialStore: CredentialStore
    lateinit var uuidGenerator: UuidGenerator
    lateinit var timeProvider: TimeProvider
    lateinit var repository: DiagnosticsRepositoryImpl

    var uuidCounter = 0

    beforeEach {
        uuidCounter = 0
        dao = mockk(relaxed = true)
        credentialStore = mockk()
        uuidGenerator = mockk {
            every { generate() } answers { "uuid-${uuidCounter++}" }
        }
        timeProvider = mockk {
            every { now() } returns Instant.ofEpochMilli(1000000L)
        }

        coEvery { credentialStore.getApiKey() } returns null

        repository = DiagnosticsRepositoryImpl(
            diagnosticsLogDao = dao,
            credentialStore = credentialStore,
            uuidGenerator = uuidGenerator,
            timeProvider = timeProvider,
        )
    }

    describe("logEvent") {

        it("inserts an entity into the DAO") {
            val entitySlot = slot<DiagnosticsLogEntity>()
            coEvery { dao.insert(capture(entitySlot)) } returns Unit

            repository.logEvent(
                sessionId = "session-1",
                eventType = "bootstrap",
                message = "Installing proot-distro",
                details = "Step 3 of 6"
            )

            val captured = entitySlot.captured
            captured.id shouldBe 0L // autoGenerate, Room assigns the real ID
            captured.sessionId shouldBe "session-1"
            captured.eventType shouldBe "bootstrap"
            captured.timestamp shouldBe 1000000L
            captured.message shouldBe "Installing proot-distro"
            captured.details shouldBe "Step 3 of 6"
        }

        it("calls deleteOldest after insert for LRU eviction") {
            repository.logEvent(
                sessionId = "session-1",
                eventType = "bridge_lifecycle",
                message = "Process started"
            )

            coVerify(exactly = 1) { dao.deleteOldest() }
        }

        it("supports null sessionId for global events") {
            val entitySlot = slot<DiagnosticsLogEntity>()
            coEvery { dao.insert(capture(entitySlot)) } returns Unit

            repository.logEvent(
                sessionId = null,
                eventType = "crash",
                message = "Unexpected error",
                details = "java.lang.NullPointerException"
            )

            entitySlot.captured.sessionId shouldBe null
        }

        it("supports null details") {
            val entitySlot = slot<DiagnosticsLogEntity>()
            coEvery { dao.insert(capture(entitySlot)) } returns Unit

            repository.logEvent(
                sessionId = "session-1",
                eventType = "bootstrap",
                message = "Check complete"
            )

            entitySlot.captured.details shouldBe null
        }
    }

    describe("getSessionLogs") {

        it("returns mapped domain entries from DAO flow") {
            val entities = listOf(
                DiagnosticsLogEntity(
                    id = 1L,
                    sessionId = "session-1",
                    eventType = "bootstrap",
                    timestamp = 1000L,
                    message = "Started",
                    details = null
                ),
                DiagnosticsLogEntity(
                    id = 2L,
                    sessionId = "session-1",
                    eventType = "stderr",
                    timestamp = 2000L,
                    message = "Error output",
                    details = "line detail"
                )
            )
            every { dao.getBySessionId("session-1") } returns flowOf(entities)

            val result = repository.getSessionLogs("session-1").first()

            result shouldHaveSize 2
            result[0].id shouldBe "1"
            result[0].eventType shouldBe "bootstrap"
            result[0].message shouldBe "Started"
            result[0].details shouldBe null
            result[1].id shouldBe "2"
            result[1].eventType shouldBe "stderr"
            result[1].details shouldBe "line detail"
        }
    }

    describe("exportRedacted") {

        it("produces text with session header and entries") {
            val entities = listOf(
                DiagnosticsLogEntity(
                    id = 1L,
                    sessionId = "session-1",
                    eventType = "bootstrap",
                    timestamp = 5000L,
                    message = "Bootstrap started",
                    details = null
                )
            )
            every { dao.getBySessionId("session-1") } returns flowOf(entities)

            val result = repository.exportRedacted("session-1")

            result shouldContain "=== Diagnostics Log ==="
            result shouldContain "Session: session-1"
            result shouldContain "[5000] [bootstrap] Bootstrap started"
        }

        it("includes details when present") {
            val entities = listOf(
                DiagnosticsLogEntity(
                    id = 1L,
                    sessionId = "session-1",
                    eventType = "crash",
                    timestamp = 5000L,
                    message = "App crashed",
                    details = "NullPointerException at line 42"
                )
            )
            every { dao.getBySessionId("session-1") } returns flowOf(entities)

            val result = repository.exportRedacted("session-1")

            result shouldContain "Details: NullPointerException at line 42"
        }

        it("redacts API key from exported text") {
            val apiKey = "sk-ant-api03-secret-key-12345"
            coEvery { credentialStore.getApiKey() } returns apiKey

            val entities = listOf(
                DiagnosticsLogEntity(
                    id = 1L,
                    sessionId = "session-1",
                    eventType = "stderr",
                    timestamp = 5000L,
                    message = "Error: invalid key sk-ant-api03-secret-key-12345",
                    details = "Key used: sk-ant-api03-secret-key-12345"
                )
            )
            every { dao.getBySessionId("session-1") } returns flowOf(entities)

            val result = repository.exportRedacted("session-1")

            result shouldNotContain apiKey
            result shouldContain "[REDACTED]"
        }

        it("redacts API key appearing multiple times") {
            val apiKey = "sk-test-key"
            coEvery { credentialStore.getApiKey() } returns apiKey

            val entities = listOf(
                DiagnosticsLogEntity(
                    id = 1L,
                    sessionId = "session-1",
                    eventType = "stderr",
                    timestamp = 5000L,
                    message = "key=sk-test-key other=sk-test-key",
                    details = null
                )
            )
            every { dao.getBySessionId("session-1") } returns flowOf(entities)

            val result = repository.exportRedacted("session-1")

            result shouldNotContain apiKey
            result shouldContain "key=[REDACTED] other=[REDACTED]"
        }

        it("does not redact when no API key is stored") {
            coEvery { credentialStore.getApiKey() } returns null

            val entities = listOf(
                DiagnosticsLogEntity(
                    id = 1L,
                    sessionId = "session-1",
                    eventType = "bootstrap",
                    timestamp = 5000L,
                    message = "All good",
                    details = null
                )
            )
            every { dao.getBySessionId("session-1") } returns flowOf(entities)

            val result = repository.exportRedacted("session-1")

            result shouldContain "All good"
        }
    }

    describe("stderr ring buffer") {

        it("keeps only last 256 stderr lines per session") {
            // Log 300 stderr events
            for (i in 1..300) {
                every { timeProvider.now() } returns Instant.ofEpochMilli(i.toLong())
                repository.logEvent(
                    sessionId = "session-1",
                    eventType = "stderr",
                    message = "stderr line $i"
                )
            }

            // Export should show only last 256 lines
            coEvery { credentialStore.getApiKey() } returns null
            every { dao.getBySessionId("session-1") } returns flowOf(emptyList())

            val result = repository.exportRedacted("session-1")

            result shouldContain "=== Stderr (last 256 lines) ==="
            result shouldContain "stderr line 300"
            result shouldContain "stderr line 45" // 300 - 256 + 1 = 45
            result shouldNotContain "stderr line 44" // should have been evicted
        }

        it("does not include stderr section when no stderr events logged") {
            coEvery { credentialStore.getApiKey() } returns null
            every { dao.getBySessionId("session-1") } returns flowOf(emptyList())

            repository.logEvent(
                sessionId = "session-1",
                eventType = "bootstrap",
                message = "Not stderr"
            )

            val result = repository.exportRedacted("session-1")

            result shouldNotContain "=== Stderr"
        }

        it("maintains separate buffers per session") {
            repository.logEvent("session-1", "stderr", "line for session 1")
            repository.logEvent("session-2", "stderr", "line for session 2")

            coEvery { credentialStore.getApiKey() } returns null
            every { dao.getBySessionId("session-1") } returns flowOf(emptyList())
            every { dao.getBySessionId("session-2") } returns flowOf(emptyList())

            val result1 = repository.exportRedacted("session-1")
            val result2 = repository.exportRedacted("session-2")

            result1 shouldContain "line for session 1"
            result1 shouldNotContain "line for session 2"
            result2 shouldContain "line for session 2"
            result2 shouldNotContain "line for session 1"
        }
    }

    describe("clearOldEntries") {

        it("delegates to DAO deleteOldest") {
            repository.clearOldEntries()

            coVerify(exactly = 1) { dao.deleteOldest() }
        }
    }
})
