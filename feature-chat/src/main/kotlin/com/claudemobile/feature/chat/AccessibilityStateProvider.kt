package com.claudemobile.feature.chat

/**
 * Abstraction over accessibility service state detection.
 * Enables testing TalkBack announcement rate limiting without Android framework dependencies.
 */
public interface AccessibilityStateProvider {

    /**
     * Returns true if TalkBack (or equivalent screen reader) is currently enabled.
     */
    public fun isTalkBackEnabled(): Boolean

    /**
     * Announces the given [text] to the accessibility service for screen reader users.
     */
    public fun announce(text: String)
}
