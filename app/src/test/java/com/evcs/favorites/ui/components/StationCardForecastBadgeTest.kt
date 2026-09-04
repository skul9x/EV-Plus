package com.evcs.favorites.ui.components

import androidx.compose.ui.graphics.Color
import com.evcs.favorites.domain.model.ForecastSession
import com.evcs.favorites.domain.model.StationForecast
import com.evcs.favorites.ui.theme.StatusAvailable
import com.evcs.favorites.ui.theme.StatusAvailableContainer
import com.evcs.favorites.ui.theme.StatusBusy
import com.evcs.favorites.ui.theme.StatusBusyContainer
import com.evcs.favorites.ui.theme.StatusMaintaining
import com.evcs.favorites.ui.theme.StatusOffline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Single comprehensive test suite for Phase 04:
 * 1. Full station with forecast resolves to Amber "⏱️ Sắp trống" badge (#F59E0B).
 * 2. Full station without forecast resolves to Red "Hết cổng" badge (#EF4444).
 * 3. Station with available plugs resolves to Green "Hoạt động" badge regardless of forecast.
 * 4. Capsule text formatting for single session summary matching 1.md Case 1.
 * 5. Capsule text formatting for multiple sessions with bullet list and header matching 1.md Case 2.
 * 6. Edge cases: Maintaining and OutOfService override busy/forecast badges.
 */
class StationCardForecastBadgeTest {

    @Test
    fun testFullStationWithForecast_resolvesToAmberSapTrong() {
        val sampleForecast = StationForecast(
            vehicleCount = 2,
            wattageKw = 20.0,
            minMinutes = 7,
            maxMinutes = 14
        )

        val badge = resolveStatusBadge(
            depotStatus = "Normal",
            totalAvailablePlugs = 0,
            totalPlugs = 4,
            forecast = sampleForecast
        )

        assertEquals("⏱️ Sắp trống", badge.label)
        assertEquals(Color(0xFFF59E0B), badge.dotColor)
        assertEquals(Color(0x26F59E0B), badge.containerColor)
    }

    @Test
    fun testFullStationWithoutForecast_resolvesToRedHetCong() {
        val badge = resolveStatusBadge(
            depotStatus = "Normal",
            totalAvailablePlugs = 0,
            totalPlugs = 4,
            forecast = null
        )

        assertEquals("Hết cổng", badge.label)
        assertEquals(StatusBusy, badge.dotColor)
        assertEquals(StatusBusyContainer, badge.containerColor)
        assertEquals(Color(0xFFEF4444), badge.dotColor)
        assertEquals(Color(0x26EF4444), badge.containerColor)
    }

    @Test
    fun testStationWithAvailablePlugs_resolvesToGreenHoatDongRegardlessOfForecast() {
        val forecast = StationForecast(
            vehicleCount = 1,
            wattageKw = 250.0,
            minMinutes = 8,
            maxMinutes = 8
        )

        // Case A: With forecast present but available plugs > 0
        val badgeWithForecast = resolveStatusBadge(
            depotStatus = "Normal",
            totalAvailablePlugs = 2,
            totalPlugs = 4,
            forecast = forecast
        )
        assertEquals("Hoạt động", badgeWithForecast.label)
        assertEquals(StatusAvailable, badgeWithForecast.dotColor)
        assertEquals(StatusAvailableContainer, badgeWithForecast.containerColor)

        // Case B: Without forecast and available plugs > 0
        val badgeWithoutForecast = resolveStatusBadge(
            depotStatus = "Normal",
            totalAvailablePlugs = 3,
            totalPlugs = 6,
            forecast = null
        )
        assertEquals("Hoạt động", badgeWithoutForecast.label)
        assertEquals(StatusAvailable, badgeWithoutForecast.dotColor)
        assertEquals(StatusAvailableContainer, badgeWithoutForecast.containerColor)
    }

    @Test
    fun testCapsuleTextFormatting_singleSessionSummary() {
        // Range time: 7-14 phút
        val rangeForecast = StationForecast(
            vehicleCount = 2,
            wattageKw = 20.0,
            minMinutes = 7,
            maxMinutes = 14
        )
        val capsuleDataRange = resolveForecastCapsuleData(rangeForecast)

        assertFalse(capsuleDataRange.isMultiSession)
        assertEquals("⏱️ Dự kiến 2 xe sạc trụ 20kW sẽ xong trong 7-14 phút nữa", capsuleDataRange.singleSummary)
        assertTrue(capsuleDataRange.bulletLines.isEmpty())

        // Exact time: 13 phút
        val exactForecast = StationForecast(
            vehicleCount = 1,
            wattageKw = 60.0,
            minMinutes = 13,
            maxMinutes = 13
        )
        val capsuleDataExact = resolveForecastCapsuleData(exactForecast)

        assertFalse(capsuleDataExact.isMultiSession)
        assertEquals("⏱️ Dự kiến 1 xe sạc trụ 60kW sẽ xong trong 13 phút nữa", capsuleDataExact.singleSummary)
        assertTrue(capsuleDataExact.bulletLines.isEmpty())
    }

    @Test
    fun testCapsuleTextFormatting_multipleSessions_matchingSpecBulletListAndHeader() {
        val multiForecast = StationForecast(
            detailedSessions = listOf(
                ForecastSession(kw = 20.0, min = 7),
                ForecastSession(kw = 20.0, min = 14),
                ForecastSession(kw = 60.0, min = 13),
                ForecastSession(kw = 250.0, min = 8)
            )
        )

        val capsuleData = resolveForecastCapsuleData(multiForecast)

        assertTrue(capsuleData.isMultiSession)
        assertEquals("⚡ DỰ KIẾN CỔNG SẮP TRỐNG:", capsuleData.header)
        assertEquals(3, capsuleData.bulletLines.size)

        // Spec bullets:
        // • 20kW:  ~7-14 phút (2 xe)
        // • 60kW:  ~13 phút (1 xe)
        // • 250kW: ~8 phút (1 xe)
        assertEquals("• 20kW:  ~7-14 phút (2 xe)", capsuleData.bulletLines[0])
        assertEquals("• 60kW:  ~13 phút (1 xe)", capsuleData.bulletLines[1])
        assertEquals("• 250kW: ~8 phút (1 xe)", capsuleData.bulletLines[2])
    }

    @Test
    fun testDepotStatusOverrides_maintainingAndOutOfService() {
        val forecast = StationForecast(
            vehicleCount = 1,
            wattageKw = 60.0,
            minMinutes = 5,
            maxMinutes = 10
        )

        val maintainingBadge = resolveStatusBadge(
            depotStatus = "Maintaining",
            totalAvailablePlugs = 0,
            totalPlugs = 4,
            forecast = forecast
        )
        assertEquals("Bảo trì", maintainingBadge.label)
        assertEquals(StatusMaintaining, maintainingBadge.dotColor)

        val outOfServiceBadge = resolveStatusBadge(
            depotStatus = "OutOfService",
            totalAvailablePlugs = 0,
            totalPlugs = 4,
            forecast = forecast
        )
        assertEquals("Tạm dừng", outOfServiceBadge.label)
        assertEquals(StatusOffline, outOfServiceBadge.dotColor)
    }
}
