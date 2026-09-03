package com.evcs.favorites

import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.domain.location.DistanceCalculator
import com.evcs.favorites.domain.location.formattedDistance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprehensive verification test for Phase 03: Location & Distance Service.
 *
 * Validates:
 * 1. Haversine distance calculation accuracy across known Vietnam GPS coordinates.
 * 2. Distance formatting thresholds ("450 m", "1.2 km", "15.0 km", boundary conditions).
 * 3. Charging stations sorting logic ensuring nearest-first ordering and coordinate handling.
 * 4. Concurrent thread-safety of mathematical calculations.
 */
class DistanceCalculatorTest {

    // Known Vietnam GPS Reference Coordinates
    companion object {
        // Hanoi (Hoan Kiem Lake)
        const val HANOI_LAT = 21.0285
        const val HANOI_LON = 105.8542

        // Hanoi Opera House (~560m from Hoan Kiem)
        const val HANOI_OPERA_LAT = 21.0245
        const val HANOI_OPERA_LON = 105.8575

        // Hanoi Keangnam Landmark 72 (~7.2 km from Hoan Kiem)
        const val HANOI_KEANGNAM_LAT = 21.0168
        const val HANOI_KEANGNAM_LON = 105.7839

        // Noi Bai International Airport (~21.5 km from Hoan Kiem)
        const val NOI_BAI_LAT = 21.2187
        const val NOI_BAI_LON = 105.8042

        // Da Nang Center (Han River / Dragon Bridge)
        const val DANANG_LAT = 16.0678
        const val DANANG_LON = 108.2208

        // Ho Chi Minh City (Ben Thanh Market)
        const val HCMC_LAT = 10.7725
        const val HCMC_LON = 106.6980
    }

    private fun createDummyStation(
        id: String,
        name: String,
        lat: Double,
        lon: Double
    ): Station {
        return Station(
            id = id,
            name = name,
            address = "Địa chỉ $name",
            latitude = lat,
            longitude = lon,
            summary = "24/7",
            connectors = "60kW, 120kW",
            depotStatus = "Normal",
            powers = listOf(
                PowerPort(
                    typeWatts = 60000L,
                    label = "60kW",
                    availablePlugs = 2,
                    totalPlugs = 2,
                    displayString = "60kW: trống 2/2"
                )
            ),
            totalAvailablePlugs = 2,
            totalPlugs = 2
        )
    }

    // =========================================================================
    // 1. Haversine Distance Calculation Accuracy
    // =========================================================================

    @Test
    fun testHaversine_sameCoordinates_returnsZero() {
        val distKm = DistanceCalculator.calculateDistanceKm(HANOI_LAT, HANOI_LON, HANOI_LAT, HANOI_LON)
        val distMeters = DistanceCalculator.calculateDistanceMeters(HANOI_LAT, HANOI_LON, HANOI_LAT, HANOI_LON)

        assertEquals(0.0, distKm, 0.0001)
        assertEquals(0.0, distMeters, 0.0001)
    }

    @Test
    fun testHaversine_isSymmetric() {
        val distAtoB = DistanceCalculator.calculateDistanceKm(HANOI_LAT, HANOI_LON, DANANG_LAT, DANANG_LON)
        val distBtoA = DistanceCalculator.calculateDistanceKm(DANANG_LAT, DANANG_LON, HANOI_LAT, HANOI_LON)

        assertEquals(distAtoB, distBtoA, 0.0001)
    }

    @Test
    fun testHaversine_vietnamCoordinatesAccuracy() {
        // 1. Short city distance: Hoan Kiem to Hanoi Opera House (~560m)
        val localDistanceKm = DistanceCalculator.calculateDistanceKm(
            HANOI_LAT, HANOI_LON,
            HANOI_OPERA_LAT, HANOI_OPERA_LON
        )
        // Verify between 540m and 580m (0.54 - 0.58 km)
        assertTrue("Local distance $localDistanceKm km should be ~0.56 km", localDistanceKm in 0.54..0.58)

        val localMeters = DistanceCalculator.calculateDistanceMeters(
            HANOI_LAT, HANOI_LON,
            HANOI_OPERA_LAT, HANOI_OPERA_LON
        )
        assertEquals(localDistanceKm * 1000.0, localMeters, 0.001)

        // 2. Medium distance: Hanoi to Da Nang (~605 km straight-line geodetic distance)
        val hanoiToDanangKm = DistanceCalculator.calculateDistanceKm(
            HANOI_LAT, HANOI_LON,
            DANANG_LAT, DANANG_LON
        )
        assertTrue("Hanoi to Da Nang should be ~605 km (actual: $hanoiToDanangKm)", hanoiToDanangKm in 600.0..610.0)

        // 3. Long distance: Hanoi to HCMC (~1144 km straight-line geodetic distance)
        val hanoiToHcmcKm = DistanceCalculator.calculateDistanceKm(
            HANOI_LAT, HANOI_LON,
            HCMC_LAT, HCMC_LON
        )
        assertTrue("Hanoi to HCMC should be ~1144 km (actual: $hanoiToHcmcKm)", hanoiToHcmcKm in 1130.0..1155.0)

        // 4. Central to South: Da Nang to HCMC (~611 km)
        val danangToHcmcKm = DistanceCalculator.calculateDistanceKm(
            DANANG_LAT, DANANG_LON,
            HCMC_LAT, HCMC_LON
        )
        assertTrue("Da Nang to HCMC should be ~611 km (actual: $danangToHcmcKm)", danangToHcmcKm in 605.0..620.0)
    }

    // =========================================================================
    // 2. Human-Readable Distance Formatting Thresholds
    // =========================================================================

    @Test
    fun testFormatting_requiredThresholds() {
        // Requirements specify: "450 m", "1.2 km", "15.0 km"
        assertEquals("450 m", DistanceCalculator.formatDistance(0.450))
        assertEquals("1.2 km", DistanceCalculator.formatDistance(1.2))
        assertEquals("15.0 km", DistanceCalculator.formatDistance(15.0))
    }

    @Test
    fun testFormatting_subKilometerMeters() {
        assertEquals("0 m", DistanceCalculator.formatDistance(0.0))
        assertEquals("0 m", DistanceCalculator.formatDistance(-1.0))
        assertEquals("50 m", DistanceCalculator.formatDistance(0.050))
        assertEquals("850 m", DistanceCalculator.formatDistance(0.850))
        assertEquals("999 m", DistanceCalculator.formatDistance(0.999))
    }

    @Test
    fun testFormatting_kilometerThresholds() {
        assertEquals("1.0 km", DistanceCalculator.formatDistance(1.0))
        assertEquals("1.0 km", DistanceCalculator.formatDistance(0.9999)) // roundToInt >= 1000 threshold
        assertEquals("3.4 km", DistanceCalculator.formatDistance(3.42))
        assertEquals("1138.5 km", DistanceCalculator.formatDistance(1138.48))
    }

    @Test
    fun testFormatting_nullOrNaN_returnsEmpty() {
        assertEquals("", DistanceCalculator.formatDistance(null))
        assertEquals("", DistanceCalculator.formatDistance(Double.NaN))
    }

    // =========================================================================
    // 3. Station Distance Attachment and Nearest-First Sorting
    // =========================================================================

    @Test
    fun testAttachDistance_singleStation() {
        val station = createDummyStation("st-1", "Hà Nội Opera", HANOI_OPERA_LAT, HANOI_OPERA_LON)
        val enriched = DistanceCalculator.attachDistance(station, HANOI_LAT, HANOI_LON)

        assertNotNull(enriched.distanceKm)
        assertTrue(enriched.distanceKm!! > 0.5 && enriched.distanceKm!! < 0.6)
        assertEquals("561 m", enriched.formattedDistance)
    }

    @Test
    fun testAttachDistance_unknownCoordinates_leavesDistanceNull() {
        val station = createDummyStation("st-unknown", "Trạm Không Tọa Độ", 0.0, 0.0)
        val enriched = DistanceCalculator.attachDistance(station, HANOI_LAT, HANOI_LON)

        assertNull(enriched.distanceKm)
        assertEquals("", enriched.formattedDistance)
    }

    @Test
    fun testSortByDistance_nearestFirstOrdering() {
        val userLat = HANOI_LAT
        val userLon = HANOI_LON

        // 6 stations at increasing distances from Hanoi Center:
        val st1 = createDummyStation("1", "Opera House (~560m)", HANOI_OPERA_LAT, HANOI_OPERA_LON)
        val st2 = createDummyStation("2", "Keangnam 72 (~7.2km)", HANOI_KEANGNAM_LAT, HANOI_KEANGNAM_LON)
        val st3 = createDummyStation("3", "Nội Bài Airport (~21.5km)", NOI_BAI_LAT, NOI_BAI_LON)
        val st4 = createDummyStation("4", "Đà Nẵng Station (~627km)", DANANG_LAT, DANANG_LON)
        val st5 = createDummyStation("5", "TP.HCM Station (~1138km)", HCMC_LAT, HCMC_LON)
        val stNoCoords = createDummyStation("6", "Trạm Lỗi Tọa Độ", 0.0, 0.0)

        // Pass stations in scrambled order
        val unorderedList = listOf(st4, st2, stNoCoords, st5, st1, st3)

        val sortedList = DistanceCalculator.sortByDistance(unorderedList, userLat, userLon)

        assertEquals(6, sortedList.size)
        // Verify nearest-first sequence
        assertEquals("1", sortedList[0].id) // ~0.56 km
        assertEquals("2", sortedList[1].id) // ~7.2 km
        assertEquals("3", sortedList[2].id) // ~21.5 km
        assertEquals("4", sortedList[3].id) // ~627 km
        assertEquals("5", sortedList[4].id) // ~1138 km
        assertEquals("6", sortedList[5].id) // Missing coords placed last

        // Verify distance metrics are attached
        assertTrue(sortedList[0].distanceKm!! < sortedList[1].distanceKm!!)
        assertTrue(sortedList[1].distanceKm!! < sortedList[2].distanceKm!!)
        assertTrue(sortedList[2].distanceKm!! < sortedList[3].distanceKm!!)
        assertTrue(sortedList[3].distanceKm!! < sortedList[4].distanceKm!!)
        assertNull(sortedList[5].distanceKm)

        // Verify formatted strings
        assertEquals("561 m", sortedList[0].formattedDistance)
        assertEquals("7.4 km", sortedList[1].formattedDistance)
        assertEquals("21.8 km", sortedList[2].formattedDistance)
        assertTrue(sortedList[3].formattedDistance.endsWith("km"))
        assertTrue(sortedList[4].formattedDistance.endsWith("km"))
        assertEquals("", sortedList[5].formattedDistance)
    }

    @Test
    fun testSortByDistance_nullUserCoordinates_preservesOrderGracefully() {
        val st1 = createDummyStation("1", "Trạm 1", HANOI_OPERA_LAT, HANOI_OPERA_LON)
        val st2 = createDummyStation("2", "Trạm 2", DANANG_LAT, DANANG_LON)
        val original = listOf(st1, st2)

        val resultNullLat = DistanceCalculator.sortByDistance(original, null, HANOI_LON)
        val resultNullLon = DistanceCalculator.sortByDistance(original, HANOI_LAT, null)
        val resultBothNull = DistanceCalculator.sortByDistance(original, null, null)

        assertEquals(listOf("1", "2"), resultNullLat.map { it.id })
        assertEquals(listOf("1", "2"), resultNullLon.map { it.id })
        assertEquals(listOf("1", "2"), resultBothNull.map { it.id })
    }

    // =========================================================================
    // 4. Thread-Safety & Concurrent Computation
    // =========================================================================

    @Test
    fun testConcurrency_threadSafeCalculations(): Unit = runBlocking {
        val jobs = (1..50).map { i ->
            async(Dispatchers.Default) {
                val dist = DistanceCalculator.calculateDistanceKm(
                    HANOI_LAT + (i * 0.001),
                    HANOI_LON + (i * 0.001),
                    DANANG_LAT,
                    DANANG_LON
                )
                val formatted = DistanceCalculator.formatDistance(dist)
                assertTrue(dist > 0.0)
                assertTrue(formatted.contains("km"))
            }
        }
        jobs.awaitAll()
    }
}
