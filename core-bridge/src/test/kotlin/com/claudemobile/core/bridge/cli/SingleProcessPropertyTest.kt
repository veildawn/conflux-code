package com.claudemobile.core.bridge.cli

import com.claudemobile.core.common.CoroutineDispatchers
import com.claudemobile.core.common.TimeProvider
import com.claudemobile.core.domain.bridge.PosixSignal
import com.claudemobile.core.domain.bridge.ProcessState
import com.claudemobile.core.domain.bridge.SpawnConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.time.Instant

/**
 * Property 4: Single process invariant
 *
 * **Validates: Requirements 2.13**
 *
 * For any session and any spawn operation sequence, at any point in time the number
 * of active Claude CLI processes does not exceed 1. When a new spawn request arrives while
 * a process is already active, the old process must be terminated before the new one starts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SingleProcessPropertyTest : FunSpec({

    test("Feature: android-claude-termux-client, Property 4: Single process invariant") {
        /**
         * Validates: Requirements 2.13
         *
         * Generate random sequences of 2-5 spawn operations and verify that after each spawn,
         * only one process is tracked (lastPid changes, processState is RUNNING).
         */
        checkAll(100, Arb.int(2..5)) { spawnCount ->
            val testDispatcher = StandardTestDispatcher()
            val testDispatchers = object : CoroutineDispatchers {
                override val default: CoroutineDispatcher = testDispatcher
                override val io: CoroutineDispatcher = testDispatcher
                override val main: CoroutineDispatcher = testDispatcher
                override val mainImmediate: CoroutineDispatcher = testDispatcher
                override val unconfined: CoroutineDispatcher = testDispatcher
            }

            val timeProvider = object : TimeProvider {
                override fun now(): Instant = Instant.now()
            }

            val processExecutor = FakeProcessExecutor()
            val diagnosticsRepository = FakeDiagnosticsRepository()
            val spawnEnvAdapter = PassthroughSpawnEnvAdapter()

            val bridge = CliBridgeImpl(
                testDispatchers,
                timeProvider,
                processExecutor,
                diagnosticsRepository,
                spawnEnvAdapter,
            )

            val config = SpawnConfig(
                workingDir = "/workspace",
                envVars = mapOf("ANTHROPIC_API_KEY" to "sk-test"),
                command = "/usr/bin/claude",
                args = listOf("--chat"),
            )

            runTest(testDispatcher) {
                var previousPid: Long? = null

                for (i in 1..spawnCount) {
                    val pid = i.toLong() * 100
                    val fakeProcess = FakeProcess(
                        isAliveInitially = true,
                        fakePid = pid,
                        fakeExitCode = 0,
                    )
                    // Each process dies on SIGINT so termination during next spawn succeeds
                    fakeProcess.dieAfterSignal = PosixSignal.SIGINT
                    processExecutor.nextProcess = fakeProcess

                    val result = bridge.spawn(config)
                    advanceUntilIdle()

                    // After each spawn, verify the single process invariant:
                    // 1. Spawn should succeed
                    result.isSuccess shouldBe true

                    // 2. processState should be RUNNING (only one active process)
                    bridge.processState.value shouldBe ProcessState.RUNNING

                    // 3. lastPid should be the newly spawned process
                    bridge.lastPid shouldBe pid

                    // 4. lastPid should differ from the previous one (process changed)
                    if (previousPid != null) {
                        bridge.lastPid shouldNotBe previousPid
                    }

                    // 5. The previous process should have been terminated (received signals)
                    if (previousPid != null) {
                        val signalsToOldProcess = processExecutor.signalsSent
                            .filter { it.first == previousPid }
                        // Old process must have received at least SIGINT
                        signalsToOldProcess.any { it.second == PosixSignal.SIGINT } shouldBe true
                    }

                    previousPid = pid
                }
            }
        }
    }
})
