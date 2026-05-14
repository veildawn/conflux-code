package com.claudemobile.core.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudemobile.core.domain.model.OutputEvent
import com.claudemobile.core.ui.R

/**
 * A composable that renders a list of [OutputEvent] as formatted markdown content.
 *
 * Uses [MarkdownRenderer] to convert events into an [AnnotatedString] with full markdown
 * formatting support including headings, lists, bold, italic, inline code, code fences
 * with syntax highlighting, tables, and clickable links.
 *
 * Features:
 * - Clickable URL links that open in the system browser
 * - Copy action on code blocks (copies raw text without syntax coloring)
 * - Text selection support
 * - Content descriptions for all interactive elements (accessibility)
 *
 * @param events The list of output events to render.
 * @param modifier Modifier applied to the root container.
 * @param onLinkClick Optional callback invoked when a link is clicked. If null, the default
 *   URI handler is used.
 */
@Composable
public fun MarkdownContent(
    events: List<OutputEvent>,
    modifier: Modifier = Modifier,
    onLinkClick: ((String) -> Unit)? = null
) {
    val uriHandler = LocalUriHandler.current
    val clipboardManager = LocalClipboardManager.current

    val codeBlocks = remember(events) { extractCodeBlocks(events) }
    val annotatedString = remember(events) { MarkdownRenderer.renderToAnnotatedString(events) }

    val markdownDescription = stringResource(R.string.core_ui_markdown_content_description)
    SelectionContainer(
        modifier = modifier.semantics {
            contentDescription = markdownDescription
        }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (codeBlocks.isEmpty()) {
                // Simple case: no code blocks, render as a single clickable text
                ClickableMarkdownText(
                    annotatedString = annotatedString,
                    onLinkClick = { url ->
                        if (onLinkClick != null) {
                            onLinkClick(url)
                        } else {
                            uriHandler.openUri(url)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                // Complex case: interleave text segments with code blocks that have copy buttons
                RenderEventsWithCodeBlocks(
                    events = events,
                    codeBlocks = codeBlocks,
                    onLinkClick = { url ->
                        if (onLinkClick != null) {
                            onLinkClick(url)
                        } else {
                            uriHandler.openUri(url)
                        }
                    },
                    onCopyCodeBlock = { code ->
                        clipboardManager.setText(AnnotatedString(code))
                    }
                )
            }
        }
    }
}

/**
 * Renders a clickable [AnnotatedString] that handles URL annotations.
 */
@Composable
private fun ClickableMarkdownText(
    annotatedString: AnnotatedString,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val formattedTextDescription = stringResource(R.string.core_ui_formatted_text_description)
    ClickableText(
        text = annotatedString,
        modifier = modifier.semantics {
            contentDescription = formattedTextDescription
        },
        style = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface
        ),
        onClick = { offset ->
            annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                .firstOrNull()?.let { annotation ->
                    onLinkClick(annotation.item)
                }
        }
    )
}

/**
 * Renders events with code blocks that include copy buttons.
 * Splits the content into segments: text before code blocks, code blocks with headers,
 * and text after code blocks.
 */
@Composable
private fun RenderEventsWithCodeBlocks(
    events: List<OutputEvent>,
    codeBlocks: List<CodeBlockInfo>,
    onLinkClick: (String) -> Unit,
    onCopyCodeBlock: (String) -> Unit
) {
    val segments = remember(events, codeBlocks) { splitIntoSegments(events) }

    for (segment in segments) {
        when (segment) {
            is ContentSegment.TextSegment -> {
                if (segment.annotatedString.text.isNotEmpty()) {
                    ClickableMarkdownText(
                        annotatedString = segment.annotatedString,
                        onLinkClick = onLinkClick,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            is ContentSegment.CodeBlockSegment -> {
                CodeBlockWithCopyAction(
                    code = segment.code,
                    language = segment.language,
                    onCopy = { onCopyCodeBlock(segment.code) }
                )
            }
        }
    }
}

/**
 * A code block composable with a language label and copy button.
 */
@Composable
private fun CodeBlockWithCopyAction(
    code: String,
    language: String,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier
) {
    val codeBlockDescription = if (language.isNotEmpty()) {
        stringResource(R.string.core_ui_code_block_language_description, language)
    } else {
        stringResource(R.string.core_ui_code_block_description)
    }

    val copyCodeDescription = stringResource(R.string.core_ui_copy_code_description)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .semantics { contentDescription = codeBlockDescription }
    ) {
        // Header row with language label and copy button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (language.isNotEmpty()) {
                Text(
                    text = language,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Box(modifier = Modifier.weight(1f))
            }

            IconButton(
                onClick = onCopy,
                modifier = Modifier.semantics {
                    contentDescription = copyCodeDescription
                }
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_copy),
                    contentDescription = null, // Described by parent semantics
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Code content with soft-wrap to prevent horizontal clipping at large font scales (2.0x)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            val highlightedCode = remember(code, language) {
                renderCodeContent(code, language)
            }
            Text(
                text = highlightedCode,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                softWrap = true
            )
        }
    }
}

/**
 * Renders code content with syntax highlighting if the language is supported.
 */
private fun renderCodeContent(code: String, language: String): AnnotatedString {
    if (language.isEmpty() || !MarkdownRenderer.isSupportedLanguage(language)) {
        return AnnotatedString(code)
    }
    // Use MarkdownRenderer to render just this code block with highlighting
    val wrappedEvents = listOf(
        OutputEvent.Text("```$language\n$code\n```")
    )
    val rendered = MarkdownRenderer.renderToAnnotatedString(wrappedEvents)
    // The rendered string includes the language label and newlines from the renderer;
    // extract just the code portion
    val text = rendered.text
    val langLabelEnd = text.indexOf('\n', text.indexOf(language))
    val codeStart = if (langLabelEnd >= 0) langLabelEnd + 1 else 0
    val codeEnd = text.length.let { len ->
        // Trim trailing newline added by renderer
        if (len > 0 && text[len - 1] == '\n') len - 1 else len
    }
    return if (codeStart < codeEnd) {
        rendered.subSequence(codeStart, codeEnd)
    } else {
        AnnotatedString(code)
    }
}

/**
 * Extracts code block information from a list of output events.
 */
private fun extractCodeBlocks(events: List<OutputEvent>): List<CodeBlockInfo> {
    val codeBlocks = mutableListOf<CodeBlockInfo>()
    val codeFenceRegex = Regex("""^`{3,}(.*)$""")

    for (event in events) {
        if (event is OutputEvent.Text) {
            val lines = event.content.split('\n')
            var inCodeFence = false
            var language = ""
            val codeLines = mutableListOf<String>()

            for (line in lines) {
                if (inCodeFence) {
                    if (codeFenceRegex.matches(line.trimEnd())) {
                        inCodeFence = false
                        codeBlocks.add(
                            CodeBlockInfo(
                                code = codeLines.joinToString("\n"),
                                language = language
                            )
                        )
                        codeLines.clear()
                        language = ""
                    } else {
                        codeLines.add(line)
                    }
                } else {
                    val match = codeFenceRegex.matchEntire(line.trimEnd())
                    if (match != null) {
                        inCodeFence = true
                        language = match.groupValues[1].trim()
                            .takeWhile { it.isLetterOrDigit() || it == '+' || it == '#' }
                    }
                }
            }

            // Handle unclosed code fence
            if (inCodeFence && codeLines.isNotEmpty()) {
                codeBlocks.add(
                    CodeBlockInfo(
                        code = codeLines.joinToString("\n"),
                        language = language
                    )
                )
            }
        }
    }

    return codeBlocks
}

/**
 * Splits events into content segments: text and code blocks.
 */
private fun splitIntoSegments(events: List<OutputEvent>): List<ContentSegment> {
    val segments = mutableListOf<ContentSegment>()
    val codeFenceRegex = Regex("""^`{3,}(.*)$""")

    for (event in events) {
        when (event) {
            is OutputEvent.Text -> {
                val lines = event.content.split('\n')
                var inCodeFence = false
                var language = ""
                val codeLines = mutableListOf<String>()
                val textLines = mutableListOf<String>()

                for (line in lines) {
                    if (inCodeFence) {
                        if (codeFenceRegex.matches(line.trimEnd())) {
                            inCodeFence = false
                            segments.add(
                                ContentSegment.CodeBlockSegment(
                                    code = codeLines.joinToString("\n"),
                                    language = language
                                )
                            )
                            codeLines.clear()
                            language = ""
                        } else {
                            codeLines.add(line)
                        }
                    } else {
                        val match = codeFenceRegex.matchEntire(line.trimEnd())
                        if (match != null) {
                            // Flush accumulated text lines
                            if (textLines.isNotEmpty()) {
                                val textContent = textLines.joinToString("\n")
                                val textEvents = listOf(OutputEvent.Text(textContent))
                                segments.add(
                                    ContentSegment.TextSegment(
                                        MarkdownRenderer.renderToAnnotatedString(textEvents)
                                    )
                                )
                                textLines.clear()
                            }
                            inCodeFence = true
                            language = match.groupValues[1].trim()
                                .takeWhile { it.isLetterOrDigit() || it == '+' || it == '#' }
                        } else {
                            textLines.add(line)
                        }
                    }
                }

                // Flush remaining text or unclosed code fence
                if (inCodeFence && codeLines.isNotEmpty()) {
                    segments.add(
                        ContentSegment.CodeBlockSegment(
                            code = codeLines.joinToString("\n"),
                            language = language
                        )
                    )
                } else if (textLines.isNotEmpty()) {
                    val textContent = textLines.joinToString("\n")
                    val textEvents = listOf(OutputEvent.Text(textContent))
                    segments.add(
                        ContentSegment.TextSegment(
                            MarkdownRenderer.renderToAnnotatedString(textEvents)
                        )
                    )
                }
            }
            is OutputEvent.ToolCallStart,
            is OutputEvent.ToolCallResult,
            is OutputEvent.Prompt,
            is OutputEvent.Error -> {
                // Render non-text events as text segments
                val rendered = MarkdownRenderer.renderToAnnotatedString(listOf(event))
                segments.add(ContentSegment.TextSegment(rendered))
            }
            is OutputEvent.TurnComplete -> {
                // No visual representation
            }
        }
    }

    return segments
}

/**
 * Information about a code block extracted from events.
 */
private data class CodeBlockInfo(
    val code: String,
    val language: String
)

/**
 * A segment of content that can be either text or a code block.
 */
private sealed interface ContentSegment {
    data class TextSegment(val annotatedString: AnnotatedString) : ContentSegment
    data class CodeBlockSegment(val code: String, val language: String) : ContentSegment
}
