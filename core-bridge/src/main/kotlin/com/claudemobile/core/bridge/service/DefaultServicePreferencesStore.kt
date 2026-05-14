package com.claudemobile.core.bridge.service

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default implementation of [ServicePreferencesStore] backed by Android SharedPreferences.
 */
@Singleton
public class DefaultServicePreferencesStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : ServicePreferencesStore {

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getKilledSessions(): Set<String>? {
        return prefs.getStringSet(PREF_KILLED_SESSIONS, null)
    }

    override fun clearKilledSessions() {
        prefs.edit().remove(PREF_KILLED_SESSIONS).apply()
    }

    override fun setActiveSessions(sessionIds: Set<String>) {
        prefs.edit().putStringSet(PREF_ACTIVE_SESSIONS, sessionIds).apply()
    }

    override fun getActiveSessions(): Set<String>? {
        return prefs.getStringSet(PREF_ACTIVE_SESSIONS, null)
    }

    override fun clearActiveSessions() {
        prefs.edit().remove(PREF_ACTIVE_SESSIONS).apply()
    }

    override fun setKilledSessions(sessionIds: Set<String>) {
        prefs.edit().putStringSet(PREF_KILLED_SESSIONS, sessionIds).apply()
    }

    private companion object {
        const val PREFS_NAME = "claude_session_service_prefs"
        const val PREF_ACTIVE_SESSIONS = "active_sessions"
        const val PREF_KILLED_SESSIONS = "killed_sessions"
    }
}
