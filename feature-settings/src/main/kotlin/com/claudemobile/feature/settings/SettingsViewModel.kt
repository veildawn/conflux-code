package com.claudemobile.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claudemobile.core.domain.bridge.BootstrapManager
import com.claudemobile.core.domain.bridge.HealthCheckResult
import com.claudemobile.core.domain.model.AppLanguage
import com.claudemobile.core.domain.model.AppSettings
import com.claudemobile.core.domain.model.ThemeMode
import com.claudemobile.core.domain.providers.ProviderProfile
import com.claudemobile.core.domain.providers.usecase.GetActiveProfileUseCase
import com.claudemobile.core.domain.repository.CredentialStore
import com.claudemobile.core.domain.repository.DiagnosticsEntry
import com.claudemobile.core.domain.repository.DiagnosticsRepository
import com.claudemobile.core.domain.repository.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the Settings screen.
 *
 * The Anthropic-API-key fields that previously lived here have moved to the
 * Provider screens (`ProviderListScreen` / `ProviderEditorScreen`); this
 * UI surface no longer exposes a singular API-key entry per
 * `ai-provider-presets` task 7.10 / R11 AC3.
 */
public data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val healthCheckResult: HealthCheckResult? = null,
    val isCheckingHealth: Boolean = false,
    val diagnosticsEntries: List<DiagnosticsEntry> = emptyList(),
    val isLoadingDiagnostics: Boolean = false,
    val redactedDiagnosticsExport: String? = null,
)

/**
 * Actions that can be dispatched to the SettingsViewModel.
 */
public sealed interface SettingsAction {
    public data class SetSystemPrompt(val prompt: String) : SettingsAction
    public data class SetThemeMode(val mode: ThemeMode) : SettingsAction
    public data class SetFontScale(val scale: Float) : SettingsAction
    public data class SetStreamingRenderRate(val rateMs: Long) : SettingsAction
    public data class SetDefaultWorkspacePath(val path: String) : SettingsAction
    public data class SetAutoStartForegroundService(val enabled: Boolean) : SettingsAction
    public data class SetAppLanguage(val language: AppLanguage) : SettingsAction
    public data object RunHealthCheck : SettingsAction
    public data object LoadDiagnostics : SettingsAction
    public data object ExportDiagnostics : SettingsAction
    public data object ClearDiagnosticsExport : SettingsAction
}

/**
 * ViewModel for the Settings screen.
 *
 * Exposes a [StateFlow] of [SettingsUiState] containing all preferences,
 * health check results, and diagnostics log, plus a reactive
 * [activeProfile] [StateFlow] surfaced from
 * [GetActiveProfileUseCase] for the read-only Active_Profile summary
 * card (R5 AC3, R11 AC6 — UI reflects new active selection within
 * 200 ms; the upstream store is responsible for the time bound).
 *
 * Accepts [SettingsAction] values to modify preferences and trigger
 * operations.
 */
@HiltViewModel
public class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val credentialStore: CredentialStore,
    private val bootstrapManager: BootstrapManager,
    private val diagnosticsRepository: DiagnosticsRepository,
    getActiveProfile: GetActiveProfileUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    public val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /**
     * Reactive view of the current Active_Profile (or `null` when none
     * is selected). Backed by [GetActiveProfileUseCase] →
     * `ProviderProfileStore.observeActiveProfile()`, which guarantees
     * emissions within 200 ms of a write to the active reference
     * (R5 AC2, R11 AC6).
     *
     * Held as a [StateFlow] with [SharingStarted.WhileSubscribed] so
     * that the underlying SharedPreferences listener is detached when
     * no UI is observing.
     */
    public val activeProfile: StateFlow<ProviderProfile?> =
        getActiveProfile()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STATE_FLOW_STOP_TIMEOUT_MS),
                initialValue = null,
            )

    private val _errorEvents = Channel<String>(Channel.BUFFERED)

    /**
     * One-shot error events for the Settings UI to display as snackbars.
     * Collect this flow in the composable to show transient error messages.
     */
    public val errorEvents = _errorEvents.receiveAsFlow()

    init {
        observeSettings()
    }

    /**
     * Dispatches a [SettingsAction] to update preferences or trigger operations.
     */
    public fun onAction(action: SettingsAction) {
        when (action) {
            is SettingsAction.SetSystemPrompt -> setSystemPrompt(action.prompt)
            is SettingsAction.SetThemeMode -> setThemeMode(action.mode)
            is SettingsAction.SetFontScale -> setFontScale(action.scale)
            is SettingsAction.SetStreamingRenderRate -> setStreamingRenderRate(action.rateMs)
            is SettingsAction.SetDefaultWorkspacePath -> setDefaultWorkspacePath(action.path)
            is SettingsAction.SetAutoStartForegroundService -> setAutoStartForegroundService(action.enabled)
            is SettingsAction.SetAppLanguage -> setAppLanguage(action.language)
            is SettingsAction.RunHealthCheck -> runHealthCheck()
            is SettingsAction.LoadDiagnostics -> loadDiagnostics()
            is SettingsAction.ExportDiagnostics -> exportDiagnostics()
            is SettingsAction.ClearDiagnosticsExport -> clearDiagnosticsExport()
        }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsStore.settings.collect { settings ->
                _uiState.update { it.copy(settings = settings) }
            }
        }
    }

    private fun setSystemPrompt(prompt: String) {
        viewModelScope.launch {
            settingsStore.setSystemPrompt(prompt)
        }
    }

    private fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            settingsStore.setThemeMode(mode)
        }
    }

    private fun setFontScale(scale: Float) {
        if (scale in FONT_SCALE_MIN..FONT_SCALE_MAX) {
            viewModelScope.launch {
                settingsStore.setFontScale(scale)
            }
        }
    }

    private fun setStreamingRenderRate(rateMs: Long) {
        if (rateMs in RENDER_RATE_MIN..RENDER_RATE_MAX) {
            viewModelScope.launch {
                settingsStore.setStreamingRenderRate(rateMs)
            }
        }
    }

    private fun setDefaultWorkspacePath(path: String) {
        viewModelScope.launch {
            settingsStore.setDefaultWorkspacePath(path)
        }
    }

    private fun setAutoStartForegroundService(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setAutoStartForegroundService(enabled)
        }
    }

    private fun setAppLanguage(language: AppLanguage) {
        viewModelScope.launch {
            try {
                settingsStore.setAppLanguage(language)
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                _errorEvents.send("Couldn't save language preference")
            }
        }
    }

    private fun runHealthCheck() {
        viewModelScope.launch {
            _uiState.update { it.copy(isCheckingHealth = true) }
            try {
                val result = bootstrapManager.healthCheck()
                _uiState.update { it.copy(healthCheckResult = result, isCheckingHealth = false) }
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                _uiState.update { it.copy(isCheckingHealth = false) }
            }
        }
    }

    private fun loadDiagnostics() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingDiagnostics = true) }
            try {
                val entries = diagnosticsRepository.getRecentLogs()
                _uiState.update { it.copy(diagnosticsEntries = entries, isLoadingDiagnostics = false) }
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                _uiState.update { it.copy(isLoadingDiagnostics = false) }
            }
        }
    }

    private fun exportDiagnostics() {
        viewModelScope.launch {
            try {
                // Export all recent logs with API key redaction
                val entries = diagnosticsRepository.getRecentLogs()
                val exportText = buildString {
                    appendLine("=== Claude Mobile Diagnostics Log ===")
                    appendLine("Exported at: ${System.currentTimeMillis()}")
                    appendLine()
                    for (entry in entries) {
                        appendLine("[${entry.timestamp}] [${entry.eventType}] ${entry.message}")
                        if (entry.details != null) {
                            appendLine("  Details: ${entry.details}")
                        }
                    }
                }
                // Redact the legacy single API key from the export. Per-profile
                // redaction lives in core-data's `redactProviderSecrets`
                // (task 3.20); the legacy key is preserved here as
                // defence-in-depth for any user who has not yet been migrated
                // by `LegacyKeyMigrator` (task 3.18).
                val apiKey = credentialStore.getApiKey()
                val redacted = if (apiKey != null && apiKey.isNotEmpty()) {
                    exportText.replace(apiKey, "[REDACTED]")
                } else {
                    exportText
                }
                _uiState.update { it.copy(redactedDiagnosticsExport = redacted) }
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // Best effort export
            }
        }
    }

    private fun clearDiagnosticsExport() {
        _uiState.update { it.copy(redactedDiagnosticsExport = null) }
    }

    internal companion object {
        const val FONT_SCALE_MIN = 0.5f
        const val FONT_SCALE_MAX = 3.0f
        const val RENDER_RATE_MIN = 16L
        const val RENDER_RATE_MAX = 1000L

        /**
         * Stop-timeout for the active-profile [StateFlow]. 5 s matches
         * the value used by the rest of the app and is well above the
         * R11 AC6 200 ms latency bound for active-profile change
         * propagation while a subscriber is still attached.
         */
        private const val STATE_FLOW_STOP_TIMEOUT_MS: Long = 5_000L
    }
}
