package com.claudemobile.features.settings.providers.selection

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.claudemobile.core.domain.providers.ProviderRegistry
import org.junit.Rule
import org.junit.Test

/**
 * Compose UI tests for [ProviderSelectionScreen] (task 7.2).
 *
 * Exercises the stateless [ProviderSelectionScreenContent] composable directly
 * against the [ProviderRegistry.Default] preset list so the test does not
 * depend on Hilt / ViewModel wiring.
 *
 * Requirements: 1.1, 1.4 (ai-provider-presets).
 */
class ProviderSelectionScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun rendersAllBuiltinPresetsAndCustomRow() {
        val presets = ProviderRegistry.Default.allPresets()

        composeTestRule.setContent {
            ProviderSelectionScreenContent(
                presets = presets,
                onPresetSelected = {},
                onCustomSelected = {},
                onBack = {},
            )
        }

        // All three built-in presets render with their test tag.
        for (preset in presets) {
            composeTestRule
                .onNodeWithTag(presetRowTestTag(preset.presetId))
                .assertIsDisplayed()
        }

        // Custom row renders.
        composeTestRule
            .onNodeWithTag(PROVIDER_SELECTION_CUSTOM_ROW_TAG)
            .assertIsDisplayed()
    }

    @Test
    fun clickingPresetInvokesCallbackWithPresetId() {
        val presets = ProviderRegistry.Default.allPresets()
        val selected = mutableListOf<String>()

        composeTestRule.setContent {
            ProviderSelectionScreenContent(
                presets = presets,
                onPresetSelected = { selected += it },
                onCustomSelected = {},
                onBack = {},
            )
        }

        val first = presets.first()
        composeTestRule
            .onNodeWithTag(presetRowTestTag(first.presetId))
            .performClick()

        assert(selected == listOf(first.presetId)) {
            "Expected onPresetSelected to be called with ${first.presetId}, got $selected"
        }
    }

    @Test
    fun clickingCustomRowInvokesCustomCallback() {
        val presets = ProviderRegistry.Default.allPresets()
        var customClicked = 0

        composeTestRule.setContent {
            ProviderSelectionScreenContent(
                presets = presets,
                onPresetSelected = {},
                onCustomSelected = { customClicked++ },
                onBack = {},
            )
        }

        composeTestRule
            .onNodeWithTag(PROVIDER_SELECTION_CUSTOM_ROW_TAG)
            .performClick()

        assert(customClicked == 1) {
            "Expected onCustomSelected to be called exactly once, was $customClicked"
        }
    }

    @Test
    fun rendersCustomLabelText() {
        composeTestRule.setContent {
            ProviderSelectionScreenContent(
                presets = ProviderRegistry.Default.allPresets(),
                onPresetSelected = {},
                onCustomSelected = {},
                onBack = {},
            )
        }

        // "Custom (Anthropic compatible)" in the en default resource set.
        composeTestRule
            .onNodeWithText("Custom (Anthropic compatible)")
            .assertIsDisplayed()
    }
}
