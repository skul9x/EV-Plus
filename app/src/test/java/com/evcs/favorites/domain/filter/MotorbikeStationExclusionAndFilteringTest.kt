package com.evcs.favorites.domain.filter

import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.domain.model.CustomFilterConfig
import com.evcs.favorites.domain.model.CustomFilterMode
import com.evcs.favorites.domain.model.DcWattageTier
import com.evcs.favorites.domain.model.QuickChipOption
import com.evcs.favorites.domain.model.SmartFilterMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprehensive verification unit test for Phase 02:
 * - Station.hasCarCompatiblePorts() extension function:
 *   - Returns false for pure low-power motorbike ports (3.5kW, 7kW, 7.4kW).
 *   - Falls back to parsing connectors string if powers list is empty.
 *   - Returns true for DC ports, 11kW/22kW AC ports, or ports with typeWatts >= 11,000L.
 * - NearbyStationFilter filtering behavior:
 *   - Pure 7kW / 3.5kW station is filtered out in SmartFilterMode.NONE.
 *   - Pure 7kW station is filtered out in SmartFilterMode.AC.
 *   - Mixed station (60kW DC + 7kW AC) is retained in SmartFilterMode.NONE.
 *   - Mixed station (60kW DC + 7kW AC) is filtered out in SmartFilterMode.AC (no 11kW/22kW AC port).
 *   - Station with 11kW AC port is retained in SmartFilterMode.AC.
 *   - QuickChipOption.ALL / AC excludes pure motorbike stations.
 *   - CustomFilterMode.CUSTOM_RANGE (e.g. minKw = 3, maxKw = 7) allows pure 7kW station to match and be returned.
 *   - filterStations(selectedWattages = emptySet()) excludes pure motorbike stations.
 */
class MotorbikeStationExclusionAndFilteringTest {

    private fun createPort(
        watts: Long,
        available: Int = 1,
        total: Int = 2,
        label: String = "${watts / 1000}kW"
    ): PowerPort {
        return PowerPort(
            typeWatts = watts,
            label = label,
            availablePlugs = available,
            totalPlugs = total,
            displayString = "$label: trống $available/$total cổng"
        )
    }

    private fun createStation(
        id: String,
        name: String = "Station $id",
        depotStatus: String = "Normal",
        powers: List<PowerPort> = emptyList(),
        connectors: String = ""
    ): Station {
        val totalAvailable = powers.sumOf { it.availablePlugs }
        val total = powers.sumOf { it.totalPlugs }
        val effectiveConnectors = if (connectors.isNotEmpty()) {
            connectors
        } else {
            powers.joinToString(", ") { it.label }
        }
        return Station(
            id = id,
            name = name,
            address = "Address $id",
            latitude = 10.762622,
            longitude = 106.660172,
            summary = "Summary $id",
            connectors = effectiveConnectors,
            depotStatus = depotStatus,
            powers = powers,
            totalAvailablePlugs = totalAvailable,
            totalPlugs = total
        )
    }

    @Test
    fun testHasCarCompatiblePorts_pureMotorbikePorts_returnsFalse() {
        val st3_5kw = createStation("st3_5", powers = listOf(createPort(3_500L, label = "3.5kW")))
        val st7kw = createStation("st7", powers = listOf(createPort(7_000L, label = "7kW")))
        val st7_4kw = createStation("st7_4", powers = listOf(createPort(7_400L, label = "7.4kW")))
        val stMultiMotorbike = createStation(
            "st_multi",
            powers = listOf(
                createPort(3_500L, label = "3.5kW"),
                createPort(7_000L, label = "7kW"),
                createPort(7_400L, label = "7.4kW")
            )
        )

        assertFalse("Pure 3.5kW station must not have car-compatible ports", st3_5kw.hasCarCompatiblePorts())
        assertFalse("Pure 7kW station must not have car-compatible ports", st7kw.hasCarCompatiblePorts())
        assertFalse("Pure 7.4kW station must not have car-compatible ports", st7_4kw.hasCarCompatiblePorts())
        assertFalse("Multi-motorbike port station must not have car-compatible ports", stMultiMotorbike.hasCarCompatiblePorts())
    }

    @Test
    fun testHasCarCompatiblePorts_carCompatiblePorts_returnsTrue() {
        val st11kwAc = createStation("st11", powers = listOf(createPort(11_000L, label = "11kW")))
        val st22kwAc = createStation("st22", powers = listOf(createPort(22_000L, label = "22kW")))
        val st30kwDc = createStation("st30", powers = listOf(createPort(30_000L, label = "30kW")))
        val st60kwDc = createStation("st60", powers = listOf(createPort(60_000L, label = "60kW")))
        val stUnratedDc = createStation("st_dc", powers = listOf(createPort(0L, label = "DC Fast")))
        val stMixed = createStation(
            "st_mixed",
            powers = listOf(
                createPort(60_000L, label = "60kW"),
                createPort(7_000L, label = "7kW")
            )
        )

        assertTrue("11kW AC station must have car-compatible ports", st11kwAc.hasCarCompatiblePorts())
        assertTrue("22kW AC station must have car-compatible ports", st22kwAc.hasCarCompatiblePorts())
        assertTrue("30kW DC station must have car-compatible ports", st30kwDc.hasCarCompatiblePorts())
        assertTrue("60kW DC station must have car-compatible ports", st60kwDc.hasCarCompatiblePorts())
        assertTrue("Unrated DC station must have car-compatible ports", stUnratedDc.hasCarCompatiblePorts())
        assertTrue("Mixed 60kW DC + 7kW AC station must have car-compatible ports", stMixed.hasCarCompatiblePorts())
    }

    @Test
    fun testHasCarCompatiblePorts_fallbackToConnectorStringWhenPowersEmpty() {
        val stFallbackMotorbike = createStation("fb1", powers = emptyList(), connectors = "7kW, 3.5kW")
        val stFallbackCarDc = createStation("fb2", powers = emptyList(), connectors = "60kW, 7kW")
        val stFallbackCarAc = createStation("fb3", powers = emptyList(), connectors = "11kW")
        val stEmptyConnectors = createStation("fb4", powers = emptyList(), connectors = "")

        assertFalse("Fallback parsing with only 7kW and 3.5kW must return false", stFallbackMotorbike.hasCarCompatiblePorts())
        assertTrue("Fallback parsing with 60kW and 7kW must return true", stFallbackCarDc.hasCarCompatiblePorts())
        assertTrue("Fallback parsing with 11kW must return true", stFallbackCarAc.hasCarCompatiblePorts())
        assertFalse("Empty connectors and powers must return false", stEmptyConnectors.hasCarCompatiblePorts())
    }

    @Test
    fun testSmartFilterModeNone_pureMotorbikeStationsFilteredOut_mixedRetained() {
        val st7kw = createStation("st7", powers = listOf(createPort(7_000L, available = 2, total = 2)))
        val st3_5kw = createStation("st3_5", powers = listOf(createPort(3_500L, available = 2, total = 2)))
        val stMixed = createStation(
            "st_mixed",
            powers = listOf(
                createPort(60_000L, available = 2, total = 2),
                createPort(7_000L, available = 2, total = 2)
            )
        )
        val st11kw = createStation("st11", powers = listOf(createPort(11_000L, available = 1, total = 2)))

        val stations = listOf(st7kw, st3_5kw, stMixed, st11kw)
        val filtered = NearbyStationFilter.filterSmartStations(stations, mode = SmartFilterMode.NONE)

        assertEquals("Only mixed and 11kW stations should pass in NONE mode", 2, filtered.size)
        assertTrue("Mixed station must be retained in NONE mode", filtered.any { it.id == "st_mixed" })
        assertTrue("11kW station must be retained in NONE mode", filtered.any { it.id == "st11" })
        assertFalse("Pure 7kW station must be filtered out in NONE mode", filtered.any { it.id == "st7" })
        assertFalse("Pure 3.5kW station must be filtered out in NONE mode", filtered.any { it.id == "st3_5" })
    }

    @Test
    fun testSmartFilterModeAc_pureMotorbikeFilteredOut_mixedFilteredOutUnlessCarAcPresent() {
        val st7kw = createStation("st7", powers = listOf(createPort(7_000L, available = 2, total = 2)))
        val stMixedNoCarAc = createStation(
            "st_mixed_no_car_ac",
            powers = listOf(
                createPort(60_000L, available = 2, total = 2),
                createPort(7_000L, available = 2, total = 2)
            )
        )
        val st11kwAc = createStation("st11", powers = listOf(createPort(11_000L, available = 1, total = 2)))
        val stMixedWithCarAc = createStation(
            "st_mixed_with_car_ac",
            powers = listOf(
                createPort(60_000L, available = 2, total = 2),
                createPort(11_000L, available = 1, total = 2),
                createPort(7_000L, available = 2, total = 2)
            )
        )

        val stations = listOf(st7kw, stMixedNoCarAc, st11kwAc, stMixedWithCarAc)
        val filtered = NearbyStationFilter.filterSmartStations(stations, mode = SmartFilterMode.AC)

        assertEquals("Only stations with valid 11kW/22kW AC ports should pass in AC mode", 2, filtered.size)
        assertFalse("Pure 7kW station must be filtered out in AC mode", filtered.any { it.id == "st7" })
        assertFalse("Mixed station (60kW DC + 7kW AC) must be filtered out in AC mode", filtered.any { it.id == "st_mixed_no_car_ac" })
        assertTrue("11kW AC station must be retained in AC mode", filtered.any { it.id == "st11" })
        assertTrue("Mixed station with active 11kW AC must be retained in AC mode", filtered.any { it.id == "st_mixed_with_car_ac" })
    }

    @Test
    fun testSmartFilterModeDc_pureMotorbikeStationsFilteredOut() {
        val st7kw = createStation("st7", powers = listOf(createPort(7_000L, available = 2, total = 2)))
        val stMixed = createStation(
            "st_mixed",
            powers = listOf(
                createPort(60_000L, available = 2, total = 2),
                createPort(7_000L, available = 2, total = 2)
            )
        )

        // Without DC tier (unfiltered tier)
        val filteredNoTier = NearbyStationFilter.filterSmartStations(listOf(st7kw, stMixed), mode = SmartFilterMode.DC)
        assertEquals(1, filteredNoTier.size)
        assertEquals("st_mixed", filteredNoTier.first().id)

        // With DC tier >= 60kW
        val filteredTier = NearbyStationFilter.filterSmartStations(
            listOf(st7kw, stMixed),
            mode = SmartFilterMode.DC,
            dcTier = DcWattageTier.GE_60KW
        )
        assertEquals(1, filteredTier.size)
        assertEquals("st_mixed", filteredTier.first().id)
    }

    @Test
    fun testCustomFilterModeQuickChip_excludesMotorbikeStations() {
        val st7kw = createStation("st7", powers = listOf(createPort(7_000L, available = 2, total = 2)))
        val stMixed = createStation(
            "st_mixed",
            powers = listOf(
                createPort(60_000L, available = 2, total = 2),
                createPort(7_000L, available = 2, total = 2)
            )
        )
        val st11kw = createStation("st11", powers = listOf(createPort(11_000L, available = 1, total = 2)))

        // QuickChip ALL
        val configAll = CustomFilterConfig(mode = CustomFilterMode.QUICK_CHIP, quickChip = QuickChipOption.ALL)
        val filteredAll = NearbyStationFilter.filterSmartStations(
            listOf(st7kw, stMixed, st11kw),
            mode = SmartFilterMode.CUSTOM,
            customConfig = configAll
        )
        assertEquals(2, filteredAll.size)
        assertFalse("Pure 7kW must be excluded under QuickChip ALL", filteredAll.any { it.id == "st7" })

        // QuickChip AC
        val configAc = CustomFilterConfig(mode = CustomFilterMode.QUICK_CHIP, quickChip = QuickChipOption.AC)
        val filteredAc = NearbyStationFilter.filterSmartStations(
            listOf(st7kw, stMixed, st11kw),
            mode = SmartFilterMode.CUSTOM,
            customConfig = configAc
        )
        assertEquals(1, filteredAc.size)
        assertEquals("st11", filteredAc.first().id)
    }

    @Test
    fun testCustomFilterModeCustomRange_honorsExplicitUserQueryForLowPower() {
        val st7kw = createStation("st7", powers = listOf(createPort(7_000L, available = 2, total = 2)))
        val st3_5kw = createStation("st3_5", powers = listOf(createPort(3_500L, available = 1, total = 2)))
        val st60kw = createStation("st60", powers = listOf(createPort(60_000L, available = 2, total = 2)))

        // Explicit user range: 3kW to 7kW
        val configLowRange = CustomFilterConfig(
            mode = CustomFilterMode.CUSTOM_RANGE,
            minKw = 3,
            maxKw = 7
        )
        val filteredLowRange = NearbyStationFilter.filterSmartStations(
            listOf(st7kw, st3_5kw, st60kw),
            mode = SmartFilterMode.CUSTOM,
            customConfig = configLowRange
        )

        assertEquals("Both 7kW and 3.5kW stations match custom range 3..7kW", 2, filteredLowRange.size)
        assertTrue("Pure 7kW station matches range 3..7kW", filteredLowRange.any { it.id == "st7" })
        assertTrue("Pure 3.5kW station matches range 3..7kW", filteredLowRange.any { it.id == "st3_5" })
        assertFalse("60kW station does not match range 3..7kW", filteredLowRange.any { it.id == "st60" })

        // Higher range: 50kW to 150kW
        val configHighRange = CustomFilterConfig(
            mode = CustomFilterMode.CUSTOM_RANGE,
            minKw = 50,
            maxKw = 150
        )
        val filteredHighRange = NearbyStationFilter.filterSmartStations(
            listOf(st7kw, st3_5kw, st60kw),
            mode = SmartFilterMode.CUSTOM,
            customConfig = configHighRange
        )
        assertEquals(1, filteredHighRange.size)
        assertEquals("st60", filteredHighRange.first().id)
    }

    @Test
    fun testFilterStations_withEmptySelectedWattages_excludesMotorbikeStations() {
        val st7kw = createStation("st7", powers = listOf(createPort(7_000L, available = 2, total = 2)))
        val stMixed = createStation(
            "st_mixed",
            powers = listOf(
                createPort(60_000L, available = 2, total = 2),
                createPort(7_000L, available = 2, total = 2)
            )
        )

        val filtered = NearbyStationFilter.filterStations(
            listOf(st7kw, stMixed),
            selectedWattages = emptySet()
        )

        assertEquals(1, filtered.size)
        assertEquals("st_mixed", filtered.first().id)
    }

    @Test
    fun testDepotMaintenanceStatus_excludedRegardlessOfPowers() {
        val stMaintaining = createStation(
            "st_maint",
            depotStatus = "Maintaining",
            powers = listOf(createPort(60_000L, available = 2, total = 2))
        )
        val stOutOfService = createStation(
            "st_oos",
            depotStatus = "OutOfService",
            powers = listOf(createPort(60_000L, available = 2, total = 2))
        )

        val filtered = NearbyStationFilter.filterSmartStations(
            listOf(stMaintaining, stOutOfService),
            mode = SmartFilterMode.NONE
        )
        assertTrue("Maintaining or OutOfService stations must be excluded", filtered.isEmpty())
    }
}
