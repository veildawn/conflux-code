package com.claudemobile.core.domain.parser

import com.claudemobile.core.domain.model.OutputEvent
import com.claudemobile.core.domain.model.ParseResult

/**
 * Stream-JSON output parser for Claude CLI's `--output-format stream-json` mode.
 *
 * Each line of stdout is a self-contained JSON object. The parser:
 * 1. Splits the incoming byte buffer on newline boundaries.
 * 2. Attempts to parse each complete line as a JSON event.
 * 3. Maps recognized event types to [OutputEvent] variants.
 * 4. Holds incomplete trailing bytes in [ParseResult.remainingBuffer].
 *
 * Recognized event types from Claude CLI stream-json:
 * - `{"type":"system","subtype":"init",...}` → ignored (startup info)
 * - `{"type":"assistant","subtype":"text","text":"..."}` → [OutputEvent.Text]
 * - `{"type":"assistant","subtype":"tool_use","name":"...","input":{...}}` → [OutputEvent.ToolCallStart]
 * - `{"type":"tool_result","name":"...","content":"...","is_error":false}` → [OutputEvent.ToolCallResult]
 * - `{"type":"result",...}` → [OutputEvent.TurnComplete]
 * - Any unrecognized type → ignored (forward-compatible)
 *
 * This parser uses simple string matching rather than a full JSON library to
 * avoid adding kotlinx.serialization as a dependency to core-domain. The
 * stream-json format guarantees one JSON object per line with no nested newlines
 * in string values (they are escaped as `\n`).
 */
public class OutputParserImpl : OutputParser {

    override fun parse(buffer: ByteArray): ParseResult {
        if (buffer.isEmpty()) {
            return ParseResult(events = emptyList(), remainingBuffer = byteArrayOf(), consumedBytes = 0)
        }

        val events = mutableListOf<OutputEvent>()
        var pos = 0
        val length = buffer.size

        while (pos < length) {
            val lineEnd = indexOf(buffer, NEWLINE_BYTE, pos)
            if (lineEnd == -1) {
                // No complete line — hold remainder
                break
            }

            // Extract the line (excluding the newline itself)
            val lineBytes = buffer.sliceArray(pos until lineEnd)
            pos = lineEnd + 1

            val line = String(lineBytes, Charsets.UTF_8).trim()
            if (line.isEmpty()) continue

            val event = parseLine(line)
            if (event != null) {
                events.add(event)
            }
        }

        val remaining = if (pos < length) {
            buffer.sliceArray(pos until length)
        } else {
            byteArrayOf()
        }

        return ParseResult(events = events, remainingBuffer = remaining, consumedBytes = pos)
    }

    override fun reset() {
        // No state to reset in the JSON line parser
    }

    /**
     * Parses a single JSON line into an [OutputEvent], or null if the event
     * type is not relevant to the UI (e.g. system init messages).
     */
    private fun parseLine(line: String): OutputEvent? {
        // Quick reject: must start with '{'
        if (!line.startsWith("{")) return null

        val type = extractStringField(line, "type") ?: return null

        return when (type) {
            "content_block_delta" -> {
                // {"type":"content_block_delta","delta":{"type":"text_delta","text":"..."}}
                val text = extractNestedTextField(line)
                if (text != null) {
                    OutputEvent.Text(content = text)
                } else {
                    null
                }
            }

            "assistant" -> {
                // {"type":"assistant","message":{"id":"...","content":[{"type":"text","text":"..."}],...}}
                // Content array may contain: text, thinking, tool_use blocks
                val content = line.substringAfter("\"content\":[", "")
                if (content.isEmpty()) return null

                when {
                    // Text content block
                    content.contains("\"type\":\"text\"") -> {
                        val text = extractAssistantText(line)
                        if (text != null && text.isNotEmpty()) {
                            OutputEvent.Text(content = text)
                        } else null
                    }
                    // Tool use block
                    content.contains("\"type\":\"tool_use\"") -> {
                        val name = extractStringField(line, "name") ?: "unknown"
                        // Extract the command or input description
                        val command = extractStringField(line, "command")
                        val description = extractStringField(line, "description")
                        val input = command ?: description ?: ""
                        OutputEvent.ToolCallStart(toolName = name, arguments = input)
                    }
                    // Thinking block — show as text with a prefix
                    content.contains("\"type\":\"thinking\"") -> {
                        val thinking = extractStringField(line, "thinking")
                        if (thinking != null && thinking.isNotEmpty()) {
                            OutputEvent.Text(content = thinking + "\n")
                        } else null
                    }
                    else -> null
                }
            }

            "content_block_start" -> {
                // {"type":"content_block_start","content_block":{"type":"tool_use","id":"...","name":"...","input":{}}}
                if (line.contains("\"tool_use\"")) {
                    val name = extractStringField(line, "name") ?: "unknown"
                    OutputEvent.ToolCallStart(toolName = name, arguments = "")
                } else {
                    null
                }
            }

            "tool_result" -> {
                val name = extractStringField(line, "name") ?: "unknown"
                val content = extractStringField(line, "content") ?: ""
                val isError = line.contains("\"is_error\":true") || line.contains("\"is_error\": true")
                OutputEvent.ToolCallResult(toolName = name, result = content, success = !isError)
            }

            "user" -> {
                // Tool result feedback from CLI: {"type":"user","message":{"content":[{"type":"tool_result","content":"...","is_error":true/false}]}}
                val toolResult = extractStringField(line, "tool_use_result")
                    ?: extractStringField(line, "content")
                if (toolResult != null) {
                    val isError = line.contains("\"is_error\":true") || line.contains("\"is_error\": true")
                    val toolName = extractStringField(line, "tool_use_id") ?: "tool"
                    OutputEvent.ToolCallResult(toolName = toolName, result = toolResult, success = !isError)
                } else {
                    null
                }
            }

            "result" -> {
                // Check if it's an error result
                val isError = line.contains("\"is_error\":true") || line.contains("\"is_error\": true")
                if (isError) {
                    val errors = extractStringField(line, "errors")
                        ?: extractStringField(line, "terminal_reason")
                        ?: "Task completed with errors"
                    OutputEvent.Text(content = "\n⚠️ $errors\n")
                }
                OutputEvent.TurnComplete
            }

            "message_stop" -> {
                OutputEvent.TurnComplete
            }

            "error" -> {
                val message = extractStringField(line, "message")
                    ?: extractStringField(line, "error")
                    ?: "Unknown error"
                OutputEvent.Error(reason = message, rawBytes = line.toByteArray(Charsets.UTF_8))
            }

            // Ignore system, ping, message_start, content_block_stop, etc.
            else -> null
        }
    }

    /**
     * Extracts a top-level string field value from a JSON line using simple
     * string matching. Returns the unescaped value or null if not found.
     *
     * Handles: `"field":"value"` and `"field": "value"`
     */
    private fun extractStringField(json: String, field: String): String? {
        val key = "\"$field\""
        val keyIdx = json.indexOf(key)
        if (keyIdx == -1) return null

        // Find the colon after the key
        val colonIdx = json.indexOf(':', keyIdx + key.length)
        if (colonIdx == -1) return null

        // Find the opening quote of the value
        val valueStart = json.indexOf('"', colonIdx + 1)
        if (valueStart == -1) return null

        // Find the closing quote (handling escaped quotes)
        var i = valueStart + 1
        val sb = StringBuilder()
        while (i < json.length) {
            val ch = json[i]
            if (ch == '\\' && i + 1 < json.length) {
                val next = json[i + 1]
                when (next) {
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    'u' -> {
                        if (i + 5 < json.length) {
                            val hex = json.substring(i + 2, i + 6)
                            val codePoint = hex.toIntOrNull(16)
                            if (codePoint != null) {
                                sb.append(codePoint.toChar())
                            }
                            i += 6
                        } else {
                            sb.append(ch)
                            i++
                        }
                    }
                    else -> { sb.append(ch); i++ }
                }
            } else if (ch == '"') {
                return sb.toString()
            } else {
                sb.append(ch)
                i++
            }
        }
        return null // Unterminated string
    }

    /**
     * Extracts text from a content_block_delta event:
     * `{"type":"content_block_delta","delta":{"type":"text_delta","text":"..."}}`
     */
    private fun extractNestedTextField(json: String): String? {
        // Look for "text" field that comes after "text_delta"
        val deltaIdx = json.indexOf("\"text_delta\"")
        if (deltaIdx == -1) return null
        // Find the "text" field after the delta type
        val textKeyIdx = json.indexOf("\"text\"", deltaIdx)
        if (textKeyIdx == -1) return null
        val colonIdx = json.indexOf(':', textKeyIdx + 6)
        if (colonIdx == -1) return null
        val valueStart = json.indexOf('"', colonIdx + 1)
        if (valueStart == -1) return null

        return extractStringValueAt(json, valueStart)
    }

    /**
     * Extracts text content from an assistant message event.
     * Format: `{"type":"assistant","message":{"content":[{"type":"text","text":"..."},...],...}}`
     *
     * The content array may contain multiple blocks (thinking, text, tool_use).
     * We extract and concatenate all "text" type blocks.
     */
    private fun extractAssistantText(json: String): String? {
        val sb = StringBuilder()
        // Find all occurrences of "type":"text" followed by "text":"..."
        // We need to distinguish from the top-level "type":"assistant"
        var searchFrom = 0
        while (true) {
            // Find next "type":"text" (the content block type, not the top-level type)
            val typeTextIdx = json.indexOf("\"type\":\"text\"", searchFrom)
            if (typeTextIdx == -1) break
            // Make sure this isn't the top-level type by checking it's after "content"
            val contentIdx = json.indexOf("\"content\"")
            if (contentIdx == -1 || typeTextIdx < contentIdx) {
                searchFrom = typeTextIdx + 12
                continue
            }
            // Find the "text" field after this type marker
            val textKeyIdx = json.indexOf("\"text\":", typeTextIdx + 12)
            if (textKeyIdx == -1) break
            // Skip to the value
            val valueStart = json.indexOf('"', textKeyIdx + 7)
            if (valueStart == -1) break
            val extracted = extractStringValueAt(json, valueStart)
            if (extracted != null) {
                sb.append(extracted)
            }
            searchFrom = textKeyIdx + 7
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    /**
     * Extracts a JSON string value starting at the opening quote position.
     * Returns the unescaped content, or null if malformed.
     */
    private fun extractStringValueAt(json: String, openQuoteIdx: Int): String? {
        var i = openQuoteIdx + 1
        val sb = StringBuilder()
        while (i < json.length) {
            val ch = json[i]
            if (ch == '\\' && i + 1 < json.length) {
                val next = json[i + 1]
                when (next) {
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    'u' -> {
                        if (i + 5 < json.length) {
                            val hex = json.substring(i + 2, i + 6)
                            val codePoint = hex.toIntOrNull(16)
                            if (codePoint != null) {
                                sb.append(codePoint.toChar())
                            }
                            i += 6
                        } else {
                            sb.append(ch)
                            i++
                        }
                    }
                    else -> { sb.append(ch); i++ }
                }
            } else if (ch == '"') {
                return sb.toString()
            } else {
                sb.append(ch)
                i++
            }
        }
        return null
    }

    private companion object {
        const val NEWLINE_BYTE: Byte = 0x0A

        fun indexOf(buffer: ByteArray, target: Byte, from: Int): Int {
            for (i in from until buffer.size) {
                if (buffer[i] == target) return i
            }
            return -1
        }
    }
}
