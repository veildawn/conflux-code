package com.claudemobile.core.bridge.service

/**
 * Abstraction over SharedPreferences for the foreground service state.
 * Enables unit testing without Android Context dependencies.
 */
public interface ServicePreferencesStore {

    /**
     * Returns the set of session IDs that were killed by the OS, or null if none.
     */
    public fun getKilledSessions(): Set<String>?

    /**
     * Clears the killed sessions record.
     */
    public fun clearKilledSessions()

    /**
     * Persists the set of currently active session IDs.
     */
    public fun setActiveSessions(sessionIds: Set<String>)

    /**
     * Returns the set of currently active session IDs, or null if none.
     */
    public fun getActiveSessions(): Set<String>?

    /**
     * Clears the active sessions record.
     */
    public fun clearActiveSessions()

    /**
     * Persists the set of session IDs that were killed by the OS.
     */
    public fun setKilledSessions(sessionIds: Set<String>)
}
