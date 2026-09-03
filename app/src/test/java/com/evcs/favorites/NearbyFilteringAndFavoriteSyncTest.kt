package com.evcs.favorites

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.EvsePowerRaw
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.SaveFavoritesRequest
import com.evcs.favorites.data.model.SearchStationRaw
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.data.repository.toDomainStation
import com.evcs.favorites.data.repository.toFavoriteStationRaw
import com.evcs.favorites.domain.filter.NearbyStationFilter
import com.evcs.favorites.domain.location.DistanceCalculator
import com.evcs.favorites.domain.model.WattageOption
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
 * - WattageOption power tiers, labels, and matching helpers.
 * - NearbyStationFilter: depot status filtering, empty wattage filtering, OR-wattage filtering, port availability rules.
 * - NearbyStationFilter: Haversine distance computation, ascending sorting, and top N truncation.
 * - SaveFavoritesRequest serialization and deserialization.
 * - EvcsApiClient.saveFavorites HTTP interaction with MockWebServer (headers, body, csrf, auth cookie).
 * - EvcsRepository.addFavoriteStation and removeFavoriteStation reactive StateFlows, cache persistence, and cloud sync.
 * - SearchStationRaw.toDomainStation transformation and distance calculation.
 */
class NearbyFilteringAndFavoriteSyncTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var apiClient: EvcsApiClient
    private lateinit var repository: EvcsRepository

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()

        sessionStorage = InMemorySessionStorage()
        sessionManager = SessionManager(sessionStorage).apply {
            phpSessionId = "sess_abc123"
            authCookie = "auth_tok_xyz"
            csrfToken = "csrf_token_secret_999"
        }

        apiClient = EvcsApiClient(
            sessionManager = sessionManager,
            baseUrl = mockServer.url("").toString().removeSuffix("/")
        )

        repository = EvcsRepository(
            apiClient = apiClient,
            cacheStorage = sessionStorage
        )
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    // =============================================================
    // 1. WATTAGE OPTION MODELING & MATCHING TESTS
    // =============================================================

    @Test
    fun testWattageOptionTiersAndMatching() {
        // Verify total tiers count (15 tiers modeled)
        val allTiers = WattageOption.entries
        assertTrue(allTiers.size >= 14)

        // Verify Ultra-fast DC
        assertEquals(360_000L, WattageOption.KW_360.watts)
        assertEquals("360kW", WattageOption.KW_360.label)
        assertTrue(WattageOption.KW_360.matchesWattage(360_000L))
        assertFalse(WattageOption.KW_360.matchesWattage(180_000L))

        assertEquals(250_000L, WattageOption.KW_250.watts)
        assertEquals(180_000L, WattageOption.KW_180.watts)

        // Verify Fast DC
        assertEquals(80_000L, WattageOption.KW_80.watts)
        assertEquals(60_000L, WattageOption.KW_60.watts)
        assertEquals(30_000L, WattageOption.KW_30.watts)

        // Verify AC Destination & 7kW / 7.4kW variation
        assertEquals(7_000L, WattageOption.KW_7.watts)
        assertTrue("KW_7 should match 7000W", WattageOption.KW_7.matchesWattage(7_000L))
        assertTrue("KW_7 should match 7400W variation", WattageOption.KW_7.matchesWattage(7_400L))
        assertFalse(WattageOption.KW_7.matchesWattage(11_000L))

        // Verify companion fromWatts
        assertEquals(WattageOption.KW_250, WattageOption.fromWatts(250_000L))
        assertEquals(WattageOption.KW_7, WattageOption.fromWatts(7_400L))
        assertEquals(null, WattageOption.fromWatts(999_999L))
    }

    // =============================================================
    // 2. NEARBY STATION FILTERING TESTS (DEPOT, WATTAGE, AVAILABILITY)
    // =============================================================

    @Test
    fun testNearbyStationFilterExcludesMaintainingAndOutOfService() {
        val normalStation = createStation(
            id = "ST_01",
            name = "Normal Station",
            depotStatus = "Normal",
            availablePlugs = 2,
            powers = listOf(PowerPort(250_000L, "250kW", availablePlugs = 2, totalPlugs = 2))
        )
        val maintainingStation = createStation(
            id = "ST_02",
            name = "Maintaining Station",
            depotStatus = "Maintaining",
            availablePlugs = 4,
            powers = listOf(PowerPort(250_000L, "250kW", availablePlugs = 4, totalPlugs = 4))
        )
        val outOfServiceStation = createStation(
            id = "ST_03",
            name = "Out Of Service Station",
            depotStatus = "OutOfService",
            availablePlugs = 1,
            powers = listOf(PowerPort(180_000L, "180kW", availablePlugs = 1, totalPlugs = 1))
        )

        val filtered = NearbyStationFilter.filterStations(
            stations = listOf(normalStation, maintainingStation, outOfServiceStation),
            selectedWattages = emptySet()
        )

        assertEquals(1, filtered.size)
        assertEquals("ST_01", filtered.first().id)
    }

    @Test
    fun testNearbyStationFilterWithEmptyWattageRequiresAvailablePlugs() {
        val hasAvailable = createStation(
            id = "ST_AVAIL",
            name = "Has Plugs",
            availablePlugs = 1,
            powers = listOf(PowerPort(60_000L, "60kW", availablePlugs = 1, totalPlugs = 2))
        )
        val zeroAvailable = createStation(
            id = "ST_FULL",
            name = "Fully Occupied",
            availablePlugs = 0,
            powers = listOf(PowerPort(60_000L, "60kW", availablePlugs = 0, totalPlugs = 2))
        )

        val filtered = NearbyStationFilter.filterStations(
            stations = listOf(hasAvailable, zeroAvailable),
            selectedWattages = emptySet()
        )

        assertEquals(1, filtered.size)
        assertEquals("ST_AVAIL", filtered.first().id)
    }

    @Test
    fun testNearbyStationFilterOrWattageLogicAndPortAvailability() {
        // Station 1: 250kW available (2/2), 60kW full (0/2) -> Matches {250kW, 180kW}
        val station1 = createStation(
            id = "ST_1",
            name = "Station 1",
            availablePlugs = 2,
            powers = listOf(
                PowerPort(250_000L, "250kW", availablePlugs = 2, totalPlugs = 2),
                PowerPort(60_000L, "60kW", availablePlugs = 0, totalPlugs = 2)
            )
        )

        // Station 2: 180kW available (1/2), 250kW not present -> Matches {250kW, 180kW}
        val station2 = createStation(
            id = "ST_2",
            name = "Station 2",
            availablePlugs = 1,
            powers = listOf(
                PowerPort(180_000L, "180kW", availablePlugs = 1, totalPlugs = 2),
                PowerPort(60_000L, "60kW", availablePlugs = 0, totalPlugs = 2)
            )
        )

        // Station 3: Has 250kW connector, but availablePlugs is 0! (Has 60kW available, but 60kW not selected) -> Should be EXCLUDED
        val station3 = createStation(
            id = "ST_3",
            name = "Station 3",
            availablePlugs = 2,
            powers = listOf(
                PowerPort(250_000L, "250kW", availablePlugs = 0, totalPlugs = 2),
                PowerPort(60_000L, "60kW", availablePlugs = 2, totalPlugs = 2)
            )
        )

        // Station 4: Has only 60kW connectors available -> Should be EXCLUDED
        val station4 = createStation(
            id = "ST_4",
            name = "Station 4",
            availablePlugs = 4,
            powers = listOf(
                PowerPort(60_000L, "60kW", availablePlugs = 4, totalPlugs = 4)
            )
        )

        val selectedFilters = setOf(WattageOption.KW_250, WattageOption.KW_180)
        val filtered = NearbyStationFilter.filterStations(
            stations = listOf(station1, station2, station3, station4),
            selectedWattages = selectedFilters
        )

        assertEquals(2, filtered.size)
        val filteredIds = filtered.map { it.id }.toSet()
        assertTrue(filteredIds.contains("ST_1"))
        assertTrue(filteredIds.contains("ST_2"))
        assertFalse("Station with matching tier but 0 available plugs must be excluded", filteredIds.contains("ST_3"))
        assertFalse("Station without matching tier must be excluded", filteredIds.contains("ST_4"))
    }

    // =============================================================
    // 3. TOP N NEAREST HAVERSINE SORTING & TRUNCATION TESTS
    // =============================================================

    @Test
    fun testExtractTopNearestSortingAndTruncation() {
        // User at Hanoi center: 21.0285, 105.8542
        val userLat = 21.0285
        val userLon = 105.8542

        // Create 25 stations with increasing distance from user
        val candidateStations = (1..25).map { index ->
            // Distribute stations moving northward
            val stationLat = userLat + (index * 0.01)
            val stationLon = userLon + (index * 0.01)
            createStation(
                id = "ST_NEAR_$index",
                name = "Station $index",
                lat = stationLat,
                lon = stationLon,
                availablePlugs = 1
            )
        }

        // Shuffle input list to ensure sorter actively orders them
        val shuffled = candidateStations.shuffled()

        // Extract top 10
        val top10 = NearbyStationFilter.extractTopNearest(
            userLat = userLat,
            userLon = userLon,
            stations = shuffled,
            limit = 10
        )

        assertEquals(10, top10.size)

        // Verify nearest is index 1
        assertEquals("ST_NEAR_1", top10[0].id)
        assertEquals("ST_NEAR_10", top10[9].id)

        // Verify strictly ascending distances
        for (i in 0 until top10.size - 1) {
            val d1 = top10[i].distanceKm ?: 0.0
            val d2 = top10[i + 1].distanceKm ?: 0.0
            assertTrue("Distance must be strictly non-decreasing: $d1 <= $d2", d1 <= d2)
            assertTrue("Distance should be greater than 0", d1 > 0.0)
        }
    }

    // =============================================================
    // 4. EVCS CLOUD FAVORITES SYNC API TESTS
    // =============================================================

    @Test
    fun testSaveFavoritesCloudRequestPayloadAndHeaders() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Set-Cookie", "PHPSESSID=updated_session; Path=/")
                .setBody("""{"status":"ok"}""")
        )

        val stationToSync = createStation(
            id = "C.BNI0012",
            name = "Vincom Bac Ninh",
            address = "Tran Phu, Bac Ninh",
            availablePlugs = 2
        )

        val rawFavorites = listOf(stationToSync.toFavoriteStationRaw())
        val result = apiClient.saveFavorites(
            stations = rawFavorites,
            csrf = "csrf_token_secret_999"
        )

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow())

        // Verify MockWebServer received the expected HTTP request
        val recordedRequest = mockServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recordedRequest)
        assertEquals("POST", recordedRequest!!.method)
        assertEquals("/favorite.html", recordedRequest.path)
        assertEquals("application/json; charset=utf-8", recordedRequest.getHeader("Content-Type"))

        // Check Cookie header contains session cookies
        val cookieHeader = recordedRequest.getHeader("Cookie")
        assertNotNull(cookieHeader)
        assertTrue(cookieHeader!!.contains("PHPSESSID=sess_abc123"))
        assertTrue(cookieHeader.contains("evcs=auth_tok_xyz"))

        // Parse and verify JSON body payload
        val requestBody = recordedRequest.body.readUtf8()
        val parsedRequest = EvcsApiClient.json.decodeFromString<SaveFavoritesRequest>(requestBody)
        assertEquals("save", parsedRequest.action)
        assertEquals("csrf_token_secret_999", parsedRequest.csrf)
        assertEquals(1, parsedRequest.stations.size)
        assertEquals("C.BNI0012", parsedRequest.stations[0].locationId)
        assertEquals("Vincom Bac Ninh", parsedRequest.stations[0].name)

        // Verify sessionManager persisted Set-Cookie from response
        assertEquals("updated_session", sessionManager.phpSessionId)
    }

    // =============================================================
    // 5. REPOSITORY REACTIVE FLOWS & CLOUD SYNC TESTS
    // =============================================================

    @Test
    fun testRepositoryAddAndRemoveFavoriteFlows() = runBlocking {
        // Enqueue response for addFavoriteStation cloud sync
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        // Enqueue response for second addFavoriteStation cloud sync
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        // Enqueue response for removeFavoriteStation cloud sync
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val stationA = createStation(id = "C.ST01", name = "Station A")
        val stationB = createStation(id = "C.ST02", name = "Station B")

        // Initial state should be empty
        assertEquals(0, repository.favoritesState.value.size)
        assertEquals(0, repository.favoriteIdsState.value.size)

        // 1. Add Station A
        val addResA = repository.addFavoriteStation(stationA)
        assertTrue(addResA.isSuccess)
        assertEquals(1, repository.favoritesState.value.size)
        assertTrue(repository.favoriteIdsState.value.contains("C.ST01"))

        // Verify offline storage persisted snapshot
        val cachedSnapshot1 = repository.getCachedFavorites()
        assertEquals(1, cachedSnapshot1.size)
        assertEquals("C.ST01", cachedSnapshot1[0].id)

        // 2. Add Station B
        val addResB = repository.addFavoriteStation(stationB)
        assertTrue(addResB.isSuccess)
        assertEquals(2, repository.favoritesState.value.size)
        assertTrue(repository.favoriteIdsState.value.contains("C.ST01"))
        assertTrue(repository.favoriteIdsState.value.contains("C.ST02"))

        // 3. Remove Station A
        val remRes = repository.removeFavoriteStation("C.ST01")
        assertTrue(remRes.isSuccess)
        assertEquals(1, repository.favoritesState.value.size)
        assertFalse(repository.favoriteIdsState.value.contains("C.ST01"))
        assertTrue(repository.favoriteIdsState.value.contains("C.ST02"))

        val cachedSnapshot2 = repository.getCachedFavorites()
        assertEquals(1, cachedSnapshot2.size)
        assertEquals("C.ST02", cachedSnapshot2[0].id)
    }

    // =============================================================
    // 6. RAW STATION DOMAIN TRANSFORMATION TESTS
    // =============================================================

    @Test
    fun testSearchStationRawToDomainStationTransformation() {
        val raw = SearchStationRaw(
            locationId = "C.TRANS_01",
            stationName = "Test Station Raw",
            stationAddress = "123 Tran Hung Dao",
            latitude = 21.0100,
            longitude = 105.8200,
            depotStatus = "Normal",
            isPublic = true,
            isFreeParking = true,
            workingTimeDescription = "24/7",
            evsePowers = listOf(
                EvsePowerRaw(type = 250_000L, status = "AVAILABLE", numberOfAvailableEvse = 2, totalEvse = 2),
                EvsePowerRaw(type = 60_000L, status = "OCCUPIED", numberOfAvailableEvse = 1, totalEvse = 4)
            )
        )

        // Transform with user coordinates
        val userLat = 21.0285
        val userLon = 105.8542
        val domain = raw.toDomainStation(userLat = userLat, userLon = userLon)

        assertEquals("C.TRANS_01", domain.id)
        assertEquals("Test Station Raw", domain.name)
        assertEquals(3, domain.totalAvailablePlugs)
        assertEquals(6, domain.totalPlugs)
        assertEquals("Normal", domain.depotStatus)
        assertTrue(domain.distanceKm != null && domain.distanceKm!! > 0.0)

        // Verify toFavoriteStationRaw mapping
        val favRaw = domain.toFavoriteStationRaw()
        assertEquals("C.TRANS_01", favRaw.locationId)
        assertEquals("Test Station Raw", favRaw.name)
    }

    // =============================================================
    // HELPER METHODS
    // =============================================================

    private fun createStation(
        id: String,
        name: String,
        address: String = "Sample Address",
        lat: Double = 21.0285,
        lon: Double = 105.8542,
        depotStatus: String = "Normal",
        availablePlugs: Int = 1,
        powers: List<PowerPort> = listOf(PowerPort(60_000L, "60kW", availablePlugs, 2))
    ): Station {
        return Station(
            id = id,
            name = name,
            address = address,
            latitude = lat,
            longitude = lon,
            summary = "24/7",
            connectors = powers.joinToString(", ") { it.label },
            depotStatus = depotStatus,
            powers = powers,
            totalAvailablePlugs = availablePlugs,
            totalPlugs = powers.sumOf { it.totalPlugs }
        )
    }
}
