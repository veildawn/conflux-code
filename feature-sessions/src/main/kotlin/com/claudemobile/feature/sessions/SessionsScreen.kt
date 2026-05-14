package com.claudemobile.feature.sessions

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claudemobile.core.domain.model.Session
import com.claudemobile.core.domain.model.SessionId
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Sessions list screen composable.
 *
 * Supports offline operations: browsing, searching, copying, and deleting sessions
 * all work from the local database without requiring network connectivity.
 *
 * @param viewModel The [SessionsViewModel] providing state and handling actions.
 * @param onSessionClick Callback when a session is selected for navigation.
 * @param onNavigateToProviderSelection Callback when the user must configure a provider
 *   before starting a session (R5 AC5, R11 AC5).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    viewModel: SessionsViewModel,
    onSessionClick: (SessionId) -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToProviderSelection: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val actionError by viewModel.actionError.collectAsStateWithLifecycle()
    val canStartNewSession by viewModel.canStartNewSession.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    var showNewSessionDialog by remember { mutableStateOf(false) }
    var deleteConfirmSession by remember { mutableStateOf<Session?>(null) }
    var renameSession by remember { mutableStateOf<Session?>(null) }
    var showSearchBar by remember { mutableStateOf(false) }

    // Navigate to chat screen immediately after a new session is created.
    LaunchedEffect(viewModel) {
        viewModel.newSessionEvents.collect { sessionId ->
            onSessionClick(sessionId)
        }
    }

    // Navigate to provider selection when no Active_Profile is set (R5 AC5, R11 AC5).
    LaunchedEffect(viewModel) {
        viewModel.navigateToProviderSelectionEvents.collect {
            onNavigateToProviderSelection()
        }
    }

    // Show action errors in snackbar
    LaunchedEffect(actionError) {
        actionError?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.onAction(SessionsAction.DismissError)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            val searchDescription = stringResource(R.string.sessions_search_description)
            TopAppBar(
                title = { Text(stringResource(R.string.sessions_title)) },
                actions = {
                    IconButton(
                        onClick = { showSearchBar = !showSearchBar },
                        modifier = Modifier.semantics {
                            contentDescription = searchDescription
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                        )
                    }
                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier.semantics {
                            contentDescription = "Settings"
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            val fabDescription = stringResource(R.string.sessions_create_fab_description)
            FloatingActionButton(
                onClick = {
                    if (canStartNewSession) {
                        showNewSessionDialog = true
                    } else {
                        onNavigateToProviderSelection()
                    }
                },
                containerColor = if (canStartNewSession) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = Modifier.semantics {
                    contentDescription = fabDescription
                },
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = if (canStartNewSession) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            // No-provider banner: shown when no Active_Profile is set (R5 AC5, R11 AC5).
            if (!canStartNewSession) {
                NoActiveProfileBanner(onConfigureClick = onNavigateToProviderSelection)
            }

            // Search bar
            if (showSearchBar) {
                val searchQuery = (uiState as? SessionsUiState.Success)?.searchQuery ?: ""
                SearchBar(
                    query = searchQuery,
                    onQueryChange = { viewModel.onAction(SessionsAction.Search(it)) },
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when (val state = uiState) {
                    is SessionsUiState.Loading -> LoadingContent()
                    is SessionsUiState.Empty -> EmptyContent(
                        onCreateClick = { showNewSessionDialog = true },
                    )
                    is SessionsUiState.Error -> ErrorContent(
                        message = state.message,
                        onRetry = { viewModel.onAction(SessionsAction.Retry) },
                    )
                    is SessionsUiState.Success -> SessionsList(
                        sessions = state.filteredSessions,
                        onSessionClick = onSessionClick,
                        onDeleteClick = { session -> deleteConfirmSession = session },
                        onRenameClick = { session -> renameSession = session },
                    )
                }
            }
        }
    }

    // New session dialog
    if (showNewSessionDialog) {
        NewSessionDialog(
            onDismiss = { showNewSessionDialog = false },
            onConfirm = { title, uri, path ->
                viewModel.onAction(
                    SessionsAction.CreateSession(
                        title = title,
                        workspaceUri = uri,
                        workspacePath = path,
                    )
                )
                showNewSessionDialog = false
            },
        )
    }

    // Delete confirmation dialog
    deleteConfirmSession?.let { session ->
        DeleteConfirmationDialog(
            sessionTitle = session.title,
            onDismiss = { deleteConfirmSession = null },
            onConfirm = {
                viewModel.onAction(SessionsAction.DeleteSession(session.id))
                deleteConfirmSession = null
            },
        )
    }

    // Rename dialog
    renameSession?.let { session ->
        RenameSessionDialog(
            currentTitle = session.title,
            onDismiss = { renameSession = null },
            onConfirm = { newTitle ->
                viewModel.onAction(SessionsAction.RenameSession(session.id, newTitle))
                renameSession = null
            },
        )
    }
}

/**
 * Banner shown when no Active_Profile is set, prompting the user to configure a provider
 * before they can start a new session (R5 AC5, R11 AC5).
 */
@Composable
private fun NoActiveProfileBanner(onConfigureClick: () -> Unit) {
    val bannerDescription = stringResource(R.string.sessions_no_provider_banner_description)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { contentDescription = bannerDescription },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.sessions_no_provider_banner_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        TextButton(onClick = onConfigureClick) {
            Text(
                text = stringResource(R.string.sessions_no_provider_banner_action),
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val inputDescription = stringResource(R.string.sessions_search_input_description)
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(stringResource(R.string.sessions_search_placeholder)) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
            )
        },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics {
                contentDescription = inputDescription
            },
    )
}

@Composable
private fun LoadingContent() {
    val loadingDescription = stringResource(R.string.sessions_loading_description)
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.semantics {
                contentDescription = loadingDescription
            },
        )
    }
}

@Composable
private fun EmptyContent(onCreateClick: () -> Unit) {
    val buttonDescription = stringResource(R.string.sessions_empty_button_description)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.sessions_empty_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.sessions_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))
        TextButton(
            onClick = onCreateClick,
            modifier = Modifier.semantics {
                contentDescription = buttonDescription
            },
        ) {
            Text(stringResource(R.string.sessions_empty_button))
        }
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    val retryDescription = stringResource(R.string.sessions_retry_description)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.sessions_error_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))
        TextButton(
            onClick = onRetry,
            modifier = Modifier.semantics {
                contentDescription = retryDescription
            },
        ) {
            Text(stringResource(R.string.sessions_retry_button))
        }
    }
}

@Composable
private fun SessionsList(
    sessions: List<Session>,
    onSessionClick: (SessionId) -> Unit,
    onDeleteClick: (Session) -> Unit,
    onRenameClick: (Session) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        items(
            items = sessions,
            key = { it.id.value },
        ) { session ->
            SwipeableSessionItem(
                session = session,
                onClick = { onSessionClick(session.id) },
                onDeleteClick = { onDeleteClick(session) },
                onRenameClick = { onRenameClick(session) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableSessionItem(
    session: Session,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onRenameClick: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDeleteClick()
                false // Don't actually dismiss; let the confirmation dialog handle it
            } else {
                false
            }
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val color by animateColorAsState(
                targetValue = when (dismissState.targetValue) {
                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
                    else -> Color.Transparent
                },
                label = "swipe-background-color",
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.sessions_swipe_delete_label),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        },
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
    ) {
        SessionItem(
            session = session,
            onClick = onClick,
            onDeleteClick = onDeleteClick,
            onRenameClick = onRenameClick,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionItem(
    session: Session,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onRenameClick: () -> Unit,
) {
    val itemDescription = stringResource(
        R.string.sessions_item_description,
        session.title,
        session.messageCount,
        formatTimestamp(session.lastActivityAt)
    )
    val renameDescription = stringResource(R.string.sessions_rename_item_description, session.title)
    val deleteDescription = stringResource(R.string.sessions_delete_item_description, session.title)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onRenameClick,
            )
            .semantics {
                contentDescription = itemDescription
            },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = formatTimestamp(session.lastActivityAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.sessions_message_count, session.messageCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = session.workspacePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = onRenameClick,
                modifier = Modifier
                    .size(40.dp)
                    .semantics {
                        contentDescription = renameDescription
                    },
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onDeleteClick,
                modifier = Modifier
                    .size(40.dp)
                    .semantics {
                        contentDescription = deleteDescription
                    },
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun NewSessionDialog(
    onDismiss: () -> Unit,
    onConfirm: (title: String, uri: Uri, path: String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedPath by remember { mutableStateOf("") }

    val directoryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        uri?.let {
            selectedUri = it
            selectedPath = it.path ?: it.toString()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sessions_new_dialog_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.sessions_new_title_label)) },
                    placeholder = { Text(stringResource(R.string.sessions_new_title_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.sessions_new_workspace_label),
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (selectedPath.isNotEmpty()) {
                    Text(
                        text = selectedPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                val selectDirDescription = stringResource(R.string.sessions_new_select_directory_description)
                TextButton(
                    onClick = { directoryPicker.launch(null) },
                    modifier = Modifier.semantics {
                        contentDescription = selectDirDescription
                    },
                ) {
                    Text(
                        if (selectedPath.isEmpty()) stringResource(R.string.sessions_new_select_directory_button)
                        else stringResource(R.string.sessions_new_change_directory_button)
                    )
                }
            }
        },
        confirmButton = {
            val defaultTitle = stringResource(R.string.sessions_new_default_title)
            TextButton(
                onClick = {
                    selectedUri?.let { uri ->
                        onConfirm(title.ifBlank { defaultTitle }, uri, selectedPath)
                    }
                },
                enabled = selectedUri != null,
            ) {
                Text(stringResource(R.string.sessions_new_create_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.sessions_new_cancel_button))
            }
        },
    )
}

@Composable
private fun DeleteConfirmationDialog(
    sessionTitle: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sessions_delete_dialog_title)) },
        text = {
            Text(stringResource(R.string.sessions_delete_dialog_body, sessionTitle))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(R.string.sessions_delete_dialog_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.sessions_delete_dialog_cancel))
            }
        },
    )
}

@Composable
private fun RenameSessionDialog(
    currentTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var newTitle by remember { mutableStateOf(currentTitle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sessions_rename_dialog_title)) },
        text = {
            OutlinedTextField(
                value = newTitle,
                onValueChange = { newTitle = it },
                label = { Text(stringResource(R.string.sessions_rename_title_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(newTitle) },
                enabled = newTitle.isNotBlank() && newTitle != currentTitle,
            ) {
                Text(stringResource(R.string.sessions_rename_confirm_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.sessions_rename_cancel_button))
            }
        },
    )
}

private fun formatTimestamp(instant: Instant): String {
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
    return formatter.format(instant)
}
