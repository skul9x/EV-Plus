package com.evcs.favorites.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Custom scrollbar indicator modifier using [drawWithContent] with smooth alpha fading
 * to clearly communicate scrollability on long modal sheets and scrollable containers.
 */
fun Modifier.verticalScrollbar(
    scrollState: ScrollState,
    width: Dp = 4.dp,
    color: Color? = null,
    alphaFadeDurationMs: Int = 300
): Modifier = composed {
    val scrollbarColor = color ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    val isScrolling = scrollState.isScrollInProgress
    val targetAlpha = if (isScrolling) 1f else 0.35f
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = alphaFadeDurationMs),
        label = "ScrollbarAlpha"
    )

    drawWithContent {
        drawContent()

        if (scrollState.maxValue > 0) {
            val totalHeight = size.height
            val visibleRatio = totalHeight / (totalHeight + scrollState.maxValue)
            val thumbHeight = (totalHeight * visibleRatio).coerceIn(36.dp.toPx(), totalHeight)
            val scrollRatio = scrollState.value.toFloat() / scrollState.maxValue.toFloat()
            val thumbOffset = scrollRatio * (totalHeight - thumbHeight)

            val widthPx = width.toPx()
            drawRoundRect(
                color = scrollbarColor.copy(alpha = scrollbarColor.alpha * alpha),
                topLeft = Offset(size.width - widthPx - 2.dp.toPx(), thumbOffset),
                size = Size(widthPx, thumbHeight),
                cornerRadius = CornerRadius(widthPx / 2, widthPx / 2)
            )
        }
    }
}
