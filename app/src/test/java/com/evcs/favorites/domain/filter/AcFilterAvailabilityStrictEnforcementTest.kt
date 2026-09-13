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
 * Single comprehensive test suite for Phase 01: AC Filter Availability Strict Enforcement.
 *
 * Verifies:
 * 1. Strict availability under [SmartFilterMode.AC]:
 *    - Stations with available 11kW or 22kW (availablePlugs > 0) are included.
 *    - Stations with full/occupied AC ports (availablePlugs == 0) are strictly excluded even if includeFullStations = true.
 * 2. Strict availability under [QuickChipOption.AC] in [CustomFilterMode.QUICK_CHIP]:
 *    - Same strict availability enforcement (ignores includeFullStations).
 * 3. Private / Franchise stations ("Tư nhân"):
 *    - Franchise station with 11kW/22kW (availablePlugs > 0) is retained.
 *    - Franchise station with only DC ports or sub-11kW ports (e.g. 20kW DC + 3.5kW) is excluded.
 * 4. Hybrid stations (DC + AC):
 *    - Station with available DC (e.g. 120kW 3/4 free) and available AC (11kW 1/2 free) is included.
 *    - Station with available DC (e.g. 120kW 3/4 free) but full AC (11kW 0/2 free) is excluded under AC filter
 *      even if includeFullStations = true.
 * 5. Regression safety for Non-AC modes:
 *    - [SmartFilterMode.NONE], [SmartFilterMode.DC], [QuickChipOption.DC_GE_120KW], and [CustomFilterMode.CUSTOM_RANGE]
 *      continue to respect includeFullStations = true when ports are full.
 * 6. Non-VinFast and OutOfService/Maintaining stations remain excluded.
 */
class AcFilterAvailabilityStrictEnforcementTest {

    private fun createTestStation(
        id: String,
        name: String = "Station $id",
        evse: String = "VinFast",
        depotStatus: String = "Normal",
        powers: List<PowerPort> = emptyList()
    ): Station {
        val totalAvailable = powers.sumOf { it.availablePlugs }
        val total = powers.sumOf { it.totalPlugs }
        return Station(
            id = id,
            name = name,
            address = "Address $id",
            latitude = 10.762622,
            longitude = 106.660172,
            summary = "Summary $id",
            connectors = powers.joinToString(", ") { it.label },
            depotStatus = depotStatus,
            powers = powers,
            totalAvailablePlugs = totalAvailable,
            totalPlugs = total,
            evse = evse
        )
    }

    private fun createPort(
        watts: Long,
        available: Int = 1,
        total: Int = 2,
        label: String = "${watts / 1000}kW"
    ): PowerPort {
        return PowerPort(
            typeWatts = watts,
            availablePlugs = available,
            totalPlugs = total,
            label = label
        )
    }

    @Test
    fun testAcFilterStrictAvailabilityIgnoresIncludeFullStations() {
        val stationAvailable11kw = createTestStation(
            id = "st_11kw_avail",
            name = "VinFast - Vincom Plaza",
            powers = listOf(createPort(11_000L, available = 1, total = 1))
        )
        val stationFull11kw = createTestStation(
            id = "st_11kw_full",
            name = "VinFast - Mega Mall",
            powers = listOf(createPort(11_000L, available = 0, total = 2))
        )
        val stationAvailable22kw = createTestStation(
            id = "st_22kw_avail",
            name = "VinFast - Center",
            powers = listOf(createPort(22_000L, available = 1, total = 2))
        )
        val stationFull22kw = createTestStation(
            id = "st_22kw_full",
            name = "VinFast - Tower",
            powers = listOf(createPort(22_000L, available = 0, total = 1))
        )

        val stations = listOf(stationAvailable11kw, stationFull11kw, stationAvailable22kw, stationFull22kw)

        // When includeFullStations = false: full AC stations must be excluded
        val resultDefault = NearbyStationFilter.filterSmartStations(
            stations = stations,
            mode = SmartFilterMode.AC,
            includeFullStations = false
        )
        assertEquals(listOf("st_11kw_avail", "st_22kw_avail"), resultDefault.map { it.id })

        // When includeFullStations = true: full AC stations must STILL be excluded
        val resultIncludeFull = NearbyStationFilter.filterSmartStations(
            stations = stations,
            mode = SmartFilterMode.AC,
            includeFullStations = true
        )
        assertEquals(
            "Full AC stations must be excluded even when includeFullStations is true",
            listOf("st_11kw_avail", "st_22kw_avail"),
            resultIncludeFull.map { it.id }
        )
    }

    @Test
    fun testQuickChipAcStrictAvailabilityIgnoresIncludeFullStations() {
        val stationAvailable = createTestStation(
            id = "st_qc_ac_avail",
            powers = listOf(createPort(11_000L, available = 2, total = 2))
        )
        val stationFull = createTestStation(
            id = "st_qc_ac_full",
            powers = listOf(createPort(11_000L, available = 0, total = 2))
        )
        val stations = listOf(stationAvailable, stationFull)
        val config = CustomFilterConfig(
            mode = CustomFilterMode.QUICK_CHIP,
            quickChip = QuickChipOption.AC
        )

        // Under QUICK_CHIP AC with includeFullStations = false
        val resultDefault = NearbyStationFilter.filterSmartStations(
            stations = stations,
            mode = SmartFilterMode.CUSTOM,
            customConfig = config,
            includeFullStations = false
        )
        assertEquals(listOf("st_qc_ac_avail"), resultDefault.map { it.id })

        // Under QUICK_CHIP AC with includeFullStations = true
        val resultIncludeFull = NearbyStationFilter.filterSmartStations(
            stations = stations,
            mode = SmartFilterMode.CUSTOM,
            customConfig = config,
            includeFullStations = true
        )
        assertEquals(
            "QuickChipOption.AC must strictly exclude full stations even with includeFullStations = true",
            listOf("st_qc_ac_avail"),
            resultIncludeFull.map { it.id }
        )
    }

    @Test
    fun testPrivateFranchiseStationRetentionAndExclusionRules() {
        // Private franchise station with 11kW (1/1 available) and 20kW DC
        val privateStationWithAc = createTestStation(
            id = "st_private_ac",
            name = "VinFast - Nhượng Quyền Tư Nhân Vũ Thị Hợi",
            powers = listOf(
                createPort(20_000L, available = 1, total = 1),
                createPort(11_000L, available = 1, total = 1)
            )
        )

        // Private franchise station with only 20kW DC and 3.5kW sub-car port
        val privateStationOnlyDcAndLowAc = createTestStation(
            id = "st_private_dc_only",
            name = "VinFast - Nhượng Quyền Tư Nhân ABC",
            powers = listOf(
                createPort(20_000L, available = 1, total = 1),
                createPort(3_500L, available = 1, total = 1)
            )
        )

        // Standard official station with 22kW (1/1 available)
        val officialStationWithAc = createTestStation(
            id = "st_official_ac",
            name = "VinFast - Vincom Plaza",
            powers = listOf(createPort(22_000L, available = 1, total = 1))
        )

        val stations = listOf(privateStationWithAc, privateStationOnlyDcAndLowAc, officialStationWithAc)

        val acResult = NearbyStationFilter.filterSmartStations(
            stations = stations,
            mode = SmartFilterMode.AC
        )

        // Both private and official stations with available 11kW or 22kW must be included.
        // Station with only 20kW DC + 3.5kW must be excluded.
        assertEquals(listOf("st_private_ac", "st_official_ac"), acResult.map { it.id })
    }

    @Test
    fun testHybridStationScenarios() {
        // Hybrid station with 120kW DC (3/4 available) and 11kW AC (1/2 available) -> Included
        val hybridWithFreeAc = createTestStation(
            id = "st_hybrid_free_ac",
            name = "VinFast - Rest Stop A",
            powers = listOf(
                createPort(120_000L, available = 3, total = 4),
                createPort(11_000L, available = 1, total = 2)
            )
        )

        // Hybrid station with 120kW DC (3/4 available) and 11kW AC (0/2 available) -> Excluded
        val hybridWithOccupiedAc = createTestStation(
            id = "st_hybrid_full_ac",
            name = "VinFast - Rest Stop B",
            powers = listOf(
                createPort(120_000L, available = 3, total = 4),
                createPort(11_000L, available = 0, total = 2)
            )
        )

        val stations = listOf(hybridWithFreeAc, hybridWithOccupiedAc)

        val resultNormal = NearbyStationFilter.filterSmartStations(
            stations = stations,
            mode = SmartFilterMode.AC,
            includeFullStations = false
        )
        assertEquals(listOf("st_hybrid_free_ac"), resultNormal.map { it.id })

        val resultIncludeFull = NearbyStationFilter.filterSmartStations(
            stations = stations,
            mode = SmartFilterMode.AC,
            includeFullStations = true
        )
        assertEquals(
            "Hybrid station with occupied AC must be excluded even if DC has free plugs and includeFullStations is true",
            listOf("st_hybrid_free_ac"),
            resultIncludeFull.map { it.id }
        )
    }

    @Test
    fun testSub11kwAndMotorbikePortsExcludedUnderAcMode() {
        val stMotorbike7kw = createTestStation(
            id = "st_7kw",
            powers = listOf(createPort(7_000L, available = 2, total = 2))
        )
        val stMotorbike3kw = createTestStation(
            id = "st_3kw",
            powers = listOf(createPort(3_500L, available = 2, total = 2))
        )
        val stDcOnly = createTestStation(
            id = "st_dc_only",
            powers = listOf(createPort(60_000L, available = 2, total = 2))
        )

        val result = NearbyStationFilter.filterSmartStations(
            stations = listOf(stMotorbike7kw, stMotorbike3kw, stDcOnly),
            mode = SmartFilterMode.AC
        )
        assertTrue("Sub-11kW and DC-only stations must be excluded in AC mode", result.isEmpty())
    }

    @Test
    fun testNonAcModesPreserveIncludeFullStationsBehavior() {
        val fullDcStation = createTestStation(
            id = "st_full_dc",
            powers = listOf(createPort(120_000L, available = 0, total = 2))
        )
        val fullGenericStation = createTestStation(
            id = "st_full_generic",
            powers = listOf(createPort(60_000L, available = 0, total = 2))
        )
        val stations = listOf(fullDcStation, fullGenericStation)

        // 1. SmartFilterMode.NONE
        assertFalse(
            "NONE mode excludes full stations when includeFullStations = false",
            NearbyStationFilter.filterSmartStations(stations, SmartFilterMode.NONE, includeFullStations = false)
                .map { it.id }.contains("st_full_generic")
        )
        assertTrue(
            "NONE mode retains full stations when includeFullStations = true",
            NearbyStationFilter.filterSmartStations(stations, SmartFilterMode.NONE, includeFullStations = true)
                .map { it.id }.contains("st_full_generic")
        )

        // 2. SmartFilterMode.DC with tier
        val dcExcludeFull = NearbyStationFilter.filterSmartStations(
            stations = listOf(fullDcStation),
            mode = SmartFilterMode.DC,
            dcTier = DcWattageTier.GE_120KW,
            includeFullStations = false
        )
        assertTrue("DC mode excludes full stations when includeFullStations = false", dcExcludeFull.isEmpty())

        val dcIncludeFull = NearbyStationFilter.filterSmartStations(
            stations = listOf(fullDcStation),
            mode = SmartFilterMode.DC,
            dcTier = DcWattageTier.GE_120KW,
            includeFullStations = true
        )
        assertEquals(
            "DC mode includes full stations when includeFullStations = true",
            listOf("st_full_dc"),
            dcIncludeFull.map { it.id }
        )

        // 3. CustomFilterMode.QUICK_CHIP for DC_GE_120KW
        val qcDcConfig = CustomFilterConfig(
            mode = CustomFilterMode.QUICK_CHIP,
            quickChip = QuickChipOption.DC_GE_120KW
        )
        val qcExcludeFull = NearbyStationFilter.filterSmartStations(
            stations = listOf(fullDcStation),
            mode = SmartFilterMode.CUSTOM,
            customConfig = qcDcConfig,
            includeFullStations = false
        )
        assertTrue(qcExcludeFull.isEmpty())

        val qcIncludeFull = NearbyStationFilter.filterSmartStations(
            stations = listOf(fullDcStation),
            mode = SmartFilterMode.CUSTOM,
            customConfig = qcDcConfig,
            includeFullStations = true
        )
        assertEquals(listOf("st_full_dc"), qcIncludeFull.map { it.id })

        // 4. CustomFilterMode.CUSTOM_RANGE
        val rangeConfig = CustomFilterConfig(
            mode = CustomFilterMode.CUSTOM_RANGE,
            minKw = 50,
            maxKw = 150
        )
        val rangeExcludeFull = NearbyStationFilter.filterSmartStations(
            stations = listOf(fullDcStation),
            mode = SmartFilterMode.CUSTOM,
            customConfig = rangeConfig,
            includeFullStations = false
        )
        assertTrue(rangeExcludeFull.isEmpty())

        val rangeIncludeFull = NearbyStationFilter.filterSmartStations(
            stations = listOf(fullDcStation),
            mode = SmartFilterMode.CUSTOM,
            customConfig = rangeConfig,
            includeFullStations = true
        )
        assertEquals(listOf("st_full_dc"), rangeIncludeFull.map { it.id })
    }

    @Test
    fun testOutOfServiceAndNonVinFastExcludedEvenIfAcAvailable() {
        val stMaintaining = createTestStation(
            id = "st_maint",
            depotStatus = "Maintaining",
            powers = listOf(createPort(11_000L, available = 2, total = 2))
        )
        val stOutOfService = createTestStation(
            id = "st_oos",
            depotStatus = "OutOfService",
            powers = listOf(createPort(22_000L, available = 2, total = 2))
        )
        val stNonVinfast = createTestStation(
            id = "st_other_evse",
            evse = "EVN",
            powers = listOf(createPort(11_000L, available = 2, total = 2))
        )

        val result = NearbyStationFilter.filterSmartStations(
            stations = listOf(stMaintaining, stOutOfService, stNonVinfast),
            mode = SmartFilterMode.AC
        )
        assertTrue("Maintaining, OutOfService, and non-VinFast stations must be excluded", result.isEmpty())
    }
}
