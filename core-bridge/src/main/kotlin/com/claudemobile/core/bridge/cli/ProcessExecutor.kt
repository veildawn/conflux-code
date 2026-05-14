package com.claudemobile.core.bridge.cli

import com.claudemobile.core.domain.bridge.PosixSignal
import com.claudemobile.core.domain.bridge.SpawnConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Abstraction for process execution and signal delivery.
 * Enables testing of [CliBridgeImpl] without spawning real processes.
 */
public interface ProcessExecutor {

    /**
     * Executes a process based on the given [SpawnConfig].
     * Returns the [Process] handle.
     *
     * The implementation must:
     * - Set the working directory to [SpawnConfig.workspacePath]
     * - Pass environment variables (including ANTHROPIC_API_KEY) without logging them
     * - Redirect error stream into the standard output stream (PTY-like behavior)
     * - Spawn the process inside the proot environment
     *
     * @throws ProcessSpawnException if the process cannot be started
     */
    public fun execute(config: SpawnConfig): Process

    /**
     * Sends a POSIX signal to the process with the given [pid].
     *
     * @throws SignalDeliveryException if the signal cannot be delivered
     */
    public fun sendSignal(pid: Long, signal: PosixSignal)
}

/**
 * Exception thrown when a process cannot be spawned.
 */
public class ProcessSpawnException(
    message: String,
    public val commandLine: String,
    public val exitCode: Int? = null,
    public val stderr: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Exception thrown when a signal cannot be delivered to a process.
 */
public class SignalDeliveryException(
    message: String,
    public val pid: Long,
    public val signal: PosixSignal,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Default implementation of [ProcessExecutor] that uses [ProcessBuilder]
 * to spawn processes and Runtime.exec to send signals.
 *
 * This implementation spawns the Claude CLI inside a proot environment
 * using the `proot-distro login` command pattern.
 *
 * @deprecated Use [JniProcessExecutor] for PTY-based process communication.
 * This implementation is retained for fallback/testing scenarios.
 */
@Singleton
public class DefaultProcessExecutor @Inject constructor() : ProcessExecutor {

    override fun execute(config: SpawnConfig): Process {
        val command = buildProotCommand(config)

        val processBuilder = ProcessBuilder(command).apply {
            directory(java.io.File(config.workingDir))
            redirectErrorStream(true)

            // Set environment variables — API key is passed here, never logged
            environment().putAll(config.envVars)
        }

        return try {
            processBuilder.start()
        } catch (e: Exception) {
            throw ProcessSpawnException(
                message = "Failed to start process: ${e.message}",
                commandLine = command.joinToString(" "),
                cause = e,
            )
        }
    }

    override fun sendSignal(pid: Long, signal: PosixSignal) {
        val signalNumber = when (signal) {
            PosixSignal.SIGINT -> 2
            PosixSignal.SIGTERM -> 15
            PosixSignal.SIGKILL -> 9
        }

        try {
            // Use android.os.Process.sendSignal for Android compatibility
            android.os.Process.sendSignal(pid.toInt(), signalNumber)
        } catch (e: Exception) {
            throw SignalDeliveryException(
                message = "Failed to send signal ${signal.name} to pid $pid: ${e.message}",
                pid = pid,
                signal = signal,
                cause = e,
            )
        }
    }

    /**
     * Builds the command to execute Claude CLI inside the proot environment.
     */
    private fun buildProotCommand(config: SpawnConfig): List<String> {
        return buildList {
            add(config.command)
            addAll(config.args)
        }
    }
}
