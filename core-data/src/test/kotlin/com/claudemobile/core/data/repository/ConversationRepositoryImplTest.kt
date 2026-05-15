package com.claudemobile.core.data.repository

import com.claudemobile.core.common.TimeProvider
import com.claudemobile.core.common.UuidGenerator
import com.claudemobile.core.data.transcript.ClaudeTranscriptStore
import com.claudemobile.core.domain.model.Message
import com.claudemobile.core.domain.model.MessageId
import com.claudemobile.core.domain.model.MessageRole
import com.claudemobile.core.domain.model.MessageStatus
import com.claudemobile.core.domain.model.SessionId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.flow.first

class ConversationRepositoryImplTest : FunSpec({

    test("loads sessions and visible messages from Claude transcripts") {
        val root = Files.createTempDirectory("claude-transcripts").toFile()
        val store = ClaudeTranscriptStore(root)
        val repo = ConversationRepositoryImpl(
            transcriptStore = store,
            uuidGenerator = FakeUuidGenerator(),
            timeProvider = FixedTimeProvider(),
        )

        store.transcriptFile("session-1").writeText(
            """
            {"type":"custom-title","customTitle":"From transcript","workspacePath":"/workspace","sessionId":"session-1","timestamp":"2026-05-15T10:00:00Z"}
            {"type":"user","uuid":"user-1","sessionId":"session-1","timestamp":"2026-05-15T10:00:01Z","cwd":"/root","message":{"role":"user","content":"hello"}}
            {"type":"assistant","uuid":"assistant-1","sessionId":"session-1","timestamp":"2026-05-15T10:00:02Z","cwd":"/root","message":{"id":"msg-1","role":"assistant","content":[{"type":"text","text":"hi"}]}}
            """.trimIndent(),
        )

        val sessions = repo.getSessions().first()
        sessions shouldHaveSize 1
        sessions.single().title shouldBe "From transcript"
        sessions.single().messageCount shouldBe 2

        val messages = repo.getMessages(SessionId("session-1"))
        messages.map(Message::content) shouldBe listOf("hello", "hi")
    }

    test("overlay messages disappear once the transcript catches up") {
        val root = Files.createTempDirectory("claude-transcripts").toFile()
        val store = ClaudeTranscriptStore(root)
        val repo = ConversationRepositoryImpl(
            transcriptStore = store,
            uuidGenerator = FakeUuidGenerator(),
            timeProvider = FixedTimeProvider(),
        )
        store.appendLine(
            "session-1",
            """{"type":"custom-title","customTitle":"Draft","sessionId":"session-1","timestamp":"2026-05-15T10:00:00Z"}""",
        )

        repo.insertMessage(
            Message(
                id = MessageId("overlay-user"),
                sessionId = SessionId("session-1"),
                role = MessageRole.USER,
                createdAt = Instant.parse("2026-05-15T10:00:01Z"),
                position = 0,
                content = "hello",
                status = MessageStatus.SENDING,
                toolCallMetadata = null,
            ),
        )

        repo.getMessages(SessionId("session-1")).single().id.value shouldBe "overlay-user"

        store.appendLine(
            "session-1",
            """{"type":"user","uuid":"user-1","sessionId":"session-1","timestamp":"2026-05-15T10:00:01Z","message":{"role":"user","content":"hello"}}""",
        )

        repo.getMessages(SessionId("session-1")).single().id.value shouldBe "user-1"
    }
})

private class FakeUuidGenerator : UuidGenerator {
    private var counter: Int = 0
    override fun generate(): String {
        counter += 1
        return "generated-$counter"
    }
}

private class FixedTimeProvider : TimeProvider {
    override fun now(): Instant = Instant.parse("2026-05-15T10:00:00Z")
}
