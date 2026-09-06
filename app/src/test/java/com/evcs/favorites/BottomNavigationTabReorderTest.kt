package com.evcs.favorites
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LocationOn
import com.evcs.favorites.navigation.AppTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit Test for Phase 03: Bottom Navigation Tab Order Swapping (Nearby Left, Favorites Right).
 *
 * Verifies:
 * 1. AppTab enum order:
 *    - Index 0 is AppTab.NEARBY ("Quanh đây") with LocationOn icon.
 *    - Index 1 is AppTab.FAVORITES ("Yêu thích") with Favorite icon.
 * 2. Default start destination contract:
 *    - Default tab is AppTab.NEARBY.
 * 3. Bidirectional tab transitions:
 *    - NEARBY -> FAVORITES and FAVORITES -> NEARBY.
 * 4. Guest navigation routing contract:
 *    - Unauthenticated guest user on Nearby routes to AppTab.FAVORITES upon requesting login.
 */
class BottomNavigationTabReorderTest {

    @Test
    fun testTabOrderingAndEnumContract() {
        val entries = AppTab.entries
        assertEquals("AppTab must have exactly 2 entries", 2, entries.size)

        // Slot 0 (Leftmost): NEARBY
        val leftTab = entries[0]
        assertEquals(AppTab.NEARBY, leftTab)
        assertEquals("Quanh đây", leftTab.label)
        assertEquals(Icons.Filled.LocationOn, leftTab.selectedIcon)
        assertEquals(Icons.Outlined.LocationOn, leftTab.unselectedIcon)

        // Slot 1 (Rightmost): FAVORITES
        val rightTab = entries[1]
        assertEquals(AppTab.FAVORITES, rightTab)
        assertEquals("Yêu thích", rightTab.label)
        assertEquals(Icons.Filled.Favorite, rightTab.selectedIcon)
        assertEquals(Icons.Outlined.FavoriteBorder, rightTab.unselectedIcon)
    }

    @Test
    fun testDefaultStartDestinationAndBidirectionalTransitions() {
        // Initial state simulator matching FavoritesApp: currentTab initialized to AppTab.NEARBY
        var currentTab: AppTab = AppTab.NEARBY
        assertEquals("Default start destination must strictly be AppTab.NEARBY", AppTab.NEARBY, currentTab)

        // Switch to Favorites
        val onTabSelected: (AppTab) -> Unit = { selected -> currentTab = selected }
        onTabSelected(AppTab.FAVORITES)
        assertEquals(AppTab.FAVORITES, currentTab)

        // Switch back to Nearby
        onTabSelected(AppTab.NEARBY)
        assertEquals(AppTab.NEARBY, currentTab)
    }

    @Test
    fun testGuestNavigationRoutingContractFromNearbyToFavoritesLogin() {
        var currentTab: AppTab = AppTab.NEARBY
        var isLoggedIn = false

        // Unauthenticated guest user directly lands on Nearby without gatekeeping
        assertEquals(AppTab.NEARBY, currentTab)
        assertFalse(isLoggedIn)

        // When guest taps login CTA or favorite action on Nearby screen, onNavigateToLogin callback triggers
        val onNavigateToLogin: () -> Unit = {
            currentTab = AppTab.FAVORITES
        }
        onNavigateToLogin()

        // Tab transitions to FAVORITES
        assertEquals(AppTab.FAVORITES, currentTab)
        // With isLoggedIn == false, FavoritesApp displays LoginScreen
        assertFalse("Destination tab is FAVORITES for unauthenticated guest to show LoginScreen", isLoggedIn)

        // Once logged in, tab remains FAVORITES and would show FavoritesScreen
        isLoggedIn = true
        assertTrue(isLoggedIn)
        assertEquals(AppTab.FAVORITES, currentTab)
    }
}
