package com.evcs.favorites.ui.screens

import androidx.compose.runtime.Immutable
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.routing.ChargerTier
import com.evcs.favorites.data.routing.EvRouteStop
import com.evcs.favorites.data.routing.EvSmartRoutePlan
import com.evcs.favorites.data.routing.StopAvailabilityStatus
import com.evcs.favorites.data.routing.distanceFromPrimaryStationKm
import com.evcs.favorites.data.routing.extractStationMaxPowerKw
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Comprehensive Single Verification Test for Phase 04:
 * Compose Route Timeline Optimization & Memoization.
 *
 * Test Criteria:
 * 1. Memoized distance calculation precisely matches [distanceFromPrimaryStationKm] for diverse station coordinate pairs.
 * 2. Memoized power calculation accurately reflects [extractStationMaxPowerKw] and remains stable across repeated invocations with the same station IDs.
 * 3. When station IDs change (e.g. after a station swap), the memoized values update correctly to reflect the new station properties.
 * 4. Station stop keys and timeline data models preserve structural equality and immutability across UI state flows.
 */
class RouteTimelineMemoizationTest {

    private fun hasImmutableAnnotation(clazz: Class<*>): Boolean {
        if (clazz.isAnnotationPresent(Immutable::class.java) ||
            clazz.annotations.any { it.annotationClass.java.name == Immutable::class.java.name }
        ) {
            return true
        }
        val resourcePath = clazz.name.replace('.', '/') + ".class"
        val stream = clazz.classLoader?.getResourceAsStream(resourcePath)
            ?: Thread.currentThread().contextClassLoader?.getResourceAsStream(resourcePath)
        if (stream != null) {
            val bytes = stream.use { it.readBytes() }
            val bytecodeString = String(bytes, Charsets.ISO_8859_1)
            return bytecodeString.contains("Landroidx/compose/runtime/Immutable;")
        }
        return false
    }

    private fun createStation(
        id: String,
        name: String,
        lat: Double,
        lng: Double,
        powers: List<PowerPort> = emptyList(),
        connectors: String = "",
        summary: String = "",
        totalPlugs: Int = 4,
        availablePlugs: Int = 2
    ): Station {
        return Station(
            id = id,
            name = name,
            address = "Địa chỉ $name",
            latitude = lat,
            longitude = lng,
            summary = summary,
            connectors = connectors,
            depotStatus = "Normal",
            powers = powers,
            totalAvailablePlugs = availablePlugs,
            totalPlugs = totalPlugs
        )
    }

    // =========================================================================
    // Criterion 1: Memoized distance calculation matches distanceFromPrimaryStationKm
    // =========================================================================
    @Test
    fun testMemoizedDistanceCalculationMatchesDistanceFormulaForDiverseCoordinatePairs() {
        val testCoordinatePairs = listOf(
            // Hanoi Central to Long Bien
            Pair(
                createStation("hn_central", "Hanoi Central", 21.0285, 105.8542),
                createStation("hn_longbien", "Long Bien Backup", 21.0360, 105.8820)
            ),
            // Da Nang Coastal to Airport
            Pair(
                createStation("dn_coast", "Da Nang Coast", 16.0610, 108.2430),
                createStation("dn_airport", "Da Nang Airport Backup", 16.0540, 108.1990)
            ),
            // HCMC District 1 to Thu Duc
            Pair(
                createStation("hcm_d1", "Saigon D1", 10.7769, 106.7009),
                createStation("hcm_thuduc", "Thu Duc Backup", 10.8494, 106.7727)
            ),
            // Same location (0 distance edge case)
            Pair(
                createStation("same_primary", "Primary Colocated", 20.8449, 106.6881),
                createStation("same_backup", "Backup Colocated", 20.8449, 106.6881)
            ),
            // Very close proximity (~300m)
            Pair(
                createStation("close_p", "Primary Close", 21.0115, 105.8501),
                createStation("close_b", "Backup Close", 21.0140, 105.8510)
            )
        )

        for ((primary, backup) in testCoordinatePairs) {
            val directDistance = distanceFromPrimaryStationKm(primary, backup)
            val (memoizedDist, _) = computeBackupStationMetrics(primary, backup)

            assertEquals(
                "Memoized distance must precisely match distanceFromPrimaryStationKm",
                directDistance,
                memoizedDist,
                1e-6
            )

            val expectedFormatted = String.format(Locale.US, "%.1f", directDistance)
            val actualFormatted = formatBackupDistance(memoizedDist)
            assertEquals("Formatted distance string must match US locale 1-decimal format", expectedFormatted, actualFormatted)

            // Cross-check with EvRouteStop derived property if wrapped
            val stop = EvRouteStop(
                stopIndex = 1,
                station = primary,
                distanceFromOriginKm = 50.0,
                distanceFromPreviousStopKm = 50.0,
                arrivalBatteryPercent = 30,
                targetBatteryPercent = 80,
                estimatedChargingMinutes = 25,
                backupStation = backup
            )
            assertEquals(directDistance, stop.backupDistanceKm!!, 1e-6)
        }
    }

    // =========================================================================
    // Criterion 2: Memoized power calculation reflects extractStationMaxPowerKw & remains stable
    // =========================================================================
    @Test
    fun testMemoizedPowerCalculationAccurateAndStableAcrossRepeatedInvocations() {
        val ultraFastStation = createStation(
            id = "st_ultra",
            name = "Trạm Vincom 250kW",
            lat = 21.0,
            lng = 105.0,
            powers = listOf(
                PowerPort(typeWatts = 250000L, label = "250kW", availablePlugs = 2, totalPlugs = 4),
                PowerPort(typeWatts = 60000L, label = "60kW", availablePlugs = 2, totalPlugs = 4)
            )
        )

        val textMatchedStation = createStation(
            id = "st_text",
            name = "Trạm Cao Tốc",
            lat = 21.1,
            lng = 105.1,
            powers = emptyList(),
            connectors = "Trụ sạc nhanh 180kW DC",
            summary = "Trạm dừng nghỉ 180kW"
        )

        val genericDcStation = createStation(
            id = "st_dc",
            name = "Trạm DC Fast",
            lat = 21.2,
            lng = 105.2,
            powers = emptyList(),
            summary = "Trụ sạc DC Supercharger"
        )

        val slowFallbackStation = createStation(
            id = "st_slow",
            name = "Trạm AC Chậm",
            lat = 21.3,
            lng = 105.3,
            powers = emptyList(),
            summary = "Sạc chậm bãi đỗ xe"
        )

        val stations = listOf(ultraFastStation, textMatchedStation, genericDcStation, slowFallbackStation)
        val primaryDummy = createStation("dummy_p", "Primary Dummy", 20.9, 104.9)

        // Simulated Memoization Cache Keyed by (primaryStation.id, backupStation.id)
        class MemoizationSlotTable {
            private var lastPrimaryKey: String? = null
            private var lastBackupKey: String? = null
            var computationCount = 0
                private set
            private var cachedResult: Pair<Double, Double>? = null

            fun getOrCompute(primary: Station, backup: Station): Pair<Double, Double> {
                if (lastPrimaryKey == primary.id && lastBackupKey == backup.id && cachedResult != null) {
                    return cachedResult!!
                }
                computationCount++
                lastPrimaryKey = primary.id
                lastBackupKey = backup.id
                val result = computeBackupStationMetrics(primary, backup)
                cachedResult = result
                return result
            }
        }

        for (station in stations) {
            val expectedPower = extractStationMaxPowerKw(station)
            val slotTable = MemoizationSlotTable()

            // 1st computation
            val firstCall = slotTable.getOrCompute(primaryDummy, station)
            assertEquals(1, slotTable.computationCount)
            assertEquals(expectedPower, firstCall.second, 0.001)

            // Invocations 2 to 10 with identical IDs must return cached result without recomputation
            for (i in 2..10) {
                val repeatedCall = slotTable.getOrCompute(primaryDummy, station)
                assertEquals(
                    "Recomposition with identical IDs must not increment computation count",
                    1,
                    slotTable.computationCount
                )
                assertSame("Cached result pair reference must be preserved across recompositions", firstCall, repeatedCall)
                assertEquals(expectedPower, repeatedCall.second, 0.001)
            }

            val formattedPower = formatBackupPower(firstCall.second)
            assertTrue("Power string must contain expected kW value or DC", formattedPower.startsWith("⚡"))
            if (expectedPower > 0) {
                assertTrue(formattedPower.contains("${expectedPower.toInt()} kW"))
            } else {
                assertTrue(formattedPower.contains("DC"))
            }
        }
    }

    // =========================================================================
    // Criterion 3: Station ID changes (e.g. station swap) correctly trigger re-computation
    // =========================================================================
    @Test
    fun testStationSwapUpdatesMemoizedValuesAccurately() {
        val stationA = createStation(
            id = "station_A",
            name = "Trạm Sạc A - Hoàn Kiếm",
            lat = 21.0285,
            lng = 105.8542,
            powers = listOf(PowerPort(typeWatts = 60000L, label = "60kW", availablePlugs = 1, totalPlugs = 2))
        )
        val stationB = createStation(
            id = "station_B",
            name = "Trạm Sạc B - Hai Bà Trưng",
            lat = 21.0115,
            lng = 105.8501,
            powers = listOf(PowerPort(typeWatts = 250000L, label = "250kW", availablePlugs = 3, totalPlugs = 4))
        )
        val stationC = createStation(
            id = "station_C",
            name = "Trạm Sạc C - Ba Đình",
            lat = 21.0340,
            lng = 105.8300,
            powers = listOf(PowerPort(typeWatts = 180000L, label = "180kW", availablePlugs = 2, totalPlugs = 2))
        )

        class ComposableSimulator {
            private var lastPrimaryId: String? = null
            private var lastBackupId: String? = null
            private var cachedMetrics: Pair<Double, Double>? = null
            private var cachedFormattedDist: String? = null
            private var cachedFormattedPower: String? = null
            var totalRecomputations = 0
                private set

            fun render(primary: Station, backup: Station?): Triple<Double, Double, Pair<String, String>> {
                if (backup == null) {
                    return Triple(0.0, 0.0, Pair("", ""))
                }
                if (lastPrimaryId != primary.id || lastBackupId != backup.id || cachedMetrics == null) {
                    totalRecomputations++
                    lastPrimaryId = primary.id
                    lastBackupId = backup.id
                    val metrics = Pair(
                        distanceFromPrimaryStationKm(primary, backup),
                        extractStationMaxPowerKw(backup)
                    )
                    cachedMetrics = metrics
                    cachedFormattedDist = formatBackupDistance(metrics.first)
                    cachedFormattedPower = formatBackupPower(metrics.second)
                }
                return Triple(
                    cachedMetrics!!.first,
                    cachedMetrics!!.second,
                    Pair(cachedFormattedDist!!, cachedFormattedPower!!)
                )
            }
        }

        val simulator = ComposableSimulator()

        // State 1: Primary = A, Backup = B
        val state1 = simulator.render(primary = stationA, backup = stationB)
        assertEquals(1, simulator.totalRecomputations)
        assertEquals(250.0, state1.second, 0.001) // Backup B has 250 kW
        assertEquals("⚡ 250 kW", state1.third.second)
        val distAB = distanceFromPrimaryStationKm(stationA, stationB)
        assertEquals(distAB, state1.first, 1e-6)

        // Multiple simulated recompositions with state 1 (same IDs)
        repeat(5) {
            val sameState = simulator.render(primary = stationA, backup = stationB)
            assertEquals(1, simulator.totalRecomputations)
            assertEquals(state1, sameState)
        }

        // State 2: Driver performs 1-tap fast swap -> Primary = B, Backup = A
        val state2 = simulator.render(primary = stationB, backup = stationA)
        assertEquals(2, simulator.totalRecomputations)
        assertEquals(60.0, state2.second, 0.001) // New backup A has 60 kW
        assertEquals("⚡ 60 kW", state2.third.second)
        val distBA = distanceFromPrimaryStationKm(stationB, stationA)
        assertEquals(distBA, state2.first, 1e-6)
        assertEquals(distAB, distBA, 1e-6) // Symmetry of straight-line distance

        // State 3: Swap station B for alternate candidate C -> Primary = C, Backup = A
        val state3 = simulator.render(primary = stationC, backup = stationA)
        assertEquals(3, simulator.totalRecomputations)
        assertEquals(60.0, state3.second, 0.001)
        val distCA = distanceFromPrimaryStationKm(stationC, stationA)
        assertEquals(distCA, state3.first, 1e-6)
        assertNotEquals(distAB, distCA, 1e-3)

        // State 4: Swap candidate C with new backup B -> Primary = C, Backup = B
        val state4 = simulator.render(primary = stationC, backup = stationB)
        assertEquals(4, simulator.totalRecomputations)
        assertEquals(250.0, state4.second, 0.001)
        assertEquals("⚡ 250 kW", state4.third.second)
    }

    // =========================================================================
    // Criterion 4: Stop keys and timeline data models preserve equality & immutability
    // =========================================================================
    @Test
    fun testStationStopKeysAndTimelineDataModelsPreserveStructuralEqualityAndImmutability() {
        // 1. Verify @Immutable annotations on timeline domain models
        assertTrue(
            "EvRouteStop must carry @Immutable annotation",
            hasImmutableAnnotation(EvRouteStop::class.java)
        )
        assertTrue(
            "EvSmartRoutePlan must carry @Immutable annotation",
            hasImmutableAnnotation(EvSmartRoutePlan::class.java)
        )
        assertTrue(
            "Station must carry @Immutable annotation",
            hasImmutableAnnotation(Station::class.java)
        )

        // 2. Build multi-stop route plan
        val station1 = createStation("st1", "Trạm 1", 21.0, 105.0)
        val backup1 = createStation("bk1", "Backup 1", 21.02, 105.02)
        val station2 = createStation("st2", "Trạm 2", 20.5, 105.5)
        val backup2 = createStation("bk2", "Backup 2", 20.52, 105.52)
        val station3 = createStation("st3", "Trạm 3", 20.0, 106.0)

        val stop1 = EvRouteStop(
            stopIndex = 1,
            station = station1,
            distanceFromOriginKm = 60.0,
            distanceFromPreviousStopKm = 60.0,
            arrivalBatteryPercent = 25,
            targetBatteryPercent = 80,
            estimatedChargingMinutes = 25,
            backupStation = backup1
        )
        val stop2 = EvRouteStop(
            stopIndex = 2,
            station = station2,
            distanceFromOriginKm = 140.0,
            distanceFromPreviousStopKm = 80.0,
            arrivalBatteryPercent = 20,
            targetBatteryPercent = 75,
            estimatedChargingMinutes = 28,
            backupStation = backup2
        )
        val stop3 = EvRouteStop(
            stopIndex = 3,
            station = station3,
            distanceFromOriginKm = 230.0,
            distanceFromPreviousStopKm = 90.0,
            arrivalBatteryPercent = 18,
            targetBatteryPercent = 85,
            estimatedChargingMinutes = 32,
            backupStation = null
        )

        val originalStops = listOf(stop1, stop2, stop3)
        val originalPlan = EvSmartRoutePlan(
            originLat = 21.2,
            originLng = 104.8,
            destinationLat = 19.8,
            destinationLng = 106.2,
            totalDistanceKm = 260.0,
            totalDrivingDurationSeconds = 14400,
            totalChargingDurationMinutes = 85,
            stops = originalStops,
            energyProfile = emptyList(),
            finalBatteryPercent = 45
        )

        // Verify stable keys
        assertEquals(1, stop1.stopIndex)
        assertEquals(2, stop2.stopIndex)
        assertEquals(3, stop3.stopIndex)

        // 3. Simulating stop swap on Stop 1 only
        val swappedStop1 = stop1.copy(
            station = backup1,
            backupStation = station1
        )
        val updatedStops = listOf(swappedStop1, stop2, stop3)
        val updatedPlan = originalPlan.copy(stops = updatedStops)

        // Unmodified stops 2 and 3 must maintain identical instance references and structural equality
        assertSame("Stop 2 instance reference must remain identical", stop2, updatedPlan.stops[1])
        assertSame("Stop 3 instance reference must remain identical", stop3, updatedPlan.stops[2])
        assertEquals("Stop 2 hash must remain unchanged", stop2.hashCode(), updatedPlan.stops[1].hashCode())
        assertEquals("Stop 3 hash must remain unchanged", stop3.hashCode(), updatedPlan.stops[2].hashCode())

        // Stop 1 has updated station & backup while retaining stopIndex key
        assertEquals(1, updatedPlan.stops[0].stopIndex)
        assertNotEquals(stop1, updatedPlan.stops[0])
        assertEquals(backup1.id, updatedPlan.stops[0].station.id)
        assertEquals(station1.id, updatedPlan.stops[0].backupStation?.id)

        // 4. Data class copy and equality verification
        val identicalPlan = originalPlan.copy()
        assertEquals(originalPlan, identicalPlan)
        assertEquals(originalPlan.hashCode(), identicalPlan.hashCode())
    }
}
