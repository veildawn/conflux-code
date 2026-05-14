package com.claudemobile.core.domain.usecase

import com.claudemobile.core.common.AppResult
import com.claudemobile.core.common.ErrorCode
import com.claudemobile.core.common.TimeProvider
import com.claudemobile.core.common.UuidGenerator
import com.claudemobile.core.domain.bridge.BridgeEvent
import com.claudemobile.core.domain.bridge.CliBridge
import com.claudemobile.core.domain.bridge.ExitCause
import com.claudemobile.core.domain.bridge.ProcessState
import com.claudemobile.core.domain.model.Message
import com.claudemobile.core.domain.model.MessageId
import com.claudemobile.core.domain.model.MessageRole
import com.claudemobile.core.domain.model.MessageStatus
import com.claudemobile.core.domain.model.OutputEvent
import com.claudemobile.core.domain.model.ParseResult
import com.claudemobile.core.domain.model.SessionId
import com.claudemobile.core.domain.parser.OutputParser
import com.claudemobile.core.domain.repository.ConversationRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import java.time.Instant

class StreamResponseUseCaseTest : DescribeSpec({

    val conversationRepository = mockk<ConversationRepository>(relaxed = true)
    val cliBridge = mockk<CliBridge>()
    val outputParser = mockk<OutputParser>(relaxed = true)
    val timeProvider = mockk<TimeProvider>()
    val uuidGenerator = mockk<UuidGenerator>()

    val useCase = StreamResponseUseCase(
        conversationRepository = conversationRepository,
        cliBridge = cliBridge,
        outputParser = outputParser,
        timeProvider = timeProvider,
        uuidGenerator = uuidGenerator,
    )

    val testSessionId = SessionId("session-123")
    val testUuid = "assistant-msg-uuid"
    val baseTime = Instant.parse("2024-01-15T10:00:00Z")

    beforeEach {
        io.mockk.clearMocks(conversationRepository, cliBridge, outputParser, timeProvider, uuidGenerator, answers = false)
        every { uuidGenerator.generate() } returns testUuid
        every { timeProvider.now() } returns baseTime
        every { cliBridge.processState } returns MutableStateFlow(ProcessState.RUNNING)
        coEvery { conversationRepository.getMessages(testSessionId) } returns emptyList()
        coEvery { conversationRepository.insertMessage(any()) } answers { firstArg() }
        every { outputParser.reset() } returns Unit
    }

    describe("StreamResponseUseCase") {

        it("returns failure when session ID is blank") {
            val result = useCase(SessionId(""))

            result.shouldBeInstanceOf<AppResult.Failure>()
            result.error.code shouldBe ErrorCode.INVALID_ARGUMENT
        }

        it("returns a Flow on success") {
            every { cliBridge.outputFlow } returns flowOf()

            val result = useCase(testSessionId)

            result.shouldBeInstanceOf<AppResult.Success<Flow<StreamEvent>>>()
        }

        it("creates assistant message with STREAMING status") {
            every { cliBridge.outputFlow } returns flowOf(
                BridgeEvent.Output("Hello".toByteArray()),
            )
            every { outputParser.parse(any()) } returns ParseResult(
                events = listOf(OutputEvent.TurnComplete),
                consumedBytes = 5,
            )

            val result = useCase(testSessionId)
            result.shouldBeInstanceOf<AppResult.Success<Flow<StreamEvent>>>()
            result.value.toList()

            coVerify {
                conversationRepository.insertMessage(match { msg ->
                    msg.id == MessageId(testUuid) &&
                        msg.role == MessageRole.ASSISTANT &&
                        msg.status == MessageStatus.STREAMING &&
                        msg.content == ""
                })
            }
        }

        it("emits MessageCreated as first event") {
            every { cliBridge.outputFlow } returns flowOf(
                BridgeEvent.Output("data".toByteArray()),
            )
            every { outputParser.parse(any()) } returns ParseResult(
                events = listOf(OutputEvent.TurnComplete),
                consumedBytes = 4,
            )

            val result = useCase(testSessionId)
            result.shouldBeInstanceOf<AppResult.Success<Flow<StreamEvent>>>()
            val events = result.value.toList()

            events.first().shouldBeInstanceOf<StreamEvent.MessageCreated>()
            (events.first() as StreamEvent.MessageCreated).messageId shouldBe MessageId(testUuid)
        }

        it("accumulates text content and emits ContentUpdated") {
            every { cliBridge.outputFlow } returns flowOf(
                BridgeEvent.Output("Hello ".toByteArray()),
                BridgeEvent.Output("World".toByteArray()),
            )
            every { outputParser.parse("Hello ".toByteArray()) } returns ParseResult(
                events = listOf(OutputEvent.Text("Hello ")),
                consumedBytes = 6,
            )
            every { outputParser.parse("World".toByteArray()) } returns ParseResult(
                events = listOf(OutputEvent.Text("World"), OutputEvent.TurnComplete),
                consumedBytes = 5,
            )

            val result = useCase(testSessionId)
            result.shouldBeInstanceOf<AppResult.Success<Flow<StreamEvent>>>()
            val events = result.value.toList()

            val contentEvents = events.filterIsInstance<StreamEvent.ContentUpdated>()
            contentEvents[0].content shouldBe "Hello "
            contentEvents[1].content shouldBe "Hello World"
        }

        it("marks message as COMPLETE on TurnComplete event") {
            every { cliBridge.outputFlow } returns flowOf(
                BridgeEvent.Output("done".toByteArray()),
            )
            every { outputParser.parse(any()) } returns ParseResult(
                events = listOf(OutputEvent.Text("done"), OutputEvent.TurnComplete),
                consumedBytes = 4,
            )

            val result = useCase(testSessionId)
            result.shouldBeInstanceOf<AppResult.Success<Flow<StreamEvent>>>()
            val events = result.value.toList()

            events.last().shouldBeInstanceOf<StreamEvent.TurnCompleted>()
            coVerify {
                conversationRepository.updateMessageStatus(MessageId(testUuid), MessageStatus.COMPLETE)
            }
        }

        it("persists content on TurnComplete") {
            every { cliBridge.outputFlow } returns flowOf(
                BridgeEvent.Output("final content".toByteArray()),
            )
            every { outputParser.parse(any()) } returns ParseResult(
                events = listOf(OutputEvent.Text("final content"), OutputEvent.TurnComplete),
                consumedBytes = 13,
            )

            val result = useCase(testSessionId)
            result.shouldBeInstanceOf<AppResult.Success<Flow<StreamEvent>>>()
            result.value.toList()

            coVerify {
                conversationRepository.updateMessageContent(MessageId(testUuid), "final content")
            }
        }

        it("persists content every 2 seconds during streaming") {
            // Simulate time passing beyond 2 seconds
            var callCount = 0
            every { timeProvider.now() } answers {
                callCount++
                when {
                    callCount <= 2 -> baseTime
                    else -> baseTime.plusMillis(2500) // 2.5 seconds later
                }
            }

            every { cliBridge.outputFlow } returns flowOf(
                BridgeEvent.Output("chunk1".toByteArray()),
                BridgeEvent.Output("chunk2".toByteArray()),
            )
            every { outputParser.parse("chunk1".toByteArray()) } returns ParseResult(
                events = listOf(OutputEvent.Text("chunk1")),
                consumedBytes = 6,
            )
            every { outputParser.parse("chunk2".toByteArray()) } returns ParseResult(
                events = listOf(OutputEvent.Text("chunk2"), OutputEvent.TurnComplete),
                consumedBytes = 6,
            )

            val result = useCase(testSessionId)
            result.shouldBeInstanceOf<AppResult.Success<Flow<StreamEvent>>>()
            result.value.toList()

            // Should persist at least once during streaming (when 2s elapsed) plus final persist
            coVerify(atLeast = 1) {
                conversationRepository.updateMessageContent(MessageId(testUuid), any())
            }
        }

        it("does NOT persist content before 2 seconds have elapsed") {
            // Time never advances past 2 seconds
            every { timeProvider.now() } returns baseTime

            every { cliBridge.outputFlow } returns flowOf(
                BridgeEvent.Output("chunk1".toByteArray()),
                BridgeEvent.Output("chunk2".toByteArray()),
                BridgeEvent.Output("done".toByteArray()),
            )
            every { outputParser.parse("chunk1".toByteArray()) } returns ParseResult(
                events = listOf(OutputEvent.Text("chunk1")),
                consumedBytes = 6,
            )
            every { outputParser.parse("chunk2".toByteArray()) } returns ParseResult(
                events = listOf(OutputEvent.Text("chunk2")),
                consumedBytes = 6,
            )
            every { outputParser.parse("done".toByteArray()) } returns ParseResult(
                events = listOf(OutputEvent.TurnComplete),
                consumedBytes = 4,
            )

            val result = useCase(testSessionId)
            result.shouldBeInstanceOf<AppResult.Success<Flow<StreamEvent>>>()
            result.value.toList()

            // Only the final persist on TurnComplete should happen (not intermediate ones)
            coVerify(exactly = 1) {
                conversationRepository.updateMessageContent(MessageId(testUuid), any())
            }
        }

        it("persists intermediate content when 2 seconds elapse between chunks") {
            // First chunk at time 0, second chunk at time 2.5s, third chunk triggers TurnComplete
            var timeCallCount = 0
            every { timeProvider.now() } answers {
                timeCallCount++
                when {
                    // Initial calls (message creation, first lastPersistTime)
                    timeCallCount <= 2 -> baseTime
                    // First text event check - still within 2s
                    timeCallCount == 3 -> baseTime.plusMillis(500)
                    // Second text event check - past 2s threshold
                    else -> baseTime.plusMillis(2500)
                }
            }

            every { cliBridge.outputFlow } returns flowOf(
                BridgeEvent.Output("first".toByteArray()),
                BridgeEvent.Output("second".toByteArray()),
                BridgeEvent.Output("end".toByteArray()),
            )
            every { outputParser.parse("first".toByteArray()) } returns ParseResult(
                events = listOf(OutputEvent.Text("first")),
                consumedBytes = 5,
            )
            every { outputParser.parse("second".toByteArray()) } returns ParseResult(
                events = listOf(OutputEvent.Text("second")),
                consumedBytes = 6,
            )
            every { outputParser.parse("end".toByteArray()) } returns ParseResult(
                events = listOf(OutputEvent.TurnComplete),
                consumedBytes = 3,
            )

            val result = useCase(testSessionId)
            result.shouldBeInstanceOf<AppResult.Success<Flow<StreamEvent>>>()
            result.value.toList()

            // Should persist twice: once at 2.5s interval and once on TurnComplete
            coVerify(exactly = 2) {
                conversationRepository.updateMessageContent(MessageId(testUuid), any())
            }
        }

        it("marks message as ERROR on OutputEvent.Error") {
            every { cliBridge.outputFlow } returns flowOf(
                BridgeEvent.Output("error data".toByteArray()),
            )
            every { outputParser.parse(any()) } returns ParseResult(
                events = listOf(OutputEvent.Error("Parse failure", null)),
                consumedBytes = 10,
            )

            val result = useCase(testSessionId)
            result.shouldBeInstanceOf<AppResult.Success<Flow<StreamEvent>>>()
            val events = result.value.toList()

            val errorEvent = events.filterIsInstance<StreamEvent.Error>().first()
            errorEvent.reason shouldBe "Parse failure"
            coVerify {
                conversationRepository.updateMessageStatus(MessageId(testUuid), MessageStatus.ERROR)
            }
        }

        it("marks message as ERROR on BridgeEvent.Error") {
            every { cliBridge.outputFlow } returns flowOf(
                BridgeEvent.Error("Bridge failure", RuntimeException("oops")),
            )

            val result = useCase(testSessionId)
            result.shouldBeInstanceOf<AppResult.Success<Flow<StreamEvent>>>()
            val events = result.value.toList()

            val errorEvent = events.filterIsInstance<StreamEvent.Error>().first()
            errorEvent.reason shouldBe "Bridge failure"
            coVerify {
                conversationRepository.updateMessageStatus(MessageId(testUuid), MessageStatus.ERROR)
            }
        }

        it("handles ProcessExited with non-zero exit code as ERROR") {
            every { cliBridge.outputFlow } returns flowOf(
                BridgeEvent.ProcessExited(exitCode = 1, cause = ExitCause.CRASH),
            )

            val result = useCase(testSessionId)
            result.shouldBeInstanceOf<AppResult.Success<Flow<StreamEvent>>>()
            val events = result.value.toList()

            val exitEvent = events.filterIsInstance<StreamEvent.ProcessExited>().first()
            exitEvent.exitCode shouldBe 1
            coVerify {
                conversationRepository.updateMessageStatus(MessageId(testUuid), MessageStatus.ERROR)
            }
        }

        it("handles ProcessExited with zero exit code as COMPLETE") {
            every { cliBridge.outputFlow } returns flowOf(
                BridgeEvent.ProcessExited(exitCode = 0, cause = ExitCause.NORMAL),
            )

            val result = useCase(testSessionId)
            result.shouldBeInstanceOf<AppResult.Success<Flow<StreamEvent>>>()
            result.value.toList()

            coVerify {
                conversationRepository.updateMessageStatus(MessageId(testUuid), MessageStatus.COMPLETE)
            }
        }

        it("emits ToolCallStarted for ToolCallStart events") {
            every { cliBridge.outputFlow } returns flowOf(
                BridgeEvent.Output("tool".toByteArray()),
            )
            every { outputParser.parse(any()) } returns ParseResult(
                events = listOf(
                    OutputEvent.ToolCallStart("read_file", "{\"path\": \"/tmp/test.kt\"}"),
                    OutputEvent.TurnComplete,
                ),
                consumedBytes = 4,
            )

            val result = useCase(testSessionId)
            result.shouldBeInstanceOf<AppResult.Success<Flow<StreamEvent>>>()
            val events = result.value.toList()

            val toolEvent = events.filterIsInstance<StreamEvent.ToolCallStarted>().first()
            toolEvent.toolName shouldBe "read_file"
            toolEvent.arguments shouldBe "{\"path\": \"/tmp/test.kt\"}"
        }

        it("emits ToolCallCompleted for ToolCallResult events") {
            every { cliBridge.outputFlow } returns flowOf(
                BridgeEvent.Output("result".toByteArray()),
            )
            every { outputParser.parse(any()) } returns ParseResult(
                events = listOf(
                    OutputEvent.ToolCallResult("read_file", "file contents here", true),
                    OutputEvent.TurnComplete,
                ),
                consumedBytes = 6,
            )

            val result = useCase(testSessionId)
            result.shouldBeInstanceOf<AppResult.Success<Flow<StreamEvent>>>()
            val events = result.value.toList()

            val toolEvent = events.filterIsInstance<StreamEvent.ToolCallCompleted>().first()
            toolEvent.toolName shouldBe "read_file"
            toolEvent.result shouldBe "file contents here"
            toolEvent.success shouldBe true
        }

        it("resets output parser before starting collection") {
            every { cliBridge.outputFlow } returns flowOf(
                BridgeEvent.Output("x".toByteArray()),
            )
            every { outputParser.parse(any()) } returns ParseResult(
                events = listOf(OutputEvent.TurnComplete),
                consumedBytes = 1,
            )

            val result = useCase(testSessionId)
            result.shouldBeInstanceOf<AppResult.Success<Flow<StreamEvent>>>()
            result.value.toList()

            coVerify { outputParser.reset() }
        }
    }
})
