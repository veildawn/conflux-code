package com.claudemobile.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.claudemobile.app.bootstrap.BootstrapScreen
import com.claudemobile.core.domain.model.SessionId
import com.claudemobile.feature.chat.ChatAction
import com.claudemobile.feature.chat.ChatScreen
import com.claudemobile.feature.chat.ChatViewModel
import com.claudemobile.feature.sessions.SessionsScreen
import com.claudemobile.feature.sessions.SessionsViewModel
import com.claudemobile.feature.settings.SettingsScreen
import com.claudemobile.features.settings.providers.editor.EditorMode
import com.claudemobile.features.settings.providers.editor.ProviderEditorScreen
import com.claudemobile.features.settings.providers.list.ProviderListScreen
import com.claudemobile.features.settings.providers.selection.ProviderSelectionScreen

/**
 * The main navigation graph for the application.
 *
 * Routes:
 * - "bootstrap" — First-launch bootstrap flow
 * - "sessions" — Session list (home screen)
 * - "chat/{sessionId}" — Chat screen for a specific session
 * - "settings" — Settings screen
 * - "provider/selection" — Provider selection (built-in presets + Custom)
 * - "provider/editor/preset/{presetId}" — Editor in create-from-preset mode
 * - "provider/editor/custom" — Editor in create-custom mode
 * - "provider/editor/edit/{profileId}" — Editor in edit-existing-profile mode
 * - "provider/list" — Saved provider profiles list
 */
@Composable
fun AppNavGraph(
    navController: NavHostController,
    startDestination: String,
    isOffline: Boolean = false,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable(route = NavRoutes.BOOTSTRAP) {
            BootstrapScreen(
                onBootstrapComplete = {
                    navController.navigate(NavRoutes.SESSIONS) {
                        popUpTo(NavRoutes.BOOTSTRAP) { inclusive = true }
                    }
                },
                onNavigateToSettings = {
                    navController.navigate(NavRoutes.SETTINGS)
                },
                onNavigateToProviderSelection = {
                    navController.navigate(NavRoutes.PROVIDER_SELECTION)
                },
            )
        }

        composable(route = NavRoutes.SESSIONS) {
            val viewModel: SessionsViewModel = hiltViewModel()
            SessionsScreen(
                viewModel = viewModel,
                onSessionClick = { sessionId ->
                    navController.navigate(NavRoutes.chatRoute(sessionId.value))
                },
                onNavigateToSettings = {
                    navController.navigate(NavRoutes.SETTINGS)
                },
                onNavigateToProviderSelection = {
                    navController.navigate(NavRoutes.PROVIDER_SELECTION)
                },
            )
        }

        composable(
            route = NavRoutes.CHAT,
            arguments = listOf(
                navArgument("sessionId") { type = NavType.StringType }
            ),
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
            val viewModel: ChatViewModel = hiltViewModel()

            LaunchedEffect(sessionId) {
                viewModel.onAction(ChatAction.LoadSession(SessionId(sessionId)))
            }

            ChatScreen(
                viewModel = viewModel,
                isOffline = isOffline,
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(route = NavRoutes.SETTINGS) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToProviders = { navController.navigate(NavRoutes.PROVIDER_LIST) },
            )
        }

        // --- ai-provider-presets ---
        composable(route = NavRoutes.PROVIDER_SELECTION) {
            ProviderSelectionScreen(
                onPresetSelected = { presetId ->
                    navController.navigate(NavRoutes.providerEditorPreset(presetId))
                },
                onCustomSelected = {
                    navController.navigate(NavRoutes.providerEditorCustom())
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = NavRoutes.PROVIDER_EDITOR_PRESET,
            arguments = listOf(
                navArgument("presetId") { type = NavType.StringType }
            ),
        ) { backStackEntry ->
            val presetId = backStackEntry.arguments?.getString("presetId").orEmpty()
            ProviderEditorScreen(
                mode = EditorMode.Preset(presetId),
                onSaved = {
                    // Pop back past the Selection screen so the caller
                    // (Bootstrap or Provider List) can observe the new profile.
                    navController.popBackStack(NavRoutes.PROVIDER_SELECTION, inclusive = true)
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(route = NavRoutes.PROVIDER_EDITOR_CUSTOM) {
            ProviderEditorScreen(
                mode = EditorMode.Custom,
                onSaved = {
                    navController.popBackStack(NavRoutes.PROVIDER_SELECTION, inclusive = true)
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = NavRoutes.PROVIDER_EDITOR_EDIT,
            arguments = listOf(
                navArgument("profileId") { type = NavType.StringType }
            ),
        ) { backStackEntry ->
            val profileId = backStackEntry.arguments?.getString("profileId").orEmpty()
            ProviderEditorScreen(
                mode = EditorMode.Edit(profileId),
                onSaved = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }

        composable(route = NavRoutes.PROVIDER_LIST) {
            ProviderListScreen(
                onAddProfile = {
                    navController.navigate(NavRoutes.PROVIDER_SELECTION)
                },
                onEditProfile = { profileId ->
                    navController.navigate(NavRoutes.providerEditorEdit(profileId))
                },
                onClearAllNavigateToSelection = {
                    navController.navigate(NavRoutes.PROVIDER_SELECTION) {
                        popUpTo(NavRoutes.PROVIDER_LIST) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
    }
}
