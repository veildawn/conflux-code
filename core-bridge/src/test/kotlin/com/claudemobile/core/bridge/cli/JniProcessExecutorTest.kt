package com.claudemobile.core.bridge.cli

import com.claudemobile.core.common.CoroutineDispatchers
import com.claudemobile.core.domain.bridge.PosixSignal
import com.claudemobile.core.domain.bridge.SpawnConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [JniProcessExecutor].
 *
 * Tests verify:
 * - Correct delegation to TerminalJni.createSubprocess
 * - Proper SpawnConfig → JNI parameter mapping
 * - Error handling for spawn failures
 * - Signal delivery via TerminalJni.sendSignal
 *
 * Validates: Requirements 2.1, 2.5, 2.8, 2.9
 */
class JniProcessExecutorTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testDispatchers = object : CoroutineDispatchers {
        override val default: CoroutineDispatcher = testDispatcher
        override val io: CoroutineDispatcher = testDispatcher
        override val main: CoroutineDispatcher = testDispatcher
        override val mainImmediate: CoroutineDispatcher = testDispatcher
        override val unconfined: CoroutineDispatcher = testDispatcher
    }

    private lateinit var executor: JniProcessExecutor

    @BeforeEach
    fun setup() {
        mockkObject(TerminalJni)
        executor = JniProcessExecutor(testDispatchers)
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(TerminalJni)
    }

    @Nested
    @DisplayName("execute()")
    inner class Execute {

        @Test
        fun `execute calls TerminalJni createSubprocess with correct parameters`() {
            val config = SpawnConfig(
                command = "/data/data/com.claudemobile/prefix/usr/bin/proot",
                workingDir = "/workspace/project",
                args = listOf("--rootfs=/data/data/com.claudemobile/rootfs"),
                envVars = mapOf(
                    "HOME" to "/root",
                    "PATH" to "/usr/bin:/bin",
                    "TERM" to "xterm-256color",
                    "LANG" to "en_US.UTF-8",
                    "ANTHROPIC_API_KEY" to "sk-ant-test123",
                ),
                rows = 40,
                cols = 120,
            )

            every {
                TerminalJni.createSubprocess(
                    cmd = "/data/data/com.claudemobile/prefix/usr/bin/proot",
                    cwd = "/workspace/project",
                    args = arrayOf("--rootfs=/data/data/com.claudemobile/rootfs"),
                    envVars = any(),
                    rows = 40,
                    cols = 120,
                )
            } returns intArrayOf(1234, 5)

            val process = executor.execute(config)

            process.shouldBeInstanceOf<JniProcess>()
            process.pid() shouldBe 1234L
        }

        @Test
        fun `execute converts envVars map to KEY=VALUE array`() {
            val config = SpawnConfig(
                command = "/bin/sh",
                workingDir = "/tmp",
                envVars = mapOf(
                    "HOME" to "/root",
                    "TERM" to "xterm",
                ),
                rows = 24,
                cols = 80,
            )

            every {
                TerminalJni.createSubprocess(
                    cmd = any(),
                    cwd = any(),
                    args = any(),
                    envVars = any(),
                    rows = any(),
                    cols = any(),
                )
            } returns intArrayOf(100, 3)

            executor.execute(config)

            verify {
                TerminalJni.createSubprocess(
                    cmd = "/bin/sh",
                    cwd = "/tmp",
                    args = emptyArray(),
                    envVars = match { envArray ->
                        envArray.toSet() == setOf("HOME=/root", "TERM=xterm")
                    },
                    rows = 24,
                    cols = 80,
                )
            }
        }

        @Test
        fun `execute returns JniProcess with correct PtyChannel`() {
            val config = SpawnConfig(
                command = "/bin/sh",
                workingDir = "/tmp",
                rows = 24,
                cols = 80,
            )

            every {
                TerminalJni.createSubprocess(any(), any(), any(), any(), any(), any())
            } returns intArrayOf(42, 7)

            val process = executor.execute(config) as JniProcess

            process.pid() shouldBe 42L
            process.ptyChannel shouldNotBe null
            process.ptyChannel.ptyFd shouldBe 7
        }

        @Test
        fun `execute throws ProcessSpawnException when JNI returns invalid pid`() {
            val config = SpawnConfig(
                command = "/nonexistent",
                workingDir = "/tmp",
                rows = 24,
                cols = 80,
            )

            every {
                TerminalJni.createSubprocess(any(), any(), any(), any(), any(), any())
            } returns intArrayOf(-1, 0) // Invalid pid

            val exception = shouldThrow<ProcessSpawnException> {
                executor.execute(config)
            }

            exception.message shouldContain "invalid pid"
            exception.commandLine shouldContain "/nonexistent"
        }

        @Test
        fun `execute throws ProcessSpawnException when JNI throws`() {
            val config = SpawnConfig(
                command = "/bin/sh",
                workingDir = "/tmp",
                rows = 24,
                cols = 80,
            )

            every {
                TerminalJni.createSubprocess(any(), any(), any(), any(), any(), any())
            } throws RuntimeException("Native library not loaded")

            val exception = shouldThrow<ProcessSpawnException> {
                executor.execute(config)
            }

            exception.message shouldContain "Failed to create subprocess via JNI"
            exception.commandLine shouldBe "/bin/sh"
        }

        @Test
        fun `execute uses default rows and cols from SpawnConfig`() {
            val config = SpawnConfig(
                command = "/bin/sh",
                workingDir = "/tmp",
                // Using defaults: rows = 24, cols = 80
            )

            every {
                TerminalJni.createSubprocess(any(), any(), any(), any(), any(), any())
            } returns intArrayOf(1, 2)

            executor.execute(config)

            verify {
                TerminalJni.createSubprocess(
                    cmd = "/bin/sh",
                    cwd = "/tmp",
                    args = emptyArray(),
                    envVars = emptyArray(),
                    rows = 24,
                    cols = 80,
                )
            }
        }
    }

    @Nested
    @DisplayName("sendSignal()")
    inner class SendSignal {

        @Test
        fun `sendSignal delegates SIGINT to TerminalJni`() {
            every { TerminalJni.sendSignal(100, 2) } returns Unit

            executor.sendSignal(100L, PosixSignal.SIGINT)

            verify { TerminalJni.sendSignal(100, 2) }
        }

        @Test
        fun `sendSignal delegates SIGTERM to TerminalJni`() {
            every { TerminalJni.sendSignal(200, 15) } returns Unit

            executor.sendSignal(200L, PosixSignal.SIGTERM)

            verify { TerminalJni.sendSignal(200, 15) }
        }

        @Test
        fun `sendSignal delegates SIGKILL to TerminalJni`() {
            every { TerminalJni.sendSignal(300, 9) } returns Unit

            executor.sendSignal(300L, PosixSignal.SIGKILL)

            verify { TerminalJni.sendSignal(300, 9) }
        }

        @Test
        fun `sendSignal throws SignalDeliveryException on failure`() {
            every { TerminalJni.sendSignal(any(), any()) } throws RuntimeException("No such process")

            val exception = shouldThrow<SignalDeliveryException> {
                executor.sendSignal(999L, PosixSignal.SIGTERM)
            }

            exception.pid shouldBe 999L
            exception.signal shouldBe PosixSignal.SIGTERM
            exception.message shouldContain "Failed to send SIGTERM"
        }
    }
}
