package com.evcs.favorites

import com.evcs.favorites.data.model.EvsePowerRaw
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.ui.components.resolveStatusBadge
import com.evcs.favorites.ui.theme.ElectricCyan
import com.evcs.favorites.ui.theme.StatusAvailable
import com.evcs.favorites.ui.theme.StatusBusy
import com.evcs.favorites.ui.theme.StatusMaintaining
import com.evcs.favorites.ui.theme.StatusOffline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Core verification test for Phase 01:
 * 1. Favorite stations with connector string "30kW, 20kW, 3.5kW" and unverified live telemetry
 *    render clean connector chips without any "0/0" string.
 * 2. Status badge displays "Hoạt động" or "Đã lưu" and never "Hết cổng" when totalPlugs == 0.
 * 3. Verified live telemetry (e.g. 2 available / 4 total for 30kW) accurately displays "30kW: trống 2/4"
 *    and positive availability badge.
 * 4. Full telemetry occupancy (0 available / 4 total) displays "Hết cổng".
 */
class StationCardStatusTest {

    @Test
    fun testStationCardStatusAndConnectorsCoreFunctionality() {
        // =====================================================================
        // Part 1: Baseline Connector Parsing without Live Telemetry
        // =====================================================================
        val connectorString = "30kW, 20kW, 3.5kW"
        val parsedPorts = EvcsRepository.parseConnectorsToPowers(connectorString)

        assertEquals(3, parsedPorts.size)

        // Port 1: 30kW
        val port30 = parsedPorts[0]
        assertEquals("30kW", port30.label)
        assertEquals(30000L, port30.typeWatts)
        assertEquals(0, port30.availablePlugs)
        assertEquals(0, port30.totalPlugs)
        assertFalse("Port with totalPlugs == 0 must not report live telemetry", port30.hasLiveTelemetry)
        assertEquals("30kW", port30.chipDisplayString)
        assertFalse("Chip display string must not contain 0/0", port30.chipDisplayString.contains("0/0"))
        assertFalse("Chip display string must not contain trống", port30.chipDisplayString.contains("trống"))

        // Port 2: 20kW
        val port20 = parsedPorts[1]
        assertEquals("20kW", port20.label)
        assertEquals(20000L, port20.typeWatts)
        assertEquals(0, port20.totalPlugs)
        assertFalse(port20.hasLiveTelemetry)
        assertEquals("20kW", port20.chipDisplayString)
        assertFalse(port20.chipDisplayString.contains("0/0"))

        // Port 3: 3.5kW
        val port35 = parsedPorts[2]
        assertEquals("3.5kW", port35.label)
        assertEquals(3500L, port35.typeWatts)
        assertEquals(0, port35.totalPlugs)
        assertFalse(port35.hasLiveTelemetry)
        assertEquals("3.5kW", port35.chipDisplayString)
        assertFalse(port35.chipDisplayString.contains("0/0"))

        // Fallback station representation
        val fallbackStation = Station(
            id = "C.BNI0012",
            name = "VinFast - TTTM Dabaco Mart Quế Võ",
            address = "Bắc Ninh",
            latitude = 21.14,
            longitude = 106.16,
            summary = "Mở 24/7",
            connectors = connectorString,
            depotStatus = "Unknown",
            powers = parsedPorts,
            totalAvailablePlugs = 0,
            totalPlugs = 0
        )
        assertFalse("Station without live telemetry must report hasLiveTelemetry = false", fallbackStation.hasLiveTelemetry)

        // =====================================================================
        // Part 2: Status Badge Verification when totalPlugs == 0
        // =====================================================================
        // Case 2a: depotStatus = "Unknown", totalPlugs = 0 -> "Đã lưu" (never "Hết cổng")
        val badgeUnknown = resolveStatusBadge(
            depotStatus = "Unknown",
            totalAvailablePlugs = 0,
            totalPlugs = 0
        )
        assertEquals("Đã lưu", badgeUnknown.label)
        assertEquals(ElectricCyan, badgeUnknown.dotColor)
        assertNotEquals("Hết cổng", badgeUnknown.label)

        // Case 2b: depotStatus = "Normal", totalPlugs = 0 -> "Hoạt động" (never "Hết cổng")
        val badgeNormalUnverified = resolveStatusBadge(
            depotStatus = "Normal",
            totalAvailablePlugs = 0,
            totalPlugs = 0
        )
        assertEquals("Hoạt động", badgeNormalUnverified.label)
        assertEquals(StatusAvailable, badgeNormalUnverified.dotColor)
        assertNotEquals("Hết cổng", badgeNormalUnverified.label)

        // Case 2c: depotStatus = blank, totalPlugs = 0 -> "Đã lưu" (never "Hết cổng")
        val badgeBlank = resolveStatusBadge(
            depotStatus = "",
            totalAvailablePlugs = 0,
            totalPlugs = 0
        )
        assertEquals("Đã lưu", badgeBlank.label)
        assertNotEquals("Hết cổng", badgeBlank.label)

        // Case 2d: Maintaining and OutOfService always preserve their alerts
        val badgeMaintaining = resolveStatusBadge(
            depotStatus = "Maintaining",
            totalAvailablePlugs = 0,
            totalPlugs = 0
        )
        assertEquals("Bảo trì", badgeMaintaining.label)
        assertEquals(StatusMaintaining, badgeMaintaining.dotColor)

        val badgeOutOfService = resolveStatusBadge(
            depotStatus = "OutOfService",
            totalAvailablePlugs = 0,
            totalPlugs = 0
        )
        assertEquals("Tạm dừng", badgeOutOfService.label)
        assertEquals(StatusOffline, badgeOutOfService.dotColor)

        // =====================================================================
        // Part 3: Verified Live Telemetry
        // =====================================================================
        val livePort = PowerPort(
            typeWatts = 30000L,
            label = "30kW",
            availablePlugs = 2,
            totalPlugs = 4,
            displayString = "30kW: trống 2/4 cổng"
        )
        assertTrue("Port with totalPlugs > 0 must report hasLiveTelemetry = true", livePort.hasLiveTelemetry)
        assertEquals("30kW: trống 2/4", livePort.chipDisplayString)

        val liveStation = Station(
            id = "C.BNI0012",
            name = "VinFast - TTTM Dabaco Mart Quế Võ",
            address = "Bắc Ninh",
            latitude = 21.14,
            longitude = 106.16,
            summary = "Mở 24/7",
            connectors = "30kW",
            depotStatus = "Normal",
            powers = listOf(livePort),
            totalAvailablePlugs = 2,
            totalPlugs = 4
        )
        assertTrue(liveStation.hasLiveTelemetry)

        // Positive availability badge
        val badgeLiveAvailable = resolveStatusBadge(
            depotStatus = liveStation.depotStatus,
            totalAvailablePlugs = liveStation.totalAvailablePlugs,
            totalPlugs = liveStation.totalPlugs
        )
        assertEquals("Hoạt động", badgeLiveAvailable.label)
        assertEquals(StatusAvailable, badgeLiveAvailable.dotColor)

        // =====================================================================
        // Part 4: Fully Occupied Live Telemetry (0 available / 4 total)
        // =====================================================================
        val fullLiveStation = liveStation.copy(
            powers = listOf(livePort.copy(availablePlugs = 0, totalPlugs = 4)),
            totalAvailablePlugs = 0,
            totalPlugs = 4
        )
        val badgeFull = resolveStatusBadge(
            depotStatus = fullLiveStation.depotStatus,
            totalAvailablePlugs = fullLiveStation.totalAvailablePlugs,
            totalPlugs = fullLiveStation.totalPlugs
        )
        assertEquals("Hết cổng", badgeFull.label)
        assertEquals(StatusBusy, badgeFull.dotColor)

        // =====================================================================
        // Part 5: EvsePowerRaw Mapping Accuracy
        // =====================================================================
        val rawLive = EvsePowerRaw(
            type = 30000L,
            numberOfAvailableEvse = 2,
            totalEvse = 4
        ).toDomainPowerPort()
        assertEquals("30kW", rawLive.label)
        assertEquals("30kW: trống 2/4 cổng", rawLive.displayString)
        assertEquals("30kW: trống 2/4", rawLive.chipDisplayString)
        assertTrue(rawLive.hasLiveTelemetry)

        val rawUnverified = EvsePowerRaw(
            type = 30000L,
            numberOfAvailableEvse = 0,
            totalEvse = 0
        ).toDomainPowerPort()
        assertEquals("30kW", rawUnverified.label)
        assertEquals("30kW", rawUnverified.displayString)
        assertEquals("30kW", rawUnverified.chipDisplayString)
        assertFalse(rawUnverified.hasLiveTelemetry)
    }
}
