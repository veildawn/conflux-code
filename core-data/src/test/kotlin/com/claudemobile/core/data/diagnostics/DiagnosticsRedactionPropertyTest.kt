package com.claudemobile.core.data.diagnostics

import com.claudemobile.core.common.TimeProvider
import com.claudemobile.core.common.UuidGenerator
import com.claudemobile.core.data.database.dao.DiagnosticsLogDao
import com.claudemobile.core.data.database.entity.DiagnosticsLogEntity
import com.claudemobile.core.domain.repository.CredentialStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldNotContain
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import java.time.Instant

/**
 * Property-based test for Diagnostics Log Redaction.
 *
 * **Validates: Requirements 13.5**
 *
 * Property 16: For any diagnostics log text containing an API key and any API key value,
 * the exported/shared text should not contain any occurrence of that API key.
 */
class DiagnosticsRedactionPropertyTest : FunSpec({

    test("Feature: android-claude-termux-client, Property 16: Diagnostics log redaction") {
        checkAll(
            PropTestConfig(iterations = 100),
            Arb.string(1..64),       // API key (non-empty, various lengths)
            Arb.string(0..100),      // prefix text before key injection
            Arb.string(0..100),      // suffix text after key injection
            Arb.int(1..5)            // number of times the key appears
        ) { apiKey, prefix, suffix, repetitions ->
            // Build log message with the API key embedded at random positions
            val logMessage = buildString {
                append(prefix)
                repeat(repetitions) {
                    append(apiKey)
                    if (it < repetitions - 1) append(" ")
                }
                append(suffix)
            }

            // Set up mocks
            val dao = mockk<DiagnosticsLogDao>(relaxed = true)
            val credentialStore = mockk<CredentialStore>()
            val uuidGenerator = mockk<UuidGenerator> {
                every { generate() } returns "test-uuid"
            }
            val timeProvider = mockk<TimeProvider> {
                every { now() } returns Instant.ofEpochMilli(1000L)
            }

            coEvery { credentialStore.getApiKey() } returns apiKey

            val entities = listOf(
                DiagnosticsLogEntity(
                    id = 1L,
                    sessionId = "session-1",
                    eventType = "stderr",
                    timestamp = 1000L,
                    message = logMessage,
                    details = "Detail with key: $apiKey embedded"
                )
            )
            every { dao.getBySessionId("session-1") } returns flowOf(entities)

            val repository = DiagnosticsRepositoryImpl(
                diagnosticsLogDao = dao,
                credentialStore = credentialStore,
                uuidGenerator = uuidGenerator,
                timeProvider = timeProvider,
            )

            // Exercise: export the redacted diagnostics
            val exported = repository.exportRedacted("session-1")

            // Verify: the exported text must never contain the API key
            exported shouldNotContain apiKey
        }
    }
})
