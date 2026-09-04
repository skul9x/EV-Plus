package com.evcs.favorites.ui.components

import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.ui.theme.StatusAvailable
import com.evcs.favorites.ui.theme.StatusAvailableContainer
import com.evcs.favorites.ui.theme.StatusBusy
import com.evcs.favorites.ui.theme.StatusBusyContainer
import com.evcs.favorites.ui.theme.StatusMaintaining
import com.evcs.favorites.ui.theme.StatusOffline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Comprehensive verification test for Phase 01: Presentation and StationCard Decoupling.
 *
 * Requirements verified:
 * 1. resolveStatusBadge always returns "Hết cổng" (StatusBusy) for full stations with
 *    totalPlugs > 0 && totalAvailablePlugs == 0, regardless of whether forecast is present or null.
 * 2. Available stations return "Hoạt động" with green StatusAvailable (and Maintaining/OutOfService preserved).
 * 3. StationDetailModal WebView CSS snippet contains .amd-ticker and .amd-more hiding rules (display: none !important).
 * 4. ForecastCapsule, ForecastCapsuleData, and amber forecast color constants are completely decoupled and retired.
 */
class StationCardForecastRemovalTest {

    // =========================================================================
    // 1. Full stations unconditionally return "Hết cổng" (StatusBusy)
    // =========================================================================
    @Test
    fun fullStationStatusBadgeReturnsHetCongRegardlessOfForecast() {
        // Full station -> "Hết cổng", StatusBusy
        val badge = resolveStatusBadge(
            depotStatus = "Normal",
            totalAvailablePlugs = 0,
            totalPlugs = 4
        )
        assertEquals("Hết cổng", badge.label)
        assertEquals(StatusBusy, badge.dotColor)
        assertEquals(StatusBusyContainer, badge.containerColor)
        assertFalse(badge.label.contains("Sắp trống"))

        // Station with 10 plugs and 0 available -> "Hết cổng"
        val badgeLargeDepot = resolveStatusBadge(
            depotStatus = "Normal",
            totalAvailablePlugs = 0,
            totalPlugs = 10
        )
        assertEquals("Hết cổng", badgeLargeDepot.label)
        assertEquals(StatusBusy, badgeLargeDepot.dotColor)
    }

    // =========================================================================
    // 2. Available stations return "Hoạt động" with green StatusAvailable
    // =========================================================================
    @Test
    fun availableStationStatusBadgeReturnsHoatDongWithGreen() {
        // Available plugs > 0 -> "Hoạt động", StatusAvailable
        val badgeAvailable = resolveStatusBadge(
            depotStatus = "Normal",
            totalAvailablePlugs = 2,
            totalPlugs = 4
        )
        assertEquals("Hoạt động", badgeAvailable.label)
        assertEquals(StatusAvailable, badgeAvailable.dotColor)
        assertEquals(StatusAvailableContainer, badgeAvailable.containerColor)

        val badgeAvailableSingle = resolveStatusBadge(
            depotStatus = "Normal",
            totalAvailablePlugs = 1,
            totalPlugs = 2
        )
        assertEquals("Hoạt động", badgeAvailableSingle.label)
        assertEquals(StatusAvailable, badgeAvailableSingle.dotColor)
        assertEquals(StatusAvailableContainer, badgeAvailableSingle.containerColor)

        // Maintaining preserves its status
        val badgeMaintaining = resolveStatusBadge(
            depotStatus = "Maintaining",
            totalAvailablePlugs = 0,
            totalPlugs = 4
        )
        assertEquals("Bảo trì", badgeMaintaining.label)
        assertEquals(StatusMaintaining, badgeMaintaining.dotColor)

        // OutOfService preserves its status
        val badgeOutOfService = resolveStatusBadge(
            depotStatus = "OutOfService",
            totalAvailablePlugs = 0,
            totalPlugs = 4
        )
        assertEquals("Tạm dừng", badgeOutOfService.label)
        assertEquals(StatusOffline, badgeOutOfService.dotColor)
    }

    // =========================================================================
    // 3. StationDetailModal CSS hides web forecast banner (.amd-ticker, .amd-more)
    // =========================================================================
    @Test
    fun stationDetailModalWebViewCssHidesForecastTickerAndMoreButton() {
        assertTrue(
            "FORECAST_OVERLAP_FIX_CSS must target .amd-ticker",
            FORECAST_OVERLAP_FIX_CSS.contains(".amd-ticker")
        )
        assertTrue(
            "FORECAST_OVERLAP_FIX_CSS must target .amd-more",
            FORECAST_OVERLAP_FIX_CSS.contains(".amd-more")
        )
        assertTrue(
            "FORECAST_OVERLAP_FIX_CSS must hide elements with display: none !important",
            FORECAST_OVERLAP_FIX_CSS.contains("display: none !important")
        )
        assertTrue(
            "FORECAST_OVERLAP_FIX_SCRIPT must embed the updated CSS rule",
            FORECAST_OVERLAP_FIX_SCRIPT.contains(FORECAST_OVERLAP_FIX_CSS)
        )
    }

    // =========================================================================
    // 4. StationCard decoupling: ForecastCapsule & amber constants eliminated
    // =========================================================================
    @Test
    fun stationCardDecoupledFromForecastCapsuleAndAmberConstants() {
        val stationCardClass = Class.forName("com.evcs.favorites.ui.components.StationCardKt")
        val declaredMethods = stationCardClass.declaredMethods.map { it.name }
        val declaredFields = stationCardClass.declaredFields.map { it.name }

        // ForecastCapsule composable must be removed
        assertFalse(
            "ForecastCapsule composable must be removed from StationCardKt",
            declaredMethods.any { it.contains("ForecastCapsule") }
        )

        // resolveForecastCapsuleData helper must be removed
        assertFalse(
            "resolveForecastCapsuleData must be removed from StationCardKt",
            declaredMethods.any { it.contains("resolveForecastCapsuleData") }
        )

        // Amber forecast colors must be removed
        assertFalse(
            "StatusForecastAmber must be removed from StationCardKt",
            declaredFields.any { it.contains("StatusForecastAmber") }
        )
        assertFalse(
            "ForecastCapsuleBg must be removed from StationCardKt",
            declaredFields.any { it.contains("ForecastCapsuleBg") }
        )

        // ForecastCapsuleData class must no longer exist
        try {
            Class.forName("com.evcs.favorites.ui.components.ForecastCapsuleData")
            fail("ForecastCapsuleData data class should have been deleted")
        } catch (_: ClassNotFoundException) {
            // Expected
        }

        // Station compiles and runs cleanly without visual capsule
        val testStation = Station(
            id = "station_1",
            name = "Trạm Test",
            address = "Hà Nội",
            latitude = 21.0,
            longitude = 105.0,
            summary = "Mở 24/7",
            connectors = "60kW",
            totalAvailablePlugs = 0,
            totalPlugs = 4,
            depotStatus = "Normal"
        )
        val badge = resolveStatusBadge(
            depotStatus = testStation.depotStatus,
            totalAvailablePlugs = testStation.totalAvailablePlugs,
            totalPlugs = testStation.totalPlugs
        )
        assertEquals("Hết cổng", badge.label)
    }
}
