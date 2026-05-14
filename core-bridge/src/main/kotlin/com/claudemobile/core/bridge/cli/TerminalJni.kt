package com.claudemobile.core.bridge.cli

import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Kotlin wrapper around the Termux terminal-emulator JNI interface.
 *
 * The Termux `JNI` class is package-private, so we access it via reflection.
 * Once the native library is loaded (triggered by class initialization), we
 * cache the Method references for efficient repeated calls.
 *
 * For PTY I/O, we use [android.system.Os.read]/[android.system.Os.write] on
 * a wrapped [FileDescriptor], which is the same approach Termux uses internally
 * via FileInputStream/FileOutputStream.
 *
 * All methods in this object are thread-safe.
 */
public object TerminalJni {

    private val jniClass: Class<*> by lazy {
        Class.forName("com.termux.terminal.JNI")
    }

    private val createSubprocessMethod: Method by lazy {
        jniClass.getDeclaredMethod(
            "createSubprocess",
            String::class.java,       // cmd
            String::class.java,       // cwd
            Array<String>::class.java, // args
            Array<String>::class.java, // envVars
            IntArray::class.java,      // processId (out)
            Int::class.javaPrimitiveType, // rows
            Int::class.javaPrimitiveType, // columns
            Int::class.javaPrimitiveType, // cellWidth
            Int::class.javaPrimitiveType, // cellHeight
        ).also { it.isAccessible = true }
    }

    private val setPtyWindowSizeMethod: Method by lazy {
        jniClass.getDeclaredMethod(
            "setPtyWindowSize",
            Int::class.javaPrimitiveType, // fd
            Int::class.javaPrimitiveType, // rows
            Int::class.javaPrimitiveType, // cols
            Int::class.javaPrimitiveType, // cellWidth
            Int::class.javaPrimitiveType, // cellHeight
        ).also { it.isAccessible = true }
    }

    private val waitForMethod: Method by lazy {
        jniClass.getDeclaredMethod(
            "waitFor",
            Int::class.javaPrimitiveType, // processId
        ).also { it.isAccessible = true }
    }

    private val closeMethod: Method by lazy {
        jniClass.getDeclaredMethod(
            "close",
            Int::class.javaPrimitiveType, // fileDescriptor
        ).also { it.isAccessible = true }
    }

    private val fdField: Field by lazy {
        try {
            FileDescriptor::class.java.getDeclaredField("descriptor")
        } catch (_: NoSuchFieldException) {
            FileDescriptor::class.java.getDeclaredField("fd")
        }.also { it.isAccessible = true }
    }

    /**
     * Creates a subprocess with a new PTY allocated.
     *
     * Delegates to the Termux JNI native method which:
     * 1. Opens /dev/ptmx to get a master PTY fd
     * 2. Forks the process
     * 3. In the child: opens the slave PTY, sets it as stdin/stdout/stderr, exec's the command
     * 4. Returns the master fd to the parent
     *
     * @param cmd The command path to execute (e.g., path to proot binary)
     * @param cwd The working directory for the subprocess
     * @param args Command-line arguments passed to the command
     * @param envVars Environment variables in "KEY=VALUE" format
     * @param rows Terminal row count for PTY window size
     * @param cols Terminal column count for PTY window size
     * @return IntArray containing [pid, ptyFd] on success
     * @throws RuntimeException if the subprocess cannot be created
     */
    public fun createSubprocess(
        cmd: String,
        cwd: String,
        args: Array<String>,
        envVars: Array<String>,
        rows: Int,
        cols: Int,
    ): IntArray {
        val processId = IntArray(1)
        val fd = createSubprocessMethod.invoke(
            null, // static method
            cmd,
            cwd,
            args,
            envVars,
            processId,
            rows,
            cols,
            0, // cellWidth (not needed for our use case)
            0, // cellHeight (not needed for our use case)
        ) as Int

        return intArrayOf(processId[0], fd)
    }

    /**
     * Writes bytes to the PTY file descriptor using [Os.write].
     *
     * @param fd The PTY file descriptor obtained from [createSubprocess]
     * @param bytes The byte array to write
     * @param offset Starting offset in the byte array
     * @param count Number of bytes to write
     * @return Number of bytes actually written
     */
    public fun writeToPty(fd: Int, bytes: ByteArray, offset: Int, count: Int): Int {
        val fileDescriptor = wrapFileDescriptor(fd)
        return Os.write(fileDescriptor, bytes, offset, count)
    }

    /**
     * Reads available bytes from the PTY file descriptor using [Os.read].
     *
     * This is a blocking call that waits until data is available.
     *
     * @param fd The PTY file descriptor obtained from [createSubprocess]
     * @param buffer Buffer to read bytes into
     * @param offset Starting offset in the buffer
     * @param count Maximum number of bytes to read
     * @return Number of bytes read, or -1 on EOF/error
     */
    public fun readFromPty(fd: Int, buffer: ByteArray, offset: Int, count: Int): Int {
        val fileDescriptor = wrapFileDescriptor(fd)
        return try {
            Os.read(fileDescriptor, buffer, offset, count)
        } catch (e: android.system.ErrnoException) {
            if (e.errno == OsConstants.EIO || e.errno == OsConstants.EBADF) {
                -1 // EOF or closed fd
            } else {
                throw e
            }
        }
    }

    /**
     * Closes the PTY file descriptor, releasing the associated resources.
     *
     * @param fd The PTY file descriptor to close
     */
    public fun closePty(fd: Int) {
        closeMethod.invoke(null, fd)
    }

    /**
     * Waits for the subprocess to exit and returns its exit status.
     *
     * This is a blocking call.
     *
     * @param pid The process ID returned from [createSubprocess]
     * @return The exit status of the process (>= 0), or negated signal number (< 0)
     */
    public fun waitFor(pid: Int): Int {
        return waitForMethod.invoke(null, pid) as Int
    }

    /**
     * Sends a POSIX signal to the specified process.
     *
     * Uses [android.os.Process.sendSignal] which is the standard Android API
     * for sending signals to processes owned by the same UID.
     *
     * @param pid The process ID to signal
     * @param signal The signal number (e.g., 2 for SIGINT, 15 for SIGTERM, 9 for SIGKILL)
     */
    public fun sendSignal(pid: Int, signal: Int) {
        android.os.Process.sendSignal(pid, signal)
    }

    /**
     * Sets the terminal window size for the PTY.
     *
     * @param fd The PTY file descriptor
     * @param rows New row count
     * @param cols New column count
     */
    public fun setWindowSize(fd: Int, rows: Int, cols: Int) {
        setPtyWindowSizeMethod.invoke(null, fd, rows, cols, 0, 0)
    }

    /**
     * Wraps a raw integer file descriptor into a [FileDescriptor] object
     * for use with [Os.read] and [Os.write].
     */
    private fun wrapFileDescriptor(fd: Int): FileDescriptor {
        val fileDescriptor = FileDescriptor()
        fdField.set(fileDescriptor, fd)
        return fileDescriptor
    }
}
