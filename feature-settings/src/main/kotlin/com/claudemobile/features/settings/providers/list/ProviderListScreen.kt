package com.claudemobile.features.settings.providers.list

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claudemobile.core.ui.R as CoreUiR
import com.claudemobile.feature.settings.R as SettingsR
import kotlinx.coroutines.launch

/**
 * Provider list screen (design §6.1, R4 AC1).
 *
 * Renders every persisted [com.claudemobile.core.domain.providers.ProviderProfile]
 * sorted by `updatedAt` descending. Each row shows:
 *
 *  - [ProviderListRow.profile] `displayName`,
 *  - originating preset id (or `"custom"`),
 *  - [com.claudemobile.core.domain.providers.ProviderProfile.maskedApiKey],
 *  - effective `model`,
 *  - "Active" badge on the current Active_Profile (R5 AC3),
 *  - persistent "✓" mark on the last successfully-tested profile until
 *    the next edit (R7 AC4).
 *
 * Each row exposes an overflow menu with: Set as active / Edit / Test
 * connection / Delete. The FAB navigates to
 * [com.claudemobile.features.settings.providers.selection.ProviderSelectionScreen]
 * to add a new profile (R4 AC2 + R11 AC4 onboarding flow).
 *
 * The destructive "Clear all credentials" affordance lives in the top
 * app bar overflow menu (R9 AC5, Property 17). It surfaces a
 * confirmation dialog and on success navigates back to the selection
 * screen via [ProviderListEvent.NavigateToSelection].
 *
 * Connection_Test outcomes render as a [androidx.compose.material3.Snackbar]
 * with the localized outcome label; the persistent ✓ on a row is held
 * by the ViewModel until the underlying profile's `updatedAt` advances.
 *
 * Requirements: 4.1, 4.2, 5.2, 5.3, 7.1, 7.3, 7.4, 9.2, 9.5.
 */
@Composable
public fun ProviderListScreen(
    onAddProfile: () -> Unit,
    onEditProfile: (profileId: String) -> Unit,
    onClearAllNavigateToSelection: () -> Unit,
    onBack: () -> Unit,
    viewModel: ProviderListViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    // Translate one-shot ViewModel events into navigation calls. The events
    // channel is consumed exactly once per emission so re-entering the
    // screen does not replay stale navigation.
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                ProviderListEvent.NavigateToSelection -> onClearAllNavigateToSelection()
                is ProviderListEvent.NavigateToEdit -> onEditProfile(event.profileId)
            }
        }
    }

    // Surface the latest test outcome as a snackbar. Each new outcome
    // produces a new snackbar; `DismissLatestTest` clears the state so
    // the same outcome doesn't re-trigger on recomposition.
    val latestTest = uiState.latestTest
    LaunchedEffect(latestTest) {
        val snapshot = latestTest ?: return@LaunchedEffect
        coroutineScope.launch {
            snackbarHostState.showSnackbar(snapshot.result.outcomeFallbackLabel())
            viewModel.onIntent(ProviderListIntent.DismissLatestTest)
        }
    }

    ProviderListScreenContent(
        state = uiState,
        snackbarHostState = snackbarHostState,
        onIntent = viewModel::onIntent,
        onAddProfile = onAddProfile,
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * Stateless composable, separated from the Hilt entry point so Compose
 * UI tests and previews can drive the screen without a real ViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderListScreenContent(
    state: ProviderListUiState,
    snackbarHostState: SnackbarHostState,
    onIntent: (ProviderListIntent) -> Unit,
    onAddProfile: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var topMenuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(CoreUiR.string.settings_providers_entry)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { topMenuExpanded = true },
                        modifier = Modifier.testTag(PROVIDER_LIST_TOP_MENU_BUTTON_TAG),
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = topMenuExpanded,
                        onDismissRequest = { topMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            modifier = Modifier.testTag(PROVIDER_LIST_CLEAR_ALL_MENU_TAG),
                            text = {
                                Text(stringResource(CoreUiR.string.provider_clear_all_title))
                            },
                            onClick = {
                                topMenuExpanded = false
                                onIntent(ProviderListIntent.RequestClearAll)
                            },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    onIntent(ProviderListIntent.AddNew)
                    onAddProfile()
                },
                modifier = Modifier.testTag(PROVIDER_LIST_FAB_TAG),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
            }
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            when {
                state.loading -> CenteredProgress()
                state.rows.isEmpty() -> EmptyState()
                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(PROVIDER_LIST_ROWS_TAG),
                ) {
                    items(state.rows, key = { it.profile.profileId }) { row ->
                        ProviderListRowComposable(row = row, onIntent = onIntent)
                    }
                }
            }
        }
    }

    // Per-row delete confirmation dialog. The single dialog is reused for
    // whichever row is in `pendingDeleteId`.
    if (state.pendingDeleteId != null) {
        DeleteConfirmationDialog(
            onConfirm = { onIntent(ProviderListIntent.ConfirmDelete) },
            onDismiss = { onIntent(ProviderListIntent.DismissDelete) },
        )
    }

    // Destructive "Clear all" confirmation dialog (R9 AC5).
    if (state.pendingClearAll) {
        ClearAllConfirmationDialog(
            onConfirm = { onIntent(ProviderListIntent.ConfirmClearAll) },
            onDismiss = { onIntent(ProviderListIntent.DismissClearAll) },
        )
    }
}

@Composable
private fun CenteredProgress() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) { CircularProgressIndicator() }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag(PROVIDER_LIST_EMPTY_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "No providers yet. Tap + to add one.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ProviderListRowComposable(
    row: ProviderListRow,
    onIntent: (ProviderListIntent) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(role = Role.Button) {
                onIntent(ProviderListIntent.Edit(row.profile.profileId))
            }
            .testTag(rowTestTag(row.profile.profileId)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.profile.displayName,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.size(8.dp))
                    if (row.isActive) {
                        AssistChip(
                            onClick = {},
                            enabled = false,
                            label = { Text("Active") },
                            colors = AssistChipDefaults.assistChipColors(
                                disabledLabelColor = MaterialTheme.colorScheme.primary,
                            ),
                            modifier = Modifier.testTag(activeBadgeTag(row.profile.profileId)),
                        )
                    }
                    if (row.lastTestOk) {
                        Spacer(Modifier.size(4.dp))
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(20.dp)
                                .testTag(lastTestOkTag(row.profile.profileId)),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = row.displayPreset,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = row.profile.maskedApiKey(),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = row.profile.model,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.testTag(rowOverflowTag(row.profile.profileId)),
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = null)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        modifier = Modifier.testTag(menuSetActiveTag(row.profile.profileId)),
                        text = { Text("Set as active") },
                        onClick = {
                            menuExpanded = false
                            onIntent(ProviderListIntent.SetActive(row.profile.profileId))
                        },
                    )
                    DropdownMenuItem(
                        modifier = Modifier.testTag(menuEditTag(row.profile.profileId)),
                        text = { Text("Edit") },
                        onClick = {
                            menuExpanded = false
                            onIntent(ProviderListIntent.Edit(row.profile.profileId))
                        },
                    )
                    DropdownMenuItem(
                        modifier = Modifier.testTag(menuTestTag(row.profile.profileId)),
                        text = { Text("Test connection") },
                        onClick = {
                            menuExpanded = false
                            onIntent(ProviderListIntent.TestConnection(row.profile.profileId))
                        },
                    )
                    DropdownMenuItem(
                        modifier = Modifier.testTag(menuDeleteTag(row.profile.profileId)),
                        text = { Text("Delete") },
                        onClick = {
                            menuExpanded = false
                            onIntent(ProviderListIntent.RequestDelete(row.profile.profileId))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeleteConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag(DELETE_DIALOG_TAG),
        onDismissRequest = onDismiss,
        title = { Text("Delete provider?") },
        text = {
            Text("This removes the saved profile. The stored API key will be overwritten before deletion.")
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag(DELETE_DIALOG_CONFIRM_TAG),
            ) {
                Text(text = "Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(DELETE_DIALOG_DISMISS_TAG),
            ) { Text("Cancel") }
        },
    )
}

@Composable
private fun ClearAllConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag(CLEAR_ALL_DIALOG_TAG),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreUiR.string.provider_clear_all_title)) },
        text = { Text(stringResource(CoreUiR.string.provider_clear_all_message)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag(CLEAR_ALL_CONFIRM_TAG),
            ) {
                Text(
                    text = stringResource(SettingsR.string.settings_remove_api_key_confirm_button),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(CLEAR_ALL_DISMISS_TAG),
            ) {
                Text(stringResource(SettingsR.string.settings_remove_api_key_cancel_button))
            }
        },
    )
}

/**
 * Localization-free fallback label for snackbar surfaces; the snackbar
 * is shown from a non-Composable scope, so it cannot call [stringResource].
 * The list ViewModel always sets the structured outcome alongside the
 * `userReason` carried by the result, so the fallback is sufficient
 * for the visible message.
 */
private fun com.claudemobile.core.domain.providers.ConnectionTestResult.outcomeFallbackLabel(): String =
    if (userReason.isNotBlank()) {
        "${outcome.name}: $userReason"
    } else {
        outcome.name
    }

// --- Test tags ----------------------------------------------------------

public const val PROVIDER_LIST_ROWS_TAG: String = "provider_list_rows"
public const val PROVIDER_LIST_FAB_TAG: String = "provider_list_fab"
public const val PROVIDER_LIST_EMPTY_TAG: String = "provider_list_empty"
public const val PROVIDER_LIST_TOP_MENU_BUTTON_TAG: String = "provider_list_top_menu"
public const val PROVIDER_LIST_CLEAR_ALL_MENU_TAG: String = "provider_list_clear_all_menu"
public const val DELETE_DIALOG_TAG: String = "provider_list_delete_dialog"
public const val DELETE_DIALOG_CONFIRM_TAG: String = "provider_list_delete_confirm"
public const val DELETE_DIALOG_DISMISS_TAG: String = "provider_list_delete_dismiss"
public const val CLEAR_ALL_DIALOG_TAG: String = "provider_list_clear_all_dialog"
public const val CLEAR_ALL_CONFIRM_TAG: String = "provider_list_clear_all_confirm"
public const val CLEAR_ALL_DISMISS_TAG: String = "provider_list_clear_all_dismiss"

public fun rowTestTag(profileId: String): String = "provider_list_row_$profileId"
public fun activeBadgeTag(profileId: String): String = "provider_list_active_$profileId"
public fun lastTestOkTag(profileId: String): String = "provider_list_test_ok_$profileId"
public fun rowOverflowTag(profileId: String): String = "provider_list_overflow_$profileId"
public fun menuSetActiveTag(profileId: String): String = "provider_list_menu_set_active_$profileId"
public fun menuEditTag(profileId: String): String = "provider_list_menu_edit_$profileId"
public fun menuTestTag(profileId: String): String = "provider_list_menu_test_$profileId"
public fun menuDeleteTag(profileId: String): String = "provider_list_menu_delete_$profileId"
