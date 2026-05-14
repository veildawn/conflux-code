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
 * Property-based test for Stream Reassembly Round-Trip.
 *
 * **Validates: Requirements 4.2, 4.7**
 *
 * Property 1: For any byte sequence produced by the PTY_Bridge for a single turn,
 * parsing the bytes into events via the Output_Parser and concatenating the text
 * event payloads in emission order (after stripping ANSI escapes) SHALL yield the
 * same string as stripping ANSI escapes directly from the bytes.
 *
 * Feature: android-claude-termux-client, Property 1: Stream Reassembly Round-Trip
 */
class StreamReassemblyPropertyTest : FunSpec({

    tags(
        io.kotest.core.Tag("Feature: android-claude-termux-client"),
        io.kotest.core.Tag("Property 1: Stream Reassembly Round-Trip")
    )

    test("stream reassembly round-trip: concatenating text event payloads equals stripping ANSI from original bytes") {
        checkAll(PropTestConfig(iterations = 100), arbTextLinesWithAnsi()) { inputBytes ->
            val parser = OutputParserImpl()
            val result = parser.parse(inputBytes)

            // Concatenate all Text event content payloads (already ANSI-stripped by the parser)
            val reassembled = result.events
                .filterIsInstance<OutputEvent.Text>()
                .joinToString("") { it.content }

            // Strip ANSI from the consumed portion of the original bytes directly
            val consumedBytes = inputBytes.sliceArray(0 until result.consumedBytes)
            val directStripped = stripAnsi(consumedBytes)

            reassembled shouldBe directStripped
        }
    }
})

/**
 * Strips ANSI escape sequences from a byte array, returning the plain text string.
 * This is the reference implementation used to verify the parser's behavior.
 */
private fun stripAnsi(bytes: ByteArray): String {
    val sb = StringBuilder()
    var i = 0
    while (i < bytes.size) {
        if (bytes[i] == 0x1B.toByte() && i + 1 < bytes.size && bytes[i + 1] == '['.code.toByte()) {
            // Skip ESC [ ... (terminator)
            i += 2
            while (i < bytes.size) {
                val b = bytes[i].toInt() and 0xFF
                if (b in 0x40..0x7E) {
                    // Found terminator
                    i++
                    break
                }
                if (b in 0x20..0x3F) {
                    i++
                } else {
                    // Invalid sequence byte — stop consuming
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
 * Characters safe for use at the start of a line (avoids '[' which triggers sentinel detection).
 */
private val safeFirstChar = Arb.of(
    ('a'..'z').toList() +
        ('A'..'Z').toList() +
        ('0'..'9').toList() +
        listOf(' ', '.', ',', '!', '?', '-', '+', '=', '/', '(', ')', '{', '}', '"', '\'', ':', ';')
)

/**
 * Characters safe for use in the body of a line.
 */
private val safeBodyChar = Arb.of(
    ('a'..'z').toList() +
        ('A'..'Z').toList() +
        ('0'..'9').toList() +
        listOf(' ', '.', ',', '!', '?', '-', '+', '=', '/', '[', ']', '(', ')', '{', '}', '"', '\'', ':', ';')
)

/**
 * Generates a random ANSI escape sequence (SGR format: ESC[...m).
 */
private val arbAnsiSequence: Arb<ByteArray> = arbitrary {
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
        // First character: avoid '[' and '>' to prevent sentinel/prompt matching
        append(safeFirstChar.bind())
        repeat(length - 1) {
            append(safeBodyChar.bind())
        }
    }

    // Final safety check: ensure the stripped content doesn't match prompt or sentinel patterns
    val stripped = chars.trimEnd()
    if (stripped.matches(Regex("""^\w*>\s?$""")) ||
        stripped == "[turn_complete]" ||
        stripped.startsWith("[tool_call_")
    ) {
        // Prefix with safe text to break the pattern
        "text $chars"
    } else {
        chars
    }
}

/**
 * Generates a single text line (ending with '\n') with randomly injected ANSI sequences.
 * The line content is safe text that won't be interpreted as a sentinel or prompt.
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
            segments.add(arbAnsiSequence.bind())
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
        segments.add(arbAnsiSequence.bind())
    }

    // Add newline
    segments.add("\n".toByteArray())

    // Concatenate all segments
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
 * Generates random byte arrays consisting of one or more complete text lines
 * (each ending with '\n') with randomly injected ANSI escape sequences.
 *
 * The generator avoids producing lines that match tool-call sentinels, prompts,
 * or turn-complete markers, since those produce non-Text events and are not
 * part of the text reassembly property.
 */
private fun arbTextLinesWithAnsi(): Arb<ByteArray> = arbitrary {
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
