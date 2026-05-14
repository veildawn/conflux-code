package com.claudemobile.core.domain.parser

import com.claudemobile.core.domain.model.OutputEvent
import com.claudemobile.core.domain.model.StyleHint
import com.claudemobile.core.domain.model.TextStyle

/**
 * Implementation of [PrettyPrinter] that serializes output events back into
 * byte streams conforming to the parser grammar, enabling round-trip testing.
 *
 * The serialization format matches what [OutputParserImpl] expects:
 * - Text events: content as-is (already includes trailing newline from parsing)
 * - ToolCallStart: [tool_call_start:toolName]arguments\n
 * - ToolCallResult: [tool_call_result:toolName:success/failure]result\n
 * - Prompt: text\n
 * - TurnComplete: [turn_complete]\n
 * - Error events are skipped (not round-trippable by definition)
 *
 * The [extractPlainText] method strips markdown syntax while preserving
 * semantic content (words, numbers, whitespace structure).
 */
public class PrettyPrinterImpl : PrettyPrinter {

    override fun eventsToBytes(events: List<OutputEvent>): ByteArray {
        val builder = StringBuilder()

        for (event in events) {
            when (event) {
                is OutputEvent.Text -> {
                    // Re-insert ANSI SGR codes based on StyleHints so that
                    // re-parsing produces equivalent events with matching hints.
                    builder.append(insertAnsiCodes(event.content, event.styleHints))
                }
                is OutputEvent.ToolCallStart -> {
                    builder.append("[tool_call_start:")
                    builder.append(event.toolName)
                    builder.append("]")
                    builder.append(event.arguments)
                    builder.append("\n")
                }
                is OutputEvent.ToolCallResult -> {
                    builder.append("[tool_call_result:")
                    builder.append(event.toolName)
                    builder.append(":")
                    builder.append(if (event.success) "success" else "failure")
                    builder.append("]")
                    builder.append(event.result)
                    builder.append("\n")
                }
                is OutputEvent.Prompt -> {
                    builder.append(event.text)
                    builder.append("\n")
                }
                is OutputEvent.TurnComplete -> {
                    builder.append("[turn_complete]\n")
                }
                is OutputEvent.Error -> {
                    // Error events are not serializable for round-trip
                    // They represent parse failures and are excluded from the property
                }
            }
        }

        return builder.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Re-inserts ANSI SGR escape codes into text content based on style hints.
     *
     * For each style hint, inserts the appropriate SGR activation code at the
     * start of the range and the deactivation code at the end. When no style
     * hints are present, returns the content unchanged.
     *
     * SGR code mapping:
     * - BOLD: ESC[1m ... ESC[22m
     * - DIM: ESC[2m ... ESC[22m
     * - ITALIC: ESC[3m ... ESC[23m
     * - UNDERLINE: ESC[4m ... ESC[24m
     * - STRIKETHROUGH: ESC[9m ... ESC[29m
     *
     * Special handling: BOLD and DIM share the same close code (ESC[22m).
     * When closing one while the other is still active, we must re-open the
     * still-active style after emitting the close code to maintain correct
     * round-trip behavior with the parser.
     */
    private fun insertAnsiCodes(content: String, styleHints: List<StyleHint>): String {
        if (styleHints.isEmpty()) {
            return content
        }

        // Collect all boundary events (open/close) at each text position.
        // At each position we process closes first, then opens.
        data class BoundaryEvent(
            val position: Int,
            val style: TextStyle,
            val isOpen: Boolean
        )

        val boundaries = mutableListOf<BoundaryEvent>()

        for (hint in styleHints) {
            // Skip CODE style — it has no standard ANSI SGR representation
            // that the parser can recognize on re-parse.
            if (hint.style == TextStyle.CODE) continue

            val startPos = hint.range.first
            val endPos = hint.range.last + 1 // close after last character
            boundaries.add(BoundaryEvent(startPos, hint.style, true))
            boundaries.add(BoundaryEvent(endPos, hint.style, false))
        }

        // Sort: by position, then closes before opens at same position
        boundaries.sortWith(compareBy<BoundaryEvent> { it.position }.thenBy { it.isOpen })

        // If all hints were CODE (skipped), return content unchanged
        if (boundaries.isEmpty()) {
            return content
        }

        // Track currently active styles to handle shared close codes
        val currentlyActive = mutableSetOf<TextStyle>()

        val result = StringBuilder()
        var contentIndex = 0

        for (boundary in boundaries) {
            // Append content up to this position
            val targetPos = boundary.position.coerceAtMost(content.length)
            if (contentIndex < targetPos) {
                result.append(content, contentIndex, targetPos)
                contentIndex = targetPos
            }

            if (boundary.isOpen) {
                result.append(styleToOpenSgr(boundary.style))
                currentlyActive.add(boundary.style)
            } else {
                result.append(styleToCloseSgr(boundary.style))
                currentlyActive.remove(boundary.style)

                // Handle shared close codes: ESC[22m closes both BOLD and DIM.
                // If we just closed one but the other is still active, re-open it.
                if (boundary.style == TextStyle.BOLD && TextStyle.DIM in currentlyActive) {
                    result.append(styleToOpenSgr(TextStyle.DIM))
                } else if (boundary.style == TextStyle.DIM && TextStyle.BOLD in currentlyActive) {
                    result.append(styleToOpenSgr(TextStyle.BOLD))
                }
            }
        }

        // Append remaining content after all boundaries
        if (contentIndex < content.length) {
            result.append(content, contentIndex, content.length)
        }

        return result.toString()
    }

    /**
     * Returns the ANSI SGR activation escape sequence for the given text style.
     */
    private fun styleToOpenSgr(style: TextStyle): String {
        return when (style) {
            TextStyle.BOLD -> "$ESC[1m"
            TextStyle.DIM -> "$ESC[2m"
            TextStyle.ITALIC -> "$ESC[3m"
            TextStyle.UNDERLINE -> "$ESC[4m"
            TextStyle.STRIKETHROUGH -> "$ESC[9m"
            TextStyle.CODE -> "$ESC[7m" // Use reverse video for CODE style
        }
    }

    /**
     * Returns the ANSI SGR deactivation escape sequence for the given text style.
     */
    private fun styleToCloseSgr(style: TextStyle): String {
        return when (style) {
            TextStyle.BOLD -> "$ESC[22m"
            TextStyle.DIM -> "$ESC[22m"
            TextStyle.ITALIC -> "$ESC[23m"
            TextStyle.UNDERLINE -> "$ESC[24m"
            TextStyle.STRIKETHROUGH -> "$ESC[29m"
            TextStyle.CODE -> "$ESC[27m" // Reset reverse video
        }
    }

    override fun extractPlainText(events: List<OutputEvent>): String {
        val builder = StringBuilder()

        for (event in events) {
            when (event) {
                is OutputEvent.Text -> {
                    builder.append(stripMarkdownSyntax(event.content))
                }
                is OutputEvent.ToolCallStart -> {
                    builder.append("[${event.toolName}] ${event.arguments}")
                }
                is OutputEvent.ToolCallResult -> {
                    builder.append("[${event.toolName}] ${event.result}")
                }
                is OutputEvent.Prompt -> builder.append(event.text)
                is OutputEvent.TurnComplete -> { /* no text content */ }
                is OutputEvent.Error -> builder.append(event.reason)
            }
        }

        return builder.toString()
    }

    /**
     * Strips markdown syntax from text content while preserving semantic content.
     *
     * Handles:
     * - Headings (# ## ### etc.) → content without # prefix
     * - Bold (**text** or __text__) → text
     * - Italic (*text* or _text_) → text
     * - Inline code (`code`) → code
     * - Code fences (```lang ... ```) → content without fence markers
     * - Unordered lists (- or * prefix) → content without bullet
     * - Ordered lists (1. 2. etc.) → content without number prefix
     * - Links ([text](url)) → text
     * - Images (![alt](url)) → alt
     * - Tables (| col | col |) → content without pipe separators
     * - Table separator lines (|---|---| ) → removed entirely
     * - Blockquotes (> text) → text
     * - Horizontal rules (--- or ***) → removed
     * - Strikethrough (~~text~~) → text
     */
    private fun stripMarkdownSyntax(content: String): String {
        val lines = content.split('\n')
        val result = StringBuilder()
        var inCodeFence = false

        for ((index, line) in lines.withIndex()) {
            val processedLine = if (inCodeFence) {
                if (CODE_FENCE_REGEX.matches(line.trimEnd())) {
                    inCodeFence = false
                    "" // Skip closing fence marker
                } else {
                    line // Preserve code content as-is
                }
            } else if (CODE_FENCE_REGEX.matches(line.trimEnd())) {
                inCodeFence = true
                "" // Skip opening fence marker
            } else {
                stripMarkdownFromLine(line)
            }

            if (index > 0) {
                result.append('\n')
            }
            result.append(processedLine)
        }

        return result.toString()
    }

    /**
     * Strips markdown syntax from a single line of text.
     */
    private fun stripMarkdownFromLine(line: String): String {
        // Horizontal rules: lines that are only ---, ***, or ___
        if (HORIZONTAL_RULE_REGEX.matches(line.trim())) {
            return ""
        }

        // Table separator lines: |---|---|
        if (TABLE_SEPARATOR_REGEX.matches(line.trim())) {
            return ""
        }

        var result = line

        // Strip heading markers (# ## ### etc.)
        result = HEADING_REGEX.replace(result) { it.groupValues[1] }

        // Strip blockquote markers (> )
        result = BLOCKQUOTE_REGEX.replace(result) { it.groupValues[1] }

        // Strip unordered list markers (- or * at line start with space)
        result = UNORDERED_LIST_REGEX.replace(result) { it.groupValues[1] + it.groupValues[2] }

        // Strip ordered list markers (1. 2. etc.)
        result = ORDERED_LIST_REGEX.replace(result) { it.groupValues[1] + it.groupValues[2] }

        // Strip table pipes — extract cell content
        if (TABLE_ROW_REGEX.matches(result.trim())) {
            result = stripTableRow(result)
        }

        // Strip inline formatting (order matters: process longer markers first)
        // Images: ![alt](url) → alt
        result = IMAGE_REGEX.replace(result) { it.groupValues[1] }

        // Links: [text](url) → text
        result = LINK_REGEX.replace(result) { it.groupValues[1] }

        // Bold: **text** or __text__
        result = BOLD_ASTERISK_REGEX.replace(result) { it.groupValues[1] }
        result = BOLD_UNDERSCORE_REGEX.replace(result) { it.groupValues[1] }

        // Strikethrough: ~~text~~
        result = STRIKETHROUGH_REGEX.replace(result) { it.groupValues[1] }

        // Italic: *text* or _text_ (careful not to match within words for underscore)
        result = ITALIC_ASTERISK_REGEX.replace(result) { it.groupValues[1] }
        result = ITALIC_UNDERSCORE_REGEX.replace(result) { it.groupValues[1] }

        // Inline code: `code`
        result = INLINE_CODE_REGEX.replace(result) { it.groupValues[1] }

        return result
    }

    /**
     * Extracts cell content from a table row, joining cells with spaces.
     */
    private fun stripTableRow(row: String): String {
        val trimmed = row.trim()
        // Remove leading and trailing pipes, split by pipe, trim each cell
        val content = trimmed.removePrefix("|").removeSuffix("|")
        return content.split("|").joinToString(" ") { it.trim() }
    }

    private companion object {
        /** ESC character (0x1B) used in ANSI escape sequences */
        const val ESC = "\u001B"

        val CODE_FENCE_REGEX = Regex("""^`{3,}.*$""")
        val HORIZONTAL_RULE_REGEX = Regex("""^[-*_]{3,}$""")
        val TABLE_SEPARATOR_REGEX = Regex("""^\|?[\s\-:|]+\|[\s\-:|]*$""")
        val HEADING_REGEX = Regex("""^#{1,6}\s(.*)$""")
        val BLOCKQUOTE_REGEX = Regex("""^>\s?(.*)$""")
        val UNORDERED_LIST_REGEX = Regex("""^(\s*)[-*+]\s+(.*)$""")
        val ORDERED_LIST_REGEX = Regex("""^(\s*)\d+\.\s+(.*)$""")
        val TABLE_ROW_REGEX = Regex("""^\|.*\|$""")
        val IMAGE_REGEX = Regex("""!\[([^\]]*)\]\([^)]*\)""")
        val LINK_REGEX = Regex("""\[([^\]]*)\]\([^)]*\)""")
        val BOLD_ASTERISK_REGEX = Regex("""\*\*(.+?)\*\*""")
        val BOLD_UNDERSCORE_REGEX = Regex("""__(.+?)__""")
        val STRIKETHROUGH_REGEX = Regex("""~~(.+?)~~""")
        val ITALIC_ASTERISK_REGEX = Regex("""\*(.+?)\*""")
        val ITALIC_UNDERSCORE_REGEX = Regex("""(?<!\w)_(.+?)_(?!\w)""")
        val INLINE_CODE_REGEX = Regex("""`([^`]+)`""")
    }
}
