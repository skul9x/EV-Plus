package com.evcs.favorites.ui.layout

import com.evcs.favorites.data.model.Station
import com.evcs.favorites.navigation.AppTab

/**
 * Encapsulates dimension allocation for Automotive Master-Detail layouts.
 */
data class MasterDetailWidthAllocation(
    val masterWidthDp: Float,
    val detailWidthDp: Float,
    val totalAvailableWidthDp: Float,
    val navRailWidthDp: Float = AdaptiveLayoutHelper.DEFAULT_NAV_RAIL_WIDTH_DP
) {
    val masterRatio: Float
        get() = if (totalAvailableWidthDp > 0f) masterWidthDp / totalAvailableWidthDp else 0f

    val detailRatio: Float
        get() = if (totalAvailableWidthDp > 0f) detailWidthDp / totalAvailableWidthDp else 0f
}

/**
 * Pure Kotlin helper for automotive display dimension detection, master-detail
 * proportional width calculations, and landscape state resolution.
 */
object AdaptiveLayoutHelper {

    const val DEFAULT_NAV_RAIL_WIDTH_DP = 72f
    const val NAVIGATION_RAIL_WIDTH_DP = 72f
    const val COMPACT_NAV_RAIL_WIDTH_DP = 58f
    const val MIN_MASTER_WIDTH_DP = 320f
    const val MAX_MASTER_WIDTH_DP = 480f
    const val MIN_LANDSCAPE_WIDTH_DP = 600f
    const val DEFAULT_MASTER_RATIO = 0.38f
    const val MIN_TOUCH_TARGET_DP = 56f

    /**
     * Determines whether display dimensions qualify for Automotive Landscape Mode.
     * True when width > height AND width >= 600dp.
     *
     * Supported displays:
     * - 7-inch Android Box: 1024x600 -> true
     * - 9-inch / 10.1-inch Android Box: 1280x720 -> true
     * - 12.3-inch Ultrawide Head Unit: 1920x720 -> true
     * - Handheld smartphone in portrait: 1080x2400 -> false
     */
    fun isLandscapeMode(widthDp: Float, heightDp: Float): Boolean {
        return widthDp > heightDp && widthDp >= MIN_LANDSCAPE_WIDTH_DP
    }

    /**
     * Calculates width allocation between Master list (35% - 40%, clamped to [320dp, 480dp])
     * and Detail pane (60% - 65%) after deducting NavigationRail width (default 72dp).
     */
    fun calculateMasterDetailWidths(
        totalWidthDp: Float,
        navRailWidthDp: Float = DEFAULT_NAV_RAIL_WIDTH_DP
    ): MasterDetailWidthAllocation {
        val availableWidthDp = (totalWidthDp - navRailWidthDp).coerceAtLeast(0f)
        val rawMasterWidth = availableWidthDp * DEFAULT_MASTER_RATIO
        val masterWidthDp = rawMasterWidth.coerceIn(MIN_MASTER_WIDTH_DP, MAX_MASTER_WIDTH_DP)
        val detailWidthDp = (availableWidthDp - masterWidthDp).coerceAtLeast(0f)

        return MasterDetailWidthAllocation(
            masterWidthDp = masterWidthDp,
            detailWidthDp = detailWidthDp,
            totalAvailableWidthDp = availableWidthDp,
            navRailWidthDp = navRailWidthDp
        )
    }

    /**
     * Resolves auto-selection of the nearest (first) station on wide/landscape displays
     * when current selection is null and the station list is non-empty.
     */
    fun resolveAutoSelectedStation(
        isLandscape: Boolean,
        currentSelection: Station?,
        stations: List<Station>
    ): Station? {
        if (!isLandscape) return currentSelection
        return currentSelection ?: stations.firstOrNull()
    }

    /**
     * Determines whether a station card should display active highlighted selection border (2dp EmeraldPrimary).
     */
    fun isStationCardSelected(
        isLandscape: Boolean,
        cardStationId: String,
        selectedStationId: String?
    ): Boolean {
        return isLandscape && selectedStationId != null && cardStationId == selectedStationId
    }

    /**
     * Validates that touch target dimension meets minimum automotive safety standards (>= 56dp).
     */
    fun isTouchTargetCompliant(dimensionDp: Float): Boolean {
        return dimensionDp >= MIN_TOUCH_TARGET_DP
    }

    /**
     * Maps an incoming tab change event.
     */
    fun resolveSelectedTab(currentTab: AppTab, targetTab: AppTab): AppTab {
        return targetTab
    }
}
