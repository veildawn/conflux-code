package com.claudemobile.core.domain.parser

import com.claudemobile.core.domain.model.OutputEvent
import com.claudemobile.core.domain.model.ParseResult
import com.claudemobile.core.domain.model.StyleHint
import com.claudemobile.core.domain.model.TextStyle

/**
 * Pure-function implementation of [OutputParser] that converts raw PTY byte streams
 * into structured [OutputEvent]s.
 *
 * The parser handles:
 * - ANSI escape sequences (SGR codes) → stripped from text, emitted as [StyleHint] metadata
 * - Tool-call sentinels → [OutputEvent.ToolCallStart] and [OutputEvent.ToolCallResult]
 * - Prompt lines → [OutputEvent.Prompt]
 * - Turn-completion markers → [OutputEvent.TurnComplete]
 * - Error recovery → single [OutputEvent.Error] on invalid frames, resync on next newline
 *
 * This implementation maintains no global mutable state beyond the internal style stack
 * used during a single [parse] invocation. It is safe for property-based testing.
 */
public class OutputParserImpl : OutputParser {

    private var activeStyles: MutableSet<TextStyle> = mutableSetOf()

    override fun parse(buffer: ByteArray): ParseResult {
        if (buffer.isEmpty()) {
            return ParseResult(events = emptyList(), remainingBuffer = byteArrayOf(), consumedBytes = 0)
        }

        val events = mutableListOf<OutputEvent>()
        var pos = 0
        val length = buffer.size

        while (pos < length) {
            // Try to parse a complete line or structure
            val lineEnd = findLineEnd(buffer, pos)

            if (lineEnd == -1) {
                // No complete line available — check if we can parse partial content
                // If we're at the start of an escape sequence or sentinel that might be incomplete,
                // stop and wait for more data
                if (buffer[pos] == ESC_BYTE && pos + 1 >= length) {
                    break
                }
                if (buffer[pos] == OPEN_BRACKET_BYTE && mightBeIncompleteSentinel(buffer, pos)) {
                    break
                }
                // Otherwise, we have trailing text without a newline — don't consume it yet
                // (incremental parsing: wait for the newline to arrive)
                break
            }

            // We have a complete line from pos to lineEnd (inclusive of newline)
            val lineBytes = buffer.sliceArray(pos..lineEnd)
            val lineLength = lineEnd - pos + 1

            // Try to parse the line as a special structure
            val specialEvent = tryParseSpecialLine(lineBytes)
            if (specialEvent != null) {
                events.add(specialEvent)
                pos += lineLength
                continue
            }

            // Parse as text with ANSI stripping
            val textEvent = parseTextLine(lineBytes)
            if (textEvent != null) {
                events.add(textEvent)
            }
            pos += lineLength
        }

        val remaining = if (pos < length) {
            buffer.sliceArray(pos until length)
        } else {
            byteArrayOf()
        }

        return ParseResult(events = events, remainingBuffer = remaining, consumedBytes = pos)
    }

    override fun reset() {
        activeStyles.clear()
    }

    /**
     * Attempts to parse a line as a special structure (tool-call sentinel, prompt, turn-complete).
     * Returns null if the line is plain text, or an Error event if the line looks like a malformed sentinel.
     */
    private fun tryParseSpecialLine(lineBytes: ByteArray): OutputEvent? {
        val lineStr = stripAnsiFromBytes(lineBytes).trimEnd('\n', '\r')

        // Turn-completion marker
        if (lineStr == TURN_COMPLETE_MARKER) {
            return OutputEvent.TurnComplete
        }

        // Tool-call start sentinel: [tool_call_start:toolname]arguments
        val toolStartMatch = TOOL_CALL_START_REGEX.matchEntire(lineStr)
        if (toolStartMatch != null) {
            val toolName = toolStartMatch.groupValues[1]
            val arguments = toolStartMatch.groupValues[2]
            if (toolName.isBlank()) {
                return OutputEvent.Error(
                    reason = "Malformed tool_call_start: empty tool name",
                    rawBytes = lineBytes
                )
            }
            return OutputEvent.ToolCallStart(toolName = toolName, arguments = arguments)
        }

        // Tool-call result sentinel: [tool_call_result:toolname:success/failure]result
        val toolResultMatch = TOOL_CALL_RESULT_REGEX.matchEntire(lineStr)
        if (toolResultMatch != null) {
            val toolName = toolResultMatch.groupValues[1]
            val successStr = toolResultMatch.groupValues[2]
            val result = toolResultMatch.groupValues[3]
            if (toolName.isBlank()) {
                return OutputEvent.Error(
                    reason = "Malformed tool_call_result: empty tool name",
                    rawBytes = lineBytes
                )
            }
            val success = successStr == "success"
            return OutputEvent.ToolCallResult(toolName = toolName, result = result, success = success)
        }

        // Check for malformed sentinels — lines that look like they intended to be
        // sentinels but don't match the grammar. This enables error recovery per Req 14.4.
        if (MALFORMED_SENTINEL_REGEX.containsMatchIn(lineStr)) {
            return OutputEvent.Error(
                reason = "Malformed sentinel: does not match expected grammar",
                rawBytes = lineBytes
            )
        }

        // Prompt line: "> " or "claude> " at line start
        val promptMatch = PROMPT_REGEX.matchEntire(lineStr)
        if (promptMatch != null) {
            return OutputEvent.Prompt(text = lineStr)
        }

        return null
    }

    /**
     * Parses a text line, stripping ANSI escape sequences and producing style hints.
     */
    private fun parseTextLine(lineBytes: ByteArray): OutputEvent.Text? {
        val result = stripAnsiWithHints(lineBytes)
        val content = result.first
        val hints = result.second

        if (content.isEmpty()) {
            return null
        }

        return OutputEvent.Text(content = content, styleHints = hints)
    }

    /**
     * Strips ANSI escape sequences from bytes and returns the plain text string
     * along with style hints derived from the SGR codes.
     */
    private fun stripAnsiWithHints(bytes: ByteArray): Pair<String, List<StyleHint>> {
        val textBuilder = StringBuilder()
        val hints = mutableListOf<StyleHint>()
        var i = 0
        val length = bytes.size

        // Track where each style starts in the output text
        val styleStarts = mutableMapOf<TextStyle, Int>()

        // Initialize style starts for any already-active styles
        for (style in activeStyles) {
            styleStarts[style] = 0
        }

        while (i < length) {
            if (bytes[i] == ESC_BYTE && i + 1 < length && bytes[i + 1] == OPEN_BRACKET_BYTE_ASCII) {
                // Parse ANSI CSI sequence: ESC [ ... m
                val seqEnd = findAnsiSequenceEnd(bytes, i)
                if (seqEnd != -1) {
                    // Extract the SGR parameters
                    val params = extractSgrParams(bytes, i + 2, seqEnd)
                    applyStyles(params, textBuilder.length, styleStarts, hints)
                    i = seqEnd + 1
                } else {
                    // Incomplete or malformed escape sequence — skip ESC byte
                    i++
                }
            } else {
                textBuilder.append(bytes[i].toInt().toChar())
                i++
            }
        }

        // Close any open style ranges at the end of the text
        val textLength = textBuilder.length
        for ((style, start) in styleStarts) {
            if (start < textLength) {
                hints.add(StyleHint(range = start until textLength, style = style))
            }
        }

        return Pair(textBuilder.toString(), hints)
    }

    /**
     * Strips ANSI escape sequences from bytes and returns plain text only.
     */
    private fun stripAnsiFromBytes(bytes: ByteArray): String {
        val textBuilder = StringBuilder()
        var i = 0
        val length = bytes.size

        while (i < length) {
            if (bytes[i] == ESC_BYTE && i + 1 < length && bytes[i + 1] == OPEN_BRACKET_BYTE_ASCII) {
                val seqEnd = findAnsiSequenceEnd(bytes, i)
                if (seqEnd != -1) {
                    i = seqEnd + 1
                } else {
                    i++
                }
            } else {
                textBuilder.append(bytes[i].toInt().toChar())
                i++
            }
        }

        return textBuilder.toString()
    }

    /**
     * Finds the end of an ANSI CSI sequence starting at [start].
     * Returns the index of the terminating byte (letter), or -1 if not found.
     */
    private fun findAnsiSequenceEnd(bytes: ByteArray, start: Int): Int {
        // CSI sequence: ESC [ (params) (terminator)
        // Params are digits and semicolons, terminator is a letter (0x40-0x7E)
        var i = start + 2 // skip ESC [
        val length = bytes.size

        while (i < length) {
            val b = bytes[i].toInt() and 0xFF
            if (b in 0x40..0x7E) {
                // Found terminator
                return i
            }
            if (b in 0x20..0x3F) {
                // Parameter or intermediate byte — continue
                i++
            } else {
                // Invalid byte in sequence
                return -1
            }
        }

        return -1 // Sequence not terminated within buffer
    }

    /**
     * Extracts SGR parameter codes from the CSI sequence between [start] and [end] (exclusive).
     */
    private fun extractSgrParams(bytes: ByteArray, start: Int, end: Int): List<Int> {
        // Check that the terminator is 'm' (SGR)
        if (bytes[end].toInt().toChar() != 'm') {
            return emptyList()
        }

        val paramStr = StringBuilder()
        for (i in start until end) {
            paramStr.append(bytes[i].toInt().toChar())
        }

        if (paramStr.isEmpty()) {
            return listOf(0) // ESC[m is equivalent to ESC[0m (reset)
        }

        return paramStr.split(';').mapNotNull { it.toIntOrNull() }
    }

    /**
     * Applies SGR style codes, updating active styles and emitting hints for closed ranges.
     */
    private fun applyStyles(
        params: List<Int>,
        currentTextPos: Int,
        styleStarts: MutableMap<TextStyle, Int>,
        hints: MutableList<StyleHint>
    ) {
        for (param in params) {
            when (param) {
                0 -> {
                    // Reset all styles
                    for ((style, start) in styleStarts) {
                        if (start < currentTextPos) {
                            hints.add(StyleHint(range = start until currentTextPos, style = style))
                        }
                    }
                    styleStarts.clear()
                    activeStyles.clear()
                }
                1 -> activateStyle(TextStyle.BOLD, currentTextPos, styleStarts)
                2 -> activateStyle(TextStyle.DIM, currentTextPos, styleStarts)
                3 -> activateStyle(TextStyle.ITALIC, currentTextPos, styleStarts)
                4 -> activateStyle(TextStyle.UNDERLINE, currentTextPos, styleStarts)
                9 -> activateStyle(TextStyle.STRIKETHROUGH, currentTextPos, styleStarts)
                22 -> deactivateStyle(TextStyle.BOLD, currentTextPos, styleStarts, hints).also {
                    deactivateStyle(TextStyle.DIM, currentTextPos, styleStarts, hints)
                }
                23 -> deactivateStyle(TextStyle.ITALIC, currentTextPos, styleStarts, hints)
                24 -> deactivateStyle(TextStyle.UNDERLINE, currentTextPos, styleStarts, hints)
                29 -> deactivateStyle(TextStyle.STRIKETHROUGH, currentTextPos, styleStarts, hints)
            }
        }
    }

    private fun activateStyle(
        style: TextStyle,
        currentTextPos: Int,
        styleStarts: MutableMap<TextStyle, Int>
    ) {
        if (style !in activeStyles) {
            activeStyles.add(style)
            styleStarts[style] = currentTextPos
        }
    }

    private fun deactivateStyle(
        style: TextStyle,
        currentTextPos: Int,
        styleStarts: MutableMap<TextStyle, Int>,
        hints: MutableList<StyleHint>
    ) {
        if (style in activeStyles) {
            val start = styleStarts.remove(style)
            if (start != null && start < currentTextPos) {
                hints.add(StyleHint(range = start until currentTextPos, style = style))
            }
            activeStyles.remove(style)
        }
    }

    /**
     * Finds the index of the next newline character (\n) starting from [start].
     * Returns the index of the newline, or -1 if not found.
     */
    private fun findLineEnd(buffer: ByteArray, start: Int): Int {
        for (i in start until buffer.size) {
            if (buffer[i] == NEWLINE_BYTE) {
                return i
            }
        }
        return -1
    }

    /**
     * Checks if the buffer starting at [pos] might be an incomplete sentinel bracket sequence.
     * This prevents consuming partial sentinels during incremental parsing.
     */
    private fun mightBeIncompleteSentinel(buffer: ByteArray, pos: Int): Boolean {
        // Check if it starts with '[' and could be a sentinel prefix
        if (buffer[pos] != OPEN_BRACKET_BYTE) return false

        val remaining = buffer.size - pos
        if (remaining >= MAX_SENTINEL_LENGTH) return false

        // Check if the content so far matches a sentinel prefix
        val partial = String(buffer, pos, remaining, Charsets.UTF_8)
        return partial.startsWith("[tool_call_") ||
            partial.startsWith("[turn_") ||
            PARTIAL_SENTINEL_REGEX.containsMatchIn(partial)
    }

    private companion object {
        const val ESC_BYTE: Byte = 0x1B
        const val OPEN_BRACKET_BYTE_ASCII: Byte = 0x5B // '['
        const val OPEN_BRACKET_BYTE: Byte = 0x5B // '['
        const val NEWLINE_BYTE: Byte = 0x0A // '\n'
        const val MAX_SENTINEL_LENGTH = 100

        const val TURN_COMPLETE_MARKER = "[turn_complete]"

        // Tool-call start: [tool_call_start:toolname]optional_arguments
        val TOOL_CALL_START_REGEX = Regex("""\[tool_call_start:([^\]]+)\](.*)""")

        // Tool-call result: [tool_call_result:toolname:success/failure]optional_result
        val TOOL_CALL_RESULT_REGEX = Regex("""\[tool_call_result:([^:]+):(success|failure)\](.*)""")

        // Detects malformed sentinels — lines that start with a sentinel-like pattern
        // but don't match the full grammar (e.g., missing closing bracket, wrong format)
        val MALFORMED_SENTINEL_REGEX = Regex("""\[tool_call_(?:start|result)[^\]]*$|\[turn_[^\]]*$""")

        // Prompt line: "> " or "word> " at line start
        val PROMPT_REGEX = Regex("""^(\w*>\s?)$""")

        // Partial sentinel detection for incomplete buffer handling
        val PARTIAL_SENTINEL_REGEX = Regex("""^\[(?:t(?:o(?:o(?:l(?:_(?:c(?:a(?:l(?:l(?:_(?:s(?:t(?:a(?:r(?:t)?)?)?)?|r(?:e(?:s(?:u(?:l(?:t)?)?)?)?)?)?)?)?)?)?)?)?)?)?|u(?:r(?:n(?:_(?:c(?:o(?:m(?:p(?:l(?:e(?:t(?:e)?)?)?)?)?)?)?)?)?)?)?)?)?""")
    }
}
