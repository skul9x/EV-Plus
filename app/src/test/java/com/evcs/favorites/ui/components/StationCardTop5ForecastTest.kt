package com.evcs.favorites.ui.components

import androidx.compose.ui.graphics.Color
import com.evcs.favorites.domain.model.ForecastSession
import com.evcs.favorites.domain.model.StationForecast
import com.evcs.favorites.ui.theme.StatusAvailable
import com.evcs.favorites.ui.theme.StatusAvailableContainer
import com.evcs.favorites.ui.theme.StatusBusy
import com.evcs.favorites.ui.theme.StatusBusyContainer
import com.evcs.favorites.ui.theme.StatusMaintaining
import com.evcs.favorites.ui.theme.StatusMaintainingContainer
import com.evcs.favorites.ui.theme.StatusOffline
import com.evcs.favorites.ui.theme.StatusOfflineContainer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Single comprehensive test suite for Phase 02: StationCard UI & Badge Presentation for Live Top 5 Forecasts.
 *
 * Verifies:
 * 1. Station with available plugs (`totalAvailablePlugs > 0`) and valid forecast displays
 *    ForecastCapsule content while retaining green "Hoạt động" badge.
 * 2. Full station (`totalAvailablePlugs == 0`) and valid forecast displays
 *    ForecastCapsule content with Amber "⏱️ Sắp trống" badge.
 * 3. Station without forecast (`forecast == null`) completely skips ForecastCapsule and renders appropriate badges.
 * 4. Single-session and multi-session text formatting accurately matching 1.md specifications.
 * 5. Animated crossfade model transitions and distinct state representations between badge states.
 */
class StationCardTop5ForecastTest {

    @Test
    fun testAvailableStationWithValidForecast_retainsGreenHoatDongBadgeAndProvidesCapsuleData() {
        // Station with 2 available plugs out of 4 total, but has forecast for other slots
        val liveForecast = StationForecast(
            detailedSessions = listOf(
                ForecastSession(kw = 60.0, min = 15),
                ForecastSession(kw = 120.0, min = 8)
            )
        )

        val badge = resolveStatusBadge(
            depotStatus = "Normal",
            totalAvailablePlugs = 2,
            totalPlugs = 4,
            forecast = liveForecast
        )

        // Badge MUST retain green "Hoạt động"
        assertEquals("Hoạt động", badge.label)
        assertEquals(StatusAvailable, badge.dotColor)
        assertEquals(StatusAvailableContainer, badge.containerColor)
        assertEquals(Color(0xFF10B981), badge.dotColor)
        assertEquals(Color(0x2610B981), badge.containerColor)

        // Forecast Capsule data MUST be available and resolved cleanly
        val capsuleData = resolveForecastCapsuleData(liveForecast)
        assertTrue(capsuleData.isMultiSession)
        assertEquals("⚡ DỰ KIẾN CỔNG SẮP TRỐNG:", capsuleData.header)
        assertEquals(2, capsuleData.bulletLines.size)
        assertEquals("• 60kW:  ~15 phút (1 xe)", capsuleData.bulletLines[0])
        assertEquals("• 120kW: ~8 phút (1 xe)", capsuleData.bulletLines[1])
    }

    @Test
    fun testFullStationWithValidForecast_resolvesToAmberSapTrongBadgeAndProvidesCapsuleData() {
        // Full station with 0 available plugs out of 4
        val liveForecast = StationForecast(
            vehicleCount = 2,
            wattageKw = 20.0,
            minMinutes = 7,
            maxMinutes = 14
        )

        val badge = resolveStatusBadge(
            depotStatus = "Normal",
            totalAvailablePlugs = 0,
            totalPlugs = 4,
            forecast = liveForecast
        )

        // Badge MUST show Amber "⏱️ Sắp trống"
        assertEquals("⏱️ Sắp trống", badge.label)
        assertEquals(StatusForecastAmber, badge.dotColor)
        assertEquals(StatusForecastAmberContainer, badge.containerColor)
        assertEquals(Color(0xFFF59E0B), badge.dotColor)
        assertEquals(Color(0x26F59E0B), badge.containerColor)

        // Forecast Capsule data is present
        val capsuleData = resolveForecastCapsuleData(liveForecast)
        assertFalse(capsuleData.isMultiSession)
        assertEquals("⏱️ Dự kiến 2 xe sạc trụ 20kW sẽ xong trong 7-14 phút nữa", capsuleData.singleSummary)
        assertTrue(capsuleData.bulletLines.isEmpty())
    }

    @Test
    fun testStationWithoutForecast_resolvesAppropriateBadgesAndNoForecastPresence() {
        // Case A: Full station without forecast -> Red "Hết cổng"
        val fullWithoutForecast = resolveStatusBadge(
            depotStatus = "Normal",
            totalAvailablePlugs = 0,
            totalPlugs = 4,
            forecast = null
        )
        assertEquals("Hết cổng", fullWithoutForecast.label)
        assertEquals(StatusBusy, fullWithoutForecast.dotColor)
        assertEquals(StatusBusyContainer, fullWithoutForecast.containerColor)
        assertEquals(Color(0xFFEF4444), fullWithoutForecast.dotColor)
        assertEquals(Color(0x26EF4444), fullWithoutForecast.containerColor)

        // Case B: Available station without forecast -> Green "Hoạt động"
        val availableWithoutForecast = resolveStatusBadge(
            depotStatus = "Normal",
            totalAvailablePlugs = 3,
            totalPlugs = 6,
            forecast = null
        )
        assertEquals("Hoạt động", availableWithoutForecast.label)
        assertEquals(StatusAvailable, availableWithoutForecast.dotColor)
        assertEquals(StatusAvailableContainer, availableWithoutForecast.containerColor)

        // Case C: Depot Status overrides (Maintaining & OutOfService) take precedence over full/available
        val maintainingBadge = resolveStatusBadge(
            depotStatus = "Maintaining",
            totalAvailablePlugs = 0,
            totalPlugs = 4,
            forecast = null
        )
        assertEquals("Bảo trì", maintainingBadge.label)
        assertEquals(StatusMaintaining, maintainingBadge.dotColor)
        assertEquals(StatusMaintainingContainer, maintainingBadge.containerColor)

        val outOfServiceBadge = resolveStatusBadge(
            depotStatus = "OutOfService",
            totalAvailablePlugs = 0,
            totalPlugs = 4,
            forecast = null
        )
        assertEquals("Tạm dừng", outOfServiceBadge.label)
        assertEquals(StatusOffline, outOfServiceBadge.dotColor)
        assertEquals(StatusOfflineContainer, outOfServiceBadge.containerColor)
    }

    @Test
    fun testSingleSessionAndMultiSessionTextFormatting_matchingSpec1md() {
        // 1. Single session with range time
        val rangeForecast = StationForecast(
            vehicleCount = 2,
            wattageKw = 20.0,
            minMinutes = 7,
            maxMinutes = 14
        )
        val singleRange = resolveForecastCapsuleData(rangeForecast)
        assertFalse(singleRange.isMultiSession)
        assertEquals("⏱️ Dự kiến 2 xe sạc trụ 20kW sẽ xong trong 7-14 phút nữa", singleRange.singleSummary)
        assertTrue(singleRange.bulletLines.isEmpty())

        // 2. Single session with exact time
        val exactForecast = StationForecast(
            vehicleCount = 1,
            wattageKw = 60.0,
            minMinutes = 13,
            maxMinutes = 13
        )
        val singleExact = resolveForecastCapsuleData(exactForecast)
        assertFalse(singleExact.isMultiSession)
        assertEquals("⏱️ Dự kiến 1 xe sạc trụ 60kW sẽ xong trong 13 phút nữa", singleExact.singleSummary)

        // 3. Single session with decimal kw
        val decimalForecast = StationForecast(
            vehicleCount = 1,
            wattageKw = 22.5,
            minMinutes = 10,
            maxMinutes = 10
        )
        val singleDecimal = resolveForecastCapsuleData(decimalForecast)
        assertFalse(singleDecimal.isMultiSession)
        assertEquals("⏱️ Dự kiến 1 xe sạc trụ 22.5kW sẽ xong trong 10 phút nữa", singleDecimal.singleSummary)

        // 4. Multi-sessions matching 1.md specification:
        // • 20kW:  ~7-14 phút (2 xe)
        // • 60kW:  ~13 phút (1 xe)
        // • 250kW: ~8 phút (1 xe)
        val multiForecast = StationForecast(
            detailedSessions = listOf(
                ForecastSession(kw = 20.0, min = 7),
                ForecastSession(kw = 20.0, min = 14),
                ForecastSession(kw = 60.0, min = 13),
                ForecastSession(kw = 250.0, min = 8)
            )
        )
        val multiCapsule = resolveForecastCapsuleData(multiForecast)
        assertTrue(multiCapsule.isMultiSession)
        assertEquals("⚡ DỰ KIẾN CỔNG SẮP TRỐNG:", multiCapsule.header)
        assertEquals(3, multiCapsule.bulletLines.size)
        assertEquals("• 20kW:  ~7-14 phút (2 xe)", multiCapsule.bulletLines[0])
        assertEquals("• 60kW:  ~13 phút (1 xe)", multiCapsule.bulletLines[1])
        assertEquals("• 250kW: ~8 phút (1 xe)", multiCapsule.bulletLines[2])
    }

    @Test
    fun testAnimatedCrossfadeModelTransitions_betweenBadgeStates() {
        val forecast = StationForecast(vehicleCount = 1, wattageKw = 60.0, minMinutes = 5, maxMinutes = 5)

        val stateAvailable = resolveStatusBadge("Normal", totalAvailablePlugs = 2, totalPlugs = 4, forecast = forecast)
        val stateSapTrong = resolveStatusBadge("Normal", totalAvailablePlugs = 0, totalPlugs = 4, forecast = forecast)
        val stateHetCong = resolveStatusBadge("Normal", totalAvailablePlugs = 0, totalPlugs = 4, forecast = null)
        val stateBaoTri = resolveStatusBadge("Maintaining", totalAvailablePlugs = 0, totalPlugs = 4, forecast = forecast)
        val stateTamDung = resolveStatusBadge("OutOfService", totalAvailablePlugs = 0, totalPlugs = 4, forecast = forecast)

        // Verify distinct identity of each state so AnimatedContent triggers transitions
        val states = listOf(stateAvailable, stateSapTrong, stateHetCong, stateBaoTri, stateTamDung)
        for (i in states.indices) {
            for (j in states.indices) {
                if (i == j) {
                    assertEquals(states[i], states[j])
                    assertEquals(states[i].hashCode(), states[j].hashCode())
                } else {
                    assertNotEquals("State $i should not equal State $j", states[i], states[j])
                }
            }
        }

        // Verify state labels and colors match visual specification
        assertEquals("Hoạt động", stateAvailable.label)
        assertEquals(Color(0xFF10B981), stateAvailable.dotColor)

        assertEquals("⏱️ Sắp trống", stateSapTrong.label)
        assertEquals(Color(0xFFF59E0B), stateSapTrong.dotColor)

        assertEquals("Hết cổng", stateHetCong.label)
        assertEquals(Color(0xFFEF4444), stateHetCong.dotColor)

        assertEquals("Bảo trì", stateBaoTri.label)
        assertEquals(StatusMaintaining, stateBaoTri.dotColor)

        assertEquals("Tạm dừng", stateTamDung.label)
        assertEquals(StatusOffline, stateTamDung.dotColor)
    }
}
