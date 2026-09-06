package com.evcs.favorites

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.logging.AppDebugLogger
import com.evcs.favorites.data.logging.DebugLogLevel
import com.evcs.favorites.data.logging.DebugLogTag
import com.evcs.favorites.data.model.EvsePowerRaw
import com.evcs.favorites.data.model.SearchStationRaw
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.network.vinfast.VinFastApiException
import com.evcs.favorites.data.network.vinfast.VinFastCAppApiClient
import com.evcs.favorites.data.network.vinfast.VinFastConnectorDto
import com.evcs.favorites.data.network.vinfast.VinFastStationStatusDto
import com.evcs.favorites.data.repository.DualTierStationRepository
import com.evcs.favorites.data.repository.FirestoreFavoritesDataSource
import com.evcs.favorites.data.repository.FirestoreFavoritesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * File-based comprehensive test suite for Phase 03: Dual-Tier Station Repository & Fallback.
 * Validates:
 * 1. Tier 1 primary search success (tags VINFAST_DIRECT, 0 interactions with Tier 2).
 * 2. Tier 1 failure (HTTP 401/timeout) automated failover to Tier 2 EVCS aggregator (tags EVCS_FALLBACK, logs to AppDebugLogger).
 * 3. Tier 1 favorites enrichment with live telemetry (updates in-memory/cache, tags VINFAST_DIRECT).
 * 4. Graceful failure contract when both Tier 1 and Tier 2 fail (returns Result.failure without unhandled exceptions).
 * 5. Helper ergonomics (getStationsByIds, searchNearby) and motorbike port stripping.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DualTierStationRepositoryFallbackTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var fakeVinFastApiClient: FakeVinFastCAppApiClient
    private lateinit var fakeEvcsApiClient: FakeEvcsApiClient
    private lateinit var fakeFirestoreDataSource: FakeTestFirestoreDataSource
    private lateinit var firestoreFavoritesRepo: FirestoreFavoritesRepository
    private lateinit var repository: DualTierStationRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        AppDebugLogger.clear()

        sessionStorage = InMemorySessionStorage()
        sessionManager = SessionManager(sessionStorage).apply {
            phpSessionId = "test_php_session"
            authCookie = "test_auth_cookie"
            csrfToken = "test_csrf_token"
        }

        fakeVinFastApiClient = FakeVinFastCAppApiClient()
        fakeEvcsApiClient = FakeEvcsApiClient(sessionManager)
        fakeFirestoreDataSource = FakeTestFirestoreDataSource()

        firestoreFavoritesRepo = FirestoreFavoritesRepository(
            remoteDataSource = fakeFirestoreDataSource,
            localStorage = sessionStorage,
            ioDispatcher = testDispatcher
        )

        repository = DualTierStationRepository(
            apiClient = fakeEvcsApiClient,
            vinFastApiClient = fakeVinFastApiClient,
            cacheStorage = sessionStorage,
            ioDispatcher = testDispatcher,
            firestoreFavoritesRepository = firestoreFavoritesRepo
        )
    }

    @After
    fun tearDown() {
        AppDebugLogger.clear()
        Dispatchers.resetMain()
    }

    // -------------------------------------------------------------
    // 1. TIER 1 SEARCH SUCCESS
    // -------------------------------------------------------------

    @Test
    fun testTier1SearchSuccess_doesNotCallTier2() = runTest(testDispatcher) {
        val sampleDto = VinFastStationStatusDto(
            locationId = "C.HNO11417",
            stationName = "Vincom Center Long Biên",
            stationAddress = "Long Biên, Hà Nội",
            latitude = 21.0456,
            longitude = 105.9012,
            connectors = listOf(
                VinFastConnectorDto(type = 60000, count = 2, total = 4, powerType = "DC"),
                VinFastConnectorDto(type = 120000, count = 1, total = 2, powerType = "DC")
            ),
            depotStatus = "Normal",
            isPublic = true,
            isFreeParking = true,
            workingTimeDescription = "24/7"
        )
        fakeVinFastApiClient.searchStationsResult = Result.success(listOf(sampleDto))

        val result = repository.searchNearbyVinFast(21.0285, 105.8542)

        assertTrue("Result should be successful", result.isSuccess)
        val stations = result.getOrNull()
        assertNotNull("Stations list should not be null", stations)
        assertEquals(1, stations!!.size)

        val station = stations.first()
        assertEquals("C.HNO11417", station.id)
        assertEquals("VINFAST_DIRECT", station.sourceTier)
        assertEquals(3, station.totalAvailablePlugs)
        assertEquals(6, station.totalPlugs)

        // Verify Tier 2 was NEVER called (0 interactions)
        assertEquals(
            "EvcsApiClient.searchStations should never be called when Tier 1 succeeds",
            0,
            fakeEvcsApiClient.searchStationsCallCount
        )

        // Verify coordinates were cached
        val cachedCoords = repository.getCachedCoordinates()
        assertTrue("Coordinates must be saved to cache", cachedCoords.containsKey("c.hno11417"))
        assertEquals(21.0456, cachedCoords["c.hno11417"]!!.first, 0.0001)
    }

    // -------------------------------------------------------------
    // 2. TIER 1 FAILURE WITH 401/TIMEOUT -> FAILOVER TO TIER 2
    // -------------------------------------------------------------

    @Test
    fun testTier1SearchFailsWith401OrTimeout_seamlesslyFallsBackToTier2() = runTest(testDispatcher) {
        // Mock Tier 1 to fail with HTTP 401 Unauthorized
        fakeVinFastApiClient.searchStationsResult = Result.failure(
            VinFastApiException(code = 401, message = "Authenticate failed")
        )

        // Mock Tier 2 to succeed with search stations containing both car and bike plugs
        val fallbackRaw = SearchStationRaw(
            locationId = "C.FALLBACK1",
            stationName = "Trạm sạc VinFast Fallback EVCS",
            stationAddress = "Hoàn Kiếm, Hà Nội",
            latitude = 21.0285,
            longitude = 105.8542,
            evsePowers = listOf(
                EvsePowerRaw(type = 3500, numberOfAvailableEvse = 4, totalEvse = 4), // Motorbike -> must be stripped
                EvsePowerRaw(type = 60000, numberOfAvailableEvse = 2, totalEvse = 4, powerType = "DC") // Car -> preserved
            )
        )
        fakeEvcsApiClient.searchStationsResult = Result.success(listOf(fallbackRaw))

        val result = repository.searchNearbyVinFast(21.0285, 105.8542)

        assertTrue("Result should be successful via Tier 2 fallback", result.isSuccess)
        val stations = result.getOrNull()
        assertNotNull(stations)
        assertEquals(1, stations!!.size)

        val station = stations.first()
        assertEquals("C.FALLBACK1", station.id)
        assertEquals("EVCS_FALLBACK", station.sourceTier)

        // Verify motorbike plug was stripped
        assertEquals("Only car ports should remain", 1, station.powers.size)
        assertEquals(60000L, station.powers[0].typeWatts)
        assertEquals(2, station.totalAvailablePlugs)
        assertEquals(4, station.totalPlugs)

        // Verify Tier 2 was invoked exactly once
        assertEquals(1, fakeEvcsApiClient.searchStationsCallCount)

        // Verify fallback event was logged to AppDebugLogger
        val logs = AppDebugLogger.getLogs()
        val fallbackLog = logs.firstOrNull { log ->
            log.tag == DebugLogTag.SEARCH &&
                    log.level == DebugLogLevel.WARN &&
                    log.message.contains("falling back", ignoreCase = true)
        }
        assertNotNull("Fallback event must be logged to AppDebugLogger with WARN level", fallbackLog)
        assertTrue("Log should mention Tier 2", fallbackLog!!.message.contains("Tier 2"))
    }

    // -------------------------------------------------------------
    // 3. TIER 1 FAVORITES SUCCESS WITH LIVE TELEMETRY
    // -------------------------------------------------------------

    @Test
    fun testTier1FavoritesSuccess_updatesFavoritesWithLiveTelemetry() = runTest(testDispatcher) {
        val favoriteId = "C.HNO11417"

        // Setup favorite station in Firestore repository
        val initialStation = Station(
            id = favoriteId,
            name = "Vincom Center Long Biên",
            address = "Long Biên, Hà Nội",
            latitude = 21.0456,
            longitude = 105.9012,
            summary = "24/7",
            connectors = "60kW",
            depotStatus = "Unknown",
            powers = emptyList(),
            totalAvailablePlugs = 0,
            totalPlugs = 0,
            addedAt = 1700000000L
        )
        firestoreFavoritesRepo.addFavoriteStation(initialStation)

        // Mock Tier 1 getLocationInfo returning real-time enriched telemetry
        val liveDto = VinFastStationStatusDto(
            locationId = favoriteId,
            stationName = "Vincom Center Long Biên Live",
            stationAddress = "Long Biên, Hà Nội",
            latitude = 21.0456,
            longitude = 105.9012,
            connectors = listOf(
                VinFastConnectorDto(type = 60000, count = 3, total = 4, powerType = "DC"),
                VinFastConnectorDto(type = 250000, count = 2, total = 2, powerType = "DC")
            ),
            depotStatus = "Normal",
            isPublic = true,
            isFreeParking = true,
            workingTimeDescription = "24/7"
        )
        fakeVinFastApiClient.getLocationInfoResult = Result.success(listOf(liveDto))

        val result = repository.getFavorites(userLat = 21.0285, userLon = 105.8542)

        assertTrue("Favorites result should be successful", result.isSuccess)
        val favorites = result.getOrNull()
        assertNotNull(favorites)
        assertEquals(1, favorites!!.size)

        val enriched = favorites.first()
        assertEquals(favoriteId, enriched.id)
        assertEquals("VINFAST_DIRECT", enriched.sourceTier)
        assertEquals(5, enriched.totalAvailablePlugs)
        assertEquals(6, enriched.totalPlugs)
        assertEquals(1700000000L, enriched.addedAt) // Preserves addedAt timestamp
        assertNotNull(enriched.distanceKm)

        // Verify in-memory state and cache were updated
        val stateList = repository.favoritesState.value
        assertEquals(1, stateList.size)
        assertEquals("VINFAST_DIRECT", stateList[0].sourceTier)
        assertEquals(5, stateList[0].totalAvailablePlugs)
    }

    // -------------------------------------------------------------
    // 4. BOTH TIERS FAIL -> CLEAN FAILURE WITHOUT CRASH
    // -------------------------------------------------------------

    @Test
    fun testBothTiersFail_returnsCleanFailureWithoutCrash() = runTest(testDispatcher) {
        fakeVinFastApiClient.searchStationsResult = Result.failure(
            VinFastApiException(code = 500, message = "VinFast Internal Server Error")
        )
        fakeEvcsApiClient.searchStationsResult = Result.failure(
            IOException("EVCS Connection refused")
        )

        val result = repository.searchNearbyVinFast(21.0285, 105.8542)

        assertTrue("Result must be failure when both tiers fail", result.isFailure)
        assertNotNull("Exception should be encapsulated", result.exceptionOrNull())
        assertTrue(
            "Exception should contain EVCS failure message",
            result.exceptionOrNull()?.message?.contains("EVCS") == true ||
                    result.exceptionOrNull()?.message?.contains("Connection refused") == true
        )

        // Verify error log recorded
        val logs = AppDebugLogger.getLogs()
        val errorLog = logs.firstOrNull { it.level == DebugLogLevel.ERROR }
        assertNotNull("Fatal error must be logged to AppDebugLogger", errorLog)
    }

    // -------------------------------------------------------------
    // 5. HELPER ERGONOMICS & PURE MOTORBIKE FILTERING
    // -------------------------------------------------------------

    @Test
    fun testHelperErgonomicsAndMotorbikeFiltering() = runTest(testDispatcher) {
        // Test pure motorbike station dropped in fallback
        fakeVinFastApiClient.searchStationsResult = Result.failure(
            VinFastApiException(code = 503, message = "Service Unavailable")
        )
        val bikeOnly = SearchStationRaw(
            locationId = "M.BIKE01",
            stationName = "Motorbike only station",
            evsePowers = listOf(EvsePowerRaw(type = 3500, numberOfAvailableEvse = 4, totalEvse = 4))
        )
        fakeEvcsApiClient.searchStationsResult = Result.success(listOf(bikeOnly))

        val searchResult = repository.searchNearby(21.0285, 105.8542)
        assertTrue(searchResult.isSuccess)
        assertEquals(
            "Pure motorbike station should be dropped during fallback sanitization",
            0,
            searchResult.getOrThrow().size
        )

        // Test getStationsByIds Tier 1 success
        val carDto = VinFastStationStatusDto(
            locationId = "C.ST01",
            stationName = "Car Station",
            connectors = listOf(VinFastConnectorDto(type = 60000, count = 1, total = 2))
        )
        fakeVinFastApiClient.getLocationInfoResult = Result.success(listOf(carDto))
        val byIdsResult = repository.getStationsByIds(listOf("C.ST01"))
        assertTrue(byIdsResult.isSuccess)
        assertEquals("VINFAST_DIRECT", byIdsResult.getOrThrow().first().sourceTier)
    }
}

// -----------------------------------------------------------------
// TEST DOUBLES / FAKES
// -----------------------------------------------------------------

class FakeVinFastCAppApiClient : VinFastCAppApiClient() {
    var searchStationsCallCount = 0
    var searchStationsResult: Result<List<VinFastStationStatusDto>> = Result.success(emptyList())

    var getLocationInfoCallCount = 0
    var getLocationInfoResult: Result<List<VinFastStationStatusDto>> = Result.success(emptyList())

    override suspend fun searchStations(
        lat: Double,
        lon: Double,
        page: Int,
        size: Int,
        province: String?,
        district: String?,
        wattageTypes: List<String>?,
        parkingFee: Boolean?,
        freeParking: Boolean?,
        excludeFavorite: Boolean?
    ): Result<List<VinFastStationStatusDto>> {
        searchStationsCallCount++
        return searchStationsResult
    }

    override suspend fun getLocationInfo(
        locationIds: List<String>
    ): Result<List<VinFastStationStatusDto>> {
        getLocationInfoCallCount++
        return getLocationInfoResult
    }
}

class FakeEvcsApiClient(sessionManager: SessionManager) : EvcsApiClient(sessionManager) {
    var searchStationsCallCount = 0
    var searchStationsResult: Result<List<SearchStationRaw>> = Result.success(emptyList())

    override suspend fun searchStations(
        latitude: Double,
        longitude: Double,
        token: String
    ): Result<List<SearchStationRaw>> {
        searchStationsCallCount++
        return searchStationsResult
    }
}

class FakeTestFirestoreDataSource : FirestoreFavoritesDataSource {
    private val store = mutableMapOf<String, MutableMap<String, Any>>()

    override suspend fun getFavoritesDocument(userId: String): Result<Map<String, Any>?> {
        return Result.success(store[userId])
    }

    override suspend fun saveAllFavorites(
        userId: String,
        favoritesMap: Map<String, Any>,
        updatedAt: Long
    ): Result<Unit> {
        store[userId] = favoritesMap.toMutableMap()
        return Result.success(Unit)
    }

    override suspend fun updateFavorite(
        userId: String,
        stationId: String,
        stationData: Any,
        updatedAt: Long
    ): Result<Unit> {
        val userMap = store.getOrPut(userId) { mutableMapOf() }
        userMap[stationId] = stationData
        return Result.success(Unit)
    }

    override suspend fun deleteFavorite(
        userId: String,
        stationId: String,
        updatedAt: Long
    ): Result<Unit> {
        store[userId]?.remove(stationId)
        return Result.success(Unit)
    }
}
