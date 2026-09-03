package com.evcs.favorites

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.crypto.EvcsHmacSigner
import com.evcs.favorites.data.model.FavoritesResponse
import com.evcs.favorites.data.model.SearchResponse
import com.evcs.favorites.data.model.SearchStationRaw
import com.evcs.favorites.data.repository.EvcsRepository
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive verification test for Phase 02:
 * 1. HMAC-SHA256 signing against verified test vectors and header generation
 * 2. JSON deserialization of `/favorite.html` partial response and `/search` response
 * 3. MockWebServer network interaction for EvcsApiClient
 * 4. EvcsRepository data merging matching locationId across favorites and search data
 * 5. Plug availability calculation (available and total plugs)
 * 6. Graceful degradation when search API fails or station is outside search radius
 * 7. Coordinate caching and custom coordinate resolver fallback
 */
class EvcsRepositoryTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
        sessionStorage = InMemorySessionStorage()
        sessionManager = SessionManager(sessionStorage).apply {
            phpSessionId = "test_php_session"
            authCookie = "test_auth_cookie_value"
            csrfToken = "test_csrf_token"
        }
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    // -------------------------------------------------------------
    // 1. HMAC-SHA256 SIGNING & HEADER TESTS
    // -------------------------------------------------------------

    @Test
    fun testHmacSha256SigningWithKnownTestVector() {
        // Known verified test vector:
        // Key: EvcsHmacSigner.SECRET_KEY
        // Body: "{\"latitude\":21.0,\"longitude\":105.8}"
        // Timestamp: "1725330000000"
        // Expected HmacSHA256 lowercase hex: "1badb1fcb193889b6baae5fe72eb3437b887bad0f8041123de03aadd8ec45834"
        val body = "{\"latitude\":21.0,\"longitude\":105.8}"
        val timestamp = "1725330000000"
        val expectedSignature = "1badb1fcb193889b6baae5fe72eb3437b887bad0f8041123de03aadd8ec45834"

        val computedSignature = EvcsHmacSigner.sign(body, timestamp)
        assertEquals(expectedSignature, computedSignature)

        // Verify signed headers generation
        val headers = EvcsHmacSigner.createSignedHeaders(body, timestamp)
        assertEquals("EVCS/A1.57", headers["User-Agent"])
        assertEquals(timestamp, headers["X-App-Timestamp"])
        assertEquals(expectedSignature, headers["X-App-Signature"])
        assertEquals("https://evcs.vn/", headers["Referer"])
        assertEquals("https://evcs.vn", headers["Origin"])
        assertEquals("application/json", headers["Content-Type"])
        assertEquals("application/json", headers["Accept"])
    }

    // -------------------------------------------------------------
    // 2. JSON DESERIALIZATION TESTS
    // -------------------------------------------------------------

    @Test
    fun testFavoritesResponseDeserialization() {
        val favoritesJson = """
        {
          "sync": true,
          "csrf": "ada7d6f165a5f60411323069452798e4",
          "server": [
            {
              "locationId": "C.BNI0012",
              "name": "VinFast - TTTM Dabaco Mart Quế Võ",
              "address": "Bãi đỗ xe ngoài trời, thị trấn Phố Mới, Bắc Ninh",
              "summary": "Mở 24/7 • Công cộng • Gửi xe tính phí",
              "connectors": "30kW, 20kW, 3.5kW",
              "image": "https://media.evcs.vn/stations/dabaco.jpg"
            },
            {
              "locationId": "C.BNI0311",
              "name": "VinFast - KĐT Tùng Bách",
              "address": "Sân bóng KĐT Tùng Bách, Quế Võ, Bắc Ninh",
              "summary": "Mở 24/7 • Công cộng • Miễn phí đỗ xe",
              "connectors": "120kW, 60kW",
              "image": "https://media.evcs.vn/stations/tungbach.jpg"
            }
          ],
          "limits": {
            "fav": 10,
            "notifyFree": 0,
            "tier": "none"
          }
        }
        """.trimIndent()

        val response = EvcsApiClient.json.decodeFromString<FavoritesResponse>(favoritesJson)

        assertTrue(response.sync)
        assertEquals("ada7d6f165a5f60411323069452798e4", response.csrf)
        assertEquals(2, response.server?.size)
        assertEquals(10, response.limits?.fav)

        val first = response.server!![0]
        assertEquals("C.BNI0012", first.locationId)
        assertEquals("VinFast - TTTM Dabaco Mart Quế Võ", first.name)
        assertEquals("30kW, 20kW, 3.5kW", first.connectors)
    }

    @Test
    fun testSearchResponseDeserialization() {
        val searchJson = """
        {
          "code": 200000,
          "data": [
            {
              "locationId": "C.BNI0012",
              "stationName": "VinFast - TTTM Dabaco Mart Quế Võ",
              "stationAddress": "Bãi đỗ xe ngoài trời, thị trấn Phố Mới, Bắc Ninh",
              "latitude": 21.1452,
              "longitude": 106.1553,
              "depotStatus": "Normal",
              "evsePowers": [
                {
                  "type": 60000,
                  "numberOfAvailableEvse": 1,
                  "totalEvse": 2
                },
                {
                  "type": 30000,
                  "numberOfAvailableEvse": 2,
                  "totalEvse": 2
                }
              ],
              "isPublic": true,
              "isFreeParking": false,
              "workingTimeDescription": "24/7"
            }
          ]
        }
        """.trimIndent()

        val response = EvcsApiClient.json.decodeFromString<SearchResponse>(searchJson)

        assertEquals(200000, response.code)
        assertNotNull(response.data)
        assertEquals(1, response.data!!.size)

        val station = response.data!![0]
        assertEquals("C.BNI0012", station.effectiveLocationId)
        assertEquals(21.1452, station.latitude, 0.0001)
        assertEquals(106.1553, station.longitude, 0.0001)
        assertEquals(2, station.evsePowers.size)

        val power1 = station.evsePowers[0].toDomainPowerPort()
        assertEquals("60kW", power1.label)
        assertEquals(1, power1.availablePlugs)
        assertEquals(2, power1.totalPlugs)
        assertEquals("60kW: trống 1/2 cổng", power1.displayString)

        val power2 = station.evsePowers[1].toDomainPowerPort()
        assertEquals("30kW", power2.label)
        assertEquals(2, power2.availablePlugs)
        assertEquals(2, power2.totalPlugs)
    }

    // -------------------------------------------------------------
    // 3. API CLIENT NETWORK CALLS WITH MOCKWEBSERVER
    // -------------------------------------------------------------

    @Test
    fun testEvcsApiClientNetworkCalls() = runBlocking {
        val baseUrl = mockServer.url("/").toString().removeSuffix("/")
        val client = EvcsApiClient(sessionManager, baseUrl = baseUrl)

        // Enqueue Mock Response for /favorite.html
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"sync":true,"csrf":"token123","server":[]}""")
        )

        val favResult = client.fetchFavorites()
        assertTrue(favResult.isSuccess)
        val favReq = mockServer.takeRequest()
        assertEquals("/favorite.html", favReq.path)
        assertEquals("fav", favReq.getHeader("X-Partial"))
        assertTrue(favReq.getHeader("Cookie")!!.contains("PHPSESSID=test_php_session"))
        assertTrue(favReq.getHeader("Cookie")!!.contains("evcs=test_auth_cookie_value"))

        // Enqueue Mock Response for /search?t=eepe5dp9zpipl102
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"code":200000,"data":[]}""")
        )

        val searchResult = client.searchStations(21.0, 105.8)
        assertTrue(searchResult.isSuccess)
        val searchReq = mockServer.takeRequest()
        assertEquals("/search?t=eepe5dp9zpipl102", searchReq.path)
        assertEquals("EVCS/A1.57", searchReq.getHeader("User-Agent"))
        assertNotNull(searchReq.getHeader("X-App-Timestamp"))
        assertNotNull(searchReq.getHeader("X-App-Signature"))
    }

    // -------------------------------------------------------------
    // 4. REPOSITORY DATA MERGING & PLUG CALCULATION
    // -------------------------------------------------------------

    @Test
    fun testRepositoryDataMergingAndPlugCalculation() = runBlocking {
        val baseUrl = mockServer.url("/").toString().removeSuffix("/")
        val client = EvcsApiClient(sessionManager, baseUrl = baseUrl)
        val coordinateCache = mutableMapOf<String, Pair<Double, Double>>()
        val repository = EvcsRepository(client, coordinateCache = coordinateCache)

        // Mock 1: /favorite.html response with 2 stations
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "sync": true,
                      "csrf": "csrf123",
                      "server": [
                        {
                          "locationId": "C.BNI0012",
                          "name": "VinFast - TTTM Dabaco Mart Quế Võ",
                          "address": "Phố Mới, Quế Võ",
                          "summary": "Mở 24/7",
                          "connectors": "60kW, 30kW",
                          "image": "https://media.evcs.vn/dabaco.jpg"
                        },
                        {
                          "locationId": "C.BNI0311",
                          "name": "VinFast - KĐT Tùng Bách",
                          "address": "Tùng Bách, Quế Võ",
                          "summary": "Mở 24/7",
                          "connectors": "120kW, 60kW",
                          "image": null
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )

        // Mock 2: /search response containing details for C.BNI0012 only
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "code": 200000,
                      "data": [
                        {
                          "locationId": "C.BNI0012",
                          "stationName": "VinFast - TTTM Dabaco Mart Quế Võ Enriched",
                          "stationAddress": "Phố Mới, Quế Võ Enriched",
                          "latitude": 21.1452,
                          "longitude": 106.1553,
                          "depotStatus": "Normal",
                          "evsePowers": [
                            {
                              "type": 60000,
                              "numberOfAvailableEvse": 1,
                              "totalEvse": 2
                            },
                            {
                              "type": 30000,
                              "numberOfAvailableEvse": 1,
                              "totalEvse": 1
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
        )

        val result = repository.getFavorites(userLat = 21.0, userLon = 105.8)
        assertTrue(result.isSuccess)
        val stations = result.getOrThrow()

        assertEquals(2, stations.size)

        // Station 1: Enriched with search data
        val station1 = stations.first { it.id == "C.BNI0012" }
        assertEquals("VinFast - TTTM Dabaco Mart Quế Võ Enriched", station1.name)
        assertEquals(21.1452, station1.latitude, 0.0001)
        assertEquals(106.1553, station1.longitude, 0.0001)
        assertEquals("Normal", station1.depotStatus)
        assertEquals(2, station1.powers.size)
        assertEquals(2, station1.totalAvailablePlugs) // 1 + 1
        assertEquals(3, station1.totalPlugs) // 2 + 1
        assertEquals("https://media.evcs.vn/dabaco.jpg", station1.image)

        // Verify coordinates were stored into coordinateCache
        assertEquals(Pair(21.1452, 106.1553), coordinateCache["c.bni0012"])

        // Station 2: Outside search radius - fallback metadata
        val station2 = stations.first { it.id == "C.BNI0311" }
        assertEquals("VinFast - KĐT Tùng Bách", station2.name)
        assertEquals("Unknown", station2.depotStatus)
        assertEquals(0, station2.totalAvailablePlugs)
        assertEquals(0.0, station2.latitude, 0.0001)
        assertEquals(0.0, station2.longitude, 0.0001)
        assertEquals("120kW, 60kW", station2.connectors)
        assertEquals(2, station2.powers.size)
    }

    // -------------------------------------------------------------
    // 5. GRACEFUL DEGRADATION WHEN SEARCH API FAILS
    // -------------------------------------------------------------

    @Test
    fun testRepositoryGracefulDegradationWhenSearchFails() = runBlocking {
        val baseUrl = mockServer.url("/").toString().removeSuffix("/")
        val client = EvcsApiClient(sessionManager, baseUrl = baseUrl)
        val repository = EvcsRepository(client)

        // Mock 1: /favorite.html returns valid list
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "sync": true,
                      "csrf": "csrf123",
                      "server": [
                        {
                          "locationId": "C.BNI0012",
                          "name": "VinFast - TTTM Dabaco Mart Quế Võ",
                          "address": "Bắc Ninh",
                          "connectors": "60kW"
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )

        // Mock 2: /search returns 500 Internal Server Error
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"error":"Internal Server Error"}""")
        )

        val result = repository.getFavorites(userLat = 21.0, userLon = 105.8)

        // Repository MUST NOT crash; it degrades gracefully
        assertTrue(result.isSuccess)
        val stations = result.getOrThrow()
        assertEquals(1, stations.size)
        assertEquals("VinFast - TTTM Dabaco Mart Quế Võ", stations[0].name)
        assertEquals("Unknown", stations[0].depotStatus)
        assertEquals(0, stations[0].totalAvailablePlugs)
    }

    // -------------------------------------------------------------
    // 6. COORDINATE CACHE & RESOLVER FALLBACK
    // -------------------------------------------------------------

    @Test
    fun testCoordinateCacheAndResolverFallback() = runBlocking {
        val baseUrl = mockServer.url("/").toString().removeSuffix("/")
        val client = EvcsApiClient(sessionManager, baseUrl = baseUrl)

        // Pre-populated cache and custom resolver
        val preCache = mutableMapOf(
            "c.bni0012" to Pair(21.145, 106.155)
        )
        val resolver: (String) -> Pair<Double, Double>? = { id ->
            if (id == "C.BNI0311") Pair(21.200, 106.200) else null
        }

        val repository = EvcsRepository(
            apiClient = client,
            coordinateCache = preCache,
            coordinateResolver = resolver
        )

        // Mock favorites response
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "sync": true,
                      "csrf": "csrf123",
                      "server": [
                        {
                          "locationId": "C.BNI0012",
                          "name": "Station 1",
                          "address": "Address 1",
                          "connectors": "60kW"
                        },
                        {
                          "locationId": "C.BNI0311",
                          "name": "Station 2",
                          "address": "Address 2",
                          "connectors": "120kW"
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )

        // Empty search response
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"code":200000,"data":[]}""")
        )

        val result = repository.getFavorites(userLat = null, userLon = null)
        assertTrue(result.isSuccess)
        val stations = result.getOrThrow()

        val s1 = stations.first { it.id == "C.BNI0012" }
        assertEquals(21.145, s1.latitude, 0.001)
        assertEquals(106.155, s1.longitude, 0.001)

        val s2 = stations.first { it.id == "C.BNI0311" }
        assertEquals(21.200, s2.latitude, 0.001)
        assertEquals(106.200, s2.longitude, 0.001)
    }
}
