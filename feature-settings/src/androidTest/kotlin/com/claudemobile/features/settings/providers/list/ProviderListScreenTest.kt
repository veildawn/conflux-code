package com.claudemobile.features.settings.providers.list

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.claudemobile.core.domain.providers.AuthHeaderStyle
import com.claudemobile.core.domain.providers.PresetReference
import com.claudemobile.core.domain.providers.ProviderProfile
import org.junit.Rule
import org.junit.Test

/**
 * Compose UI smoke tests for [ProviderListScreenContent] (tasks 7.7 and
 * 7.8). The tests drive the stateless composable directly so they can
 * run without Hilt / a real ViewModel; the per-row interactions are
 * verified at the JVM level in [ProviderListViewModelTest].
 *
 * Coverage:
 *  - rows render with their displayName, masked key, model, and the
 *    Active badge for the active row (R4 AC1, R5 AC3, R9 AC2).
 *  - the FAB is present (R4 AC2).
 *  - the destructive "Clear all" dialog renders when `pendingClearAll`
 *    is true and dispatches `ConfirmClearAll` / `DismissClearAll`
 *    (R9 AC5; task 7.8).
 *  - the per-row delete dialog renders when `pendingDeleteId` is set
 *    and dispatches `ConfirmDelete` / `DismissDelete` (R4 AC5).
 *
 * Requirements: 4.1, 4.2, 4.5, 5.3, 9.2, 9.5.
 */
class ProviderListScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val customProfile = ProviderProfile(
        profileId = "p-custom",
        displayName = "My Custom",
        presetReference = PresetReference.Custom,
        baseUrl = "https://example.com",
        apiKey = "secret-12345",
        model = "claude-3",
        smallFastModel = null,
        authHeaderStyle = AuthHeaderStyle.ApiKey,
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    private val presetProfile = ProviderProfile(
        profileId = "p-preset",
        displayName = "GLM",
        presetReference = PresetReference.Preset("glm_coding_plan"),
        baseUrl = "https://open.bigmodel.cn/api/anthropic",
        apiKey = "sk-glm-abcd",
        model = "glm-4.6",
        smallFastModel = null,
        authHeaderStyle = AuthHeaderStyle.AuthToken,
        createdAt = 2_000L,
        updatedAt = 2_000L,
    )

    private fun row(profile: ProviderProfile, isActive: Boolean = false, lastTestOk: Boolean = false): ProviderListRow =
        ProviderListRow(
            profile = profile,
            isActive = isActive,
            lastTestOk = lastTestOk,
            displayPreset = if (profile.presetReference is PresetReference.Preset) {
                (profile.presetReference as PresetReference.Preset).presetId
            } else {
                ProviderListViewModel.CUSTOM_DISPLAY_PRESET
            },
        )

    @Test
    fun rendersRowsWithFabAndActiveBadge() {
        val state = ProviderListUiState(
            rows = listOf(
                row(presetProfile, isActive = true),
                row(customProfile),
            ),
            loading = false,
        )
        composeTestRule.setContent {
            ProviderListScreenContent(
                state = state,
                snackbarHostState = SnackbarHostState(),
                onIntent = {},
                onAddProfile = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithTag(rowTestTag(presetProfile.profileId)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(rowTestTag(customProfile.profileId)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(activeBadgeTag(presetProfile.profileId)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(PROVIDER_LIST_FAB_TAG).assertIsDisplayed()
    }

    @Test
    fun lastTestOkRowDisplaysCheckmarkIndicator() {
        val state = ProviderListUiState(
            rows = listOf(row(customProfile, lastTestOk = true)),
            loading = false,
        )
        composeTestRule.setContent {
            ProviderListScreenContent(
                state = state,
                snackbarHostState = SnackbarHostState(),
                onIntent = {},
                onAddProfile = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithTag(lastTestOkTag(customProfile.profileId)).assertIsDisplayed()
    }

    @Test
    fun emptyStateRendersWhenNoRows() {
        val state = ProviderListUiState(rows = emptyList(), loading = false)
        composeTestRule.setContent {
            ProviderListScreenContent(
                state = state,
                snackbarHostState = SnackbarHostState(),
                onIntent = {},
                onAddProfile = {},
                onBack = {},
            )
        }
        composeTestRule.onNodeWithTag(PROVIDER_LIST_EMPTY_TAG).assertIsDisplayed()
    }

    // -----------------------------------------------------------------
    // Per-row delete dialog (task 7.7).
    // -----------------------------------------------------------------

    @Test
    fun deleteDialogRendersWhenPending() {
        val state = ProviderListUiState(
            rows = listOf(row(customProfile)),
            loading = false,
            pendingDeleteId = customProfile.profileId,
        )
        composeTestRule.setContent {
            ProviderListScreenContent(
                state = state,
                snackbarHostState = SnackbarHostState(),
                onIntent = {},
                onAddProfile = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithTag(DELETE_DIALOG_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(DELETE_DIALOG_CONFIRM_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(DELETE_DIALOG_DISMISS_TAG).assertIsDisplayed()
    }

    @Test
    fun deleteDialogConfirmDispatchesConfirmDelete() {
        val state = ProviderListUiState(
            rows = listOf(row(customProfile)),
            loading = false,
            pendingDeleteId = customProfile.profileId,
        )
        val intents = mutableListOf<ProviderListIntent>()
        composeTestRule.setContent {
            ProviderListScreenContent(
                state = state,
                snackbarHostState = SnackbarHostState(),
                onIntent = { intents += it },
                onAddProfile = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithTag(DELETE_DIALOG_CONFIRM_TAG).performClick()
        assert(intents.contains(ProviderListIntent.ConfirmDelete)) {
            "Expected ConfirmDelete to be dispatched, got $intents"
        }
    }

    // -----------------------------------------------------------------
    // Destructive "Clear all" dialog (task 7.8).
    // -----------------------------------------------------------------

    @Test
    fun clearAllDialogIsHiddenByDefault() {
        val state = ProviderListUiState(
            rows = listOf(row(customProfile)),
            loading = false,
        )
        composeTestRule.setContent {
            ProviderListScreenContent(
                state = state,
                snackbarHostState = SnackbarHostState(),
                onIntent = {},
                onAddProfile = {},
                onBack = {},
            )
        }
        composeTestRule.onNodeWithTag(CLEAR_ALL_DIALOG_TAG).assertIsNotDisplayed()
    }

    @Test
    fun clearAllDialogRendersWhenPendingClearAll() {
        val state = ProviderListUiState(
            rows = listOf(row(customProfile)),
            loading = false,
            pendingClearAll = true,
        )
        composeTestRule.setContent {
            ProviderListScreenContent(
                state = state,
                snackbarHostState = SnackbarHostState(),
                onIntent = {},
                onAddProfile = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithTag(CLEAR_ALL_DIALOG_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(CLEAR_ALL_CONFIRM_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(CLEAR_ALL_DISMISS_TAG).assertIsDisplayed()
    }

    @Test
    fun clearAllConfirmDispatchesConfirmClearAll() {
        val state = ProviderListUiState(
            rows = listOf(row(customProfile)),
            loading = false,
            pendingClearAll = true,
        )
        val intents = mutableListOf<ProviderListIntent>()
        composeTestRule.setContent {
            ProviderListScreenContent(
                state = state,
                snackbarHostState = SnackbarHostState(),
                onIntent = { intents += it },
                onAddProfile = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithTag(CLEAR_ALL_CONFIRM_TAG).performClick()
        assert(intents.contains(ProviderListIntent.ConfirmClearAll)) {
            "Expected ConfirmClearAll to be dispatched, got $intents"
        }
    }

    @Test
    fun clearAllDismissDispatchesDismissClearAll() {
        val state = ProviderListUiState(
            rows = listOf(row(customProfile)),
            loading = false,
            pendingClearAll = true,
        )
        val intents = mutableListOf<ProviderListIntent>()
        composeTestRule.setContent {
            ProviderListScreenContent(
                state = state,
                snackbarHostState = SnackbarHostState(),
                onIntent = { intents += it },
                onAddProfile = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithTag(CLEAR_ALL_DISMISS_TAG).performClick()
        assert(intents.contains(ProviderListIntent.DismissClearAll)) {
            "Expected DismissClearAll to be dispatched, got $intents"
        }
    }
}
