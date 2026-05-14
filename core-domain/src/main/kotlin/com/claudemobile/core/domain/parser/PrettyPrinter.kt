package com.claudemobile.core.domain.parser

import com.claudemobile.core.domain.model.OutputEvent

/**
 * Serializes and renders output events for display and round-trip testing.
 *
 * This interface provides platform-agnostic operations. The Compose-specific
 * rendering (AnnotatedString) is handled in the core-ui module.
 */
public interface PrettyPrinter {

    /**
     * Serializes a list of output events back into a byte stream that can be
     * re-parsed by the OutputParser to produce equivalent events (round-trip property).
     */
    public fun eventsToBytes(events: List<OutputEvent>): ByteArray

    /**
     * Extracts the plain text content from a list of output events,
     * with markdown syntax removed but semantic content preserved.
     */
    public fun extractPlainText(events: List<OutputEvent>): String
}
