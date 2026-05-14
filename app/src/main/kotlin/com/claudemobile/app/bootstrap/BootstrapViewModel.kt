package com.claudemobile.app.bootstrap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claudemobile.app.startup.AppMigrationCoordinator
import com.claudemobile.app.startup.MigrationState
import com.claudemobile.core.common.CoroutineDispatchers
import com.claudemobile.core.domain.bridge.BootstrapManager
import com.claudemobile.core.domain.bridge.BootstrapProgress
import com.claudemobile.core.domain.bridge.BootstrapState
import com.claudemobile.core.domain.bridge.BootstrapStep
import com.claudemobile.core.domain.providers.ProviderProfileStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the bootstrap screen that orchestrates the environment setup flow.
 *
 * Monitors [BootstrapManager.bootstrapState] and [BootstrapManager.progressFlow]
 * to drive the UI with current step name, progress percentage, and installer output.
 *
 * Step 6 (ai-provider-presets R11.4): after the five environment-setup steps complete,
 * checks [ProviderProfileStore.list]. If the list is empty the VM emits a
 * [BootstrapNavigationEvent.NavigateToProviderSelection] intent and marks bootstrap
 * incomplete until the user creates and activates a profile. If the list is non-empty
 * (e.g. because [LegacyKeyMigrator] already ran) Step 6 succeeds transparently.
 *
 * Migration failure (R8 AC4): if [AppMigrationCoordinator.state] is
 * [MigrationState.Failed], [migrationFailureCause] is set so the screen can render
 * a retry surface. [retryMigration] re-runs the coordinator.
 *
 * Requirements: 1.5, 1.6, 1.7, 8.4, 11.4
 */
@HiltViewModel
class BootstrapViewModel @Inject constructor(
    private val bootstrapManager: BootstrapManager,
    private val providerProfileStore: ProviderProfileStore,
    private val migrationCoordinator: AppMigrationCoordinator,
    private val dispatchers: CoroutineDispatchers,
) : ViewModel() {

    /**
     * The current bootstrap state, observed by the BootstrapScreen composable.
     */
    val bootstrapState: StateFlow<BootstrapState> = bootstrapManager.bootstrapState

    /**
     * The most recent progress update with detailed step info and download percentage.
     */
    private val _latestProgress = MutableStateFlow<BootstrapProgress?>(null)
    val latestProgress: StateFlow<BootstrapProgress?> = _latestProgress.asStateFlow()

    /**
     * The most recent line of installer output for display.
     */
    private val _lastOutputLine = MutableStateFlow("")
    val lastOutputLine: StateFlow<String> = _lastOutputLine.asStateFlow()

    /**
     * One-shot navigation events emitted by the VM.
     * The screen collects this flow and acts on each event exactly once.
     */
    private val _navigationEvents = MutableSharedFlow<BootstrapNavigationEvent>(extraBufferCapacity = 1)
    val navigationEvents: SharedFlow<BootstrapNavigationEvent> = _navigationEvents.asSharedFlow()

    /**
     * Whether Step 6 (Configure Provider) is currently in progress.
     * Exposed so the screen can render the step label while waiting for the user.
     */
    private val _configuringProvider = MutableStateFlow(false)
    val configuringProvider: StateFlow<Boolean> = _configuringProvider.asStateFlow()

    /**
     * Non-null when the legacy-key migration failed (R8 AC4). The screen renders a
     * retry surface when this is set. Cleared when [retryMigration] is called.
     *
     * Requirements: 8.4.
     */
    private val _migrationFailureCause = MutableStateFlow<Throwable?>(null)
    val migrationFailureCause: StateFlow<Throwable?> = _migrationFailureCause.asStateFlow()

    init {
        observeProgress()
        observeMigrationState()
        observeProviderConfigured()
    }

    /**
     * Collects progress updates from BootstrapManager and updates local state.
     */
    private fun observeProgress() {
        viewModelScope.launch(dispatchers.io) {
            bootstrapManager.progressFlow.collect { progress ->
                _latestProgress.value = progress
                if (progress.message.isNotBlank()) {
                    _lastOutputLine.value = progress.message
                }
            }
        }
    }

    /**
     * Observes [AppMigrationCoordinator.state] and surfaces failures so the screen
     * can render a retry action (R8 AC4).
     *
     * Requirements: 8.4.
     */
    private fun observeMigrationState() {
        viewModelScope.launch(dispatchers.io) {
            migrationCoordinator.state.collect { state ->
                when (state) {
                    is MigrationState.Failed -> _migrationFailureCause.value = state.cause
                    is MigrationState.Completed -> _migrationFailureCause.value = null
                    else -> { /* Pending / Running — no action */ }
                }
            }
        }
    }

    /**
     * Reactively observes the provider profile store while Step 6 is pending.
     * When [configuringProvider] is `true` and the store becomes non-empty
     * (i.e. the user saved a profile on the editor screen), automatically
     * clears the flag so bootstrap can complete without requiring an explicit
     * callback from the navigation layer.
     *
     * Requirements: 11.4.
     */
    private fun observeProviderConfigured() {
        viewModelScope.launch(dispatchers.io) {
            providerProfileStore.observeProfiles().collect { profiles ->
                if (_configuringProvider.value && profiles.isNotEmpty()) {
                    _configuringProvider.value = false
                }
            }
        }
    }

    /**
     * Retries the legacy-key migration after a failure (R8 AC4).
     * Clears the failure state and re-runs the coordinator.
     *
     * Requirements: 8.4.
     */
    fun retryMigration() {
        _migrationFailureCause.value = null
        migrationCoordinator.runMigration()
    }

    /**
     * Starts or restarts the bootstrap process.
     * If the environment is already ready, runs Step 6 (Configure Provider) directly.
     */
    fun startBootstrap() {
        viewModelScope.launch(dispatchers.io) {
            if (bootstrapManager.isReady()) {
                // Environment already set up — run Step 6 only
                runConfigureProviderStep()
                return@launch
            }
            bootstrapManager.bootstrap()
            // Step 6 is triggered reactively via observeBootstrapReady()
        }
        observeBootstrapReady()
    }

    /**
     * Observes the bootstrap state and triggers Step 6 once the environment is Ready.
     * This handles both the case where bootstrap() just completed and the case where
     * the environment was already ready before startBootstrap() was called.
     */
    private fun observeBootstrapReady() {
        viewModelScope.launch(dispatchers.io) {
            bootstrapManager.bootstrapState.collect { state ->
                if (state is BootstrapState.Ready) {
                    runConfigureProviderStep()
                    return@collect
                }
            }
        }
    }

    /**
     * Step 6 (ai-provider-presets R11.4): checks whether at least one Provider_Profile
     * exists.
     *
     * - If the store is non-empty (e.g. migrated from legacy key by [LegacyKeyMigrator]),
     *   succeeds silently and clears [configuringProvider].
     * - If the store is empty, emits [BootstrapNavigationEvent.NavigateToProviderSelection]
     *   and sets [configuringProvider] = true so the screen can render the step label.
     *   Bootstrap is considered incomplete until [onProviderConfigured] is called.
     *
     * Requirements: 11.4.
     */
    private suspend fun runConfigureProviderStep() {
        _configuringProvider.value = true
        val profiles = providerProfileStore.list()
        if (profiles.isNotEmpty()) {
            // Profile already exists (e.g. from LegacyKeyMigrator) — step passes transparently
            _configuringProvider.value = false
        } else {
            // No profile yet — navigate to provider selection; keep configuringProvider = true
            _navigationEvents.emit(BootstrapNavigationEvent.NavigateToProviderSelection)
        }
    }

    /**
     * Called by the screen when the user has successfully created and activated a
     * Provider_Profile. Clears the [configuringProvider] flag so the screen can
     * proceed to the main sessions view.
     */
    fun onProviderConfigured() {
        _configuringProvider.value = false
    }

    /**
     * Retries the bootstrap process after a failure.
     * Resets progress state before restarting.
     */
    fun retry() {
        _latestProgress.value = null
        _lastOutputLine.value = ""
        startBootstrap()
    }
}

/**
 * One-shot navigation events emitted by [BootstrapViewModel].
 */
sealed interface BootstrapNavigationEvent {
    /** Navigate to the provider selection screen so the user can create their first profile. */
    data object NavigateToProviderSelection : BootstrapNavigationEvent
}
