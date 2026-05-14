package com.claudemobile.app.startup

import com.claudemobile.core.common.CoroutineDispatchers
import com.claudemobile.core.data.providers.migration.LegacyKeyMigrator
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Application-scoped coordinator that runs [LegacyKeyMigrator.runIfNeeded] exactly
 * once per process lifetime and exposes the result as a [StateFlow].
 *
 * [ClaudeMobileApplication.onCreate] calls [runMigration] on the IO dispatcher
 * **before** any Bootstrap UI is rendered, so that [BootstrapViewModel]'s Step 6
 * check (`ProviderProfileStore.list()`) can observe the already-migrated profile.
 *
 * [MainActivity] collects [state] and defers the start-destination decision
 * (`bootstrap` vs `sessions`) until the state is terminal ([MigrationState.Completed]
 * or [MigrationState.Failed]).  On [MigrationState.Failed] the Bootstrap screen
 * surfaces a retry action (R8 AC4).
 *
 * Idempotence: [runMigration] is guarded by a `compareAndSet` so concurrent or
 * repeated calls are no-ops after the first invocation. The underlying
 * [LegacyKeyMigrator.runIfNeeded] is itself idempotent (Property 22), so even if
 * the guard were bypassed the final state would be identical.
 *
 * Requirements: 8.1, 8.2, 8.3, 8.4, 8.5.
 */
@Singleton
public class AppMigrationCoordinator @Inject constructor(
    private val migrator: LegacyKeyMigrator,
    private val dispatchers: CoroutineDispatchers,
) {

    private val _state = MutableStateFlow<MigrationState>(MigrationState.Pending)

    /**
     * Observable migration lifecycle. Starts as [MigrationState.Pending], transitions
     * to [MigrationState.Running] when [runMigration] is called, and settles on
     * [MigrationState.Completed] or [MigrationState.Failed].
     */
    public val state: StateFlow<MigrationState> = _state.asStateFlow()

    /** Guards against concurrent / repeated invocations. */
    @Volatile
    private var started = false

    /**
     * Launches [LegacyKeyMigrator.runIfNeeded] on the IO dispatcher.
     *
     * Safe to call from [ClaudeMobileApplication.onCreate] (main thread). The
     * coroutine is launched on a fresh [SupervisorJob] so that a crash inside
     * the migrator does not propagate to the application scope.
     *
     * Subsequent calls after the first are silently ignored.
     */
    public fun runMigration() {
        if (started) return
        started = true

        _state.value = MigrationState.Running

        // Use a dedicated scope so the migration survives even if the caller's
        // scope is cancelled (e.g., the Application scope is torn down in tests).
        val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
        scope.launch {
            val result = runCatching { migrator.runIfNeeded() }
            _state.value = result.fold(
                onSuccess = { migrationResult ->
                    migrationResult.fold(
                        onSuccess = { outcome -> MigrationState.Completed(outcome) },
                        onFailure = { cause -> MigrationState.Failed(cause) },
                    )
                },
                onFailure = { cause -> MigrationState.Failed(cause) },
            )
        }
    }
}
