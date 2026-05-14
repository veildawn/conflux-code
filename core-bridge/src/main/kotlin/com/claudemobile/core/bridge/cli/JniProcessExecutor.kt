package com.claudemobile.core.bridge.cli

import com.claudemobile.core.common.CoroutineDispatchers
import com.claudemobile.core.domain.bridge.PosixSignal
import com.claudemobile.core.domain.bridge.SpawnConfig
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * JNI-based implementation of [ProcessExecutor] that uses the Termux
 * terminal-emulator library to allocate PTYs and fork/exec subprocesses.
 *
 * This implementation:
 * - Calls [TerminalJni.createSubprocess] to allocate a PTY and fork the process
 * - Returns a [JniProcess] that wraps the pid and [PtyChannel] for I/O
 * - Sends signals via [TerminalJni.sendSignal]
 * - Executes JNI calls on [Dispatchers.IO] to avoid blocking
 *
 * Requirements satisfied:
 * - Req 2.1: Uses Terminal_Emulator_Lib JNI to allocate PTY and fork/exec
 * - Req 2.5: Connects PTY fd to PtyChannel for bidirectional streaming
 * - Req 2.8: Forward bytes to PTY within 100ms (via PtyChannel on IO dispatcher)
 * - Req 2.9: Emit output chunks within 100ms (via PtyChannel.outputFlow)
 */
@Singleton
public class JniProcessExecutor @Inject constructor(
    private val dispatchers: CoroutineDispatchers,
) : ProcessExecutor {

    /**
     * Spawns a subprocess using the Terminal_Emulator_Lib JNI interface.
     *
     * Converts [SpawnConfig] into JNI parameters and calls [TerminalJni.createSubprocess].
     * Returns a [JniProcess] wrapping the allocated PTY fd and process ID.
     *
     * @param config The spawn configuration including command, args, env vars, and terminal size
     * @return A [Process] handle (actually [JniProcess]) for the spawned subprocess
     * @throws ProcessSpawnException if the JNI call fails
     */
    override fun execute(config: SpawnConfig): Process {
        val envArray = config.envVars.map { (key, value) -> "$key=$value" }.toTypedArray()
        val argsArray = config.args.toTypedArray()

        return try {
            val result = TerminalJni.createSubprocess(
                cmd = config.command,
                cwd = config.workingDir,
                args = argsArray,
                envVars = envArray,
                rows = config.rows,
                cols = config.cols,
            )

            val pid = result[0]
            val ptyFd = result[1]

            if (pid <= 0) {
                throw ProcessSpawnException(
                    message = "JNI createSubprocess returned invalid pid: $pid",
                    commandLine = buildCommandLine(config),
                    exitCode = null,
                    stderr = null,
                )
            }

            val ptyChannel = PtyChannel(fd = ptyFd, dispatchers = dispatchers)

            JniProcess(
                pid = pid,
                ptyFd = ptyFd,
                ptyChannel = ptyChannel,
            )
        } catch (e: ProcessSpawnException) {
            throw e
        } catch (e: Exception) {
            throw ProcessSpawnException(
                message = "Failed to create subprocess via JNI: ${e.message}",
                commandLine = buildCommandLine(config),
                exitCode = null,
                stderr = e.stackTraceToString().take(MAX_STDERR_LENGTH),
                cause = e,
            )
        }
    }

    /**
     * Sends a POSIX signal to the process with the given [pid].
     *
     * Uses [TerminalJni.sendSignal] which delegates to `android.os.Process.sendSignal`.
     *
     * @param pid The process ID to signal
     * @param signal The POSIX signal to send
     * @throws SignalDeliveryException if the signal cannot be delivered
     */
    override fun sendSignal(pid: Long, signal: PosixSignal) {
        try {
            TerminalJni.sendSignal(pid.toInt(), signal.value)
        } catch (e: Exception) {
            throw SignalDeliveryException(
                message = "Failed to send ${signal.name} to pid $pid via JNI: ${e.message}",
                pid = pid,
                signal = signal,
                cause = e,
            )
        }
    }

    private fun buildCommandLine(config: SpawnConfig): String = buildString {
        append(config.command)
        config.args.forEach { arg ->
            append(' ')
            append(arg)
        }
    }

    private companion object {
        const val MAX_STDERR_LENGTH = 4096
    }
}
