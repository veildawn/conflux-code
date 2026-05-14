package com.claudemobile.core.domain.parser

import com.claudemobile.core.domain.model.OutputEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.core.test.TestCaseOrder
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
 * Property 2: Parser/Printer Round-Trip
 *
 * **Validates: Requirements 14.2, 14.3**
 *
 * For any byte stream `b` that the Output_Parser accepts without emitting an `error` event,
 * parsing `b` to events `e`, serializing `e` via the Pretty_Printer to `b'`, and parsing `b'`
 * SHALL yield events equivalent to `e` under the defined event equivalence relation.
 *
 * Feature: android-claude-termux-client, Property 2: Parser/Printer Round-Trip
 */
class ParserPrinterRoundTripPropertyTest : FunSpec({

    tags(
        io.kotest.core.Tag("Feature: android-claude-termux-client"),
        io.kotest.core.Tag("Property 2: Parser/Printer Round-Trip")
    )

    testOrder = TestCaseOrder.Sequential

    /**
     * Event equivalence: compare events ignoring rawBytes in Error events,
     * and comparing byte array contents for Output events.
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
                a.reason == b.reason // ignore rawBytes
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

    val safeText: Arb<String> = arbitrary { rs ->
        val length = Arb.int(1, 80).bind()
        buildString {
            repeat(length) {
                append(safeTextChar.bind())
            }
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
        val length = Arb.int(0, 60).bind()
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

    // Generate a valid prompt line
    val promptLine: Arb<ByteArray> = arbitrary {
        val prefix = Arb.of(listOf("", "claude", "user", "cmd")).bind()
        val trailing = Arb.of(listOf("> ", ">")).bind()
        "$prefix$trailing\n".toByteArray(Charsets.UTF_8)
    }

    // Generate a turn_complete marker
    val turnCompleteLine: Arb<ByteArray> = arbitrary {
        "[turn_complete]\n".toByteArray(Charsets.UTF_8)
    }

    // Generate a valid line (any of the above types)
    val validLine: Arb<ByteArray> = Arb.choice(
        textLine,
        toolCallStartLine,
        toolCallResultLine,
        promptLine,
        turnCompleteLine
    )

    // Generate a valid byte stream (sequence of valid lines)
    val validByteStream: Arb<ByteArray> = arbitrary {
        val lineCount = Arb.int(1, 10).bind()
        val lines = Arb.list(validLine, lineCount..lineCount).bind()
        lines.fold(byteArrayOf()) { acc, line -> acc + line }
    }

    test("Parser/Printer round-trip: parse(b) -> events e -> print(e) -> b' -> parse(b') yields equivalent events") {
        val parser = OutputParserImpl()
        val printer = PrettyPrinterImpl()

        checkAll(PropTestConfig(iterations = 200), validByteStream) { bytes ->
            // Reset parser state for each iteration
            parser.reset()

            // Step 1: Parse original bytes
            val firstParseResult = parser.parse(bytes)
            val firstEvents = firstParseResult.events

            // Only test the round-trip property for streams that parse without errors
            val hasErrors = firstEvents.any { it is OutputEvent.Error }
            if (!hasErrors && firstEvents.isNotEmpty()) {
                // Step 2: Print events back to bytes
                val printedBytes = printer.eventsToBytes(firstEvents)

                // Step 3: Parse the printed bytes
                parser.reset()
                val secondParseResult = parser.parse(printedBytes)
                val secondEvents = secondParseResult.events

                // Step 4: Verify event equivalence
                // The second parse should not have errors either
                val secondHasErrors = secondEvents.any { it is OutputEvent.Error }
                secondHasErrors shouldBe false

                // Events should be equivalent
                eventsEquivalent(firstEvents, secondEvents) shouldBe true
            }
        }
    }

    test("Parser/Printer round-trip holds for text-only streams") {
        val parser = OutputParserImpl()
        val printer = PrettyPrinterImpl()

        checkAll(PropTestConfig(iterations = 100), Arb.list(textLine, 1..5)) { lines ->
            val bytes = lines.fold(byteArrayOf()) { acc, line -> acc + line }

            parser.reset()
            val firstResult = parser.parse(bytes)
            val firstEvents = firstResult.events

            if (firstEvents.isNotEmpty() && firstEvents.none { it is OutputEvent.Error }) {
                val printedBytes = printer.eventsToBytes(firstEvents)

                parser.reset()
                val secondResult = parser.parse(printedBytes)

                eventsEquivalent(firstEvents, secondResult.events) shouldBe true
            }
        }
    }

    test("Parser/Printer round-trip holds for tool-call streams") {
        val parser = OutputParserImpl()
        val printer = PrettyPrinterImpl()

        val toolStream: Arb<ByteArray> = arbitrary {
            val start = toolCallStartLine.bind()
            val result = toolCallResultLine.bind()
            start + result
        }

        checkAll(PropTestConfig(iterations = 100), toolStream) { bytes ->
            parser.reset()
            val firstResult = parser.parse(bytes)
            val firstEvents = firstResult.events

            if (firstEvents.isNotEmpty() && firstEvents.none { it is OutputEvent.Error }) {
                val printedBytes = printer.eventsToBytes(firstEvents)

                parser.reset()
                val secondResult = parser.parse(printedBytes)

                eventsEquivalent(firstEvents, secondResult.events) shouldBe true
            }
        }
    }

    test("Parser/Printer round-trip holds for mixed streams with turn-complete") {
        val parser = OutputParserImpl()
        val printer = PrettyPrinterImpl()

        val mixedStream: Arb<ByteArray> = arbitrary {
            val textLines = Arb.list(textLine, 1..3).bind()
            val toolStart = toolCallStartLine.bind()
            val toolResult = toolCallResultLine.bind()
            val turnComplete = turnCompleteLine.bind()

            textLines.fold(byteArrayOf()) { acc, line -> acc + line } +
                toolStart + toolResult +
                textLines.first() +
                turnComplete
        }

        checkAll(PropTestConfig(iterations = 100), mixedStream) { bytes ->
            parser.reset()
            val firstResult = parser.parse(bytes)
            val firstEvents = firstResult.events

            if (firstEvents.isNotEmpty() && firstEvents.none { it is OutputEvent.Error }) {
                val printedBytes = printer.eventsToBytes(firstEvents)

                parser.reset()
                val secondResult = parser.parse(printedBytes)

                eventsEquivalent(firstEvents, secondResult.events) shouldBe true
            }
        }
    }
})
