package com.claudemobile.core.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.claudemobile.core.ui.R
import com.claudemobile.core.ui.theme.LocalSpacing

/**
 * An animated streaming indicator composable that displays three pulsing dots.
 *
 * Used to indicate that an assistant message is actively being streamed.
 * Each dot animates with a staggered delay to create a wave-like pulsing effect.
 *
 * Provides a content description for TalkBack accessibility: "Response streaming".
 *
 * @param modifier Optional [Modifier] for the root layout.
 */
@Composable
public fun StreamingIndicator(
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val dotColor = MaterialTheme.colorScheme.primary

    val infiniteTransition = rememberInfiniteTransition(label = "streaming_dots")

    val dot1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = DOT_ANIMATION_DURATION_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot1_alpha",
    )

    val dot2Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = DOT_ANIMATION_DURATION_MS,
                delayMillis = DOT_STAGGER_DELAY_MS,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot2_alpha",
    )

    val dot3Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = DOT_ANIMATION_DURATION_MS,
                delayMillis = DOT_STAGGER_DELAY_MS * 2,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot3_alpha",
    )

    val streamingDescription = stringResource(R.string.core_ui_streaming_description)
    Row(
        modifier = modifier
            .semantics { contentDescription = streamingDescription },
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StreamingDot(alpha = dot1Alpha, dotColor = dotColor)
        StreamingDot(alpha = dot2Alpha, dotColor = dotColor)
        StreamingDot(alpha = dot3Alpha, dotColor = dotColor)
    }
}

/**
 * A single animated dot used in the [StreamingIndicator].
 */
@Composable
private fun StreamingDot(
    alpha: Float,
    dotColor: androidx.compose.ui.graphics.Color,
) {
    Box(
        modifier = Modifier
            .size(DOT_SIZE_DP.dp)
            .alpha(alpha)
            .background(
                color = dotColor,
                shape = CircleShape,
            ),
    )
}

private const val DOT_ANIMATION_DURATION_MS: Int = 600
private const val DOT_STAGGER_DELAY_MS: Int = 200
private val DOT_SIZE_DP: Int = 6
