package com.evcs.favorites.focus

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import java.util.Locale

/**
 * Color tokens representing badge and capsule status for Focus Mode overlay and notifications.
 */
enum class FocusBadgeColor {
    GREEN,
    RED,
    AMBER
}

/**
 * Display modes for the Focus Mode floating HUD.
 * MINI_PILL is an ultra-compact status capsule (~80x38dp).
 * FULL_HUD is the expanded automotive dashboard overlay.
 */
enum class FocusModeDisplayMode {
    MINI_PILL,
    FULL_HUD
}

/**
 * Presentation mode chosen based on system overlay permission availability.
 */
enum class PresentationMode {
    FLOATING_OVERLAY,
    NOTIFICATION_FALLBACK
}

/**
 * Formatted presentation state for the floating capsule UI and notification display.
 */
data class FloatingViewState(
    val stationName: String,
    val badgeText: String,
    val detailedTiersText: String? = null,
    val badgeColorToken: FocusBadgeColor,
    val isRerouteAvailable: Boolean,
    val rerouteButtonText: String?,
    val isOffline: Boolean,
    val distanceText: String?
)

/**
 * Utility helper providing layout calculations, boundary clamping, snap-to-edge math,
 * and view presentation formatting for the Focus Mode floating window overlay.
 */
object FocusModeViewLayoutHelper {

    const val DEFAULT_MARGIN = 16

    const val LANDSCAPE_WIDTH_PERCENTAGE = 0.32f
    const val PORTRAIT_WIDTH_PERCENTAGE = 0.82f

    const val MIN_LANDSCAPE_WIDTH_DP = 280
    const val MAX_LANDSCAPE_WIDTH_DP = 440

    const val MIN_PORTRAIT_WIDTH_DP = 280
    const val MAX_PORTRAIT_WIDTH_DP = 380

    const val MIN_OVERLAY_HEIGHT_DP = 64
    const val MIN_TOUCH_TARGET_DP = 48
    const val CAR_CTA_HEIGHT_DP = 56

    const val HERO_BADGE_MIN_WIDTH_DP = 80
    const val HERO_BADGE_MIN_HEIGHT_DP = 48

    const val VIETNAMESE_VERTICAL_PADDING_DP = 4
    const val HERO_METRIC_TEXT_SIZE_SP = 24f
    const val DETAILED_TIERS_TEXT_SIZE_SP = 16f
    const val TITLE_MIN_TEXT_SIZE_SP = 13
    const val TITLE_MAX_TEXT_SIZE_SP = 16

    const val MINI_PILL_WIDTH_DP = 80
    const val MINI_PILL_HEIGHT_DP = 38
    const val MINI_PILL_CORNER_RADIUS_DP = 20
    const val MINI_PILL_TEXT_SIZE_SP = 18f
    const val TAP_MAX_DURATION_MS = 350L
    const val TOUCH_SLOP_FALLBACK_DP = 12

    /**
     * Determines whether to render the floating overlay bubble or fall back
     * to persistent foreground notification based on overlay permission status.
     */
    fun resolvePresentationMode(canDrawOverlays: Boolean): PresentationMode {
        return if (canDrawOverlays) {
            PresentationMode.FLOATING_OVERLAY
        } else {
            PresentationMode.NOTIFICATION_FALLBACK
        }
    }

    /**
     * Checks if the app currently has permission to draw system alert window overlays.
     */
    fun canDrawOverlays(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /**
     * Formats raw [FocusModeState] into [FloatingViewState] presentation tokens:
     * - Normal state: Green badge ("🟢 Trống 3/6 cổng DC"), Line 2 details ("⚡ 60kW (1/4)  |  20kW (2/2)"), reroute disabled.
     * - Full state: Red badge ("🔴 HẾT CHỖ!"), Line 2 details ("⚡ 60kW (0/4)  |  20kW (0/2)"), 1-tap reroute button revealed if alternative available.
     * - Offline state: Amber badge ("⚠️ Mất kết nối - Dữ liệu lúc HH:mm"), reroute disabled.
     */
    fun formatViewState(state: FocusModeState): FloatingViewState {
        val distText = state.distanceRemainingKm?.let { dist ->
            if (dist < 1.0) {
                "${(dist * 1000).toInt()}m"
            } else {
                String.format(Locale.US, "%.1f km", dist)
            }
        }
        val cleanStationName = com.evcs.favorites.util.StationNameSanitizer.sanitize(state.targetStation.name)
            .ifBlank { state.targetStation.name }

        return when {
            state.isOffline -> {
                FloatingViewState(
                    stationName = cleanStationName,
                    badgeText = state.statusBadgeText,
                    detailedTiersText = null,
                    badgeColorToken = FocusBadgeColor.AMBER,
                    isRerouteAvailable = false,
                    rerouteButtonText = null,
                    isOffline = true,
                    distanceText = distText
                )
            }
            state.isDcFull -> {
                val rerouteLabel = state.alternativeStation?.let {
                    "🔄 ${it.displayRerouteLabel}"
                }
                FloatingViewState(
                    stationName = cleanStationName,
                    badgeText = state.statusBadgeText,
                    detailedTiersText = state.detailedDcTiersText,
                    badgeColorToken = FocusBadgeColor.RED,
                    isRerouteAvailable = state.alternativeStation != null,
                    rerouteButtonText = rerouteLabel,
                    isOffline = false,
                    distanceText = distText
                )
            }
            else -> {
                FloatingViewState(
                    stationName = cleanStationName,
                    badgeText = state.statusBadgeText,
                    detailedTiersText = state.detailedDcTiersText,
                    badgeColorToken = FocusBadgeColor.GREEN,
                    isRerouteAvailable = false,
                    rerouteButtonText = null,
                    isOffline = false,
                    distanceText = distText
                )
            }
        }
    }

    /**
     * Formats [FocusModeState] for the ultra-compact Mini Pill HUD:
     * - Normal: "🟢 4" (or availableDcSlots count) with GREEN token
     * - Saturated/Full: "🔴 0" with RED token
     * - Offline: "⚠️ !" with AMBER token
     */
    fun formatMiniPillState(state: FocusModeState): Pair<String, FocusBadgeColor> {
        return when {
            state.isOffline -> Pair("⚠️ !", FocusBadgeColor.AMBER)
            state.isDcFull || state.availableDcSlots == 0 -> Pair("🔴 0", FocusBadgeColor.RED)
            else -> Pair("🟢 ${state.availableDcSlots}", FocusBadgeColor.GREEN)
        }
    }

    /**
     * Clamps proposed (x, y) coordinates within screen display boundaries,
     * preventing the floating window from escaping beyond visible screen edges.
     *
     * @param x Desired horizontal coordinate
     * @param y Desired vertical coordinate
     * @param viewWidth Floating view width in pixels
     * @param viewHeight Floating view height in pixels
     * @param screenWidth Total display width in pixels
     * @param screenHeight Total display height in pixels
     * @return Pair of clamped (x, y) coordinates
     */
    fun clampPosition(
        x: Int,
        y: Int,
        viewWidth: Int,
        viewHeight: Int,
        screenWidth: Int,
        screenHeight: Int
    ): Pair<Int, Int> {
        val minX = 0
        val maxX = maxOf(0, screenWidth - viewWidth)
        val minY = 0
        val maxY = maxOf(0, screenHeight - viewHeight)

        val clampedX = x.coerceIn(minX, maxX)
        val clampedY = y.coerceIn(minY, maxY)
        return Pair(clampedX, clampedY)
    }

    /**
     * Calculates the horizontal snap coordinate when touch release occurs.
     * If center of view is on the left half of the screen, snaps to the left margin.
     * If on the right half, snaps to the right margin.
     *
     * @param currentX Current horizontal position of the view
     * @param viewWidth Floating view width in pixels
     * @param screenWidth Total display width in pixels
     * @param margin Inset margin in pixels from screen edge
     * @return Snapped horizontal X coordinate
     */
    fun calculateSnapToEdgeX(
        currentX: Int,
        viewWidth: Int,
        screenWidth: Int,
        margin: Int = DEFAULT_MARGIN
    ): Int {
        val viewCenterX = currentX + (viewWidth / 2)
        val screenCenterX = screenWidth / 2

        return if (viewCenterX < screenCenterX) {
            margin
        } else {
            maxOf(margin, screenWidth - viewWidth - margin)
        }
    }

    /**
     * Calculates the adjusted horizontal X coordinate when switching display modes
     * (e.g. MINI_PILL to FULL_HUD or FULL_HUD to MINI_PILL).
     * If the view is snapped to the left half, stays pinned to left margin.
     * If snapped to the right half, adjusts X to remain neatly pinned against the right margin.
     *
     * @param currentX Current horizontal position of the view
     * @param oldWidth Old view width before mode transition
     * @param newWidth New view width after mode transition
     * @param screenWidth Total display width in pixels
     * @param margin Inset margin in pixels from screen edge
     * @return Adjusted horizontal X coordinate
     */
    fun calculateAdjustedXOnModeChange(
        currentX: Int,
        oldWidth: Int,
        newWidth: Int,
        screenWidth: Int,
        margin: Int = DEFAULT_MARGIN
    ): Int {
        val viewCenterX = currentX + (oldWidth / 2)
        val screenCenterX = screenWidth / 2

        return if (viewCenterX < screenCenterX) {
            margin
        } else {
            maxOf(margin, screenWidth - newWidth - margin)
        }
    }

    /**
     * Calculates dynamic overlay width based on screen width, orientation, and display density.
     * Landscape (Car / Android Box): 30% to 34% (nominal 32%) of screen width, clamped between 280dp and 440dp.
     * Portrait (Phone): 80% to 85% (nominal 82%) of screen width, clamped between 280dp and 380dp.
     *
     * @param screenWidthPx Screen width in pixels
     * @param isLandscape True if current orientation is landscape (e.g. car head unit)
     * @param density Screen display density (DPI scaling factor)
     * @return Clamped overlay width in pixels
     */
    fun calculateOverlayWidth(
        screenWidthPx: Int,
        isLandscape: Boolean,
        density: Float
    ): Int {
        val safeDensity = if (density > 0f) density else 1.0f
        val (percentage, minDp, maxDp) = if (isLandscape) {
            Triple(LANDSCAPE_WIDTH_PERCENTAGE, MIN_LANDSCAPE_WIDTH_DP, MAX_LANDSCAPE_WIDTH_DP)
        } else {
            Triple(PORTRAIT_WIDTH_PERCENTAGE, MIN_PORTRAIT_WIDTH_DP, MAX_PORTRAIT_WIDTH_DP)
        }
        val minWidthPx = kotlin.math.round(minDp * safeDensity).toInt()
        val maxWidthPx = kotlin.math.round(maxDp * safeDensity).toInt()
        val rawTargetWidthPx = kotlin.math.round(screenWidthPx * percentage).toInt()
        return rawTargetWidthPx.coerceIn(minWidthPx, maxWidthPx)
    }

    /**
     * Calculates minimum dimensions (widthPx, heightPx) for the standalone Hero Badge card.
     */
    fun calculateHeroBadgeDimensions(density: Float): Pair<Int, Int> {
        val safeDensity = if (density > 0f) density else 1.0f
        val minW = kotlin.math.round(HERO_BADGE_MIN_WIDTH_DP * safeDensity).toInt()
        val minH = kotlin.math.round(HERO_BADGE_MIN_HEIGHT_DP * safeDensity).toInt()
        return Pair(minW, minH)
    }

    /**
     * Returns touch target minimum dimension in pixels for automotive accessibility (>= 48dp).
     */
    fun calculateMinTouchTargetPx(density: Float): Int {
        val safeDensity = if (density > 0f) density else 1.0f
        return kotlin.math.round(MIN_TOUCH_TARGET_DP * safeDensity).toInt()
    }

    /**
     * Returns CTA button height in pixels based on orientation (56dp for car/landscape, 48dp for portrait).
     */
    fun calculateCtaButtonHeightPx(isLandscape: Boolean, density: Float): Int {
        val safeDensity = if (density > 0f) density else 1.0f
        val dp = if (isLandscape) CAR_CTA_HEIGHT_DP else MIN_TOUCH_TARGET_DP
        return kotlin.math.round(dp * safeDensity).toInt()
    }

    /**
     * Creates standard [WindowManager.LayoutParams] for the floating overlay view,
     * configured with TYPE_APPLICATION_OVERLAY and non-focusable flags.
     * Accepts dynamic overlay width with fallback to WRAP_CONTENT.
     */
    fun createWindowLayoutParams(
        x: Int = DEFAULT_MARGIN,
        y: Int = 150,
        width: Int = WindowManager.LayoutParams.WRAP_CONTENT
    ): WindowManager.LayoutParams {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        return WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
            this.width = width
            this.height = WindowManager.LayoutParams.WRAP_CONTENT
        }
    }

    /**
     * Calculates width in pixels for the ultra-compact Mini Pill HUD (~80dp).
     */
    fun calculateMiniPillWidth(density: Float): Int {
        val safeDensity = if (density > 0f) density else 1.0f
        return kotlin.math.round(MINI_PILL_WIDTH_DP * safeDensity).toInt()
    }

    /**
     * Calculates height in pixels for the ultra-compact Mini Pill HUD (~38dp).
     */
    fun calculateMiniPillHeight(density: Float): Int {
        val safeDensity = if (density > 0f) density else 1.0f
        return kotlin.math.round(MINI_PILL_HEIGHT_DP * safeDensity).toInt()
    }

    /**
     * Calculates effective touch slop in pixels with a fallback floor of 12dp.
     */
    fun calculateEffectiveTouchSlop(scaledTouchSlop: Int, density: Float): Int {
        val safeDensity = if (density > 0f) density else 1.0f
        val floorPx = kotlin.math.round(TOUCH_SLOP_FALLBACK_DP * safeDensity).toInt()
        return maxOf(scaledTouchSlop, floorPx)
    }

    /**
     * Discriminates whether a touch gesture is classified as a 1-tap action:
     * Euclidean delta <= touchSlop AND touch duration < 350ms.
     */
    fun isTapGesture(
        dx: Float,
        dy: Float,
        durationMs: Long,
        touchSlop: Int,
        maxDurationMs: Long = TAP_MAX_DURATION_MS
    ): Boolean {
        val distanceSquared = (dx * dx) + (dy * dy)
        val slopSquared = (touchSlop.toLong() * touchSlop.toLong()).toDouble()
        return distanceSquared <= slopSquared && durationMs < maxDurationMs
    }

    fun isTapGesture(
        dx: Int,
        dy: Int,
        durationMs: Long,
        touchSlop: Int,
        maxDurationMs: Long = TAP_MAX_DURATION_MS
    ): Boolean = isTapGesture(dx.toFloat(), dy.toFloat(), durationMs, touchSlop, maxDurationMs)

    /**
     * Discriminates whether a touch gesture is classified as a drag:
     * Euclidean delta > touchSlop.
     */
    fun isDragGesture(
        dx: Float,
        dy: Float,
        touchSlop: Int
    ): Boolean {
        val distanceSquared = (dx * dx) + (dy * dy)
        val slopSquared = (touchSlop.toLong() * touchSlop.toLong()).toDouble()
        return distanceSquared > slopSquared
    }

    fun isDragGesture(
        dx: Int,
        dy: Int,
        touchSlop: Int
    ): Boolean = isDragGesture(dx.toFloat(), dy.toFloat(), touchSlop)
}

