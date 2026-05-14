package com.claudemobile.core.bridge.cli

import com.claudemobile.core.bridge.providers.SpawnEnvAdapter
import com.claudemobile.core.bridge.providers.toSafeString
import com.claudemobile.core.common.CoroutineDispatchers
import com.claudemobile.core.common.TimeProvider
import com.claudemobile.core.domain.bridge.BridgeError
import com.claudemobile.core.domain.bridge.BridgeEvent
import com.claudemobile.core.domain.bridge.CliBridge
import com.claudemobile.core.domain.bridge.ExitCause
import com.claudemobile.core.domain.bridge.PosixSignal
import com.claudemobile.core.domain.bridge.ProcessHandle
import com.claudemobile.core.domain.bridge.ProcessState
import com.claudemobile.core.domain.bridge.SpawnConfig
import com.claudemobile.core.domain.bridge.classifyExitCause
import com.claudemobile.core.domain.repository.DiagnosticsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [CliBridge] that manages a Claude CLI process via PTY-based
 * process communication inside a proot environment.
 *
 * Spec interaction with `ai-provider-presets`:
 * -------------------------------------------
 * The base-spec `android-claude-termux-client` (R2 AC3) declared that the bridge
 * SHALL set `HOME`, `PATH`, `TERM`, `LANG`, and `ANTHROPIC_API_KEY` in the spawn
 * environment. The `ai-provider-presets` spec **supersedes the
 * `ANTHROPIC_API_KEY`-only behavior** of base-spec R2 AC3: the bridge now
 * derives the Anthropic-compatible variables (`ANTHROPIC_BASE_URL`, exactly one
 * of `ANTHROPIC_API_KEY` / `ANTHROPIC_AUTH_TOKEN`, `ANTHROPIC_MODEL`, optionally
 * `ANTHROPIC_SMALL_FAST_MODEL`) from the currently active
 * [com.claudemobile.core.domain.providers.ProviderProfile] at spawn time.
 *
 * The merge between the two specs is concentrated in [SpawnEnvAdapter] (see the
 * pure `buildClaudeEnv` function and its bridge-side adapter); this class only
 * delegates to that adapter and never reads `CredentialStore` / `SettingsStore`
 * directly. See `ai-provider-presets` Requirement 6 (R6.1, R6.2, R6.3, R6.4,
 * R6.5, R6.6, R6.7, R6.8) and Requirement 11.2 for the full contract.
 *
 * Key behaviors:
 * - Spawns Claude CLI inside proot env with the workspace as working directory.
 * - Reads the Active_Profile via [SpawnEnvAdapter] **at the moment of each
 *   spawn** (R6.1) and merges the Anthropic-compatible variables on top of the
 *   `envVars` supplied by the caller. The `apiKey` value is never logged
 *   (R6.7); diagnostic log lines use [SpawnConfig.toSafeString] which prints
 *   only env keys.
 * - Aborts the spawn — without invoking [ProcessExecutor] — when no
 *   Active_Profile is selected and emits [BridgeEvent.Error] carrying
 *   [BridgeError.NoActiveProfile] (R6.8 / R5.5 / R11.5). The UI then routes the
 *   user to the provider-selection screen.
 * - Forwards user input to stdin within 100ms.
 * - Emits output chunks from PTY within 100ms.
 * - Implements signal escalation: SIGINT → 5s → SIGTERM → 5s → SIGKILL.
 * - Enforces the single-process-per-session invariant.
 * - Records process ID and start timestamp in session runtime state.
 * - Reports spawn failures with command line, exit code, and last 4096 bytes of
 *   stderr.
 *
 * Properties: 9 (each spawn re-reads the Active_Profile), 12 (apiKey never
 * appears in `BridgeEvent.Error.message`, lifecycle log messages, or
 * [SpawnConfig.toSafeString]).
 */
@Singleton
public class CliBridgeImpl @Inject constructor(
    private val dispatchers: CoroutineDispatchers,
    private val timeProvider: TimeProvider,
    private val processExecutor: ProcessExecutor,
    private val diagnosticsRepository: DiagnosticsRepository,
    private val spawnEnvAdapter: SpawnEnvAdapter,
) : CliBridge {

    private val _outputFlow = MutableSharedFlow<BridgeEvent>(extraBufferCapacity = 64)
    override val outputFlow: Flow<BridgeEvent> = _outputFlow.asSharedFlow()

    private val _processState = MutableStateFlow(ProcessState.IDLE)
    override val processState: StateFlow<ProcessState> = _processState.asStateFlow()

    private val spawnMutex = Mutex()
    private var activeProcess: ManagedProcess? = null
    private var readerJob: Job? = null
    private var bridgeScope: CoroutineScope? = null

    /**
     * Runtime state recorded when a process is spawned.
     */
    public var lastPid: Long? = null
        private set

    public var lastStartTimestamp: Instant? = null
        private set

    override suspend fun spawn(config: SpawnConfig): Result<ProcessHandle> =
        spawnMutex.withLock {
            // Resolve the spawn environment from the Active_Profile *before*
            // touching any process state. If no profile is active, abort the
            // spawn entirely: no existing process is terminated, no
            // ProcessExecutor call is made, and the bridge state is left
            // untouched (R6.8). This is the bridge-side fulfilment of
            // ai-provider-presets R6.1 (read at spawn time) and R6.8
            // (NoActiveProfile aborts spawn).
            val envResult = spawnEnvAdapter.prepareEnv(config.envVars)
            val resolvedConfig = envResult.fold(
                onSuccess = { resolvedEnv -> config.copy(envVars = resolvedEnv) },
                onFailure = { error ->
                    if (error is BridgeError.NoActiveProfile) {
                        _outputFlow.emit(
                            BridgeEvent.Error(
                                message = "No active provider profile. " +
                                    "Select or create a provider before starting a session.",
                                throwable = error,
                            )
                        )
                        return@withLock Result.failure(error)
                    }
                    // Any other failure surfaces verbatim. We deliberately do
                    // not log the failure body to diagnostics here because the
                    // adapter is the only place that may have touched secrets;
                    // its own implementation must not put apiKey into the
                    // exception payload (R6.7 / R10.4).
                    _outputFlow.emit(
                        BridgeEvent.Error(
                            message = "Failed to prepare spawn environment: " +
                                "${error.javaClass.simpleName}",
                            throwable = error,
                        )
                    )
                    return@withLock Result.failure(error)
                },
            )

            // Enforce single process per session invariant only after the env
            // is known to be valid. This avoids tearing down a healthy process
            // when the new spawn would have been rejected anyway.
            activeProcess?.let { existing ->
                terminateInternal(existing)
            }

            _processState.value = ProcessState.STARTING

            return@withLock try {
                val process = withContext(dispatchers.io) {
                    processExecutor.execute(resolvedConfig)
                }

                val pid = extractPid(process)
                val managed = ManagedProcess(
                    process = process,
                    pid = pid,
                    startTimestamp = timeProvider.now(),
                    config = resolvedConfig,
                )

                activeProcess = managed
                lastPid = managed.pid
                lastStartTimestamp = managed.startTimestamp

                _processState.value = ProcessState.RUNNING

                // Log bridge lifecycle event. Use SpawnConfig.toSafeString to
                // ensure no env value (including the apiKey under any header
                // style) is written to diagnostics — R6.7 / Property 12.
                logLifecycleEvent(
                    "Process spawned: pid=${managed.pid}",
                    details = resolvedConfig.toSafeString(),
                )

                // Start reading output and monitoring process exit on Dispatchers.IO
                // to avoid blocking the caller's dispatcher (important for testability).
                val scope = CoroutineScope(kotlinx.coroutines.Dispatchers.IO + SupervisorJob())
                bridgeScope = scope
                readerJob = scope.launch {
                    readProcessOutput(managed)
                }

                // Monitor process exit
                scope.launch {
                    monitorProcessExit(managed)
                }

                Result.success(ProcessHandle(
                    pid = managed.pid,
                    startedAt = managed.startTimestamp,
                ))
            } catch (e: Exception) {
                _processState.value = ProcessState.TERMINATED
                activeProcess = null

                val errorMessage = buildSpawnFailureMessage(resolvedConfig, e)
                _outputFlow.emit(BridgeEvent.Error(errorMessage, e))

                // Log spawn failure (never log the API key)
                logLifecycleEvent(
                    "Process spawn failed: ${e.message}",
                    details = errorMessage
                )

                Result.failure(e)
            }
        }

    override suspend fun write(bytes: ByteArray) {
        val process = activeProcess ?: return
        withContext(dispatchers.io) {
            try {
                process.process.outputStream.write(bytes)
                process.process.outputStream.flush()
            } catch (e: Exception) {
                _outputFlow.emit(
                    BridgeEvent.Error("Failed to write to process stdin: ${e.message}", e)
                )
            }
        }
    }

    override suspend fun sendSignal(signal: PosixSignal) {
        val process = activeProcess ?: return
        withContext(dispatchers.io) {
            try {
                processExecutor.sendSignal(process.pid, signal)
            } catch (e: Exception) {
                _outputFlow.emit(
                    BridgeEvent.Error("Failed to send ${signal.name}: ${e.message}", e)
                )
            }
        }
    }

    override suspend fun terminate() {
        spawnMutex.withLock {
            val process = activeProcess ?: return
            terminateInternal(process)
        }
    }

    /**
     * Internal termination with signal escalation:
     * SIGINT → wait 5s → SIGTERM → wait 5s → SIGKILL
     */
    private suspend fun terminateInternal(managed: ManagedProcess) {
        _processState.value = ProcessState.STOPPING

        // Cancel the monitor and reader coroutines to prevent race conditions
        // between monitorProcessExit and this termination sequence.
        readerJob?.cancel()
        readerJob = null
        bridgeScope?.cancel()
        bridgeScope = null

        // Step 1: SIGINT
        try {
            processExecutor.sendSignal(managed.pid, PosixSignal.SIGINT)
        } catch (_: Exception) {
            // Process may already be dead
        }

        // Wait up to 5 seconds for process to exit
        if (waitForExit(managed, SIGNAL_WAIT_MS)) {
            handleProcessExit(managed, ExitCause.USER_CANCELLED)
            return
        }

        // Step 2: SIGTERM
        try {
            processExecutor.sendSignal(managed.pid, PosixSignal.SIGTERM)
        } catch (_: Exception) {
            // Process may already be dead
        }

        // Wait up to 5 seconds for process to exit
        if (waitForExit(managed, SIGNAL_WAIT_MS)) {
            handleProcessExit(managed, ExitCause.USER_CANCELLED)
            return
        }

        // Step 3: SIGKILL
        try {
            processExecutor.sendSignal(managed.pid, PosixSignal.SIGKILL)
        } catch (_: Exception) {
            // Process may already be dead
        }

        // Force cleanup
        try {
            managed.process.destroyForcibly()
        } catch (_: Exception) {
            // Best effort
        }

        handleProcessExit(managed, ExitCause.USER_CANCELLED)
    }

    private suspend fun waitForExit(managed: ManagedProcess, timeoutMs: Long): Boolean {
        val checkInterval = 100L
        var elapsed = 0L
        while (elapsed < timeoutMs) {
            if (!managed.process.isAlive) {
                return true
            }
            delay(checkInterval)
            elapsed += checkInterval
        }
        return !managed.process.isAlive
    }

    private suspend fun handleProcessExit(managed: ManagedProcess, cause: ExitCause) {
        val exitCode = try {
            managed.process.exitValue()
        } catch (_: IllegalThreadStateException) {
            -1
        }

        cleanupProcess()
        _outputFlow.emit(BridgeEvent.ProcessExited(exitCode, cause))
        _processState.value = ProcessState.TERMINATED
    }

    private fun cleanupProcess() {
        readerJob?.cancel()
        readerJob = null
        bridgeScope?.cancel()
        bridgeScope = null
        activeProcess = null
    }

    private suspend fun readProcessOutput(managed: ManagedProcess) {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        val inputStream: InputStream = managed.process.inputStream

        try {
            while (managed.process.isAlive || inputStream.available() > 0) {
                val bytesRead = inputStream.read(buffer)
                if (bytesRead > 0) {
                    val chunk = buffer.copyOf(bytesRead)
                    _outputFlow.emit(BridgeEvent.Output(chunk))
                } else if (bytesRead == -1) {
                    break
                }
            }
        } catch (_: Exception) {
            // Stream closed or process terminated — expected during shutdown
        }
    }

    private suspend fun monitorProcessExit(managed: ManagedProcess) {
        // This coroutine is launched on Dispatchers.IO, so blocking waitFor() is acceptable.
        val exitCode = try {
            managed.process.waitFor()
        } catch (_: InterruptedException) {
            return
        }

        // Only handle if we haven't already cleaned up (e.g., via terminate())
        if (activeProcess === managed) {
            val cause = classifyExitCause(exitCode)
            _outputFlow.emit(BridgeEvent.ProcessExited(exitCode, cause))
            _processState.value = ProcessState.TERMINATED
            cleanupProcess()
        }
    }

    private fun buildSpawnFailureMessage(config: SpawnConfig, exception: Exception): String {
        val commandLine = buildString {
            append(config.command)
            config.args.forEach { arg ->
                append(" ")
                append(arg)
            }
        }

        return when (exception) {
            is ProcessSpawnException -> {
                val exitCodeStr = exception.exitCode?.let { "Exit code: $it" } ?: "Exit code: unknown"
                val stderrContent = exception.stderr
                    ?.takeLast(MAX_STDERR_BYTES)
                    ?: exception.message
                    ?: "Unknown error"
                "Failed to spawn process.\nCommand: $commandLine\n$exitCodeStr\nStderr (last ${MAX_STDERR_BYTES} bytes): $stderrContent"
            }
            else -> {
                val errorMsg = exception.message?.take(MAX_STDERR_BYTES) ?: "Unknown error"
                "Failed to spawn process.\nCommand: $commandLine\nError: $errorMsg"
            }
        }
    }

    private suspend fun logLifecycleEvent(message: String, details: String? = null) {
        try {
            diagnosticsRepository.logEvent(
                sessionId = null,
                eventType = "bridge_lifecycle",
                message = message,
                details = details,
            )
        } catch (_: Exception) {
            // Diagnostics logging should never fail the main operation
        }
    }

    /**
     * Extracts the process ID from a [Process] instance.
     * For [JniProcess], uses the pid() method directly.
     * For other processes, attempts reflection to find the PID field.
     */
    private fun extractPid(process: Process): Long {
        return when (process) {
            is JniProcess -> process.pid()
            else -> {
                // Try calling pid() method via reflection (available on Java 9+ / test JVM)
                try {
                    val pidMethod = process.javaClass.getMethod("pid")
                    (pidMethod.invoke(process) as Long)
                } catch (_: Exception) {
                    // Fallback: try to get PID via field reflection
                    try {
                        val pidField = process.javaClass.getDeclaredField("pid")
                        pidField.isAccessible = true
                        pidField.getInt(process).toLong()
                    } catch (_: Exception) {
                        -1L
                    }
                }
            }
        }
    }

    private companion object {
        const val SIGNAL_WAIT_MS = 5_000L
        const val READ_BUFFER_SIZE = 8192
        const val MAX_STDERR_BYTES = 4096
    }
}

/**
 * Internal representation of a managed CLI process.
 */
internal data class ManagedProcess(
    val process: Process,
    val pid: Long,
    val startTimestamp: Instant,
    val config: SpawnConfig,
)
