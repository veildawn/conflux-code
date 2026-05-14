package com.claudemobile.core.bridge.workspace

/**
 * Abstraction over SharedPreferences for workspace history storage.
 * Enables unit testing without Android Context dependencies.
 */
public interface WorkspacePreferencesStore {

    /**
     * Returns the stored workspace entries as a JSON string, or null if none stored.
     */
    public fun getWorkspaceEntries(): String?

    /**
     * Persists the workspace entries as a JSON string.
     */
    public fun setWorkspaceEntries(json: String)

    /**
     * Clears all stored workspace entries.
     */
    public fun clearWorkspaceEntries()
}
