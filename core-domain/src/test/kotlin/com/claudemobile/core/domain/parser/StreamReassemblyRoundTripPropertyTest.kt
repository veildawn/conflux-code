package com.claudemobile.core.domain.parser

import com.claudemobile.core.domain.model.OutputEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll

/**
 * Property-based test for Stream Reassembly Round-Trip Consistency.
 *
 * **Validates: Requirements 4.7**
 *
 * Property 6: For any byte sequence `b` produced by the PTY_Bridge for a single turn,
 * concatenating the `text` event payloads in order from OutputParser SHALL yield the same
 * string as stripping ANSI escapes from `b` directly.
 *
 * The test generates arbitrary byte streams that:
 * - Contain random text lines (with newlines)
 * - May contain ANSI escape sequences
 * - Do NOT contain sentinel patterns (to avoid tool_call events)
 *
 * Then verifies that parsing with OutputParserImpl and concatenating all Text event
 * `content` fields produces the same result as stripping ANSI from the original bytes directly.
 */
class StreamReassemblyRoundTripPropertyTest : FunSpec({

    test("Feature: android-claude-termux-client, Property 6: Stream reassembly round-trip consistency") {
        checkAll(PropTestConfig(iterations = 100), arbPtyByteStream()) { inputBytes ->
            val parser = OutputParserImpl()

            // Step 2: Parse with OutputParserImpl
            val result = parser.parse(inputBytes)

            // Step 3: Concatenate all Text event content fields
            val reassembled = result.events
                .filterIsInstance<OutputEvent.Text>()
                .joinToString("") { it.content }

            // Step 4: Strip ANSI from the consumed portion of the original bytes directly
            val consumedBytes = inputBytes.sliceArray(0 until result.consumedBytes)
            val directStripped = stripAnsiEscapes(consumedBytes)

            // Step 5: Verify both strings are equal
            reassembled shouldBe directStripped
        }
    }
})

// --- Reference ANSI stripping implementation ---

/**
 * Strips ANSI escape sequences from a byte array, returning the plain text string.
 * This is the reference implementation used to verify the parser's behavior independently.
 *
 * Handles CSI sequences: ESC [ (params) (terminator where terminator is 0x40-0x7E)
 */
private fun stripAnsiEscapes(bytes: ByteArray): String {
    val sb = StringBuilder()
    var i = 0
    while (i < bytes.size) {
        if (bytes[i] == 0x1B.toByte() && i + 1 < bytes.size && bytes[i + 1] == '['.code.toByte()) {
            // Skip ESC [ ... (terminator)
            i += 2
            while (i < bytes.size) {
                val b = bytes[i].toInt() and 0xFF
                if (b in 0x40..0x7E) {
                    // Found terminator byte
                    i++
                    break
                }
                if (b in 0x20..0x3F) {
                    // Parameter or intermediate byte
                    i++
                } else {
                    // Invalid sequence byte — stop consuming the escape
                    break
                }
            }
        } else {
            sb.append(bytes[i].toInt().toChar())
            i++
        }
    }
    return sb.toString()
}

// --- Generators ---

/**
 * Safe characters for the first position of a line.
 * Avoids '[' which could trigger sentinel detection by the parser.
 */
private val safeFirstChar = Arb.of(
    ('a'..'z').toList() +
        ('A'..'Z').toList() +
        ('0'..'9').toList() +
        listOf(' ', '.', ',', '!', '?', '-', '+', '=', '/', '(', ')', '{', '}', '"', '\'', ':', ';')
)

/**
 * Safe characters for the body of a line.
 * Includes '[' and ']' since they are safe in non-first positions for most cases,
 * but we still avoid generating sentinel-like patterns via content validation.
 */
private val safeBodyChar = Arb.of(
    ('a'..'z').toList() +
        ('A'..'Z').toList() +
        ('0'..'9').toList() +
        listOf(' ', '.', ',', '!', '?', '-', '+', '=', '/', '(', ')', '{', '}', '"', '\'', ':', ';')
)

/**
 * Generates a random ANSI SGR escape sequence (ESC[...m format).
 * Uses common SGR codes for styling (bold, italic, colors, etc.)
 */
private val arbAnsiSgrSequence: Arb<ByteArray> = arbitrary {
    val sgrCode = Arb.of(
        listOf(0, 1, 2, 3, 4, 9, 22, 23, 24, 29, 30, 31, 32, 33, 34, 35, 36, 37, 39, 40, 41, 42, 43, 44, 45, 46, 47, 49)
    ).bind()

    val useMultipleCodes = Arb.boolean().bind()
    val sequence = if (useMultipleCodes) {
        val secondCode = Arb.of(listOf(0, 1, 2, 3, 4, 9, 22, 23, 24, 29)).bind()
        "\u001b[${sgrCode};${secondCode}m"
    } else {
        "\u001b[${sgrCode}m"
    }
    sequence.toByteArray()
}

/**
 * Generates safe text content that won't be mistaken for sentinels, prompts,
 * or turn-complete markers by the parser.
 *
 * Avoids:
 * - Starting with '[' (sentinel prefix)
 * - Matching prompt regex (word followed by '>' at line boundary)
 * - Matching turn_complete or tool_call patterns
 */
private val arbSafeTextContent: Arb<String> = arbitrary {
    val length = Arb.int(2..40).bind()
    val chars = buildString {
        // First character: avoid '[' and patterns that trigger sentinel/prompt matching
        append(safeFirstChar.bind())
        repeat(length - 1) {
            append(safeBodyChar.bind())
        }
    }

    // Safety check: ensure the content doesn't match prompt or sentinel patterns
    val stripped = chars.trimEnd()
    if (stripped.matches(Regex("""^\w*>\s?$""")) ||
        stripped == "[turn_complete]" ||
        stripped.startsWith("[tool_call_")
    ) {
        // Prefix with safe text to break the pattern
        "safe $chars"
    } else {
        chars
    }
}

/**
 * Generates a single text line (ending with '\n') with randomly injected ANSI sequences.
 *
 * Structure: [ANSI?] text_segment [ANSI?] text_segment ... [ANSI?] '\n'
 */
private val arbTextLineWithAnsi: Arb<ByteArray> = arbitrary {
    val textContent = arbSafeTextContent.bind()
    val segmentCount = Arb.int(1..4).bind()

    // Split text into roughly equal segments
    val segmentSize = (textContent.length / segmentCount).coerceAtLeast(1)
    val segments = mutableListOf<ByteArray>()

    var textPos = 0
    repeat(segmentCount) { idx ->
        // Optionally prepend an ANSI sequence before this segment
        val prependAnsi = Arb.boolean().bind()
        if (prependAnsi) {
            segments.add(arbAnsiSgrSequence.bind())
        }

        // Add text segment
        val end = if (idx == segmentCount - 1) textContent.length
        else (textPos + segmentSize).coerceAtMost(textContent.length)

        if (textPos < end) {
            segments.add(textContent.substring(textPos, end).toByteArray())
            textPos = end
        }
    }

    // Optionally add a trailing ANSI sequence before the newline
    val trailingAnsi = Arb.boolean().bind()
    if (trailingAnsi) {
        segments.add(arbAnsiSgrSequence.bind())
    }

    // Add newline terminator
    segments.add("\n".toByteArray())

    // Concatenate all segments into a single byte array
    val totalSize = segments.sumOf { it.size }
    val result = ByteArray(totalSize)
    var offset = 0
    for (seg in segments) {
        seg.copyInto(result, offset)
        offset += seg.size
    }
    result
}

/**
 * Generates a random byte stream simulating PTY_Bridge output for a single turn.
 *
 * The stream consists of 1-5 complete text lines, each potentially containing
 * ANSI escape sequences. Lines are terminated with newlines.
 *
 * The generator avoids producing lines that match:
 * - Tool-call sentinels ([tool_call_start:...], [tool_call_result:...])
 * - Turn-complete markers ([turn_complete])
 * - Prompt patterns (word>)
 *
 * This ensures only Text events are produced, which is what the stream
 * reassembly property verifies.
 */
private fun arbPtyByteStream(): Arb<ByteArray> = arbitrary {
    val lineCount = Arb.int(1..5).bind()
    val lines = mutableListOf<ByteArray>()

    repeat(lineCount) {
        lines.add(arbTextLineWithAnsi.bind())
    }

    // Concatenate all lines into a single byte array
    val totalSize = lines.sumOf { it.size }
    val result = ByteArray(totalSize)
    var offset = 0
    for (line in lines) {
        line.copyInto(result, offset)
        offset += line.size
    }
    result
}
