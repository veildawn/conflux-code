package com.claudemobile.core.domain.parser

import com.claudemobile.core.domain.model.OutputEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll

/**
 * Property 7: Parser/Printer Round-Trip (Bytes Direction)
 *
 * **Validates: Requirements 14.3**
 *
 * For any byte stream `b` accepted by OutputParser without emitting an Error event,
 * parsing `b` to events `e`, printing `e` via PrettyPrinter's `eventsToBytes(e)` to `b'`,
 * and parsing `b'` yields events equivalent to `e`.
 *
 * Feature: android-claude-termux-client, Property 7: Parser/Printer round-trip (bytes direction)
 */
class ParserPrinterBytesRoundTripPropertyTest : FunSpec({

    /**
     * Event equivalence: compare events ignoring StyleHints for simplicity.
     * Text events are compared by content only.
     * ToolCallStart/ToolCallResult/Prompt/TurnComplete are compared by their semantic fields.
     */
    fun eventEquivalent(a: OutputEvent, b: OutputEvent): Boolean {
        return when {
            a is OutputEvent.Text && b is OutputEvent.Text ->
                a.content == b.content
            a is OutputEvent.ToolCallStart && b is OutputEvent.ToolCallStart ->
                a.toolName == b.toolName && a.arguments == b.arguments
            a is OutputEvent.ToolCallResult && b is OutputEvent.ToolCallResult ->
                a.toolName == b.toolName && a.result == b.result && a.success == b.success
            a is OutputEvent.Prompt && b is OutputEvent.Prompt ->
                a.text == b.text
            a is OutputEvent.TurnComplete && b is OutputEvent.TurnComplete -> true
            a is OutputEvent.Error && b is OutputEvent.Error ->
                a.reason == b.reason
            else -> false
        }
    }

    fun eventsEquivalent(a: List<OutputEvent>, b: List<OutputEvent>): Boolean {
        if (a.size != b.size) return false
        return a.zip(b).all { (ea, eb) -> eventEquivalent(ea, eb) }
    }

    // --- Generators for valid byte streams conforming to parser grammar ---

    // Generate safe text content: printable ASCII without sentinel-like patterns
    val safeTextChar = Arb.of(
        ('a'..'z').toList() + ('A'..'Z').toList() + ('0'..'9').toList() +
            listOf(' ', ',', '.', '!', '?', '-', '(', ')', '{', '}', ':', ';', '"', '\'', '/', '+', '=')
    )

    // Generate safe first character for a line (avoids '[' which triggers sentinel detection)
    val safeFirstChar = Arb.of(
        ('a'..'z').toList() + ('A'..'Z').toList() + ('0'..'9').toList() +
            listOf(' ', ',', '.', '!', '?', '-', '(', ')', '{', '}', '"', '\'', '/', '+', '=')
    )

    // Generate safe text that won't match prompt or sentinel patterns
    val safeText: Arb<String> = arbitrary {
        val length = Arb.int(2, 60).bind()
        val text = buildString {
            // First char avoids triggering sentinel/prompt patterns
            append(safeFirstChar.bind())
            repeat(length - 1) {
                append(safeTextChar.bind())
            }
        }
        // Safety: ensure it doesn't match prompt regex
        val stripped = text.trimEnd()
        if (stripped.matches(Regex("""^\w*>\s?$""")) ||
            stripped == "[turn_complete]" ||
            stripped.startsWith("[tool_call_")
        ) {
            "safe $text"
        } else {
            text
        }
    }

    // Generate valid tool names: alphanumeric with underscores, non-empty
    val toolNameChar = Arb.of(('a'..'z').toList() + ('0'..'9').toList() + listOf('_'))

    val toolName: Arb<String> = arbitrary {
        val length = Arb.int(1, 20).bind()
        buildString {
            // First char must be a letter
            append(Arb.of(('a'..'z').toList()).bind())
            repeat(length - 1) {
                append(toolNameChar.bind())
            }
        }
    }

    // Generate tool arguments (safe text that doesn't contain newlines or sentinel patterns)
    val toolArguments: Arb<String> = arbitrary {
        val length = Arb.int(0, 40).bind()
        if (length == 0) ""
        else buildString {
            repeat(length) {
                append(safeTextChar.bind())
            }
        }
    }

    // Generate a valid text line (ends with \n, no sentinel patterns)
    val textLine: Arb<ByteArray> = arbitrary {
        val text = safeText.bind()
        "$text\n".toByteArray(Charsets.UTF_8)
    }

    // Generate a valid tool_call_start line
    val toolCallStartLine: Arb<ByteArray> = arbitrary {
        val name = toolName.bind()
        val args = toolArguments.bind()
        "[tool_call_start:$name]$args\n".toByteArray(Charsets.UTF_8)
    }

    // Generate a valid tool_call_result line
    val toolCallResultLine: Arb<ByteArray> = arbitrary {
        val name = toolName.bind()
        val success = Arb.boolean().bind()
        val result = toolArguments.bind()
        val successStr = if (success) "success" else "failure"
        "[tool_call_result:$name:$successStr]$result\n".toByteArray(Charsets.UTF_8)
    }

    // Generate a turn_complete marker
    val turnCompleteLine: Arb<ByteArray> = arbitrary {
        "[turn_complete]\n".toByteArray(Charsets.UTF_8)
    }

    // Generate a valid line (any of the above types, excluding prompt to avoid ambiguity)
    val validLine: Arb<ByteArray> = Arb.choice(
        textLine,
        toolCallStartLine,
        toolCallResultLine,
        turnCompleteLine
    )

    // Generate a valid byte stream (sequence of valid lines)
    val validByteStream: Arb<ByteArray> = arbitrary {
        val lineCount = Arb.int(1, 8).bind()
        val lines = Arb.list(validLine, lineCount..lineCount).bind()
        lines.fold(byteArrayOf()) { acc, line -> acc + line }
    }

    test("Feature: android-claude-termux-client, Property 7: Parser/Printer round-trip (bytes direction)") {
        checkAll(PropTestConfig(iterations = 100), validByteStream) { bytes ->
            val parser = OutputParserImpl()
            val printer = PrettyPrinterImpl()

            // Step 1: Parse original bytes → events `e`
            val firstParseResult = parser.parse(bytes)
            val firstEvents = firstParseResult.events

            // Step 2: Skip if any Error events are present
            if (firstEvents.any { it is OutputEvent.Error }) return@checkAll
            if (firstEvents.isEmpty()) return@checkAll

            // Step 3: Serialize `e` with PrettyPrinterImpl.eventsToBytes() → `b'`
            val printedBytes = printer.eventsToBytes(firstEvents)

            // Step 4: Parse `b'` with a fresh OutputParserImpl → events `e'`
            val freshParser = OutputParserImpl()
            val secondParseResult = freshParser.parse(printedBytes)
            val secondEvents = secondParseResult.events

            // Step 5: Verify `e'` has no errors
            secondEvents.any { it is OutputEvent.Error } shouldBe false

            // Step 6: Verify `e'` equals `e` (using event equivalence - compare ignoring StyleHints)
            eventsEquivalent(firstEvents, secondEvents) shouldBe true
        }
    }
})
