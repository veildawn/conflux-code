package com.claudemobile.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claudemobile.core.domain.bridge.HealthCheckResult
import com.claudemobile.core.domain.model.AppLanguage
import com.claudemobile.core.domain.model.ThemeMode
import com.claudemobile.core.domain.providers.ProviderProfile
import com.claudemobile.core.domain.repository.DiagnosticsEntry
import com.claudemobile.core.ui.R as CoreUiR

/**
 * Settings screen composable that displays all application preferences,
 * environment health check information, and diagnostics log access.
 *
 * Includes:
 *
 *  - a read-only **Active_Profile summary card** showing the current
 *    `displayName` and effective `model`, reactive to
 *    `observeActiveProfile()` (R5 AC3, R11 AC6); and
 *  - a **"Providers" list item** that navigates to `ProviderListScreen`
 *    (R11 AC3).
 *
 * The legacy "Anthropic API key" input surface previously rendered here
 * has moved to the Provider screens as part of `ai-provider-presets`
 * task 7.10 (supersedes base-spec R6 AC1 entry point).
 */
@Composable
public fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {},
    onNavigateToProviders: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()

    SettingsScreenContent(
        uiState = uiState,
        activeProfile = activeProfile,
        onAction = viewModel::onAction,
        onNavigateBack = onNavigateBack,
        onNavigateToProviders = onNavigateToProviders,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreenContent(
    uiState: SettingsUiState,
    activeProfile: ProviderProfile?,
    onAction: (SettingsAction) -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToProviders: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    val backDescription = stringResource(R.string.settings_navigate_back_description)
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.semantics { contentDescription = backDescription },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Provider configuration: read-only Active_Profile summary +
            // entry to the Provider list screen (R5 AC3, R11 AC3, R11 AC6).
            ProvidersSection(
                activeProfile = activeProfile,
                onNavigateToProviders = onNavigateToProviders,
            )

            HorizontalDivider()

            // Language
            LanguageSection(
                current = uiState.settings.appLanguage,
                onLanguageChange = { onAction(SettingsAction.SetAppLanguage(it)) }
            )

            HorizontalDivider()

            // UI Preferences
            UiPreferencesSection(
                themeMode = uiState.settings.themeMode,
                fontScale = uiState.settings.fontScale,
                streamingRenderRate = uiState.settings.streamingRenderRate,
                onThemeModeChange = { onAction(SettingsAction.SetThemeMode(it)) },
                onFontScaleChange = { onAction(SettingsAction.SetFontScale(it)) },
                onStreamingRenderRateChange = { onAction(SettingsAction.SetStreamingRenderRate(it)) }
            )

            HorizontalDivider()

            // Workspace & Service
            WorkspaceServiceSection(
                defaultWorkspacePath = uiState.settings.defaultWorkspacePath,
                autoStartForegroundService = uiState.settings.autoStartForegroundService,
                onDefaultWorkspacePathChange = { onAction(SettingsAction.SetDefaultWorkspacePath(it)) },
                onAutoStartForegroundServiceChange = { onAction(SettingsAction.SetAutoStartForegroundService(it)) }
            )

            HorizontalDivider()

            // Health Check
            HealthCheckSection(
                healthCheckResult = uiState.healthCheckResult,
                isCheckingHealth = uiState.isCheckingHealth,
                onRunHealthCheck = { onAction(SettingsAction.RunHealthCheck) }
            )

            HorizontalDivider()

            // Diagnostics
            DiagnosticsSection(
                entries = uiState.diagnosticsEntries,
                isLoading = uiState.isLoadingDiagnostics,
                redactedExport = uiState.redactedDiagnosticsExport,
                onLoadDiagnostics = { onAction(SettingsAction.LoadDiagnostics) },
                onExportDiagnostics = { onAction(SettingsAction.ExportDiagnostics) },
                onClearExport = { onAction(SettingsAction.ClearDiagnosticsExport) }
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Settings → Providers section.
 *
 * Renders two stacked surfaces:
 *
 *  1. A **read-only Active_Profile summary card** showing the current
 *     active profile's `displayName` and effective `model` (R5 AC3).
 *     When no profile is active, an explanatory line is rendered so the
 *     user is aware they need to configure one before starting a
 *     session (R5 AC5 hint surface).
 *  2. A **"Providers" navigation list item** with a chevron that
 *     invokes [onNavigateToProviders] to land on `ProviderListScreen`
 *     (R11 AC3).
 *
 * The card is reactive to `observeActiveProfile()` upstream (R11 AC6 —
 * 200 ms latency bound is enforced by `ProviderProfileStoreImpl` via
 * `editor.commit()`).
 */
@Composable
private fun ProvidersSection(
    activeProfile: ProviderProfile?,
    onNavigateToProviders: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(CoreUiR.string.settings_providers_entry),
            style = MaterialTheme.typography.titleMedium,
        )

        ActiveProfileSummaryCard(activeProfile = activeProfile)

        ProvidersNavigationItem(onNavigateToProviders = onNavigateToProviders)
    }
}

@Composable
private fun ActiveProfileSummaryCard(activeProfile: ProviderProfile?) {
    val cardDescription = stringResource(R.string.settings_active_profile_card_description)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ACTIVE_PROFILE_CARD_TAG)
            .semantics { contentDescription = cardDescription },
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (activeProfile == null) {
                // No Active_Profile selected: surface the empty state so
                // users see why "Start session" is gated (R5 AC5).
                Text(
                    text = stringResource(R.string.settings_active_profile_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(ACTIVE_PROFILE_NONE_TAG),
                )
            } else {
                // R5 AC3: show displayName and effective model.
                Text(
                    text = stringResource(
                        CoreUiR.string.settings_active_profile_summary,
                        activeProfile.displayName,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag(ACTIVE_PROFILE_DISPLAY_NAME_TAG),
                )
                Text(
                    text = stringResource(
                        R.string.settings_active_profile_model,
                        activeProfile.model,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(ACTIVE_PROFILE_MODEL_TAG),
                )
            }
        }
    }
}

@Composable
private fun ProvidersNavigationItem(onNavigateToProviders: () -> Unit) {
    val itemDescription = stringResource(R.string.settings_providers_entry_description)
    ListItem(
        headlineContent = {
            Text(stringResource(CoreUiR.string.settings_providers_entry))
        },
        supportingContent = {
            Text(
                text = stringResource(R.string.settings_providers_entry_supporting),
                style = MaterialTheme.typography.bodySmall,
            )
        },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = false,
                role = Role.Button,
                onClick = onNavigateToProviders,
            )
            .testTag(PROVIDERS_ENTRY_TAG)
            .semantics { contentDescription = itemDescription },
    )
}

@Composable
private fun LanguageSection(
    current: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.settings_language_title),
            style = MaterialTheme.typography.titleMedium,
        )
        AppLanguage.entries.forEach { lang ->
            val selected = current == lang
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = { onLanguageChange(lang) },
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = lang.displayLabel(),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun AppLanguage.displayLabel(): String = when (this) {
    AppLanguage.FOLLOW_SYSTEM -> stringResource(R.string.settings_language_follow_system)
    AppLanguage.ENGLISH -> "English"
    AppLanguage.SIMPLIFIED_CHINESE -> "简体中文"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UiPreferencesSection(
    themeMode: ThemeMode,
    fontScale: Float,
    streamingRenderRate: Long,
    onThemeModeChange: (ThemeMode) -> Unit,
    onFontScaleChange: (Float) -> Unit,
    onStreamingRenderRateChange: (Long) -> Unit
) {
    var fontScaleText by remember(fontScale) { mutableStateOf(fontScale.toString()) }
    var renderRateText by remember(streamingRenderRate) { mutableStateOf(streamingRenderRate.toString()) }

    val themeSelectionDescription = stringResource(R.string.settings_theme_selection_description)
    val fontScaleDescription = stringResource(R.string.settings_font_scale_description)
    val renderRateDescription = stringResource(R.string.settings_render_rate_description)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.settings_appearance_title),
            style = MaterialTheme.typography.titleMedium
        )

        // Theme Mode - switching takes effect within one recomposition cycle (Req 9.5)
        Text(
            text = stringResource(R.string.settings_theme_label),
            style = MaterialTheme.typography.bodyMedium
        )

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = themeSelectionDescription }
        ) {
            ThemeMode.entries.forEachIndexed { index, mode ->
                val modeItemDescription = stringResource(
                    R.string.settings_theme_item_description,
                    mode.name.lowercase()
                )
                SegmentedButton(
                    selected = themeMode == mode,
                    onClick = { onThemeModeChange(mode) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = ThemeMode.entries.size
                    ),
                    modifier = Modifier.semantics {
                        contentDescription = modeItemDescription
                    }
                ) {
                    Text(mode.name.lowercase().replaceFirstChar { it.uppercase() })
                }
            }
        }

        // Font Scale with range validation (Req 9.3)
        OutlinedTextField(
            value = fontScaleText,
            onValueChange = { text ->
                fontScaleText = text
                text.toFloatOrNull()?.let { value ->
                    if (value in SettingsViewModel.FONT_SCALE_MIN..SettingsViewModel.FONT_SCALE_MAX) {
                        onFontScaleChange(value)
                    }
                }
            },
            label = {
                Text(
                    stringResource(
                        R.string.settings_font_scale_label,
                        SettingsViewModel.FONT_SCALE_MIN.toString(),
                        SettingsViewModel.FONT_SCALE_MAX.toString()
                    )
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = fontScaleDescription },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            isError = fontScaleText.toFloatOrNull()?.let {
                it !in SettingsViewModel.FONT_SCALE_MIN..SettingsViewModel.FONT_SCALE_MAX
            } ?: (fontScaleText.isNotEmpty()),
            supportingText = {
                val parsed = fontScaleText.toFloatOrNull()
                if (parsed != null && parsed !in SettingsViewModel.FONT_SCALE_MIN..SettingsViewModel.FONT_SCALE_MAX) {
                    Text(
                        stringResource(
                            R.string.settings_font_scale_error,
                            SettingsViewModel.FONT_SCALE_MIN.toString(),
                            SettingsViewModel.FONT_SCALE_MAX.toString()
                        )
                    )
                }
            }
        )

        // Streaming Render Rate with range validation (Req 9.3)
        OutlinedTextField(
            value = renderRateText,
            onValueChange = { text ->
                renderRateText = text
                text.toLongOrNull()?.let { value ->
                    if (value in SettingsViewModel.RENDER_RATE_MIN..SettingsViewModel.RENDER_RATE_MAX) {
                        onStreamingRenderRateChange(value)
                    }
                }
            },
            label = {
                Text(
                    stringResource(
                        R.string.settings_render_rate_label,
                        SettingsViewModel.RENDER_RATE_MIN,
                        SettingsViewModel.RENDER_RATE_MAX
                    )
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = renderRateDescription },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            isError = renderRateText.toLongOrNull()?.let {
                it !in SettingsViewModel.RENDER_RATE_MIN..SettingsViewModel.RENDER_RATE_MAX
            } ?: (renderRateText.isNotEmpty()),
            supportingText = {
                val parsed = renderRateText.toLongOrNull()
                if (parsed != null && parsed !in SettingsViewModel.RENDER_RATE_MIN..SettingsViewModel.RENDER_RATE_MAX) {
                    Text(
                        stringResource(
                            R.string.settings_render_rate_error,
                            SettingsViewModel.RENDER_RATE_MIN,
                            SettingsViewModel.RENDER_RATE_MAX
                        )
                    )
                }
            }
        )
    }
}

@Composable
private fun WorkspaceServiceSection(
    defaultWorkspacePath: String,
    autoStartForegroundService: Boolean,
    onDefaultWorkspacePathChange: (String) -> Unit,
    onAutoStartForegroundServiceChange: (Boolean) -> Unit
) {
    val workspacePathDescription = stringResource(R.string.settings_workspace_path_description)
    val autoStartDescription = stringResource(R.string.settings_auto_start_service_description)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.settings_workspace_service_title),
            style = MaterialTheme.typography.titleMedium
        )

        OutlinedTextField(
            value = defaultWorkspacePath,
            onValueChange = onDefaultWorkspacePathChange,
            label = { Text(stringResource(R.string.settings_workspace_path_label)) },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = workspacePathDescription },
            singleLine = true
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.settings_auto_start_service_label),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = autoStartForegroundService,
                onCheckedChange = onAutoStartForegroundServiceChange,
                modifier = Modifier.semantics {
                    contentDescription = autoStartDescription
                }
            )
        }
    }
}

@Composable
private fun HealthCheckSection(
    healthCheckResult: HealthCheckResult?,
    isCheckingHealth: Boolean,
    onRunHealthCheck: () -> Unit
) {
    val runHealthDescription = stringResource(R.string.settings_health_run_description)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.settings_health_title),
            style = MaterialTheme.typography.titleMedium
        )

        if (healthCheckResult != null) {
            HealthCheckResultCard(healthCheckResult)
        } else {
            Text(
                text = stringResource(R.string.settings_health_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Button(
            onClick = onRunHealthCheck,
            enabled = !isCheckingHealth,
            modifier = Modifier.semantics { contentDescription = runHealthDescription }
        ) {
            if (isCheckingHealth) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .height(16.dp)
                        .width(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.settings_health_checking))
            } else {
                Text(stringResource(R.string.settings_health_run_button))
            }
        }
    }
}

@Composable
private fun HealthCheckResultCard(result: HealthCheckResult) {
    val resultsDescription = stringResource(R.string.settings_health_results_description)
    val installedLabel = stringResource(R.string.settings_health_status_installed)
    val notInstalledLabel = stringResource(R.string.settings_health_status_not_installed)

    val usedBytes = formatBytes(result.storageUsedBytes)
    val availableBytes = formatBytes(result.storageAvailableBytes)
    val storageText = stringResource(R.string.settings_health_storage_usage, usedBytes, availableBytes)
    val storageDescription = stringResource(
        R.string.settings_health_storage_usage_description,
        usedBytes,
        availableBytes
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = resultsDescription }
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            HealthCheckRow(
                label = stringResource(R.string.settings_health_row_embedded_prefix),
                status = if (result.prefixInstalled) installedLabel else notInstalledLabel,
                isNegative = !result.prefixInstalled,
                version = result.prefixVersion
            )
            HealthCheckRow(
                label = stringResource(R.string.settings_health_row_rootfs),
                status = if (result.rootfsInstalled) installedLabel else notInstalledLabel,
                isNegative = !result.rootfsInstalled,
                version = result.rootfsDistro
            )
            HealthCheckRow(
                label = stringResource(R.string.settings_health_row_node),
                status = if (result.nodeVersion != null) installedLabel else notInstalledLabel,
                isNegative = result.nodeVersion == null,
                version = result.nodeVersion
            )
            HealthCheckRow(
                label = stringResource(R.string.settings_health_row_claude_cli),
                status = if (result.claudeCliVersion != null) installedLabel else notInstalledLabel,
                isNegative = result.claudeCliVersion == null,
                version = result.claudeCliVersion
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Storage usage display (Req 1.9)
            Text(
                text = stringResource(R.string.settings_health_storage_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = storageText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics {
                    contentDescription = storageDescription
                }
            )
        }
    }
}

@Composable
private fun HealthCheckRow(
    label: String,
    status: String,
    isNegative: Boolean,
    version: String?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = if (version != null) {
                stringResource(R.string.settings_health_status_with_version, status, version)
            } else {
                status
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (isNegative) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            }
        )
    }
}

@Composable
private fun DiagnosticsSection(
    entries: List<DiagnosticsEntry>,
    isLoading: Boolean,
    redactedExport: String?,
    onLoadDiagnostics: () -> Unit,
    onExportDiagnostics: () -> Unit,
    onClearExport: () -> Unit
) {
    val loadDescription = stringResource(R.string.settings_diagnostics_load_description)
    val shareDescription = stringResource(R.string.settings_diagnostics_share_description)
    val entriesDescription = stringResource(R.string.settings_diagnostics_entries_description)
    val exportDescription = stringResource(R.string.settings_diagnostics_export_description)
    val dismissDescription = stringResource(R.string.settings_diagnostics_dismiss_description)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.settings_diagnostics_title),
            style = MaterialTheme.typography.titleMedium
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onLoadDiagnostics,
                enabled = !isLoading,
                modifier = Modifier.semantics { contentDescription = loadDescription }
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .height(16.dp)
                            .width(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(R.string.settings_diagnostics_view_button))
            }

            OutlinedButton(
                onClick = onExportDiagnostics,
                modifier = Modifier.semantics { contentDescription = shareDescription }
            ) {
                Text(stringResource(R.string.settings_diagnostics_share_button))
            }
        }

        // Show recent diagnostics entries
        if (entries.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = entriesDescription }
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_diagnostics_recent_entries, entries.size),
                        style = MaterialTheme.typography.labelMedium
                    )
                    // Show last 10 entries in the UI
                    entries.take(10).forEach { entry ->
                        Text(
                            text = stringResource(
                                R.string.settings_diagnostics_entry_line,
                                entry.eventType,
                                entry.message
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2
                        )
                    }
                    if (entries.size > 10) {
                        Text(
                            text = stringResource(
                                R.string.settings_diagnostics_more_entries,
                                entries.size - 10
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Show export result (for sharing via intent)
        if (redactedExport != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = exportDescription }
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_diagnostics_export_ready),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.settings_diagnostics_export_body),
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(
                        onClick = onClearExport,
                        modifier = Modifier.semantics { contentDescription = dismissDescription }
                    ) {
                        Text(stringResource(R.string.settings_diagnostics_dismiss_button))
                    }
                }
            }
        }
    }
}

/**
 * Formats byte count into a human-readable string. The unit prefix is a stable
 * symbol (B/KB/MB/GB), so only the number is localized through the app locale's
 * number formatter; the final string is assembled through a `%1$s`-style resource
 * so translators can rearrange the number and unit.
 */
@Composable
private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824L ->
            stringResource(R.string.settings_bytes_gb, "%.1f".format(bytes / 1_073_741_824.0))
        bytes >= 1_048_576L ->
            stringResource(R.string.settings_bytes_mb, "%.1f".format(bytes / 1_048_576.0))
        bytes >= 1_024L ->
            stringResource(R.string.settings_bytes_kb, "%.1f".format(bytes / 1_024.0))
        else -> stringResource(R.string.settings_bytes_b, bytes)
    }
}

// --- Test tags ----------------------------------------------------------

/** Test tag on the Active_Profile summary [androidx.compose.material3.Card]. */
public const val ACTIVE_PROFILE_CARD_TAG: String = "settings_active_profile_card"

/** Test tag on the empty-state text inside the summary card. */
public const val ACTIVE_PROFILE_NONE_TAG: String = "settings_active_profile_none"

/** Test tag on the active profile's `displayName` text. */
public const val ACTIVE_PROFILE_DISPLAY_NAME_TAG: String = "settings_active_profile_display_name"

/** Test tag on the active profile's effective `model` text. */
public const val ACTIVE_PROFILE_MODEL_TAG: String = "settings_active_profile_model"

/** Test tag on the "Providers" navigation list item. */
public const val PROVIDERS_ENTRY_TAG: String = "settings_providers_entry"
