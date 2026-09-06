package com.evcs.favorites

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.navigation.MapNavigator
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Single Comprehensive Verification Test for Phase 05: Navigation & End-to-End Integration.
 *
 * Validates:
 * 1. `MapNavigator` URI string generation for standard coordinates, encoded Vietnamese labels,
 *    Google Maps navigation protocols, and web browser fallbacks.
 * 2. Intent specifications verifying `ACTION_VIEW` action types and Google Maps package targeting.
 * 3. 3-Tier fallback dispatch sequence: Google Maps -> Generic Geo -> Web Browser -> Graceful safe degrade.
 * 4. Offline cache snapshot persistence in repository and reload when network is disconnected.
 * 5. Dynamic GPS distance computation over reloaded offline station snapshots.
 * 6. Cache eviction and offline failure behavior after cache clearing.
 */
class MapIntentIntegrationTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var baseUrl: String
    private lateinit var apiClient: EvcsApiClient
    private lateinit var repository: EvcsRepository

    private val sampleFavoritesJson = """
    {
      "sync": true,
      "csrf": "csrf_test_fav_05",
      "server": [
        {
          "locationId": "C.BNI0012",
          "name": "VinFast - TTTM Dabaco Mart Quế Võ",
          "address": "Bãi đỗ xe ngoài trời, Phố Mới, Bắc Ninh",
          "summary": "Mở 24/7 • Gửi xe tính phí",
          "connectors": "60kW, 30kW",
          "image": "https://media.evcs.vn/dabaco.jpg"
        },
        {
          "locationId": "C.HNI0099",
          "name": "VinFast - Royal City Hà Nội",
          "address": "72A Nguyễn Trãi, Thanh Xuân, Hà Nội",
          "summary": "Mở 24/7 • Miễn phí đỗ xe",
          "connectors": "120kW, 60kW",
          "image": null
        }
      ],
      "limits": {
        "fav": 10,
        "notifyFree": 0
      }
    }
    """.trimIndent()

    private val sampleSearchJson = """
    {
      "code": 200000,
      "data": [
        {
          "locationId": "C.BNI0012",
          "stationName": "VinFast - TTTM Dabaco Mart Quế Võ",
          "stationAddress": "Bãi đỗ xe ngoài trời, Phố Mới, Bắc Ninh",
          "latitude": 21.1452,
          "longitude": 106.1553,
          "depotStatus": "Normal",
          "evsePowers": [
            {
              "type": 60000,
              "numberOfAvailableEvse": 3,
              "totalEvse": 4
            },
            {
              "type": 30000,
              "numberOfAvailableEvse": 1,
              "totalEvse": 2
            }
          ],
          "isPublic": true,
          "isFreeParking": false,
          "workingTimeDescription": "24/7"
        },
        {
          "locationId": "C.HNI0099",
          "stationName": "VinFast - Royal City Hà Nội",
          "stationAddress": "72A Nguyễn Trãi, Thanh Xuân, Hà Nội",
          "latitude": 21.0031,
          "longitude": 105.8155,
          "depotStatus": "Normal",
          "evsePowers": [
            {
              "type": 120000,
              "numberOfAvailableEvse": 2,
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

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()

        sessionStorage = InMemorySessionStorage()
        sessionManager = SessionManager(sessionStorage).apply {
            authCookie = "mock_auth_cookie_phase05"
            phpSessionId = "mock_phpsessid_phase05"
            csrfToken = "mock_csrf_phase05"
        }

        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()

        baseUrl = mockServer.url("/").toString().removeSuffix("/")

        apiClient = EvcsApiClient(
            sessionManager = sessionManager,
            client = okHttpClient,
            baseUrl = baseUrl
        )

        // Initialize repository with offline cache storage
        repository = EvcsRepository(
            apiClient = apiClient,
            cacheStorage = sessionStorage
        )
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    // =========================================================================
    // 1. MapNavigator URI String Generation Tests
    // =========================================================================

    @Test
    fun testMapNavigator_uriGeneration_coordinatesAndEncodedNames() {
        val lat = 21.0285
        val lon = 105.8542

        // A. Standard geo URI without station name
        val geoUriNoName = MapNavigator.buildGeoUriString(lat, lon, null)
        assertEquals("geo:21.0285,105.8542?q=21.0285,105.8542", geoUriNoName)

        val geoUriBlankName = MapNavigator.buildGeoUriString(lat, lon, "   ")
        assertEquals("geo:21.0285,105.8542?q=21.0285,105.8542", geoUriBlankName)

        // B. Standard geo URI with station name containing spaces & Unicode/Vietnamese characters
        val stationName = "VinFast - Royal City (Hà Nội)"
        val geoUriWithName = MapNavigator.buildGeoUriString(lat, lon, stationName)

        assertTrue(geoUriWithName.startsWith("geo:21.0285,105.8542?q=21.0285,105.8542("))
        assertTrue(geoUriWithName.endsWith(")"))

        // Extract and verify encoded parameter
        val encodedPart = geoUriWithName.substringAfter("(").substringBeforeLast(")")
        val decodedPart = URLDecoder.decode(encodedPart, StandardCharsets.UTF_8.name())
        assertEquals(stationName, decodedPart)
        assertFalse("Encoded string should not contain raw unencoded spaces", encodedPart.contains(" "))

        // C. Google Maps navigation intent URI
        val navUri = MapNavigator.buildGoogleNavigationUriString(lat, lon)
        assertEquals("google.navigation:q=21.0285,105.8542&mode=d", navUri)

        // D. Web browser fallback URL
        val browserUrl = MapNavigator.buildBrowserMapsUrl(lat, lon)
        assertEquals("https://www.google.com/maps/search/?api=1&query=21.0285,105.8542", browserUrl)
    }

    // =========================================================================
    // 2. Intent Action Types and Package Handling Specifications
    // =========================================================================

    @Test
    fun testMapNavigator_intentSpecificationsAndPackageTargeting() {
        val lat = 21.1452
        val lon = 106.1553

        // 1. Google Maps Spec: Action must be ACTION_VIEW, package com.google.android.apps.maps
        val gmapsSpec = MapNavigator.getGoogleMapsIntentSpec(lat, lon)
        assertEquals("android.intent.action.VIEW", gmapsSpec.action)
        assertEquals("com.google.android.apps.maps", gmapsSpec.packageName)
        assertEquals("google.navigation:q=21.1452,106.1553&mode=d", gmapsSpec.uriString)

        // 2. Generic Geo Spec: Action ACTION_VIEW, null package (allows chooser/any map app)
        val geoSpec = MapNavigator.getGeoIntentSpec(lat, lon, "VinFast Quế Võ")
        assertEquals("android.intent.action.VIEW", geoSpec.action)
        assertNull(geoSpec.packageName)
        assertTrue(geoSpec.uriString.startsWith("geo:21.1452,106.1553?q=21.1452,106.1553("))

        // 3. Browser Fallback Spec: Action ACTION_VIEW, null package (opens default browser)
        val browserSpec = MapNavigator.getBrowserIntentSpec(lat, lon)
        assertEquals("android.intent.action.VIEW", browserSpec.action)
        assertNull(browserSpec.packageName)
        assertEquals("https://www.google.com/maps/search/?api=1&query=21.1452,106.1553", browserSpec.uriString)
    }

    // =========================================================================
    // 3. Fallback Handling Logic
    // =========================================================================

    @Test
    fun testMapNavigator_fallbackChainExecution() {
        val dummyContext = DummyContext()
        val lat = 21.0285
        val lon = 105.8542
        val stationName = "VinFast Royal City"

        // Case A: Google Maps succeeds on first attempt
        var attemptsA = 0
        val successA = MapNavigator.navigate(
            context = dummyContext,
            latitude = lat,
            longitude = lon,
            stationName = stationName,
            intentLauncher = { _ ->
                attemptsA++
            }
        )
        assertTrue(successA)
        assertEquals(1, attemptsA)

        // Case B: Google Maps throws ActivityNotFoundException -> falls back to Geo Intent
        var attemptsB = 0
        val successB = MapNavigator.navigate(
            context = dummyContext,
            latitude = lat,
            longitude = lon,
            stationName = stationName,
            intentLauncher = { _ ->
                attemptsB++
                if (attemptsB == 1) {
                    throw ActivityNotFoundException("Google Maps missing")
                }
            }
        )
        assertTrue(successB)
        assertEquals(2, attemptsB)

        // Case C: Both Google Maps and Geo throw -> falls back to Browser Intent
        var attemptsC = 0
        val successC = MapNavigator.navigate(
            context = dummyContext,
            latitude = lat,
            longitude = lon,
            stationName = stationName,
            intentLauncher = { _ ->
                attemptsC++
                if (attemptsC <= 2) {
                    throw ActivityNotFoundException("No map app installed")
                }
            }
        )
        assertTrue(successC)
        assertEquals(3, attemptsC)

        // Case D: All launchers fail/throw -> safe degrade returning false without crashing
        var attemptsD = 0
        val successD = MapNavigator.navigate(
            context = dummyContext,
            latitude = lat,
            longitude = lon,
            stationName = stationName,
            intentLauncher = { _ ->
                attemptsD++
                throw RuntimeException("Fatal OS security policy exception")
            }
        )
        assertFalse(successD)
        assertEquals(3, attemptsD)
    }

    // =========================================================================
    // 4. Offline Cache Persistence and Reload without Network Connectivity
    // =========================================================================

    @Test
    fun testOfflineCache_persistenceAndReloadWithoutNetwork() = runBlocking {
        // Step 1: Verify cache is initially empty
        val initialCached = repository.getCachedFavorites()
        assertTrue("Initial cache should be empty", initialCached.isEmpty())

        // Step 2: Perform online fetch
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(sampleFavoritesJson))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(sampleSearchJson))

        val onlineResult = repository.getFavorites(userLat = 21.0285, userLon = 105.8542)
        assertTrue(onlineResult.isSuccess)
        val onlineStations = onlineResult.getOrThrow()
        assertEquals(2, onlineStations.size)

        // Verify cache snapshot is now populated in local storage
        val cachedAfterOnline = repository.getCachedFavorites()
        assertEquals(2, cachedAfterOnline.size)
        val cachedStation1 = cachedAfterOnline.first { it.id == "C.BNI0012" }
        assertEquals("VinFast - TTTM Dabaco Mart Quế Võ", cachedStation1.name)
        assertEquals(21.1452, cachedStation1.latitude, 0.001)
        assertEquals(106.1553, cachedStation1.longitude, 0.001)
        assertEquals(4, cachedStation1.totalAvailablePlugs) // 3 + 1
        assertEquals(6, cachedStation1.totalPlugs) // 4 + 2

        // Step 3: Simulate network disconnection / offline status (500 Server Error)
        mockServer.enqueue(MockResponse().setResponseCode(500).setBody("Network Down"))

        // Fetch again without network connectivity
        val offlineResult = repository.getFavorites(userLat = 21.0285, userLon = 105.8542)

        // Must succeed using offline cached snapshot
        assertTrue("Repository must gracefully return cached favorites offline", offlineResult.isSuccess)
        val offlineStations = offlineResult.getOrThrow()
        assertEquals(2, offlineStations.size)

        val reloadedDabaco = offlineStations.first { it.id == "C.BNI0012" }
        assertEquals("VinFast - TTTM Dabaco Mart Quế Võ", reloadedDabaco.name)
        assertEquals(4, reloadedDabaco.totalAvailablePlugs)
        assertNotNull(reloadedDabaco.distanceKm)
        assertTrue(reloadedDabaco.distanceKm!! > 0.0)

        // Step 4: Verify cache clearance behavior
        repository.clearOfflineCache()
        assertTrue(repository.getCachedFavorites().isEmpty())

        // Fetch when offline AND cache cleared -> must return failure
        mockServer.enqueue(MockResponse().setResponseCode(500).setBody("Server Unavailable"))
        val emptyCacheOfflineResult = repository.getFavorites()
        assertTrue("Must report failure when offline and cache is empty", emptyCacheOfflineResult.isFailure)
    }

    // =========================================================================
    // Dummy Context for Unit Testing Intent Dispatcher
    // =========================================================================

    private class DummyContext : android.content.ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun startActivity(intent: Intent?) {
            // No-op for testing
        }
    }
}
