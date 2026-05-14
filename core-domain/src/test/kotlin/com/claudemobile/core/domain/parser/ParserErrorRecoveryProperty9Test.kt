package com.claudemobile.core.domain.parser

import com.claudemobile.core.domain.model.OutputEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll

/**
 * Property-based test for Parser Error Recovery.
 *
 * **Validates: Requirements 14.4**
 *
 * Property 9: For any byte stream containing one malformed sentinel inserted between valid
 * content, the OutputParser should:
 * (a) emit exactly one Error event for the malformed part,
 * (b) resynchronize at the next newline boundary,
 * (c) continue correctly parsing subsequent valid content.
 *
 * The test generates byte streams that:
 * - Start with valid text lines (producing Text events)
 * - Contain one malformed sentinel (e.g., `[tool_call_start` without closing bracket,
 *   or `[tool_call_result:name` without status)
 * - End with more valid text lines
 */
class ParserErrorRecoveryProperty9Test : FunSpec({

    test("Feature: android-claude-termux-client, Property 9: Parser error recovery") {
        checkAll(
            PropTestConfig(iterations = 100),
            arbValidTextLinesBefore(),
            arbMalformedSentinel(),
            arbValidTextLinesAfter()
        ) { textBefore, malformedSentinel, textAfter ->
            val parser = OutputParserImpl()

            // Step 1: Build the combined byte stream
            val beforeBytes = textBefore.lines.joinToString("") { "$it\n" }.toByteArray(Charsets.UTF_8)
            val malformedBytes = "$malformedSentinel\n".toByteArray(Charsets.UTF_8)
            val afterBytes = textAfter.lines.joinToString("") { "$it\n" }.toByteArray(Charsets.UTF_8)
            val combinedInput = beforeBytes + malformedBytes + afterBytes

            // Step 2: Parse the combined byte stream
            val result = parser.parse(combinedInput)

            // Step 3: Verify exactly one Error event is emitted
            val errorEvents = result.events.filterIsInstance<OutputEvent.Error>()
            errorEvents.size shouldBe 1

            // Step 4: Verify Text events before the error are correctly parsed
            val allEvents = result.events
            val errorIndex = allEvents.indexOfFirst { it is OutputEvent.Error }

            val eventsBefore = allEvents.subList(0, errorIndex)
            val textEventsBefore = eventsBefore.filterIsInstance<OutputEvent.Text>()
            textEventsBefore.size shouldBe textBefore.lines.size

            // Verify each text event before matches the expected content
            textBefore.lines.forEachIndexed { index, expectedLine ->
                val textEvent = textEventsBefore[index]
                textEvent.shouldBeInstanceOf<OutputEvent.Text>()
                // The content should include the line text with newline
                textEvent.content shouldBe "$expectedLine\n"
            }

            // Step 5: Verify Text events after the error are correctly parsed (parser resynchronized)
            val eventsAfter = allEvents.subList(errorIndex + 1, allEvents.size)
            val textEventsAfter = eventsAfter.filterIsInstance<OutputEvent.Text>()
            textEventsAfter.size shouldBe textAfter.lines.size

            // Verify each text event after matches the expected content
            textAfter.lines.forEachIndexed { index, expectedLine ->
                val textEvent = textEventsAfter[index]
                textEvent.shouldBeInstanceOf<OutputEvent.Text>()
                textEvent.content shouldBe "$expectedLine\n"
            }

            // Verify all bytes were consumed (resynchronization happened at newline boundary)
            result.consumedBytes shouldBe combinedInput.size
        }
    }
})

// --- Data classes for generated test inputs ---

/**
 * Represents a collection of valid text lines that produce Text events.
 */
private data class ValidTextLines(val lines: List<String>)

// --- Generators ---

/**
 * Safe words that won't trigger sentinel or prompt detection.
 * These start with lowercase letters and don't form prompt patterns.
 */
private val safeWords = listOf(
    "hello", "world", "this", "is", "a", "test", "output",
    "the", "result", "was", "successful", "processing", "data",
    "function", "returns", "value", "completed", "running",
    "parsing", "stream", "content", "valid", "text", "line",
    "some", "more", "words", "here", "for", "testing"
)

/**
 * Generates a single safe text line that:
 * - Does NOT start with '[' (avoids sentinel detection)
 * - Does NOT match prompt regex (word followed by '>')
 * - Contains only printable ASCII characters
 */
private fun arbSafeTextLine(): Arb<String> = arbitrary {
    val wordCount = Arb.int(2, 5).bind()
    val words = (1..wordCount).map { Arb.element(safeWords).bind() }
    words.joinToString(" ")
}

/**
 * Generates 1-3 valid text lines that will produce Text events when parsed.
 */
private fun arbValidTextLinesBefore(): Arb<ValidTextLines> = arbitrary {
    val lineCount = Arb.int(1, 3).bind()
    val lines = (1..lineCount).map { arbSafeTextLine().bind() }
    ValidTextLines(lines)
}

/**
 * Generates 1-3 valid text lines that will produce Text events when parsed.
 */
private fun arbValidTextLinesAfter(): Arb<ValidTextLines> = arbitrary {
    val lineCount = Arb.int(1, 3).bind()
    val lines = (1..lineCount).map { arbSafeTextLine().bind() }
    ValidTextLines(lines)
}

/**
 * Generates a malformed sentinel string that the parser should reject with an Error event.
 *
 * These are lines that look like they intended to be sentinels but don't match
 * the expected grammar:
 * - [tool_call_start without closing bracket
 * - [tool_call_result:name without status
 * - [turn_ incomplete markers
 * - [tool_call_start: with empty name and no closing bracket
 */
private fun arbMalformedSentinel(): Arb<String> = arbitrary {
    val toolNames = listOf("read_file", "write_file", "bash", "search", "edit_file", "list_dir")
    val toolName = Arb.element(toolNames).bind()

    val malformedPatterns = listOf(
        // tool_call_start without closing bracket
        "[tool_call_start:${toolName}",
        // tool_call_result without status field (no colon-separated success/failure)
        "[tool_call_result:${toolName}",
        // tool_call_start with empty content and no closing bracket
        "[tool_call_start:",
        // tool_call_result with name but missing status (no second colon)
        "[tool_call_result:${toolName}_no_status",
        // Incomplete turn marker
        "[turn_incomplete",
        // tool_call_start with extra colons but no closing bracket
        "[tool_call_start:${toolName}:extra",
        // tool_call_result with wrong format (missing closing bracket)
        "[tool_call_result:${toolName}:success_no_bracket"
    )

    Arb.element(malformedPatterns).bind()
}
