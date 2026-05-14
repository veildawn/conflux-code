package com.claudemobile.core.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.claudemobile.core.domain.model.OutputEvent

/**
 * Renders [OutputEvent] lists into Compose [AnnotatedString] with markdown formatting.
 *
 * Supports:
 * - Headings (# through ######)
 * - Bold (**text** and __text__)
 * - Italic (*text* and _text_)
 * - Strikethrough (~~text~~)
 * - Inline code (`code`)
 * - Code fences (```language ... ```) with syntax highlighting
 * - Unordered lists (- or * prefix)
 * - Ordered lists (1. 2. etc.)
 * - Links ([text](url))
 * - Tables (| col | col |)
 * - Blockquotes (> text)
 */
public object MarkdownRenderer {

    /**
     * Renders a list of output events into an [AnnotatedString] with markdown formatting.
     */
    public fun renderToAnnotatedString(events: List<OutputEvent>): AnnotatedString {
        return buildAnnotatedString {
            for (event in events) {
                when (event) {
                    is OutputEvent.Text -> {
                        renderMarkdownText(event.content)
                    }
                    is OutputEvent.ToolCallStart -> {
                        renderToolCallStart(event)
                    }
                    is OutputEvent.ToolCallResult -> {
                        renderToolCallResult(event)
                    }
                    is OutputEvent.Prompt -> {
                        pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = PromptColor))
                        append(event.text)
                        pop()
                    }
                    is OutputEvent.TurnComplete -> {
                        // No visual representation
                    }
                    is OutputEvent.Error -> {
                        pushStyle(SpanStyle(color = ErrorColor))
                        append("Error: ${event.reason}")
                        pop()
                    }
                }
            }
        }
    }

    private fun AnnotatedString.Builder.renderToolCallStart(event: OutputEvent.ToolCallStart) {
        pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = ToolCallColor))
        append("⚙ ${event.toolName}")
        pop()
        if (event.arguments.isNotEmpty()) {
            append(" ")
            pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = ToolArgColor))
            append(event.arguments)
            pop()
        }
    }

    private fun AnnotatedString.Builder.renderToolCallResult(event: OutputEvent.ToolCallResult) {
        val statusColor = if (event.success) SuccessColor else ErrorColor
        val statusIcon = if (event.success) "✓" else "✗"
        pushStyle(SpanStyle(color = statusColor))
        append("$statusIcon ${event.toolName}")
        pop()
        if (event.result.isNotEmpty()) {
            append(": ")
            pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
            append(event.result)
            pop()
        }
    }

    /**
     * Renders markdown-formatted text content into the [AnnotatedString.Builder].
     * Processes the text line-by-line to handle block-level elements, then applies
     * inline formatting within each line.
     */
    private fun AnnotatedString.Builder.renderMarkdownText(content: String) {
        val lines = content.split('\n')
        var inCodeFence = false
        var codeFenceLanguage = ""
        val codeBlockLines = mutableListOf<String>()

        for ((index, line) in lines.withIndex()) {
            if (inCodeFence) {
                if (CODE_FENCE_REGEX.matches(line.trimEnd())) {
                    // End of code fence — render accumulated code block
                    inCodeFence = false
                    renderCodeBlock(codeBlockLines.joinToString("\n"), codeFenceLanguage)
                    codeBlockLines.clear()
                    codeFenceLanguage = ""
                } else {
                    codeBlockLines.add(line)
                }
            } else if (CODE_FENCE_REGEX.matches(line.trimEnd())) {
                // Start of code fence
                inCodeFence = true
                codeFenceLanguage = extractLanguage(line.trimEnd())
            } else {
                renderMarkdownLine(line)
            }

            // Add newline between lines (but not after the last one)
            if (index < lines.size - 1 && !inCodeFence) {
                append("\n")
            }
        }

        // Handle unclosed code fence
        if (inCodeFence && codeBlockLines.isNotEmpty()) {
            renderCodeBlock(codeBlockLines.joinToString("\n"), codeFenceLanguage)
        }
    }

    /**
     * Renders a single line of markdown, handling block-level elements.
     */
    private fun AnnotatedString.Builder.renderMarkdownLine(line: String) {
        // Horizontal rule
        if (HORIZONTAL_RULE_REGEX.matches(line.trim())) {
            pushStyle(SpanStyle(color = DividerColor))
            append("───────────────────")
            pop()
            return
        }

        // Table separator — skip
        if (TABLE_SEPARATOR_REGEX.matches(line.trim())) {
            return
        }

        // Heading
        val headingMatch = HEADING_REGEX.matchEntire(line)
        if (headingMatch != null) {
            val level = headingMatch.groupValues[1].length
            val text = headingMatch.groupValues[2]
            val style = headingStyle(level)
            pushStyle(style)
            renderInlineMarkdown(text)
            pop()
            return
        }

        // Blockquote
        val blockquoteMatch = BLOCKQUOTE_REGEX.matchEntire(line)
        if (blockquoteMatch != null) {
            pushStyle(SpanStyle(color = BlockquoteColor, fontStyle = FontStyle.Italic))
            append("│ ")
            renderInlineMarkdown(blockquoteMatch.groupValues[1])
            pop()
            return
        }

        // Unordered list
        val ulMatch = UNORDERED_LIST_REGEX.matchEntire(line)
        if (ulMatch != null) {
            val indent = ulMatch.groupValues[1]
            val content = ulMatch.groupValues[2]
            append(indent)
            append("• ")
            renderInlineMarkdown(content)
            return
        }

        // Ordered list
        val olMatch = ORDERED_LIST_REGEX.matchEntire(line)
        if (olMatch != null) {
            val indent = olMatch.groupValues[1]
            val number = olMatch.groupValues[2]
            val content = olMatch.groupValues[3]
            append(indent)
            append("$number. ")
            renderInlineMarkdown(content)
            return
        }

        // Table row
        if (TABLE_ROW_REGEX.matches(line.trim())) {
            renderTableRow(line.trim())
            return
        }

        // Regular text — apply inline formatting
        renderInlineMarkdown(line)
    }

    /**
     * Renders inline markdown formatting (bold, italic, code, links, etc.)
     * within a text segment.
     */
    private fun AnnotatedString.Builder.renderInlineMarkdown(text: String) {
        var pos = 0
        val length = text.length

        while (pos < length) {
            // Try to match inline patterns at current position
            val remaining = text.substring(pos)

            // Inline code: `code`
            val codeMatch = INLINE_CODE_PATTERN.find(remaining)
            if (codeMatch != null && codeMatch.range.first == 0) {
                pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = CodeBgColor))
                append(codeMatch.groupValues[1])
                pop()
                pos += codeMatch.value.length
                continue
            }

            // Image: ![alt](url) — render alt text
            val imageMatch = IMAGE_PATTERN.find(remaining)
            if (imageMatch != null && imageMatch.range.first == 0) {
                pushStyle(SpanStyle(color = LinkColor))
                append("🖼 ${imageMatch.groupValues[1]}")
                pop()
                pos += imageMatch.value.length
                continue
            }

            // Link: [text](url)
            val linkMatch = LINK_PATTERN.find(remaining)
            if (linkMatch != null && linkMatch.range.first == 0) {
                val linkText = linkMatch.groupValues[1]
                val url = linkMatch.groupValues[2]
                pushStringAnnotation(tag = "URL", annotation = url)
                pushStyle(SpanStyle(color = LinkColor, textDecoration = TextDecoration.Underline))
                append(linkText)
                pop()
                pop()
                pos += linkMatch.value.length
                continue
            }

            // Bold: **text** or __text__
            val boldMatch = BOLD_PATTERN.find(remaining)
            if (boldMatch != null && boldMatch.range.first == 0) {
                val boldContent = boldMatch.groupValues[1].ifEmpty { boldMatch.groupValues[2] }
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                renderInlineMarkdown(boldContent)
                pop()
                pos += boldMatch.value.length
                continue
            }

            // Strikethrough: ~~text~~
            val strikeMatch = STRIKETHROUGH_PATTERN.find(remaining)
            if (strikeMatch != null && strikeMatch.range.first == 0) {
                pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                append(strikeMatch.groupValues[1])
                pop()
                pos += strikeMatch.value.length
                continue
            }

            // Italic: *text* or _text_
            val italicMatch = ITALIC_PATTERN.find(remaining)
            if (italicMatch != null && italicMatch.range.first == 0) {
                val italicContent = italicMatch.groupValues[1].ifEmpty { italicMatch.groupValues[2] }
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                renderInlineMarkdown(italicContent)
                pop()
                pos += italicMatch.value.length
                continue
            }

            // No pattern matched — append character as-is
            append(text[pos])
            pos++
        }
    }

    /**
     * Renders a code block with syntax highlighting based on the language.
     */
    private fun AnnotatedString.Builder.renderCodeBlock(code: String, language: String) {
        append("\n")
        if (language.isNotEmpty()) {
            pushStyle(SpanStyle(fontSize = 10.sp, color = CodeLangColor))
            append(language)
            pop()
            append("\n")
        }

        if (language.isNotEmpty() && isSupportedLanguage(language)) {
            renderHighlightedCode(code, language)
        } else {
            // Fallback to monospace rendering
            pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = CodeBlockBgColor))
            append(code)
            pop()
        }
        append("\n")
    }

    /**
     * Renders syntax-highlighted code for supported languages.
     * Uses a simple keyword-based highlighting approach.
     */
    private fun AnnotatedString.Builder.renderHighlightedCode(code: String, language: String) {
        val keywords = getLanguageKeywords(language)
        val lines = code.split('\n')

        pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = CodeBlockBgColor))

        for ((lineIndex, line) in lines.withIndex()) {
            highlightCodeLine(line, keywords, language)
            if (lineIndex < lines.size - 1) {
                append("\n")
            }
        }

        pop()
    }

    /**
     * Highlights a single line of code with keyword and string/comment detection.
     */
    private fun AnnotatedString.Builder.highlightCodeLine(
        line: String,
        keywords: Set<String>,
        language: String
    ) {
        var pos = 0
        val length = line.length

        while (pos < length) {
            // String literals
            if (line[pos] == '"' || line[pos] == '\'') {
                val quote = line[pos]
                val start = pos
                pos++
                while (pos < length && line[pos] != quote) {
                    if (line[pos] == '\\' && pos + 1 < length) pos++ // skip escaped char
                    pos++
                }
                if (pos < length) pos++ // consume closing quote
                pushStyle(SpanStyle(color = StringColor))
                append(line.substring(start, pos))
                pop()
                continue
            }

            // Line comments
            val commentPrefix = getCommentPrefix(language)
            if (commentPrefix != null && line.startsWith(commentPrefix, pos)) {
                pushStyle(SpanStyle(color = CommentColor))
                append(line.substring(pos))
                pop()
                break
            }

            // Numbers
            if (line[pos].isDigit()) {
                val start = pos
                while (pos < length && (line[pos].isDigit() || line[pos] == '.' || line[pos] == 'x' || line[pos] == 'f')) {
                    pos++
                }
                pushStyle(SpanStyle(color = NumberColor))
                append(line.substring(start, pos))
                pop()
                continue
            }

            // Identifiers / keywords
            if (line[pos].isLetter() || line[pos] == '_') {
                val start = pos
                while (pos < length && (line[pos].isLetterOrDigit() || line[pos] == '_')) {
                    pos++
                }
                val word = line.substring(start, pos)
                if (word in keywords) {
                    pushStyle(SpanStyle(color = KeywordColor))
                    append(word)
                    pop()
                } else {
                    append(word)
                }
                continue
            }

            // Other characters
            append(line[pos])
            pos++
        }
    }

    /**
     * Renders a table row with cell content separated by spaces.
     */
    private fun AnnotatedString.Builder.renderTableRow(row: String) {
        val content = row.removePrefix("|").removeSuffix("|")
        val cells = content.split("|").map { it.trim() }

        pushStyle(SpanStyle(fontFamily = FontFamily.Monospace))
        for ((index, cell) in cells.withIndex()) {
            renderInlineMarkdown(cell)
            if (index < cells.size - 1) {
                append("  │  ")
            }
        }
        pop()
    }

    private fun headingStyle(level: Int): SpanStyle = when (level) {
        1 -> SpanStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold)
        2 -> SpanStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold)
        3 -> SpanStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        4 -> SpanStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        5 -> SpanStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
        else -> SpanStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }

    private fun extractLanguage(fenceLine: String): String {
        val trimmed = fenceLine.trimStart('`').trim()
        return trimmed.takeWhile { it.isLetterOrDigit() || it == '+' || it == '#' }
    }

    /**
     * Returns whether the given language is supported for syntax highlighting.
     */
    public fun isSupportedLanguage(language: String): Boolean {
        return language.lowercase() in SUPPORTED_LANGUAGES
    }

    private fun getLanguageKeywords(language: String): Set<String> = when (language.lowercase()) {
        "kotlin", "kt" -> KOTLIN_KEYWORDS
        "java" -> JAVA_KEYWORDS
        "python", "py" -> PYTHON_KEYWORDS
        "javascript", "js" -> JAVASCRIPT_KEYWORDS
        "typescript", "ts" -> TYPESCRIPT_KEYWORDS
        "rust", "rs" -> RUST_KEYWORDS
        "go", "golang" -> GO_KEYWORDS
        "swift" -> SWIFT_KEYWORDS
        "c", "cpp", "c++" -> C_KEYWORDS
        "bash", "sh", "shell", "zsh" -> BASH_KEYWORDS
        "sql" -> SQL_KEYWORDS
        else -> emptySet()
    }

    private fun getCommentPrefix(language: String): String? = when (language.lowercase()) {
        "kotlin", "kt", "java", "javascript", "js", "typescript", "ts",
        "rust", "rs", "go", "golang", "swift", "c", "cpp", "c++" -> "//"
        "python", "py", "bash", "sh", "shell", "zsh" -> "#"
        "sql" -> "--"
        else -> null
    }

    // Supported languages for syntax highlighting
    private val SUPPORTED_LANGUAGES = setOf(
        "kotlin", "kt", "java", "python", "py",
        "javascript", "js", "typescript", "ts",
        "rust", "rs", "go", "golang", "swift",
        "c", "cpp", "c++", "bash", "sh", "shell", "zsh", "sql"
    )

    // Language keyword sets
    private val KOTLIN_KEYWORDS = setOf(
        "fun", "val", "var", "class", "interface", "object", "data", "sealed",
        "when", "if", "else", "for", "while", "do", "return", "break", "continue",
        "import", "package", "private", "public", "internal", "protected",
        "override", "abstract", "open", "suspend", "inline", "companion",
        "null", "true", "false", "is", "as", "in", "this", "super", "throw",
        "try", "catch", "finally", "typealias", "enum", "annotation", "const"
    )

    private val JAVA_KEYWORDS = setOf(
        "class", "interface", "enum", "extends", "implements", "abstract",
        "public", "private", "protected", "static", "final", "void",
        "if", "else", "for", "while", "do", "switch", "case", "break",
        "continue", "return", "new", "this", "super", "throw", "throws",
        "try", "catch", "finally", "import", "package", "null", "true", "false",
        "instanceof", "synchronized", "volatile", "transient"
    )

    private val PYTHON_KEYWORDS = setOf(
        "def", "class", "if", "elif", "else", "for", "while", "return",
        "import", "from", "as", "try", "except", "finally", "raise",
        "with", "yield", "lambda", "pass", "break", "continue",
        "True", "False", "None", "and", "or", "not", "in", "is",
        "global", "nonlocal", "assert", "del", "async", "await"
    )

    private val JAVASCRIPT_KEYWORDS = setOf(
        "function", "const", "let", "var", "class", "extends",
        "if", "else", "for", "while", "do", "switch", "case", "break",
        "continue", "return", "new", "this", "throw", "try", "catch",
        "finally", "import", "export", "default", "from", "async", "await",
        "null", "undefined", "true", "false", "typeof", "instanceof", "of", "in"
    )

    private val TYPESCRIPT_KEYWORDS = JAVASCRIPT_KEYWORDS + setOf(
        "type", "interface", "enum", "implements", "abstract",
        "public", "private", "protected", "readonly", "as", "is",
        "keyof", "typeof", "infer", "extends", "never", "unknown", "any"
    )

    private val RUST_KEYWORDS = setOf(
        "fn", "let", "mut", "const", "struct", "enum", "impl", "trait",
        "pub", "mod", "use", "crate", "self", "super", "if", "else",
        "for", "while", "loop", "match", "return", "break", "continue",
        "move", "async", "await", "unsafe", "where", "type", "dyn",
        "true", "false", "Some", "None", "Ok", "Err"
    )

    private val GO_KEYWORDS = setOf(
        "func", "var", "const", "type", "struct", "interface",
        "if", "else", "for", "range", "switch", "case", "default",
        "return", "break", "continue", "go", "defer", "select",
        "chan", "map", "package", "import", "nil", "true", "false",
        "make", "new", "append", "len", "cap"
    )

    private val SWIFT_KEYWORDS = setOf(
        "func", "var", "let", "class", "struct", "enum", "protocol",
        "extension", "if", "else", "for", "while", "switch", "case",
        "return", "break", "continue", "import", "guard", "defer",
        "throw", "throws", "try", "catch", "nil", "true", "false",
        "self", "super", "init", "deinit", "override", "private",
        "public", "internal", "fileprivate", "open", "static", "async", "await"
    )

    private val C_KEYWORDS = setOf(
        "int", "char", "float", "double", "void", "long", "short",
        "unsigned", "signed", "struct", "union", "enum", "typedef",
        "if", "else", "for", "while", "do", "switch", "case", "break",
        "continue", "return", "goto", "sizeof", "static", "extern",
        "const", "volatile", "register", "auto", "include", "define",
        "NULL", "true", "false", "class", "public", "private", "protected",
        "virtual", "override", "template", "namespace", "using", "new", "delete"
    )

    private val BASH_KEYWORDS = setOf(
        "if", "then", "else", "elif", "fi", "for", "while", "do", "done",
        "case", "esac", "function", "return", "exit", "echo", "export",
        "local", "readonly", "declare", "set", "unset", "source", "alias",
        "cd", "ls", "grep", "sed", "awk", "cat", "rm", "mv", "cp", "mkdir"
    )

    private val SQL_KEYWORDS = setOf(
        "SELECT", "FROM", "WHERE", "INSERT", "UPDATE", "DELETE", "CREATE",
        "DROP", "ALTER", "TABLE", "INDEX", "VIEW", "JOIN", "LEFT", "RIGHT",
        "INNER", "OUTER", "ON", "AND", "OR", "NOT", "IN", "EXISTS",
        "GROUP", "BY", "ORDER", "HAVING", "LIMIT", "OFFSET", "AS",
        "NULL", "TRUE", "FALSE", "IS", "LIKE", "BETWEEN", "UNION",
        "select", "from", "where", "insert", "update", "delete", "create",
        "drop", "alter", "table", "join", "left", "right", "inner",
        "outer", "on", "and", "or", "not", "in", "exists", "group",
        "by", "order", "having", "limit", "offset", "as", "null",
        "true", "false", "is", "like", "between", "union"
    )

    // Regex patterns for inline markdown
    private val CODE_FENCE_REGEX = Regex("""^`{3,}.*$""")
    private val HORIZONTAL_RULE_REGEX = Regex("""^[-*_]{3,}$""")
    private val TABLE_SEPARATOR_REGEX = Regex("""^\|?[\s\-:|]+\|[\s\-:|]*$""")
    private val HEADING_REGEX = Regex("""^(#{1,6})\s+(.*)$""")
    private val BLOCKQUOTE_REGEX = Regex("""^>\s?(.*)$""")
    private val UNORDERED_LIST_REGEX = Regex("""^(\s*)[-*+]\s+(.*)$""")
    private val ORDERED_LIST_REGEX = Regex("""^(\s*)(\d+)\.\s+(.*)$""")
    private val TABLE_ROW_REGEX = Regex("""^\|.*\|$""")

    private val INLINE_CODE_PATTERN = Regex("""^`([^`]+)`""")
    private val IMAGE_PATTERN = Regex("""^!\[([^\]]*)\]\(([^)]*)\)""")
    private val LINK_PATTERN = Regex("""^\[([^\]]*)\]\(([^)]*)\)""")
    private val BOLD_PATTERN = Regex("""^\*\*(.+?)\*\*|^__(.+?)__""")
    private val STRIKETHROUGH_PATTERN = Regex("""^~~(.+?)~~""")
    private val ITALIC_PATTERN = Regex("""^\*(.+?)\*|^_(.+?)_""")

    // Colors for syntax highlighting and markdown rendering
    private val KeywordColor = Color(0xFF569CD6)
    private val StringColor = Color(0xFFCE9178)
    private val CommentColor = Color(0xFF6A9955)
    private val NumberColor = Color(0xFFB5CEA8)
    private val CodeBgColor = Color(0x20808080)
    private val CodeBlockBgColor = Color(0x15808080)
    private val CodeLangColor = Color(0xFF808080)
    private val LinkColor = Color(0xFF4FC1FF)
    private val BlockquoteColor = Color(0xFF808080)
    private val DividerColor = Color(0xFF606060)
    private val PromptColor = Color(0xFF4EC9B0)
    private val ToolCallColor = Color(0xFFDCDCAA)
    private val ToolArgColor = Color(0xFF9CDCFE)
    private val SuccessColor = Color(0xFF4EC9B0)
    private val ErrorColor = Color(0xFFF44747)
}
