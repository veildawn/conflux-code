package com.claudemobile.core.bridge.cli

import app.cash.turbine.test
import com.claudemobile.core.common.CoroutineDispatchers
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [PtyChannel].
 *
 * Tests verify:
 * - Read operations delegate to TerminalJni.readFromPty on IO dispatcher
 * - Write operations delegate to TerminalJni.writeToPty on IO dispatcher
 * - Close properly closes the fd and prevents further I/O
 * - outputFlow emits data and completes on EOF
 *
 * Validates: Requirements 2.5, 2.8, 2.9
 */
class PtyChannelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testDispatchers = object : CoroutineDispatchers {
        override val default: CoroutineDispatcher = testDispatcher
        override val io: CoroutineDispatcher = testDispatcher
        override val main: CoroutineDispatcher = testDispatcher
        override val mainImmediate: CoroutineDispatcher = testDispatcher
        override val unconfined: CoroutineDispatcher = testDispatcher
    }

    private val testFd = 42

    @BeforeEach
    fun setup() {
        mockkObject(TerminalJni)
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(TerminalJni)
    }

    @Nested
    @DisplayName("write()")
    inner class Write {

        @Test
        fun `write delegates to TerminalJni writeToPty`() = runTest(testDispatcher) {
            val channel = PtyChannel(fd = testFd, dispatchers = testDispatchers)
            val data = "hello\n".toByteArray()

            every { TerminalJni.writeToPty(testFd, data, 0, data.size) } returns data.size

            channel.write(data)

            verify { TerminalJni.writeToPty(testFd, data, 0, data.size) }
        }

        @Test
        fun `write throws PtyIOException when channel is closed`() = runTest(testDispatcher) {
            val channel = PtyChannel(fd = testFd, dispatchers = testDispatchers)
            every { TerminalJni.closePty(testFd) } returns Unit

            channel.close()

            shouldThrow<PtyIOException> {
                channel.write("test".toByteArray())
            }
        }

        @Test
        fun `write throws PtyIOException on JNI failure`() = runTest(testDispatcher) {
            val channel = PtyChannel(fd = testFd, dispatchers = testDispatchers)
            val data = "test".toByteArray()

            every {
                TerminalJni.writeToPty(testFd, data, 0, data.size)
            } throws RuntimeException("Write failed")

            shouldThrow<PtyIOException> {
                channel.write(data)
            }
        }
    }

    @Nested
    @DisplayName("read()")
    inner class Read {

        @Test
        fun `read delegates to TerminalJni readFromPty`() = runTest(testDispatcher) {
            val channel = PtyChannel(fd = testFd, dispatchers = testDispatchers)
            val buffer = ByteArray(1024)

            every {
                TerminalJni.readFromPty(testFd, buffer, 0, buffer.size)
            } returns 5

            val bytesRead = channel.read(buffer)

            bytesRead shouldBe 5
            verify { TerminalJni.readFromPty(testFd, buffer, 0, buffer.size) }
        }

        @Test
        fun `read returns -1 when channel is closed`() = runTest(testDispatcher) {
            val channel = PtyChannel(fd = testFd, dispatchers = testDispatchers)
            every { TerminalJni.closePty(testFd) } returns Unit

            channel.close()

            val bytesRead = channel.read(ByteArray(1024))
            bytesRead shouldBe -1
        }

        @Test
        fun `read returns -1 on EOF`() = runTest(testDispatcher) {
            val channel = PtyChannel(fd = testFd, dispatchers = testDispatchers)
            val buffer = ByteArray(1024)

            every {
                TerminalJni.readFromPty(testFd, buffer, 0, buffer.size)
            } returns -1

            val bytesRead = channel.read(buffer)
            bytesRead shouldBe -1
        }
    }

    @Nested
    @DisplayName("outputFlow")
    inner class OutputFlow {

        @Test
        fun `outputFlow emits data chunks from PTY`() = runTest(testDispatcher) {
            val channel = PtyChannel(fd = testFd, dispatchers = testDispatchers)
            val expectedData = "Hello from PTY\n".toByteArray()

            var callCount = 0
            every {
                TerminalJni.readFromPty(testFd, any(), 0, any())
            } answers {
                callCount++
                if (callCount == 1) {
                    val buffer = secondArg<ByteArray>()
                    expectedData.copyInto(buffer)
                    expectedData.size
                } else {
                    -1 // EOF on second call
                }
            }

            channel.outputFlow.test {
                val chunk = awaitItem()
                chunk shouldBe expectedData
                awaitComplete()
            }
        }

        @Test
        fun `outputFlow completes on EOF`() = runTest(testDispatcher) {
            val channel = PtyChannel(fd = testFd, dispatchers = testDispatchers)

            every {
                TerminalJni.readFromPty(testFd, any(), 0, any())
            } returns -1

            channel.outputFlow.test {
                awaitComplete()
            }
        }

        @Test
        fun `outputFlow emits multiple chunks before EOF`() = runTest(testDispatcher) {
            val channel = PtyChannel(fd = testFd, dispatchers = testDispatchers)
            val chunk1 = "first\n".toByteArray()
            val chunk2 = "second\n".toByteArray()

            var callCount = 0
            every {
                TerminalJni.readFromPty(testFd, any(), 0, any())
            } answers {
                callCount++
                val buffer = secondArg<ByteArray>()
                when (callCount) {
                    1 -> {
                        chunk1.copyInto(buffer)
                        chunk1.size
                    }
                    2 -> {
                        chunk2.copyInto(buffer)
                        chunk2.size
                    }
                    else -> -1
                }
            }

            channel.outputFlow.test {
                awaitItem() shouldBe chunk1
                awaitItem() shouldBe chunk2
                awaitComplete()
            }
        }
    }

    @Nested
    @DisplayName("close()")
    inner class Close {

        @Test
        fun `close delegates to TerminalJni closePty`() {
            val channel = PtyChannel(fd = testFd, dispatchers = testDispatchers)
            every { TerminalJni.closePty(testFd) } returns Unit

            channel.close()

            verify { TerminalJni.closePty(testFd) }
        }

        @Test
        fun `close is idempotent - second call does not invoke JNI again`() {
            val channel = PtyChannel(fd = testFd, dispatchers = testDispatchers)
            every { TerminalJni.closePty(testFd) } returns Unit

            channel.close()
            channel.close()

            verify(exactly = 1) { TerminalJni.closePty(testFd) }
        }

        @Test
        fun `isClosed returns true after close`() {
            val channel = PtyChannel(fd = testFd, dispatchers = testDispatchers)
            every { TerminalJni.closePty(testFd) } returns Unit

            channel.isClosed shouldBe false
            channel.close()
            channel.isClosed shouldBe true
        }

        @Test
        fun `close does not throw even if JNI throws`() {
            val channel = PtyChannel(fd = testFd, dispatchers = testDispatchers)
            every { TerminalJni.closePty(testFd) } throws RuntimeException("Bad fd")

            // Should not throw
            channel.close()
            channel.isClosed shouldBe true
        }
    }

    @Nested
    @DisplayName("ptyFd property")
    inner class PtyFdProperty {

        @Test
        fun `ptyFd returns the fd passed to constructor`() {
            val channel = PtyChannel(fd = 99, dispatchers = testDispatchers)
            channel.ptyFd shouldBe 99
        }
    }
}
