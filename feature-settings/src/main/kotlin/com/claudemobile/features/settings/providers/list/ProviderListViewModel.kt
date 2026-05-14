package com.claudemobile.features.settings.providers.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claudemobile.core.common.AppResult
import com.claudemobile.core.domain.providers.ConnectionTestResult
import com.claudemobile.core.domain.providers.ConnectionTester
import com.claudemobile.core.domain.providers.PresetReference
import com.claudemobile.core.domain.providers.ProviderProfile
import com.claudemobile.core.domain.providers.ProviderProfileStore
import com.claudemobile.core.domain.providers.usecase.DeleteProfileUseCase
import com.claudemobile.core.domain.providers.usecase.GetActiveProfileUseCase
import com.claudemobile.core.domain.providers.usecase.ListProfilesUseCase
import com.claudemobile.core.domain.providers.usecase.SetActiveProfileUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI row state for [ProviderListScreen]. One row per stored profile,
 * combined with derived display flags for "Active" and the most recent
 * successful Connection_Test indicator.
 *
 * @property profile underlying [ProviderProfile] (non-null).
 * @property isActive `true` iff this profile is the current
 *   Active_Profile; rendered as an "Active" badge (R5 AC3).
 * @property lastTestOk `true` while the last Connection_Test for this
 *   profile completed with [com.claudemobile.core.domain.providers.ConnectionTestOutcome.Ok]
 *   and the profile has not been edited since (R7 AC4). The flag is
 *   transient: it is held in memory only and is reset whenever
 *   [profile.updatedAt] advances.
 * @property displayPreset Convenience field carrying either the
 *   originating preset id (e.g. `"glm_coding_plan"`) or the literal
 *   string `"custom"` (R4 AC1). Computed from
 *   [ProviderProfile.presetReference] so that the screen does not need
 *   to repeat the `when` expression.
 */
public data class ProviderListRow(
    val profile: ProviderProfile,
    val isActive: Boolean,
    val lastTestOk: Boolean,
    val displayPreset: String,
)

/**
 * Snapshot of the Provider List screen.
 */
public data class ProviderListUiState(
    val rows: List<ProviderListRow> = emptyList(),
    val loading: Boolean = true,
    /** Profile id targeted by the in-progress confirmation dialog, or null. */
    val pendingDeleteId: String? = null,
    /** `true` while the user has the destructive "Clear all" dialog open (R9 AC5). */
    val pendingClearAll: Boolean = false,
    /**
     * Most recent Connection_Test result, paired with the originating
     * profile id so the screen can render a snackbar referencing the
     * row that was tested (R7 AC3).
     */
    val latestTest: TestSnapshot? = null,
) {
    public data class TestSnapshot(
        val profileId: String,
        val result: ConnectionTestResult,
    )
}

/**
 * One-shot navigation events emitted by [ProviderListViewModel].
 *
 * Captured via a [Channel] rather than [StateFlow] because they should
 * fire exactly once per occurrence even if the screen is re-collected.
 */
public sealed interface ProviderListEvent {

    /** User tapped FAB or "Clear all" succeeded → go to selection screen. */
    public data object NavigateToSelection : ProviderListEvent

    /** User tapped row → edit existing profile. */
    public data class NavigateToEdit(val profileId: String) : ProviderListEvent
}

/**
 * Intents the screen dispatches into [ProviderListViewModel].
 */
public sealed interface ProviderListIntent {
    public data class SetActive(val profileId: String) : ProviderListIntent
    public data class Edit(val profileId: String) : ProviderListIntent
    public data class TestConnection(val profileId: String) : ProviderListIntent

    /** Open the per-row delete confirmation dialog. */
    public data class RequestDelete(val profileId: String) : ProviderListIntent
    public data object ConfirmDelete : ProviderListIntent
    public data object DismissDelete : ProviderListIntent

    /** Open the destructive "clear all" confirmation dialog (R9 AC5). */
    public data object RequestClearAll : ProviderListIntent
    public data object ConfirmClearAll : ProviderListIntent
    public data object DismissClearAll : ProviderListIntent

    /** User pressed FAB to add a new profile. */
    public data object AddNew : ProviderListIntent

    /** User dismissed the snackbar showing the latest Connection_Test result. */
    public data object DismissLatestTest : ProviderListIntent
}

/**
 * ViewModel backing [ProviderListScreen] (design §6.1).
 *
 * Combines [ListProfilesUseCase] and [GetActiveProfileUseCase] into a
 * single [ProviderListUiState] and tracks the most recent successful
 * Connection_Test per profile in memory only — the indicator clears as
 * soon as the underlying profile's `updatedAt` advances (R7 AC4).
 *
 * Destructive actions ([ProviderListIntent.ConfirmDelete] and
 * [ProviderListIntent.ConfirmClearAll]) are **gated by an explicit
 * confirmation step** so the screen always renders an [AlertDialog]
 * before invoking the store's delete paths (R9 AC5). The "Clear all"
 * branch additionally dispatches [ProviderListEvent.NavigateToSelection]
 * so the caller can surface the selection screen on the next
 * recomposition.
 *
 * Requirements: 4.1, 4.2, 4.5, 4.6, 5.1, 5.2, 5.3, 7.1, 7.3, 7.4, 9.2,
 * 9.5.
 */
@HiltViewModel
public class ProviderListViewModel @Inject constructor(
    listProfiles: ListProfilesUseCase,
    getActiveProfile: GetActiveProfileUseCase,
    private val store: ProviderProfileStore,
    private val setActive: SetActiveProfileUseCase,
    private val deleteProfile: DeleteProfileUseCase,
    private val connectionTester: ConnectionTester,
) : ViewModel() {

    // --- Internal state --------------------------------------------------

    /** Successful Connection_Test snapshots keyed by profileId.
     *
     *  Entry value is the `updatedAt` of the profile at the time the test
     *  succeeded; if the live profile's `updatedAt` has advanced past it,
     *  the indicator is considered stale and is suppressed (R7 AC4).
     */
    private val lastTestOkSnapshots: MutableMap<String, Long> = mutableMapOf()

    private val _uiState: MutableStateFlow<ProviderListUiState> =
        MutableStateFlow(ProviderListUiState())
    public val uiState: StateFlow<ProviderListUiState> = _uiState.asStateFlow()

    private val _events: Channel<ProviderListEvent> = Channel(Channel.BUFFERED)
    public val events: kotlinx.coroutines.flow.Flow<ProviderListEvent> = _events.receiveAsFlow()

    init {
        // Combine profile list with active id so a single emission always carries
        // the consistent (rows, isActive) projection (R5 AC2 200 ms latency).
        combine(
            listProfiles.invoke(),
            getActiveProfile.invoke(),
        ) { profiles, active ->
            buildRows(profiles, active?.profileId)
        }.onEach { rows ->
            _uiState.update { it.copy(rows = rows, loading = false) }
        }.launchIn(viewModelScope)
    }

    // --- Intent dispatch -------------------------------------------------

    public fun onIntent(intent: ProviderListIntent) {
        when (intent) {
            is ProviderListIntent.SetActive -> setActiveAsync(intent.profileId)
            is ProviderListIntent.Edit -> {
                viewModelScope.launch { _events.send(ProviderListEvent.NavigateToEdit(intent.profileId)) }
            }
            is ProviderListIntent.TestConnection -> testConnection(intent.profileId)
            is ProviderListIntent.RequestDelete ->
                _uiState.update { it.copy(pendingDeleteId = intent.profileId) }
            ProviderListIntent.ConfirmDelete -> confirmDelete()
            ProviderListIntent.DismissDelete ->
                _uiState.update { it.copy(pendingDeleteId = null) }
            ProviderListIntent.RequestClearAll ->
                _uiState.update { it.copy(pendingClearAll = true) }
            ProviderListIntent.ConfirmClearAll -> confirmClearAll()
            ProviderListIntent.DismissClearAll ->
                _uiState.update { it.copy(pendingClearAll = false) }
            ProviderListIntent.AddNew -> {
                viewModelScope.launch { _events.send(ProviderListEvent.NavigateToSelection) }
            }
            ProviderListIntent.DismissLatestTest ->
                _uiState.update { it.copy(latestTest = null) }
        }
    }

    // --- Implementation --------------------------------------------------

    private fun setActiveAsync(profileId: String) {
        viewModelScope.launch { setActive(profileId) }
    }

    private fun confirmDelete() {
        val target = _uiState.value.pendingDeleteId ?: return
        viewModelScope.launch {
            // Drop any cached test indicator for the deleted row.
            lastTestOkSnapshots.remove(target)
            deleteProfile(target)
            _uiState.update { it.copy(pendingDeleteId = null) }
        }
    }

    /**
     * Implements the destructive "Clear all credentials" action (R9 AC5,
     * Property 17). Calls [ProviderProfileStore.deleteAll] directly
     * since no shared use case wraps it; emits
     * [ProviderListEvent.NavigateToSelection] so the host can route the
     * user back to the provider selection screen.
     */
    private fun confirmClearAll() {
        viewModelScope.launch {
            lastTestOkSnapshots.clear()
            val result = store.deleteAll()
            _uiState.update {
                it.copy(
                    pendingClearAll = false,
                    latestTest = null,
                    pendingDeleteId = null,
                )
            }
            if (result.isSuccess) {
                _events.send(ProviderListEvent.NavigateToSelection)
            }
        }
    }

    private fun testConnection(profileId: String) {
        viewModelScope.launch {
            val profile = store.get(profileId) ?: return@launch
            val result = connectionTester.test(profile)
            // Track in-memory success indicator until the next edit (R7 AC4).
            if (result.outcome == com.claudemobile.core.domain.providers.ConnectionTestOutcome.Ok) {
                lastTestOkSnapshots[profileId] = profile.updatedAt
            } else {
                lastTestOkSnapshots.remove(profileId)
            }
            // Re-derive rows so the new lastTestOk flag is reflected.
            val current = _uiState.value
            val rebuilt = current.rows.map { row ->
                row.copy(lastTestOk = isLastTestOkFor(row.profile))
            }
            _uiState.update {
                it.copy(
                    rows = rebuilt,
                    latestTest = ProviderListUiState.TestSnapshot(profileId, result),
                )
            }
        }
    }

    private fun buildRows(
        profiles: List<ProviderProfile>,
        activeId: String?,
    ): List<ProviderListRow> = profiles.map { profile ->
        ProviderListRow(
            profile = profile,
            isActive = profile.profileId == activeId,
            lastTestOk = isLastTestOkFor(profile),
            displayPreset = displayPresetFor(profile.presetReference),
        )
    }

    private fun isLastTestOkFor(profile: ProviderProfile): Boolean {
        val recordedAt = lastTestOkSnapshots[profile.profileId] ?: return false
        // The test's success applied to the version at recordedAt; if the
        // profile has been edited since, drop the stale flag.
        if (profile.updatedAt > recordedAt) {
            lastTestOkSnapshots.remove(profile.profileId)
            return false
        }
        return true
    }

    private fun displayPresetFor(reference: PresetReference): String = when (reference) {
        is PresetReference.Preset -> reference.presetId
        PresetReference.Custom -> CUSTOM_DISPLAY_PRESET
    }

    @Suppress("UnusedPrivateMember")
    private fun handleResult(result: AppResult<Unit>) {
        // Hook left intentionally simple — error surfaces piggy-back on
        // the snackbar via Channel events in a follow-up; for now use
        // cases swallow `Failure` cases so the UI shows an unchanged
        // dialog state.
    }

    public companion object {
        /** Literal label rendered in the row's "preset" column for Custom profiles (R4 AC1). */
        public const val CUSTOM_DISPLAY_PRESET: String = "custom"
    }
}
