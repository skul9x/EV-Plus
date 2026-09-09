package com.evcs.favorites.ui

import androidx.compose.ui.unit.dp
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.ui.layout.AdaptiveLayoutHelper
import com.evcs.favorites.ui.screens.landscape.LandscapeScreenContracts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Single Comprehensive Verification Test for Phase 03: Dedicated Landscape UI Screens.
 *
 * Verifies:
 * 1. Layout contract constants: TopAppBar and filter summary pill suppressed in landscape mode.
 * 2. Master-detail width ratio calculation with the 58dp navigation rail across automotive displays:
 *    - 1024x600 (7-inch Android Box)
 *    - 1280x720 (9-inch / 10.1-inch Android Box)
 *    - 1920x720 (12.3-inch Ultrawide Head Unit)
 *    - Small landscape screen with 320dp clamping
 * 3. Auto-selection resolution contract for nearest station in landscape mode.
 * 4. Active station card selection highlighting in landscape vs portrait.
 * 5. Automotive landscape layout dimensions, padding, and elevation contract constants.
 */
class LandscapeDedicatedScreensContractTest {

    private fun createDummyStation(id: String, name: String): Station {
        return Station(
            id = id,
            name = name,
            address = "Địa chỉ thử nghiệm $id",
            latitude = 21.0285,
            longitude = 105.8542,
            summary = "Summary $id",
            connectors = "CCS2",
            depotStatus = "Normal",
            powers = emptyList(),
            totalPlugs = 6,
            totalAvailablePlugs = 3
        )
    }

    // =========================================================================
    // 1. TopAppBar & Filter Summary Pill Suppression Contracts
    // =========================================================================
    @Test
    fun testTopAppBarAndFilterSummaryPillSuppressedInLandscape() {
        // Contract: TopAppBar must be completely eliminated in dedicated landscape screens
        assertFalse(
            "TopAppBar must be suppressed in dedicated landscape screens to reclaim vertical space",
            LandscapeScreenContracts.TOP_APP_BAR_VISIBLE
        )

        // Contract: Filter summary pill must be suppressed so cards start directly below filter chips
        assertFalse(
            "Filter summary pill must be suppressed in landscape screens to fit 3-4 visible station cards",
            LandscapeScreenContracts.FILTER_SUMMARY_PILL_VISIBLE
        )

        // Compact navigation rail contract width
        assertEquals(58.dp, LandscapeScreenContracts.COMPACT_NAV_RAIL_WIDTH_DP)
        assertEquals(58f, AdaptiveLayoutHelper.COMPACT_NAV_RAIL_WIDTH_DP, 0.001f)

        // Compact filter bar vertical padding
        assertEquals(4.dp, LandscapeScreenContracts.FILTER_BAR_VERTICAL_PADDING_DP)
    }

    // =========================================================================
    // 2. Master-Detail Proportional Width Calculations with 58dp Navigation Rail
    // =========================================================================
    @Test
    fun testMasterDetailWidthRatioCalculationWith58dpNavRail() {
        val railWidth = AdaptiveLayoutHelper.COMPACT_NAV_RAIL_WIDTH_DP // 58f

        // Case A: 1024x600 display (7-inch Android Box) with 58dp rail
        val totalWidth1024 = 1024f
        val alloc1024 = AdaptiveLayoutHelper.calculateMasterDetailWidths(
            totalWidthDp = totalWidth1024,
            navRailWidthDp = railWidth
        )
        val expectedAvailable1024 = 1024f - 58f // 966dp
        assertEquals(expectedAvailable1024, alloc1024.totalAvailableWidthDp, 0.01f)
        assertEquals(58f, alloc1024.navRailWidthDp, 0.01f)

        // Master receives ~38% (strictly within 35% - 40%)
        assertTrue(
            "Master ratio must be between 35% and 40%",
            alloc1024.masterRatio in 0.35f..0.40f
        )
        assertEquals(0.38f, alloc1024.masterRatio, 0.001f)

        // Detail receives ~62% (strictly within 60% - 65%)
        assertTrue(
            "Detail ratio must be between 60% and 65%",
            alloc1024.detailRatio in 0.60f..0.65f
        )
        assertEquals(0.62f, alloc1024.detailRatio, 0.001f)

        // Master width clamped within [320dp, 480dp]
        assertTrue(
            "Master width must be within [320dp, 480dp]",
            alloc1024.masterWidthDp in 320f..480f
        )
        assertEquals(966f * 0.38f, alloc1024.masterWidthDp, 0.01f) // 367.08dp

        // Sum of master + detail must perfectly match total available width
        assertEquals(
            expectedAvailable1024,
            alloc1024.masterWidthDp + alloc1024.detailWidthDp,
            0.01f
        )

        // Case B: 1280x720 display (9-inch / 10.1-inch Android Box) with 58dp rail
        val totalWidth1280 = 1280f
        val alloc1280 = AdaptiveLayoutHelper.calculateMasterDetailWidths(
            totalWidthDp = totalWidth1280,
            navRailWidthDp = railWidth
        )
        val expectedAvailable1280 = 1280f - 58f // 1222dp
        assertEquals(expectedAvailable1280, alloc1280.totalAvailableWidthDp, 0.01f)
        assertEquals(0.38f, alloc1280.masterRatio, 0.001f)
        assertEquals(0.62f, alloc1280.detailRatio, 0.001f)
        assertEquals(1222f * 0.38f, alloc1280.masterWidthDp, 0.01f) // 464.36dp
        assertEquals(
            expectedAvailable1280,
            alloc1280.masterWidthDp + alloc1280.detailWidthDp,
            0.01f
        )

        // Case C: 1920x720 display (12.3-inch Ultrawide Head Unit) clamped at 480dp max
        val totalWidth1920 = 1920f
        val alloc1920 = AdaptiveLayoutHelper.calculateMasterDetailWidths(
            totalWidthDp = totalWidth1920,
            navRailWidthDp = railWidth
        )
        val expectedAvailable1920 = 1920f - 58f // 1862dp
        assertEquals(expectedAvailable1920, alloc1920.totalAvailableWidthDp, 0.01f)
        assertEquals(
            "Ultrawide master width must be clamped at MAX_MASTER_WIDTH_DP (480dp)",
            AdaptiveLayoutHelper.MAX_MASTER_WIDTH_DP,
            alloc1920.masterWidthDp,
            0.01f
        )
        assertEquals(1862f - 480f, alloc1920.detailWidthDp, 0.01f) // 1382dp
        assertEquals(
            expectedAvailable1920,
            alloc1920.masterWidthDp + alloc1920.detailWidthDp,
            0.01f
        )

        // Case D: Small landscape screen clamped at 320dp min
        val totalWidth700 = 700f
        val alloc700 = AdaptiveLayoutHelper.calculateMasterDetailWidths(
            totalWidthDp = totalWidth700,
            navRailWidthDp = railWidth
        )
        val expectedAvailable700 = 700f - 58f // 642dp
        // 642 * 0.38 = 243.96dp -> clamped up to MIN_MASTER_WIDTH_DP (320dp)
        assertEquals(
            "Small landscape master width must be clamped at MIN_MASTER_WIDTH_DP (320dp)",
            AdaptiveLayoutHelper.MIN_MASTER_WIDTH_DP,
            alloc700.masterWidthDp,
            0.01f
        )
        assertEquals(642f - 320f, alloc700.detailWidthDp, 0.01f) // 322dp
        assertEquals(
            expectedAvailable700,
            alloc700.masterWidthDp + alloc700.detailWidthDp,
            0.01f
        )
    }

    // =========================================================================
    // 3. Auto-Selection Resolution Contract for Nearest Station
    // =========================================================================
    @Test
    fun testAutoSelectionResolutionContractForNearestStationInLandscape() {
        val st1 = createDummyStation("st_nearest", "VinFast Vincom Center (Gần nhất)")
        val st2 = createDummyStation("st_second", "VinFast Landmark 81")
        val st3 = createDummyStation("st_third", "VinFast Thảo Điền")
        val stations = listOf(st1, st2, st3)

        // Landscape mode with null currentSelection -> auto-selects nearest (st1)
        assertTrue(LandscapeScreenContracts.AUTO_SELECTION_ENABLED)
        val autoSelected = AdaptiveLayoutHelper.resolveAutoSelectedStation(
            isLandscape = true,
            currentSelection = null,
            stations = stations
        )
        assertNotNull("Nearest station must be auto-selected when selection is null", autoSelected)
        assertEquals("st_nearest", autoSelected?.id)
        assertEquals("VinFast Vincom Center (Gần nhất)", autoSelected?.name)

        // Landscape mode with existing selection -> preserves user's current selection
        val preserved = AdaptiveLayoutHelper.resolveAutoSelectedStation(
            isLandscape = true,
            currentSelection = st2,
            stations = stations
        )
        assertNotNull(preserved)
        assertEquals("st_second", preserved?.id)

        // Landscape mode with empty stations -> safely returns null
        val emptyResult = AdaptiveLayoutHelper.resolveAutoSelectedStation(
            isLandscape = true,
            currentSelection = null,
            stations = emptyList()
        )
        assertNull("Empty station list must resolve to null", emptyResult)

        // Portrait mode with null selection -> must NOT auto-select (user chooses via bottom sheet)
        val portraitNull = AdaptiveLayoutHelper.resolveAutoSelectedStation(
            isLandscape = false,
            currentSelection = null,
            stations = stations
        )
        assertNull("Portrait mode must not force auto-selection", portraitNull)
    }

    // =========================================================================
    // 4. Station Card Selection Highlight Resolution
    // =========================================================================
    @Test
    fun testStationCardSelectionResolutionInLandscape() {
        // In landscape, matching station id is marked selected
        assertTrue(
            AdaptiveLayoutHelper.isStationCardSelected(
                isLandscape = true,
                cardStationId = "station_abc",
                selectedStationId = "station_abc"
            )
        )

        // Non-matching station id is NOT selected
        assertFalse(
            AdaptiveLayoutHelper.isStationCardSelected(
                isLandscape = true,
                cardStationId = "station_xyz",
                selectedStationId = "station_abc"
            )
        )

        // Null selected id results in false
        assertFalse(
            AdaptiveLayoutHelper.isStationCardSelected(
                isLandscape = true,
                cardStationId = "station_abc",
                selectedStationId = null
            )
        )

        // In portrait mode, persistent selection border is disabled
        assertFalse(
            AdaptiveLayoutHelper.isStationCardSelected(
                isLandscape = false,
                cardStationId = "station_abc",
                selectedStationId = "station_abc"
            )
        )
    }

    // =========================================================================
    // 5. Automotive Landscape Layout Dimension & Spacing Constants
    // =========================================================================
    @Test
    fun testLandscapeLayoutContractsDimensionsAndSpacings() {
        assertEquals(8.dp, LandscapeScreenContracts.SCREEN_HORIZONTAL_PADDING_DP)
        assertEquals(6.dp, LandscapeScreenContracts.SCREEN_VERTICAL_PADDING_DP)
        assertEquals(8.dp, LandscapeScreenContracts.MASTER_DETAIL_SPACING_DP)
        assertEquals(8.dp, LandscapeScreenContracts.CARD_SPACING_DP)
        assertEquals(16.dp, LandscapeScreenContracts.DETAIL_SURFACE_CORNER_RADIUS_DP)
        assertEquals(2.dp, LandscapeScreenContracts.DETAIL_SURFACE_ELEVATION_DP)
    }
}
