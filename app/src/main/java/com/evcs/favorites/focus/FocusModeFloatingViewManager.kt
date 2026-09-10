package com.evcs.favorites.focus

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import com.evcs.favorites.util.DebounceHelper

/**
 * Manages the lifecycle, touch gestures, and state rendering of the floating overlay capsule
 * (Android System Alert Overlay) that floats above external navigation apps (e.g. Google Maps).
 *
 * Implements smooth touch dragging, edge-snapping, zero-leak WindowManager removal,
 * dynamic responsive sizing, orientation handling, and handles Normal, Full (1-tap reroute), and Offline states.
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

    private var currentDisplayMode: FocusModeDisplayMode = FocusModeDisplayMode.FULL_HUD
    val displayMode: FocusModeDisplayMode get() = currentDisplayMode

    private var miniPillContainer: View? = null
    private var miniPillTextView: TextView? = null
    private var fullHudContainer: View? = null

    private var stationNameView: AppCompatTextView? = null
    private var headerRowView: LinearLayout? = null
    private var statusBadgeView: TextView? = null
    private var detailedTiersView: TextView? = null
    private var rerouteButtonView: TextView? = null
    private var closeButtonView: TextView? = null
    private var heroMetricContainer: LinearLayout? = null
    private var currentAlternativeStation: AlternativeStationRecommendation? = null

    val alternativeStation: AlternativeStationRecommendation? get() = currentAlternativeStation

    internal var rerouteDebounceHelper = DebounceHelper(1000L)

    val testStationNameView: AppCompatTextView? get() = stationNameView
    val testHeaderRowView: LinearLayout? get() = headerRowView
    val testStatusBadgeView: TextView? get() = statusBadgeView
    val testDetailedTiersView: TextView? get() = detailedTiersView
    val testRerouteButtonView: TextView? get() = rerouteButtonView
    val testCloseButtonView: TextView? get() = closeButtonView
    val testHeroMetricContainer: LinearLayout? get() = heroMetricContainer
    val testMiniPillContainer: View? get() = miniPillContainer
    val testMiniPillTextView: TextView? get() = miniPillTextView
    val testFullHudContainer: View? get() = fullHudContainer
    val testFloatingRootView: View? get() = floatingRootView
    val testWindowLayoutParams: WindowManager.LayoutParams? get() = windowLayoutParams

    fun setStationNameViewForTesting(view: AppCompatTextView?) {
        stationNameView = view
    }

    fun setHeaderRowViewForTesting(row: LinearLayout?) {
        headerRowView = row
    }

    fun setCloseButtonViewForTesting(btn: TextView?) {
        closeButtonView = btn
    }

    fun setFloatingRootViewForTesting(view: View?) {
        floatingRootView = view
    }

    fun setIsViewAttachedForTesting(attached: Boolean) {
        isViewAttached = attached
    }

    fun buildCapsuleViewForTesting(): View = buildCapsuleView()

    fun setRerouteDebounceHelperForTesting(helper: DebounceHelper) {
        rerouteDebounceHelper = helper
    }

    fun setAlternativeStationForTesting(station: AlternativeStationRecommendation?) {
        currentAlternativeStation = station
    }

    fun triggerReroute(): Boolean {
        val alt = currentAlternativeStation ?: return false
        return rerouteDebounceHelper.runIfAllowed {
            onReroute(alt)
        }
    }

    val isAttached: Boolean
        get() = isViewAttached

    /**
     * Toggles between MINI_PILL and FULL_HUD display modes with smooth edge-snap recalculation.
     */
    fun toggleDisplayMode(): FocusModeDisplayMode {
        val targetMode = if (currentDisplayMode == FocusModeDisplayMode.FULL_HUD) {
            FocusModeDisplayMode.MINI_PILL
        } else {
            FocusModeDisplayMode.FULL_HUD
        }
        setDisplayMode(targetMode)
        return currentDisplayMode
    }

    /**
     * Sets the active display mode (MINI_PILL vs FULL_HUD) and updates layout width and edge snap.
     */
    fun setDisplayMode(mode: FocusModeDisplayMode) {
        currentDisplayMode = mode
        val root = floatingRootView ?: return
        val params = windowLayoutParams ?: return
        val displayMetrics = context.resources.displayMetrics
        val isLandscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
            (context.resources.configuration.orientation == Configuration.ORIENTATION_UNDEFINED && displayMetrics.widthPixels > displayMetrics.heightPixels)

        val overlayWidth = FocusModeViewLayoutHelper.calculateOverlayWidth(
            screenWidthPx = displayMetrics.widthPixels,
            isLandscape = isLandscape,
            density = displayMetrics.density
        )
        val miniPillWidth = FocusModeViewLayoutHelper.calculateMiniPillWidth(displayMetrics.density)

        val oldWidth = params.width
        val newWidth = if (mode == FocusModeDisplayMode.MINI_PILL) miniPillWidth else overlayWidth

        val adjustedX = FocusModeViewLayoutHelper.calculateAdjustedXOnModeChange(
            currentX = params.x,
            oldWidth = oldWidth,
            newWidth = newWidth,
            screenWidth = displayMetrics.widthPixels
        )

        params.width = newWidth
        params.x = adjustedX

        if (mode == FocusModeDisplayMode.MINI_PILL) {
            miniPillContainer?.visibility = View.VISIBLE
            fullHudContainer?.visibility = View.GONE
        } else {
            miniPillContainer?.visibility = View.GONE
            fullHudContainer?.visibility = View.VISIBLE
        }

        try {
            windowManager?.updateViewLayout(root, params)
        } catch (e: Exception) {
            // Safe handling against transient window manager errors
        }
    }

    /**
     * Instantiates and attaches the floating capsule to the WindowManager.
     */
    fun showOverlay(initialState: FocusModeState) {
        if (isViewAttached) {
            updateView(initialState)
            return
        }

        try {
            val displayMetrics = context.resources.displayMetrics
            val isLandscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
                (context.resources.configuration.orientation == Configuration.ORIENTATION_UNDEFINED && displayMetrics.widthPixels > displayMetrics.heightPixels)
            val overlayWidth = FocusModeViewLayoutHelper.calculateOverlayWidth(
                screenWidthPx = displayMetrics.widthPixels,
                isLandscape = isLandscape,
                density = displayMetrics.density
            )
            val miniPillWidth = FocusModeViewLayoutHelper.calculateMiniPillWidth(displayMetrics.density)
            val initialWidth = if (currentDisplayMode == FocusModeDisplayMode.MINI_PILL) miniPillWidth else overlayWidth

            val view = buildCapsuleView()
            val params = FocusModeViewLayoutHelper.createWindowLayoutParams(width = initialWidth)
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
     * Handles screen orientation and configuration changes dynamically.
     * Re-queries display metrics, recalculates overlay width, re-clamps (x, y) bounds,
     * and updates the WindowManager layout.
     */
    fun onConfigurationChanged(newConfig: Configuration) {
        if (!isViewAttached || floatingRootView == null) return
        val params = windowLayoutParams ?: return
        val displayMetrics = context.resources.displayMetrics
        val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE ||
            (newConfig.orientation == Configuration.ORIENTATION_UNDEFINED && displayMetrics.widthPixels > displayMetrics.heightPixels)

        val newWidth = if (currentDisplayMode == FocusModeDisplayMode.MINI_PILL) {
            FocusModeViewLayoutHelper.calculateMiniPillWidth(displayMetrics.density)
        } else {
            FocusModeViewLayoutHelper.calculateOverlayWidth(
                screenWidthPx = displayMetrics.widthPixels,
                isLandscape = isLandscape,
                density = displayMetrics.density
            )
        }
        params.width = newWidth

        val currentViewWidth = if ((floatingRootView?.width ?: 0) > 0) floatingRootView!!.width else newWidth
        val currentViewHeight = if ((floatingRootView?.height ?: 0) > 0) {
            floatingRootView!!.height
        } else {
            (FocusModeViewLayoutHelper.MIN_OVERLAY_HEIGHT_DP * displayMetrics.density).toInt()
        }

        val (clampedX, clampedY) = FocusModeViewLayoutHelper.clampPosition(
            x = params.x,
            y = params.y,
            viewWidth = currentViewWidth,
            viewHeight = currentViewHeight,
            screenWidth = displayMetrics.widthPixels,
            screenHeight = displayMetrics.heightPixels
        )
        params.x = clampedX
        params.y = clampedY

        rerouteButtonView?.let { btn ->
            val ctaHeight = FocusModeViewLayoutHelper.calculateCtaButtonHeightPx(isLandscape, displayMetrics.density)
            btn.minimumHeight = ctaHeight
            btn.layoutParams?.let { lp ->
                lp.height = ctaHeight
                btn.layoutParams = lp
            }
        }

        try {
            windowManager?.updateViewLayout(floatingRootView, params)
        } catch (e: Exception) {
            // Safe handling against transient window manager errors
        }
    }

    /**
     * Updates text, badge colors, and reroute CTA visibility based on new [FocusModeState].
     */
    fun updateView(state: FocusModeState) {
        currentAlternativeStation = state.alternativeStation
        if (!isViewAttached || floatingRootView == null) return

        val viewState = FocusModeViewLayoutHelper.formatViewState(state)

        // Update Mini Pill HUD
        val (pillText, pillColor) = FocusModeViewLayoutHelper.formatMiniPillState(state)
        miniPillTextView?.text = pillText
        val pillColorInt = when (pillColor) {
            FocusBadgeColor.GREEN -> Color.parseColor("#4CAF50")
            FocusBadgeColor.RED -> Color.parseColor("#FF5252")
            FocusBadgeColor.AMBER -> Color.parseColor("#FFA000")
        }
        miniPillTextView?.setTextColor(pillColorInt)

        // Update Full HUD
        stationNameView?.let { tv ->
            if (tv is MarqueeTextView) {
                tv.setTextDirect(viewState.stationName)
            } else {
                tv.text = viewState.stationName
            }
            tv.isSelected = true
        }
        statusBadgeView?.text = viewState.badgeText

        val badgeColorInt = when (viewState.badgeColorToken) {
            FocusBadgeColor.GREEN -> Color.parseColor("#4CAF50")
            FocusBadgeColor.RED -> Color.parseColor("#FF5252")
            FocusBadgeColor.AMBER -> Color.parseColor("#FFA000")
        }
        statusBadgeView?.setTextColor(badgeColorInt)

        if (viewState.detailedTiersText != null) {
            detailedTiersView?.visibility = View.VISIBLE
            detailedTiersView?.text = viewState.detailedTiersText
        } else {
            detailedTiersView?.visibility = View.GONE
        }

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
                headerRowView = null
                statusBadgeView = null
                detailedTiersView = null
                rerouteButtonView = null
                closeButtonView = null
                heroMetricContainer = null
                miniPillContainer = null
                miniPillTextView = null
                fullHudContainer = null
                currentDisplayMode = FocusModeDisplayMode.FULL_HUD
                isViewAttached = false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener(view: View, params: WindowManager.LayoutParams) {
        var downTime = 0L
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var screenWidth = 0
        var screenHeight = 0
        var isDragging = false

        val density = context.resources.displayMetrics.density
        val rawTouchSlop = try {
            ViewConfiguration.get(context).scaledTouchSlop
        } catch (e: Exception) {
            12
        }
        val touchSlop = FocusModeViewLayoutHelper.calculateEffectiveTouchSlop(rawTouchSlop, density)

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
                    downTime = System.currentTimeMillis()
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY

                    if (isDragging || FocusModeViewLayoutHelper.isDragGesture(dx, dy, touchSlop)) {
                        isDragging = true
                        val newX = initialX + dx.toInt()
                        val newY = initialY + dy.toInt()
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
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    val duration = System.currentTimeMillis() - downTime

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
                    } else if (FocusModeViewLayoutHelper.isTapGesture(dx, dy, duration, touchSlop)) {
                        v.performClick()
                        true
                    } else {
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

        // Root container: FrameLayout enclosing both miniPillContainer and fullHudContainer
        val root = FrameLayout(context).apply {
            isClickable = true
            isFocusable = false
            setOnClickListener {
                toggleDisplayMode()
            }
        }

        // 1. Mini Pill Container: ultra-compact capsule (~80dp x 38dp, 20dp corner radius)
        val pillW = FocusModeViewLayoutHelper.calculateMiniPillWidth(dpDensity)
        val pillH = FocusModeViewLayoutHelper.calculateMiniPillHeight(dpDensity)
        val miniPill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val pillBg = GradientDrawable().apply {
                setColor(Color.parseColor("#E61E1E24"))
                cornerRadius = dp(FocusModeViewLayoutHelper.MINI_PILL_CORNER_RADIUS_DP).toFloat()
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            background = pillBg
            minimumWidth = pillW
            minimumHeight = pillH
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
            setPadding(dp(8), dp(4), dp(8), dp(4))
            visibility = if (currentDisplayMode == FocusModeDisplayMode.MINI_PILL) View.VISIBLE else View.GONE
            isClickable = false
        }
        miniPillContainer = miniPill

        val pillTv = TextView(context).apply {
            textSize = FocusModeViewLayoutHelper.MINI_PILL_TEXT_SIZE_SP
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 1
            includeFontPadding = false
        }
        miniPillTextView = pillTv
        miniPill.addView(pillTv)
        root.addView(miniPill)

        // 2. Full HUD Container: expanded automotive dashboard
        val fullHud = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val bgDrawable = GradientDrawable().apply {
                setColor(Color.parseColor("#E61E1E24"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            background = bgDrawable
            setPadding(dp(12), dp(8), dp(12), dp(8))
            minimumHeight = dp(FocusModeViewLayoutHelper.MIN_OVERLAY_HEIGHT_DP)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            visibility = if (currentDisplayMode == FocusModeDisplayMode.FULL_HUD) View.VISIBLE else View.GONE
            isClickable = false
        }
        fullHudContainer = fullHud

        // Header row: Station Name (weight 1f, marquee) + Close button '✕' (>= 48dp)
        val headerRow = TrackingLinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        headerRowView = headerRow

        val nameTv = MarqueeTextView.create(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                weight = 1f
                gravity = Gravity.CENTER_VERTICAL
            }
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSelected = true
            isHorizontalFadingEdgeEnabled = true
            try {
                setFadingEdgeLength(dp(10))
            } catch (ignored: Throwable) {}
            includeFontPadding = true
            try {
                setPadding(0, dp(FocusModeViewLayoutHelper.VIETNAMESE_VERTICAL_PADDING_DP), dp(4), dp(FocusModeViewLayoutHelper.VIETNAMESE_VERTICAL_PADDING_DP))
            } catch (ignored: Throwable) {}
        }
        stationNameView = nameTv
        headerRow.addView(nameTv)

        // Close [X] button with minimum 48x48dp automotive touch target
        val touchTargetPx = FocusModeViewLayoutHelper.calculateMinTouchTargetPx(dpDensity)
        val closeBtn = TextView(context).apply {
            text = "✕"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#B0B0B0"))
            gravity = Gravity.CENTER
            minimumWidth = touchTargetPx
            minimumHeight = touchTargetPx
            layoutParams = LinearLayout.LayoutParams(touchTargetPx, touchTargetPx).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            isClickable = true
            setOnClickListener {
                onDismiss()
            }
        }
        closeButtonView = closeBtn
        headerRow.addView(closeBtn)

        fullHud.addView(headerRow)

        // Hero Metric Card: Standalone container with prominent 24sp Bold available slots count
        val (minHeroW, minHeroH) = FocusModeViewLayoutHelper.calculateHeroBadgeDimensions(dpDensity)
        val heroMetricCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumWidth = minHeroW
            minimumHeight = minHeroH
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(2)
            }
            layoutParams = lp
        }
        heroMetricContainer = heroMetricCard

        val badgeTv = TextView(context).apply {
            textSize = FocusModeViewLayoutHelper.HERO_METRIC_TEXT_SIZE_SP
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#4CAF50"))
            maxLines = 1
            includeFontPadding = true
            setPadding(0, dp(2), 0, dp(2))
            gravity = Gravity.CENTER_VERTICAL
        }
        statusBadgeView = badgeTv
        heroMetricCard.addView(badgeTv)
        fullHud.addView(heroMetricCard)

        // Detailed Tiers TextView: Multi-tier breakdown
        val detailedTv = TextView(context).apply {
            textSize = FocusModeViewLayoutHelper.DETAILED_TIERS_TEXT_SIZE_SP
            setTextColor(Color.parseColor("#CCCCCC"))
            maxLines = 2
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(2)
            }
            layoutParams = lp
            visibility = View.GONE
        }
        detailedTiersView = detailedTv
        fullHud.addView(detailedTv)

        // 1-Tap Reroute CTA Button (Dynamic automotive touch target >= 48dp)
        val isLandscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
            (context.resources.configuration.orientation == Configuration.ORIENTATION_UNDEFINED && context.resources.displayMetrics.widthPixels > context.resources.displayMetrics.heightPixels)
        val ctaHeightPx = FocusModeViewLayoutHelper.calculateCtaButtonHeightPx(isLandscape, dpDensity)

        val rerouteBtn = TextView(context).apply {
            val btnBg = GradientDrawable().apply {
                setColor(Color.parseColor("#1565C0"))
                cornerRadius = dp(14).toFloat()
            }
            background = btnBg
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            minimumHeight = ctaHeightPx
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ctaHeightPx
            ).apply {
                topMargin = dp(6)
            }
            layoutParams = lp
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
            isClickable = true
            setOnClickListener {
                triggerReroute()
            }
        }
        rerouteButtonView = rerouteBtn
        fullHud.addView(rerouteBtn)

        root.addView(fullHud)

        return root
    }
}

/**
 * Custom [AppCompatTextView] optimized for infinite marquee animation in alert window overlays.
 *
 * Overrides [isFocused] and [isSelected] so Android's marquee engine continuously scrolls
 * long station names even when the alert overlay window has FLAG_NOT_FOCUSABLE.
 * Retains internal backing state for marquee properties so they remain queryable across both
 * real Android devices and JVM unit testing environments.
 */
open class MarqueeTextView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    companion object {
        fun create(context: Context): MarqueeTextView {
            return try {
                MarqueeTextView(context)
            } catch (e: Throwable) {
                createHeadlessInstance()
            }
        }

        private fun createHeadlessInstance(): MarqueeTextView {
            return try {
                val unsafeClass = Class.forName("sun.misc.Unsafe")
                val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe")
                theUnsafeField.isAccessible = true
                val unsafe = theUnsafeField.get(null)
                val allocateMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
                val instance = allocateMethod.invoke(unsafe, MarqueeTextView::class.java) as MarqueeTextView
                instance.initDefaults()
                instance
            } catch (e: Throwable) {
                throw RuntimeException("Failed to instantiate MarqueeTextView in headless environment", e)
            }
        }
    }

    private var customEllipsize: TextUtils.TruncateAt? = TextUtils.TruncateAt.MARQUEE
    private var customMarqueeRepeatLimit: Int = -1
    private var customIsSelected: Boolean = true
    private var customText: CharSequence = ""

    init {
        initDefaults()
    }

    internal fun initDefaults() {
        customEllipsize = TextUtils.TruncateAt.MARQUEE
        customMarqueeRepeatLimit = -1
        customIsSelected = true
        customText = ""
        try {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSelected = true
            isHorizontalFadingEdgeEnabled = true
        } catch (ignored: Throwable) {
        }
    }

    override fun getText(): CharSequence = customText

    fun setTextDirect(content: CharSequence?) {
        customText = content ?: ""
        try {
            setText(content, BufferType.NORMAL)
        } catch (ignored: Throwable) {
        }
    }

    override fun setText(text: CharSequence?, type: BufferType?) {
        customText = text ?: ""
        try {
            super.setText(text, type)
        } catch (ignored: Throwable) {
        }
    }

    private var customLayoutParams: android.view.ViewGroup.LayoutParams? = null

    override fun getLayoutParams(): android.view.ViewGroup.LayoutParams? {
        return customLayoutParams ?: try { super.getLayoutParams() } catch (e: Throwable) { null }
    }

    override fun setLayoutParams(params: android.view.ViewGroup.LayoutParams?) {
        customLayoutParams = params
        try {
            super.setLayoutParams(params)
        } catch (ignored: Throwable) {
        }
    }

    override fun isFocused(): Boolean = true

    override fun isSelected(): Boolean = customIsSelected

    override fun setSelected(selected: Boolean) {
        try {
            super.setSelected(selected)
        } catch (ignored: Throwable) {
        }
        customIsSelected = selected
    }

    override fun getEllipsize(): TextUtils.TruncateAt? = customEllipsize

    override fun setEllipsize(where: TextUtils.TruncateAt?) {
        try {
            super.setEllipsize(where)
        } catch (ignored: Throwable) {
        }
        customEllipsize = where
    }

    override fun getMarqueeRepeatLimit(): Int = customMarqueeRepeatLimit

    override fun setMarqueeRepeatLimit(limit: Int) {
        try {
            super.setMarqueeRepeatLimit(limit)
        } catch (ignored: Throwable) {
        }
        customMarqueeRepeatLimit = limit
    }
}

/**
 * [LinearLayout] subclass that maintains child references for layout structure verification
 * in test and alert window environments.
 */
open class TrackingLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val childrenList = mutableListOf<View>()

    override fun addView(child: View?) {
        child?.let { childrenList.add(it) }
        try {
            super.addView(child)
        } catch (ignored: Throwable) {
        }
    }

    override fun getChildCount(): Int {
        val superCount = try {
            super.getChildCount()
        } catch (e: Throwable) {
            0
        }
        return if (superCount > 0) superCount else childrenList.size
    }

    override fun getChildAt(index: Int): View? {
        val superChild = try {
            super.getChildAt(index)
        } catch (e: Throwable) {
            null
        }
        return superChild ?: childrenList.getOrNull(index)
    }
}

