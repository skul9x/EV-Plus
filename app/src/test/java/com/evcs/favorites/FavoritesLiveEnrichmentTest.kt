package com.evcs.favorites

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.domain.location.DistanceCalculator
import com.evcs.favorites.ui.components.resolveStatusBadge
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Single comprehensive verification test for Phase 03: Real-Time Enrichment for Favorites.
 *
 * Covers:
 * 1. Station detail HTML coordinate & metadata extraction across diverse real-world formats:
 *    - OpenGraph / HTML meta tags (`place:location:latitude`, `geo.position`, etc.)
 *    - Embedded JSON / JS variables (`latitude`, `longitude`, `lat`, `lng`)
 *    - Maps search query & geo URI links
 *    - HTML data attributes (`data-lat`, `data-lng`)
 *    - Error / corrupted HTML resilience (graceful null fallback)
 * 2. DistanceCalculator geographic clustering:
 *    - Grouping stations within ~15km into unified cluster centers
 *    - Minimal search query center generation across distant provinces
 *    - Edge case handling (0.0/0.0 coords, empty lists, single points)
 * 3. EvcsRepository targeted cluster enrichment:
 *    - Resolving unknown coordinates from station detail HTML
 *    - Dispatching targeted HMAC `/search` requests for distinct clusters
 *    - Merging live port metrics: exact `availablePlugs/totalPlugs` (`2/4`, `2/2`),
 *      active plug counts (`totalAvailablePlugs`), and accurate status badges
 *      ("Hoạt động" when plugs open, "Hết cổng" when full, "Đã lưu" when unqueried)
 * 4. Persistent coordinate caching:
 *    - Saving discovered coordinates to SessionStorage
 *    - Restoring cached coordinates in new repository instances without refetching HTML
 */
class FavoritesLiveEnrichmentTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
        sessionStorage = InMemorySessionStorage()
        sessionManager = SessionManager(sessionStorage).apply {
            phpSessionId = "test_sess_id_123"
            authCookie = "test_auth_token_456"
            csrfToken = "test_csrf_789"
        }
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    // =========================================================================
    // PART 1: Station Detail HTML & Metadata Coordinate Parsing
    // =========================================================================

    @Test
    fun testStationDetailCoordinateAndMetadataParsing() {
        // 1a. OpenGraph / Location Meta Tags
        val metaHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta property="place:location:latitude" content="21.1452" />
                <meta property="place:location:longitude" content="106.1553" />
                <meta property="business:contact_data:street_address" content="Quế Võ, Bắc Ninh" />
                <meta property="working-time" content="24/7" />
            </head>
            <body><h1>Trạm sạc Bắc Ninh</h1></body>
            </html>
        """.trimIndent()

        val metaCoords = EvcsApiClient.parseCoordinatesFromHtml(metaHtml)
        assertNotNull(metaCoords)
        assertEquals(21.1452, metaCoords!!.first, 0.0001)
        assertEquals(106.1553, metaCoords.second, 0.0001)

        val metadata = EvcsApiClient.parseStationMetadataFromHtml(metaHtml)
        assertNotNull(metadata)
        assertEquals(21.1452, metadata!!.latitude, 0.0001)
        assertEquals(106.1553, metadata.longitude, 0.0001)
        assertEquals("Quế Võ, Bắc Ninh", metadata.address)
        assertEquals("24/7", metadata.workingTime)

        // 1b. geo.position Meta Tag with semicolon & comma
        val geoPosSemicolon = """<meta name="geo.position" content="10.0345;105.7890">"""
        val geoPosComma = """<meta name="geo.position" content="10.0345, 105.7890">"""
        val parsedSemi = EvcsApiClient.parseCoordinatesFromHtml(geoPosSemicolon)
        val parsedComma = EvcsApiClient.parseCoordinatesFromHtml(geoPosComma)
        assertNotNull(parsedSemi)
        assertEquals(10.0345, parsedSemi!!.first, 0.0001)
        assertEquals(105.7890, parsedSemi.second, 0.0001)
        assertNotNull(parsedComma)
        assertEquals(10.0345, parsedComma!!.first, 0.0001)
        assertEquals(105.7890, parsedComma.second, 0.0001)

        // 1c. Embedded JSON & JavaScript variables
        val jsonHtml = """
            <script>
                window.__STATION_DATA__ = {
                    "stationId": "C.BNI0012",
                    "latitude": 21.1452,
                    "longitude": 106.1553,
                    "name": "VinFast Quế Võ"
                };
            </script>
        """.trimIndent()
        val parsedJson = EvcsApiClient.parseCoordinatesFromHtml(jsonHtml)
        assertNotNull(parsedJson)
        assertEquals(21.1452, parsedJson!!.first, 0.0001)
        assertEquals(106.1553, parsedJson.second, 0.0001)

        // 1d. Short format lat/lng
        val shortFormatHtml = """var loc = { "lat": 16.0544, "lng": 108.2022 };"""
        val parsedShort = EvcsApiClient.parseCoordinatesFromHtml(shortFormatHtml)
        assertNotNull(parsedShort)
        assertEquals(16.0544, parsedShort!!.first, 0.0001)
        assertEquals(108.2022, parsedShort.second, 0.0001)

        // 1e. Maps / Geo navigation link
        val mapLinkHtml = """<a href="https://www.google.com/maps/search/?api=1&query=21.0285,105.8542">Xem bản đồ</a>"""
        val parsedMapLink = EvcsApiClient.parseCoordinatesFromHtml(mapLinkHtml)
        assertNotNull(parsedMapLink)
        assertEquals(21.0285, parsedMapLink!!.first, 0.0001)
        assertEquals(105.8542, parsedMapLink.second, 0.0001)

        // 1f. HTML data-lat and data-lng attributes
        val dataAttrHtml = """<div class="station-map" data-lat="21.1452" data-lng="106.1553"></div>"""
        val parsedDataAttr = EvcsApiClient.parseCoordinatesFromHtml(dataAttrHtml)
        assertNotNull(parsedDataAttr)
        assertEquals(21.1452, parsedDataAttr!!.first, 0.0001)
        assertEquals(106.1553, parsedDataAttr.second, 0.0001)

        // 1g. Malformed, blank or invalid coordinates return null
        assertNull(EvcsApiClient.parseCoordinatesFromHtml(""))
        assertNull(EvcsApiClient.parseCoordinatesFromHtml("   "))
        assertNull(EvcsApiClient.parseCoordinatesFromHtml("<html><body>No coords here</body></html>"))
        assertNull(EvcsApiClient.parseCoordinatesFromHtml("""<meta name="geo.position" content="0.0, 0.0">"""))
        assertFalse(EvcsApiClient.isValidCoordinate(0.0, 0.0))
        assertFalse(EvcsApiClient.isValidCoordinate(null, 105.8))
        assertFalse(EvcsApiClient.isValidCoordinate(95.0, 105.8)) // Out of bounds
    }

    // =========================================================================
    // PART 2: Geographic Clustering & Minimal Search Centers
    // =========================================================================

    @Test
    fun testGeographicClusteringAndSearchCenterMinimization() {
        // Define coordinates across 3 distant regions:
        // Region 1: Bắc Ninh (2 stations ~400m apart)
        val bacNinh1 = Pair(21.1452, 106.1553)
        val bacNinh2 = Pair(21.1480, 106.1580)

        // Region 2: Hà Nội (2 stations ~3.5km apart, ~32km from Bắc Ninh)
        val hanoi1 = Pair(21.0285, 105.8542)
        val hanoi2 = Pair(21.0031, 105.8200)

        // Region 3: Cần Thơ (1 station, ~1100km from Hà Nội & Bắc Ninh)
        val canTho = Pair(10.0345, 105.7890)

        val allPoints = listOf(bacNinh1, bacNinh2, hanoi1, hanoi2, canTho)

        // Run clustering with 15km grouping radius
        val clusters = DistanceCalculator.clusterPoints(allPoints, maxDistanceKm = 15.0)

        // 5 stations across 3 regions MUST produce exactly 3 clusters
        assertEquals(3, clusters.size)

        // Cluster 1: Bắc Ninh (contains 2 stations)
        val bacNinhCluster = clusters.first { cluster ->
            cluster.points.contains(bacNinh1)
        }
        assertEquals(2, bacNinhCluster.points.size)
        assertTrue(bacNinhCluster.points.contains(bacNinh2))
        // Center should be roughly the midpoint
        assertEquals((21.1452 + 21.1480) / 2.0, bacNinhCluster.center.first, 0.001)
        assertEquals((106.1553 + 106.1580) / 2.0, bacNinhCluster.center.second, 0.001)

        // Cluster 2: Hà Nội (contains 2 stations)
        val hanoiCluster = clusters.first { cluster ->
            cluster.points.contains(hanoi1)
        }
        assertEquals(2, hanoiCluster.points.size)
        assertTrue(hanoiCluster.points.contains(hanoi2))

        // Cluster 3: Cần Thơ (contains 1 station)
        val canThoCluster = clusters.first { cluster ->
            cluster.points.contains(canTho)
        }
        assertEquals(1, canThoCluster.points.size)
        assertEquals(10.0345, canThoCluster.center.first, 0.001)
        assertEquals(105.7890, canThoCluster.center.second, 0.001)

        // Convenience method clusterCoordinates returns the 3 centers
        val centers = DistanceCalculator.clusterCoordinates(allPoints, maxDistanceKm = 15.0)
        assertEquals(3, centers.size)

        // Edge Cases:
        // Empty list -> empty clusters
        assertTrue(DistanceCalculator.clusterPoints(emptyList()).isEmpty())
        assertTrue(DistanceCalculator.clusterCoordinates(emptyList()).isEmpty())

        // Invalid (0.0, 0.0) coordinates are filtered out
        val invalidPoints = listOf(Pair(0.0, 0.0), Pair(0.0, 0.0))
        assertTrue(DistanceCalculator.clusterPoints(invalidPoints).isEmpty())

        // Single point -> exactly 1 cluster
        val singlePointClusters = DistanceCalculator.clusterCoordinates(listOf(bacNinh1))
        assertEquals(1, singlePointClusters.size)
        assertEquals(bacNinh1.first, singlePointClusters[0].first, 0.0001)
        assertEquals(bacNinh1.second, singlePointClusters[0].second, 0.0001)
    }

    // =========================================================================
    // PART 3: End-to-End Targeted Cluster Enrichment & Live Port Metrics
    // =========================================================================

    @Test
    fun testTargetedClusterEnrichmentForDistantFavorites() = runBlocking {
        val baseUrl = mockServer.url("/").toString().removeSuffix("/")
        val apiClient = EvcsApiClient(sessionManager, baseUrl = baseUrl)
        val persistentStorage = InMemorySessionStorage()

        val recordedRequests = CopyOnWriteArrayList<RecordedRequest>()

        // Dispatcher answering requests dynamically:
        // 1. /favorite.html returns 3 favorites in 2 distinct regions (Bắc Ninh & Cần Thơ)
        // 2. /tram-sac-...detail pages return HTML containing station coordinates
        // 3. /search queries return live telemetry per cluster
        mockServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recordedRequests.add(request)
                val path = request.path ?: ""

                return when {
                    path.startsWith("/favorite.html") -> {
                        MockResponse().setResponseCode(200).setBody(
                            """
                            {
                              "sync": true,
                              "csrf": "csrf_token_fav",
                              "server": [
                                {
                                  "locationId": "C.BNI0012",
                                  "name": "VinFast - TTTM Dabaco Mart Quế Võ",
                                  "address": "Bắc Ninh",
                                  "connectors": "30kW, 20kW"
                                },
                                {
                                  "locationId": "C.BNI0311",
                                  "name": "VinFast - KĐT Tùng Bách",
                                  "address": "Bắc Ninh",
                                  "connectors": "60kW"
                                },
                                {
                                  "locationId": "C.RAB0045",
                                  "name": "Rabbit E-Mobility TTC",
                                  "address": "Cần Thơ",
                                  "connectors": "120kW, 60kW"
                                }
                              ]
                            }
                            """.trimIndent()
                        )
                    }

                    // Detail HTML resolution for Bắc Ninh station 1
                    path.contains("bni0012", ignoreCase = true) -> {
                        MockResponse().setResponseCode(200).setBody(
                            """
                            <html>
                            <head>
                                <meta property="place:location:latitude" content="21.1452" />
                                <meta property="place:location:longitude" content="106.1553" />
                            </head>
                            <body>Detail Page Dabaco</body>
                            </html>
                            """.trimIndent()
                        )
                    }

                    // Detail HTML resolution for Bắc Ninh station 2 (close to station 1: ~400m)
                    path.contains("bni0311", ignoreCase = true) -> {
                        MockResponse().setResponseCode(200).setBody(
                            """
                            <html>
                            <head>
                                <meta property="place:location:latitude" content="21.1480" />
                                <meta property="place:location:longitude" content="106.1580" />
                            </head>
                            <body>Detail Page Tung Bach</body>
                            </html>
                            """.trimIndent()
                        )
                    }

                    // Detail HTML resolution for Cần Thơ station (~1100km away)
                    path.contains("rab0045", ignoreCase = true) -> {
                        MockResponse().setResponseCode(200).setBody(
                            """
                            <html>
                            <head>
                                <meta property="place:location:latitude" content="10.0345" />
                                <meta property="place:location:longitude" content="105.7890" />
                            </head>
                            <body>Detail Page Can Tho</body>
                            </html>
                            """.trimIndent()
                        )
                    }

                    // Search API query - response matches cluster coordinates
                    path.startsWith("/search") -> {
                        val body = request.body.readUtf8()
                        if (body.contains("21.14")) {
                            // Bắc Ninh Cluster response
                            MockResponse().setResponseCode(200).setBody(
                                """
                                {
                                  "code": 200000,
                                  "data": [
                                    {
                                      "locationId": "C.BNI0012",
                                      "stationName": "VinFast - TTTM Dabaco Mart Quế Võ Enriched",
                                      "stationAddress": "Bắc Ninh Enriched",
                                      "latitude": 21.1452,
                                      "longitude": 106.1553,
                                      "depotStatus": "Normal",
                                      "evsePowers": [
                                        {
                                          "type": 30000,
                                          "numberOfAvailableEvse": 2,
                                          "totalEvse": 4
                                        },
                                        {
                                          "type": 20000,
                                          "numberOfAvailableEvse": 2,
                                          "totalEvse": 2
                                        }
                                      ],
                                      "isPublic": true,
                                      "isFreeParking": true,
                                      "workingTimeDescription": "24/7"
                                    },
                                    {
                                      "locationId": "C.BNI0311",
                                      "stationName": "VinFast - KĐT Tùng Bách (Full)",
                                      "stationAddress": "Bắc Ninh Enriched",
                                      "latitude": 21.1480,
                                      "longitude": 106.1580,
                                      "depotStatus": "Normal",
                                      "evsePowers": [
                                        {
                                          "type": 60000,
                                          "numberOfAvailableEvse": 0,
                                          "totalEvse": 4
                                        }
                                      ],
                                      "isPublic": true,
                                      "isFreeParking": false,
                                      "workingTimeDescription": "24/7"
                                    }
                                  ]
                                }
                                """.trimIndent()
                            )
                        } else {
                            // Cần Thơ Cluster response
                            MockResponse().setResponseCode(200).setBody(
                                """
                                {
                                  "code": 200000,
                                  "data": [
                                    {
                                      "locationId": "C.RAB0045",
                                      "stationName": "Rabbit E-Mobility TTC Enriched",
                                      "stationAddress": "Cần Thơ Enriched",
                                      "latitude": 10.0345,
                                      "longitude": 105.7890,
                                      "depotStatus": "Normal",
                                      "evsePowers": [
                                        {
                                          "type": 120000,
                                          "numberOfAvailableEvse": 3,
                                          "totalEvse": 4
                                        },
                                        {
                                          "type": 60000,
                                          "numberOfAvailableEvse": 1,
                                          "totalEvse": 2
                                        }
                                      ],
                                      "isPublic": true,
                                      "isFreeParking": true,
                                      "workingTimeDescription": "24/7"
                                    }
                                  ]
                                }
                                """.trimIndent()
                            )
                        }
                    }

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        // Initialize repository with persistent storage and autoResolveCoordinates enabled
        val repository = EvcsRepository(
            apiClient = apiClient,
            cacheStorage = persistentStorage,
            autoResolveCoordinates = true
        )

        // Execute getFavorites without device GPS (userLat = null, userLon = null)
        val result = repository.getFavorites(userLat = null, userLon = null)
        assertTrue(result.isSuccess)

        val stations = result.getOrThrow()
        assertEquals(3, stations.size)

        // 3a. Verify Station 1 (Bắc Ninh - Dabaco): Enriched with exact live telemetry 2/4 and 2/2
        val station1 = stations.first { it.id == "C.BNI0012" }
        assertEquals("VinFast - TTTM Dabaco Mart Quế Võ Enriched", station1.name)
        assertEquals(21.1452, station1.latitude, 0.0001)
        assertEquals(106.1553, station1.longitude, 0.0001)
        assertEquals(4, station1.totalAvailablePlugs) // 2 + 2
        assertEquals(6, station1.totalPlugs) // 4 + 2
        assertTrue(station1.hasLiveTelemetry)

        assertEquals(2, station1.powers.size)
        val p1 = station1.powers[0]
        assertEquals("30kW", p1.label)
        assertEquals(2, p1.availablePlugs)
        assertEquals(4, p1.totalPlugs)
        assertEquals("30kW: trống 2/4", p1.chipDisplayString)

        val p2 = station1.powers[1]
        assertEquals("20kW", p2.label)
        assertEquals(2, p2.availablePlugs)
        assertEquals(2, p2.totalPlugs)
        assertEquals("20kW: trống 2/2", p2.chipDisplayString)

        // Status badge: Available plugs > 0 -> "Hoạt động"
        val badge1 = resolveStatusBadge(station1.depotStatus, station1.totalAvailablePlugs, station1.totalPlugs)
        assertEquals("Hoạt động", badge1.label)

        // 3b. Verify Station 2 (Bắc Ninh - Tùng Bách): Verified fully occupied station -> "Hết cổng"
        val station2 = stations.first { it.id == "C.BNI0311" }
        assertEquals(0, station2.totalAvailablePlugs)
        assertEquals(4, station2.totalPlugs)
        assertTrue(station2.hasLiveTelemetry)
        val badge2 = resolveStatusBadge(station2.depotStatus, station2.totalAvailablePlugs, station2.totalPlugs)
        assertEquals("Hết cổng", badge2.label)

        // 3c. Verify Station 3 (Cần Thơ): Enriched across distant province
        val station3 = stations.first { it.id == "C.RAB0045" }
        assertEquals(10.0345, station3.latitude, 0.0001)
        assertEquals(105.7890, station3.longitude, 0.0001)
        assertEquals(4, station3.totalAvailablePlugs) // 3 + 1
        assertEquals(6, station3.totalPlugs) // 4 + 2

        // Verify Search Request Batching:
        // Even with 3 stations in total, Bắc Ninh stations are within 15km of each other,
        // so only 2 search requests were executed: 1 for Bắc Ninh cluster, 1 for Cần Thơ cluster!
        val searchRequests = recordedRequests.filter { it.path?.startsWith("/search") == true }
        assertEquals("Must execute exactly 2 targeted search requests for 2 clusters", 2, searchRequests.size)

        // =========================================================================
        // PART 4: Persistent Coordinate Caching Verification
        // =========================================================================

        // Verify coordinates were persisted in SessionStorage under KEY_COORDINATE_CACHE
        val cachedCoordJson = persistentStorage.getString(EvcsRepository.KEY_COORDINATE_CACHE)
        assertNotNull("Coordinates must be saved persistently", cachedCoordJson)
        assertTrue(cachedCoordJson!!.contains("c.bni0012"))
        assertTrue(cachedCoordJson.contains("c.rab0045"))

        // Create a brand new repository instance with the same storage (simulating app restart)
        val restoredRepository = EvcsRepository(
            apiClient = apiClient,
            cacheStorage = persistentStorage
        )

        // Cached coordinates must be immediately available without HTML fetching
        val restoredCache = restoredRepository.getCachedCoordinates()
        assertEquals(3, restoredCache.size)
        assertEquals(Pair(21.1452, 106.1553), restoredCache["c.bni0012"])
        assertEquals(Pair(10.0345, 105.7890), restoredCache["c.rab0045"])
    }
}
