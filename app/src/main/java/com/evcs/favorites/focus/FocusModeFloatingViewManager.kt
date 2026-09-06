package com.evcs.favorites.focus

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Manages the lifecycle, touch gestures, and state rendering of the floating overlay capsule
 * (Android System Alert Overlay) that floats above external navigation apps (e.g. Google Maps).
 *
 * Implements smooth touch dragging, edge-snapping, zero-leak WindowManager removal,
 * and handles Normal, Full (1-tap reroute), and Offline states.
 */
class FocusModeFloatingViewManager(
    private val context: Context,
    private val windowManager: WindowManager? = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager,
    private val onDismiss: () -> Unit,
    private val onReroute: (AlternativeStationRecommendation) -> Unit
) {

    companion object {
        fun canDrawOverlays(context: Context): Boolean {
            return FocusModeViewLayoutHelper.canDrawOverlays(context)
        }
    }

    private var floatingRootView: View? = null
    private var windowLayoutParams: WindowManager.LayoutParams? = null
    private var isViewAttached = false

    private var stationNameView: TextView? = null
    private var statusBadgeView: TextView? = null
    private var rerouteButtonView: TextView? = null
    private var currentAlternativeStation: AlternativeStationRecommendation? = null

    val isAttached: Boolean
        get() = isViewAttached

    /**
     * Instantiates and attaches the floating capsule to the WindowManager.
     */
    fun showOverlay(initialState: FocusModeState) {
        if (isViewAttached) {
            updateView(initialState)
            return
        }

        try {
            val view = buildCapsuleView()
            val params = FocusModeViewLayoutHelper.createWindowLayoutParams()
            windowLayoutParams = params
            floatingRootView = view

            setupTouchListener(view, params)
            windowManager?.addView(view, params)
            isViewAttached = true

            updateView(initialState)
        } catch (e: Exception) {
            isViewAttached = false
            floatingRootView = null
            windowLayoutParams = null
        }
    }

    /**
     * Updates text, badge colors, and reroute CTA visibility based on new [FocusModeState].
     */
    fun updateView(state: FocusModeState) {
        if (!isViewAttached || floatingRootView == null) return

        currentAlternativeStation = state.alternativeStation
        val viewState = FocusModeViewLayoutHelper.formatViewState(state)

        stationNameView?.text = viewState.stationName
        statusBadgeView?.text = viewState.badgeText

        val badgeColorInt = when (viewState.badgeColorToken) {
            FocusBadgeColor.GREEN -> Color.parseColor("#4CAF50")
            FocusBadgeColor.RED -> Color.parseColor("#FF5252")
            FocusBadgeColor.AMBER -> Color.parseColor("#FFA000")
        }
        statusBadgeView?.setTextColor(badgeColorInt)

        if (viewState.isRerouteAvailable && viewState.rerouteButtonText != null) {
            rerouteButtonView?.visibility = View.VISIBLE
            rerouteButtonView?.text = viewState.rerouteButtonText
        } else {
            rerouteButtonView?.visibility = View.GONE
        }

        try {
            windowLayoutParams?.let { params ->
                windowManager?.updateViewLayout(floatingRootView, params)
            }
        } catch (e: Exception) {
            // Safe handling against transient window manager errors
        }
    }

    /**
     * Safely detaches the floating window view from WindowManager, preventing window leaks.
     */
    fun removeOverlay() {
        if (isViewAttached && floatingRootView != null) {
            try {
                windowManager?.removeView(floatingRootView)
            } catch (e: Exception) {
                // Ignore if view was already removed or invalid token
            } finally {
                floatingRootView = null
                windowLayoutParams = null
                stationNameView = null
                statusBadgeView = null
                rerouteButtonView = null
                isViewAttached = false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var screenWidth = 0
        var screenHeight = 0
        var isDragging = false

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val displayMetrics = context.resources.displayMetrics
                    screenWidth = displayMetrics.widthPixels
                    screenHeight = displayMetrics.heightPixels
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    if (abs(dx) > 10 || abs(dy) > 10 || isDragging) {
                        isDragging = true
                        val newX = initialX + dx
                        val newY = initialY + dy
                        val (clampedX, clampedY) = FocusModeViewLayoutHelper.clampPosition(
                            x = newX,
                            y = newY,
                            viewWidth = v.width,
                            viewHeight = v.height,
                            screenWidth = screenWidth,
                            screenHeight = screenHeight
                        )
                        params.x = clampedX
                        params.y = clampedY
                        try {
                            windowManager?.updateViewLayout(v, params)
                        } catch (e: Exception) {
                            // Safe gesture update
                        }
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        val snappedX = FocusModeViewLayoutHelper.calculateSnapToEdgeX(
                            currentX = params.x,
                            viewWidth = v.width,
                            screenWidth = screenWidth
                        )
                        params.x = snappedX
                        try {
                            windowManager?.updateViewLayout(v, params)
                        } catch (e: Exception) {
                            // Safe snap update
                        }
                        true
                    } else {
                        v.performClick()
                        false
                    }
                }
                else -> false
            }
        }
    }

    private fun buildCapsuleView(): View {
        val dpDensity = context.resources.displayMetrics.density
        fun dp(px: Int): Int = (px * dpDensity).toInt()

        // Root container: vertical linear layout with dark translucent rounded capsule background
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val bgDrawable = GradientDrawable().apply {
                setColor(Color.parseColor("#E61E1E24"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            background = bgDrawable
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true
            isFocusable = false
        }

        // Header row: [Station Name + Badge] and [Close Button 'X']
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Left info column
        val infoCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                dp(170),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val nameTv = TextView(context).apply {
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        stationNameView = nameTv
        infoCol.addView(nameTv)

        val badgeTv = TextView(context).apply {
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#4CAF50"))
            maxLines = 1
        }
        statusBadgeView = badgeTv
        infoCol.addView(badgeTv)

        headerRow.addView(infoCol)

        // Close [X] button
        val closeBtn = TextView(context).apply {
            text = "✕"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#B0B0B0"))
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(4), dp(4))
            isClickable = true
            setOnClickListener {
                onDismiss()
            }
        }
        headerRow.addView(closeBtn)

        root.addView(headerRow)

        // 1-Tap Reroute button
        val rerouteBtn = TextView(context).apply {
            val btnBg = GradientDrawable().apply {
                setColor(Color.parseColor("#1565C0"))
                cornerRadius = dp(12).toFloat()
            }
            background = btnBg
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
            }
            layoutParams = lp
            visibility = View.GONE
            isClickable = true
            setOnClickListener {
                currentAlternativeStation?.let { alt ->
                    onReroute(alt)
                }
            }
        }
        rerouteButtonView = rerouteBtn
        root.addView(rerouteBtn)

        return root
    }
}
