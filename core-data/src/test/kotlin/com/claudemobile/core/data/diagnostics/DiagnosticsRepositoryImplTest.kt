package com.claudemobile.core.data.diagnostics

import com.claudemobile.core.common.TimeProvider
import com.claudemobile.core.common.UuidGenerator
import com.claudemobile.core.domain.repository.CredentialStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.flow.first

class DiagnosticsRepositoryImplTest : FunSpec({

    test("persists entries and exports with secret redaction") {
        val logFile = Files.createTempDirectory("diagnostics").resolve("logs.jsonl").toFile()
        val repo = DiagnosticsRepositoryImpl(
            logFile = logFile,
            credentialStore = FakeCredentialStore("secret-key"),
            uuidGenerator = FakeUuidGenerator(),
            timeProvider = FixedTimeProvider(),
            providerProfileSnapshot = ProviderProfileSnapshot { emptyList() },
        )

        repo.logEvent(
            sessionId = "session-1",
            eventType = "stderr",
            message = "failed with secret-key",
            details = "details secret-key",
        )

        val logs = repo.getSessionLogs("session-1").first()
        logs.single().eventType shouldBe "stderr"

        val exported = repo.exportRedacted("session-1")
        exported shouldContain "[REDACTED]"
        exported shouldNotContain "secret-key"
    }
})

private class FakeCredentialStore(
    private val apiKey: String?,
) : CredentialStore {
    override suspend fun getApiKey(): String? = apiKey
    override suspend fun setApiKey(key: String) = Unit
    override suspend fun hasApiKey(): Boolean = apiKey != null
    override suspend fun deleteApiKey() = Unit
    override fun getMaskedApiKey(): String? = apiKey?.let { "••••${it.takeLast(4)}" }
}

private class FakeUuidGenerator : UuidGenerator {
    private var counter: Int = 0
    override fun generate(): String {
        counter += 1
        return "diag-$counter"
    }
}

private class FixedTimeProvider : TimeProvider {
    override fun now(): Instant = Instant.parse("2026-05-15T10:00:00Z")
}
