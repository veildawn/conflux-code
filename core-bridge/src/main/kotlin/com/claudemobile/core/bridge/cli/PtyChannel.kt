package com.claudemobile.core.bridge.cli

import com.claudemobile.core.common.CoroutineDispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/**
 * Bidirectional channel for PTY file descriptor I/O operations.
 *
 * Wraps the raw PTY fd obtained from [TerminalJni.createSubprocess] and provides
 * coroutine-friendly read/write operations that execute on [Dispatchers.IO] to
 * avoid blocking the caller's dispatcher.
 *
 * The channel ensures:
 * - Reads and writes execute on the IO dispatcher (requirement 2.8, 2.9)
 * - Output is emitted within 100ms of availability via the [outputFlow]
 * - The fd is properly closed when the channel is closed
 *
 * @param fd The PTY file descriptor from [TerminalJni.createSubprocess]
 * @param dispatchers The coroutine dispatchers abstraction for testability
 */
public class PtyChannel(
    private val fd: Int,
    private val dispatchers: CoroutineDispatchers,
) {
    private val closed = AtomicBoolean(false)

    /**
     * A cold Flow that continuously reads from the PTY fd and emits byte chunks.
     *
     * The flow runs on [Dispatchers.IO] and emits data as soon as it becomes
     * available from the PTY, satisfying the 100ms latency requirement (Req 2.9).
     *
     * The flow completes when:
     * - The PTY fd returns EOF (read returns -1)
     * - The channel is [close]d
     * - The collecting coroutine is cancelled
     */
    public val outputFlow: Flow<ByteArray> = flow {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        while (coroutineContext.isActive && !closed.get()) {
            val bytesRead = try {
                TerminalJni.readFromPty(fd, buffer, 0, buffer.size)
            } catch (e: Exception) {
                if (closed.get()) break
                throw PtyIOException("Failed to read from PTY fd=$fd", e)
            }

            when {
                bytesRead > 0 -> emit(buffer.copyOf(bytesRead))
                bytesRead == -1 -> break // EOF
                else -> break // Unexpected
            }
        }
    }.flowOn(dispatchers.io)

    /**
     * Writes bytes to the PTY file descriptor.
     *
     * Executes on [Dispatchers.IO] to avoid blocking the caller.
     * Satisfies the 100ms write latency requirement (Req 2.8).
     *
     * @param bytes The data to write to the PTY
     * @throws PtyIOException if the write fails or the channel is closed
     */
    public suspend fun write(bytes: ByteArray): Unit = withContext(dispatchers.io) {
        if (closed.get()) {
            throw PtyIOException("Cannot write to closed PtyChannel (fd=$fd)")
        }
        try {
            TerminalJni.writeToPty(fd, bytes, 0, bytes.size)
        } catch (e: Exception) {
            throw PtyIOException("Failed to write to PTY fd=$fd", e)
        }
    }

    /**
     * Reads available bytes from the PTY file descriptor.
     *
     * This is a suspending call that blocks on the IO dispatcher until data
     * is available. Returns the number of bytes read into [buffer].
     *
     * @param buffer The buffer to read data into
     * @return Number of bytes read, or -1 on EOF
     * @throws PtyIOException if the read fails or the channel is closed
     */
    public suspend fun read(buffer: ByteArray): Int = withContext(dispatchers.io) {
        if (closed.get()) {
            return@withContext -1
        }
        try {
            TerminalJni.readFromPty(fd, buffer, 0, buffer.size)
        } catch (e: Exception) {
            if (closed.get()) -1
            else throw PtyIOException("Failed to read from PTY fd=$fd", e)
        }
    }

    /**
     * Closes the PTY file descriptor and marks this channel as closed.
     *
     * After calling close, all subsequent read/write operations will fail.
     * Any ongoing read operations will return -1 or throw.
     */
    public fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                TerminalJni.closePty(fd)
            } catch (_: Exception) {
                // Best effort close — fd may already be invalid
            }
        }
    }

    /**
     * Returns whether this channel has been closed.
     */
    public val isClosed: Boolean
        get() = closed.get()

    /**
     * The underlying PTY file descriptor. Exposed for window size changes.
     */
    public val ptyFd: Int
        get() = fd

    private companion object {
        /** Buffer size for PTY reads — 8KB provides good throughput without excessive memory. */
        const val READ_BUFFER_SIZE = 8192
    }
}

/**
 * Exception thrown when a PTY I/O operation fails.
 */
public class PtyIOException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
