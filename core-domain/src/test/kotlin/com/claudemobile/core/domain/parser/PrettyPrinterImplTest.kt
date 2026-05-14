package com.claudemobile.core.domain.parser

import com.claudemobile.core.domain.model.OutputEvent
import com.claudemobile.core.domain.model.StyleHint
import com.claudemobile.core.domain.model.TextStyle
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class PrettyPrinterImplTest : DescribeSpec({

    lateinit var printer: PrettyPrinterImpl

    beforeEach {
        printer = PrettyPrinterImpl()
    }

    describe("eventsToBytes") {
        it("serializes text events preserving content") {
            val events = listOf(OutputEvent.Text("Hello, world!\n"))
            val bytes = printer.eventsToBytes(events)
            String(bytes) shouldBe "Hello, world!\n"
        }

        it("serializes tool call start with arguments") {
            val events = listOf(
                OutputEvent.ToolCallStart("read_file", "{\"path\": \"/tmp/test.txt\"}")
            )
            val bytes = printer.eventsToBytes(events)
            String(bytes) shouldBe "[tool_call_start:read_file]{\"path\": \"/tmp/test.txt\"}\n"
        }

        it("serializes tool call start without arguments") {
            val events = listOf(OutputEvent.ToolCallStart("list_files", ""))
            val bytes = printer.eventsToBytes(events)
            String(bytes) shouldBe "[tool_call_start:list_files]\n"
        }

        it("serializes tool call result with success") {
            val events = listOf(
                OutputEvent.ToolCallResult("read_file", "file contents", true)
            )
            val bytes = printer.eventsToBytes(events)
            String(bytes) shouldBe "[tool_call_result:read_file:success]file contents\n"
        }

        it("serializes tool call result with failure") {
            val events = listOf(
                OutputEvent.ToolCallResult("write_file", "permission denied", false)
            )
            val bytes = printer.eventsToBytes(events)
            String(bytes) shouldBe "[tool_call_result:write_file:failure]permission denied\n"
        }

        it("serializes prompt events") {
            val events = listOf(OutputEvent.Prompt("claude> "))
            val bytes = printer.eventsToBytes(events)
            String(bytes) shouldBe "claude> \n"
        }

        it("serializes turn complete") {
            val events = listOf(OutputEvent.TurnComplete)
            val bytes = printer.eventsToBytes(events)
            String(bytes) shouldBe "[turn_complete]\n"
        }

        it("skips error events") {
            val events = listOf(
                OutputEvent.Text("before\n"),
                OutputEvent.Error("some error", null),
                OutputEvent.Text("after\n")
            )
            val bytes = printer.eventsToBytes(events)
            String(bytes) shouldBe "before\nafter\n"
        }

        it("serializes mixed event sequence") {
            val events = listOf(
                OutputEvent.Text("Thinking...\n"),
                OutputEvent.ToolCallStart("bash", "{\"cmd\": \"ls\"}"),
                OutputEvent.ToolCallResult("bash", "file1.txt\nfile2.txt", true),
                OutputEvent.Text("Done.\n"),
                OutputEvent.TurnComplete
            )
            val bytes = printer.eventsToBytes(events)
            val expected = "Thinking...\n" +
                "[tool_call_start:bash]{\"cmd\": \"ls\"}\n" +
                "[tool_call_result:bash:success]file1.txt\nfile2.txt\n" +
                "Done.\n" +
                "[turn_complete]\n"
            String(bytes) shouldBe expected
        }

        it("round-trips through parser") {
            val events = listOf(
                OutputEvent.Text("Hello\n"),
                OutputEvent.ToolCallStart("read_file", "{\"path\": \"test.kt\"}"),
                OutputEvent.ToolCallResult("read_file", "fun main() {}", true),
                OutputEvent.TurnComplete
            )
            val bytes = printer.eventsToBytes(events)
            val parser = OutputParserImpl()
            val result = parser.parse(bytes)

            result.events.size shouldBe 4
            (result.events[0] as OutputEvent.Text).content shouldBe "Hello\n"
            (result.events[1] as OutputEvent.ToolCallStart).toolName shouldBe "read_file"
            (result.events[2] as OutputEvent.ToolCallResult).toolName shouldBe "read_file"
            result.events[3] shouldBe OutputEvent.TurnComplete
        }

        it("serializes text with BOLD style hint and round-trips correctly") {
            val events = listOf(
                OutputEvent.Text(
                    content = "Hello bold world\n",
                    styleHints = listOf(StyleHint(range = 6..9, style = TextStyle.BOLD))
                )
            )
            val bytes = printer.eventsToBytes(events)
            val output = String(bytes)
            output shouldContain "\u001B[1m"
            output shouldContain "\u001B[22m"

            // Round-trip: re-parse should produce equivalent text content
            val parser = OutputParserImpl()
            val result = parser.parse(bytes)
            result.events.size shouldBe 1
            val textEvent = result.events[0] as OutputEvent.Text
            textEvent.content shouldBe "Hello bold world\n"
            textEvent.styleHints.size shouldBe 1
            textEvent.styleHints[0].range shouldBe (6..9)
            textEvent.styleHints[0].style shouldBe TextStyle.BOLD
        }

        it("serializes text with ITALIC style hint") {
            val events = listOf(
                OutputEvent.Text(
                    content = "Hello italic\n",
                    styleHints = listOf(StyleHint(range = 6..11, style = TextStyle.ITALIC))
                )
            )
            val bytes = printer.eventsToBytes(events)
            val output = String(bytes)
            output shouldContain "\u001B[3m"
            output shouldContain "\u001B[23m"
        }

        it("handles overlapping BOLD and DIM with different ranges") {
            // BOLD covers positions 0..4, DIM covers positions 2..6
            // Note: ANSI SGR code ESC[22m closes BOTH bold and dim simultaneously.
            // When BOLD ends at position 5 while DIM is still active, the PrettyPrinter
            // must emit ESC[22m (closing both) then re-open DIM with ESC[2m.
            // This causes the parser to produce two DIM ranges: (2..4) and (5..6)
            // instead of a single (2..6). This is a known limitation of ANSI encoding.
            val events = listOf(
                OutputEvent.Text(
                    content = "abcdefgh\n",
                    styleHints = listOf(
                        StyleHint(range = 0..4, style = TextStyle.BOLD),
                        StyleHint(range = 2..6, style = TextStyle.DIM)
                    )
                )
            )
            val bytes = printer.eventsToBytes(events)

            // Round-trip: re-parse should produce equivalent styled characters
            val parser = OutputParserImpl()
            val result = parser.parse(bytes)
            result.events.size shouldBe 1
            val textEvent = result.events[0] as OutputEvent.Text
            textEvent.content shouldBe "abcdefgh\n"

            // Verify BOLD hint is preserved
            val boldHints = textEvent.styleHints.filter { it.style == TextStyle.BOLD }
            boldHints.size shouldBe 1
            boldHints[0].range shouldBe (0..4)

            // Verify DIM coverage is preserved (may be split due to shared ESC[22m close code)
            val dimHints = textEvent.styleHints.filter { it.style == TextStyle.DIM }
            // DIM gets split into two ranges: before BOLD close and after BOLD close
            dimHints.size shouldBe 2
            dimHints[0].range shouldBe (2..4)
            dimHints[1].range shouldBe (5..6)
        }

        it("skips CODE style hints during serialization") {
            val events = listOf(
                OutputEvent.Text(
                    content = "Hello code world\n",
                    styleHints = listOf(StyleHint(range = 6..9, style = TextStyle.CODE))
                )
            )
            val bytes = printer.eventsToBytes(events)
            // CODE style should not produce any ANSI codes
            String(bytes) shouldBe "Hello code world\n"
        }
    }

    describe("extractPlainText") {
        it("extracts plain text from simple text event") {
            val events = listOf(OutputEvent.Text("Hello, world!\n"))
            printer.extractPlainText(events) shouldBe "Hello, world!\n"
        }

        it("strips markdown headings") {
            val events = listOf(OutputEvent.Text("# Heading 1\n"))
            printer.extractPlainText(events) shouldBe "Heading 1\n"
        }

        it("strips multiple heading levels") {
            val events = listOf(
                OutputEvent.Text("## Second Level\n"),
                OutputEvent.Text("### Third Level\n")
            )
            val result = printer.extractPlainText(events)
            result shouldContain "Second Level"
            result shouldContain "Third Level"
            result shouldNotContain "##"
            result shouldNotContain "###"
        }

        it("strips bold markdown") {
            val events = listOf(OutputEvent.Text("This is **bold** text\n"))
            printer.extractPlainText(events) shouldBe "This is bold text\n"
        }

        it("strips italic markdown with asterisks") {
            val events = listOf(OutputEvent.Text("This is *italic* text\n"))
            printer.extractPlainText(events) shouldBe "This is italic text\n"
        }

        it("strips italic markdown with underscores") {
            val events = listOf(OutputEvent.Text("This is _italic_ text\n"))
            printer.extractPlainText(events) shouldBe "This is italic text\n"
        }

        it("strips inline code backticks") {
            val events = listOf(OutputEvent.Text("Use `println()` to print\n"))
            printer.extractPlainText(events) shouldBe "Use println() to print\n"
        }

        it("strips code fence markers but preserves code content") {
            val events = listOf(
                OutputEvent.Text("```kotlin\nfun main() {\n    println(\"hello\")\n}\n```\n")
            )
            val result = printer.extractPlainText(events)
            result shouldContain "fun main()"
            result shouldContain "println(\"hello\")"
            result shouldNotContain "```"
        }

        it("strips unordered list markers") {
            val events = listOf(
                OutputEvent.Text("- item one\n- item two\n")
            )
            val result = printer.extractPlainText(events)
            result shouldContain "item one"
            result shouldContain "item two"
            result shouldNotContain "- "
        }

        it("strips ordered list markers") {
            val events = listOf(
                OutputEvent.Text("1. first\n2. second\n")
            )
            val result = printer.extractPlainText(events)
            result shouldContain "first"
            result shouldContain "second"
            result shouldNotContain "1."
            result shouldNotContain "2."
        }

        it("strips links preserving link text") {
            val events = listOf(
                OutputEvent.Text("Visit [Google](https://google.com) for search\n")
            )
            val result = printer.extractPlainText(events)
            result shouldContain "Google"
            result shouldContain "for search"
            result shouldNotContain "https://google.com"
            result shouldNotContain "["
            result shouldNotContain "]"
        }

        it("strips images preserving alt text") {
            val events = listOf(
                OutputEvent.Text("See ![diagram](image.png) here\n")
            )
            val result = printer.extractPlainText(events)
            result shouldContain "diagram"
            result shouldNotContain "image.png"
            result shouldNotContain "!["
        }

        it("strips table syntax preserving cell content") {
            val events = listOf(
                OutputEvent.Text("| Name | Age |\n|------|-----|\n| Alice | 30 |\n")
            )
            val result = printer.extractPlainText(events)
            result shouldContain "Name"
            result shouldContain "Age"
            result shouldContain "Alice"
            result shouldContain "30"
        }

        it("strips blockquote markers") {
            val events = listOf(OutputEvent.Text("> This is a quote\n"))
            val result = printer.extractPlainText(events)
            result shouldContain "This is a quote"
            result shouldNotContain "> "
        }

        it("strips strikethrough") {
            val events = listOf(OutputEvent.Text("This is ~~deleted~~ text\n"))
            printer.extractPlainText(events) shouldBe "This is deleted text\n"
        }

        it("strips horizontal rules") {
            val events = listOf(
                OutputEvent.Text("above\n---\nbelow\n")
            )
            val result = printer.extractPlainText(events)
            result shouldContain "above"
            result shouldContain "below"
        }

        it("handles tool call start events") {
            val events = listOf(
                OutputEvent.ToolCallStart("read_file", "{\"path\": \"test.kt\"}")
            )
            val result = printer.extractPlainText(events)
            result shouldContain "read_file"
            result shouldContain "{\"path\": \"test.kt\"}"
        }

        it("handles tool call result events") {
            val events = listOf(
                OutputEvent.ToolCallResult("bash", "output text", true)
            )
            val result = printer.extractPlainText(events)
            result shouldContain "bash"
            result shouldContain "output text"
        }

        it("handles prompt events") {
            val events = listOf(OutputEvent.Prompt("claude> "))
            printer.extractPlainText(events) shouldBe "claude> "
        }

        it("skips turn complete events") {
            val events = listOf(
                OutputEvent.Text("text\n"),
                OutputEvent.TurnComplete
            )
            printer.extractPlainText(events) shouldBe "text\n"
        }

        it("handles error events") {
            val events = listOf(OutputEvent.Error("parse error", null))
            printer.extractPlainText(events) shouldBe "parse error"
        }

        it("handles complex mixed markdown") {
            val events = listOf(
                OutputEvent.Text("# Title\n\nThis has **bold** and *italic* and `code`.\n\n- List item\n")
            )
            val result = printer.extractPlainText(events)
            result shouldContain "Title"
            result shouldContain "bold"
            result shouldContain "italic"
            result shouldContain "code"
            result shouldContain "List item"
            result shouldNotContain "#"
            result shouldNotContain "**"
            result shouldNotContain "*italic*"
            result shouldNotContain "`"
            result shouldNotContain "- "
        }
    }
})
