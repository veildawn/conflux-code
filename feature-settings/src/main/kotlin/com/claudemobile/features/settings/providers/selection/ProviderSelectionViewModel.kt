package com.claudemobile.features.settings.providers.selection

import androidx.lifecycle.ViewModel
import com.claudemobile.core.domain.providers.ProviderPreset
import com.claudemobile.core.domain.providers.ProviderRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * UI state for [ProviderSelectionScreen].
 *
 * Holds the ordered list of built-in [ProviderPreset]s from [ProviderRegistry].
 * A non-preset "Custom (Anthropic compatible)" entry is rendered in the screen
 * itself and does not need to appear in state — see design §6.1.
 */
public data class ProviderSelectionUiState(
    val presets: List<ProviderPreset> = emptyList(),
)

/**
 * ViewModel backing the Provider Selection screen (design §6.1).
 *
 * The screen simply lists the built-in presets shipped with the app plus a
 * separate "Custom (Anthropic compatible)" row. All data is sourced from
 * [ProviderRegistry] which is pure, in-process, and emits a stable list — so
 * the state is set once at construction time and never mutates.
 *
 * Navigation is handled at the screen level via callbacks (`onPresetSelected`,
 * `onCustomSelected`, `onBack`), keeping this ViewModel free of Android /
 * navigation dependencies.
 *
 * Requirements: 1.1, 1.4 (ai-provider-presets).
 */
@HiltViewModel
public class ProviderSelectionViewModel @Inject constructor(
    providerRegistry: ProviderRegistry,
) : ViewModel() {

    private val _uiState: MutableStateFlow<ProviderSelectionUiState> =
        MutableStateFlow(ProviderSelectionUiState(presets = providerRegistry.allPresets()))

    public val uiState: StateFlow<ProviderSelectionUiState> = _uiState.asStateFlow()
}
