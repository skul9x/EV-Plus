package com.evcs.favorites.focus

import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.network.here.model.HereConnector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verification test suite for Phase 01: Focus Mode 20kW DC Support & Smart Reroute.
 *
 * Verifies:
 * 1. FocusModeDcFilter recognizes 20kW DC ports while excluding 22kW AC and other AC/motorcycle ports.
 * 2. FocusModeDcFilter.calculateDcSlots correctly calculates available and total DC slots.
 * 3. HereConnector.isDcCharging classifies >= 20kW (excluding 22kW AC) as DC.
 * 4. FocusModeTelemetryEngine.findAlternativeStation selects nearest candidate with matching or higher tier (>= targetMaxDcWatts).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FocusMode20kWSupportTest {

    private fun createSampleStation(
        id: String,
        name: String,
        lat: Double,
        lon: Double,
        powers: List<PowerPort>,
        depotStatus: String = "Normal"
    ): Station {
        val (availDc, totalDc) = FocusModeDcFilter.calculateDcSlots(powers)
        return Station(
            id = id,
            name = name,
            address = "Địa chỉ $name",
            latitude = lat,
            longitude = lon,
            summary = "Trống $availDc/$totalDc cổng sạc DC",
            connectors = powers.joinToString(", ") { it.label },
            depotStatus = depotStatus,
            powers = powers,
            totalAvailablePlugs = powers.sumOf { it.availablePlugs },
            totalPlugs = powers.sumOf { it.totalPlugs },
            evse = "VinFast"
        )
    }

    @Test
    fun verifyFocusMode20kWSupportAndReroute() = runTest {
        // =========================================================================
        // 1. FocusModeDcFilter Port Classification & Threshold
        // =========================================================================
        assertEquals("MIN_DC_POWER_WATTS must be 20kW (20_000L)", 20_000L, FocusModeDcFilter.MIN_DC_POWER_WATTS)

        val port20kDc = PowerPort(typeWatts = 20_000L, label = "20kW", availablePlugs = 2, totalPlugs = 2)
        val port20kLabeledDc = PowerPort(typeWatts = 20_000L, label = "DC 20kW", availablePlugs = 1, totalPlugs = 1)
        val port22kAc = PowerPort(typeWatts = 22_000L, label = "22kW AC", availablePlugs = 2, totalPlugs = 2)
        val port22kUnlabeled = PowerPort(typeWatts = 22_000L, label = "22kW", availablePlugs = 1, totalPlugs = 1)
        val port30kDc = PowerPort(typeWatts = 30_000L, label = "30kW", availablePlugs = 1, totalPlugs = 2)
        val port60kDc = PowerPort(typeWatts = 60_000L, label = "60kW", availablePlugs = 2, totalPlugs = 2)
        val port11kAc = PowerPort(typeWatts = 11_000L, label = "11kW AC", availablePlugs = 4, totalPlugs = 4)
        val port7kAc = PowerPort(typeWatts = 7_400L, label = "7.4kW AC", availablePlugs = 2, totalPlugs = 2)
        val portMoto = PowerPort(typeWatts = 3_300L, label = "3.3kW Xe máy", availablePlugs = 8, totalPlugs = 8)

        assertTrue("20kW DC port must be classified as DC", FocusModeDcFilter.isDcPort(port20kDc))
        assertTrue("20kW DC labeled port must be classified as DC", FocusModeDcFilter.isDcPort(port20kLabeledDc))
        assertTrue("30kW DC port must be classified as DC", FocusModeDcFilter.isDcPort(port30kDc))
        assertTrue("60kW DC port must be classified as DC", FocusModeDcFilter.isDcPort(port60kDc))

        assertFalse("22kW AC port must NOT be DC", FocusModeDcFilter.isDcPort(port22kAc))
        assertFalse("22kW unlabeled port must NOT be DC", FocusModeDcFilter.isDcPort(port22kUnlabeled))
        assertFalse("11kW AC port must NOT be DC", FocusModeDcFilter.isDcPort(port11kAc))
        assertFalse("7.4kW AC port must NOT be DC", FocusModeDcFilter.isDcPort(port7kAc))
        assertFalse("3.3kW motorcycle port must NOT be DC", FocusModeDcFilter.isDcPort(portMoto))

        // =========================================================================
        // 2. Slot Calculation for 20kW DC Stations
        // =========================================================================
        val mixedStationPorts = listOf(port20kDc, port22kAc, port11kAc)
        val (availDc, totalDc) = FocusModeDcFilter.calculateDcSlots(mixedStationPorts)
        // Only port20kDc (2/2) should be counted; 22kW AC (2/2) and 11kW AC (4/4) excluded
        assertEquals("Available DC slots should only count 20kW DC", 2, availDc)
        assertEquals("Total DC slots should only count 20kW DC", 2, totalDc)

        val station20k = createSampleStation(
            id = "station_vf3_20k",
            name = "Trạm Sạc VinFast VF3 20kW",
            lat = 10.7769,
            lon = 106.7009,
            powers = listOf(port20kDc, port22kAc)
        )
        val initialState = FocusModeState.createInitial(station20k)
        assertEquals(2, initialState.availableDcSlots)
        assertEquals(2, initialState.totalDcSlots)
        assertEquals(20_000L, initialState.maxDcPowerWatts)
        assertEquals(20, initialState.maxDcPowerKw)
        assertEquals("🟢 2/2 Trống (20kW)", initialState.statusBadgeText)

        // =========================================================================
        // 3. HereConnector.isDcCharging Classification
        // =========================================================================
        val conn20kDc = HereConnector(maxPowerLevel = 20.0, powerType = "DC")
        val conn20kNoType = HereConnector(maxPowerLevel = 20.0, powerType = null)
        val conn22kAc = HereConnector(maxPowerLevel = 22.0, powerType = "AC_3_PHASE")
        val conn22kNoType = HereConnector(maxPowerLevel = 22.0, powerType = null)
        val conn30k = HereConnector(maxPowerLevel = 30.0, powerType = null)
        val conn60k = HereConnector(maxPowerLevel = 60.0, powerType = "DC")
        val conn11k = HereConnector(maxPowerLevel = 11.0, powerType = "AC_3_PHASE")

        assertTrue("HereConnector 20kW DC must be recognized as DC", conn20kDc.isDcCharging)
        assertTrue("HereConnector 20kW without type must be recognized as DC", conn20kNoType.isDcCharging)
        assertTrue("HereConnector 30kW must be recognized as DC", conn30k.isDcCharging)
        assertTrue("HereConnector 60kW DC must be recognized as DC", conn60k.isDcCharging)

        assertFalse("HereConnector 22kW AC_3_PHASE must NOT be DC", conn22kAc.isDcCharging)
        assertFalse("HereConnector 22kW without type must NOT be DC", conn22kNoType.isDcCharging)
        assertFalse("HereConnector 11kW AC_3_PHASE must NOT be DC", conn11k.isDcCharging)

        // =========================================================================
        // 4. Smart Reroute for 20kW Target Station (Matching or Higher Tier)
        // =========================================================================
        val driverLat = 10.7769
        val driverLon = 106.7009

        // Target station: 20kW DC, currently saturated (0/2 slots available)
        val targetStation = createSampleStation(
            id = "target_20kw_saturated",
            name = "VinFast Bãi Đỗ Xe VF3",
            lat = 10.7770,
            lon = 106.7010,
            powers = listOf(
                PowerPort(typeWatts = 20_000L, label = "20kW", availablePlugs = 0, totalPlugs = 2)
            )
        )

        // Candidate 1: 22kW AC only (has available plugs, but is AC -> must NOT be selected)
        val candAcOnly = createSampleStation(
            id = "cand_ac_only",
            name = "Trạm Sạc AC 22kW",
            lat = 10.7772,
            lon = 106.7012,
            powers = listOf(
                PowerPort(typeWatts = 22_000L, label = "22kW AC", availablePlugs = 4, totalPlugs = 4)
            )
        )

        // Candidate 2: 20kW DC, but saturated (0 available plugs -> must NOT be selected)
        val cand20kFull = createSampleStation(
            id = "cand_20k_full",
            name = "Trạm 20kW Đầy Chỗ",
            lat = 10.7775,
            lon = 106.7015,
            powers = listOf(
                PowerPort(typeWatts = 20_000L, label = "20kW", availablePlugs = 0, totalPlugs = 2)
            )
        )

        // Candidate 3: 20kW DC with available plugs, but depot status is Maintaining -> must NOT be selected
        val candMaintaining = createSampleStation(
            id = "cand_maintaining",
            name = "Trạm 20kW Đang Bảo Trì",
            lat = 10.7773,
            lon = 106.7013,
            powers = listOf(
                PowerPort(typeWatts = 20_000L, label = "20kW", availablePlugs = 2, totalPlugs = 2)
            ),
            depotStatus = "Maintaining"
        )

        // Candidate 4: 20kW DC (exact match), 1 available slot, distance ~2.5km
        val cand20kFar = createSampleStation(
            id = "cand_20k_far",
            name = "Trạm 20kW Xa",
            lat = 10.7950,
            lon = 106.7150,
            powers = listOf(
                PowerPort(typeWatts = 20_000L, label = "20kW", availablePlugs = 1, totalPlugs = 2)
            )
        )

        // Candidate 5: 60kW DC (higher tier >= 20kW), 2 available slots, distance ~0.8km (Closest eligible!)
        val cand60kClose = createSampleStation(
            id = "cand_60k_close",
            name = "Trạm 60kW Gần",
            lat = 10.7820,
            lon = 106.7050,
            powers = listOf(
                PowerPort(typeWatts = 60_000L, label = "60kW", availablePlugs = 2, totalPlugs = 4)
            )
        )

        val candidates = listOf(targetStation, candAcOnly, cand20kFull, candMaintaining, cand20kFar, cand60kClose)

        val recommendation = FocusModeTelemetryEngine.findAlternativeStation(
            targetStation = targetStation,
            candidates = candidates,
            driverLat = driverLat,
            driverLon = driverLon
        )

        assertNotNull("Should find an alternative station recommendation", recommendation)
        assertEquals(
            "Should recommend closest station offering >= 20kW DC (Candidate 5: 60kW)",
            "cand_60k_close",
            recommendation!!.station.id
        )
        assertEquals("Trạm 60kW Gần", recommendation.station.name)
        assertEquals(20_000L, recommendation.matchingPowerWatts)
        assertEquals(2, recommendation.availableDcSlots)
        assertEquals(4, recommendation.totalDcSlots)
        assertTrue("Distance should be around 0.7km - 1.0km", recommendation.distanceKm in 0.5..1.5)
        assertTrue(
            "Display label should format properly",
            recommendation.displayRerouteLabel.startsWith("Đổi trạm: Trạm 60kW Gần")
        )

        // If 60kW candidate is removed, Candidate 4 (20kW DC) should be recommended
        val candidatesWithout60k = listOf(targetStation, candAcOnly, cand20kFull, candMaintaining, cand20kFar)
        val rec20k = FocusModeTelemetryEngine.findAlternativeStation(
            targetStation = targetStation,
            candidates = candidatesWithout60k,
            driverLat = driverLat,
            driverLon = driverLon
        )
        assertNotNull("Should find 20kW alternative station", rec20k)
        assertEquals("cand_20k_far", rec20k!!.station.id)
        assertEquals("Trạm 20kW Xa", rec20k.station.name)

        // If no candidates offer >= 20kW DC with available slots, return null
        val saturatedCandidates = listOf(targetStation, candAcOnly, cand20kFull, candMaintaining)
        val recNull = FocusModeTelemetryEngine.findAlternativeStation(
            targetStation = targetStation,
            candidates = saturatedCandidates,
            driverLat = driverLat,
            driverLon = driverLon
        )
        assertNull("Should return null when no eligible stations are available", recNull)
    }

    @Test
    fun verifyDetailedDcTiersTextFormatting() {
        // Multi-tier station: 60kW (1/4) and 20kW (2/2)
        val port60k = PowerPort(typeWatts = 60_000L, label = "60kW", availablePlugs = 1, totalPlugs = 4)
        val port20k = PowerPort(typeWatts = 20_000L, label = "20kW", availablePlugs = 2, totalPlugs = 2)
        val portAc = PowerPort(typeWatts = 11_000L, label = "11kW AC", availablePlugs = 2, totalPlugs = 2)

        val station = createSampleStation(
            id = "st_nguyen_van_sang",
            name = "VinFast Nguyễn Văn Sáng",
            lat = 21.195641,
            lon = 106.130966,
            powers = listOf(port60k, port20k, portAc)
        )

        val state = FocusModeState.createInitial(station)
        assertEquals(3, state.availableDcSlots)
        assertEquals(6, state.totalDcSlots)
        assertEquals("⚡ 60kW (1/4)  |  20kW (2/2)", state.detailedDcTiersText)

        val viewState = FocusModeViewLayoutHelper.formatViewState(state)
        assertEquals("VinFast Nguyễn Văn Sáng", viewState.stationName)
        assertEquals("⚡ 60kW (1/4)  |  20kW (2/2)", viewState.detailedTiersText)
        assertEquals(FocusBadgeColor.GREEN, viewState.badgeColorToken)

        // Saturated state: 0/6 slots available
        val fullState = state.copy(
            targetStation = station.copy(
                powers = listOf(
                    port60k.copy(availablePlugs = 0),
                    port20k.copy(availablePlugs = 0),
                    portAc
                )
            ),
            availableDcSlots = 0
        )
        assertTrue(fullState.isDcFull)
        assertEquals("⚡ 60kW (0/4)  |  20kW (0/2)", fullState.detailedDcTiersText)
        val fullViewState = FocusModeViewLayoutHelper.formatViewState(fullState)
        assertEquals("🔴 HẾT CHỖ!", fullViewState.badgeText)
        assertEquals("⚡ 60kW (0/4)  |  20kW (0/2)", fullViewState.detailedTiersText)

        // Offline state
        val offlineState = state.copy(
            connectionStatus = FocusConnectionStatus.OFFLINE,
            offlineMessage = "⚠️ Mất kết nối - Dữ liệu lúc 14:35"
        )
        assertNull("Offline state should have null detailedDcTiersText", offlineState.detailedDcTiersText)
        val offlineViewState = FocusModeViewLayoutHelper.formatViewState(offlineState)
        assertNull("Offline view state should have null detailedTiersText", offlineViewState.detailedTiersText)
        assertEquals("⚠️ Mất kết nối - Dữ liệu lúc 14:35", offlineViewState.badgeText)
    }
}
