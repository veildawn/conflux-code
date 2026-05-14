package com.claudemobile.core.domain.model

/**
 * A structured event produced by the Output Parser from raw PTY byte streams.
 */
public sealed interface OutputEvent {

    /**
     * A text chunk with optional style hints derived from ANSI escape sequences.
     */
    public data class Text(
        val content: String,
        val styleHints: List<StyleHint> = emptyList()
    ) : OutputEvent

    /**
     * Indicates the start of a tool call with the tool name and arguments.
     */
    public data class ToolCallStart(
        val toolName: String,
        val arguments: String
    ) : OutputEvent

    /**
     * The result of a completed tool call.
     */
    public data class ToolCallResult(
        val toolName: String,
        val result: String,
        val success: Boolean
    ) : OutputEvent

    /**
     * A prompt line emitted by Claude CLI awaiting user input.
     */
    public data class Prompt(val text: String) : OutputEvent

    /**
     * Indicates that the current turn is complete.
     */
    public data object TurnComplete : OutputEvent

    /**
     * An error encountered during parsing, with optional raw bytes for diagnostics.
     */
    public data class Error(
        val reason: String,
        val rawBytes: ByteArray?
    ) : OutputEvent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Error) return false
            return reason == other.reason && rawBytes.contentEquals(other.rawBytes)
        }

        override fun hashCode(): Int {
            var result = reason.hashCode()
            result = 31 * result + (rawBytes?.contentHashCode() ?: 0)
            return result
        }
    }
}

/**
 * A style hint indicating a text style applied to a range within a text chunk.
 */
public data class StyleHint(
    val range: IntRange,
    val style: TextStyle
)

/**
 * Text styles that can be derived from ANSI escape sequences.
 */
public enum class TextStyle {
    BOLD,
    ITALIC,
    UNDERLINE,
    STRIKETHROUGH,
    DIM,
    CODE
}
