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
 * Property-based test for Markdown Copy Round-Trip.
 *
 * **Validates: Requirements 4.6, 4.8**
 *
 * Property 3: For any Message content composed only of standard markdown that the
 * Pretty_Printer supports, rendering it and then extracting the plain text via the
 * copy action SHALL yield a string equal to the content with markdown syntax removed
 * but semantic content (words, numbers, whitespace structure) preserved.
 *
 * Feature: android-claude-termux-client, Property 3: Markdown Copy Round-Trip
 */
class MarkdownCopyRoundTripPropertyTest : FunSpec({

    tags(
        io.kotest.core.Tag("Feature: android-claude-termux-client"),
        io.kotest.core.Tag("Property 3: Markdown Copy Round-Trip")
    )

    val prettyPrinter = PrettyPrinterImpl()

    test("markdown copy round-trip: extractPlainText strips markdown syntax but preserves content") {
        checkAll(PropTestConfig(iterations = 100), arbMarkdownWithExpectedPlainText()) { (markdown, expectedPlainText) ->
            val events = listOf(OutputEvent.Text(markdown))
            val actualPlainText = prettyPrinter.extractPlainText(events)

            actualPlainText shouldBe expectedPlainText
        }
    }
})

/**
 * Represents a pair of markdown content and its expected plain text after stripping.
 */
private data class MarkdownTestCase(val markdown: String, val expectedPlainText: String)

/**
 * Generates random markdown content using supported syntax along with the expected
 * plain text output after markdown syntax is stripped.
 *
 * Supported syntax:
 * - Headings (# ## ### etc.)
 * - Bold (**text**)
 * - Italic (*text*)
 * - Inline code (`code`)
 * - Unordered lists (- item)
 * - Links ([text](url))
 * - Blockquotes (> text)
 */
private fun arbMarkdownWithExpectedPlainText(): Arb<MarkdownTestCase> = arbitrary {
    val lineCount = Arb.int(1..5).bind()
    val markdownLines = mutableListOf<String>()
    val plainTextLines = mutableListOf<String>()

    repeat(lineCount) {
        val (mdLine, ptLine) = arbMarkdownLine.bind()
        markdownLines.add(mdLine)
        plainTextLines.add(ptLine)
    }

    MarkdownTestCase(
        markdown = markdownLines.joinToString("\n"),
        expectedPlainText = plainTextLines.joinToString("\n")
    )
}

/**
 * Generates a single line of markdown with its expected plain text.
 * Randomly selects from supported markdown syntax types.
 */
private val arbMarkdownLine: Arb<Pair<String, String>> = arbitrary {
    val lineType = Arb.int(0..6).bind()
    val content = arbSafeWord.bind()

    when (lineType) {
        0 -> {
            // Heading: # content
            val level = Arb.int(1..6).bind()
            val prefix = "#".repeat(level)
            Pair("$prefix $content", content)
        }
        1 -> {
            // Bold: **content**
            val prefix = arbOptionalPlainPrefix.bind()
            val suffix = arbOptionalPlainSuffix.bind()
            Pair(
                "${prefix}**${content}**${suffix}",
                "${prefix}${content}${suffix}"
            )
        }
        2 -> {
            // Italic: *content*
            val prefix = arbOptionalPlainPrefix.bind()
            val suffix = arbOptionalPlainSuffix.bind()
            Pair(
                "${prefix}*${content}*${suffix}",
                "${prefix}${content}${suffix}"
            )
        }
        3 -> {
            // Inline code: `content`
            val prefix = arbOptionalPlainPrefix.bind()
            val suffix = arbOptionalPlainSuffix.bind()
            Pair(
                "${prefix}`${content}`${suffix}",
                "${prefix}${content}${suffix}"
            )
        }
        4 -> {
            // Unordered list: - content
            // Trim leading spaces since the implementation's regex (\s+) strips all
            // whitespace between bullet marker and content (standard markdown behavior)
            val trimmedContent = content.trimStart()
            if (trimmedContent.isEmpty()) {
                // Fallback to plain text if content was all spaces
                Pair(content, content)
            } else {
                Pair("- $trimmedContent", trimmedContent)
            }
        }
        5 -> {
            // Link: [text](url)
            val url = "https://example.com/${arbUrlSegment.bind()}"
            val prefix = arbOptionalPlainPrefix.bind()
            val suffix = arbOptionalPlainSuffix.bind()
            Pair(
                "${prefix}[${content}](${url})${suffix}",
                "${prefix}${content}${suffix}"
            )
        }
        6 -> {
            // Blockquote: > content
            Pair("> $content", content)
        }
        else -> {
            // Plain text (fallback)
            Pair(content, content)
        }
    }
}

/**
 * Generates a safe word that won't be confused with markdown syntax.
 * Avoids characters that have special meaning in markdown: *, _, `, #, [, ], (, ), >, -, |, ~, !
 */
private val arbSafeWord: Arb<String> = arbitrary {
    val length = Arb.int(2..15).bind()
    val chars = buildString {
        repeat(length) {
            append(safeContentChar.bind())
        }
    }
    chars
}

/**
 * Characters safe for use in content that won't trigger markdown parsing.
 */
private val safeContentChar = Arb.of(
    ('a'..'z').toList() +
        ('A'..'Z').toList() +
        ('0'..'9').toList() +
        listOf(' ', ',', '.', '?', ';', ':', '/')
)

/**
 * Generates an optional plain text prefix (no markdown syntax characters).
 * Used to add context around inline markdown elements.
 */
private val arbOptionalPlainPrefix: Arb<String> = arbitrary {
    val hasPrefix = Arb.boolean().bind()
    if (hasPrefix) {
        val word = arbShortPlainWord.bind()
        "$word "
    } else {
        ""
    }
}

/**
 * Generates an optional plain text suffix (no markdown syntax characters).
 */
private val arbOptionalPlainSuffix: Arb<String> = arbitrary {
    val hasSuffix = Arb.boolean().bind()
    if (hasSuffix) {
        val word = arbShortPlainWord.bind()
        " $word"
    } else {
        ""
    }
}

/**
 * Generates a short plain word (letters only, no special chars).
 */
private val arbShortPlainWord: Arb<String> = arbitrary {
    val length = Arb.int(2..8).bind()
    val chars = buildString {
        repeat(length) {
            append(Arb.of(('a'..'z').toList()).bind())
        }
    }
    chars
}

/**
 * Generates a URL-safe path segment.
 */
private val arbUrlSegment: Arb<String> = arbitrary {
    val length = Arb.int(3..10).bind()
    val chars = buildString {
        repeat(length) {
            append(Arb.of(('a'..'z').toList() + ('0'..'9').toList()).bind())
        }
    }
    chars
}
