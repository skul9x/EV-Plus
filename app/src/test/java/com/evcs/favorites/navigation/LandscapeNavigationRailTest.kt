package com.evcs.favorites.navigation

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Single Verification Test for Phase 02: Fullscreen Immersive Sticky Mode & Compact Centered Navigation Rail.
 *
 * Verifies:
 * 1. Rail contract constants (RAIL_WIDTH_DP = 58.dp, touch target >= 48-52dp, icon size 26-28dp).
 * 2. Strict action order sequence: NEARBY -> FAVORITES -> SETTINGS -> REFRESH.
 * 3. Action dispatch handlers (onTabSelected, onSettingsClick, onRefreshClick) and refresh debounce guards.
 * 4. Refresh loading animation state mapping (rotation angle and content description).
 * 5. Tab selection state mapping for active indicators.
 */
class LandscapeNavigationRailTest {

    @Test
    fun testRailContractConstants() {
        assertEquals("Top-level RAIL_WIDTH_DP must be exactly 58.dp", 58.dp, RAIL_WIDTH_DP)
        assertEquals("AppNavigationRailDefaults.RAIL_WIDTH_DP must be 58.dp", 58.dp, AppNavigationRailDefaults.RAIL_WIDTH_DP)
        assertEquals("Icon size must be 26.dp (within 26-28dp range)", 26.dp, AppNavigationRailDefaults.ICON_SIZE)
        assertEquals("Touch target size must be 50.dp (within >= 48-52dp touch container)", 50.dp, AppNavigationRailDefaults.TOUCH_TARGET_SIZE)
        assertEquals("Item spacing must be 16.dp", 16.dp, AppNavigationRailDefaults.ITEM_SPACING)
    }

    @Test
    fun testActionOrderSequence() {
        val actionOrder = AppNavigationRailDefaults.ACTION_ORDER
        assertEquals("Navigation rail must define exactly 4 actions", 4, actionOrder.size)

        // Strict automotive action sequence
        assertEquals("Index 0 must strictly be NEARBY", NavigationRailAction.NEARBY, actionOrder[0])
        assertEquals("Index 1 must strictly be FAVORITES", NavigationRailAction.FAVORITES, actionOrder[1])
        assertEquals("Index 2 must strictly be SETTINGS", NavigationRailAction.SETTINGS, actionOrder[2])
        assertEquals("Index 3 must strictly be REFRESH", NavigationRailAction.REFRESH, actionOrder[3])

        // Verify titles/labels
        assertEquals("Quanh đây", NavigationRailAction.NEARBY.title)
        assertEquals("Yêu thích", NavigationRailAction.FAVORITES.title)
        assertEquals("Cài đặt", NavigationRailAction.SETTINGS.title)
        assertEquals("Làm mới", NavigationRailAction.REFRESH.title)
    }

    @Test
    fun testActionDispatchHandlers() {
        var selectedTab: AppTab? = null
        var settingsClicked = false
        var refreshClickCount = 0

        val onTabSelected: (AppTab) -> Unit = { selectedTab = it }
        val onSettingsClick: () -> Unit = { settingsClicked = true }
        val onRefreshClick: () -> Unit = { refreshClickCount++ }

        // Test NEARBY action dispatch
        AppNavigationRailHelper.handleRailAction(
            action = NavigationRailAction.NEARBY,
            onTabSelected = onTabSelected,
            onSettingsClick = onSettingsClick,
            onRefreshClick = onRefreshClick,
            isRefreshing = false
        )
        assertEquals(AppTab.NEARBY, selectedTab)
        assertFalse(settingsClicked)
        assertEquals(0, refreshClickCount)

        // Test FAVORITES action dispatch
        AppNavigationRailHelper.handleRailAction(
            action = NavigationRailAction.FAVORITES,
            onTabSelected = onTabSelected,
            onSettingsClick = onSettingsClick,
            onRefreshClick = onRefreshClick,
            isRefreshing = false
        )
        assertEquals(AppTab.FAVORITES, selectedTab)
        assertFalse(settingsClicked)
        assertEquals(0, refreshClickCount)

        // Test SETTINGS action dispatch
        AppNavigationRailHelper.handleRailAction(
            action = NavigationRailAction.SETTINGS,
            onTabSelected = onTabSelected,
            onSettingsClick = onSettingsClick,
            onRefreshClick = onRefreshClick,
            isRefreshing = false
        )
        assertTrue("Settings click handler must be invoked", settingsClicked)
        assertEquals(0, refreshClickCount)

        // Test REFRESH action dispatch when idle (isRefreshing = false)
        AppNavigationRailHelper.handleRailAction(
            action = NavigationRailAction.REFRESH,
            onTabSelected = onTabSelected,
            onSettingsClick = onSettingsClick,
            onRefreshClick = onRefreshClick,
            isRefreshing = false
        )
        assertEquals("Refresh click handler must be invoked when idle", 1, refreshClickCount)

        // Test REFRESH action dispatch guarded when already refreshing (isRefreshing = true)
        AppNavigationRailHelper.handleRailAction(
            action = NavigationRailAction.REFRESH,
            onTabSelected = onTabSelected,
            onSettingsClick = onSettingsClick,
            onRefreshClick = onRefreshClick,
            isRefreshing = true
        )
        assertEquals("Refresh click handler must NOT be invoked when already refreshing", 1, refreshClickCount)
    }

    @Test
    fun testRefreshLoadingAnimationStateMapping() {
        // When not refreshing: rotation angle should always be 0f regardless of animation angle
        assertEquals(0f, AppNavigationRailHelper.resolveRefreshRotationAngle(isRefreshing = false, animatedAngle = 0f), 0.001f)
        assertEquals(0f, AppNavigationRailHelper.resolveRefreshRotationAngle(isRefreshing = false, animatedAngle = 180f), 0.001f)
        assertEquals(0f, AppNavigationRailHelper.resolveRefreshRotationAngle(isRefreshing = false, animatedAngle = 360f), 0.001f)

        // When refreshing: rotation angle reflects animated float
        assertEquals(45f, AppNavigationRailHelper.resolveRefreshRotationAngle(isRefreshing = true, animatedAngle = 45f), 0.001f)
        assertEquals(180f, AppNavigationRailHelper.resolveRefreshRotationAngle(isRefreshing = true, animatedAngle = 180f), 0.001f)
        assertEquals(360f, AppNavigationRailHelper.resolveRefreshRotationAngle(isRefreshing = true, animatedAngle = 360f), 0.001f)

        // Should allow refresh gating
        assertTrue(AppNavigationRailHelper.shouldAllowRefresh(isRefreshing = false))
        assertFalse(AppNavigationRailHelper.shouldAllowRefresh(isRefreshing = true))

        // Content description mapping
        assertEquals("Làm mới dữ liệu", AppNavigationRailHelper.resolveRefreshContentDescription(isRefreshing = false))
        assertEquals("Đang làm mới", AppNavigationRailHelper.resolveRefreshContentDescription(isRefreshing = true))
    }

    @Test
    fun testTabSelectionStateMapping() {
        // When current tab is NEARBY
        assertTrue(AppNavigationRailHelper.isTabSelected(AppTab.NEARBY, currentTab = AppTab.NEARBY))
        assertFalse(AppNavigationRailHelper.isTabSelected(AppTab.FAVORITES, currentTab = AppTab.NEARBY))

        // When current tab is FAVORITES
        assertFalse(AppNavigationRailHelper.isTabSelected(AppTab.NEARBY, currentTab = AppTab.FAVORITES))
        assertTrue(AppNavigationRailHelper.isTabSelected(AppTab.FAVORITES, currentTab = AppTab.FAVORITES))
    }
}
