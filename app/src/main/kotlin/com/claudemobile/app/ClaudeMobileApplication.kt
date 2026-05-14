package com.claudemobile.app

import android.app.Application
import com.claudemobile.app.startup.AppMigrationCoordinator
import com.claudemobile.core.bridge.service.ClaudeSessionService
import com.claudemobile.core.bridge.service.ServicePreferencesStore
import com.claudemobile.core.domain.repository.ConversationRepository
import com.claudemobile.core.domain.repository.DiagnosticsRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Application class annotated with @HiltAndroidApp to enable Hilt dependency injection.
 *
 * This is the entry point for the Hilt DI graph. All modules across the multi-module
 * project are automatically discovered and included in the component hierarchy.
 *
 * On startup:
 * 1. Runs [AppMigrationCoordinator.runMigration] on the IO dispatcher **before** any
 *    Bootstrap UI is rendered, so that BootstrapViewModel's Step 6 check
 *    (`ProviderProfileStore.list()`) can observe the already-migrated profile.
 * 2. Detects if the OS killed the foreground service on a previous run and marks any
 *    in-flight messages as killed_by_os.
 *
 * [MainActivity] observes [AppMigrationCoordinator.state] and defers the
 * start-destination decision (`bootstrap` vs `sessions`) until the migration is
 * terminal (Requirements: 8.1–8.5).
 *
 * Requirements: 7.6, 8.1, 8.2, 8.3, 8.4, 8.5, 12.5
 */
@HiltAndroidApp
class ClaudeMobileApplication : Application() {

    @Inject
    lateinit var servicePreferencesStore: ServicePreferencesStore

    @Inject
    lateinit var conversationRepository: ConversationRepository

    @Inject
    lateinit var diagnosticsRepository: DiagnosticsRepository

    /**
     * Coordinator that runs [LegacyKeyMigrator.runIfNeeded] exactly once per process
     * lifetime and exposes the result as a [StateFlow<MigrationState>].
     *
     * Injected here (rather than in [MainActivity]) so the migration starts on the
     * IO dispatcher before any Compose composition begins, satisfying the "before any
     * Bootstrap UI is rendered" requirement (R8 AC1).
     */
    @Inject
    lateinit var migrationCoordinator: AppMigrationCoordinator

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        // Start the legacy-key migration immediately. The coroutine runs on the IO
        // dispatcher inside AppMigrationCoordinator; this call returns instantly.
        migrationCoordinator.runMigration()
        detectOsKill()
    }

    /**
     * Detects if the OS killed the foreground service on a previous run.
     * If so, marks any in-flight assistant messages as killed_by_os with a system note.
     */
    private fun detectOsKill() {
        applicationScope.launch {
            ClaudeSessionService.detectAndHandleOsKill(
                context = this@ClaudeMobileApplication,
                preferencesStore = servicePreferencesStore,
                conversationRepository = conversationRepository,
                diagnosticsRepository = diagnosticsRepository,
            )
        }
    }
}
