package com.claudemobile.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.claudemobile.core.domain.model.Message
import com.claudemobile.core.domain.model.MessageId
import com.claudemobile.core.domain.model.MessageRole
import com.claudemobile.core.domain.model.MessageStatus
import com.claudemobile.core.domain.model.ToolCallMetadata
import com.claudemobile.core.ui.components.MessageBubble
import com.claudemobile.core.ui.components.StreamingIndicator
import com.claudemobile.core.ui.components.ToolCallBlock
import com.claudemobile.core.ui.theme.LocalSpacing
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The main chat screen composable that displays the conversation with Claude CLI.
 *
 * Features:
 * - Message list with auto-scroll to bottom on new messages (LazyColumn)
 * - Input field with send button (or cancel button when streaming)
 * - Workspace path in session header with file-tree control (Requirement 8.7)
 * - Offline banner when network is unavailable (Requirement 11.2)
 * - Structured error messages with retry action (Requirement 3.8, 11.2)
 * - Tool call block rendering with collapsible arguments/results (Requirement 3.3)
 * - Streaming indicator display and hide (Requirement 3.2, 3.4)
 * - Cancel control replaces send button during streaming (Requirement 3.6)
 * - Keyboard navigation for all interactive controls (Requirement 15.4)
 * - Reflow without horizontal clipping at font scale 2.0x (Requirement 15.2)
 *
 * @param viewModel The [ChatViewModel] providing state and handling actions.
 * @param isOffline Whether the device currently has no network connectivity.
 * @param onOpenFileTree Callback invoked when the user taps the file-tree control.
 * @param modifier Optional [Modifier] for the root layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun ChatScreen(
    viewModel: ChatViewModel = hiltViewModel(),
    isOffline: Boolean = false,
    onOpenFileTree: (() -> Unit)? = null,
    onNavigateBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val spacing = LocalSpacing.current

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            ChatTopBar(
                sessionTitle = uiState.sessionTitle,
                workspacePath = uiState.workspacePath,
                onOpenFileTree = onOpenFileTree,
                onNavigateBack = onNavigateBack,
            )
        },
        contentWindowInsets = WindowInsets.ime,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            // Offline banner (Requirement 11.1)
            OfflineBanner(
                isVisible = isOffline,
            )

            // Error banner with retry (Requirement 3.8, 11.2)
            ErrorBanner(
                errorMessage = uiState.errorMessage,
                onRetry = { viewModel.onAction(ChatAction.Retry) },
                onDismiss = { viewModel.onAction(ChatAction.DismissError) },
            )

            // Message list with tool call blocks
            MessageList(
                messages = uiState.messages,
                isStreaming = uiState.isStreaming,
                activeToolCalls = uiState.activeToolCalls,
                onCopyMessage = { messageId ->
                    viewModel.onAction(ChatAction.CopyMessage(messageId))
                },
                modifier = Modifier.weight(1f),
            )

            // Input area with send/cancel toggle (Requirement 3.5, 3.6)
            ChatInputBar(
                inputText = uiState.inputText,
                isStreaming = uiState.isStreaming,
                onInputChanged = { text ->
                    viewModel.onAction(ChatAction.UpdateInput(text))
                },
                onSend = {
                    viewModel.onAction(ChatAction.SendMessage(uiState.inputText))
                },
                onCancel = {
                    viewModel.onAction(ChatAction.Cancel)
                },
            )
        }
    }
}

/**
 * Top app bar showing the session title and workspace path with a file-tree control.
 * Requirement 8.7: display workspace path in session header.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    sessionTitle: String,
    workspacePath: String,
    onOpenFileTree: (() -> Unit)?,
    onNavigateBack: (() -> Unit)?,
) {
    val fallbackTitle = stringResource(R.string.chat_title_fallback)
    val workspacePathDescription = if (workspacePath.isNotEmpty()) {
        stringResource(R.string.chat_workspace_path_description, workspacePath)
    } else {
        ""
    }
    val openFileTreeDescription = stringResource(R.string.chat_open_file_tree_description)
    val navigateBackDescription = stringResource(R.string.chat_navigate_back_description)
    TopAppBar(
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = sessionTitle.ifEmpty { fallbackTitle },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (workspacePath.isNotEmpty()) {
                    Text(
                        text = workspacePath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics {
                            contentDescription = workspacePathDescription
                        },
                    )
                }
            }
        },
        navigationIcon = {
            if (onNavigateBack != null) {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.semantics {
                        contentDescription = navigateBackDescription
                    },
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                    )
                }
            }
        },
        actions = {
            if (workspacePath.isNotEmpty() && onOpenFileTree != null) {
                IconButton(
                    onClick = onOpenFileTree,
                    modifier = Modifier.semantics {
                        contentDescription = openFileTreeDescription
                    },
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

/**
 * Offline banner displayed when the device has no network connectivity.
 * Requirement 11.1: display offline banner when network unavailable.
 */
@Composable
private fun OfflineBanner(
    isVisible: Boolean,
) {
    val offlineBannerDescription = stringResource(R.string.chat_offline_banner_description)
    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically() + fadeIn(),
        exit = slideOutVertically() + fadeOut(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics {
                    contentDescription = offlineBannerDescription
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Default.WifiOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.chat_offline_banner_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/**
 * Error banner with a retry action and dismiss control.
 * Requirement 3.8: error events append system message and allow next user turn.
 * Requirement 11.2: network error renders as structured system message with retry.
 */
@Composable
private fun ErrorBanner(
    errorMessage: String?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val errorDescription = if (errorMessage != null) {
        stringResource(R.string.chat_error_description, errorMessage)
    } else {
        ""
    }
    val retryDescription = stringResource(R.string.chat_retry_description)
    val dismissDescription = stringResource(R.string.chat_dismiss_error_description)
    AnimatedVisibility(
        visible = errorMessage != null,
        enter = slideInVertically() + fadeIn(),
        exit = slideOutVertically() + fadeOut(),
    ) {
        if (errorMessage != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .semantics {
                        contentDescription = errorDescription
                    },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                shape = MaterialTheme.shapes.small,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = onRetry,
                        modifier = Modifier
                            .focusable()
                            .semantics {
                                contentDescription = retryDescription
                            },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = stringResource(R.string.chat_retry_button))
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.semantics {
                            contentDescription = dismissDescription
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The scrollable message list using LazyColumn with auto-scroll to bottom on new messages.
 * Renders tool call blocks inline within assistant messages (Requirement 3.3).
 * Shows streaming indicator on actively streaming messages (Requirement 3.2).
 */
@Composable
private fun MessageList(
    messages: List<Message>,
    isStreaming: Boolean,
    activeToolCalls: Map<MessageId, List<ToolCallMetadata>>,
    onCopyMessage: (MessageId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val spacing = LocalSpacing.current

    // Auto-scroll to bottom when new messages arrive or content updates during streaming
    LaunchedEffect(messages.size, isStreaming) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Also scroll when the last message content changes (streaming updates)
    LaunchedEffect(Unit) {
        snapshotFlow { messages.lastOrNull()?.content?.length ?: 0 }
            .distinctUntilChanged()
            .collect {
                if (messages.isNotEmpty()) {
                    listState.animateScrollToItem(messages.size - 1)
                }
            }
    }

    if (messages.isEmpty()) {
        val emptyDescription = stringResource(R.string.chat_empty_description)
        Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.chat_empty_text),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics {
                    contentDescription = emptyDescription
                },
            )
        }
    } else {
        val messageListDescription = stringResource(R.string.chat_message_list_description)
        LazyColumn(
            state = listState,
            modifier = modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = messageListDescription
                },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(
                items = messages,
                key = { it.id.value },
            ) { message ->
                Column {
                    // Render the message bubble
                    MessageBubble(
                        message = message,
                        onCopyMessage = { _ -> onCopyMessage(message.id) },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // Render tool call blocks for this message (Requirement 3.3)
                    val toolCalls = getToolCallsForMessage(message, activeToolCalls)
                    if (toolCalls.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = spacing.lg, vertical = spacing.xs),
                            verticalArrangement = Arrangement.spacedBy(spacing.sm),
                        ) {
                            toolCalls.forEach { toolCall ->
                                ToolCallBlock(
                                    toolCall = toolCall,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }

                    // Show standalone streaming indicator below the last streaming message
                    // when it's the last message in the list (Requirement 3.2)
                    if (message == messages.lastOrNull() &&
                        message.status == MessageStatus.STREAMING &&
                        isStreaming
                    ) {
                        StreamingIndicator(
                            modifier = Modifier
                                .padding(horizontal = spacing.lg, vertical = spacing.xs),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Resolves tool calls for a given message from both the message's own metadata
 * and the active tool calls tracked during streaming.
 */
private fun getToolCallsForMessage(
    message: Message,
    activeToolCalls: Map<MessageId, List<ToolCallMetadata>>,
): List<ToolCallMetadata> {
    // First check active streaming tool calls
    val streamingCalls = activeToolCalls[message.id]
    if (!streamingCalls.isNullOrEmpty()) {
        return streamingCalls
    }

    // Fall back to persisted tool call metadata on the message itself
    val metadata = message.toolCallMetadata
    return if (metadata != null) listOf(metadata) else emptyList()
}

/**
 * The input bar at the bottom of the chat screen with a text field and send/cancel button.
 *
 * Shows a send button when not streaming, and a cancel button during an active assistant turn.
 * Requirement 3.5: send control with non-empty input appends user message.
 * Requirement 3.6: cancel control replaces send control during streaming.
 * Supports keyboard navigation: Tab moves focus, Enter sends the message.
 */
@Composable
private fun ChatInputBar(
    inputText: String,
    isStreaming: Boolean,
    onInputChanged: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val focusManager = LocalFocusManager.current
    val inputDescription = stringResource(R.string.chat_input_description)
    val cancelDescription = stringResource(R.string.chat_cancel_description)
    val sendDescription = stringResource(R.string.chat_send_description)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = spacing.lg, vertical = spacing.sm)
            .onKeyEvent { keyEvent ->
                when (keyEvent.key) {
                    Key.Tab -> {
                        focusManager.moveFocus(FocusDirection.Next)
                        true
                    }
                    else -> false
                }
            },
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        OutlinedTextField(
            value = inputText,
            onValueChange = onInputChanged,
            modifier = Modifier
                .weight(1f)
                .focusable()
                .semantics {
                    contentDescription = inputDescription
                },
            placeholder = {
                Text(text = stringResource(R.string.chat_input_placeholder))
            },
            keyboardOptions = KeyboardOptions(
                imeAction = if (isStreaming) ImeAction.None else ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(
                onSend = {
                    if (!isStreaming && inputText.isNotBlank()) {
                        onSend()
                    }
                },
            ),
            maxLines = 6,
            shape = MaterialTheme.shapes.large,
        )

        if (isStreaming) {
            // Requirement 3.6: cancel control replaces send button during streaming
            IconButton(
                onClick = onCancel,
                modifier = Modifier
                    .size(48.dp)
                    .focusable()
                    .semantics {
                        contentDescription = cancelDescription
                    },
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        } else {
            // Requirement 3.5: send control
            IconButton(
                onClick = {
                    if (inputText.isNotBlank()) {
                        onSend()
                    }
                },
                enabled = inputText.isNotBlank(),
                modifier = Modifier
                    .size(48.dp)
                    .focusable()
                    .semantics {
                        contentDescription = sendDescription
                    },
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    tint = if (inputText.isNotBlank()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
