package com.claudemobile.app.startup

import com.claudemobile.core.common.CoroutineDispatchers
import com.claudemobile.core.data.providers.migration.LegacyKeyMigrator
import com.claudemobile.core.data.providers.migration.MigrationOutcome
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for [AppMigrationCoordinator] and the startup wiring described in task 9.2.
 *
 * Assertions:
 * 1. [AppMigrationCoordinator.runMigration] invokes [LegacyKeyMigrator.runIfNeeded] exactly once.
 * 2. Repeated calls to [runMigration] do not invoke the migrator a second time.
 * 3. The start-destination decision (simulated here as waiting for a terminal
 *    [MigrationState]) blocks until the migration is complete.
 * 4. A successful migration transitions state to [MigrationState.Completed].
 * 5. A failed migration transitions state to [MigrationState.Failed].
 *
 * **Validates: Requirements 8.1, 8.2, 8.3, 8.4, 8.5**
 * **Property 22: Migration idempotence** — running the coordinator N times yields the
 * same final state as running it once (the migrator itself is called only once).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppStartupTest : FunSpec({

    fun makeDispatchers(dispatcher: CoroutineDispatcher): CoroutineDispatchers =
        object : CoroutineDispatchers {
            override val default = dispatcher
            override val io = dispatcher
            override val main = dispatcher
            override val mainImmediate = dispatcher
            override val unconfined = Dispatchers.Unconfined
        }

    test("runMigration invokes LegacyKeyMigrator.runIfNeeded exactly once") {
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val migrator = mockk<LegacyKeyMigrator>()
            coEvery { migrator.runIfNeeded() } returns Result.success(MigrationOutcome.SkippedAlreadyDone)

            val coordinator = AppMigrationCoordinator(migrator, makeDispatchers(testDispatcher))
            coordinator.runMigration()
            advanceUntilIdle()

            coVerify(exactly = 1) { migrator.runIfNeeded() }
        }
    }

    test("repeated calls to runMigration do not invoke migrator a second time") {
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val migrator = mockk<LegacyKeyMigrator>()
            coEvery { migrator.runIfNeeded() } returns Result.success(MigrationOutcome.SkippedAlreadyDone)

            val coordinator = AppMigrationCoordinator(migrator, makeDispatchers(testDispatcher))
            coordinator.runMigration()
            coordinator.runMigration()
            coordinator.runMigration()
            advanceUntilIdle()

            // Migrator must be called exactly once regardless of how many times
            // runMigration() is called (Property 22 — idempotence at the coordinator level).
            coVerify(exactly = 1) { migrator.runIfNeeded() }
        }
    }

    test("start-destination decision waits for migration to complete") {
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val migrator = mockk<LegacyKeyMigrator>()
            coEvery { migrator.runIfNeeded() } returns Result.success(MigrationOutcome.SkippedAlreadyDone)

            val coordinator = AppMigrationCoordinator(migrator, makeDispatchers(testDispatcher))

            // Before runMigration() is called the state is Pending — not terminal.
            coordinator.state.value shouldBe MigrationState.Pending

            coordinator.runMigration()

            // After runMigration() but before the coroutine runs, state is Running.
            coordinator.state.value shouldBe MigrationState.Running

            // Simulate MainActivity waiting for a terminal state (the same pattern used
            // in the real Activity via filterIsInstance + first { terminal }).
            val terminalState = coordinator.state
                .filterIsInstance<MigrationState>()
                .first { it is MigrationState.Completed || it is MigrationState.Failed }

            // The terminal state is reached only after the coroutine completes.
            terminalState.shouldBeInstanceOf<MigrationState.Completed>()
        }
    }

    test("successful migration transitions state to Completed") {
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val migrator = mockk<LegacyKeyMigrator>()
            val outcome = MigrationOutcome.Migrated("profile-123")
            coEvery { migrator.runIfNeeded() } returns Result.success(outcome)

            val coordinator = AppMigrationCoordinator(migrator, makeDispatchers(testDispatcher))
            coordinator.runMigration()
            advanceUntilIdle()

            val state = coordinator.state.value
            state.shouldBeInstanceOf<MigrationState.Completed>()
            (state as MigrationState.Completed).outcome shouldBe outcome
        }
    }

    test("failed migration transitions state to Failed") {
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val migrator = mockk<LegacyKeyMigrator>()
            val cause = RuntimeException("Keystore unavailable")
            coEvery { migrator.runIfNeeded() } returns Result.failure(cause)

            val coordinator = AppMigrationCoordinator(migrator, makeDispatchers(testDispatcher))
            coordinator.runMigration()
            advanceUntilIdle()

            val state = coordinator.state.value
            state.shouldBeInstanceOf<MigrationState.Failed>()
            (state as MigrationState.Failed).cause shouldBe cause
        }
    }

    test("migrator exception (not Result.failure) transitions state to Failed") {
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val migrator = mockk<LegacyKeyMigrator>()
            val cause = IllegalStateException("DataStore I/O error")
            coEvery { migrator.runIfNeeded() } throws cause

            val coordinator = AppMigrationCoordinator(migrator, makeDispatchers(testDispatcher))
            coordinator.runMigration()
            advanceUntilIdle()

            val state = coordinator.state.value
            state.shouldBeInstanceOf<MigrationState.Failed>()
            (state as MigrationState.Failed).cause shouldBe cause
        }
    }

    test("SkippedAlreadyDone outcome transitions state to Completed") {
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val migrator = mockk<LegacyKeyMigrator>()
            coEvery { migrator.runIfNeeded() } returns Result.success(MigrationOutcome.SkippedAlreadyDone)

            val coordinator = AppMigrationCoordinator(migrator, makeDispatchers(testDispatcher))
            coordinator.runMigration()
            advanceUntilIdle()

            val state = coordinator.state.value
            state.shouldBeInstanceOf<MigrationState.Completed>()
            (state as MigrationState.Completed).outcome shouldBe MigrationOutcome.SkippedAlreadyDone
        }
    }

    test("SkippedNoLegacyKey outcome transitions state to Completed") {
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val migrator = mockk<LegacyKeyMigrator>()
            coEvery { migrator.runIfNeeded() } returns Result.success(MigrationOutcome.SkippedNoLegacyKey)

            val coordinator = AppMigrationCoordinator(migrator, makeDispatchers(testDispatcher))
            coordinator.runMigration()
            advanceUntilIdle()

            val state = coordinator.state.value
            state.shouldBeInstanceOf<MigrationState.Completed>()
            (state as MigrationState.Completed).outcome shouldBe MigrationOutcome.SkippedNoLegacyKey
        }
    }
})
