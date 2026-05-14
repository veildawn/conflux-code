package com.claudemobile.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.claudemobile.core.domain.model.ToolCallMetadata
import com.claudemobile.core.domain.model.ToolCallStatus
import com.claudemobile.core.ui.R
import com.claudemobile.core.ui.theme.LocalSpacing

/**
 * A composable that renders a tool-call block with a distinct visual style.
 *
 * Displays:
 * - Tool name with a status indicator icon
 * - Collapsible arguments section
 * - Collapsible results section (when available)
 * - Copy action for the entire tool call content
 *
 * All interactive controls have content descriptions for TalkBack accessibility.
 *
 * @param toolCall The [ToolCallMetadata] to render.
 * @param modifier Optional [Modifier] for the root layout.
 */
@Composable
public fun ToolCallBlock(
    toolCall: ToolCallMetadata,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val clipboardManager: ClipboardManager = LocalClipboardManager.current

    var argumentsExpanded by rememberSaveable { mutableStateOf(false) }
    var resultExpanded by rememberSaveable { mutableStateOf(false) }

    val statusIcon = when (toolCall.status) {
        ToolCallStatus.PENDING -> "⏳"
        ToolCallStatus.RUNNING -> "⚙"
        ToolCallStatus.COMPLETED -> "✓"
        ToolCallStatus.FAILED -> "✗"
    }

    val statusColor = when (toolCall.status) {
        ToolCallStatus.PENDING -> MaterialTheme.colorScheme.outline
        ToolCallStatus.RUNNING -> MaterialTheme.colorScheme.primary
        ToolCallStatus.COMPLETED -> MaterialTheme.colorScheme.tertiary
        ToolCallStatus.FAILED -> MaterialTheme.colorScheme.error
    }

    val statusDescription = when (toolCall.status) {
        ToolCallStatus.PENDING -> stringResource(R.string.core_ui_tool_status_pending)
        ToolCallStatus.RUNNING -> stringResource(R.string.core_ui_tool_status_running)
        ToolCallStatus.COMPLETED -> stringResource(R.string.core_ui_tool_status_completed)
        ToolCallStatus.FAILED -> stringResource(R.string.core_ui_tool_status_failed)
    }

    val toolCallDescription = stringResource(R.string.core_ui_tool_call_description, toolCall.toolName, statusDescription)
    val copyToolCallDescription = stringResource(R.string.core_ui_copy_tool_call_description)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = toolCallDescription
            },
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
        ) {
            // Header: tool name + status + copy
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    Text(
                        text = statusIcon,
                        color = statusColor,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = toolCall.toolName,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }

                IconButton(
                    onClick = {
                        val copyText = buildToolCallCopyText(toolCall)
                        clipboardManager.setText(AnnotatedString(copyText))
                    },
                    modifier = Modifier
                        .size(32.dp)
                        .focusable()
                        .semantics {
                            contentDescription = copyToolCallDescription
                        },
                ) {
                    Text(
                        text = "📋",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // Streaming indicator for running tool calls
            if (toolCall.status == ToolCallStatus.RUNNING) {
                StreamingIndicator(
                    modifier = Modifier.padding(top = spacing.xs),
                )
            }

            // Collapsible arguments section
            if (toolCall.arguments.isNotEmpty()) {
                CollapsibleSection(
                    title = stringResource(R.string.core_ui_tool_arguments_title),
                    expanded = argumentsExpanded,
                    onToggle = { argumentsExpanded = !argumentsExpanded },
                    content = toolCall.arguments,
                    modifier = Modifier.padding(top = spacing.sm),
                )
            }

            // Collapsible results section
            if (toolCall.result != null) {
                CollapsibleSection(
                    title = stringResource(R.string.core_ui_tool_result_title),
                    expanded = resultExpanded,
                    onToggle = { resultExpanded = !resultExpanded },
                    content = toolCall.result!!,
                    modifier = Modifier.padding(top = spacing.sm),
                )
            }
        }
    }
}

/**
 * A collapsible section with a clickable header and animated content reveal.
 */
@Composable
private fun CollapsibleSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: String,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val chevron = if (expanded) "▾" else "▸"
    val toggleDescription = if (expanded) {
        stringResource(R.string.core_ui_collapse_section_description, title)
    } else {
        stringResource(R.string.core_ui_expand_section_description, title)
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .focusable()
                .padding(vertical = spacing.xs)
                .semantics {
                    contentDescription = toggleDescription
                    role = Role.Button
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(
                text = chevron,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            SelectionContainer {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerLowest,
                            shape = MaterialTheme.shapes.extraSmall,
                        )
                        .padding(spacing.sm),
                )
            }
        }
    }
}

/**
 * Builds a plain-text representation of the tool call for clipboard copy.
 */
private fun buildToolCallCopyText(toolCall: ToolCallMetadata): String {
    return buildString {
        appendLine("Tool: ${toolCall.toolName}")
        appendLine("Status: ${toolCall.status.name.lowercase()}")
        if (toolCall.arguments.isNotEmpty()) {
            appendLine("Arguments:")
            appendLine(toolCall.arguments)
        }
        if (toolCall.result != null) {
            appendLine("Result:")
            appendLine(toolCall.result)
        }
    }.trimEnd()
}
