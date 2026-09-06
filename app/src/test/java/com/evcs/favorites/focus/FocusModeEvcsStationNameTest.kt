package com.evcs.favorites.focus

import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.SearchStationRaw
import com.evcs.favorites.data.model.Station
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Single Comprehensive Verification Test for Phase 02:
 * evcs.vn Station Name Resolution & Preservation.
 *
 * Requirements covered:
 * 1. Name preservation during telemetry polling: Authentic evcs.vn station name ("VinFast Landmark 81")
 *    is preserved in FocusModeTelemetryEngine.pollOnce() and not overwritten by Here API's generic "Trạm sạc VinFast".
 * 2. EvcsStationNameResolver matching by location ID (e.g. "c.bni0012" -> "VinFast TTTM Dabaco Mart Quế Võ")
 *    and by coordinate proximity (<= 100 meters).
 * 3. In-memory caching: Repeated lookups use memory cache with 0 redundant network calls.
 * 4. Alternative station candidates for auto-reroute are enriched with standard evcs.vn names so
 *    the recommendation displays "Đổi trạm: [Authentic Name] (+...)" instead of generic name.
 * 5. Floating window presentation state (FocusModeViewLayoutHelper.formatViewState) renders authentic,
 *    sanitized station names across Normal, Full, and Offline states.
 * 6. Resilience and graceful degradation when evcs.vn is unavailable or returns an error.
 * 7. Correct classification of generic vs non-generic station names.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FocusModeEvcsStationNameTest {

    private fun createDcPower(watts: Long, avail: Int, total: Int): PowerPort {
        val kw = watts / 1000L
        return PowerPort(
            typeWatts = watts,
            label = "${kw}kW",
            availablePlugs = avail,
            totalPlugs = total,
            displayString = "${kw}kW: trống $avail/$total cổng"
        )
    }

    private fun createStation(
        id: String,
        name: String,
        lat: Double,
        lon: Double,
        powers: List<PowerPort>,
        depotStatus: String = "Normal"
    ): Station {
        return Station(
            id = id,
            name = name,
            address = "Địa chỉ trạm $name",
            latitude = lat,
            longitude = lon,
            summary = "Trụ sạc $name",
            connectors = powers.joinToString(", ") { it.label },
            depotStatus = depotStatus,
            powers = powers,
            totalAvailablePlugs = powers.sumOf { it.availablePlugs },
            totalPlugs = powers.sumOf { it.totalPlugs }
        )
    }

    @Test
    fun testIsGenericStationNameClassification() {
        // Generic names that should be resolved/enriched
        assertTrue(EvcsStationNameResolver.isGenericStationName(null))
        assertTrue(EvcsStationNameResolver.isGenericStationName(""))
        assertTrue(EvcsStationNameResolver.isGenericStationName("   "))
        assertTrue(EvcsStationNameResolver.isGenericStationName("Trạm sạc VinFast"))
        assertTrue(EvcsStationNameResolver.isGenericStationName("Tram sac VinFast"))
        assertTrue(EvcsStationNameResolver.isGenericStationName("VinFast"))
        assertTrue(EvcsStationNameResolver.isGenericStationName("vinfast"))
        assertTrue(EvcsStationNameResolver.isGenericStationName("Trạm sạc"))
        assertTrue(EvcsStationNameResolver.isGenericStationName("Trụ sạc"))
        assertTrue(EvcsStationNameResolver.isGenericStationName("Trạm sạc xe điện"))

        // Authentic names with specific locations that should be preserved
        assertFalse(EvcsStationNameResolver.isGenericStationName("VinFast Landmark 81"))
        assertFalse(EvcsStationNameResolver.isGenericStationName("Trạm sạc VinFast Landmark 81"))
        assertFalse(EvcsStationNameResolver.isGenericStationName("VinFast TTTM Dabaco Mart Quế Võ"))
        assertFalse(EvcsStationNameResolver.isGenericStationName("Trạm sạc VinFast TTTM Dabaco Mart Quế Võ"))
        assertFalse(EvcsStationNameResolver.isGenericStationName("VinFast Imperia Smart City"))
        assertFalse(EvcsStationNameResolver.isGenericStationName("VinFast Vinhomes Ocean Park"))
    }

    @Test
    fun testNamePreservationDuringTelemetryPolling() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)

        // Initial station has authentic evcs.vn name
        val initialStation = createStation(
            id = "C.HCM0081",
            name = "VinFast Landmark 81",
            lat = 10.7950,
            lon = 106.7218,
            powers = listOf(createDcPower(60_000L, avail = 3, total = 4))
        )

        // Here EV API returns generic "Trạm sạc VinFast" with updated plug counts
        val hereApiResponse = createStation(
            id = "here-hcm-81",
            name = "Trạm sạc VinFast",
            lat = 10.7950,
            lon = 106.7218,
            powers = listOf(createDcPower(60_000L, avail = 1, total = 4))
        )

        val engine = FocusModeTelemetryEngine(
            initialStation = initialStation,
            fetchStationTelemetry = { _, _, _ -> Result.success(hereApiResponse) },
            defaultDispatcher = testDispatcher
        )

        // Verify initial state has authentic name
        assertEquals("VinFast Landmark 81", engine.state.value.targetStation.name)

        // Execute telemetry poll cycle
        val polledState = engine.pollOnce()

        // Authentic name must be preserved, NOT overwritten with Here API's "Trạm sạc VinFast"
        assertEquals("VinFast Landmark 81", polledState.targetStation.name)
        // Telemetry values are updated correctly
        assertEquals(1, polledState.availableDcSlots)
        assertEquals(4, polledState.totalDcSlots)
    }

    @Test
    fun testResolverMatchesByLocationIdWithDabacoQueVo() = runTest {
        // Test station based on user input:
        // https://evcs.vn/tram-sac-vinfast-tttm-dabaco-mart-que-vo-c.bni0012.html
        val dabacoLocationId = "c.bni0012"
        val expectedAuthenticName = "VinFast TTTM Dabaco Mart Quế Võ"

        var networkCalls = 0
        val resolver = EvcsStationNameResolver(
            searchStationsProvider = { _, _ ->
                networkCalls++
                Result.success(
                    listOf(
                        SearchStationRaw(
                            locationId = dabacoLocationId,
                            stationName = "Trạm sạc VinFast TTTM Dabaco Mart Quế Võ",
                            latitude = 21.1447,
                            longitude = 106.1554
                        ),
                        SearchStationRaw(
                            locationId = "c.bni0099",
                            stationName = "Trạm sạc VinFast Bắc Ninh Center",
                            latitude = 21.1800,
                            longitude = 106.0700
                        )
                    )
                )
            }
        )

        // Generic station with location ID matching evcs.vn
        val genericStation = createStation(
            id = "C.BNI0012", // Test case-insensitive match
            name = "Trạm sạc VinFast",
            lat = 21.1447,
            lon = 106.1554,
            powers = listOf(createDcPower(60_000L, avail = 2, total = 2))
        )

        val resolved = resolver.resolveStation(genericStation)

        assertEquals(expectedAuthenticName, resolved.name)
        assertEquals(1, networkCalls)

        // Subsequent call should be served from memory cache (0ms, 0 extra network calls)
        val secondResolved = resolver.resolveStation(genericStation)
        assertEquals(expectedAuthenticName, secondResolved.name)
        assertEquals(1, networkCalls)
    }

    @Test
    fun testResolverMatchesByCoordinateProximity() = runTest {
        val resolver = EvcsStationNameResolver(
            searchStationsProvider = { _, _ ->
                Result.success(
                    listOf(
                        SearchStationRaw(
                            locationId = "c.hno0045",
                            stationName = "Trạm sạc VinFast Imperia Smart City",
                            latitude = 21.0065,
                            longitude = 105.7420
                        )
                    )
                )
            }
        )

        // Candidate station from Here EV API has a generic Here ID but coordinates within 35 meters
        val hereCandidate = createStation(
            id = "here-ev-uuid-9988",
            name = "VinFast",
            lat = 21.0068, // ~35m difference (well within <= 100m)
            lon = 105.7422,
            powers = listOf(createDcPower(150_000L, avail = 2, total = 4))
        )

        val resolved = resolver.resolveStation(hereCandidate)

        assertEquals("VinFast Imperia Smart City", resolved.name)
    }

    @Test
    fun testAlternativeStationRecommendationEnrichedWithEvcsName() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)

        val targetStation = createStation(
            id = "target-saturated",
            name = "VinFast Landmark 81",
            lat = 10.7950,
            lon = 106.7218,
            powers = listOf(createDcPower(60_000L, avail = 0, total = 4)) // 0 slots available -> trigger reroute
        )

        val hereCandidates = listOf(
            createStation(
                id = "c.bni0012",
                name = "Trạm sạc VinFast", // Generic Here name
                lat = 10.7980,
                lon = 106.7250,
                powers = listOf(createDcPower(60_000L, avail = 2, total = 4))
            )
        )

        val resolver = EvcsStationNameResolver(
            searchStationsProvider = { _, _ ->
                Result.success(
                    listOf(
                        SearchStationRaw(
                            locationId = "c.bni0012",
                            stationName = "Trạm sạc VinFast TTTM Dabaco Mart Quế Võ",
                            latitude = 10.7980,
                            longitude = 106.7250
                        )
                    )
                )
            }
        )

        val engine = FocusModeTelemetryEngine(
            initialStation = targetStation,
            fetchStationTelemetry = { _, _, _ -> Result.success(targetStation) },
            fetchNearbyCandidates = { _, _ -> Result.success(hereCandidates) },
            defaultDispatcher = testDispatcher,
            stationNameResolver = resolver
        )

        val polledState = engine.pollOnce()

        // Alternative station recommendation must be present
        val recommendation = polledState.alternativeStation
        assertNotNull("Alternative station recommendation should not be null", recommendation)

        // Recommendation station name must be enriched with evcs.vn name, NOT generic "Trạm sạc VinFast"
        assertEquals("VinFast TTTM Dabaco Mart Quế Võ", recommendation!!.station.name)

        // displayRerouteLabel must show authentic evcs.vn name
        assertTrue(
            "Expected display label to contain 'VinFast TTTM Dabaco Mart Quế Võ', but got: ${recommendation.displayRerouteLabel}",
            recommendation.displayRerouteLabel.contains("VinFast TTTM Dabaco Mart Quế Võ")
        )
        assertFalse(
            "Display label should NOT contain generic 'Trạm sạc VinFast'",
            recommendation.displayRerouteLabel.contains("Trạm sạc VinFast")
        )
    }

    @Test
    fun testFloatingViewStateDisplaysAuthenticNameAcrossAllStates() {
        val authenticName = "VinFast TTTM Dabaco Mart Quế Võ"
        val sampleStation = createStation(
            id = "c.bni0012",
            name = authenticName,
            lat = 21.1447,
            lon = 106.1554,
            powers = listOf(createDcPower(60_000L, avail = 2, total = 4))
        )

        // 1. Normal Connected State
        val normalState = FocusModeState(
            targetStation = sampleStation,
            availableDcSlots = 2,
            totalDcSlots = 4,
            distanceRemainingKm = 2.5,
            connectionStatus = FocusConnectionStatus.CONNECTED
        )
        val normalView = FocusModeViewLayoutHelper.formatViewState(normalState)
        assertEquals(authenticName, normalView.stationName)
        assertEquals(FocusBadgeColor.GREEN, normalView.badgeColorToken)

        // 2. Full DC State with Alternative Station
        val altStation = createStation(
            id = "alt-01",
            name = "VinFast Landmark 81",
            lat = 21.1500,
            lon = 106.1600,
            powers = listOf(createDcPower(60_000L, avail = 3, total = 4))
        )
        val fullState = FocusModeState(
            targetStation = sampleStation,
            availableDcSlots = 0,
            totalDcSlots = 4,
            distanceRemainingKm = 1.8,
            connectionStatus = FocusConnectionStatus.CONNECTED,
            alternativeStation = AlternativeStationRecommendation(
                station = altStation,
                distanceKm = 1.2,
                matchingPowerWatts = 60_000L,
                availableDcSlots = 3,
                totalDcSlots = 4
            )
        )
        val fullView = FocusModeViewLayoutHelper.formatViewState(fullState)
        assertEquals(authenticName, fullView.stationName)
        assertEquals(FocusBadgeColor.RED, fullView.badgeColorToken)
        assertTrue(fullView.isRerouteAvailable)
        assertTrue(fullView.rerouteButtonText!!.contains("VinFast Landmark 81"))

        // 3. Offline State
        val offlineState = FocusModeState(
            targetStation = sampleStation,
            availableDcSlots = 2,
            totalDcSlots = 4,
            distanceRemainingKm = 2.5,
            connectionStatus = FocusConnectionStatus.OFFLINE,
            offlineMessage = "⚠️ Mất kết nối - Dữ liệu lúc 14:30"
        )
        val offlineView = FocusModeViewLayoutHelper.formatViewState(offlineState)
        assertEquals(authenticName, offlineView.stationName)
        assertEquals(FocusBadgeColor.AMBER, offlineView.badgeColorToken)
        assertTrue(offlineView.isOffline)
    }

    @Test
    fun testGracefulDegradationWhenEvcsUnavailable() = runTest {
        val resolver = EvcsStationNameResolver(
            searchStationsProvider = { _, _ ->
                Result.failure(IOException("evcs.vn connection timeout"))
            }
        )

        val station = createStation(
            id = "c.unknown",
            name = "Trạm sạc VinFast",
            lat = 21.1447,
            lon = 106.1554,
            powers = listOf(createDcPower(60_000L, avail = 1, total = 2))
        )

        // When network fails, resolver must degrade gracefully and retain existing name without crashing
        val resolved = resolver.resolveStation(station)
        assertNotNull(resolved)
        assertFalse(resolved.name.isBlank())
    }

    @Test
    fun testUpdateTargetStationPreservesAuthenticName() {
        val initial = createStation(
            id = "init-1",
            name = "VinFast Landmark 81",
            lat = 10.7950,
            lon = 106.7218,
            powers = listOf(createDcPower(60_000L, avail = 1, total = 2))
        )
        val engine = FocusModeTelemetryEngine(
            initialStation = initial,
            fetchStationTelemetry = { _, _, _ -> Result.success(initial) }
        )

        val newStation = createStation(
            id = "c.bni0012",
            name = "Trạm sạc VinFast TTTM Dabaco Mart Quế Võ",
            lat = 21.1447,
            lon = 106.1554,
            powers = listOf(createDcPower(60_000L, avail = 2, total = 4))
        )

        engine.updateTargetStation(newStation)

        assertEquals("VinFast TTTM Dabaco Mart Quế Võ", engine.state.value.targetStation.name)
        assertEquals(2, engine.state.value.availableDcSlots)
        assertEquals(4, engine.state.value.totalDcSlots)
    }
}
