package com.claudemobile.feature.sessions

import com.claudemobile.core.domain.model.Session

/**
 * UI state for the Sessions list screen following UDF pattern.
 */
sealed interface SessionsUiState {
    /** Initial loading state while sessions are being fetched. */
    data object Loading : SessionsUiState

    /** Sessions loaded successfully. */
    data class Success(
        val sessions: List<Session>,
        val searchQuery: String = "",
        val filteredSessions: List<Session> = sessions,
    ) : SessionsUiState

    /** No sessions exist yet. */
    data object Empty : SessionsUiState

    /** An error occurred while loading sessions. */
    data class Error(val message: String) : SessionsUiState
}
