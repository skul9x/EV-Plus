package com.evcs.favorites.ui.components

import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.domain.filter.clearConnectorCompatibilityCache
import com.evcs.favorites.domain.filter.clearConnectorDcCache
import com.evcs.favorites.domain.filter.hasDcPorts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Single comprehensive unit test verifying AC focus button suppression
 * and adaptive button layout for Phase 01.
 *
 * Verifies:
 * 1. Pure AC stations (11kW, 22kW) return hasDcPorts() == false and suppress Focus button across all filter states.
 * 2. DC-only stations return hasDcPorts() == true and show Focus button when AC filter is inactive, but suppress when AC filter is active.
 * 3. Hybrid stations (AC + DC) show Focus button when AC filter is inactive, but suppress when AC filter is active.
 * 4. Stations with empty powers fall back to connector string parsing to accurately determine DC capability.
 * 5. Primary action layout specs adaptively expand "Chỉ Đường" to full width when Focus is suppressed, and split when Focus is shown.
 * 6. Landscape filter switching (toggling AC filter on/off) deterministically updates Focus button visibility and layout specs.
 */
class StationDetailAcFocusButtonSuppressionTest {

    @Before
    fun setUp() {
        clearConnectorCompatibilityCache()
        clearConnectorDcCache()
    }

    private fun createStation(
        powers: List<PowerPort>,
        connectors: String = ""
    ): Station {
        return Station(
            id = "test-station-id",
            name = "Test Charging Station",
            address = "123 Test Street",
            latitude = 21.0285,
            longitude = 105.8542,
            summary = "24/7",
            connectors = connectors.ifBlank { powers.joinToString(", ") { it.label } },
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
    fun testPureAcStationSuppressesFocusButtonRegardlessOfFilter() {
        val pureAcStation = createStation(
            listOf(
                createPort(11_000L, "11kW AC"),
                createPort(22_000L, "22kW AC")
            )
        )

        assertFalse("Pure AC station must return hasDcPorts == false", pureAcStation.hasDcPorts())

        // Focus button must be suppressed when AC filter is NOT active
        val showWhenAcFilterInactive = NativeStationDetailSheetHelper.shouldShowFocusButton(
            station = pureAcStation,
            isAcFilterActive = false
        )
        assertFalse("Pure AC station must hide Focus button even when AC filter is inactive", showWhenAcFilterInactive)

        // Focus button must also be suppressed when AC filter IS active
        val showWhenAcFilterActive = NativeStationDetailSheetHelper.shouldShowFocusButton(
            station = pureAcStation,
            isAcFilterActive = true
        )
        assertFalse("Pure AC station must hide Focus button when AC filter is active", showWhenAcFilterActive)

        // Verify layout spec expands "Chỉ Đường" to full width in both cases
        val layoutInactive = NativeStationDetailSheetHelper.resolvePrimaryActionLayout(pureAcStation, isAcFilterActive = false)
        assertEquals(
            PrimaryActionLayoutSpec(showFocusMode = false, isNavigateFullWidth = true),
            layoutInactive
        )

        val layoutActive = NativeStationDetailSheetHelper.resolvePrimaryActionLayout(pureAcStation, isAcFilterActive = true)
        assertEquals(
            PrimaryActionLayoutSpec(showFocusMode = false, isNavigateFullWidth = true),
            layoutActive
        )
    }

    @Test
    fun testDcOnlyStationShowsFocusWhenAcFilterInactiveAndSuppressesWhenAcFilterActive() {
        val dcStation = createStation(
            listOf(
                createPort(60_000L, "60kW DC"),
                createPort(120_000L, "120kW DC")
            )
        )

        assertTrue("DC-only station must return hasDcPorts == true", dcStation.hasDcPorts())

        // Normal mode (Favorites, Search, or Nearby with DC/All filter): Focus button visible
        val showInactive = NativeStationDetailSheetHelper.shouldShowFocusButton(dcStation, isAcFilterActive = false)
        assertTrue("DC station must show Focus button when AC filter is inactive", showInactive)

        val layoutInactive = NativeStationDetailSheetHelper.resolvePrimaryActionLayout(dcStation, isAcFilterActive = false)
        assertEquals(
            PrimaryActionLayoutSpec(showFocusMode = true, isNavigateFullWidth = false),
            layoutInactive
        )

        // AC filter active: Focus button suppressed
        val showActive = NativeStationDetailSheetHelper.shouldShowFocusButton(dcStation, isAcFilterActive = true)
        assertFalse("DC station opened under AC filter must suppress Focus button", showActive)

        val layoutActive = NativeStationDetailSheetHelper.resolvePrimaryActionLayout(dcStation, isAcFilterActive = true)
        assertEquals(
            PrimaryActionLayoutSpec(showFocusMode = false, isNavigateFullWidth = true),
            layoutActive
        )
    }

    @Test
    fun testHybridStationShowsFocusWhenAcFilterInactiveAndSuppressesWhenAcFilterActive() {
        val hybridStation = createStation(
            listOf(
                createPort(11_000L, "11kW AC"),
                createPort(60_000L, "60kW DC")
            )
        )

        assertTrue("Hybrid station must return hasDcPorts == true", hybridStation.hasDcPorts())

        // When AC filter is inactive (e.g. opened from Favorites or Nearby "All" mode)
        assertTrue(
            "Hybrid station must show Focus button when AC filter is inactive",
            NativeStationDetailSheetHelper.shouldShowFocusButton(hybridStation, isAcFilterActive = false)
        )
        val layoutInactive = NativeStationDetailSheetHelper.resolvePrimaryActionLayout(hybridStation, isAcFilterActive = false)
        assertEquals(
            PrimaryActionLayoutSpec(showFocusMode = true, isNavigateFullWidth = false),
            layoutInactive
        )

        // When opened under AC filter
        assertFalse(
            "Hybrid station must suppress Focus button when AC filter is active",
            NativeStationDetailSheetHelper.shouldShowFocusButton(hybridStation, isAcFilterActive = true)
        )
        val layoutActive = NativeStationDetailSheetHelper.resolvePrimaryActionLayout(hybridStation, isAcFilterActive = true)
        assertEquals(
            PrimaryActionLayoutSpec(showFocusMode = false, isNavigateFullWidth = true),
            layoutActive
        )
    }

    @Test
    fun testEmptyPowersFallbackToConnectorParsing() {
        // Pure AC connectors string fallback
        val emptyPowersAcStation = createStation(
            powers = emptyList(),
            connectors = "11kW AC, 22kW AC"
        )
        assertFalse("Empty powers with AC connectors must return hasDcPorts == false", emptyPowersAcStation.hasDcPorts())
        assertFalse(
            "Empty powers with AC connectors must suppress Focus button",
            NativeStationDetailSheetHelper.shouldShowFocusButton(emptyPowersAcStation, isAcFilterActive = false)
        )

        // DC connectors string fallback
        val emptyPowersDcStation = createStation(
            powers = emptyList(),
            connectors = "30kW DC, 60kW DC"
        )
        assertTrue("Empty powers with DC connectors must return hasDcPorts == true", emptyPowersDcStation.hasDcPorts())
        assertTrue(
            "Empty powers with DC connectors must show Focus button when AC filter is inactive",
            NativeStationDetailSheetHelper.shouldShowFocusButton(emptyPowersDcStation, isAcFilterActive = false)
        )
        assertFalse(
            "Empty powers with DC connectors must suppress Focus button when AC filter is active",
            NativeStationDetailSheetHelper.shouldShowFocusButton(emptyPowersDcStation, isAcFilterActive = true)
        )

        // Blank connectors
        val blankStation = createStation(
            powers = emptyList(),
            connectors = ""
        )
        assertFalse("Empty powers and blank connectors must return hasDcPorts == false", blankStation.hasDcPorts())
        assertFalse(
            "Blank station must suppress Focus button",
            NativeStationDetailSheetHelper.shouldShowFocusButton(blankStation, isAcFilterActive = false)
        )
    }

    @Test
    fun testLandscapeFilterTransitionTogglesFocusButton() {
        val hybridStation = createStation(
            listOf(
                createPort(22_000L, "22kW AC"),
                createPort(120_000L, "120kW DC")
            )
        )
        val pureAcStation = createStation(
            listOf(
                createPort(11_000L, "11kW AC"),
                createPort(22_000L, "22kW AC")
            )
        )

        // State 1: User is on AC filter chip (isAcActive = true)
        var isAcFilterActive = true

        assertFalse(
            "Under AC filter, hybrid station suppresses Focus button",
            NativeStationDetailSheetHelper.shouldShowFocusButton(hybridStation, isAcFilterActive)
        )
        assertEquals(
            PrimaryActionLayoutSpec(showFocusMode = false, isNavigateFullWidth = true),
            NativeStationDetailSheetHelper.resolvePrimaryActionLayout(hybridStation, isAcFilterActive)
        )

        assertFalse(
            "Under AC filter, pure AC station suppresses Focus button",
            NativeStationDetailSheetHelper.shouldShowFocusButton(pureAcStation, isAcFilterActive)
        )
        assertEquals(
            PrimaryActionLayoutSpec(showFocusMode = false, isNavigateFullWidth = true),
            NativeStationDetailSheetHelper.resolvePrimaryActionLayout(pureAcStation, isAcFilterActive)
        )

        // State 2: User clicks DC chip or All chip in Landscape Master column (isAcActive = false)
        isAcFilterActive = false

        assertTrue(
            "Switching away from AC filter immediately restores Focus button on hybrid station",
            NativeStationDetailSheetHelper.shouldShowFocusButton(hybridStation, isAcFilterActive)
        )
        assertEquals(
            PrimaryActionLayoutSpec(showFocusMode = true, isNavigateFullWidth = false),
            NativeStationDetailSheetHelper.resolvePrimaryActionLayout(hybridStation, isAcFilterActive)
        )

        assertFalse(
            "Switching away from AC filter still suppresses Focus button on pure AC station",
            NativeStationDetailSheetHelper.shouldShowFocusButton(pureAcStation, isAcFilterActive)
        )
        assertEquals(
            PrimaryActionLayoutSpec(showFocusMode = false, isNavigateFullWidth = true),
            NativeStationDetailSheetHelper.resolvePrimaryActionLayout(pureAcStation, isAcFilterActive)
        )
    }

    @Test
    fun testLowPowerMotorbikeStationSuppressesFocusButton() {
        val motorbikeStation = createStation(
            listOf(
                createPort(3_500L, "3.5kW"),
                createPort(7_000L, "7kW")
            )
        )

        assertFalse("Motorbike low-power station must not have DC ports", motorbikeStation.hasDcPorts())
        assertFalse(
            "Motorbike station must suppress Focus button",
            NativeStationDetailSheetHelper.shouldShowFocusButton(motorbikeStation, isAcFilterActive = false)
        )
        assertEquals(
            PrimaryActionLayoutSpec(showFocusMode = false, isNavigateFullWidth = true),
            NativeStationDetailSheetHelper.resolvePrimaryActionLayout(motorbikeStation, isAcFilterActive = false)
        )
    }
}
