package com.evcs.favorites.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import com.evcs.favorites.ui.theme.AppIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Single Comprehensive Verification Test for Phase 01:
 * Navigation Core & Top-Level Tab Integration.
 *
 * Verifies:
 * 1. AppTab enum contains exactly 3 entries (NEARBY, FAVORITES, SETTINGS) with correct labels and icons.
 * 2. AppNavigationRailHelper.isTabSelected returns true for AppTab.SETTINGS when active and false when inactive.
 * 3. AppNavigationRailHelper.handleRailAction for SETTINGS correctly triggers tab selection AppTab.SETTINGS and maintains backward compatibility.
 * 4. Back navigation logic contract: non-nearby tabs (FAVORITES, SETTINGS) navigate back to NEARBY, while NEARBY does not intercept system exit.
 * 5. Absence of settings gear icon in NearbyScreen and FavoritesScreen TopAppBars, and CustomConfigPromptDialog triggers SETTINGS selection.
 */
class SettingsNavigationTabIntegrationTest {

    private fun resolveFile(vararg candidatePaths: String): File {
        for (path in candidatePaths) {
            val file = File(path)
            if (file.exists()) return file
        }
        val userDir = System.getProperty("user.dir") ?: "."
        for (path in candidatePaths) {
            val file = File(userDir, path)
            if (file.exists()) return file
        }
        throw AssertionError("Could not find any file from candidates: ${candidatePaths.joinToString()} (user.dir=$userDir)")
    }

    // =========================================================================
    // 1. AppTab Enum Structure, Labels, and Icons
    // =========================================================================

    @Test
    fun testAppTabEnumStructureAndOrder() {
        val tabs = AppTab.entries
        assertEquals("AppTab must contain exactly 3 top-level destinations", 3, tabs.size)

        // Strict sequence: NEARBY -> FAVORITES -> SETTINGS
        assertEquals("Tab 0 must strictly be NEARBY", AppTab.NEARBY, tabs[0])
        assertEquals("Tab 1 must strictly be FAVORITES", AppTab.FAVORITES, tabs[1])
        assertEquals("Tab 2 must strictly be SETTINGS", AppTab.SETTINGS, tabs[2])

        // Verify NEARBY attributes
        assertEquals("Quanh đây", AppTab.NEARBY.label)
        assertEquals(Icons.Filled.LocationOn, AppTab.NEARBY.selectedIcon)
        assertEquals(AppIcons.LocationOn, AppTab.NEARBY.unselectedIcon)

        // Verify FAVORITES attributes
        assertEquals("Yêu thích", AppTab.FAVORITES.label)
        assertEquals(Icons.Filled.Favorite, AppTab.FAVORITES.selectedIcon)
        assertEquals(AppIcons.FavoriteBorder, AppTab.FAVORITES.unselectedIcon)

        // Verify SETTINGS attributes
        assertEquals("Cài đặt", AppTab.SETTINGS.label)
        assertEquals(Icons.Filled.Settings, AppTab.SETTINGS.selectedIcon)
        assertEquals(AppIcons.Settings, AppTab.SETTINGS.unselectedIcon)

        // ValueOf resolution
        assertEquals(AppTab.SETTINGS, AppTab.valueOf("SETTINGS"))
        assertEquals(AppTab.NEARBY, AppTab.valueOf("NEARBY"))
        assertEquals(AppTab.FAVORITES, AppTab.valueOf("FAVORITES"))
    }

    // =========================================================================
    // 2. Tab Selection State Mapping
    // =========================================================================

    @Test
    fun testAppNavigationRailHelperTabSelectionState() {
        // When current tab is SETTINGS
        assertTrue(
            "isTabSelected must return true for AppTab.SETTINGS when currentTab is SETTINGS",
            AppNavigationRailHelper.isTabSelected(AppTab.SETTINGS, currentTab = AppTab.SETTINGS)
        )
        assertFalse(
            "isTabSelected must return false for AppTab.NEARBY when currentTab is SETTINGS",
            AppNavigationRailHelper.isTabSelected(AppTab.NEARBY, currentTab = AppTab.SETTINGS)
        )
        assertFalse(
            "isTabSelected must return false for AppTab.FAVORITES when currentTab is SETTINGS",
            AppNavigationRailHelper.isTabSelected(AppTab.FAVORITES, currentTab = AppTab.SETTINGS)
        )

        // When current tab is NEARBY
        assertTrue(AppNavigationRailHelper.isTabSelected(AppTab.NEARBY, currentTab = AppTab.NEARBY))
        assertFalse(AppNavigationRailHelper.isTabSelected(AppTab.SETTINGS, currentTab = AppTab.NEARBY))
        assertFalse(AppNavigationRailHelper.isTabSelected(AppTab.FAVORITES, currentTab = AppTab.NEARBY))

        // When current tab is FAVORITES
        assertTrue(AppNavigationRailHelper.isTabSelected(AppTab.FAVORITES, currentTab = AppTab.FAVORITES))
        assertFalse(AppNavigationRailHelper.isTabSelected(AppTab.SETTINGS, currentTab = AppTab.FAVORITES))
        assertFalse(AppNavigationRailHelper.isTabSelected(AppTab.NEARBY, currentTab = AppTab.FAVORITES))
    }

    // =========================================================================
    // 3. Action Dispatch for NavigationRailAction.SETTINGS & Backward Compatibility
    // =========================================================================

    @Test
    fun testAppNavigationRailHelperActionDispatch() {
        var selectedTab: AppTab? = null
        var settingsClicked = false
        var homeClicked = false
        var refreshCount = 0

        val onHomeClick: () -> Unit = { homeClicked = true }
        val onTabSelected: (AppTab) -> Unit = { selectedTab = it }
        val onSettingsClick: () -> Unit = { settingsClicked = true }
        val onRefreshClick: () -> Unit = { refreshCount++ }

        // Test SETTINGS action dispatch: triggers both onTabSelected(AppTab.SETTINGS) and onSettingsClick
        AppNavigationRailHelper.handleRailAction(
            action = NavigationRailAction.SETTINGS,
            onHomeClick = onHomeClick,
            onTabSelected = onTabSelected,
            onSettingsClick = onSettingsClick,
            onRefreshClick = onRefreshClick,
            isRefreshing = false
        )
        assertEquals("SETTINGS rail action must select AppTab.SETTINGS", AppTab.SETTINGS, selectedTab)
        assertTrue("SETTINGS rail action must also invoke onSettingsClick for backward compatibility", settingsClicked)
        assertFalse(homeClicked)
        assertEquals(0, refreshCount)

        // Reset and test NEARBY
        selectedTab = null
        settingsClicked = false
        AppNavigationRailHelper.handleRailAction(
            action = NavigationRailAction.NEARBY,
            onHomeClick = onHomeClick,
            onTabSelected = onTabSelected,
            onSettingsClick = onSettingsClick,
            onRefreshClick = onRefreshClick,
            isRefreshing = false
        )
        assertEquals("NEARBY rail action must select AppTab.NEARBY", AppTab.NEARBY, selectedTab)

        // Reset and test FAVORITES
        selectedTab = null
        AppNavigationRailHelper.handleRailAction(
            action = NavigationRailAction.FAVORITES,
            onHomeClick = onHomeClick,
            onTabSelected = onTabSelected,
            onSettingsClick = onSettingsClick,
            onRefreshClick = onRefreshClick,
            isRefreshing = false
        )
        assertEquals("FAVORITES rail action must select AppTab.FAVORITES", AppTab.FAVORITES, selectedTab)

        // Reset and test HOME
        AppNavigationRailHelper.handleRailAction(
            action = NavigationRailAction.HOME,
            onHomeClick = onHomeClick,
            onTabSelected = onTabSelected,
            onSettingsClick = onSettingsClick,
            onRefreshClick = onRefreshClick,
            isRefreshing = false
        )
        assertTrue("HOME rail action must invoke onHomeClick", homeClicked)

        // Test REFRESH idle vs debounced
        AppNavigationRailHelper.handleRailAction(
            action = NavigationRailAction.REFRESH,
            onHomeClick = onHomeClick,
            onTabSelected = onTabSelected,
            onSettingsClick = onSettingsClick,
            onRefreshClick = onRefreshClick,
            isRefreshing = false
        )
        assertEquals(1, refreshCount)

        AppNavigationRailHelper.handleRailAction(
            action = NavigationRailAction.REFRESH,
            onHomeClick = onHomeClick,
            onTabSelected = onTabSelected,
            onSettingsClick = onSettingsClick,
            onRefreshClick = onRefreshClick,
            isRefreshing = true
        )
        assertEquals("Refresh action must be guarded while refreshing", 1, refreshCount)
    }

    // =========================================================================
    // 4. Back Navigation Logic Contract
    // =========================================================================

    @Test
    fun testBackNavigationLogicContract() {
        // When user is on SETTINGS tab: back navigation must be intercepted and target NEARBY
        assertTrue(
            "Back navigation must be intercepted when on SETTINGS tab",
            AppNavigationRailHelper.shouldInterceptBack(AppTab.SETTINGS)
        )
        assertEquals(
            "Back target tab from SETTINGS must be NEARBY",
            AppTab.NEARBY,
            AppNavigationRailHelper.resolveBackTargetTab(AppTab.SETTINGS)
        )

        // When user is on FAVORITES tab: back navigation must be intercepted and target NEARBY
        assertTrue(
            "Back navigation must be intercepted when on FAVORITES tab",
            AppNavigationRailHelper.shouldInterceptBack(AppTab.FAVORITES)
        )
        assertEquals(
            "Back target tab from FAVORITES must be NEARBY",
            AppTab.NEARBY,
            AppNavigationRailHelper.resolveBackTargetTab(AppTab.FAVORITES)
        )

        // When user is on NEARBY tab: back navigation must NOT be intercepted, enabling standard system exit
        assertFalse(
            "Back navigation must NOT be intercepted when on NEARBY tab to allow system exit",
            AppNavigationRailHelper.shouldInterceptBack(AppTab.NEARBY)
        )
    }

    // =========================================================================
    // 5. Clean TopAppBars (Absence of Settings Gear Icon) & Setup Action Contract
    // =========================================================================

    @Test
    fun testAbsenceOfSettingsGearIconInTopAppBars() {
        val nearbyFile = resolveFile(
            "app/src/main/java/com/evcs/favorites/ui/screens/NearbyScreen.kt",
            "src/main/java/com/evcs/favorites/ui/screens/NearbyScreen.kt"
        )
        val favoritesFile = resolveFile(
            "app/src/main/java/com/evcs/favorites/ui/screens/FavoritesScreen.kt",
            "src/main/java/com/evcs/favorites/ui/screens/FavoritesScreen.kt"
        )

        val nearbySource = nearbyFile.readText()
        val favoritesSource = favoritesFile.readText()

        // Verify NearbyScreen TopAppBar actions do not include settings gear button
        val nearbyTopAppBarSection = nearbySource.substringAfter("actions = {").substringBefore("colors = TopAppBarDefaults")
        assertFalse(
            "NearbyScreen TopAppBar actions must not contain settings gear icon or description",
            nearbyTopAppBarSection.contains("Cài đặt lộ trình") || nearbyTopAppBarSection.contains("Icons.Default.Settings")
        )

        // Verify FavoritesScreen TopAppBar actions do not include settings gear button
        val favoritesTopAppBarSection = favoritesSource.substringAfter("actions = {").substringBefore("colors = TopAppBarDefaults")
        assertFalse(
            "FavoritesScreen TopAppBar actions must not contain settings gear icon or description",
            favoritesTopAppBarSection.contains("Cài đặt lộ trình") || favoritesTopAppBarSection.contains("Icons.Default.Settings")
        )

        // Verify CustomConfigPromptDialog in NearbyScreen invokes onNavigateToSettings
        assertTrue(
            "NearbyScreen must wire CustomConfigPromptDialog onConfirmSetup to onNavigateToSettings",
            nearbySource.contains("CustomConfigPromptDialog") && nearbySource.contains("onNavigateToSettings()")
        )
    }
}
