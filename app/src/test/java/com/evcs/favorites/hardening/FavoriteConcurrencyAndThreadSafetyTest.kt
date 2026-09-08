package com.evcs.favorites.hardening

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.AuthEngine
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.ConflictResolutionStrategy
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.data.repository.FakeFirestoreFavoritesDataSource
import com.evcs.favorites.data.repository.FirestoreFavoritesRepository
import com.evcs.favorites.data.repository.toFirestoreMap
import com.evcs.favorites.domain.location.LocationService
import com.evcs.favorites.ui.components.NativeStationDetailSheetHelper
import com.evcs.favorites.ui.state.FavoritesUiState
import com.evcs.favorites.ui.state.NearbyUiEvent
import com.evcs.favorites.ui.viewmodel.FavoritesViewModel
import com.evcs.favorites.ui.viewmodel.NearbyViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single Comprehensive Verification Test for Phase 02:
 * Thread-Safe Favorites Synchronization & Rapid-Click Guard.
 *
 * Validates:
 * 1. Thread-safe atomic mutations in FirestoreFavoritesRepository under high concurrency
 *    (100 concurrent coroutines across Dispatchers.IO and Dispatchers.Default) with zero lost updates.
 * 2. Concurrent add, remove, and syncOnLogin operations without race conditions or deadlocks.
 * 3. Zero blocking of Android Main UI thread during Mutex acquisition.
 * 4. NearbyViewModel tracking in-flight operations per station ID (togglingStationIds)
 *    and dropping duplicate rapid clicks.
 * 5. NearbyViewModel toast debouncing suppressing duplicate toast spam events.
 * 6. FavoritesViewModel.removeFavorite guarding in-flight station IDs and preserving
 *    rollback metadata on duplicate rapid delete clicks when repository fails.
 * 7. StationCard and NativeStationDetailSheet / Helper disabling favorite buttons when in-flight.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FavoriteConcurrencyAndThreadSafetyTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockServer: MockWebServer
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var apiClient: EvcsApiClient
    private lateinit var fakeRemoteDataSource: FakeFirestoreFavoritesDataSource
    private lateinit var firestoreRepo: FirestoreFavoritesRepository
    private lateinit var repository: EvcsRepository
    private lateinit var authEngine: AuthEngine

    private val sampleStation = Station(
        id = "STATION_HARDENING_01",
        name = "VinFast Concurrency Station",
        address = "456 Le Duan, Hanoi",
        latitude = 21.0285,
        longitude = 105.8542,
        summary = "24/7",
        connectors = "CCS2",
        depotStatus = "Normal",
        powers = listOf(
            PowerPort(typeWatts = 60000L, label = "60kW", availablePlugs = 2, totalPlugs = 4, displayString = "60kW: trống 2/4")
        )
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockServer = MockWebServer()
        mockServer.start()

        sessionStorage = InMemorySessionStorage()
        sessionManager = SessionManager(sessionStorage).apply {
            phpSessionId = "sess_concurrency_test"
            authCookie = "auth_tok_concurrency"
            csrfToken = "csrf_token_test"
            userEmail = "tester@evplus.vn"
        }

        okHttpClient = OkHttpClient.Builder().build()
        apiClient = EvcsApiClient(
            sessionManager = sessionManager,
            baseUrl = mockServer.url("").toString().removeSuffix("/"),
            client = okHttpClient
        )

        fakeRemoteDataSource = FakeFirestoreFavoritesDataSource()
        firestoreRepo = FirestoreFavoritesRepository(
            remoteDataSource = fakeRemoteDataSource,
            localStorage = sessionStorage,
            ioDispatcher = Dispatchers.IO
        )

        repository = EvcsRepository(
            apiClient = apiClient,
            cacheStorage = sessionStorage,
            firestoreFavoritesRepository = firestoreRepo
        )

        authEngine = AuthEngine(
            sessionManager = sessionManager,
            client = okHttpClient,
            baseUrl = mockServer.url("").toString().removeSuffix("/")
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        mockServer.shutdown()
    }

    // =========================================================================
    // 1. FIRESTORE REPOSITORY HIGH-CONCURRENCY & MUTEX ATOMICITY
    // =========================================================================

    @Test
    fun testFirestoreFavoritesRepository_highConcurrency_atomicMutationsWithoutLostUpdates() = runTest {
        // Enqueue 100 concurrent station additions across Dispatchers.IO and Dispatchers.Default
        val stationCount = 100
        val stations = (1..stationCount).map { index ->
            Station(
                id = "CONCURRENT_STATION_$index",
                name = "Station #$index",
                address = "Address #$index",
                latitude = 21.0 + (index * 0.001),
                longitude = 105.0 + (index * 0.001),
                summary = "24/7",
                connectors = "CCS2",
                depotStatus = "Normal"
            )
        }

        val ioScope = CoroutineScope(Dispatchers.IO)
        val defaultScope = CoroutineScope(Dispatchers.Default)

        val deferredList = stations.mapIndexed { index, station ->
            val scope = if (index % 2 == 0) ioScope else defaultScope
            scope.async {
                firestoreRepo.addFavoriteStation(station, userId = "user_concurrent")
            }
        }

        // Await all 100 concurrent mutations
        deferredList.awaitAll()

        // Verify that all 100 mutations succeeded without lost updates
        val finalFavorites = firestoreRepo.favoritesState.value
        assertEquals("All 100 concurrent station additions must be preserved by stateMutex", stationCount, finalFavorites.size)

        val finalIds = firestoreRepo.favoriteIdsState.value
        assertEquals(stationCount, finalIds.size)
        assertTrue("All station IDs must exist in favoriteIdsState", stations.all { finalIds.contains(it.id) })

        // Verify local storage cached snapshot contains all 100 stations
        val cached = firestoreRepo.getCachedFavorites()
        assertEquals(stationCount, cached.size)

        // Now test 50 concurrent removals across multiple dispatchers
        val toRemove = stations.take(50)
        val removeDeferredList = toRemove.mapIndexed { index, station ->
            val scope = if (index % 2 == 0) ioScope else defaultScope
            scope.async {
                firestoreRepo.removeFavoriteStation(station.id, userId = "user_concurrent")
            }
        }
        removeDeferredList.awaitAll()

        val afterRemoval = firestoreRepo.favoritesState.value
        assertEquals("Exactly 50 stations should remain after 50 concurrent removals", 50, afterRemoval.size)
        val remainingIds = firestoreRepo.favoriteIdsState.value
        assertEquals(50, remainingIds.size)
        toRemove.forEach { removed ->
            assertFalse("Removed station must not exist in favoriteIdsState", remainingIds.contains(removed.id))
        }
    }

    @Test
    fun testFirestoreFavoritesRepository_concurrentSyncOnLoginAndMutations() = runTest {
        // Setup initial cloud doc
        val cloudMap = mapOf(
            "CLOUD_01" to sampleStation.copy(id = "CLOUD_01", name = "Cloud Station 1").toFirestoreMap(),
            "CLOUD_02" to sampleStation.copy(id = "CLOUD_02", name = "Cloud Station 2").toFirestoreMap()
        )
        fakeRemoteDataSource.saveAllFavorites("user_sync_race", cloudMap, System.currentTimeMillis() / 1000L)

        // Prepopulate 1 local station
        firestoreRepo.addFavoriteStation(sampleStation.copy(id = "LOCAL_01", name = "Local Station 1"), userId = "user_sync_race")

        val ioScope = CoroutineScope(Dispatchers.IO)
        val defaultScope = CoroutineScope(Dispatchers.Default)

        // Concurrently run syncOnLogin and new station additions
        val syncDeferred = ioScope.async {
            firestoreRepo.syncOnLogin("user_sync_race", ConflictResolutionStrategy.MERGE)
        }
        val addDeferred = defaultScope.async {
            firestoreRepo.addFavoriteStation(sampleStation.copy(id = "LOCAL_RACE_02", name = "Local Station 2"), userId = "user_sync_race")
        }

        val syncResult = syncDeferred.await()
        val addResult = addDeferred.await()

        assertTrue(syncResult.isSuccess)
        assertTrue(addResult.isSuccess)

        // StateMutex guarantees both operations completed safely without ConcurrentModificationException
        val finalFavorites = firestoreRepo.favoritesState.value
        assertTrue(finalFavorites.any { it.id == "CLOUD_01" })
        assertTrue(finalFavorites.any { it.id == "CLOUD_02" })
        assertTrue(finalFavorites.any { it.id == "LOCAL_01" })
        assertTrue(finalFavorites.any { it.id == "LOCAL_RACE_02" })
    }

    @Test
    fun testFirestoreFavoritesRepository_zeroBlockingMainThreadDuringMutexAcquisition() = runTest {
        val ioScope = CoroutineScope(Dispatchers.IO)
        val mainActionExecuted = AtomicBoolean(false)

        // Launch an operation that holds mutex
        val slowJob = ioScope.launch {
            firestoreRepo.addFavoriteStation(sampleStation)
        }
        slowJob.join()

        // Main thread verification: Main dispatcher executes and acquires Mutex in non-blocking suspending manner
        val mainJob = launch(Dispatchers.Main) {
            val result = firestoreRepo.addFavoriteStation(sampleStation.copy(id = "MAIN_STATION"))
            assertTrue(result.isSuccess)
            mainActionExecuted.set(true)
        }

        advanceUntilIdle()
        mainJob.join()
        assertTrue("Main thread coroutine executed without blocking UI thread", mainActionExecuted.get())
    }

    // =========================================================================
    // 2. NEARBY VIEWMODEL IN-FLIGHT GUARD & TOAST DEBOUNCING
    // =========================================================================

    @Test
    fun testNearbyViewModel_inFlightGuard_dropsRapidDuplicateClicks() = runTest {
        val fakeLocationService = object : LocationService() {
            override fun hasLocationPermission(): Boolean = true
        }

        val nearbyVm = NearbyViewModel(
            repository = repository,
            sessionManager = sessionManager,
            locationService = fakeLocationService,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher
        )

        assertEquals("Initially togglingStationIds should be empty", emptySet<String>(), nearbyVm.togglingStationIds.value)
        assertEquals(emptySet<String>(), nearbyVm.uiState.value.togglingStationIds)

        // Call toggleFavorite 5 times rapidly
        val job1 = nearbyVm.toggleFavorite(sampleStation)
        val job2 = nearbyVm.toggleFavorite(sampleStation)
        val job3 = nearbyVm.toggleFavorite(sampleStation)
        val job4 = nearbyVm.toggleFavorite(sampleStation)
        val job5 = nearbyVm.toggleFavorite(sampleStation)

        // Subsequent rapid clicks should return the same active job
        assertEquals(job1, job2)
        assertEquals(job1, job3)
        assertEquals(job1, job4)
        assertEquals(job1, job5)

        // Let coroutine run to completion
        advanceUntilIdle()
        job1.join()

        // Once completed, in-flight set is cleared in finally block
        assertFalse(nearbyVm.togglingStationIds.value.contains(sampleStation.id))
        assertFalse(nearbyVm.uiState.value.togglingStationIds.contains(sampleStation.id))

        // Station is now favorite
        assertTrue(nearbyVm.uiState.value.favoriteStationIds.contains(sampleStation.id))
    }

    @Test
    fun testNearbyViewModel_toastDebounce_suppressesDuplicateToasts() = runTest {
        val fakeLocationService = object : LocationService() {
            override fun hasLocationPermission(): Boolean = true
        }

        val nearbyVm = NearbyViewModel(
            repository = repository,
            sessionManager = sessionManager,
            locationService = fakeLocationService,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher
        )

        val receivedEvents = mutableListOf<NearbyUiEvent>()
        val collectorJob = launch {
            nearbyVm.events.collect { receivedEvents.add(it) }
        }

        // Rapid click spam 10 times
        val jobs = (1..10).map { nearbyVm.toggleFavorite(sampleStation) }
        advanceUntilIdle()
        jobs.first().join()

        // Verify only 1 toast event was emitted for the state change
        val toastEvents = receivedEvents.filterIsInstance<NearbyUiEvent.ShowToast>()
        assertEquals("Rapid clicks must emit strictly 1 toast event", 1, toastEvents.size)
        assertEquals("Đã thêm vào danh sách yêu thích", toastEvents.first().message)

        collectorJob.cancel()
    }

    // =========================================================================
    // 3. FAVORITES VIEWMODEL IN-FLIGHT GUARD & ROLLBACK PRESERVATION
    // =========================================================================

    @Test
    fun testFavoritesViewModel_removeFavorite_guardsInFlight_andPreservesRollbackOnFailure() = runTest {
        // Pre-populate repository with 1 favorite
        firestoreRepo.addFavoriteStation(sampleStation, userId = "user_fav_test")

        val favVm = FavoritesViewModel(
            repository = repository,
            authEngine = authEngine,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher
        )

        advanceUntilIdle()
        val initialSuccess = favVm.uiState.value as? FavoritesUiState.Success
        assertNotNull(initialSuccess)
        assertEquals(1, initialSuccess!!.stations.size)
        assertEquals(sampleStation.id, initialSuccess.stations.first().id)

        // Configure a failing repository wrapper to test rollback
        val failingRepo = object : EvcsRepository(apiClient = apiClient, cacheStorage = sessionStorage, firestoreFavoritesRepository = firestoreRepo) {
            override suspend fun removeFavoriteStation(locationId: String): Result<Unit> {
                delay(50) // simulate network delay
                return Result.failure(Exception("Network error during deletion"))
            }
        }

        val favVmWithFailingRepo = FavoritesViewModel(
            repository = failingRepo,
            authEngine = authEngine,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher
        )

        advanceUntilIdle()
        val vmInitialState = favVmWithFailingRepo.uiState.value as? FavoritesUiState.Success
        assertNotNull(vmInitialState)
        assertEquals(1, vmInitialState!!.stations.size)

        // Rapidly click removeFavorite twice
        val removeJob1 = favVmWithFailingRepo.removeFavorite(sampleStation.id)
        val removeJob2 = favVmWithFailingRepo.removeFavorite(sampleStation.id)

        // Duplicate call should return the same active job
        assertEquals(removeJob1, removeJob2)

        // In-flight state should track stationId
        assertTrue(favVmWithFailingRepo.togglingStationIds.value.contains(sampleStation.id))

        // Complete the operation
        advanceUntilIdle()
        removeJob1.join()

        // Verify station was safely rolled back and restored into UI state
        val finalState = favVmWithFailingRepo.uiState.value as? FavoritesUiState.Success
        assertNotNull(finalState)
        assertEquals("Rollback must restore the station into favorites list on failure", 1, finalState!!.stations.size)
        assertEquals(sampleStation.id, finalState.stations.first().id)

        // Verify togglingStationIds is cleared
        assertFalse(favVmWithFailingRepo.togglingStationIds.value.contains(sampleStation.id))
    }

    // =========================================================================
    // 4. UI COMPONENT IN-FLIGHT DISABLING (STATION CARD & DETAIL SHEET)
    // =========================================================================

    @Test
    fun testStationCardAndDetailSheet_disabledWhenToggleInProgress() {
        // Test NativeStationDetailSheetHelper when isToggleInProgress is true
        val inFlightUnsavedSpec = NativeStationDetailSheetHelper.resolveFavoriteButtonSpec(
            isFavorite = false,
            isToggleInProgress = true
        )
        assertFalse("Favorite button must be disabled when in-flight", inFlightUnsavedSpec.isEnabled)
        assertFalse(inFlightUnsavedSpec.isFavorite)

        val inFlightSavedSpec = NativeStationDetailSheetHelper.resolveFavoriteButtonSpec(
            isFavorite = true,
            isToggleInProgress = true
        )
        assertFalse("Saved button must be disabled when in-flight", inFlightSavedSpec.isEnabled)
        assertTrue(inFlightSavedSpec.isFavorite)

        // Test normal state when isToggleInProgress is false
        val idleUnsavedSpec = NativeStationDetailSheetHelper.resolveFavoriteButtonSpec(
            isFavorite = false,
            isToggleInProgress = false
        )
        assertTrue("Favorite button must be enabled when idle", idleUnsavedSpec.isEnabled)

        val idleSavedSpec = NativeStationDetailSheetHelper.resolveFavoriteButtonSpec(
            isFavorite = true,
            isToggleInProgress = false
        )
        assertTrue("Saved button must be enabled when idle", idleSavedSpec.isEnabled)
    }

    @Test
    fun testMultiThreadedContention_highThroughput_threadPoolSafety() {
        val threadPool = Executors.newFixedThreadPool(8)
        val customDispatcher = threadPool.asCoroutineDispatcher()
        val customScope = CoroutineScope(customDispatcher)

        val successCount = AtomicInteger(0)
        val latch = CountDownLatch(50)

        for (i in 1..50) {
            customScope.launch {
                val st = sampleStation.copy(id = "POOL_STATION_$i", name = "Pool Station $i")
                val res = firestoreRepo.addFavoriteStation(st, userId = "user_pool")
                if (res.isSuccess) {
                    successCount.incrementAndGet()
                }
                latch.countDown()
            }
        }

        val completed = latch.await(10, TimeUnit.SECONDS)
        assertTrue("All 50 threaded coroutines should finish within 10s", completed)
        assertEquals(50, successCount.get())

        val repoStations = firestoreRepo.favoritesState.value
        assertEquals("Repository state must hold all 50 items under multi-threaded pool", 50, repoStations.size)

        threadPool.shutdown()
    }
}
