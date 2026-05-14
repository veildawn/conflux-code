package com.claudemobile.app.startup

import com.claudemobile.core.data.providers.migration.MigrationOutcome

/**
 * Represents the lifecycle of the one-shot legacy-key migration that runs
 * in [ClaudeMobileApplication.onCreate] before any UI is rendered.
 *
 * [MainActivity] observes this state via [AppMigrationCoordinator.state] and
 * blocks the start-destination decision until the state is terminal
 * ([Completed] or [Failed]).
 *
 * Requirements: 8.1, 8.2, 8.3, 8.4, 8.5.
 */
public sealed class MigrationState {

    /**
     * The migrator has not yet been invoked. Initial state emitted by
     * [AppMigrationCoordinator] before [AppMigrationCoordinator.runMigration]
     * is called.
     */
    public data object Pending : MigrationState()

    /**
     * [LegacyKeyMigrator.runIfNeeded] is currently executing.
     */
    public data object Running : MigrationState()

    /**
     * The migrator finished successfully. [outcome] carries the specific
     * terminal branch (migrated / skipped-already-done / skipped-no-key /
     * skipped-profiles-exist).
     *
     * Requirements: 8.1, 8.2, 8.3, 8.5.
     */
    public data class Completed(val outcome: MigrationOutcome) : MigrationState()

    /**
     * The migrator returned [Result.failure]. The legacy credentials are
     * still intact; the UI should surface a retry action (R8 AC4).
     *
     * Requirements: 8.4.
     */
    public data class Failed(val cause: Throwable) : MigrationState()
}
