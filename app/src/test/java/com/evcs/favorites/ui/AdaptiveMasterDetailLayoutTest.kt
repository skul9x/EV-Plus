package com.evcs.favorites.ui

import com.evcs.favorites.data.model.Station
import com.evcs.favorites.navigation.AppTab
import com.evcs.favorites.ui.layout.AdaptiveLayoutHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Single Comprehensive Verification Test for Phase 02:
 * Adaptive Master-Detail Architecture & Automotive NavigationRail.
 *
 * Verifies:
 * 1. Landscape vs portrait classification across common automotive displays:
 *    - 1024x600 (7-inch Android Box): Landscape
 *    - 1280x720 (9-inch Android Box): Landscape
 *    - 1920x720 (12.3-inch Ultrawide Head Unit): Landscape
 *    - 1080x2400 (Handheld smartphone): Portrait
 *    - Boundary checks (600x599, 599x400, square 600x600)
 * 2. Master-detail width split:
 *    - Master list width is strictly between 35% and 40% within clamp limits [320dp, 480dp].
 *    - Detail pane receives remaining width (60% to 65%).
 *    - Clamp behavior at 320dp min and 480dp max.
 *    - Subtraction of 72dp NavigationRail.
 * 3. Auto-selection policy (first station selected on wide screens if null; preserved if set; null if empty).
 * 4. Selected station state propagation and active card selection resolution.
 * 5. NavigationRail tab selection event mapping and dimension constraints (>= 56dp touch targets, 72dp width).
 */
class AdaptiveMasterDetailLayoutTest {

    private fun createDummyStation(id: String, name: String): Station {
        return Station(
            id = id,
            name = name,
            address = "Test Address $id",
            latitude = 21.0,
            longitude = 105.8,
            summary = "Summary $id",
            connectors = "CCS2",
            depotStatus = "Normal",
            powers = emptyList(),
            totalPlugs = 4,
            totalAvailablePlugs = 2
        )
    }

    // =========================================================================
    // 1. Landscape vs Portrait Classification
    // =========================================================================
    @Test
    fun testLandscapeVsPortraitClassification_AutomotiveAndPhoneDisplays() {
        // 7-inch Android Box: 1024x600 px (~682 - 1024 dp) -> Landscape
        assertTrue(
            "1024x600 Android Box must be classified as Landscape",
            AdaptiveLayoutHelper.isLandscapeMode(widthDp = 1024f, heightDp = 600f)
        )
        assertTrue(
            "682x400 Android Box in dp must be classified as Landscape",
            AdaptiveLayoutHelper.isLandscapeMode(widthDp = 682f, heightDp = 400f)
        )

        // 9-inch / 10.1-inch Android Box: 1280x720 px (~853 dp) -> Landscape
        assertTrue(
            "1280x720 Android Box must be classified as Landscape",
            AdaptiveLayoutHelper.isLandscapeMode(widthDp = 1280f, heightDp = 720f)
        )
        assertTrue(
            "853x480 Android Box in dp must be classified as Landscape",
            AdaptiveLayoutHelper.isLandscapeMode(widthDp = 853f, heightDp = 480f)
        )

        // 10.25-inch / 12.3-inch Ultrawide Head Units: 1920x720 px (~1280 dp) -> Landscape
        assertTrue(
            "1920x720 Ultrawide Head Unit must be classified as Landscape",
            AdaptiveLayoutHelper.isLandscapeMode(widthDp = 1920f, heightDp = 720f)
        )
        assertTrue(
            "1280x480 Ultrawide Head Unit in dp must be classified as Landscape",
            AdaptiveLayoutHelper.isLandscapeMode(widthDp = 1280f, heightDp = 480f)
        )

        // Handheld smartphone in portrait: 1080x2400 px -> Portrait
        assertFalse(
            "1080x2400 Handheld Smartphone portrait must NOT be classified as Landscape",
            AdaptiveLayoutHelper.isLandscapeMode(widthDp = 1080f, heightDp = 2400f)
        )
        assertFalse(
            "412x915 Phone in portrait must NOT be classified as Landscape",
            AdaptiveLayoutHelper.isLandscapeMode(widthDp = 412f, heightDp = 915f)
        )

        // Boundary edge cases
        assertTrue(
            "Exact threshold width=600dp and height=599dp must be Landscape",
            AdaptiveLayoutHelper.isLandscapeMode(widthDp = 600f, heightDp = 599f)
        )
        assertFalse(
            "Width 599dp (below 600dp) must NOT be Landscape",
            AdaptiveLayoutHelper.isLandscapeMode(widthDp = 599f, heightDp = 400f)
        )
        assertFalse(
            "Square 600x600 (width not strictly greater than height) must NOT be Landscape",
            AdaptiveLayoutHelper.isLandscapeMode(widthDp = 600f, heightDp = 600f)
        )
    }

    // =========================================================================
    // 2. Master-Detail Proportional Width Split & Clamping
    // =========================================================================
    @Test
    fun testMasterDetailWidthSplit_AutomotiveDisplaysAndProportions() {
        // Case A: 1024dp display (7-inch Android Box) with 72dp NavigationRail
        val alloc1024 = AdaptiveLayoutHelper.calculateMasterDetailWidths(
            totalWidthDp = 1024f,
            navRailWidthDp = 72f
        )
        val available1024 = 1024f - 72f // 952dp
        assertEquals(available1024, alloc1024.totalAvailableWidthDp, 0.01f)
        assertEquals(72f, alloc1024.navRailWidthDp, 0.01f)

        // Verify master allocation is strictly between 35% and 40% (38% default)
        assertTrue(
            "Master ratio must be between 35% and 40%",
            alloc1024.masterRatio in 0.35f..0.40f
        )
        assertEquals(0.38f, alloc1024.masterRatio, 0.001f)

        // Verify detail allocation receives the remaining 60% to 65% (62% default)
        assertTrue(
            "Detail ratio must be between 60% and 65%",
            alloc1024.detailRatio in 0.60f..0.65f
        )
        assertEquals(0.62f, alloc1024.detailRatio, 0.001f)

        // Verify sum of master + detail equals available width
        assertEquals(
            available1024,
            alloc1024.masterWidthDp + alloc1024.detailWidthDp,
            0.01f
        )

        // Master width must be within clamp bounds [320dp, 480dp]
        assertTrue(
            "Master width must be >= 320dp and <= 480dp",
            alloc1024.masterWidthDp in 320f..480f
        )
        assertEquals(952f * 0.38f, alloc1024.masterWidthDp, 0.01f) // ~361.76dp

        // Case B: 1280dp display (9-inch Android Box)
        val alloc1280 = AdaptiveLayoutHelper.calculateMasterDetailWidths(
            totalWidthDp = 1280f,
            navRailWidthDp = 72f
        )
        val available1280 = 1280f - 72f // 1208dp
        assertEquals(available1280, alloc1280.totalAvailableWidthDp, 0.01f)
        assertEquals(0.38f, alloc1280.masterRatio, 0.001f)
        assertEquals(0.62f, alloc1280.detailRatio, 0.001f)
        assertTrue(
            "Master width for 1280dp must be within clamp bounds",
            alloc1280.masterWidthDp in 320f..480f
        )
        assertEquals(1208f * 0.38f, alloc1280.masterWidthDp, 0.01f) // ~459.04dp

        // Case C: Minimum clamping at 320dp on smaller landscape screen (e.g. 700dp available)
        val allocSmall = AdaptiveLayoutHelper.calculateMasterDetailWidths(
            totalWidthDp = 772f,
            navRailWidthDp = 72f
        )
        // 700 * 0.38 = 266dp -> clamped to MIN_MASTER_WIDTH_DP (320dp)
        assertEquals(320f, allocSmall.masterWidthDp, 0.01f)
        assertEquals(700f - 320f, allocSmall.detailWidthDp, 0.01f) // 380dp
        assertEquals(700f, allocSmall.masterWidthDp + allocSmall.detailWidthDp, 0.01f)

        // Case D: Maximum clamping at 480dp on Ultrawide screen (1920dp)
        val allocUltrawide = AdaptiveLayoutHelper.calculateMasterDetailWidths(
            totalWidthDp = 1920f,
            navRailWidthDp = 72f
        )
        val availableUltrawide = 1920f - 72f // 1848dp
        // 1848 * 0.38 = 702.24dp -> clamped to MAX_MASTER_WIDTH_DP (480dp)
        assertEquals(AdaptiveLayoutHelper.MAX_MASTER_WIDTH_DP, allocUltrawide.masterWidthDp, 0.01f)
        assertEquals(480f, allocUltrawide.masterWidthDp, 0.01f)
        assertEquals(availableUltrawide - 480f, allocUltrawide.detailWidthDp, 0.01f) // 1368dp
        assertEquals(
            availableUltrawide,
            allocUltrawide.masterWidthDp + allocUltrawide.detailWidthDp,
            0.01f
        )
    }

    // =========================================================================
    // 3. Auto-Selection Policy on Wide / Landscape Displays
    // =========================================================================
    @Test
    fun testAutoSelectionPolicy_NearestStationSelectedOnWideDisplays() {
        val st1 = createDummyStation("st1", "Trạm Sạc Số 1 (Gần nhất)")
        val st2 = createDummyStation("st2", "Trạm Sạc Số 2")
        val st3 = createDummyStation("st3", "Trạm Sạc Số 3")
        val stations = listOf(st1, st2, st3)

        // In landscape when currentSelection is null: auto-select nearest (first) station
        val autoSelected = AdaptiveLayoutHelper.resolveAutoSelectedStation(
            isLandscape = true,
            currentSelection = null,
            stations = stations
        )
        assertNotNull(autoSelected)
        assertEquals("st1", autoSelected?.id)
        assertEquals("Trạm Sạc Số 1 (Gần nhất)", autoSelected?.name)

        // In landscape when user already selected a station: preserve user selection!
        val preservedSelection = AdaptiveLayoutHelper.resolveAutoSelectedStation(
            isLandscape = true,
            currentSelection = st2,
            stations = stations
        )
        assertNotNull(preservedSelection)
        assertEquals("st2", preservedSelection?.id)

        // In landscape when stations list is empty: safely returns null
        val emptySelection = AdaptiveLayoutHelper.resolveAutoSelectedStation(
            isLandscape = true,
            currentSelection = null,
            stations = emptyList()
        )
        assertNull(emptySelection)

        // In portrait mode: do NOT force auto-selection, preserve null
        val portraitNullSelection = AdaptiveLayoutHelper.resolveAutoSelectedStation(
            isLandscape = false,
            currentSelection = null,
            stations = stations
        )
        assertNull("Portrait mode must not force auto-selection when null", portraitNullSelection)

        // In portrait mode: preserve existing selection if set
        val portraitExistingSelection = AdaptiveLayoutHelper.resolveAutoSelectedStation(
            isLandscape = false,
            currentSelection = st3,
            stations = stations
        )
        assertEquals("st3", portraitExistingSelection?.id)
    }

    // =========================================================================
    // 4. Selected Station State Propagation & Active Card Highlight Resolution
    // =========================================================================
    @Test
    fun testSelectedStationStatePropagation_AndActiveCardSelectionResolution() {
        // Active card highlight (2dp EmeraldPrimary) applies ONLY in landscape for matching id
        assertTrue(
            "Selected card in landscape must be resolved as selected",
            AdaptiveLayoutHelper.isStationCardSelected(
                isLandscape = true,
                cardStationId = "station_101",
                selectedStationId = "station_101"
            )
        )

        // Different station ID must not be highlighted
        assertFalse(
            "Non-matching card in landscape must not be highlighted",
            AdaptiveLayoutHelper.isStationCardSelected(
                isLandscape = true,
                cardStationId = "station_102",
                selectedStationId = "station_101"
            )
        )

        // When selection is null, no card is highlighted
        assertFalse(
            "No card highlighted when selectedStationId is null",
            AdaptiveLayoutHelper.isStationCardSelected(
                isLandscape = true,
                cardStationId = "station_101",
                selectedStationId = null
            )
        )

        // In portrait mode, cards do not retain persistent selection highlight
        assertFalse(
            "Card must not show persistent landscape highlight in portrait mode",
            AdaptiveLayoutHelper.isStationCardSelected(
                isLandscape = false,
                cardStationId = "station_101",
                selectedStationId = "station_101"
            )
        )
    }

    // =========================================================================
    // 5. NavigationRail Tab Mapping & Automotive Dimension Constraints
    // =========================================================================
    @Test
    fun testNavigationRailTabSelectionEventMapping_AndDimensionConstraints() {
        // Dimension constants verification
        assertEquals(72f, AdaptiveLayoutHelper.DEFAULT_NAV_RAIL_WIDTH_DP, 0.01f)
        assertEquals(72f, AdaptiveLayoutHelper.NAVIGATION_RAIL_WIDTH_DP, 0.01f)
        assertEquals(56f, AdaptiveLayoutHelper.MIN_TOUCH_TARGET_DP, 0.01f)

        // Touch target safety compliance (>= 56dp for automotive in-car use)
        assertTrue(
            "56dp touch target must be compliant",
            AdaptiveLayoutHelper.isTouchTargetCompliant(56f)
        )
        assertTrue(
            "72dp touch target must be compliant",
            AdaptiveLayoutHelper.isTouchTargetCompliant(72f)
        )
        assertFalse(
            "48dp touch target is below automotive 56dp standard",
            AdaptiveLayoutHelper.isTouchTargetCompliant(48f)
        )

        // Tab selection event resolution
        assertEquals(
            AppTab.FAVORITES,
            AdaptiveLayoutHelper.resolveSelectedTab(AppTab.NEARBY, AppTab.FAVORITES)
        )
        assertEquals(
            AppTab.NEARBY,
            AdaptiveLayoutHelper.resolveSelectedTab(AppTab.FAVORITES, AppTab.NEARBY)
        )

        // Verify AppTab entries and properties
        assertEquals(2, AppTab.entries.size)
        assertTrue(AppTab.entries.contains(AppTab.NEARBY))
        assertTrue(AppTab.entries.contains(AppTab.FAVORITES))

        assertNotNull(AppTab.NEARBY.label)
        assertNotNull(AppTab.NEARBY.selectedIcon)
        assertNotNull(AppTab.NEARBY.unselectedIcon)

        assertNotNull(AppTab.FAVORITES.label)
        assertNotNull(AppTab.FAVORITES.selectedIcon)
        assertNotNull(AppTab.FAVORITES.unselectedIcon)
    }
}
