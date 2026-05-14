package com.claudemobile.core.domain.parser

import com.claudemobile.core.domain.model.OutputEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll

/**
 * Property-based test for ANSI Stripping Preserves Text Content.
 *
 * **Validates: Requirements 4.2**
 *
 * Property 5: For any byte stream containing ANSI SGR escape sequences, stripping the ANSI
 * sequences yields plain text equal to the concatenation of all non-escape-sequence characters
 * in the original byte stream. StyleHint ranges should cover exactly the text regions affected
 * by the corresponding SGR codes.
 *
 * Feature: android-claude-termux-client, Property 5: ANSI stripping preserves text content
 */
class AnsiStrippingPropertyTest : FunSpec({

    tags(
        io.kotest.core.Tag("Feature: android-claude-termux-client"),
        io.kotest.core.Tag("Property 5: ANSI stripping preserves text content")
    )

    test("Feature: android-claude-termux-client, Property 5: ANSI stripping preserves text content") {
        checkAll(PropTestConfig(iterations = 100), arbPlainTextWithAnsiInserted()) { testData ->
            val parser = OutputParserImpl()
            val result = parser.parse(testData.bytesWithAnsi)

            // Concatenate all Text event contents
            val parsedText = result.events
                .filterIsInstance<OutputEvent.Text>()
                .joinToString("") { it.content }

            // The parsed text should equal the original plain text (with newlines preserved)
            parsedText shouldBe testData.expectedPlainText
        }
    }

    test("StyleHint ranges do not overlap within a single Text event") {
        checkAll(PropTestConfig(iterations = 100), arbPlainTextWithAnsiInserted()) { testData ->
            val parser = OutputParserImpl()
            val result = parser.parse(testData.bytesWithAnsi)

            result.events.filterIsInstance<OutputEvent.Text>().forEach { textEvent ->
                val hints = textEvent.styleHints
                // Check that no two hints of the same style overlap
                val hintsByStyle = hints.groupBy { it.style }
                for ((_, styleHints) in hintsByStyle) {
                    val sorted = styleHints.sortedBy { it.range.first }
                    for (i in 0 until sorted.size - 1) {
                        val current = sorted[i]
                        val next = sorted[i + 1]
                        // Current range should end before next range starts
                        (current.range.last < next.range.first) shouldBe true
                    }
                }
            }
        }
    }

    test("StyleHint ranges cover valid text positions") {
        checkAll(PropTestConfig(iterations = 100), arbPlainTextWithAnsiInserted()) { testData ->
            val parser = OutputParserImpl()
            val result = parser.parse(testData.bytesWithAnsi)

            result.events.filterIsInstance<OutputEvent.Text>().forEach { textEvent ->
                val contentLength = textEvent.content.length
                textEvent.styleHints.forEach { hint ->
                    hint.range.first shouldBeGreaterThanOrEqualTo 0
                    hint.range.last shouldBeLessThan contentLength
                    (hint.range.first <= hint.range.last) shouldBe true
                }
            }
        }
    }

    test("ANSI SGR sequences produce non-empty StyleHints when wrapping text") {
        checkAll(PropTestConfig(iterations = 100), arbTextWithGuaranteedStyleHints()) { testData ->
            val parser = OutputParserImpl()
            val result = parser.parse(testData.bytesWithAnsi)

            val allHints = result.events
                .filterIsInstance<OutputEvent.Text>()
                .flatMap { it.styleHints }

            // When we insert style-on/text/style-off sequences, we should get hints
            allHints.shouldNotBeEmpty()
        }
    }
})

/**
 * Test data containing the bytes with ANSI sequences inserted and the expected plain text.
 */
private data class AnsiTestData(
    val bytesWithAnsi: ByteArray,
    val expectedPlainText: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AnsiTestData) return false
        return bytesWithAnsi.contentEquals(other.bytesWithAnsi) && expectedPlainText == other.expectedPlainText
    }

    override fun hashCode(): Int {
        var result = bytesWithAnsi.contentHashCode()
        result = 31 * result + expectedPlainText.hashCode()
        return result
    }
}

// --- Valid SGR codes used in the tests ---

/** SGR codes that activate styles */
private val STYLE_ON_CODES = listOf(1, 2, 3, 4, 9)

/** SGR codes that deactivate styles */
private val STYLE_OFF_CODES = listOf(22, 23, 24, 29, 0)

/** All valid SGR codes for testing */
private val ALL_SGR_CODES = listOf(1, 2, 3, 4, 9, 22, 23, 24, 29, 0)

// --- Character generators ---

/**
 * Characters safe for use in text content. Avoids characters that could trigger
 * sentinel/prompt detection by the parser.
 */
private val safeChars = ('a'..'z').toList() +
    ('A'..'Z').toList() +
    ('0'..'9').toList() +
    listOf(' ', '.', ',', '!', '?', '-', '+', '=', '/', '(', ')', '{', '}', '"', '\'', ':', ';')

/**
 * Characters safe for the first position of a line (avoids '[' which triggers sentinel detection
 * and patterns that match prompt regex).
 */
private val safeFirstChars = ('a'..'z').toList() +
    ('A'..'Z').toList() +
    ('0'..'9').toList() +
    listOf(' ', '.', ',', '!', '?', '-', '+', '=', '/', '(', ')', '{', '}', '"', '\'', ':', ';')

// --- Generators ---

/**
 * Generates a random ANSI SGR escape sequence using valid SGR codes.
 * Format: ESC[Nm where N is a valid SGR code.
 */
private fun arbAnsiSgr(): Arb<ByteArray> = arbitrary {
    val code = Arb.of(ALL_SGR_CODES).bind()
    "\u001b[${code}m".toByteArray()
}

/**
 * Generates a safe text segment that won't be interpreted as a sentinel or prompt.
 * Returns a non-empty string of safe characters.
 */
private fun arbSafeTextSegment(): Arb<String> = arbitrary {
    val length = Arb.int(2, 20).bind()
    buildString {
        // First char avoids triggering prompt/sentinel patterns
        append(Arb.of(safeFirstChars).bind())
        repeat(length - 1) {
            append(Arb.of(safeChars).bind())
        }
    }
}

/**
 * Generates a single line of plain text (safe content) and the same line with
 * ANSI SGR sequences randomly inserted at various positions.
 *
 * The generator:
 * 1. Creates a random plain text string
 * 2. Inserts random ANSI SGR sequences at random positions
 * 3. Appends a newline
 * 4. Returns both the ANSI-injected bytes and the expected plain text (with newline)
 */
private fun arbLineWithAnsi(): Arb<AnsiTestData> = arbitrary {
    val textSegment = arbSafeTextSegment().bind()

    // Decide how many ANSI sequences to insert
    val numInsertions = Arb.int(0, 5).bind()

    // Generate insertion positions (indices into the text where ANSI will be inserted)
    val insertPositions = mutableListOf<Int>()
    repeat(numInsertions) {
        insertPositions.add(Arb.int(0, textSegment.length).bind())
    }
    // Sort positions in descending order so insertions don't shift indices
    val sortedPositions = insertPositions.sortedDescending()

    // Build the ANSI-injected version
    val segments = mutableListOf<ByteArray>()
    var currentText = textSegment

    // We'll rebuild from scratch: split text at insertion points and interleave ANSI
    // First, collect all insertion points sorted ascending with their ANSI sequences
    data class Insertion(val pos: Int, val ansi: ByteArray)

    val insertions = sortedPositions.reversed().map { pos ->
        Insertion(pos, arbAnsiSgr().bind())
    }.sortedBy { it.pos }

    // Build the byte array by interleaving text and ANSI sequences
    val resultBytes = mutableListOf<ByteArray>()
    var textPos = 0

    for (insertion in insertions) {
        // Add text before this insertion point
        if (insertion.pos > textPos) {
            resultBytes.add(currentText.substring(textPos, insertion.pos).toByteArray())
        }
        // Add the ANSI sequence
        resultBytes.add(insertion.ansi)
        textPos = insertion.pos
    }

    // Add remaining text after last insertion
    if (textPos < currentText.length) {
        resultBytes.add(currentText.substring(textPos).toByteArray())
    }

    // Add newline
    resultBytes.add("\n".toByteArray())

    // Concatenate all byte segments
    val totalSize = resultBytes.sumOf { it.size }
    val finalBytes = ByteArray(totalSize)
    var offset = 0
    for (seg in resultBytes) {
        seg.copyInto(finalBytes, offset)
        offset += seg.size
    }

    // The expected plain text is the original text + newline
    val expectedPlain = textSegment + "\n"

    AnsiTestData(bytesWithAnsi = finalBytes, expectedPlainText = expectedPlain)
}

/**
 * Generates test data with multiple lines, each containing randomly inserted ANSI SGR sequences.
 * Returns the combined bytes and the expected concatenated plain text.
 */
private fun arbPlainTextWithAnsiInserted(): Arb<AnsiTestData> = arbitrary {
    val lineCount = Arb.int(1, 5).bind()
    val allBytes = mutableListOf<ByteArray>()
    val allPlainText = StringBuilder()

    repeat(lineCount) {
        val lineData = arbLineWithAnsi().bind()
        allBytes.add(lineData.bytesWithAnsi)
        allPlainText.append(lineData.expectedPlainText)
    }

    // Concatenate all line bytes
    val totalSize = allBytes.sumOf { it.size }
    val finalBytes = ByteArray(totalSize)
    var offset = 0
    for (seg in allBytes) {
        seg.copyInto(finalBytes, offset)
        offset += seg.size
    }

    AnsiTestData(bytesWithAnsi = finalBytes, expectedPlainText = allPlainText.toString())
}

/**
 * Generates test data that guarantees StyleHints will be produced.
 * Inserts a style-on code, then text, then the corresponding style-off code.
 */
private fun arbTextWithGuaranteedStyleHints(): Arb<AnsiTestData> = arbitrary {
    val textSegment = arbSafeTextSegment().bind()

    // Pick a style pair (on/off)
    val styleIndex = Arb.int(0, STYLE_ON_CODES.size - 1).bind()
    val onCode = STYLE_ON_CODES[styleIndex]
    val offCode = STYLE_OFF_CODES[styleIndex]

    // Build: ESC[onCode m + text + ESC[offCode m + \n
    val onSeq = "\u001b[${onCode}m".toByteArray()
    val offSeq = "\u001b[${offCode}m".toByteArray()
    val textBytes = textSegment.toByteArray()
    val newline = "\n".toByteArray()

    val totalSize = onSeq.size + textBytes.size + offSeq.size + newline.size
    val finalBytes = ByteArray(totalSize)
    var offset = 0
    onSeq.copyInto(finalBytes, offset); offset += onSeq.size
    textBytes.copyInto(finalBytes, offset); offset += textBytes.size
    offSeq.copyInto(finalBytes, offset); offset += offSeq.size
    newline.copyInto(finalBytes, offset)

    AnsiTestData(bytesWithAnsi = finalBytes, expectedPlainText = textSegment + "\n")
}
