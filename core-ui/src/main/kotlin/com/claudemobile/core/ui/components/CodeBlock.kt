package com.claudemobile.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
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
import com.claudemobile.core.ui.markdown.MarkdownRenderer
import com.claudemobile.core.ui.theme.LocalSpacing

/**
 * A standalone code block composable with a language label, syntax highlighting,
 * and a copy-to-clipboard action.
 *
 * When the [language] is supported by [MarkdownRenderer], the code is rendered with
 * keyword-based syntax highlighting. Otherwise, it falls back to plain monospace rendering.
 *
 * The copy action copies the raw code text (without syntax coloring) to the system clipboard.
 *
 * @param code The raw code text to display.
 * @param language The programming language identifier (e.g., "kotlin", "python"). Empty string
 *   for no language label.
 * @param modifier Optional [Modifier] for the root layout.
 * @param onCopy Optional callback invoked when the copy button is tapped. If null, the code
 *   is copied to the system clipboard directly.
 */
@Composable
public fun CodeBlock(
    code: String,
    language: String = "",
    modifier: Modifier = Modifier,
    onCopy: ((String) -> Unit)? = null,
) {
    val spacing = LocalSpacing.current
    val clipboardManager: ClipboardManager = LocalClipboardManager.current

    val blockDescription = if (language.isNotEmpty()) {
        stringResource(R.string.core_ui_code_block_language_description, language)
    } else {
        stringResource(R.string.core_ui_code_block_description)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .semantics { contentDescription = blockDescription },
    ) {
        // Header row with language label and copy button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = spacing.md, vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (language.isNotEmpty()) {
                Text(
                    text = language,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Box(modifier = Modifier.weight(1f))
            }

            val copyDescription = stringResource(R.string.core_ui_copy_code_description)
            IconButton(
                onClick = {
                    if (onCopy != null) {
                        onCopy(code)
                    } else {
                        clipboardManager.setText(AnnotatedString(code))
                    }
                },
                modifier = Modifier.semantics {
                    contentDescription = copyDescription
                },
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_copy),
                    contentDescription = null, // Described by parent semantics
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Code content with soft-wrap to prevent horizontal clipping at large font scales (2.0x)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.md),
        ) {
            val highlightedCode = remember(code, language) {
                renderHighlightedCode(code, language)
            }
            Text(
                text = highlightedCode,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                softWrap = true,
            )
        }
    }
}

/**
 * Renders code with syntax highlighting if the language is supported.
 * Falls back to plain monospace text for unsupported languages.
 */
private fun renderHighlightedCode(code: String, language: String): AnnotatedString {
    if (language.isEmpty() || !MarkdownRenderer.isSupportedLanguage(language)) {
        return AnnotatedString(code)
    }
    // Use MarkdownRenderer to produce highlighted code
    val wrappedEvents = listOf(
        OutputEvent.Text("```$language\n$code\n```")
    )
    val rendered = MarkdownRenderer.renderToAnnotatedString(wrappedEvents)
    // Extract just the code portion (skip language label line)
    val text = rendered.text
    val langLabelEnd = text.indexOf('\n', text.indexOf(language))
    val codeStart = if (langLabelEnd >= 0) langLabelEnd + 1 else 0
    val codeEnd = text.length.let { len ->
        if (len > 0 && text[len - 1] == '\n') len - 1 else len
    }
    return if (codeStart < codeEnd) {
        rendered.subSequence(codeStart, codeEnd) as AnnotatedString
    } else {
        AnnotatedString(code)
    }
}
