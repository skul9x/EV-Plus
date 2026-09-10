package com.evcs.favorites.focus

import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.routing.OsrmRoutingClient
import com.evcs.favorites.data.routing.RoutingDestination
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Comprehensive verification test suite for Phase 02:
 * Conditional OSRM Driving Matrix Reroute Engine.
 *
 * Core criteria verified:
 * 1. OSRM matrix reroute is NOT triggered when target station has available DC slots (availableDcSlots > 0).
 * 2. Candidate ranking re-orders stations when road distance differs from Haversine (river/bridge scenario).
 * 3. Seamless fallback to Haversine ranking when OSRM API returns an error or network timeout without crashing.
 * 4. Candidate pre-filtering: takes top 8 matching DC candidates by Haversine, strictly excluding
 *    lower power tiers, saturated stations, or stations under maintenance.
 */
class FocusModeOsrmRerouteTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var osrmClient: OsrmRoutingClient

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
        val customClient = OkHttpClient.Builder()
            .connectTimeout(500, TimeUnit.MILLISECONDS)
            .readTimeout(500, TimeUnit.MILLISECONDS)
            .callTimeout(1000, TimeUnit.MILLISECONDS)
            .build()
        osrmClient = OsrmRoutingClient(
            okHttpClient = customClient,
            defaultBaseUrl = mockServer.url("/").toString()
        )
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    private fun createTestStation(
        id: String,
        name: String,
        lat: Double,
        lon: Double,
        powerKw: Long = 150L,
        availableSlots: Int = 1,
        totalSlots: Int = 2,
        depotStatus: String = "Normal"
    ): Station {
        val powers = listOf(
            PowerPort(
                typeWatts = powerKw * 1000L,
                label = "${powerKw}kW",
                availablePlugs = availableSlots,
                totalPlugs = totalSlots
            )
        )
        return Station(
            id = id,
            name = name,
            address = "Address $name",
            latitude = lat,
            longitude = lon,
            summary = "Trống $availableSlots/$totalSlots cổng sạc DC",
            connectors = "${powerKw}kW",
            depotStatus = depotStatus,
            powers = powers,
            totalAvailablePlugs = availableSlots,
            totalPlugs = totalSlots
        )
    }

    // =========================================================================
    // Criterion 1: OSRM matrix reroute is NOT triggered when availableDcSlots > 0
    // =========================================================================

    @Test
    fun rerouteNotTriggered_whenTargetStationHasAvailableDcSlots() = runBlocking {
        // Target station has 2 available 150kW DC slots (availableDcSlots > 0)
        val targetStation = createTestStation(
            id = "target_avail",
            name = "VinFast Landmark 81",
            lat = 10.7950,
            lon = 106.7218,
            powerKw = 150L,
            availableSlots = 2,
            totalSlots = 4
        )

        val candidate = createTestStation(
            id = "cand_1",
            name = "VinFast Thao Dien",
            lat = 10.8010,
            lon = 106.7320,
            powerKw = 150L,
            availableSlots = 1,
            totalSlots = 2
        )

        val engine = FocusModeTelemetryEngine(
            initialStation = targetStation,
            fetchStationTelemetry = { _, _, _ -> Result.success(targetStation) },
            fetchNearbyCandidates = { _, _ -> Result.success(listOf(candidate)) },
            locationProvider = { Pair(10.7940, 106.7200) },
            osrmRoutingClient = osrmClient
        )

        // 1. Initial state has available DC slots
        val initialState = engine.state.value
        assertEquals(2, initialState.availableDcSlots)
        assertNull(initialState.alternativeStation)

        // 2. Continuous 10s polling tick does NOT query OSRM or update alternativeStation
        val polledState = engine.pollOnce()
        assertEquals(2, polledState.availableDcSlots)
        assertNull("Alternative station must remain null when slots are available", polledState.alternativeStation)
        assertEquals("OSRM matrix must never be polled during routine polling", 0, mockServer.requestCount)

        // 3. Explicit on-demand resolution MUST also be rejected when target has available slots
        val onDemandResult = engine.resolveAlternativeStationOnDemand(
            driverLat = 10.7940,
            driverLon = 106.7200
        )
        assertNull("On-demand resolution must return null when target has available slots", onDemandResult)
        assertEquals("OSRM matrix must not be invoked on-demand when slots are available", 0, mockServer.requestCount)

        // 4. When target transitions from saturated to available, alternativeStation clears without OSRM
        val saturatedStation = targetStation.copy(
            powers = listOf(PowerPort(typeWatts = 150_000L, label = "150kW", availablePlugs = 0, totalPlugs = 4))
        )
        val saturatedEngine = FocusModeTelemetryEngine(
            initialStation = saturatedStation,
            fetchStationTelemetry = { _, _, _ -> Result.success(saturatedStation) },
            fetchNearbyCandidates = { _, _ -> Result.success(listOf(candidate)) },
            locationProvider = { Pair(10.7940, 106.7200) },
            osrmRoutingClient = osrmClient
        )
        val stateWhenFull = saturatedEngine.pollOnce()
        assertEquals(0, stateWhenFull.availableDcSlots)
        assertEquals("OSRM should not be called in background polling even when full", 0, mockServer.requestCount)

        // Target becomes available again
        val recoveredEngine = FocusModeTelemetryEngine(
            initialStation = targetStation,
            fetchStationTelemetry = { _, _, _ -> Result.success(targetStation) },
            fetchNearbyCandidates = { _, _ -> Result.success(listOf(candidate)) },
            locationProvider = { Pair(10.7940, 106.7200) },
            osrmRoutingClient = osrmClient
        )
        val stateWhenRecovered = recoveredEngine.pollOnce()
        assertEquals(2, stateWhenRecovered.availableDcSlots)
        assertNull(stateWhenRecovered.alternativeStation)
        assertEquals(0, mockServer.requestCount)
    }

    // =========================================================================
    // Criterion 2: Candidate ranking re-orders stations when road distance differs from Haversine
    //              (River / Bridge scenario)
    // =========================================================================

    @Test
    fun rerouteReordersStations_whenRoadDistanceDiffersFromHaversine_riverBridgeScenario() = runBlocking {
        // Driver position
        val driverLat = 10.7769
        val driverLon = 106.7009

        // Target station is saturated (0/4 available DC slots)
        val targetStation = createTestStation(
            id = "target_saturated",
            name = "Vincom Dong Khoi",
            lat = 10.7780,
            lon = 106.7020,
            powerKw = 150L,
            availableSlots = 0,
            totalSlots = 4
        )

        // Candidate River: Across Saigon River (Thu Thiem).
        // Haversine distance: ~0.44 km (straight line very close across the water!).
        // Actual Road driving distance: 5,200 meters (must drive to bridge and back). Duration: 720 seconds (12 mins).
        val candRiver = createTestStation(
            id = "cand_river",
            name = "VinFast Thu Thiem River",
            lat = 10.7790,
            lon = 106.7050,
            powerKw = 150L,
            availableSlots = 2,
            totalSlots = 4
        )

        // Candidate Highway: Along the direct boulevard on the same side of the river.
        // Haversine distance: ~2.1 km (farther in straight line!).
        // Actual Road driving distance: 2,400 meters. Duration: 240 seconds (4 mins).
        val candHighway = createTestStation(
            id = "cand_highway",
            name = "VinFast Dien Bien Phu Highway",
            lat = 10.7950,
            lon = 106.7120,
            powerKw = 150L,
            availableSlots = 1,
            totalSlots = 2
        )

        val candidates = listOf(candRiver, candHighway)

        // Verify that pure Haversine would pick candRiver
        val pureHaversineRec = FocusModeTelemetryEngine.findAlternativeStation(
            targetStation = targetStation,
            candidates = candidates,
            driverLat = driverLat,
            driverLon = driverLon
        )
        assertNotNull(pureHaversineRec)
        assertEquals("Haversine baseline must choose cand_river", "cand_river", pureHaversineRec!!.station.id)

        // Mock OSRM Table Service response for origin + 2 destinations:
        // Sources: 0 (origin)
        // Column 0: origin -> origin
        // Column 1: origin -> cand_river (distance 5200m, duration 720s)
        // Column 2: origin -> cand_highway (distance 2400m, duration 240s)
        val osrmResponseJson = """
            {
              "code": "Ok",
              "durations": [
                [0.0, 720.0, 240.0]
              ],
              "distances": [
                [0.0, 5200.0, 2400.0]
              ]
            }
        """.trimIndent()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(osrmResponseJson)
        )

        val engine = FocusModeTelemetryEngine(
            initialStation = targetStation,
            fetchStationTelemetry = { _, _, _ -> Result.success(targetStation) },
            fetchNearbyCandidates = { _, _ -> Result.success(candidates) },
            locationProvider = { Pair(driverLat, driverLon) },
            osrmRoutingClient = osrmClient
        )

        // Pre-condition: Automatic pollOnce does NOT call OSRM
        engine.pollOnce()
        assertEquals("OSRM Table must NOT be invoked by pollOnce", 0, mockServer.requestCount)

        // Execute on-demand OSRM reroute resolution
        val osrmRec = engine.resolveAlternativeStationOnDemand(
            driverLat = driverLat,
            driverLon = driverLon
        )

        assertNotNull("Must return an alternative recommendation", osrmRec)
        assertEquals("OSRM must re-order and select cand_highway (2.4km road vs 5.2km road)", "cand_highway", osrmRec!!.station.id)
        assertEquals(2400L, osrmRec.drivingDistanceMeters)
        assertEquals(240L, osrmRec.drivingDurationSeconds)
        assertEquals(2.4, osrmRec.distanceKm, 0.01)
        assertTrue(
            "Display label must show driving distance in km: ${osrmRec.displayRerouteLabel}",
            osrmRec.displayRerouteLabel.contains("+2.4km")
        )

        // Verify OSRM was called exactly once on-demand
        assertEquals(1, mockServer.requestCount)
        val recordedRequest = mockServer.takeRequest()
        assertTrue(
            "Request URL must contain table/v1/driving with sources=0",
            recordedRequest.path?.contains("/table/v1/driving/") == true &&
                    recordedRequest.path?.contains("sources=0") == true
        )

        // Verify state is updated with OSRM recommendation
        val updatedState = engine.state.value
        assertEquals("cand_highway", updatedState.alternativeStation?.station?.id)
        assertEquals(2400L, updatedState.alternativeStation?.drivingDistanceMeters)

        // Verify subsequent polling tick retains the OSRM recommendation without re-querying OSRM
        engine.pollOnce()
        assertEquals("No additional OSRM requests should be made on polling ticks", 1, mockServer.requestCount)
        assertEquals("cand_highway", engine.state.value.alternativeStation?.station?.id)
    }

    // =========================================================================
    // Criterion 3: Seamless fallback to Haversine ranking when OSRM API errors or times out
    // =========================================================================

    @Test
    fun rerouteSeamlessFallback_toHaversineRanking_whenOsrmFailsOrTimesOut() = runBlocking {
        val driverLat = 10.7769
        val driverLon = 106.7009

        val targetStation = createTestStation(
            id = "target_saturated_2",
            name = "Vincom Dong Khoi",
            lat = 10.7780,
            lon = 106.7020,
            powerKw = 150L,
            availableSlots = 0,
            totalSlots = 4
        )

        val candClose = createTestStation(
            id = "cand_close_haversine",
            name = "VinFast Nguyen Hue",
            lat = 10.7745,
            lon = 106.7025,
            powerKw = 150L,
            availableSlots = 1,
            totalSlots = 2
        )

        val candFar = createTestStation(
            id = "cand_far_haversine",
            name = "VinFast Bach Dang",
            lat = 10.7980,
            lon = 106.7150,
            powerKw = 150L,
            availableSlots = 2,
            totalSlots = 4
        )

        val candidates = listOf(candClose, candFar)

        // Case 3a: OSRM returns HTTP 500 Internal Server Error
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"code":"InternalError","message":"Server error"}""")
        )

        val engine = FocusModeTelemetryEngine(
            initialStation = targetStation,
            fetchStationTelemetry = { _, _, _ -> Result.success(targetStation) },
            fetchNearbyCandidates = { _, _ -> Result.success(candidates) },
            locationProvider = { Pair(driverLat, driverLon) },
            osrmRoutingClient = osrmClient
        )

        val fallbackRec = engine.resolveAlternativeStationOnDemand(
            driverLat = driverLat,
            driverLon = driverLon
        )

        assertNotNull("Must fallback seamlessly to Haversine recommendation without crashing", fallbackRec)
        assertEquals("cand_close_haversine", fallbackRec!!.station.id)
        assertNull("Driving distance meters must be null on fallback", fallbackRec.drivingDistanceMeters)
        assertNull("Driving duration seconds must be null on fallback", fallbackRec.drivingDurationSeconds)
        assertTrue("Distance should match Haversine (< 1km)", fallbackRec.distanceKm < 1.0)
        assertTrue(
            "Display label must format in meters when < 1km: ${fallbackRec.displayRerouteLabel}",
            fallbackRec.displayRerouteLabel.contains("m)")
        )

        // Case 3b: OSRM network disconnect / timeout
        mockServer.enqueue(
            MockResponse()
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
        )

        val timeoutFallbackRec = FocusModeTelemetryEngine.findAlternativeStationWithOsrm(
            targetStation = targetStation,
            candidates = candidates,
            driverLat = driverLat,
            driverLon = driverLon,
            osrmClient = osrmClient,
            timeoutMs = 500L
        )

        assertNotNull("Must fallback seamlessly on network timeout", timeoutFallbackRec)
        assertEquals("cand_close_haversine", timeoutFallbackRec!!.station.id)
        assertNull(timeoutFallbackRec.drivingDistanceMeters)
        assertNull(timeoutFallbackRec.drivingDurationSeconds)

        // Case 3c: OSRM client is null
        val nullClientRec = FocusModeTelemetryEngine.findAlternativeStationWithOsrm(
            targetStation = targetStation,
            candidates = candidates,
            driverLat = driverLat,
            driverLon = driverLon,
            osrmClient = null
        )
        assertNotNull("Must fallback seamlessly when osrmClient is null", nullClientRec)
        assertEquals("cand_close_haversine", nullClientRec!!.station.id)
    }

    // =========================================================================
    // Criterion 4: Candidate pre-filtering: Top 8 by Haversine, strictly matching DC tier
    // =========================================================================

    @Test
    fun candidatePreFiltering_limitsToTop8ByHaversine_andExcludesUnderpoweredOrSaturated() = runBlocking {
        val driverLat = 10.7769
        val driverLon = 106.7009

        val targetStation = createTestStation(
            id = "target_150kw",
            name = "Vincom Dong Khoi",
            lat = 10.7780,
            lon = 106.7020,
            powerKw = 150L,
            availableSlots = 0,
            totalSlots = 4
        )

        // Excluded: Underpowered (60kW < 150kW target)
        val underpowered = createTestStation(
            id = "cand_underpowered_60kw",
            name = "Trạm 60kW Gần",
            lat = 10.7770,
            lon = 106.7010,
            powerKw = 60L,
            availableSlots = 2,
            totalSlots = 2
        )

        // Excluded: Saturated (150kW, but 0 available plugs)
        val saturated = createTestStation(
            id = "cand_saturated_150kw",
            name = "Trạm 150kW Hết Chỗ",
            lat = 10.7771,
            lon = 106.7011,
            powerKw = 150L,
            availableSlots = 0,
            totalSlots = 4
        )

        // Excluded: Maintaining depot status
        val maintaining = createTestStation(
            id = "cand_maintaining",
            name = "Trạm Bảo Trì",
            lat = 10.7772,
            lon = 106.7012,
            powerKw = 150L,
            availableSlots = 2,
            totalSlots = 2,
            depotStatus = "Maintaining"
        )

        // 10 valid 150kW stations with available slots at increasing distances
        val validStations = (1..10).map { i ->
            createTestStation(
                id = "cand_valid_$i",
                name = "Trạm Hợp Lệ $i",
                lat = 10.7769 + (i * 0.01),
                lon = 106.7009 + (i * 0.01),
                powerKw = 150L,
                availableSlots = 1,
                totalSlots = 2
            )
        }

        val allCandidates = listOf(underpowered, saturated, maintaining) + validStations

        // OSRM response for origin + 8 destinations (top 8)
        // Durations: [0, 100, 200, 300, 400, 500, 600, 700, 800]
        // Distances: [0, 1000, 2000, 3000, 4000, 5000, 6000, 7000, 8000]
        val durations = listOf(0.0) + (1..8).map { it * 100.0 }
        val distances = listOf(0.0) + (1..8).map { it * 1000.0 }
        val responseJson = """
            {
              "code": "Ok",
              "durations": [
                [${durations.joinToString(",")}]
              ],
              "distances": [
                [${distances.joinToString(",")}]
              ]
            }
        """.trimIndent()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(responseJson)
        )

        val result = FocusModeTelemetryEngine.findAlternativeStationWithOsrm(
            targetStation = targetStation,
            candidates = allCandidates,
            driverLat = driverLat,
            driverLon = driverLon,
            osrmClient = osrmClient
        )

        assertNotNull("Result must not be null", result)
        assertEquals("cand_valid_1", result!!.station.id)
        assertEquals(1000L, result.drivingDistanceMeters)
        assertEquals(100L, result.drivingDurationSeconds)

        // Verify request sent to OSRM has exactly 8 destinations (total 9 coordinate pairs: origin + 8 destinations)
        assertEquals(1, mockServer.requestCount)
        val recordedReq = mockServer.takeRequest()
        val path = recordedReq.path ?: ""
        val coordsSegment = path.substringAfter("/table/v1/driving/").substringBefore("?")
        val coords = coordsSegment.split(";")
        assertEquals("Coordinates sent to OSRM must be origin + top 8 candidates (9 total points)", 9, coords.size)
    }
}
