package com.evcs.favorites.ui.components

import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprehensive verification test for Phase 03:
 * AC-Only Station Focus Button Visibility Refinement.
 *
 * Verifies that:
 * 1. Stations with only AC charging ports (7kW, 11kW, 22kW) hide Focus Mode and expand navigation to full width.
 * 2. Stations with 20kW DC charging capability show Focus Mode and split primary action row equally.
 * 3. Stations with mixed AC and DC ports (e.g. 11kW AC + 60kW DC) show Focus Mode.
 * 4. Stations with 0 power ports hide Focus Mode and expand navigation to full width.
 * 5. Explicitly labeled DC ports (including 0W fallback labels) correctly trigger Focus Mode visibility.
 * 6. NativeStationDetailSheetHelper helper functions (hasDcCharging, shouldShowFocusModeButton, resolvePrimaryActionLayout)
 *    produce consistent and deterministic specs.
 */
class StationDetailFocusButtonVisibilityTest {

    private fun createStation(powers: List<PowerPort>): Station {
        return Station(
            id = "test-station-id",
            name = "Test Charging Station",
            address = "123 Test Street",
            latitude = 21.0285,
            longitude = 105.8542,
            summary = "24/7",
            connectors = powers.joinToString(", ") { it.label },
            depotStatus = "Normal",
            powers = powers,
            totalAvailablePlugs = powers.sumOf { it.availablePlugs },
            totalPlugs = powers.sumOf { it.totalPlugs }
        )
    }

    private fun createPort(
        typeWatts: Long,
        label: String = "${typeWatts / 1000}kW",
        available: Int = 1,
        total: Int = 1
    ): PowerPort {
        return PowerPort(
            typeWatts = typeWatts,
            label = label,
            availablePlugs = available,
            totalPlugs = total,
            displayString = "$label: $available/$total"
        )
    }

    @Test
    fun testAcOnlyStationHidesFocusButtonAndExpandsNavigate() {
        val acStation = createStation(
            listOf(
                createPort(7_000L, "7kW"),
                createPort(11_000L, "11kW"),
                createPort(22_000L, "22kW")
            )
        )

        assertFalse("AC-only station must not have DC charging", NativeStationDetailSheetHelper.hasDcCharging(acStation))
        assertFalse("AC-only station must not show Focus Mode button", NativeStationDetailSheetHelper.shouldShowFocusModeButton(acStation))

        val layoutSpec = NativeStationDetailSheetHelper.resolvePrimaryActionLayout(acStation)
        assertEquals(
            PrimaryActionLayoutSpec(showFocusMode = false, isNavigateFullWidth = true),
            layoutSpec
        )
    }

    @Test
    fun test20kWStationShowsFocusButtonAndSplitsRow() {
        val station20kW = createStation(
            listOf(
                createPort(20_000L, "20kW")
            )
        )

        assertTrue("20kW station must have DC charging", NativeStationDetailSheetHelper.hasDcCharging(station20kW))
        assertTrue("20kW station must show Focus Mode button", NativeStationDetailSheetHelper.shouldShowFocusModeButton(station20kW))

        val layoutSpec = NativeStationDetailSheetHelper.resolvePrimaryActionLayout(station20kW)
        assertEquals(
            PrimaryActionLayoutSpec(showFocusMode = true, isNavigateFullWidth = false),
            layoutSpec
        )
    }

    @Test
    fun testMixedAcAndDcStationShowsFocusButton() {
        val mixedStation = createStation(
            listOf(
                createPort(11_000L, "11kW"),
                createPort(60_000L, "60kW")
            )
        )

        assertTrue("Mixed AC and DC station must have DC charging", NativeStationDetailSheetHelper.hasDcCharging(mixedStation))
        assertTrue("Mixed station must show Focus Mode button", NativeStationDetailSheetHelper.shouldShowFocusModeButton(mixedStation))

        val layoutSpec = NativeStationDetailSheetHelper.resolvePrimaryActionLayout(mixedStation)
        assertEquals(
            PrimaryActionLayoutSpec(showFocusMode = true, isNavigateFullWidth = false),
            layoutSpec
        )
    }

    @Test
    fun testZeroPowerPortsHidesFocusButtonAndExpandsNavigate() {
        val emptyStation = createStation(emptyList())

        assertFalse("Station with no power ports must not have DC charging", NativeStationDetailSheetHelper.hasDcCharging(emptyStation))
        assertFalse("Station with no power ports must not show Focus Mode button", NativeStationDetailSheetHelper.shouldShowFocusModeButton(emptyStation))

        val layoutSpec = NativeStationDetailSheetHelper.resolvePrimaryActionLayout(emptyStation)
        assertEquals(
            PrimaryActionLayoutSpec(showFocusMode = false, isNavigateFullWidth = true),
            layoutSpec
        )
    }

    @Test
    fun testExplicitDcLabelWithZeroWattsShowsFocusButton() {
        val explicitDcStation = createStation(
            listOf(
                createPort(0L, "DC Fast")
            )
        )

        assertTrue("Station with explicit DC label must have DC charging", NativeStationDetailSheetHelper.hasDcCharging(explicitDcStation))
        assertTrue("Station with explicit DC label must show Focus Mode button", NativeStationDetailSheetHelper.shouldShowFocusModeButton(explicitDcStation))

        val layoutSpec = NativeStationDetailSheetHelper.resolvePrimaryActionLayout(explicitDcStation)
        assertEquals(
            PrimaryActionLayoutSpec(showFocusMode = true, isNavigateFullWidth = false),
            layoutSpec
        )
    }

    @Test
    fun testHighPowerDcTiersShowFocusButton() {
        val superchargingStation = createStation(
            listOf(
                createPort(30_000L, "30kW"),
                createPort(120_000L, "120kW"),
                createPort(250_000L, "250kW")
            )
        )

        assertTrue("Supercharging station must have DC charging", NativeStationDetailSheetHelper.hasDcCharging(superchargingStation))
        assertTrue("Supercharging station must show Focus Mode button", NativeStationDetailSheetHelper.shouldShowFocusModeButton(superchargingStation))

        val layoutSpec = NativeStationDetailSheetHelper.resolvePrimaryActionLayout(superchargingStation)
        assertEquals(
            PrimaryActionLayoutSpec(showFocusMode = true, isNavigateFullWidth = false),
            layoutSpec
        )
    }
}
