package com.evcs.favorites.navigation

import android.content.Intent
import androidx.compose.ui.unit.dp
import com.evcs.favorites.ui.components.RoutingSettingsModalHelper
import com.evcs.favorites.ui.components.SettingsPresentationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Single Comprehensive Verification Test for Phase 01:
 * Navigation Rail System Home Action & In-App Immersive Settings Panel.
 *
 * Verifies:
 * 1. ACTION_ORDER strictly starts with HOME at index 0, followed by NEARBY, FAVORITES, SETTINGS, REFRESH.
 * 2. handleRailAction routes NavigationRailAction.HOME to onHomeClick callback.
 * 3. SystemHomeIntentSpec builds automotive-grade launcher intent with FLAG_ACTIVITY_NEW_TASK and FLAG_ACTIVITY_RESET_TASK_IF_NEEDED.
 * 4. Settings presentation mode contracts resolve to IN_APP_PANEL for landscape and BOTTOM_SHEET for portrait.
 * 5. Automotive glanceability contract values (58dp width, 26dp icon size, 50dp touch container).
 */
class SystemHomeAndImmersiveSettingsTest {

    @Test
    fun testActionOrderContainsHomeAtIndexZero() {
        val actionOrder = AppNavigationRailDefaults.ACTION_ORDER
        assertEquals("Navigation rail must define exactly 5 actions", 5, actionOrder.size)

        // Strict automotive sequence: HOME -> NEARBY -> FAVORITES -> SETTINGS -> REFRESH
        assertEquals("Index 0 must strictly be HOME", NavigationRailAction.HOME, actionOrder[0])
        assertEquals("Index 1 must strictly be NEARBY", NavigationRailAction.NEARBY, actionOrder[1])
        assertEquals("Index 2 must strictly be FAVORITES", NavigationRailAction.FAVORITES, actionOrder[2])
        assertEquals("Index 3 must strictly be SETTINGS", NavigationRailAction.SETTINGS, actionOrder[3])
        assertEquals("Index 4 must strictly be REFRESH", NavigationRailAction.REFRESH, actionOrder[4])

        // Action titles
        assertEquals("Trang chủ xe", NavigationRailAction.HOME.title)
        assertEquals("Quanh đây", NavigationRailAction.NEARBY.title)
        assertEquals("Yêu thích", NavigationRailAction.FAVORITES.title)
        assertEquals("Cài đặt", NavigationRailAction.SETTINGS.title)
        assertEquals("Làm mới", NavigationRailAction.REFRESH.title)
    }

    @Test
    fun testRailActionDispatchesHomeClick() {
        var homeClicked = false
        var selectedTab: AppTab? = null
        var settingsClicked = false
        var refreshClickCount = 0

        val onHomeClick: () -> Unit = { homeClicked = true }
        val onTabSelected: (AppTab) -> Unit = { selectedTab = it }
        val onSettingsClick: () -> Unit = { settingsClicked = true }
        val onRefreshClick: () -> Unit = { refreshClickCount++ }

        // 1. Dispatch HOME action
        AppNavigationRailHelper.handleRailAction(
            action = NavigationRailAction.HOME,
            onHomeClick = onHomeClick,
            onTabSelected = onTabSelected,
            onSettingsClick = onSettingsClick,
            onRefreshClick = onRefreshClick,
            isRefreshing = false
        )

        assertTrue("onHomeClick must be invoked when HOME action is dispatched", homeClicked)
        assertEquals(null, selectedTab)
        assertFalse(settingsClicked)
        assertEquals(0, refreshClickCount)

        // 2. Dispatch other actions to ensure complete routing fidelity
        AppNavigationRailHelper.handleRailAction(
            action = NavigationRailAction.NEARBY,
            onHomeClick = onHomeClick,
            onTabSelected = onTabSelected,
            onSettingsClick = onSettingsClick,
            onRefreshClick = onRefreshClick,
            isRefreshing = false
        )
        assertEquals(AppTab.NEARBY, selectedTab)

        AppNavigationRailHelper.handleRailAction(
            action = NavigationRailAction.FAVORITES,
            onHomeClick = onHomeClick,
            onTabSelected = onTabSelected,
            onSettingsClick = onSettingsClick,
            onRefreshClick = onRefreshClick,
            isRefreshing = false
        )
        assertEquals(AppTab.FAVORITES, selectedTab)

        AppNavigationRailHelper.handleRailAction(
            action = NavigationRailAction.SETTINGS,
            onHomeClick = onHomeClick,
            onTabSelected = onTabSelected,
            onSettingsClick = onSettingsClick,
            onRefreshClick = onRefreshClick,
            isRefreshing = false
        )
        assertTrue(settingsClicked)

        AppNavigationRailHelper.handleRailAction(
            action = NavigationRailAction.REFRESH,
            onHomeClick = onHomeClick,
            onTabSelected = onTabSelected,
            onSettingsClick = onSettingsClick,
            onRefreshClick = onRefreshClick,
            isRefreshing = false
        )
        assertEquals(1, refreshClickCount)

        // Debounced when isRefreshing == true
        AppNavigationRailHelper.handleRailAction(
            action = NavigationRailAction.REFRESH,
            onHomeClick = onHomeClick,
            onTabSelected = onTabSelected,
            onSettingsClick = onSettingsClick,
            onRefreshClick = onRefreshClick,
            isRefreshing = true
        )
        assertEquals("Refresh click must be guarded when already refreshing", 1, refreshClickCount)
    }

    @Test
    fun testHomeIntentSpecMatchesExpectedAutomotiveFlags() {
        val spec = AppNavigationRailHelper.buildHomeIntentSpec()

        assertEquals("Intent action must be ACTION_MAIN", Intent.ACTION_MAIN, spec.action)
        assertEquals("Intent category must be CATEGORY_HOME", Intent.CATEGORY_HOME, spec.category)

        val expectedFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        assertEquals("Intent flags must match FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_RESET_TASK_IF_NEEDED", expectedFlags, spec.flags)

        // Verify individual flag bitmasks
        assertTrue(
            "FLAG_ACTIVITY_NEW_TASK must be set",
            (spec.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0
        )
        assertTrue(
            "FLAG_ACTIVITY_RESET_TASK_IF_NEEDED must be set",
            (spec.flags and Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) != 0
        )

        // Verify conversion to Android Intent produces non-null instance
        val intent = spec.toIntent()
        org.junit.Assert.assertNotNull("Intent instance should be created", intent)

        // Verify helper createHomeIntent produces non-null intent
        val helperIntent = AppNavigationRailHelper.createHomeIntent()
        org.junit.Assert.assertNotNull("Helper intent instance should be created", helperIntent)
    }

    @Test
    fun testSettingsPresentationModeContract() {
        // In landscape: MUST resolve to IN_APP_PANEL to prevent Carlinkit / Android Box dock bar pop-up
        val landscapeMode = RoutingSettingsModalHelper.resolvePresentationMode(isLandscape = true)
        assertEquals(
            "Landscape presentation mode must be IN_APP_PANEL to preserve immersion",
            SettingsPresentationMode.IN_APP_PANEL,
            landscapeMode
        )

        // In portrait: MUST resolve to BOTTOM_SHEET for standard handheld UX
        val portraitMode = RoutingSettingsModalHelper.resolvePresentationMode(isLandscape = false)
        assertEquals(
            "Portrait presentation mode must be BOTTOM_SHEET",
            SettingsPresentationMode.BOTTOM_SHEET,
            portraitMode
        )
    }

    @Test
    fun testNavigationRailContractConstants() {
        assertEquals("Top-level RAIL_WIDTH_DP must be exactly 58.dp", 58.dp, RAIL_WIDTH_DP)
        assertEquals("AppNavigationRailDefaults.RAIL_WIDTH_DP must be 58.dp", 58.dp, AppNavigationRailDefaults.RAIL_WIDTH_DP)
        assertEquals("Icon size must be 26.dp", 26.dp, AppNavigationRailDefaults.ICON_SIZE)
        assertEquals("Touch target size must be 50.dp", 50.dp, AppNavigationRailDefaults.TOUCH_TARGET_SIZE)
        assertEquals("Item spacing must be 16.dp", 16.dp, AppNavigationRailDefaults.ITEM_SPACING)
    }
}
