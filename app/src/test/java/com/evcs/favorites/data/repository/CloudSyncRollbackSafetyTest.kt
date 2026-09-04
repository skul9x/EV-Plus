package com.evcs.favorites.data.repository

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.AuthEngine
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.ui.state.FavoritesUiState
import com.evcs.favorites.ui.viewmodel.FavoritesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive verification test for Phase 03:
 * Cloud Sync Failure Rollback & Consistency Safety (ANDROID-LOGIC-006).
 *
 * Requirements verified:
 * 1. Verifies that when apiClient.saveFavorites fails during addFavoriteStation,
 *    _favoritesState.value is restored to pre-add state.
 * 2. Verifies that when apiClient.saveFavorites fails during addFavoriteStation,
 *    persistent storage cacheStorage is rolled back.
 * 3. Verifies that when apiClient.saveFavorites fails during removeFavoriteStation,
 *    _favoritesState.value retains the removed station.
 * 4. Verifies that persistent storage is restored when removeFavoriteStation encounters network errors.
 * 5. Verifies that successful cloud sync updates both in-memory and persistent cache without triggering rollback.
 * 6. Verifies that when repository.removeFavoriteStation rolls back on network failure,
 *    FavoritesViewModel retains/restores the station in UI state rather than permanently dropping it.
 * 7. Verifies rollback handling when network socket exception occurs during cloud sync.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudSyncRollbackSafetyTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockServer: MockWebServer
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var apiClient: EvcsApiClient
    private lateinit var repository: EvcsRepository

    private fun createStation(
        id: String,
        name: String,
        lat: Double = 21.0285,
        lon: Double = 105.8542
    ): Station {
        val powers = listOf(
            PowerPort(
                typeWatts = 60_000L,
                label = "60kW",
                availablePlugs = 2,
                totalPlugs = 4,
                displayString = "60kW: trống 2/4"
            )
        )
        return Station(
            id = id,
            name = name,
            address = "$name Address",
            latitude = lat,
            longitude = lon,
            summary = "24/7",
            connectors = "60kW",
            depotStatus = "Normal",
            powers = powers,
            totalAvailablePlugs = 2,
            totalPlugs = 4
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockServer = MockWebServer()
        mockServer.start()

        sessionStorage = InMemorySessionStorage()
        sessionManager = SessionManager(sessionStorage).apply {
            phpSessionId = "sess_test_123"
            authCookie = "auth_tok_valid"
            csrfToken = "csrf_token_secret"
        }

        apiClient = EvcsApiClient(
            sessionManager = sessionManager,
            baseUrl = mockServer.url("").toString().removeSuffix("/")
        )

        repository = EvcsRepository(
            apiClient = apiClient,
            cacheStorage = sessionStorage,
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
        Dispatchers.resetMain()
    }

    @Test
    fun testAddFavoriteStation_rollsBackInMemoryAndPersistentStorageOnHttpError() = runTest(testDispatcher) {
        val initialStation = createStation("ST_INITIAL", "Initial Station")

        // 1. Prepopulate repository with 1 station successfully
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val initialResult = repository.addFavoriteStation(initialStation)
        assertTrue(initialResult.isSuccess)
        assertEquals(1, repository.favoritesState.value.size)
        assertEquals(1, repository.getCachedFavorites().size)

        // 2. Enqueue 500 Internal Server Error for second add
        mockServer.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))
        val candidateStation = createStation("ST_FAILED", "Failed Candidate Station")

        val result = repository.addFavoriteStation(candidateStation)

        // Verify result is failure
        assertTrue("addFavoriteStation should return failure", result.isFailure)

        // Verify in-memory state rolled back to pre-add snapshot
        val inMemoryFavorites = repository.favoritesState.value
        assertEquals("In-memory favorites size must roll back to 1", 1, inMemoryFavorites.size)
        assertEquals("ST_INITIAL", inMemoryFavorites[0].id)
        assertFalse("Candidate station must not exist in favoriteIdsState", repository.favoriteIdsState.value.contains("ST_FAILED"))
        assertTrue("Initial station must exist in favoriteIdsState", repository.favoriteIdsState.value.contains("ST_INITIAL"))

        // Verify persistent storage rolled back to pre-add snapshot
        val cachedFavorites = repository.getCachedFavorites()
        assertEquals("Persistent storage size must roll back to 1", 1, cachedFavorites.size)
        assertEquals("ST_INITIAL", cachedFavorites[0].id)
    }

    @Test
    fun testRemoveFavoriteStation_rollsBackInMemoryAndPersistentStorageOnHttpError() = runTest(testDispatcher) {
        val stationA = createStation("ST_A", "Station A")
        val stationB = createStation("ST_B", "Station B")

        // 1. Prepopulate repository with 2 stations successfully
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        assertTrue(repository.addFavoriteStation(stationA).isSuccess)
        assertTrue(repository.addFavoriteStation(stationB).isSuccess)
        assertEquals(2, repository.favoritesState.value.size)
        assertEquals(2, repository.getCachedFavorites().size)

        // 2. Enqueue 503 Service Unavailable for remove operation
        mockServer.enqueue(MockResponse().setResponseCode(503).setBody("Service Unavailable"))

        val removeResult = repository.removeFavoriteStation("ST_A")

        // Verify failure
        assertTrue("removeFavoriteStation should return failure", removeResult.isFailure)

        // Verify in-memory state rolled back and retains removed station ST_A
        val inMemoryFavorites = repository.favoritesState.value
        assertEquals("In-memory favorites size must remain 2 after rollback", 2, inMemoryFavorites.size)
        assertTrue("ST_A must be retained in favoriteIdsState", repository.favoriteIdsState.value.contains("ST_A"))
        assertTrue("ST_B must be retained in favoriteIdsState", repository.favoriteIdsState.value.contains("ST_B"))

        // Verify persistent storage rolled back and retains both stations
        val cachedFavorites = repository.getCachedFavorites()
        assertEquals("Persistent storage must retain 2 stations after rollback", 2, cachedFavorites.size)
        val cachedIds = cachedFavorites.map { it.id }.toSet()
        assertTrue(cachedIds.contains("ST_A"))
        assertTrue(cachedIds.contains("ST_B"))
    }

    @Test
    fun testSuccessfulCloudSync_updatesInMemoryAndPersistentStorageWithoutRollback() = runTest(testDispatcher) {
        val station1 = createStation("ST_1", "Station 1")
        val station2 = createStation("ST_2", "Station 2")

        // 1. Successful add of station1
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val add1Result = repository.addFavoriteStation(station1)
        assertTrue(add1Result.isSuccess)
        assertEquals(1, repository.favoritesState.value.size)
        assertEquals("ST_1", repository.favoritesState.value[0].id)
        assertEquals(1, repository.getCachedFavorites().size)
        assertEquals("ST_1", repository.getCachedFavorites()[0].id)

        // 2. Successful add of station2
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val add2Result = repository.addFavoriteStation(station2)
        assertTrue(add2Result.isSuccess)
        assertEquals(2, repository.favoritesState.value.size)
        assertEquals(2, repository.getCachedFavorites().size)

        // 3. Successful remove of station1
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val removeResult = repository.removeFavoriteStation("ST_1")
        assertTrue(removeResult.isSuccess)
        assertEquals(1, repository.favoritesState.value.size)
        assertEquals("ST_2", repository.favoritesState.value[0].id)
        assertFalse(repository.favoriteIdsState.value.contains("ST_1"))
        assertTrue(repository.favoriteIdsState.value.contains("ST_2"))

        val cachedAfterRemove = repository.getCachedFavorites()
        assertEquals(1, cachedAfterRemove.size)
        assertEquals("ST_2", cachedAfterRemove[0].id)
    }

    @Test
    fun testCloudSyncNetworkDisconnect_triggersRollbackSafely() = runTest(testDispatcher) {
        val station = createStation("ST_DISCONNECT", "Disconnect Test")

        // Enqueue socket disconnect to simulate sudden drop/IOException
        mockServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val result = repository.addFavoriteStation(station)

        assertTrue("Should return failure on socket disconnect", result.isFailure)
        assertEquals("In-memory list should remain empty", 0, repository.favoritesState.value.size)
        assertEquals("Favorite IDs should remain empty", 0, repository.favoriteIdsState.value.size)
        assertEquals("Persistent storage should remain empty", 0, repository.getCachedFavorites().size)
    }

    @Test
    fun testFavoritesViewModel_restoresStationInUiStateWhenRepositoryRemoveFails() = runTest(testDispatcher) {
        val station1 = createStation("ST_VM_1", "VM Station 1")
        val station2 = createStation("ST_VM_2", "VM Station 2")

        // Prepopulate repository with 2 stations
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        assertTrue(repository.addFavoriteStation(station1).isSuccess)
        assertTrue(repository.addFavoriteStation(station2).isSuccess)

        val authEngine = AuthEngine(sessionManager)
        val viewModel = FavoritesViewModel(
            repository = repository,
            authEngine = authEngine,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        // Simulate successful fetch / initial state
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"server": [{"location_id": "ST_VM_1", "name": "VM Station 1"}, {"location_id": "ST_VM_2", "name": "VM Station 2"}]}""")
        )
        // Mock cluster search for fetchFavorites
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        viewModel.fetchFavorites().join()
        advanceUntilIdle()

        val stateBefore = viewModel.uiState.value as FavoritesUiState.Success
        assertEquals(2, stateBefore.stations.size)

        // Select station 1 for detail
        viewModel.selectStationForDetail(station1)
        assertEquals("ST_VM_1", viewModel.selectedStationForDetail.value?.id)

        // Enqueue server error for removeFavorite
        mockServer.enqueue(MockResponse().setResponseCode(500).setBody("Cloud sync error"))

        // Attempt to remove ST_VM_1
        viewModel.removeFavorite("ST_VM_1").join()
        advanceUntilIdle()

        // Verify that UI state retains/restores ST_VM_1
        val stateAfter = viewModel.uiState.value as FavoritesUiState.Success
        assertEquals("UI state must retain/restore the station upon rollback", 2, stateAfter.stations.size)
        val stationIds = stateAfter.stations.map { it.id }
        assertTrue("ST_VM_1 must be present in UI state", stationIds.contains("ST_VM_1"))
        assertTrue("ST_VM_2 must be present in UI state", stationIds.contains("ST_VM_2"))

        // Verify repository in-memory state also rolled back
        assertEquals(2, repository.favoritesState.value.size)
        assertTrue(repository.favoriteIdsState.value.contains("ST_VM_1"))
    }
}
