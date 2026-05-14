package com.claudemobile.core.ui.markdown

import com.claudemobile.core.domain.model.OutputEvent
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class MarkdownRendererTest : DescribeSpec({

    describe("renderToAnnotatedString") {
        it("renders plain text") {
            val events = listOf(OutputEvent.Text("Hello, world!\n"))
            val result = MarkdownRenderer.renderToAnnotatedString(events)
            result.text shouldContain "Hello, world!"
        }

        it("renders heading text without hash marks") {
            val events = listOf(OutputEvent.Text("# Main Title\n"))
            val result = MarkdownRenderer.renderToAnnotatedString(events)
            result.text shouldContain "Main Title"
            result.text shouldNotContain "#"
        }

        it("renders bold text content") {
            val events = listOf(OutputEvent.Text("This is **bold** text\n"))
            val result = MarkdownRenderer.renderToAnnotatedString(events)
            result.text shouldContain "bold"
            result.text shouldNotContain "**"
        }

        it("renders italic text content") {
            val events = listOf(OutputEvent.Text("This is *italic* text\n"))
            val result = MarkdownRenderer.renderToAnnotatedString(events)
            result.text shouldContain "italic"
        }

        it("renders inline code content") {
            val events = listOf(OutputEvent.Text("Use `println()` here\n"))
            val result = MarkdownRenderer.renderToAnnotatedString(events)
            result.text shouldContain "println()"
            result.text shouldNotContain "`"
        }

        it("renders code fence content without fence markers") {
            val events = listOf(
                OutputEvent.Text("```kotlin\nfun main() {}\n```\n")
            )
            val result = MarkdownRenderer.renderToAnnotatedString(events)
            result.text shouldContain "fun main() {}"
            result.text shouldNotContain "```"
        }

        it("renders code fence with language label") {
            val events = listOf(
                OutputEvent.Text("```python\nprint('hello')\n```\n")
            )
            val result = MarkdownRenderer.renderToAnnotatedString(events)
            result.text shouldContain "python"
            result.text shouldContain "print('hello')"
        }

        it("renders unordered list with bullet points") {
            val events = listOf(OutputEvent.Text("- item one\n- item two\n"))
            val result = MarkdownRenderer.renderToAnnotatedString(events)
            result.text shouldContain "•"
            result.text shouldContain "item one"
            result.text shouldContain "item two"
        }

        it("renders ordered list preserving numbers") {
            val events = listOf(OutputEvent.Text("1. first\n2. second\n"))
            val result = MarkdownRenderer.renderToAnnotatedString(events)
            result.text shouldContain "1."
            result.text shouldContain "first"
            result.text shouldContain "2."
            result.text shouldContain "second"
        }

        it("renders links with text visible") {
            val events = listOf(
                OutputEvent.Text("[Google](https://google.com)\n")
            )
            val result = MarkdownRenderer.renderToAnnotatedString(events)
            result.text shouldContain "Google"
            result.text shouldNotContain "https://google.com"
        }

        it("renders blockquotes with visual indicator") {
            val events = listOf(OutputEvent.Text("> A wise quote\n"))
            val result = MarkdownRenderer.renderToAnnotatedString(events)
            result.text shouldContain "│"
            result.text shouldContain "A wise quote"
        }

        it("renders tool call start events") {
            val events = listOf(
                OutputEvent.ToolCallStart("read_file", "{\"path\": \"test.kt\"}")
            )
            val result = MarkdownRenderer.renderToAnnotatedString(events)
            result.text shouldContain "read_file"
        }

        it("renders tool call result with success indicator") {
            val events = listOf(
                OutputEvent.ToolCallResult("bash", "output", true)
            )
            val result = MarkdownRenderer.renderToAnnotatedString(events)
            result.text shouldContain "✓"
            result.text shouldContain "bash"
        }

        it("renders tool call result with failure indicator") {
            val events = listOf(
                OutputEvent.ToolCallResult("bash", "error", false)
            )
            val result = MarkdownRenderer.renderToAnnotatedString(events)
            result.text shouldContain "✗"
            result.text shouldContain "bash"
        }

        it("renders prompt events") {
            val events = listOf(OutputEvent.Prompt("claude> "))
            val result = MarkdownRenderer.renderToAnnotatedString(events)
            result.text shouldContain "claude> "
        }

        it("renders error events") {
            val events = listOf(OutputEvent.Error("parse error", null))
            val result = MarkdownRenderer.renderToAnnotatedString(events)
            result.text shouldContain "Error: parse error"
        }

        it("skips turn complete events") {
            val events = listOf(
                OutputEvent.Text("text\n"),
                OutputEvent.TurnComplete
            )
            val result = MarkdownRenderer.renderToAnnotatedString(events)
            result.text shouldContain "text"
        }

        it("renders table rows with cell content") {
            val events = listOf(
                OutputEvent.Text("| Name | Age |\n|------|-----|\n| Alice | 30 |\n")
            )
            val result = MarkdownRenderer.renderToAnnotatedString(events)
            result.text shouldContain "Name"
            result.text shouldContain "Age"
            result.text shouldContain "Alice"
            result.text shouldContain "30"
        }

        it("renders strikethrough text") {
            val events = listOf(OutputEvent.Text("This is ~~deleted~~ text\n"))
            val result = MarkdownRenderer.renderToAnnotatedString(events)
            result.text shouldContain "deleted"
            result.text shouldNotContain "~~"
        }

        it("renders horizontal rule as visual divider") {
            val events = listOf(OutputEvent.Text("---\n"))
            val result = MarkdownRenderer.renderToAnnotatedString(events)
            result.text shouldContain "───"
        }
    }

    describe("isSupportedLanguage") {
        it("recognizes kotlin") {
            MarkdownRenderer.isSupportedLanguage("kotlin") shouldBe true
            MarkdownRenderer.isSupportedLanguage("kt") shouldBe true
        }

        it("recognizes java") {
            MarkdownRenderer.isSupportedLanguage("java") shouldBe true
        }

        it("recognizes python") {
            MarkdownRenderer.isSupportedLanguage("python") shouldBe true
            MarkdownRenderer.isSupportedLanguage("py") shouldBe true
        }

        it("recognizes javascript and typescript") {
            MarkdownRenderer.isSupportedLanguage("javascript") shouldBe true
            MarkdownRenderer.isSupportedLanguage("js") shouldBe true
            MarkdownRenderer.isSupportedLanguage("typescript") shouldBe true
            MarkdownRenderer.isSupportedLanguage("ts") shouldBe true
        }

        it("recognizes rust") {
            MarkdownRenderer.isSupportedLanguage("rust") shouldBe true
            MarkdownRenderer.isSupportedLanguage("rs") shouldBe true
        }

        it("recognizes go") {
            MarkdownRenderer.isSupportedLanguage("go") shouldBe true
            MarkdownRenderer.isSupportedLanguage("golang") shouldBe true
        }

        it("recognizes bash/shell") {
            MarkdownRenderer.isSupportedLanguage("bash") shouldBe true
            MarkdownRenderer.isSupportedLanguage("sh") shouldBe true
            MarkdownRenderer.isSupportedLanguage("shell") shouldBe true
        }

        it("returns false for unsupported languages") {
            MarkdownRenderer.isSupportedLanguage("haskell") shouldBe false
            MarkdownRenderer.isSupportedLanguage("cobol") shouldBe false
            MarkdownRenderer.isSupportedLanguage("") shouldBe false
        }
    }
})
