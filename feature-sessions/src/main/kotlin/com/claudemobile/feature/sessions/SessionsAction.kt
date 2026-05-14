package com.claudemobile.feature.sessions

import android.net.Uri
import com.claudemobile.core.domain.model.SessionId

/**
 * Actions that can be dispatched to the SessionsViewModel.
 */
sealed interface SessionsAction {
    /** Create a new session with the given title and workspace URI. */
    data class CreateSession(
        val title: String,
        val workspaceUri: Uri,
        val workspacePath: String,
    ) : SessionsAction

    /** Delete a session after user confirmation. */
    data class DeleteSession(val sessionId: SessionId) : SessionsAction

    /** Rename a session to a new title. */
    data class RenameSession(val sessionId: SessionId, val newTitle: String) : SessionsAction

    /** Update the search query to filter sessions locally. */
    data class Search(val query: String) : SessionsAction

    /** Retry loading sessions after a failure. */
    data object Retry : SessionsAction

    /** Dismiss any transient error message. */
    data object DismissError : SessionsAction
}
