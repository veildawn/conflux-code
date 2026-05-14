package com.claudemobile.core.bridge.cli

import app.cash.turbine.test
import com.claudemobile.core.common.CoroutineDispatchers
import com.claudemobile.core.common.TimeProvider
import com.claudemobile.core.domain.bridge.BridgeEvent
import com.claudemobile.core.domain.bridge.ExitCause
import com.claudemobile.core.domain.bridge.PosixSignal
import com.claudemobile.core.domain.bridge.ProcessState
import com.claudemobile.core.domain.bridge.SpawnConfig
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant

/**
 * Tests for Bridge signal escalation timing, process state transitions,
 * spawn failure reporting, and single-process-per-session enforcement.
 *
 * Validates: Requirements 2.8, 2.9, 2.10, 2.11
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BridgeLifecycleTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testDispatchers = object : CoroutineDispatchers {
        override val default: CoroutineDispatcher = testDispatcher
        override val io: CoroutineDispatcher = testDispatcher
        override val main: CoroutineDispatcher = testDispatcher
        override val mainImmediate: CoroutineDispatcher = testDispatcher
        override val unconfined: CoroutineDispatcher = testDispatcher
    }

    private val fixedTime = Instant.parse("2024-01-15T10:30:00Z")
    private val timeProvider = object : TimeProvider {
        override fun now(): Instant = fixedTime
    }

    private lateinit var processExecutor: FakeProcessExecutor
    private lateinit var diagnosticsRepository: FakeDiagnosticsRepository
    private lateinit var spawnEnvAdapter: PassthroughSpawnEnvAdapter
    private lateinit var bridge: CliBridgeImpl

    private val defaultConfig = SpawnConfig(
        workingDir = "/workspace",
        envVars = mapOf("ANTHROPIC_API_KEY" to "sk-test-key"),
        command = "/usr/bin/claude",
        args = listOf("--chat"),
    )

    @BeforeEach
    fun setup() {
        processExecutor = FakeProcessExecutor()
        diagnosticsRepository = FakeDiagnosticsRepository()
        spawnEnvAdapter = PassthroughSpawnEnvAdapter()
        bridge = CliBridgeImpl(
            testDispatchers,
            timeProvider,
            processExecutor,
            diagnosticsRepository,
            spawnEnvAdapter,
        )
    }

    @Nested
    @DisplayName("Signal Escalation Timing (Req 2.8, 2.9)")
    inner class SignalEscalationTiming {

        @Test
        fun `SIGINT is always the first signal sent during termination`() = runTest(testDispatcher) {
            val fakeProcess = createFakeProcess(alive = true, pid = 100L)
            fakeProcess.dieAfterSignal = PosixSignal.SIGKILL
            processExecutor.nextProcess = fakeProcess

            bridge.spawn(defaultConfig)
            advanceUntilIdle()

            bridge.terminate()
            advanceUntilIdle()

            processExecutor.signalsSent.first().second shouldBe PosixSignal.SIGINT
        }

        @Test
        fun `SIGTERM is sent only after SIGINT when process does not exit`() = runTest(testDispatcher) {
            val fakeProcess = createFakeProcess(alive = true, pid = 101L)
            fakeProcess.dieAfterSignal = PosixSignal.SIGTERM
            processExecutor.nextProcess = fakeProcess

            bridge.spawn(defaultConfig)
            advanceUntilIdle()

            bridge.terminate()
            advanceUntilIdle()

            val signals = processExecutor.signalsSent.map { it.second }
            // SIGINT must come before SIGTERM
            val sigintIndex = signals.indexOf(PosixSignal.SIGINT)
            val sigtermIndex = signals.indexOf(PosixSignal.SIGTERM)
            (sigintIndex < sigtermIndex) shouldBe true
        }

        @Test
        fun `SIGKILL is sent only after both SIGINT and SIGTERM fail`() = runTest(testDispatcher) {
            val fakeProcess = createFakeProcess(alive = true, pid = 102L)
            fakeProcess.dieAfterSignal = PosixSignal.SIGKILL
            processExecutor.nextProcess = fakeProcess

            bridge.spawn(defaultConfig)
            advanceUntilIdle()

            bridge.terminate()
            advanceUntilIdle()

            val signals = processExecutor.signalsSent.map { it.second }
            val sigintIndex = signals.indexOf(PosixSignal.SIGINT)
            val sigtermIndex = signals.indexOf(PosixSignal.SIGTERM)
            val sigkillIndex = signals.indexOf(PosixSignal.SIGKILL)

            // All three signals must be present in order
            (sigintIndex >= 0) shouldBe true
            (sigtermIndex >= 0) shouldBe true
            (sigkillIndex >= 0) shouldBe true
            (sigintIndex < sigtermIndex) shouldBe true
            (sigtermIndex < sigkillIndex) shouldBe true
        }

        @Test
        fun `process that exits after SIGINT does not receive SIGTERM or SIGKILL`() = runTest(testDispatcher) {
            val fakeProcess = createFakeProcess(alive = true, pid = 103L)
            fakeProcess.dieAfterSignal = PosixSignal.SIGINT
            processExecutor.nextProcess = fakeProcess

            bridge.spawn(defaultConfig)
            advanceUntilIdle()

            bridge.terminate()
            advanceUntilIdle()

            val signals = processExecutor.signalsSent.map { it.second }
            signals.contains(PosixSignal.SIGINT) shouldBe true
            signals.contains(PosixSignal.SIGTERM) shouldBe false
            signals.contains(PosixSignal.SIGKILL) shouldBe false
        }

        @Test
        fun `process that exits after SIGTERM does not receive SIGKILL`() = runTest(testDispatcher) {
            val fakeProcess = createFakeProcess(alive = true, pid = 104L)
            fakeProcess.dieAfterSignal = PosixSignal.SIGTERM
            processExecutor.nextProcess = fakeProcess

            bridge.spawn(defaultConfig)
            advanceUntilIdle()

            bridge.terminate()
            advanceUntilIdle()

            val signals = processExecutor.signalsSent.map { it.second }
            signals.contains(PosixSignal.SIGINT) shouldBe true
            signals.contains(PosixSignal.SIGTERM) shouldBe true
            signals.contains(PosixSignal.SIGKILL) shouldBe false
        }

        @Test
        fun `terminate emits ProcessExited with USER_CANCELLED cause`() = runTest(testDispatcher) {
            val fakeProcess = createFakeProcess(alive = true, pid = 105L)
            fakeProcess.dieAfterSignal = PosixSignal.SIGINT
            processExecutor.nextProcess = fakeProcess

            bridge.outputFlow.test {
                bridge.spawn(defaultConfig)
                advanceUntilIdle()

                bridge.terminate()
                advanceUntilIdle()

                val events = cancelAndConsumeRemainingEvents()
                val exitEvent = events
                    .filterIsInstance<app.cash.turbine.Event.Item<BridgeEvent>>()
                    .map { it.value }
                    .filterIsInstance<BridgeEvent.ProcessExited>()
                    .firstOrNull()

                exitEvent?.cause shouldBe ExitCause.USER_CANCELLED
            }
        }
    }

    @Nested
    @DisplayName("Process State Transitions (Req 2.8, 2.9)")
    inner class ProcessStateTransitions {

        @Test
        fun `initial state is IDLE`() {
            bridge.processState.value shouldBe ProcessState.IDLE
        }

        @Test
        fun `spawn transitions through STARTING to RUNNING`() = runTest(testDispatcher) {
            val fakeProcess = createFakeProcess(alive = true, pid = 200L)
            processExecutor.nextProcess = fakeProcess

            bridge.processState.test {
                awaitItem() shouldBe ProcessState.IDLE

                bridge.spawn(defaultConfig)
                awaitItem() shouldBe ProcessState.STARTING
                advanceUntilIdle()
                awaitItem() shouldBe ProcessState.RUNNING

                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun `terminate transitions through STOPPING to TERMINATED`() = runTest(testDispatcher) {
            val fakeProcess = createFakeProcess(alive = true, pid = 201L)
            fakeProcess.dieAfterSignal = PosixSignal.SIGINT
            processExecutor.nextProcess = fakeProcess

            bridge.spawn(defaultConfig)
            advanceUntilIdle()
            bridge.processState.value shouldBe ProcessState.RUNNING

            bridge.processState.test {
                // Current state
                awaitItem() shouldBe ProcessState.RUNNING

                bridge.terminate()
                awaitItem() shouldBe ProcessState.STOPPING
                advanceUntilIdle()
                awaitItem() shouldBe ProcessState.TERMINATED

                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun `spawn failure transitions from STARTING to TERMINATED`() = runTest(testDispatcher) {
            processExecutor.shouldThrow = ProcessSpawnException(
                message = "Process not found",
                commandLine = "/usr/bin/claude --chat",
            )

            bridge.processState.test {
                awaitItem() shouldBe ProcessState.IDLE

                bridge.spawn(defaultConfig)
                awaitItem() shouldBe ProcessState.STARTING
                advanceUntilIdle()
                awaitItem() shouldBe ProcessState.TERMINATED

                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun `full lifecycle IDLE to STARTING to RUNNING to STOPPING to TERMINATED`() = runTest(testDispatcher) {
            val fakeProcess = createFakeProcess(alive = true, pid = 202L)
            fakeProcess.dieAfterSignal = PosixSignal.SIGINT
            processExecutor.nextProcess = fakeProcess

            // Start at IDLE
            bridge.processState.value shouldBe ProcessState.IDLE

            bridge.processState.test {
                awaitItem() shouldBe ProcessState.IDLE

                // Spawn → STARTING → RUNNING
                bridge.spawn(defaultConfig)
                awaitItem() shouldBe ProcessState.STARTING
                advanceUntilIdle()
                awaitItem() shouldBe ProcessState.RUNNING

                // Terminate → STOPPING → TERMINATED
                bridge.terminate()
                awaitItem() shouldBe ProcessState.STOPPING
                advanceUntilIdle()
                awaitItem() shouldBe ProcessState.TERMINATED

                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun `state is TERMINATED after process exits naturally`() = runTest(testDispatcher) {
            val fakeProcess = createFakeProcess(alive = false, pid = 203L, exitCode = 0)
            processExecutor.nextProcess = fakeProcess

            bridge.spawn(defaultConfig)
            advanceUntilIdle()
            // Allow the real IO thread (Dispatchers.IO) to complete waitFor()
            // and dispatch the continuation back to the test dispatcher
            Thread.sleep(50)
            advanceUntilIdle()

            bridge.processState.value shouldBe ProcessState.TERMINATED
        }
    }

    @Nested
    @DisplayName("Spawn Failure Reporting (Req 2.4)")
    inner class SpawnFailureReporting {

        @Test
        fun `spawn failure error contains command line`() = runTest(testDispatcher) {
            processExecutor.shouldThrow = ProcessSpawnException(
                message = "No such file or directory",
                commandLine = "/usr/bin/claude --chat",
            )

            bridge.outputFlow.test {
                bridge.spawn(defaultConfig)
                advanceUntilIdle()

                val event = awaitItem()
                event.shouldBeInstanceOf<BridgeEvent.Error>()
                (event as BridgeEvent.Error).message shouldContain "/usr/bin/claude"
            }
        }

        @Test
        fun `spawn failure error contains stderr content from ProcessSpawnException`() = runTest(testDispatcher) {
            processExecutor.shouldThrow = ProcessSpawnException(
                message = "Permission denied: cannot execute binary",
                commandLine = "/usr/bin/claude --chat",
                exitCode = 126,
                stderr = "bash: /usr/bin/claude: Permission denied",
            )

            bridge.outputFlow.test {
                bridge.spawn(defaultConfig)
                advanceUntilIdle()

                val event = awaitItem()
                event.shouldBeInstanceOf<BridgeEvent.Error>()
                val errorMsg = (event as BridgeEvent.Error).message
                errorMsg shouldContain "Permission denied"
                errorMsg shouldContain "Exit code: 126"
            }
        }

        @Test
        fun `spawn failure returns Result failure`() = runTest(testDispatcher) {
            processExecutor.shouldThrow = ProcessSpawnException(
                message = "Failed to start",
                commandLine = "/usr/bin/claude --chat",
            )

            val result = bridge.spawn(defaultConfig)
            advanceUntilIdle()

            result.isFailure shouldBe true
            result.exceptionOrNull().shouldBeInstanceOf<ProcessSpawnException>()
        }

        @Test
        fun `spawn failure logs diagnostics event`() = runTest(testDispatcher) {
            processExecutor.shouldThrow = ProcessSpawnException(
                message = "Binary not found",
                commandLine = "/usr/bin/claude --chat",
            )

            bridge.spawn(defaultConfig)
            advanceUntilIdle()

            val lifecycleEvents = diagnosticsRepository.loggedEvents
                .filter { it.eventType == "bridge_lifecycle" }
            lifecycleEvents.any { it.message.contains("spawn failed") } shouldBe true
        }

        @Test
        fun `spawn failure with arguments includes full command in error`() = runTest(testDispatcher) {
            val configWithArgs = SpawnConfig(
                workingDir = "/workspace",
                envVars = mapOf("ANTHROPIC_API_KEY" to "sk-test"),
                command = "/usr/bin/claude",
                args = listOf("--chat", "--model", "claude-sonnet-4-20250514"),
            )

            processExecutor.shouldThrow = ProcessSpawnException(
                message = "Exec format error",
                commandLine = "/usr/bin/claude --chat --model claude-sonnet-4-20250514",
            )

            bridge.outputFlow.test {
                bridge.spawn(configWithArgs)
                advanceUntilIdle()

                val event = awaitItem()
                event.shouldBeInstanceOf<BridgeEvent.Error>()
                val errorMsg = (event as BridgeEvent.Error).message
                errorMsg shouldContain "/usr/bin/claude"
                errorMsg shouldContain "Exec format error"
            }
        }

        @Test
        fun `spawn failure includes exit code when available`() = runTest(testDispatcher) {
            processExecutor.shouldThrow = ProcessSpawnException(
                message = "Process exited with error",
                commandLine = "/usr/bin/claude --chat",
                exitCode = 127,
                stderr = "bash: claude: command not found",
            )

            bridge.outputFlow.test {
                bridge.spawn(defaultConfig)
                advanceUntilIdle()

                val event = awaitItem()
                event.shouldBeInstanceOf<BridgeEvent.Error>()
                val errorMsg = (event as BridgeEvent.Error).message
                errorMsg shouldContain "Exit code: 127"
                errorMsg shouldContain "command not found"
                errorMsg shouldContain "/usr/bin/claude"
            }
        }

        @Test
        fun `spawn failure stderr is limited to 4096 bytes`() = runTest(testDispatcher) {
            val longStderr = "x".repeat(8000)
            processExecutor.shouldThrow = ProcessSpawnException(
                message = "Process failed",
                commandLine = "/usr/bin/claude --chat",
                exitCode = 1,
                stderr = longStderr,
            )

            bridge.outputFlow.test {
                bridge.spawn(defaultConfig)
                advanceUntilIdle()

                val event = awaitItem()
                event.shouldBeInstanceOf<BridgeEvent.Error>()
                val errorMsg = (event as BridgeEvent.Error).message
                // The stderr portion should be limited to 4096 bytes
                errorMsg shouldContain "4096"
                // The full 8000 chars should NOT be in the message
                errorMsg.contains(longStderr) shouldBe false
            }
        }
    }

    @Nested
    @DisplayName("Single Process Per Session Enforcement (Req 2.10, 2.11)")
    inner class SingleProcessEnforcement {

        @Test
        fun `second spawn terminates first process before starting new one`() = runTest(testDispatcher) {
            val firstProcess = createFakeProcess(alive = true, pid = 300L)
            firstProcess.dieAfterSignal = PosixSignal.SIGINT
            processExecutor.nextProcess = firstProcess

            bridge.spawn(defaultConfig)
            advanceUntilIdle()
            bridge.processState.value shouldBe ProcessState.RUNNING

            val secondProcess = createFakeProcess(alive = true, pid = 301L)
            processExecutor.nextProcess = secondProcess

            val result = bridge.spawn(defaultConfig)
            advanceUntilIdle()

            result.isSuccess shouldBe true
            bridge.lastPid shouldBe 301L
            bridge.processState.value shouldBe ProcessState.RUNNING
        }

        @Test
        fun `first process receives termination signals when second spawn is requested`() = runTest(testDispatcher) {
            val firstProcess = createFakeProcess(alive = true, pid = 302L)
            firstProcess.dieAfterSignal = PosixSignal.SIGINT
            processExecutor.nextProcess = firstProcess

            bridge.spawn(defaultConfig)
            advanceUntilIdle()

            val secondProcess = createFakeProcess(alive = true, pid = 303L)
            processExecutor.nextProcess = secondProcess

            bridge.spawn(defaultConfig)
            advanceUntilIdle()

            // First process should have received SIGINT (at minimum)
            processExecutor.signalsSent.any { it.first == 302L && it.second == PosixSignal.SIGINT } shouldBe true
        }

        @Test
        fun `only one process PID is tracked after multiple spawns`() = runTest(testDispatcher) {
            val firstProcess = createFakeProcess(alive = true, pid = 304L)
            firstProcess.dieAfterSignal = PosixSignal.SIGINT
            processExecutor.nextProcess = firstProcess

            bridge.spawn(defaultConfig)
            advanceUntilIdle()
            bridge.lastPid shouldBe 304L

            val secondProcess = createFakeProcess(alive = true, pid = 305L)
            processExecutor.nextProcess = secondProcess

            bridge.spawn(defaultConfig)
            advanceUntilIdle()
            bridge.lastPid shouldBe 305L

            val thirdProcess = createFakeProcess(alive = true, pid = 306L)
            thirdProcess.dieAfterSignal = PosixSignal.SIGINT
            processExecutor.nextProcess = thirdProcess

            bridge.spawn(defaultConfig)
            advanceUntilIdle()
            bridge.lastPid shouldBe 306L
        }

        @Test
        fun `spawn after terminate succeeds with new process`() = runTest(testDispatcher) {
            val firstProcess = createFakeProcess(alive = true, pid = 307L)
            firstProcess.dieAfterSignal = PosixSignal.SIGINT
            processExecutor.nextProcess = firstProcess

            bridge.spawn(defaultConfig)
            advanceUntilIdle()

            bridge.terminate()
            advanceUntilIdle()
            bridge.processState.value shouldBe ProcessState.TERMINATED

            val secondProcess = createFakeProcess(alive = true, pid = 308L)
            processExecutor.nextProcess = secondProcess

            val result = bridge.spawn(defaultConfig)
            advanceUntilIdle()

            result.isSuccess shouldBe true
            bridge.lastPid shouldBe 308L
            bridge.processState.value shouldBe ProcessState.RUNNING
        }

        @Test
        fun `stubborn first process gets escalated signals during second spawn`() = runTest(testDispatcher) {
            val firstProcess = createFakeProcess(alive = true, pid = 309L)
            // First process only dies on SIGKILL
            firstProcess.dieAfterSignal = PosixSignal.SIGKILL
            processExecutor.nextProcess = firstProcess

            bridge.spawn(defaultConfig)
            advanceUntilIdle()

            val secondProcess = createFakeProcess(alive = true, pid = 310L)
            processExecutor.nextProcess = secondProcess

            bridge.spawn(defaultConfig)
            advanceUntilIdle()

            // First process should have received full escalation
            val firstProcessSignals = processExecutor.signalsSent
                .filter { it.first == 309L }
                .map { it.second }
            firstProcessSignals.contains(PosixSignal.SIGINT) shouldBe true
            firstProcessSignals.contains(PosixSignal.SIGTERM) shouldBe true
            firstProcessSignals.contains(PosixSignal.SIGKILL) shouldBe true

            // Second process should be running
            bridge.lastPid shouldBe 310L
            bridge.processState.value shouldBe ProcessState.RUNNING
        }
    }

    // --- Helper functions ---

    private fun createFakeProcess(
        alive: Boolean,
        pid: Long = 1L,
        exitCode: Int = 0,
        inputStream: InputStream = ByteArrayInputStream(ByteArray(0)),
        outputStream: OutputStream = ByteArrayOutputStream(),
    ): FakeProcess {
        return FakeProcess(
            isAliveInitially = alive,
            fakePid = pid,
            fakeExitCode = exitCode,
            fakeInputStream = inputStream,
            fakeOutputStream = outputStream,
        )
    }
}
