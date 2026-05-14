package com.claudemobile.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.claudemobile.app.navigation.AppNavGraph
import com.claudemobile.app.navigation.NavRoutes
import com.claudemobile.app.startup.AppMigrationCoordinator
import com.claudemobile.app.startup.MigrationState
import com.claudemobile.core.bridge.network.NetworkMonitor
import com.claudemobile.core.bridge.service.ClaudeSessionService
import com.claudemobile.core.bridge.service.ServicePreferencesStore
import com.claudemobile.core.domain.bridge.BootstrapManager
import com.claudemobile.core.domain.model.AppSettings
import com.claudemobile.core.domain.repository.ConversationRepository
import com.claudemobile.core.domain.repository.DiagnosticsRepository
import com.claudemobile.core.domain.repository.SettingsStore
import com.claudemobile.core.ui.i18n.ProvideAppLocale
import com.claudemobile.core.ui.theme.ClaudeMobileTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Main activity for the Claude Mobile application.
 *
 * Responsibilities:
 * - Sets up Jetpack Compose with the app theme
 * - Configures navigation with the app nav graph
 * - Handles runtime permission requests (POST_NOTIFICATIONS on API 33+)
 * - Handles permission denial gracefully
 * - Detects OS kill of foreground service on launch
 * - Determines start destination (bootstrap vs sessions)
 *
 * Requirements: 1.1, 1.2, 1.3, 7.5, 10.1, 10.2, 10.3, 10.4, 10.5, 12.5
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var bootstrapManager: BootstrapManager

    @Inject
    lateinit var settingsStore: SettingsStore

    @Inject
    lateinit var preferencesStore: ServicePreferencesStore

    @Inject
    lateinit var conversationRepository: ConversationRepository

    @Inject
    lateinit var diagnosticsRepository: DiagnosticsRepository

    @Inject
    lateinit var networkMonitor: NetworkMonitor

    /**
     * Shared coordinator injected from [ClaudeMobileApplication]. The same singleton
     * instance is used here so that the migration started in [Application.onCreate]
     * is already in progress (or complete) by the time the Activity reads [state].
     *
     * Requirements: 8.1, 8.4.
     */
    @Inject
    lateinit var migrationCoordinator: AppMigrationCoordinator

    private var notificationPermissionGranted = mutableStateOf(true)
    private var permissionDenialMessage = mutableStateOf<String?>(null)
    private var pendingSessionDeepLink = mutableStateOf<String?>(null)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        notificationPermissionGranted.value = isGranted
        if (!isGranted) {
            permissionDenialMessage.value =
                getString(R.string.permission_notification_denied)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Detect and handle OS kill of foreground service from previous run
        lifecycleScope.launch {
            ClaudeSessionService.detectAndHandleOsKill(
                context = applicationContext,
                preferencesStore = preferencesStore,
                conversationRepository = conversationRepository,
                diagnosticsRepository = diagnosticsRepository,
            )
        }

        // Check if launched from notification deep link
        pendingSessionDeepLink.value =
            intent?.getStringExtra(ClaudeSessionService.EXTRA_OPEN_SESSION_ID)

        // Request POST_NOTIFICATIONS permission on API 33+
        requestNotificationPermissionIfNeeded()

        // Start network monitoring
        networkMonitor.startMonitoring()

        setContent {
            val settings by settingsStore.settings.collectAsState(initial = AppSettings())
            var startDestination by remember { mutableStateOf<String?>(null) }
            val snackbarHostState = remember { SnackbarHostState() }
            val isNetworkConnected by networkMonitor.isConnected.collectAsState()
            val coroutineScope = rememberCoroutineScope()

            // Determine start destination based on bootstrap readiness.
            // Block until the legacy-key migration is terminal (Completed or Failed)
            // so that BootstrapViewModel's Step 6 check sees the already-migrated
            // profile. On MigrationState.Failed the bootstrap route is chosen so the
            // BootstrapScreen can surface the retry action (R8 AC4).
            LaunchedEffect(Unit) {
                // Wait for migration to reach a terminal state.
                val terminalMigration = migrationCoordinator.state
                    .filterIsInstance<MigrationState>()
                    .first { it is MigrationState.Completed || it is MigrationState.Failed }

                val isReady = bootstrapManager.isReady()
                startDestination = when {
                    // Migration failed → always show bootstrap so the retry surface
                    // (BootstrapScreen) is visible (R8 AC4).
                    terminalMigration is MigrationState.Failed -> NavRoutes.BOOTSTRAP
                    isReady -> NavRoutes.SESSIONS
                    else -> NavRoutes.BOOTSTRAP
                }
            }

            // Show permission denial snackbar
            LaunchedEffect(permissionDenialMessage.value) {
                permissionDenialMessage.value?.let { message ->
                    snackbarHostState.showSnackbar(
                        message = message,
                        duration = SnackbarDuration.Long,
                    )
                    permissionDenialMessage.value = null
                }
            }

            ClaudeMobileTheme(themeMode = settings.themeMode) {
                ProvideAppLocale(
                    appLanguage = settings.appLanguage,
                    onLocaleError = { errorMessage ->
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(
                                message = errorMessage,
                                duration = SnackbarDuration.Short,
                            )
                        }
                    },
                ) {
                    startDestination?.let { destination ->
                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            contentWindowInsets = WindowInsets(0, 0, 0, 0),
                            snackbarHost = { SnackbarHost(snackbarHostState) },
                        ) { paddingValues ->
                            val navController = rememberNavController()

                            AppNavGraph(
                                navController = navController,
                                startDestination = destination,
                                isOffline = !isNetworkConnected,
                                modifier = Modifier.padding(paddingValues),
                            )

                            // Handle deep link from foreground service notification
                            HandleServiceDeepLink(navController)
                        }
                    }
                }
            }
        }
    }

    /**
     * Handles new intents when the activity is already running (e.g., notification tap
     * while the app is in the foreground). Uses FLAG_ACTIVITY_SINGLE_TOP so this is
     * called instead of creating a new activity instance.
     *
     * Requirement 7.4: Notification tap opens the associated session.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val sessionId = intent.getStringExtra(ClaudeSessionService.EXTRA_OPEN_SESSION_ID)
        if (sessionId != null) {
            pendingSessionDeepLink.value = sessionId
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        networkMonitor.stopMonitoring()
    }

    /**
     * Requests POST_NOTIFICATIONS permission on Android 13+ (API 33).
     * If already granted or on older API levels, this is a no-op.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            when {
                ContextCompat.checkSelfPermission(this, permission) ==
                    PackageManager.PERMISSION_GRANTED -> {
                    notificationPermissionGranted.value = true
                }
                shouldShowRequestPermissionRationale(permission) -> {
                    // User previously denied — show rationale via snackbar, then request
                    permissionDenialMessage.value =
                        getString(R.string.permission_notification_rationale)
                    notificationPermissionLauncher.launch(permission)
                }
                else -> {
                    notificationPermissionLauncher.launch(permission)
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun HandleServiceDeepLink(navController: androidx.navigation.NavHostController) {
        val sessionId = pendingSessionDeepLink.value
        LaunchedEffect(sessionId) {
            sessionId?.let { id ->
                navController.navigate(NavRoutes.chatRoute(id)) {
                    launchSingleTop = true
                }
                // Clear the deep link so it doesn't re-trigger on recomposition
                pendingSessionDeepLink.value = null
            }
        }
    }
}
