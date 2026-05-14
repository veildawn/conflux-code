package com.claudemobile.core.bridge.cli

import app.cash.turbine.test
import com.claudemobile.core.bridge.providers.SpawnEnvAdapter
import com.claudemobile.core.common.CoroutineDispatchers
import com.claudemobile.core.common.TimeProvider
import com.claudemobile.core.domain.bridge.BridgeError
import com.claudemobile.core.domain.bridge.BridgeEvent
import com.claudemobile.core.domain.bridge.ExitCause
import com.claudemobile.core.domain.bridge.PosixSignal
import com.claudemobile.core.domain.bridge.ProcessState
import com.claudemobile.core.domain.bridge.SpawnConfig
import com.claudemobile.core.domain.repository.DiagnosticsEntry
import com.claudemobile.core.domain.repository.DiagnosticsRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class CliBridgeImplTest {

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

    @Test
    fun `initial state is IDLE`() {
        bridge.processState.value shouldBe ProcessState.IDLE
    }

    @Test
    fun `spawn transitions state to RUNNING on success`() = runTest(testDispatcher) {
        processExecutor.nextProcess = createFakeProcess(alive = true)

        val result = bridge.spawn(defaultConfig)
        advanceUntilIdle()

        result.isSuccess shouldBe true
        bridge.processState.value shouldBe ProcessState.RUNNING
    }

    @Test
    fun `spawn records PID and start timestamp`() = runTest(testDispatcher) {
        val fakeProcess = createFakeProcess(alive = true, pid = 12345L)
        processExecutor.nextProcess = fakeProcess

        bridge.spawn(defaultConfig)
        advanceUntilIdle()

        bridge.lastPid shouldBe 12345L
        bridge.lastStartTimestamp shouldBe fixedTime
    }

    @Test
    fun `spawn returns ProcessHandle with correct PID`() = runTest(testDispatcher) {
        val fakeProcess = createFakeProcess(alive = true, pid = 42L)
        processExecutor.nextProcess = fakeProcess

        val result = bridge.spawn(defaultConfig)
        advanceUntilIdle()

        result.isSuccess shouldBe true
        result.getOrNull()!!.pid shouldBe 42L
    }

    @Test
    fun `spawn failure transitions state to TERMINATED`() = runTest(testDispatcher) {
        processExecutor.shouldThrow = ProcessSpawnException(
            message = "Failed",
            commandLine = "/usr/bin/claude --chat",
        )

        val result = bridge.spawn(defaultConfig)
        advanceUntilIdle()

        result.isFailure shouldBe true
        bridge.processState.value shouldBe ProcessState.TERMINATED
    }

    @Test
    fun `spawn failure emits error event`() = runTest(testDispatcher) {
        processExecutor.shouldThrow = ProcessSpawnException(
            message = "Command not found",
            commandLine = "/usr/bin/claude --chat",
        )

        bridge.outputFlow.test {
            bridge.spawn(defaultConfig)
            advanceUntilIdle()

            val event = awaitItem()
            event.shouldBeInstanceOf<BridgeEvent.Error>()
            (event as BridgeEvent.Error).message.contains("Failed to spawn process") shouldBe true
        }
    }

    @Test
    fun `write forwards bytes to process stdin`() = runTest(testDispatcher) {
        val outputStream = ByteArrayOutputStream()
        val fakeProcess = createFakeProcess(alive = true, outputStream = outputStream)
        processExecutor.nextProcess = fakeProcess

        bridge.spawn(defaultConfig)
        advanceUntilIdle()

        val input = "hello\n".toByteArray()
        bridge.write(input)
        advanceUntilIdle()

        outputStream.toByteArray() shouldBe input
    }

    @Test
    fun `write does nothing when no process is active`() = runTest(testDispatcher) {
        // Should not throw
        bridge.write("hello".toByteArray())
        advanceUntilIdle()
    }

    @Test
    fun `sendSignal delegates to process executor`() = runTest(testDispatcher) {
        val fakeProcess = createFakeProcess(alive = true, pid = 100L)
        processExecutor.nextProcess = fakeProcess

        bridge.spawn(defaultConfig)
        advanceUntilIdle()

        bridge.sendSignal(PosixSignal.SIGINT)
        advanceUntilIdle()

        processExecutor.signalsSent shouldBe listOf(100L to PosixSignal.SIGINT)
    }

    @Test
    fun `sendSignal does nothing when no process is active`() = runTest(testDispatcher) {
        bridge.sendSignal(PosixSignal.SIGTERM)
        advanceUntilIdle()

        processExecutor.signalsSent shouldBe emptyList()
    }

    @Test
    fun `terminate sends SIGINT first`() = runTest(testDispatcher) {
        val fakeProcess = createFakeProcess(alive = true, pid = 200L)
        // Process dies after SIGINT
        fakeProcess.dieAfterSignal = PosixSignal.SIGINT
        processExecutor.nextProcess = fakeProcess

        bridge.spawn(defaultConfig)
        advanceUntilIdle()

        bridge.terminate()
        advanceUntilIdle()

        processExecutor.signalsSent.first() shouldBe (200L to PosixSignal.SIGINT)
        bridge.processState.value shouldBe ProcessState.TERMINATED
    }

    @Test
    fun `terminate escalates to SIGTERM if SIGINT does not kill process`() = runTest(testDispatcher) {
        val fakeProcess = createFakeProcess(alive = true, pid = 300L)
        // Process dies after SIGTERM
        fakeProcess.dieAfterSignal = PosixSignal.SIGTERM
        processExecutor.nextProcess = fakeProcess

        bridge.spawn(defaultConfig)
        advanceUntilIdle()

        bridge.terminate()
        advanceUntilIdle()

        val signals = processExecutor.signalsSent.map { it.second }
        signals.contains(PosixSignal.SIGINT) shouldBe true
        signals.contains(PosixSignal.SIGTERM) shouldBe true
    }

    @Test
    fun `terminate escalates to SIGKILL if SIGTERM does not kill process`() = runTest(testDispatcher) {
        val fakeProcess = createFakeProcess(alive = true, pid = 400L)
        // Process never dies from signals, only from destroyForcibly
        fakeProcess.dieAfterSignal = PosixSignal.SIGKILL
        processExecutor.nextProcess = fakeProcess

        bridge.spawn(defaultConfig)
        advanceUntilIdle()

        bridge.terminate()
        advanceUntilIdle()

        val signals = processExecutor.signalsSent.map { it.second }
        signals.contains(PosixSignal.SIGINT) shouldBe true
        signals.contains(PosixSignal.SIGTERM) shouldBe true
        signals.contains(PosixSignal.SIGKILL) shouldBe true
        bridge.processState.value shouldBe ProcessState.TERMINATED
    }

    @Test
    fun `spawn terminates existing process before starting new one`() = runTest(testDispatcher) {
        val firstProcess = createFakeProcess(alive = true, pid = 500L)
        firstProcess.dieAfterSignal = PosixSignal.SIGINT
        processExecutor.nextProcess = firstProcess

        bridge.spawn(defaultConfig)
        advanceUntilIdle()

        val secondProcess = createFakeProcess(alive = true, pid = 600L)
        processExecutor.nextProcess = secondProcess

        val result = bridge.spawn(defaultConfig)
        advanceUntilIdle()

        result.isSuccess shouldBe true
        result.getOrNull()!!.pid shouldBe 600L
        bridge.lastPid shouldBe 600L
    }

    @Test
    fun `output from process is emitted as Output events`() = runTest(testDispatcher) {
        val outputData = "Hello from Claude\n".toByteArray()
        val fakeProcess = createFakeProcess(
            alive = false, // Process exits immediately after output
            inputStream = ByteArrayInputStream(outputData),
            pid = 700L,
        )
        processExecutor.nextProcess = fakeProcess

        bridge.outputFlow.test {
            bridge.spawn(defaultConfig)
            advanceUntilIdle()
            // Allow the IO thread running monitorProcessExit to complete
            withContext(kotlinx.coroutines.Dispatchers.Default) { delay(50) }
            advanceUntilIdle()

            // Collect all events - order may vary due to IO thread timing
            val events = cancelAndConsumeRemainingEvents()
                .filterIsInstance<app.cash.turbine.Event.Item<BridgeEvent>>()
                .map { it.value }

            // Verify output was emitted
            val outputEvent = events.filterIsInstance<BridgeEvent.Output>().firstOrNull()
            outputEvent?.bytes shouldBe outputData

            // Verify process exit was emitted
            val exitEvent = events.filterIsInstance<BridgeEvent.ProcessExited>().firstOrNull()
            exitEvent.shouldBeInstanceOf<BridgeEvent.ProcessExited>()
        }
    }

    @Test
    fun `process exit with code 0 classified as NORMAL`() = runTest(testDispatcher) {
        val fakeProcess = createFakeProcess(alive = false, pid = 800L, exitCode = 0)
        processExecutor.nextProcess = fakeProcess

        bridge.outputFlow.test {
            bridge.spawn(defaultConfig)
            advanceUntilIdle()
            // Allow the real IO thread (Dispatchers.IO) to complete waitFor()
            // and dispatch the continuation back to the test dispatcher
            Thread.sleep(50)
            advanceUntilIdle()

            // Skip output events if any
            val events = cancelAndConsumeRemainingEvents()
            val exitEvent = events.filterIsInstance<app.cash.turbine.Event.Item<BridgeEvent>>()
                .map { it.value }
                .filterIsInstance<BridgeEvent.ProcessExited>()
                .firstOrNull()

            exitEvent?.cause shouldBe ExitCause.NORMAL
        }
    }

    // -----------------------------------------------------------------------
    // SpawnEnvAdapter wiring (ai-provider-presets R6.1, R6.7, R6.8, R11.2)
    // -----------------------------------------------------------------------

    @Test
    fun `spawn calls SpawnEnvAdapter with caller envVars and uses its resolved env`() =
        runTest(testDispatcher) {
            val fakeProcess = createFakeProcess(alive = true, pid = 900L)
            processExecutor.nextProcess = fakeProcess

            spawnEnvAdapter.nextResult = Result.success(
                mapOf(
                    "HOME" to "/home/user",
                    "PATH" to "/usr/bin",
                    "TERM" to "xterm-256color",
                    "LANG" to "en_US.UTF-8",
                    "ANTHROPIC_BASE_URL" to "https://open.bigmodel.cn/api/anthropic",
                    "ANTHROPIC_AUTH_TOKEN" to "sk-active-profile-token",
                    "ANTHROPIC_MODEL" to "glm-4.6",
                ),
            )

            val configFromCaller = SpawnConfig(
                workingDir = "/workspace",
                envVars = mapOf(
                    "HOME" to "/home/user",
                    "PATH" to "/usr/bin",
                    "TERM" to "xterm-256color",
                    "LANG" to "en_US.UTF-8",
                ),
                command = "/usr/bin/claude",
                args = listOf("--chat"),
            )

            val result = bridge.spawn(configFromCaller)
            advanceUntilIdle()

            result.isSuccess shouldBe true

            // The adapter is consulted exactly once per spawn (Property 9).
            spawnEnvAdapter.invocations.size shouldBe 1
            spawnEnvAdapter.invocations.first() shouldBe configFromCaller.envVars

            // The ProcessExecutor receives the config produced by the adapter,
            // not the caller's raw envVars. The Anthropic-compatible variables
            // are merged in.
            val executedConfig = processExecutor.executedConfigs.last()
            executedConfig.envVars["ANTHROPIC_BASE_URL"] shouldBe
                "https://open.bigmodel.cn/api/anthropic"
            executedConfig.envVars["ANTHROPIC_AUTH_TOKEN"] shouldBe "sk-active-profile-token"
            executedConfig.envVars["ANTHROPIC_MODEL"] shouldBe "glm-4.6"
            executedConfig.envVars.containsKey("ANTHROPIC_API_KEY") shouldBe false
        }

    @Test
    fun `spawn aborts with NoActiveProfile when SpawnEnvAdapter has no active profile`() =
        runTest(testDispatcher) {
            spawnEnvAdapter.nextResult = Result.failure(BridgeError.NoActiveProfile)

            bridge.outputFlow.test {
                val result = bridge.spawn(defaultConfig)
                advanceUntilIdle()

                // Result.failure carries the BridgeError.NoActiveProfile.
                result.isFailure shouldBe true
                result.exceptionOrNull().shouldBeInstanceOf<BridgeError.NoActiveProfile>()

                // BridgeEvent.Error is emitted on the output flow so observers
                // can route to provider selection.
                val event = awaitItem()
                event.shouldBeInstanceOf<BridgeEvent.Error>()
                (event as BridgeEvent.Error).throwable
                    .shouldBeInstanceOf<BridgeError.NoActiveProfile>()

                cancelAndIgnoreRemainingEvents()
            }

            // ProcessExecutor must NOT be called when there is no active profile.
            processExecutor.executedConfigs.isEmpty() shouldBe true
            // State is unchanged (still IDLE — no spawn was attempted).
            bridge.processState.value shouldBe ProcessState.IDLE
            // No PID / timestamp recorded.
            bridge.lastPid shouldBe null
            bridge.lastStartTimestamp shouldBe null
        }

    @Test
    fun `NoActiveProfile spawn does not terminate an existing running process`() =
        runTest(testDispatcher) {
            // First, spawn a normal process while the adapter is happy.
            val firstProcess = createFakeProcess(alive = true, pid = 901L)
            processExecutor.nextProcess = firstProcess
            bridge.spawn(defaultConfig)
            advanceUntilIdle()
            bridge.processState.value shouldBe ProcessState.RUNNING
            bridge.lastPid shouldBe 901L

            // Now the user clears the active profile and tries to spawn again.
            spawnEnvAdapter.nextResult = Result.failure(BridgeError.NoActiveProfile)

            val result = bridge.spawn(defaultConfig)
            advanceUntilIdle()

            result.isFailure shouldBe true
            result.exceptionOrNull().shouldBeInstanceOf<BridgeError.NoActiveProfile>()

            // The first process is still healthy; we did not tear it down.
            bridge.processState.value shouldBe ProcessState.RUNNING
            bridge.lastPid shouldBe 901L
            // No signals were sent to the running process.
            processExecutor.signalsSent.any { it.first == 901L } shouldBe false
            // ProcessExecutor.execute was called exactly once (the original spawn).
            processExecutor.executedConfigs.size shouldBe 1
        }

    @Test
    fun `each spawn re-reads SpawnEnvAdapter (no caching across spawns)`() =
        runTest(testDispatcher) {
            // Spawn 1: profile A.
            val firstProcess = createFakeProcess(alive = true, pid = 910L)
            firstProcess.dieAfterSignal = PosixSignal.SIGINT
            processExecutor.nextProcess = firstProcess
            spawnEnvAdapter.nextResult = Result.success(
                mapOf(
                    "ANTHROPIC_BASE_URL" to "https://first.example/api",
                    "ANTHROPIC_AUTH_TOKEN" to "first-key",
                    "ANTHROPIC_MODEL" to "model-a",
                ),
            )
            bridge.spawn(defaultConfig)
            advanceUntilIdle()

            // Spawn 2: profile B. The adapter must be consulted again.
            val secondProcess = createFakeProcess(alive = true, pid = 911L)
            processExecutor.nextProcess = secondProcess
            spawnEnvAdapter.nextResult = Result.success(
                mapOf(
                    "ANTHROPIC_BASE_URL" to "https://second.example/api",
                    "ANTHROPIC_API_KEY" to "second-key",
                    "ANTHROPIC_MODEL" to "model-b",
                ),
            )
            bridge.spawn(defaultConfig)
            advanceUntilIdle()

            // Adapter consulted once per spawn — Property 9.
            spawnEnvAdapter.invocations.size shouldBe 2

            // The second ProcessExecutor invocation reflects the *new* env, not
            // a cached snapshot from the first spawn.
            val secondExecuted = processExecutor.executedConfigs.last()
            secondExecuted.envVars["ANTHROPIC_BASE_URL"] shouldBe "https://second.example/api"
            secondExecuted.envVars["ANTHROPIC_API_KEY"] shouldBe "second-key"
            secondExecuted.envVars.containsKey("ANTHROPIC_AUTH_TOKEN") shouldBe false
        }

    @Test
    fun `bridge_lifecycle log details never contain apiKey or baseUrl values`() =
        runTest(testDispatcher) {
            val apiKey = "sk-very-secret-do-not-leak-12345"
            val baseUrl = "https://leakable.example.com/api/anthropic"
            val fakeProcess = createFakeProcess(alive = true, pid = 920L)
            processExecutor.nextProcess = fakeProcess
            spawnEnvAdapter.nextResult = Result.success(
                mapOf(
                    "HOME" to "/home/user",
                    "ANTHROPIC_BASE_URL" to baseUrl,
                    "ANTHROPIC_AUTH_TOKEN" to apiKey,
                    "ANTHROPIC_MODEL" to "glm-4.6",
                ),
            )

            bridge.spawn(defaultConfig)
            advanceUntilIdle()

            val lifecycleEvents = diagnosticsRepository.loggedEvents
                .filter { it.eventType == "bridge_lifecycle" }
            lifecycleEvents.isEmpty() shouldBe false
            lifecycleEvents.forEach { entry ->
                entry.message.contains(apiKey) shouldBe false
                entry.message.contains(baseUrl) shouldBe false
                (entry.details ?: "").contains(apiKey) shouldBe false
                (entry.details ?: "").contains(baseUrl) shouldBe false
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

/**
 * Fake process executor for testing.
 */
class FakeProcessExecutor : ProcessExecutor {
    var nextProcess: FakeProcess? = null
    var shouldThrow: Exception? = null
    val signalsSent = mutableListOf<Pair<Long, PosixSignal>>()
    val executedConfigs = mutableListOf<SpawnConfig>()
    private val spawnedProcesses = mutableMapOf<Long, FakeProcess>()

    override fun execute(config: SpawnConfig): Process {
        shouldThrow?.let { throw it }
        val process = nextProcess ?: throw IllegalStateException("No fake process configured")
        executedConfigs.add(config)
        spawnedProcesses[process.pid()] = process
        return process
    }

    override fun sendSignal(pid: Long, signal: PosixSignal) {
        signalsSent.add(pid to signal)
        // Route signal to the correct process by PID
        spawnedProcesses[pid]?.onSignal(signal)
    }
}

/**
 * Fake process for testing that simulates process lifecycle.
 */
class FakeProcess(
    private val isAliveInitially: Boolean,
    private val fakePid: Long,
    private val fakeExitCode: Int = 0,
    private val fakeInputStream: InputStream = ByteArrayInputStream(ByteArray(0)),
    private val fakeOutputStream: OutputStream = ByteArrayOutputStream(),
) : Process() {

    var dieAfterSignal: PosixSignal? = null
    @Volatile
    private var alive = isAliveInitially

    fun onSignal(signal: PosixSignal) {
        if (signal == dieAfterSignal) {
            alive = false
        }
    }

    override fun getOutputStream(): OutputStream = fakeOutputStream
    override fun getInputStream(): InputStream = fakeInputStream
    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int {
        while (alive) {
            Thread.sleep(10)
        }
        return fakeExitCode
    }

    override fun exitValue(): Int {
        if (alive) throw IllegalThreadStateException("Process has not exited")
        return fakeExitCode
    }

    override fun destroy() {
        alive = false
    }

    override fun destroyForcibly(): Process {
        alive = false
        return this
    }

    override fun isAlive(): Boolean = alive

    fun pid(): Long = fakePid
}

/**
 * Fake diagnostics repository for testing.
 */
class FakeDiagnosticsRepository : DiagnosticsRepository {
    val loggedEvents = mutableListOf<LoggedEvent>()

    data class LoggedEvent(
        val sessionId: String?,
        val eventType: String,
        val message: String,
        val details: String?,
    )

    override suspend fun logEvent(
        sessionId: String?,
        eventType: String,
        message: String,
        details: String?,
    ) {
        loggedEvents.add(LoggedEvent(sessionId, eventType, message, details))
    }

    override fun getSessionLogs(sessionId: String): Flow<List<DiagnosticsEntry>> =
        flowOf(emptyList())

    override suspend fun getRecentLogs(limit: Int): List<DiagnosticsEntry> = emptyList()

    override suspend fun exportRedacted(sessionId: String): String = ""

    override suspend fun clearOldEntries() {}
}

/**
 * Test [SpawnEnvAdapter] that returns the caller's `baseEnv` unchanged by
 * default and can be configured per-test to return a fresh env or to fail
 * (e.g. with [BridgeError.NoActiveProfile]).
 *
 * Tracks every invocation so tests can assert that the adapter is consulted
 * exactly once per spawn (Property 9: each spawn re-reads the Active_Profile,
 * no caching across spawns).
 */
class PassthroughSpawnEnvAdapter : SpawnEnvAdapter {
    /**
     * If non-null, [prepareEnv] returns this result regardless of [baseEnv].
     * If null, [prepareEnv] succeeds with [baseEnv] verbatim.
     */
    var nextResult: Result<Map<String, String>>? = null
    val invocations = mutableListOf<Map<String, String>>()

    override suspend fun prepareEnv(
        baseEnv: Map<String, String>,
    ): Result<Map<String, String>> {
        invocations.add(baseEnv)
        return nextResult ?: Result.success(baseEnv)
    }
}
