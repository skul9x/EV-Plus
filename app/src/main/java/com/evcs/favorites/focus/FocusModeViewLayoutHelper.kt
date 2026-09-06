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
     * - Normal state: Green badge ("🟢 2/8 Trống (150kW)"), reroute disabled.
     * - Full state: Red badge ("🔴 HẾT CHỖ!"), 1-tap reroute button revealed if alternative available.
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

        return when {
            state.isOffline -> {
                FloatingViewState(
                    stationName = state.targetStation.name,
                    badgeText = state.statusBadgeText,
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
                    stationName = state.targetStation.name,
                    badgeText = state.statusBadgeText,
                    badgeColorToken = FocusBadgeColor.RED,
                    isRerouteAvailable = state.alternativeStation != null,
                    rerouteButtonText = rerouteLabel,
                    isOffline = false,
                    distanceText = distText
                )
            }
            else -> {
                FloatingViewState(
                    stationName = state.targetStation.name,
                    badgeText = state.statusBadgeText,
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
     * Creates standard [WindowManager.LayoutParams] for the floating overlay view,
     * configured with TYPE_APPLICATION_OVERLAY and non-focusable flags.
     */
    fun createWindowLayoutParams(
        x: Int = DEFAULT_MARGIN,
        y: Int = 150
    ): WindowManager.LayoutParams {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
    }
}
