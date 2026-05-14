package com.claudemobile.core.domain.parser

import com.claudemobile.core.domain.model.ParseResult

/**
 * Parses raw byte streams from the PTY bridge into structured output events.
 *
 * Implemented as a pure function over its input buffer (no I/O, no global state
 * beyond the accumulating buffer), enabling property-based testing of the
 * round-trip property.
 */
public interface OutputParser {

    /**
     * Parses the given byte buffer and returns structured events along with
     * the number of bytes consumed. Unconsumed bytes should be retained by
     * the caller and prepended to the next buffer.
     */
    public fun parse(buffer: ByteArray): ParseResult

    /**
     * Resets the parser's internal state, discarding any accumulated buffer.
     */
    public fun reset()
}
