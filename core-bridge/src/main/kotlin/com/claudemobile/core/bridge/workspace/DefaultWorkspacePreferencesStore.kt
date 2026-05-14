package com.claudemobile.core.bridge.workspace

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default implementation of [WorkspacePreferencesStore] backed by Android SharedPreferences.
 */
@Singleton
public class DefaultWorkspacePreferencesStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : WorkspacePreferencesStore {

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getWorkspaceEntries(): String? {
        return prefs.getString(PREF_WORKSPACE_ENTRIES, null)
    }

    override fun setWorkspaceEntries(json: String) {
        prefs.edit().putString(PREF_WORKSPACE_ENTRIES, json).apply()
    }

    override fun clearWorkspaceEntries() {
        prefs.edit().remove(PREF_WORKSPACE_ENTRIES).apply()
    }

    private companion object {
        const val PREFS_NAME = "workspace_prefs"
        const val PREF_WORKSPACE_ENTRIES = "workspace_entries"
    }
}
