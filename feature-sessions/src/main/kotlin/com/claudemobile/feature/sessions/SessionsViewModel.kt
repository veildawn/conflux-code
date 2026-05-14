package com.claudemobile.feature.sessions

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claudemobile.core.common.AppResult
import com.claudemobile.core.domain.model.Session
import com.claudemobile.core.domain.model.SessionId
import com.claudemobile.core.domain.providers.usecase.GetActiveProfileUseCase
import com.claudemobile.core.domain.usecase.CreateSessionUseCase
import com.claudemobile.core.domain.usecase.DeleteSessionUseCase
import com.claudemobile.core.domain.usecase.GetSessionsUseCase
import com.claudemobile.core.domain.usecase.RenameSessionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Sessions list screen.
 * Exposes [SessionsUiState] as a [StateFlow] and accepts [SessionsAction] values
 * following the unidirectional data flow pattern.
 *
 * Supports offline operations: browsing, searching, copying, and deleting sessions
 * all work from the local database without requiring network connectivity.
 *
 * Implements ai-provider-presets R5 AC5 / R11 AC5: when no Active_Profile is set,
 * [canStartNewSession] is `false` and attempting to start a session emits a
 * [navigateToProviderSelectionEvents] effect instead of creating a session.
 */
@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val getSessionsUseCase: GetSessionsUseCase,
    private val createSessionUseCase: CreateSessionUseCase,
    private val deleteSessionUseCase: DeleteSessionUseCase,
    private val renameSessionUseCase: RenameSessionUseCase,
    private val getActiveProfileUseCase: GetActiveProfileUseCase,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow<SessionsUiState>(SessionsUiState.Loading)
    val uiState: StateFlow<SessionsUiState> = _uiState.asStateFlow()

    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    /**
     * `true` when an Active_Profile is set; `false` when none is selected.
     *
     * The UI should disable the "+ New session" FAB and show an explanatory
     * banner while this is `false` (R5 AC5, R11 AC5).
     */
    val canStartNewSession: StateFlow<Boolean> =
        getActiveProfileUseCase()
            .map { it != null }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )

    /**
     * One-shot navigation events emitted when a new session has been created.
     * The UI collects this and navigates to the chat screen for the new session.
     */
    private val _newSessionEvents = MutableSharedFlow<SessionId>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val newSessionEvents: SharedFlow<SessionId> = _newSessionEvents.asSharedFlow()

    /**
     * One-shot navigation events emitted when the user attempts to start a new
     * session but no Active_Profile is set (R5 AC5, R11 AC5).
     *
     * The UI collects this and navigates to the provider-selection screen,
     * displaying an explanatory message.
     */
    private val _navigateToProviderSelectionEvents = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val navigateToProviderSelectionEvents: SharedFlow<Unit> =
        _navigateToProviderSelectionEvents.asSharedFlow()

    /** Current search query, kept separate to survive state transitions. */
    private var currentSearchQuery: String = ""

    /** Cached full session list for local filtering. */
    private var allSessions: List<Session> = emptyList()

    init {
        observeSessions()
    }

    /**
     * Dispatches a user action to the ViewModel.
     */
    fun onAction(action: SessionsAction) {
        when (action) {
            is SessionsAction.CreateSession -> createSession(action)
            is SessionsAction.DeleteSession -> deleteSession(action.sessionId)
            is SessionsAction.RenameSession -> renameSession(action.sessionId, action.newTitle)
            is SessionsAction.Search -> updateSearch(action.query)
            is SessionsAction.Retry -> retry()
            is SessionsAction.DismissError -> dismissError()
        }
    }

    private fun observeSessions() {
        _uiState.value = SessionsUiState.Loading
        getSessionsUseCase()
            .onEach { sessions ->
                allSessions = sessions
                _uiState.value = if (sessions.isEmpty()) {
                    SessionsUiState.Empty
                } else {
                    val filtered = filterSessions(sessions, currentSearchQuery)
                    SessionsUiState.Success(
                        sessions = sessions,
                        searchQuery = currentSearchQuery,
                        filteredSessions = filtered,
                    )
                }
            }
            .catch { throwable ->
                _uiState.value = SessionsUiState.Error(
                    throwable.message ?: "Failed to load sessions"
                )
            }
            .launchIn(viewModelScope)
    }

    private fun updateSearch(query: String) {
        currentSearchQuery = query
        val currentState = _uiState.value
        if (currentState is SessionsUiState.Success) {
            val filtered = filterSessions(currentState.sessions, query)
            _uiState.value = currentState.copy(
                searchQuery = query,
                filteredSessions = filtered,
            )
        }
    }

    private fun filterSessions(sessions: List<Session>, query: String): List<Session> {
        if (query.isBlank()) return sessions
        val lowerQuery = query.lowercase()
        return sessions.filter { session ->
            session.title.lowercase().contains(lowerQuery) ||
                session.workspacePath.lowercase().contains(lowerQuery)
        }
    }

    private fun createSession(action: SessionsAction.CreateSession) {
        viewModelScope.launch {
            // Gate on Active_Profile (R5 AC5, R11 AC5): if no profile is set, route to
            // provider selection instead of attempting to create a session.
            val activeProfile = getActiveProfileUseCase().first()
            if (activeProfile == null) {
                _navigateToProviderSelectionEvents.tryEmit(Unit)
                return@launch
            }

            takePersistableUriPermission(action.workspaceUri)

            when (val result = createSessionUseCase(action.title, action.workspacePath)) {
                is AppResult.Success -> {
                    // Emit a one-shot event so the UI can navigate to the new session.
                    _newSessionEvents.tryEmit(result.value.id)
                }
                is AppResult.Failure -> {
                    _actionError.value = result.error.message
                }
            }
        }
    }

    private fun deleteSession(sessionId: SessionId) {
        viewModelScope.launch {
            when (val result = deleteSessionUseCase(sessionId)) {
                is AppResult.Success -> {
                    // Session deleted; the Flow will emit the updated list
                }
                is AppResult.Failure -> {
                    _actionError.value = result.error.message
                }
            }
        }
    }

    private fun renameSession(sessionId: SessionId, newTitle: String) {
        viewModelScope.launch {
            when (val result = renameSessionUseCase(sessionId, newTitle)) {
                is AppResult.Success -> {
                    // Session renamed; the Flow will emit the updated list
                }
                is AppResult.Failure -> {
                    _actionError.value = result.error.message
                }
            }
        }
    }

    private fun retry() {
        observeSessions()
    }

    private fun dismissError() {
        _actionError.value = null
    }

    /**
     * Attempts to persist URI permission for the workspace directory.
     * Falls back to temporary access if persistence fails.
     */
    private fun takePersistableUriPermission(uri: Uri) {
        try {
            val contentResolver: ContentResolver = context.contentResolver
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            // Fallback to temporary access for the current process lifetime.
            // The user will be informed that the workspace may become inaccessible on next launch.
            _actionError.value =
                "Could not persist workspace access. The workspace will become inaccessible on the next app launch."
        }
    }
}
