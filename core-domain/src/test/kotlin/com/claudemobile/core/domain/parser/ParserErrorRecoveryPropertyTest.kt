package com.claudemobile.core.domain.parser

import com.claudemobile.core.domain.model.OutputEvent
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

/**
 * Property 5: Parser Error Recovery
 *
 * **Validates: Requirements 14.4**
 *
 * For any byte sequence containing an invalid frame followed by a valid frame,
 * the Output_Parser SHALL emit exactly one error event for the invalid portion
 * and SHALL successfully parse the subsequent valid frame, producing the expected events.
 *
 * Feature: android-claude-termux-client, Property 5: Parser Error Recovery
 */
class ParserErrorRecoveryPropertyTest : DescribeSpec({

    describe("Property 5: Parser Error Recovery") {

        it("emits exactly one error for invalid frame and correctly parses subsequent valid frame") {
            checkAll(PropTestConfig(iterations = 100), invalidFrameArb(), validFrameArb()) { invalidFrame, validFrame ->
                val parser = OutputParserImpl()

                // Combine invalid frame followed by valid frame
                val input = (invalidFrame.bytes + validFrame.bytes)
                val result = parser.parse(input)

                // Count error events
                val errorEvents = result.events.filterIsInstance<OutputEvent.Error>()
                val nonErrorEvents = result.events.filter { it !is OutputEvent.Error }

                // Exactly one error event for the invalid portion
                errorEvents.size shouldBe 1

                // The valid frame should produce the expected event type
                nonErrorEvents.size shouldBe 1
                when (validFrame.expectedEventType) {
                    ValidFrameType.TEXT -> nonErrorEvents[0].shouldBeInstanceOf<OutputEvent.Text>()
                    ValidFrameType.TOOL_CALL_START -> nonErrorEvents[0].shouldBeInstanceOf<OutputEvent.ToolCallStart>()
                    ValidFrameType.TOOL_CALL_RESULT -> nonErrorEvents[0].shouldBeInstanceOf<OutputEvent.ToolCallResult>()
                    ValidFrameType.TURN_COMPLETE -> nonErrorEvents[0] shouldBe OutputEvent.TurnComplete
                    ValidFrameType.PROMPT -> nonErrorEvents[0].shouldBeInstanceOf<OutputEvent.Prompt>()
                }

                // All bytes should be consumed (both frames end with newline)
                result.consumedBytes shouldBe input.size
            }
        }

        it("error event contains descriptive reason for malformed sentinels") {
            checkAll(PropTestConfig(iterations = 100), invalidFrameArb()) { invalidFrame ->
                val parser = OutputParserImpl()
                val input = invalidFrame.bytes
                val result = parser.parse(input)

                val errorEvents = result.events.filterIsInstance<OutputEvent.Error>()
                errorEvents.size shouldBe 1
                errorEvents[0].reason.length shouldBeGreaterThan 0
                errorEvents[0].rawBytes shouldBe input
            }
        }

        it("parser resynchronizes correctly after multiple invalid-then-valid sequences") {
            checkAll(
                PropTestConfig(iterations = 100),
                invalidFrameArb(),
                validFrameArb(),
                invalidFrameArb(),
                validFrameArb()
            ) { invalid1, valid1, invalid2, valid2 ->
                val parser = OutputParserImpl()

                val input = invalid1.bytes + valid1.bytes + invalid2.bytes + valid2.bytes
                val result = parser.parse(input)

                val errorEvents = result.events.filterIsInstance<OutputEvent.Error>()
                val nonErrorEvents = result.events.filter { it !is OutputEvent.Error }

                // Exactly two error events (one per invalid frame)
                errorEvents.size shouldBe 2

                // Two valid events parsed correctly
                nonErrorEvents.size shouldBe 2

                // All bytes consumed
                result.consumedBytes shouldBe input.size
            }
        }
    }
})

/**
 * Represents an invalid frame that should trigger an error event.
 */
private data class InvalidFrame(val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InvalidFrame) return false
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = bytes.contentHashCode()
}

/**
 * Represents a valid frame with its expected event type.
 */
private data class ValidFrame(
    val bytes: ByteArray,
    val expectedEventType: ValidFrameType
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ValidFrame) return false
        return bytes.contentEquals(other.bytes) && expectedEventType == other.expectedEventType
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + expectedEventType.hashCode()
        return result
    }
}

private enum class ValidFrameType {
    TEXT,
    TOOL_CALL_START,
    TOOL_CALL_RESULT,
    TURN_COMPLETE,
    PROMPT
}

/**
 * Generator for invalid frames — malformed sentinels that the parser should reject.
 *
 * These are lines that look like they intended to be sentinels but don't match
 * the expected grammar (e.g., missing closing bracket, missing success/failure).
 */
private fun invalidFrameArb(): Arb<InvalidFrame> = arbitrary {
    val toolNames = listOf("read_file", "write_file", "bash", "search", "edit_file", "list_dir")
    val toolName = Arb.element(toolNames).bind()

    val invalidPatterns = listOf(
        // tool_call_start without closing bracket
        "[tool_call_start:${toolName}_without_bracket\n",
        // tool_call_result without success/failure indicator
        "[tool_call_result:${toolName}\n",
        // tool_call_start with empty content after prefix (no closing bracket)
        "[tool_call_start:\n",
        // tool_call_result missing the status field (only has tool name, no colon-separated status)
        "[tool_call_result:${toolName}_no_status\n",
        // Incomplete turn marker
        "[turn_incomplete_marker\n",
        // tool_call_start with extra colons but no closing bracket
        "[tool_call_start:${toolName}:extra\n",
        // tool_call_result with wrong format (missing closing bracket)
        "[tool_call_result:${toolName}:success_no_bracket\n"
    )

    val pattern = Arb.element(invalidPatterns).bind()
    InvalidFrame(pattern.toByteArray(Charsets.UTF_8))
}

/**
 * Generator for valid frames — well-formed lines that the parser should handle correctly.
 *
 * Includes: plain text, proper tool-call sentinels, turn-complete markers, and prompt lines.
 */
private fun validFrameArb(): Arb<ValidFrame> = Arb.choice(
    validTextFrameArb(),
    validToolCallStartArb(),
    validToolCallResultArb(),
    validTurnCompleteArb(),
    validPromptArb()
)

private fun validTextFrameArb(): Arb<ValidFrame> = arbitrary {
    // Generate safe text content (no brackets at start, no special characters that could be sentinels)
    val words = listOf(
        "Hello", "world", "this", "is", "a", "test", "output",
        "The", "result", "was", "successful", "processing", "data",
        "function", "returns", "value", "completed", "running"
    )
    val wordCount = Arb.int(1, 6).bind()
    val text = (1..wordCount).map { Arb.element(words).bind() }.joinToString(" ")
    ValidFrame("$text\n".toByteArray(Charsets.UTF_8), ValidFrameType.TEXT)
}

private fun validToolCallStartArb(): Arb<ValidFrame> = arbitrary {
    val toolNames = listOf("read_file", "write_file", "bash", "search", "edit_file", "list_dir")
    val toolName = Arb.element(toolNames).bind()
    val args = listOf(
        "{\"path\": \"/tmp/test.txt\"}",
        "{\"cmd\": \"ls -la\"}",
        "{\"query\": \"hello\"}",
        "",
        "{\"file\": \"main.kt\", \"line\": 42}"
    )
    val arg = Arb.element(args).bind()
    ValidFrame(
        "[tool_call_start:${toolName}]${arg}\n".toByteArray(Charsets.UTF_8),
        ValidFrameType.TOOL_CALL_START
    )
}

private fun validToolCallResultArb(): Arb<ValidFrame> = arbitrary {
    val toolNames = listOf("read_file", "write_file", "bash", "search", "edit_file", "list_dir")
    val toolName = Arb.element(toolNames).bind()
    val statuses = listOf("success", "failure")
    val status = Arb.element(statuses).bind()
    val results = listOf("file contents", "done", "error: not found", "output data", "")
    val result = Arb.element(results).bind()
    ValidFrame(
        "[tool_call_result:${toolName}:${status}]${result}\n".toByteArray(Charsets.UTF_8),
        ValidFrameType.TOOL_CALL_RESULT
    )
}

private fun validTurnCompleteArb(): Arb<ValidFrame> = arbitrary {
    ValidFrame("[turn_complete]\n".toByteArray(Charsets.UTF_8), ValidFrameType.TURN_COMPLETE)
}

private fun validPromptArb(): Arb<ValidFrame> = arbitrary {
    val prompts = listOf("> ", "claude> ", "claude>")
    val prompt = Arb.element(prompts).bind()
    ValidFrame("${prompt}\n".toByteArray(Charsets.UTF_8), ValidFrameType.PROMPT)
}
