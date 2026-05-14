package com.claudemobile.core.bridge.cli

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [Process] implementation backed by the Termux terminal-emulator JNI layer.
 *
 * Unlike [java.lang.ProcessBuilder]-based processes, this process communicates
 * through a PTY file descriptor rather than separate stdin/stdout/stderr pipes.
 * The [PtyChannel] provides the bidirectional I/O interface.
 *
 * This class adapts the JNI process to the standard [Process] API expected by
 * [CliBridgeImpl], while also exposing the [PtyChannel] for direct PTY access.
 *
 * @param pid The OS process ID returned by [TerminalJni.createSubprocess]
 * @param ptyFd The PTY file descriptor for I/O
 * @param ptyChannel The [PtyChannel] wrapping the PTY fd for coroutine-friendly I/O
 */
public class JniProcess(
    private val pid: Int,
    private val ptyFd: Int,
    public val ptyChannel: PtyChannel,
) : Process() {

    private val exited = AtomicBoolean(false)
    private val exitCodeValue = AtomicInteger(-1)
    private val destroyed = AtomicBoolean(false)

    /**
     * Returns the PTY-backed output stream for writing to the process's stdin.
     *
     * Note: For JNI processes, prefer using [ptyChannel.write] directly for
     * coroutine-friendly writes. This stream is provided for API compatibility.
     */
    override fun getOutputStream(): OutputStream = PtyOutputStream(ptyFd)

    /**
     * Returns the PTY-backed input stream for reading the process's stdout.
     *
     * Note: For JNI processes, prefer using [ptyChannel.outputFlow] or
     * [ptyChannel.read] directly. This stream is provided for API compatibility.
     */
    override fun getInputStream(): InputStream = PtyInputStream(ptyFd)

    /**
     * Returns the same stream as [getInputStream] since PTY merges stdout/stderr.
     */
    override fun getErrorStream(): InputStream = PtyInputStream(ptyFd)

    /**
     * Blocks until the process exits and returns the exit code.
     */
    override fun waitFor(): Int {
        if (exited.get()) return exitCodeValue.get()

        val code = TerminalJni.waitFor(pid)
        exitCodeValue.set(code)
        exited.set(true)
        return code
    }

    /**
     * Returns the exit value if the process has exited.
     *
     * @throws IllegalThreadStateException if the process has not yet exited
     */
    override fun exitValue(): Int {
        if (!exited.get()) {
            // Check if process has exited without blocking
            // We can't do a non-blocking wait with the JNI API,
            // so we check the isAlive state
            throw IllegalThreadStateException("Process $pid has not yet exited")
        }
        return exitCodeValue.get()
    }

    /**
     * Sends SIGTERM to the process.
     */
    override fun destroy() {
        if (destroyed.compareAndSet(false, true)) {
            try {
                TerminalJni.sendSignal(pid, SIGTERM)
            } catch (_: Exception) {
                // Process may already be dead
            }
        }
    }

    /**
     * Sends SIGKILL to the process and closes the PTY channel.
     */
    override fun destroyForcibly(): Process {
        try {
            TerminalJni.sendSignal(pid, SIGKILL)
        } catch (_: Exception) {
            // Process may already be dead
        }
        ptyChannel.close()
        return this
    }

    /**
     * Returns whether the process is still alive.
     */
    override fun isAlive(): Boolean = !exited.get()

    /**
     * Returns the process ID.
     */
    public fun pid(): Long = pid.toLong()

    /**
     * Marks this process as exited with the given exit code.
     * Called internally when waitFor completes or when the process is detected as terminated.
     */
    internal fun markExited(exitCode: Int) {
        exitCodeValue.set(exitCode)
        exited.set(true)
    }

    private companion object {
        const val SIGTERM = 15
        const val SIGKILL = 9
    }
}

/**
 * An [OutputStream] that writes to a PTY file descriptor via JNI.
 */
private class PtyOutputStream(private val fd: Int) : OutputStream() {

    override fun write(b: Int) {
        val bytes = byteArrayOf(b.toByte())
        TerminalJni.writeToPty(fd, bytes, 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        TerminalJni.writeToPty(fd, b, off, len)
    }

    override fun flush() {
        // PTY writes are unbuffered at the JNI level
    }
}

/**
 * An [InputStream] that reads from a PTY file descriptor via JNI.
 */
private class PtyInputStream(private val fd: Int) : InputStream() {

    override fun read(): Int {
        val buffer = ByteArray(1)
        val bytesRead = TerminalJni.readFromPty(fd, buffer, 0, 1)
        return if (bytesRead > 0) buffer[0].toInt() and 0xFF else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        return TerminalJni.readFromPty(fd, b, off, len)
    }
}
