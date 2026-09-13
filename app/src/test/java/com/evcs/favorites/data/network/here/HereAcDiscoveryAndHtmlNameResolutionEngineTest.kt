package com.evcs.favorites.data.network.here

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.network.here.model.HereAddress
import com.evcs.favorites.data.network.here.model.HereConnector
import com.evcs.favorites.data.network.here.model.HereConnectorStatus
import com.evcs.favorites.data.network.here.model.HereConnectorStatusesContainer
import com.evcs.favorites.data.network.here.model.HereConnectorsContainer
import com.evcs.favorites.data.network.here.model.HereEvStation
import com.evcs.favorites.data.network.here.model.HereEvStationsList
import com.evcs.favorites.data.network.here.model.HereEvStationsResponse
import com.evcs.favorites.data.network.here.model.HerePosition
import com.evcs.favorites.focus.EvcsStationNameResolver
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Comprehensive verification test for Phase 01:
 * HERE AC Discovery & EVCS HTML Canonical Name Resolver.
 *
 * Requirements covered:
 * 1. AC tier matching: Includes 11kW and 22kW AC charging posts; strictly excludes 3.5kW and 7kW tiers.
 * 2. Strict availability enforcement: Stations with 0 available or non-AVAILABLE states
 *    (OCCUPIED, OTHER, OUT_OF_SERVICE) are excluded.
 * 3. Proximity sorting & top 10 capping: Qualifying stations are sorted nearest-first by Haversine distance
 *    and capped at maxLimit (default 10).
 * 4. Canonical HTML name extraction & sanitization: Extracts title from <title> or <meta name="title">,
 *    strips "Trạm sạc VinFast - " prefix and " - Trạm Sạc EV" suffix, and sanitizes via StationNameSanitizer.
 * 5. Fallback resilience: Failed HTTP responses (404/500/timeout/empty) cleanly fallback to "VinFast - $address".
 * 6. Live resolution without cache retention: Resolving identical station IDs executes fresh lookups without cache hits.
 * 7. End-to-end pipeline: Wires HereEvApiClient and EvcsStationNameResolver for full hybrid discovery.
 */
class HereAcDiscoveryAndHtmlNameResolutionEngineTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var okHttpClient: OkHttpClient
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    // =========================================================================
    // 1. AC Tier Matching Tests (11kW & 22kW Included, 3.5kW & 7kW Excluded)
    // =========================================================================

    @Test
    fun testAcTierMatching_includes11kWAnd22kW_excludes3kWAnd7kW() {
        val st11kW = createStation(
            id = "VF-11KW",
            cpoId = "C.BNI11197",
            name = "VinFast Station 11kW",
            connectors = listOf(
                createConnector(powerKw = 11.0, powerType = "AC_3_PHASE", available = 1, occupied = 0)
            )
        )
        val st22kW = createStation(
            id = "VF-22KW",
            name = "VinFast Station 22kW",
            connectors = listOf(
                createConnector(powerKw = 22.0, powerType = "AC_3_PHASE", available = 2, occupied = 0)
            )
        )
        val st7kW = createStation(
            id = "VF-7KW",
            name = "VinFast Station 7kW",
            connectors = listOf(
                createConnector(powerKw = 7.0, powerType = "AC_1_PHASE", available = 1, occupied = 0)
            )
        )
        val st3kW = createStation(
            id = "VF-3KW",
            name = "VinFast Station 3.5kW",
            connectors = listOf(
                createConnector(powerKw = 3.5, powerType = "AC_1_PHASE", available = 1, occupied = 0)
            )
        )
        val stDcOnly = createStation(
            id = "VF-60KW",
            name = "VinFast Station 60kW DC",
            connectors = listOf(
                createConnector(powerKw = 60.0, powerType = "DC", available = 2, occupied = 0)
            )
        )

        // Verify hasAvailableAcConnector behavior directly on models
        assertTrue("11kW AC station with available slot must qualify", st11kW.hasAvailableAcConnector())
        assertTrue("22kW AC station with available slot must qualify", st22kW.hasAvailableAcConnector())
        assertFalse("7kW AC station must be excluded", st7kW.hasAvailableAcConnector())
        assertFalse("3.5kW AC station must be excluded", st3kW.hasAvailableAcConnector())
        assertFalse("DC-only station without AC must be excluded", stDcOnly.hasAvailableAcConnector())

        // Verify mapped domain station excludes 7kW/3.5kW from powers list
        val hybridStation = createStation(
            id = "VF-HYBRID",
            name = "VinFast Hybrid Station",
            connectors = listOf(
                createConnector(powerKw = 11.0, powerType = "AC_3_PHASE", available = 1, occupied = 0),
                createConnector(powerKw = 60.0, powerType = "DC", available = 2, occupied = 0),
                createConnector(powerKw = 7.0, powerType = "AC_1_PHASE", available = 1, occupied = 0)
            )
        )
        val domainStation = com.evcs.favorites.data.network.here.model.HereModelMapper.mapStationToDomain(
            hybridStation,
            dcOnly = false
        )
        assertEquals(2, domainStation.powers.size)
        assertTrue("Contains 60kW DC port", domainStation.powers.any { it.label == "60kW" })
        assertTrue("Contains 11kW AC port", domainStation.powers.any { it.label == "11kW" })
        assertFalse("7kW port must be excluded from power list", domainStation.powers.any { it.label == "7kW" })
    }

    // =========================================================================
    // 2. Strict Availability Enforcement Tests
    // =========================================================================

    @Test
    fun testStrictAvailabilityFilter_excludesOccupiedOtherAndOutOfService() {
        val fullyOccupiedAc = createStation(
            id = "VF-OCCUPIED",
            name = "VinFast Fully Occupied",
            connectors = listOf(
                createConnector(powerKw = 11.0, powerType = "AC_3_PHASE", available = 0, occupied = 2)
            )
        )
        val outOfServiceAc = createStation(
            id = "VF-OOS",
            name = "VinFast Out of Service",
            connectors = listOf(
                HereConnector(
                    maxPowerLevel = 22.0,
                    powerType = "AC_3_PHASE",
                    connectorStatuses = HereConnectorStatusesContainer(
                        connectorStatus = listOf(
                            HereConnectorStatus(state = "OUT_OF_SERVICE"),
                            HereConnectorStatus(state = "OTHER")
                        )
                    )
                )
            )
        )
        val mixedAvailability = createStation(
            id = "VF-MIXED",
            name = "VinFast Mixed Available",
            connectors = listOf(
                createConnector(powerKw = 11.0, powerType = "AC_3_PHASE", available = 0, occupied = 1),
                createConnector(powerKw = 22.0, powerType = "AC_3_PHASE", available = 1, occupied = 1)
            )
        )

        assertFalse("Fully occupied AC station must be excluded", fullyOccupiedAc.hasAvailableAcConnector())
        assertFalse("Out of service AC station must be excluded", outOfServiceAc.hasAvailableAcConnector())
        assertTrue("Station with at least 1 available AC slot must qualify", mixedAvailability.hasAvailableAcConnector())
    }

    // =========================================================================
    // 3. Proximity Sorting and Top 10 Capping Tests
    // =========================================================================

    @Test
    fun testProximitySortingAndTop10Capping_sortsNearestFirstAndLimitsTo10() = runTest {
        val userLat = 21.0285
        val userLon = 105.8542

        // Create 15 qualifying stations at increasing distances (0.01 deg lat increment approx 1.11km)
        val stationsList = (1..15).map { i ->
            val distOffset = i * 0.005
            createStation(
                id = "VF-STATION-$i",
                cpoId = "C.LOC$i",
                name = "Trạm sạc VinFast $i",
                latitude = userLat + distOffset,
                longitude = userLon + distOffset,
                connectors = listOf(
                    createConnector(powerKw = 11.0, powerType = "AC_3_PHASE", available = 1, occupied = 0)
                )
            )
        }

        val hereResponse = HereEvStationsResponse(
            evStations = HereEvStationsList(evStation = stationsList.shuffled()) // shuffled input
        )
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(json.encodeToString(hereResponse)))

        val apiClient = HereEvApiClient(
            client = okHttpClient,
            baseUrl = mockWebServer.url("/ev").toString()
        )

        val result = apiClient.fetchNearbyAvailableAcStations(
            latitude = userLat,
            longitude = userLon,
            radiusMeters = 15_000,
            maxLimit = 10,
            nameResolver = null // test raw sorting without resolver
        )

        assertTrue(result.isSuccess)
        val stations = result.getOrThrow()
        assertEquals("Must be strictly capped at 10 items", 10, stations.size)

        // Verify sorted nearest-first ascending
        for (idx in 0 until stations.size - 1) {
            val d1 = stations[idx].distanceKm ?: 0.0
            val d2 = stations[idx + 1].distanceKm ?: 0.0
            assertTrue("Stations must be sorted nearest-first: d1 ($d1) <= d2 ($d2)", d1 <= d2)
        }

        // The closest station should be C.LOC1 (VF-STATION-1)
        assertEquals("C.LOC1", stations[0].id)
    }

    // =========================================================================
    // 4. HTML Title Extraction and Cleaning Tests
    // =========================================================================

    @Test
    fun testHtmlTitleExtractionAndSanitization_extractsFromTitleAndMetaTags() = runTest {
        val resolver = EvcsStationNameResolver(
            httpClient = okHttpClient,
            htmlBaseUrl = mockWebServer.url("/").toString()
        )

        // Case 1: Standard <title> with VinFast prefix and EV suffix
        val htmlTitle1 = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Trạm sạc VinFast - TƯ NHÂN Nguyễn Văn Đức - Trạm Sạc EV</title>
            </head>
            <body></body>
            </html>
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(htmlTitle1))

        val resolvedName1 = resolver.resolveStationNameFromHtml(
            locationId = "C.BNI11197",
            fallbackAddress = "29 Bình Than 1, Bắc Ninh"
        )
        assertEquals("TƯ NHÂN Nguyễn Văn Đức", resolvedName1)

        // Verify request URL and User-Agent
        val req1 = mockWebServer.takeRequest()
        assertEquals("/tram-sac-vinfast-c.bni11197.html", req1.path)
        assertEquals(EvcsApiClient.USER_AGENT_BROWSER, req1.getHeader("User-Agent"))

        // Case 2: <meta name="title"> fallback with dash variations
        val htmlTitle2 = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="title" content="Trạm sạc VinFast – Hộ kinh doanh Trịnh Thị Duyên – Trạm Sạc EV" />
            </head>
            <body></body>
            </html>
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(htmlTitle2))

        val resolvedName2 = resolver.resolveStationNameFromHtml(
            locationId = "c.bni0088",
            fallbackAddress = "Gia Bình, Bắc Ninh"
        )
        assertEquals("Hộ kinh doanh Trịnh Thị Duyên", resolvedName2)
    }

    // =========================================================================
    // 5. Fallback Resilience on HTTP Failure or Missing Title
    // =========================================================================

    @Test
    fun testHtmlResolutionFailureAndFallback_cleanlyFallsBackToVinFastAddress() = runTest {
        val resolver = EvcsStationNameResolver(
            httpClient = okHttpClient,
            htmlBaseUrl = mockWebServer.url("/").toString()
        )

        // Case 1: HTTP 404 Not Found
        mockWebServer.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))
        val fallback404 = resolver.resolveStationNameFromHtml(
            locationId = "c.unknown001",
            fallbackAddress = "123 Đường 3/2, Cần Thơ"
        )
        assertEquals("VinFast - 123 Đường 3/2, Cần Thơ", fallback404)

        // Case 2: HTTP 500 Internal Error
        mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody("Server Error"))
        val fallback500 = resolver.resolveStationNameFromHtml(
            locationId = "c.error002",
            fallbackAddress = "Phường Đại Phúc, Bắc Ninh"
        )
        assertEquals("VinFast - Phường Đại Phúc, Bắc Ninh", fallback500)

        // Case 3: Empty HTML or no title element
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("<html><body>No Title</body></html>"))
        val fallbackNoTitle = resolver.resolveStationNameFromHtml(
            locationId = "c.notitle003",
            fallbackAddress = "Landmark 81, TP.HCM"
        )
        assertEquals("VinFast - Landmark 81, TP.HCM", fallbackNoTitle)
    }

    // =========================================================================
    // 6. Live Resolution Without Cache Retention Test
    // =========================================================================

    @Test
    fun testLiveResolutionWithoutCacheRetention_executesFreshLookupsEachTime() = runTest {
        val resolver = EvcsStationNameResolver(
            httpClient = okHttpClient,
            htmlBaseUrl = mockWebServer.url("/").toString()
        )

        val html = """
            <html>
            <head><title>Trạm sạc VinFast - TƯ NHÂN Nguyễn Văn Đức - Trạm Sạc EV</title></head>
            </html>
        """.trimIndent()

        // Enqueue two responses for two subsequent calls for the same station ID
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(html))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(html))

        val nameFirst = resolver.resolveStationNameFromHtml("C.BNI11197", "29 Bình Than 1")
        val nameSecond = resolver.resolveStationNameFromHtml("C.BNI11197", "29 Bình Than 1")

        assertEquals("TƯ NHÂN Nguyễn Văn Đức", nameFirst)
        assertEquals("TƯ NHÂN Nguyễn Văn Đức", nameSecond)

        // Must execute 2 separate HTTP requests without in-memory cache hit
        assertEquals("Must execute 2 fresh network calls", 2, mockWebServer.requestCount)
    }

    // =========================================================================
    // 7. End-to-End Hybrid AC Discovery Pipeline Test
    // =========================================================================

    @Test
    fun testEndToEndHybridAcDiscoveryPipeline_discoversFiltersAndEnrichesStations() = runTest {
        val userLat = 21.1667
        val userLon = 106.0706

        // Set up dispatcher for HERE stations query and EVCS HTML requests
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.contains("/ev/stations.json") -> {
                        val stations = listOf(
                            // Station 1: 11kW AC available -> Should qualify, closest
                            createStation(
                                id = "VF-BNI-01",
                                cpoId = "C.BNI11197",
                                name = "Trạm sạc VinFast",
                                address = "29 Bình Than 1, Phường Đại Phúc, Bắc Ninh",
                                latitude = 21.1670,
                                longitude = 106.0710,
                                connectors = listOf(
                                    createConnector(powerKw = 11.0, powerType = "AC_3_PHASE", available = 1, occupied = 0)
                                )
                            ),
                            // Station 2: 22kW AC available -> Should qualify, 2nd closest
                            createStation(
                                id = "VF-BNI-02",
                                cpoId = "C.BNI00088",
                                name = "VinFast Generic",
                                address = "Gia Bình, Bắc Ninh",
                                latitude = 21.1700,
                                longitude = 106.0750,
                                connectors = listOf(
                                    createConnector(powerKw = 22.0, powerType = "AC_3_PHASE", available = 2, occupied = 0)
                                )
                            ),
                            // Station 3: 11kW AC occupied -> Should be excluded
                            createStation(
                                id = "VF-BNI-03",
                                cpoId = "C.BNI99999",
                                name = "VinFast Occupied",
                                address = "Quế Võ, Bắc Ninh",
                                latitude = 21.1650,
                                longitude = 106.0700,
                                connectors = listOf(
                                    createConnector(powerKw = 11.0, powerType = "AC_3_PHASE", available = 0, occupied = 1)
                                )
                            ),
                            // Station 4: DC only -> Should be excluded
                            createStation(
                                id = "VF-BNI-04",
                                cpoId = "C.BNI88888",
                                name = "VinFast DC Only",
                                address = "Yên Phong, Bắc Ninh",
                                latitude = 21.1680,
                                longitude = 106.0720,
                                connectors = listOf(
                                    createConnector(powerKw = 60.0, powerType = "DC", available = 2, occupied = 0)
                                )
                            )
                        )
                        val response = HereEvStationsResponse(evStations = HereEvStationsList(evStation = stations))
                        MockResponse().setResponseCode(200).setBody(json.encodeToString(response))
                    }
                    path.contains("/tram-sac-vinfast-c.bni11197.html") -> {
                        val html = """
                            <html><head><title>Trạm sạc VinFast - TƯ NHÂN Nguyễn Văn Đức - Trạm Sạc EV</title></head></html>
                        """.trimIndent()
                        MockResponse().setResponseCode(200).setBody(html)
                    }
                    path.contains("/tram-sac-vinfast-c.bni00088.html") -> {
                        // Returns 404 to test graceful fallback
                        MockResponse().setResponseCode(404).setBody("Not Found")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val nameResolver = EvcsStationNameResolver(
            httpClient = okHttpClient,
            htmlBaseUrl = mockWebServer.url("/").toString()
        )

        val apiClient = HereEvApiClient(
            client = okHttpClient,
            baseUrl = mockWebServer.url("/ev").toString(),
            stationNameResolver = nameResolver
        )

        val result = apiClient.fetchNearbyAvailableAcStations(
            latitude = userLat,
            longitude = userLon,
            radiusMeters = 10_000,
            maxLimit = 10
        )

        assertTrue(result.isSuccess)
        val stations = result.getOrThrow()

        // Only Station 1 and Station 2 qualify
        assertEquals(2, stations.size)

        // Station 1: Canonical HTML name resolved
        val st1 = stations[0]
        assertEquals("C.BNI11197", st1.id)
        assertEquals("TƯ NHÂN Nguyễn Văn Đức", st1.name)
        assertEquals(1, st1.totalAvailablePlugs)
        assertEquals(1, st1.totalPlugs)
        assertTrue(st1.powers.any { it.label == "11kW" })

        // Station 2: 404 HTML fallback cleanly to "VinFast - address"
        val st2 = stations[1]
        assertEquals("C.BNI00088", st2.id)
        assertEquals("VinFast - Gia Bình, Bắc Ninh", st2.name)
        assertEquals(2, st2.totalAvailablePlugs)
        assertEquals(2, st2.totalPlugs)
        assertTrue(st2.powers.any { it.label == "22kW" })
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private fun createStation(
        id: String,
        cpoId: String? = null,
        name: String,
        address: String = "Test Address",
        latitude: Double = 21.0,
        longitude: Double = 105.0,
        connectors: List<HereConnector>
    ): HereEvStation {
        return HereEvStation(
            id = id,
            cpoId = cpoId,
            name = name,
            address = HereAddress(street = address),
            position = HerePosition(latitude = latitude, longitude = longitude),
            connectors = HereConnectorsContainer(connector = connectors)
        )
    }

    private fun createConnector(
        powerKw: Double,
        powerType: String,
        available: Int,
        occupied: Int
    ): HereConnector {
        val statuses = mutableListOf<HereConnectorStatus>()
        repeat(available) { statuses.add(HereConnectorStatus(state = "AVAILABLE")) }
        repeat(occupied) { statuses.add(HereConnectorStatus(state = "OCCUPIED")) }
        return HereConnector(
            maxPowerLevel = powerKw,
            powerType = powerType,
            numberOfAvailable = available,
            numberOfConnectors = available + occupied,
            connectorStatuses = HereConnectorStatusesContainer(
                connectorStatus = statuses,
                numberOfAvailable = available
            )
        )
    }
}
