package com.evcs.favorites.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Pure Kotlin helper for Lightbox state calculations, zoom clamping, pan boundaries,
 * and dismiss gesture arbitration. Decoupled from Android runtime for 100% JVM unit testability.
 */
object StationPhotoViewerHelper {
    const val MIN_SCALE = 1.0f
    const val MAX_SCALE = 4.0f
    const val DOUBLE_TAP_ZOOM_SCALE = 2.5f
    const val ZOOM_GESTURE_THRESHOLD = 1.05f
    const val DISMISS_DRAG_THRESHOLD_DP = 100f
    const val DISMISS_VELOCITY_THRESHOLD = 1000f

    val BACKDROP_COLOR = Color(0xF20B0F17) // 95% translucent dark backdrop

    /**
     * Clamps pinch zoom scale smoothly within [min] and [max].
     */
    fun clampZoomScale(scale: Float, min: Float = MIN_SCALE, max: Float = MAX_SCALE): Float {
        return scale.coerceIn(min, max)
    }

    /**
     * Toggles zoom level on double-tap: returns 1.0f if currently zoomed in, or 2.5f otherwise.
     */
    fun toggleDoubleTapZoom(currentScale: Float): Float {
        return if (currentScale > ZOOM_GESTURE_THRESHOLD) MIN_SCALE else DOUBLE_TAP_ZOOM_SCALE
    }

    /**
     * Determines whether horizontal pager navigation is active. Active strictly when scale <= 1.05f.
     */
    fun isPagingAllowed(scale: Float): Boolean {
        return scale <= ZOOM_GESTURE_THRESHOLD
    }

    /**
     * Clamps pan displacement along an axis so image content does not drift out of view bounds.
     * Boundary formula: ((scale - 1f) * size) / 2.
     */
    fun clampPanOffset(pan: Float, scale: Float, size: Float): Float {
        if (scale <= 1.0f || size <= 0f) return 0f
        val maxBound = ((scale - 1f) * size) / 2f
        return pan.coerceIn(-maxBound, maxBound)
    }

    /**
     * Determines whether vertical downward swipe should trigger viewer dismissal.
     */
    fun shouldDismissOnDrag(
        dragOffsetY: Float,
        velocityY: Float = 0f,
        thresholdDp: Float = DISMISS_DRAG_THRESHOLD_DP
    ): Boolean {
        return dragOffsetY >= thresholdDp || velocityY > DISMISS_VELOCITY_THRESHOLD
    }

    /**
     * Calculates backdrop opacity during progressive swipe-to-dismiss drag.
     */
    fun calculateDismissAlpha(
        dragOffsetY: Float,
        maxDrag: Float = 300f,
        baseAlpha: Float = 0.95f
    ): Float {
        if (dragOffsetY <= 0f) return baseAlpha
        val ratio = (1f - (dragOffsetY / maxDrag)).coerceIn(0f, 1f)
        return baseAlpha * ratio
    }

    /**
     * Formats photo index badge text (e.g. "2 / 3").
     */
    fun formatIndexBadge(currentIndex: Int, totalCount: Int): String {
        return if (totalCount > 0) "${currentIndex + 1} / $totalCount" else "0 / 0"
    }

    /**
     * Determines whether photo carousel should render based on image list.
     */
    fun shouldShowCarousel(images: List<String>): Boolean {
        return images.isNotEmpty()
    }
}

/**
 * Full-screen interactive Lightbox dialog modal for station photos.
 *
 * Encapsulates:
 * - Dedicated Window via [Dialog] with [DialogProperties] to isolate gestures from bottom sheet.
 * - System back navigation via [BackHandler].
 * - 95% translucent dark backdrop ([StationPhotoViewerHelper.BACKDROP_COLOR]).
 * - Pinch-to-zoom (1.0x - 4.0x) and animated double-tap zoom (1.0x <-> 2.5x).
 * - Clamped pan when zoomed in, with horizontal pager disabled during zoom.
 * - Horizontal swipe paging when at 1.0x.
 * - Swipe-to-dismiss on downward vertical drag with progressive alpha fade.
 * - Top header with index pill ("2 / 3") and Close button.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StationPhotoViewerModal(
    images: List<String>,
    initialIndex: Int = 0,
    onDismiss: () -> Unit
) {
    if (images.isEmpty()) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        BackHandler(onBack = onDismiss)

        val coroutineScope = rememberCoroutineScope()
        val safeInitialPage = initialIndex.coerceIn(0, images.lastIndex)
        val pagerState = rememberPagerState(initialPage = safeInitialPage) { images.size }
        val density = LocalDensity.current

        // Swipe-to-dismiss vertical translation state
        val dismissAnimatable = remember { Animatable(0f) }
        val dismissThresholdPx = with(density) { StationPhotoViewerHelper.DISMISS_DRAG_THRESHOLD_DP.dp.toPx() }

        // Progressive alpha fade based on downward drag
        val currentAlpha = remember(dismissAnimatable.value) {
            StationPhotoViewerHelper.calculateDismissAlpha(dismissAnimatable.value, maxDrag = dismissThresholdPx * 2f)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(StationPhotoViewerHelper.BACKDROP_COLOR.copy(alpha = currentAlpha))
                .offset { IntOffset(0, dismissAnimatable.value.roundToInt()) }
        ) {
            // Horizontal Pager of Zoomable Photos
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                ZoomablePhotoItem(
                    imageUrl = images[page],
                    onDismissDrag = { deltaY ->
                        coroutineScope.launch {
                            val newY = (dismissAnimatable.value + deltaY).coerceAtLeast(0f)
                            dismissAnimatable.snapTo(newY)
                        }
                    },
                    onDismissDragEnd = {
                        if (dismissAnimatable.value >= dismissThresholdPx) {
                            onDismiss()
                        } else {
                            coroutineScope.launch {
                                dismissAnimatable.animateTo(0f, animationSpec = tween(200))
                            }
                        }
                    }
                )
            }

            // Top App Bar: Photo index pill counter & Close button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Black.copy(alpha = 0.6f)
                ) {
                    Text(
                        text = StationPhotoViewerHelper.formatIndexBadge(pagerState.currentPage, images.size),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        ),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(40.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color.Black.copy(alpha = 0.6f),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Đóng",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Individual zoomable and pannable image item supporting pinch-to-zoom,
 * animated double-tap zoom, bounded panning, and swipe-to-dismiss downward drag.
 */
@Composable
private fun ZoomablePhotoItem(
    imageUrl: String,
    onDismissDrag: (Float) -> Unit,
    onDismissDragEnd: () -> Unit
) {
    var rawScale by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }

    val animatedScale by animateFloatAsState(
        targetValue = rawScale,
        animationSpec = tween(durationMillis = 250),
        label = "LightboxZoomScale"
    )

    val isPaging = StationPhotoViewerHelper.isPagingAllowed(animatedScale)

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            rawScale = StationPhotoViewerHelper.toggleDoubleTapZoom(rawScale)
                            if (rawScale <= StationPhotoViewerHelper.MIN_SCALE) {
                                panX = 0f
                                panY = 0f
                            }
                        }
                    )
                }
                .pointerInput(isPaging) {
                    if (isPaging) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { _, dragAmount ->
                                if (dragAmount > 0f) {
                                    onDismissDrag(dragAmount)
                                }
                            },
                            onDragEnd = { onDismissDragEnd() },
                            onDragCancel = { onDismissDragEnd() }
                        )
                    } else {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = StationPhotoViewerHelper.clampZoomScale(rawScale * zoom)
                            rawScale = newScale

                            val newPanX = panX + pan.x
                            val newPanY = panY + pan.y
                            panX = StationPhotoViewerHelper.clampPanOffset(newPanX, newScale, widthPx)
                            panY = StationPhotoViewerHelper.clampPanOffset(newPanY, newScale, heightPx)
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = "Station photo",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = animatedScale
                        scaleY = animatedScale
                        translationX = if (isPaging) 0f else panX
                        translationY = if (isPaging) 0f else panY
                    }
            )
        }
    }
}
