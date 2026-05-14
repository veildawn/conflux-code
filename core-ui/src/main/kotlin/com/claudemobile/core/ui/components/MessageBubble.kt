package com.claudemobile.core.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.claudemobile.core.domain.model.Message
import com.claudemobile.core.domain.model.MessageRole
import com.claudemobile.core.domain.model.MessageStatus
import com.claudemobile.core.ui.R
import com.claudemobile.core.ui.theme.LocalSpacing

/**
 * A chat message bubble composable with role-based styling and alignment.
 *
 * - User messages: right-aligned, primary container color
 * - Assistant messages: left-aligned, surface variant color
 * - System messages: center-aligned, tertiary container color
 * - Tool messages: left-aligned, secondary container color with distinct style
 *
 * Provides a copy action to copy the raw message text to the clipboard.
 * Displays a [StreamingIndicator] when the message is actively streaming.
 *
 * @param message The domain [Message] to render.
 * @param modifier Optional [Modifier] for the root layout.
 * @param onCopyMessage Callback invoked when the copy action is triggered. Defaults to clipboard copy.
 */
@Composable
public fun MessageBubble(
    message: Message,
    modifier: Modifier = Modifier,
    onCopyMessage: ((String) -> Unit)? = null,
) {
    val spacing = LocalSpacing.current
    val clipboardManager: ClipboardManager = LocalClipboardManager.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
    val maxBubbleWidth = screenWidthDp * 0.8f

    val horizontalArrangement = when (message.role) {
        MessageRole.USER -> Arrangement.End
        MessageRole.ASSISTANT, MessageRole.TOOL -> Arrangement.Start
        MessageRole.SYSTEM -> Arrangement.Center
    }

    val bubbleColors = bubbleColorsForRole(message.role)
    val bubbleShape = bubbleShapeForRole(message.role)

    val roleDescription = when (message.role) {
        MessageRole.USER -> stringResource(R.string.core_ui_role_user)
        MessageRole.ASSISTANT -> stringResource(R.string.core_ui_role_assistant)
        MessageRole.TOOL -> stringResource(R.string.core_ui_role_tool)
        MessageRole.SYSTEM -> stringResource(R.string.core_ui_role_system)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = spacing.lg,
                vertical = spacing.xs,
            )
            .semantics {
                contentDescription = roleDescription
                if (message.status == MessageStatus.STREAMING) {
                    liveRegion = LiveRegionMode.Polite
                }
            },
        horizontalArrangement = horizontalArrangement,
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = maxBubbleWidth)
                .animateContentSize(),
            shape = bubbleShape,
            colors = CardDefaults.cardColors(
                containerColor = bubbleColors.first,
                contentColor = bubbleColors.second,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Column(
                modifier = Modifier.padding(spacing.md),
            ) {
                SelectionContainer {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyLarge,
                        softWrap = true,
                    )
                }

                if (message.status == MessageStatus.STREAMING) {
                    StreamingIndicator(
                        modifier = Modifier.padding(top = spacing.sm),
                    )
                }

                // Copy action row
                if (message.content.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = spacing.xs),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        val copyDescription = stringResource(R.string.core_ui_copy_message_description)
                        IconButton(
                            onClick = {
                                if (onCopyMessage != null) {
                                    onCopyMessage(message.content)
                                } else {
                                    clipboardManager.setText(
                                        AnnotatedString(message.content)
                                    )
                                }
                            },
                            modifier = Modifier
                                .size(32.dp)
                                .focusable()
                                .semantics {
                                    contentDescription = copyDescription
                                },
                        ) {
                            Text(
                                text = "📋",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Returns the container and content colors for a given [MessageRole].
 */
@Composable
private fun bubbleColorsForRole(role: MessageRole): Pair<Color, Color> {
    return when (role) {
        MessageRole.USER -> Pair(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
        MessageRole.ASSISTANT -> Pair(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MessageRole.SYSTEM -> Pair(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        MessageRole.TOOL -> Pair(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/**
 * Returns the bubble shape for a given [MessageRole].
 * User messages use large rounded shape.
 * Assistant/tool messages use large rounded shape.
 * System messages use medium rounded shape.
 */
@Composable
private fun bubbleShapeForRole(role: MessageRole): Shape {
    return when (role) {
        MessageRole.USER -> MaterialTheme.shapes.large
        MessageRole.ASSISTANT -> MaterialTheme.shapes.large
        MessageRole.TOOL -> MaterialTheme.shapes.medium
        MessageRole.SYSTEM -> MaterialTheme.shapes.medium
    }
}
