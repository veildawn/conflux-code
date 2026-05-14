package com.claudemobile.features.settings.providers.selection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claudemobile.core.domain.providers.ProviderPreset
import com.claudemobile.core.ui.R as CoreUiR
import com.claudemobile.feature.settings.R as SettingsR

/**
 * Provider selection entry point (design §6.1).
 *
 * Lists every built-in [ProviderPreset] returned by
 * [com.claudemobile.core.domain.providers.ProviderRegistry] plus a trailing
 * "Custom (Anthropic compatible)" row. Tapping any row invokes the
 * corresponding navigation callback; the screen itself does not know about
 * [androidx.navigation.NavHostController].
 *
 * Requirements: 1.1, 1.4 (ai-provider-presets).
 */
@Composable
public fun ProviderSelectionScreen(
    onPresetSelected: (presetId: String) -> Unit,
    onCustomSelected: () -> Unit,
    onBack: () -> Unit,
    viewModel: ProviderSelectionViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ProviderSelectionScreenContent(
        presets = uiState.presets,
        onPresetSelected = onPresetSelected,
        onCustomSelected = onCustomSelected,
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * Stateless implementation, extracted so it can be exercised from Compose UI
 * tests without requiring Hilt/DI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderSelectionScreenContent(
    presets: List<ProviderPreset>,
    onPresetSelected: (presetId: String) -> Unit,
    onCustomSelected: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            val backDescription = stringResource(SettingsR.string.settings_navigate_back_description)
            TopAppBar(
                title = { Text(stringResource(CoreUiR.string.provider_selection_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = backDescription },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .testTag(PROVIDER_SELECTION_LIST_TAG),
            verticalArrangement = Arrangement.Top,
        ) {
            items(
                items = presets,
                key = { it.presetId },
            ) { preset ->
                BuiltinPresetRow(
                    preset = preset,
                    onClick = { onPresetSelected(preset.presetId) },
                )
                HorizontalDivider()
            }

            item(key = PROVIDER_SELECTION_CUSTOM_ROW_TAG) {
                CustomProviderRow(onClick = onCustomSelected)
            }
        }
    }
}

@Composable
private fun BuiltinPresetRow(
    preset: ProviderPreset,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayName = stringResource(preset.displayNameResId)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(presetRowTestTag(preset.presetId)),
    ) {
        ListItem(
            headlineContent = { Text(displayName) },
            supportingContent = { Text(preset.baseUrl, style = MaterialTheme.typography.bodySmall) },
            trailingContent = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CustomProviderRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 4.dp)
            .testTag(PROVIDER_SELECTION_CUSTOM_ROW_TAG),
    ) {
        ListItem(
            headlineContent = { Text(stringResource(CoreUiR.string.provider_custom_label)) },
            trailingContent = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// --- Test tags ----------------------------------------------------------

public const val PROVIDER_SELECTION_LIST_TAG: String = "provider_selection_list"
public const val PROVIDER_SELECTION_CUSTOM_ROW_TAG: String = "provider_selection_custom_row"

/** Stable test tag for the preset row identified by [presetId]. */
public fun presetRowTestTag(presetId: String): String = "provider_selection_row_$presetId"
