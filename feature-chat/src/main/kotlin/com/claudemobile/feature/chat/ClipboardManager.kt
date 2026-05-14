package com.claudemobile.feature.chat

/**
 * Abstraction over the system clipboard for copying text content.
 * Enables testing without Android framework dependencies.
 */
public interface ClipboardManager {

    /**
     * Copies the given [text] to the system clipboard.
     */
    public fun copyToClipboard(text: String)
}
