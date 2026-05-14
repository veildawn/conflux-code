package com.claudemobile.core.domain.bridge

import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for bidirectional communication with the Claude CLI process
 * running inside the embedded proot environment via Terminal_Emulator_Lib JNI.
 */
public interface CliBridge {

    /**
     * A flow of events emitted by the bridge (output bytes, process lifecycle, errors).
     */
    public val outputFlow: Flow<BridgeEvent>

    /**
     * The current state of the managed process.
     */
    public val processState: StateFlow<ProcessState>

    /**
     * Spawns a new Claude CLI process with the given configuration.
     * If a process is already running, it will be terminated first (single-process invariant).
     * Returns a handle to the spawned process on success.
     */
    public suspend fun spawn(config: SpawnConfig): Result<ProcessHandle>

    /**
     * Writes bytes to the process's PTY standard input.
     * Bytes are forwarded within 100ms of receipt.
     */
    public suspend fun write(bytes: ByteArray)

    /**
     * Sends a POSIX signal to the managed process.
     */
    public suspend fun sendSignal(signal: PosixSignal)

    /**
     * Terminates the managed process using the signal escalation sequence:
     * SIGINT → (wait 5s) → SIGTERM → (wait 5s) → SIGKILL.
     */
    public suspend fun terminate()
}

/**
 * A handle to a spawned process, providing its identifier and start time.
 */
public data class ProcessHandle(
    /**
     * The operating system process identifier.
     */
    val pid: Long,

    /**
     * The timestamp when the process was started.
     */
    val startedAt: Instant
)

/**
 * Configuration for spawning a Claude CLI process via the Terminal_Emulator_Lib JNI.
 *
 * Maps to the JNI createSubprocess parameters: command path, arguments,
 * environment variables, working directory, and terminal dimensions.
 */
public data class SpawnConfig(
    /**
     * The command to execute (e.g., path to proot binary).
     */
    val command: String,

    /**
     * Command-line arguments passed to the command.
     */
    val args: List<String> = emptyList(),

    /**
     * Environment variables set before exec (format: key to value).
     * Must include HOME, PATH, TERM, LANG, and ANTHROPIC_API_KEY.
     */
    val envVars: Map<String, String> = emptyMap(),

    /**
     * The working directory for the spawned process.
     */
    val workingDir: String,

    /**
     * Terminal row count for PTY allocation.
     */
    val rows: Int = 24,

    /**
     * Terminal column count for PTY allocation.
     */
    val cols: Int = 80
)

/**
 * Events emitted by the CLI bridge during process communication.
 */
public sealed interface BridgeEvent {

    /**
     * Raw output bytes read from the process's pseudo-terminal.
     */
    public data class Output(val bytes: ByteArray) : BridgeEvent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Output) return false
            return bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /**
     * The managed process has been successfully started.
     */
    public data class ProcessStarted(val handle: ProcessHandle) : BridgeEvent

    /**
     * The managed process has exited.
     */
    public data class ProcessExited(
        val exitCode: Int,
        val cause: ExitCause
    ) : BridgeEvent

    /**
     * An error occurred in the bridge layer.
     */
    public data class Error(
        val message: String,
        val throwable: Throwable? = null
    ) : BridgeEvent
}

/**
 * Classification of why a process exited.
 */
public enum class ExitCause {
    /** Process exited with code 0. */
    NORMAL,
    /** Process was terminated by user cancellation (exit code 130). */
    USER_CANCELLED,
    /** Process was killed by the OS due to memory pressure (exit code 137). */
    KILLED_BY_OS,
    /** Process crashed or exited abnormally (exit code > 128, other than 130/137). */
    CRASH
}

/**
 * The lifecycle state of the managed CLI process.
 */
public enum class ProcessState {
    /** No process is running or has been started. */
    IDLE,
    /** A process spawn has been initiated but not yet confirmed. */
    STARTING,
    /** The process is running and communicating via PTY. */
    RUNNING,
    /** A termination signal has been sent; waiting for process exit. */
    STOPPING,
    /** The process has exited (normally or abnormally). */
    TERMINATED
}

/**
 * POSIX signals that can be sent to the managed process.
 */
public enum class PosixSignal(public val value: Int) {
    SIGINT(2),
    SIGTERM(15),
    SIGKILL(9)
}
