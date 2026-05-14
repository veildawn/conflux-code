package com.claudemobile.core.domain.parser

import com.claudemobile.core.domain.model.OutputEvent
import com.claudemobile.core.domain.model.StyleHint
import com.claudemobile.core.domain.model.TextStyle
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class OutputParserImplTest : DescribeSpec({

    lateinit var parser: OutputParserImpl

    beforeEach {
        parser = OutputParserImpl()
    }

    describe("empty and basic input") {
        it("returns empty result for empty buffer") {
            val result = parser.parse(byteArrayOf())
            result.events.shouldBeEmpty()
            result.remainingBuffer shouldBe byteArrayOf()
        }

        it("parses plain text line") {
            val input = "Hello, world!\n".toByteArray()
            val result = parser.parse(input)

            result.events shouldHaveSize 1
            result.remainingBuffer shouldBe byteArrayOf()

            val event = result.events[0]
            event.shouldBeInstanceOf<OutputEvent.Text>()
            event.content shouldBe "Hello, world!\n"
            event.styleHints.shouldBeEmpty()
        }

        it("does not consume incomplete line without newline") {
            val input = "incomplete".toByteArray()
            val result = parser.parse(input)

            result.events.shouldBeEmpty()
            result.remainingBuffer shouldBe input
        }

        it("parses multiple lines") {
            val input = "line1\nline2\n".toByteArray()
            val result = parser.parse(input)

            result.events shouldHaveSize 2
            result.remainingBuffer shouldBe byteArrayOf()

            (result.events[0] as OutputEvent.Text).content shouldBe "line1\n"
            (result.events[1] as OutputEvent.Text).content shouldBe "line2\n"
        }
    }

    describe("ANSI escape sequence stripping") {
        it("strips simple ANSI reset sequence") {
            val input = "\u001b[0mHello\n".toByteArray()
            val result = parser.parse(input)

            result.events shouldHaveSize 1
            val event = result.events[0] as OutputEvent.Text
            event.content shouldBe "Hello\n"
        }

        it("strips bold ANSI sequence and produces style hint") {
            val input = "\u001b[1mbold text\u001b[0m\n".toByteArray()
            val result = parser.parse(input)

            result.events shouldHaveSize 1
            val event = result.events[0] as OutputEvent.Text
            event.content shouldBe "bold text\n"
            event.styleHints shouldHaveSize 1
            event.styleHints[0].style shouldBe TextStyle.BOLD
            event.styleHints[0].range shouldBe (0 until 9)
        }

        it("strips italic ANSI sequence and produces style hint") {
            val input = "\u001b[3mitalic\u001b[23m rest\n".toByteArray()
            val result = parser.parse(input)

            result.events shouldHaveSize 1
            val event = result.events[0] as OutputEvent.Text
            event.content shouldBe "italic rest\n"
            event.styleHints shouldHaveSize 1
            event.styleHints[0].style shouldBe TextStyle.ITALIC
            event.styleHints[0].range shouldBe (0 until 6)
        }

        it("handles multiple styles in one line") {
            val input = "\u001b[1mbold\u001b[3m and italic\u001b[0m\n".toByteArray()
            val result = parser.parse(input)

            result.events shouldHaveSize 1
            val event = result.events[0] as OutputEvent.Text
            event.content shouldBe "bold and italic\n"
            event.styleHints shouldHaveSize 2
        }

        it("strips dim style") {
            val input = "\u001b[2mdim text\u001b[22m\n".toByteArray()
            val result = parser.parse(input)

            val event = result.events[0] as OutputEvent.Text
            event.content shouldBe "dim text\n"
            event.styleHints shouldHaveSize 1
            event.styleHints[0].style shouldBe TextStyle.DIM
        }

        it("strips underline style") {
            val input = "\u001b[4munderlined\u001b[24m\n".toByteArray()
            val result = parser.parse(input)

            val event = result.events[0] as OutputEvent.Text
            event.content shouldBe "underlined\n"
            event.styleHints shouldHaveSize 1
            event.styleHints[0].style shouldBe TextStyle.UNDERLINE
        }

        it("strips strikethrough style") {
            val input = "\u001b[9mstruck\u001b[29m\n".toByteArray()
            val result = parser.parse(input)

            val event = result.events[0] as OutputEvent.Text
            event.content shouldBe "struck\n"
            event.styleHints shouldHaveSize 1
            event.styleHints[0].style shouldBe TextStyle.STRIKETHROUGH
        }

        it("handles non-SGR ANSI sequences (cursor movement etc) by stripping them") {
            // ESC[2J is clear screen — not SGR, should be stripped without style hints
            val input = "\u001b[2JHello\n".toByteArray()
            val result = parser.parse(input)

            result.events shouldHaveSize 1
            val event = result.events[0] as OutputEvent.Text
            event.content shouldBe "Hello\n"
            event.styleHints.shouldBeEmpty()
        }
    }

    describe("tool-call sentinel detection") {
        it("detects tool_call_start sentinel") {
            val input = "[tool_call_start:read_file]{\"path\": \"/tmp/test.txt\"}\n".toByteArray()
            val result = parser.parse(input)

            result.events shouldHaveSize 1
            val event = result.events[0]
            event.shouldBeInstanceOf<OutputEvent.ToolCallStart>()
            event.toolName shouldBe "read_file"
            event.arguments shouldBe "{\"path\": \"/tmp/test.txt\"}"
        }

        it("detects tool_call_start with no arguments") {
            val input = "[tool_call_start:list_files]\n".toByteArray()
            val result = parser.parse(input)

            result.events shouldHaveSize 1
            val event = result.events[0] as OutputEvent.ToolCallStart
            event.toolName shouldBe "list_files"
            event.arguments shouldBe ""
        }

        it("detects tool_call_result with success") {
            val input = "[tool_call_result:read_file:success]file contents here\n".toByteArray()
            val result = parser.parse(input)

            result.events shouldHaveSize 1
            val event = result.events[0]
            event.shouldBeInstanceOf<OutputEvent.ToolCallResult>()
            event.toolName shouldBe "read_file"
            event.result shouldBe "file contents here"
            event.success shouldBe true
        }

        it("detects tool_call_result with failure") {
            val input = "[tool_call_result:write_file:failure]permission denied\n".toByteArray()
            val result = parser.parse(input)

            result.events shouldHaveSize 1
            val event = result.events[0] as OutputEvent.ToolCallResult
            event.toolName shouldBe "write_file"
            event.result shouldBe "permission denied"
            event.success shouldBe false
        }

        it("detects tool_call_start with ANSI codes stripped") {
            val input = "\u001b[1m[tool_call_start:bash]{\"cmd\": \"ls\"}\u001b[0m\n".toByteArray()
            val result = parser.parse(input)

            result.events shouldHaveSize 1
            val event = result.events[0] as OutputEvent.ToolCallStart
            event.toolName shouldBe "bash"
            event.arguments shouldBe "{\"cmd\": \"ls\"}"
        }
    }

    describe("prompt line detection") {
        it("detects simple prompt '> '") {
            val input = "> \n".toByteArray()
            val result = parser.parse(input)

            result.events shouldHaveSize 1
            val event = result.events[0]
            event.shouldBeInstanceOf<OutputEvent.Prompt>()
            event.text shouldBe "> "
        }

        it("detects named prompt 'claude> '") {
            val input = "claude> \n".toByteArray()
            val result = parser.parse(input)

            result.events shouldHaveSize 1
            val event = result.events[0] as OutputEvent.Prompt
            event.text shouldBe "claude> "
        }

        it("detects prompt without trailing space") {
            val input = "claude>\n".toByteArray()
            val result = parser.parse(input)

            result.events shouldHaveSize 1
            val event = result.events[0] as OutputEvent.Prompt
            event.text shouldBe "claude>"
        }

        it("does not treat text with '>' in middle as prompt") {
            val input = "hello > world\n".toByteArray()
            val result = parser.parse(input)

            result.events shouldHaveSize 1
            result.events[0].shouldBeInstanceOf<OutputEvent.Text>()
        }
    }

    describe("turn-completion marker detection") {
        it("detects turn_complete marker") {
            val input = "[turn_complete]\n".toByteArray()
            val result = parser.parse(input)

            result.events shouldHaveSize 1
            result.events[0] shouldBe OutputEvent.TurnComplete
        }

        it("detects turn_complete with ANSI codes stripped") {
            val input = "\u001b[0m[turn_complete]\u001b[0m\n".toByteArray()
            val result = parser.parse(input)

            result.events shouldHaveSize 1
            result.events[0] shouldBe OutputEvent.TurnComplete
        }
    }

    describe("incremental parsing") {
        it("tracks consumedBytes accurately") {
            val input = "line1\nincomplete".toByteArray()
            val result = parser.parse(input)

            result.events shouldHaveSize 1
            result.consumedBytes shouldBe 6 // "line1\n" = 6 bytes
        }

        it("handles incremental buffer feeding") {
            val part1 = "hel".toByteArray()
            val part2 = "lo\n".toByteArray()

            val result1 = parser.parse(part1)
            result1.events.shouldBeEmpty()
            result1.consumedBytes shouldBe 0

            // Simulate caller prepending unconsumed bytes
            val combined = part1 + part2
            val result2 = parser.parse(combined)
            result2.events shouldHaveSize 1
            (result2.events[0] as OutputEvent.Text).content shouldBe "hello\n"
            result2.consumedBytes shouldBe combined.size
        }

        it("preserves style state across parse calls") {
            // First call: bold is activated but line ends
            val input1 = "\u001b[1mbold start\n".toByteArray()
            val result1 = parser.parse(input1)

            result1.events shouldHaveSize 1
            val event1 = result1.events[0] as OutputEvent.Text
            event1.content shouldBe "bold start\n"
            // Style hint should cover the text since bold was never deactivated
            event1.styleHints shouldHaveSize 1
            event1.styleHints[0].style shouldBe TextStyle.BOLD

            // Second call: bold continues
            val input2 = "still bold\u001b[0m\n".toByteArray()
            val result2 = parser.parse(input2)

            result2.events shouldHaveSize 1
            val event2 = result2.events[0] as OutputEvent.Text
            event2.content shouldBe "still bold\n"
            event2.styleHints shouldHaveSize 1
            event2.styleHints[0].style shouldBe TextStyle.BOLD
            event2.styleHints[0].range shouldBe (0 until 10)
        }
    }

    describe("error recovery") {
        it("does not emit error for valid content") {
            val input = "normal text\n[turn_complete]\n".toByteArray()
            val result = parser.parse(input)

            result.events.none { it is OutputEvent.Error } shouldBe true
        }

        it("emits error for malformed tool_call_start sentinel") {
            val input = "[tool_call_start:missing_bracket\n".toByteArray()
            val result = parser.parse(input)

            result.events shouldHaveSize 1
            val event = result.events[0]
            event.shouldBeInstanceOf<OutputEvent.Error>()
            event.reason shouldBe "Malformed sentinel: does not match expected grammar"
            event.rawBytes shouldBe input
        }

        it("emits error for malformed tool_call_result sentinel") {
            val input = "[tool_call_result:toolname\n".toByteArray()
            val result = parser.parse(input)

            result.events shouldHaveSize 1
            result.events[0].shouldBeInstanceOf<OutputEvent.Error>()
        }

        it("resynchronizes after error on next valid frame") {
            val input = "[tool_call_start:no_bracket\nHello valid text\n[turn_complete]\n".toByteArray()
            val result = parser.parse(input)

            result.events shouldHaveSize 3
            result.events[0].shouldBeInstanceOf<OutputEvent.Error>()
            result.events[1].shouldBeInstanceOf<OutputEvent.Text>()
            result.events[2] shouldBe OutputEvent.TurnComplete
            result.consumedBytes shouldBe input.size
        }

        it("emits error for malformed turn marker") {
            val input = "[turn_incomplet\n".toByteArray()
            val result = parser.parse(input)

            result.events shouldHaveSize 1
            result.events[0].shouldBeInstanceOf<OutputEvent.Error>()
        }
    }

    describe("reset") {
        it("clears active styles on reset") {
            // Activate bold
            val input1 = "\u001b[1mbold\n".toByteArray()
            parser.parse(input1)

            // Reset
            parser.reset()

            // After reset, no bold style should carry over
            val input2 = "not bold\n".toByteArray()
            val result = parser.parse(input2)

            result.events shouldHaveSize 1
            val event = result.events[0] as OutputEvent.Text
            event.styleHints.shouldBeEmpty()
        }
    }

    describe("mixed content") {
        it("parses a realistic output sequence") {
            val input = buildString {
                append("\u001b[1mClaude\u001b[0m is thinking...\n")
                append("[tool_call_start:read_file]{\"path\": \"src/main.kt\"}\n")
                append("[tool_call_result:read_file:success]fun main() {}\n")
                append("Here is the result.\n")
                append("[turn_complete]\n")
            }.toByteArray()

            val result = parser.parse(input)

            result.events shouldHaveSize 5
            result.consumedBytes shouldBe input.size

            result.events[0].shouldBeInstanceOf<OutputEvent.Text>()
            result.events[1].shouldBeInstanceOf<OutputEvent.ToolCallStart>()
            result.events[2].shouldBeInstanceOf<OutputEvent.ToolCallResult>()
            result.events[3].shouldBeInstanceOf<OutputEvent.Text>()
            result.events[4] shouldBe OutputEvent.TurnComplete
        }
    }
})
