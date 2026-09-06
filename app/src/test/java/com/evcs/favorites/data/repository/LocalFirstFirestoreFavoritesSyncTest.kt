package com.evcs.favorites.data.repository

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.FakeAuthService
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.domain.model.AuthUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive test verifying the Phase 04 Local-First Cloud Firestore Favorites Sync:
 * - Immediate 0ms offline availability from local cache.
 * - Firestore Map serialization (`favorites.<stationId>`) with FieldPath support for dotted IDs (e.g. `C.BNI0012`).
 * - Dual parsing (rich metadata vs scalar timestamp).
 * - Login sync decision matrix (Branches 1, 2, 3, 4).
 * - 3 Conflict resolution strategies (Merge with earliest timestamp, Prefer Cloud, Prefer Local).
 * - Optimistic CRUD updates and offline resilience.
 * - Unified reactive StateFlow synchronization across components.
 * - Backward-compatible delegation through [EvcsRepository].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalFirstFirestoreFavoritesSyncTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockServer: MockWebServer
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var fakeRemoteDataSource: FakeFirestoreFavoritesDataSource
    private lateinit var fakeAuthService: FakeAuthService
    private lateinit var repository: FirestoreFavoritesRepository

    private fun createStation(
        id: String,
        name: String,
        lat: Double = 21.0285,
        lon: Double = 105.8542,
        connectors: String = "60kW, 30kW",
        addedAt: Long = 1788597858L,
        images: List<String> = listOf("https://cpo-prod-s3.vinfastauto.com/image1.jpg")
    ): Station {
        return Station(
            id = id,
            name = name,
            address = "$name Address",
            latitude = lat,
            longitude = lon,
            summary = "✧ 60kW có 2 cổng...",
            connectors = connectors,
            depotStatus = "Normal",
            powers = EvcsRepository.parseConnectorsToPowers(connectors),
            images = images,
            image = images.firstOrNull(),
            addedAt = addedAt
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockServer = MockWebServer()
        mockServer.start()

        sessionStorage = InMemorySessionStorage()
        fakeRemoteDataSource = FakeFirestoreFavoritesDataSource()
        fakeAuthService = FakeAuthService()

        repository = FirestoreFavoritesRepository(
            remoteDataSource = fakeRemoteDataSource,
            localStorage = sessionStorage,
            authService = fakeAuthService,
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
        Dispatchers.resetMain()
    }

    @Test
    fun testLocalCacheDeliversImmediateFavoritesWithoutNetwork() = runTest(testDispatcher) {
        val cachedStation1 = createStation("C.BNI0012", "VinFast Bac Ninh")
        val cachedStation2 = createStation("C.SGN0001", "VinFast Saigon")

        // Prepopulate local persistent cache
        repository.saveCachedFavorites(listOf(cachedStation1, cachedStation2))

        // Create fresh repository instance
        val freshRepo = FirestoreFavoritesRepository(
            remoteDataSource = fakeRemoteDataSource,
            localStorage = sessionStorage,
            authService = fakeAuthService,
            ioDispatcher = testDispatcher
        )

        // Verify instant 0ms access without any network call
        val initialFavorites = freshRepo.favoritesState.value
        assertEquals("Must immediately load 2 cached stations", 2, initialFavorites.size)
        assertEquals("C.BNI0012", initialFavorites[0].id)
        assertEquals("C.SGN0001", initialFavorites[1].id)

        val initialIds = freshRepo.favoriteIdsState.value
        assertEquals(2, initialIds.size)
        assertTrue(initialIds.contains("C.BNI0012"))
        assertTrue(initialIds.contains("C.SGN0001"))
    }

    @Test
    fun testFirestoreMapStructureSerializationAndAtomicDeleteViaFieldPath() = runTest(testDispatcher) {
        val userId = "user_test_456"
        val stationWithDots = createStation(
            id = "C.BNI0012",
            name = "VinFast - Vincom Plaza Bắc Ninh",
            lat = 21.1843,
            lon = 106.0741,
            addedAt = 1788597858L
        )

        // 1. Serialization test
        val firestoreMap = stationWithDots.toFirestoreMap()
        assertEquals(1788597858L, firestoreMap["added_at"])
        assertEquals("VinFast - Vincom Plaza Bắc Ninh", firestoreMap["name"])
        assertEquals(21.1843, firestoreMap["lat"])
        assertEquals(106.0741, firestoreMap["lon"])
        assertEquals("60kW, 30kW", firestoreMap["connectors"])
        assertEquals(listOf("https://cpo-prod-s3.vinfastauto.com/image1.jpg"), firestoreMap["images"])

        // 2. Add station with dot in ID via repository
        repository.addFavoriteStation(stationWithDots, userId = userId)

        val rawDoc = fakeRemoteDataSource.getRawDocument(userId)
        assertNotNull("Firestore document must be created", rawDoc)
        @Suppress("UNCHECKED_CAST")
        val favs = rawDoc!!["favorites"] as Map<String, Any>
        assertTrue("Favorites map must contain key with dot 'C.BNI0012'", favs.containsKey("C.BNI0012"))

        // 3. Atomic delete mutation via FieldPath
        repository.removeFavoriteStation("C.BNI0012", userId = userId)

        val updatedDoc = fakeRemoteDataSource.getRawDocument(userId)
        @Suppress("UNCHECKED_CAST")
        val updatedFavs = updatedDoc!!["favorites"] as Map<String, Any>
        assertFalse("Key 'C.BNI0012' must be deleted", updatedFavs.containsKey("C.BNI0012"))

        // 4. Dual parsing verification:
        // Case A: Map value with rich metadata
        // Case B: Scalar Number value as added_at timestamp
        val mixedDocument = mapOf(
            "favorites" to mapOf(
                "C.BNI0012" to firestoreMap,
                "C.SGN0001" to 1788600120L
            ),
            "updated_at" to 1788600500L
        )

        val parsedStations = parseFirestoreFavoritesDocument(mixedDocument)
        assertEquals(2, parsedStations.size)

        val parsedRich = parsedStations.find { it.id == "C.BNI0012" }
        assertNotNull(parsedRich)
        assertEquals("VinFast - Vincom Plaza Bắc Ninh", parsedRich!!.name)
        assertEquals(21.1843, parsedRich.latitude, 0.0001)
        assertEquals(1, parsedRich.images.size)

        val parsedScalar = parsedStations.find { it.id == "C.SGN0001" }
        assertNotNull(parsedScalar)
        assertEquals(1788600120L, parsedScalar!!.addedAt)
    }

    @Test
    fun testLoginSyncDecisionMatrix_Branch1_Branch2_Branch3() = runTest(testDispatcher) {
        val userId = "sync_user_123"

        // Branch 1: L = ∅ and C = ∅ -> No action
        val branch1Result = repository.syncOnLogin(userId).getOrThrow()
        assertEquals(1, branch1Result.branch)
        assertTrue(branch1Result.stations.isEmpty())
        assertTrue(repository.favoritesState.value.isEmpty())

        // Branch 2: L ≠ ∅ and C = ∅ -> Auto-Upload L -> C
        val localStation = createStation("C.LOCAL1", "Local Only Station")
        repository.addFavoriteStation(localStation, userId = null) // saved locally only
        assertEquals(1, repository.favoritesState.value.size)

        val branch2Result = repository.syncOnLogin(userId).getOrThrow()
        assertEquals(2, branch2Result.branch)
        assertEquals(1, branch2Result.stations.size)

        // Cloud should now contain the uploaded station
        val cloudDoc = fakeRemoteDataSource.getRawDocument(userId)
        assertNotNull(cloudDoc)
        @Suppress("UNCHECKED_CAST")
        val cloudFavs = cloudDoc!!["favorites"] as Map<String, Any>
        assertTrue(cloudFavs.containsKey("C.LOCAL1"))

        // Reset local cache to test Branch 3
        repository.clearOfflineCache()
        assertEquals(0, repository.favoritesState.value.size)

        // Prepopulate cloud with new station for Branch 3
        val cloudStation = createStation("C.CLOUD1", "Cloud Only Station")
        fakeRemoteDataSource.saveAllFavorites(
            userId = "user_branch_3",
            favoritesMap = mapOf("C.CLOUD1" to cloudStation.toFirestoreMap()),
            updatedAt = 1788600500L
        )

        // Branch 3: L = ∅ and C ≠ ∅ -> Auto-Download C -> L
        val branch3Result = repository.syncOnLogin("user_branch_3").getOrThrow()
        assertEquals(3, branch3Result.branch)
        assertEquals(1, branch3Result.stations.size)
        assertEquals("C.CLOUD1", branch3Result.stations[0].id)
        assertEquals(1, repository.favoritesState.value.size)
        assertEquals("C.CLOUD1", repository.favoritesState.value[0].id)
        assertTrue(repository.favoriteIdsState.value.contains("C.CLOUD1"))
    }

    @Test
    fun testLoginSyncDecisionMatrix_Branch4_ConflictStrategies() = runTest(testDispatcher) {
        val userId = "user_conflict_test"

        val stationLocalOnly = createStation("ST_LOCAL", "Local Only", addedAt = 100L)
        val stationCommonLocal = createStation("ST_COMMON", "Common Station Local", addedAt = 300L)
        val stationCommonCloud = createStation("ST_COMMON", "Common Station Cloud", addedAt = 200L) // Earlier!
        val stationCloudOnly = createStation("ST_CLOUD", "Cloud Only", addedAt = 400L)

        // --- Strategy 1: MERGE (Earliest timestamp) ---
        // Setup L = [ST_LOCAL, ST_COMMON (300L)]
        repository.saveCachedFavorites(listOf(stationLocalOnly, stationCommonLocal))
        repository = FirestoreFavoritesRepository(
            remoteDataSource = fakeRemoteDataSource,
            localStorage = sessionStorage,
            authService = fakeAuthService,
            ioDispatcher = testDispatcher
        )

        // Setup C = [ST_COMMON (200L), ST_CLOUD (400L)]
        fakeRemoteDataSource.saveAllFavorites(
            userId = userId,
            favoritesMap = mapOf(
                "ST_COMMON" to stationCommonCloud.toFirestoreMap(),
                "ST_CLOUD" to stationCloudOnly.toFirestoreMap()
            ),
            updatedAt = 500L
        )

        val mergeResult = repository.syncOnLogin(userId, ConflictResolutionStrategy.MERGE).getOrThrow()
        assertEquals(4, mergeResult.branch)
        assertEquals(ConflictResolutionStrategy.MERGE, mergeResult.strategyApplied)

        val mergedStations = repository.favoritesState.value
        assertEquals("Union of L and C must contain 3 stations", 3, mergedStations.size)
        val commonStation = mergedStations.find { it.id == "ST_COMMON" }
        assertNotNull(commonStation)
        assertEquals("Must keep earliest added_at timestamp min(300, 200)", 200L, commonStation!!.addedAt)

        // Verify cloud is updated with union
        val cloudAfterMerge = fakeRemoteDataSource.getRawDocument(userId)
        @Suppress("UNCHECKED_CAST")
        val cloudFavsAfterMerge = cloudAfterMerge!!["favorites"] as Map<String, Any>
        assertEquals(3, cloudFavsAfterMerge.size)

        // --- Strategy 2: PREFER_CLOUD ---
        // Setup L = [ST_LOCAL]
        repository.saveCachedFavorites(listOf(stationLocalOnly))
        repository = FirestoreFavoritesRepository(
            remoteDataSource = fakeRemoteDataSource,
            localStorage = sessionStorage,
            authService = fakeAuthService,
            ioDispatcher = testDispatcher
        )

        val preferCloudResult = repository.syncOnLogin(userId, ConflictResolutionStrategy.PREFER_CLOUD).getOrThrow()
        assertEquals(ConflictResolutionStrategy.PREFER_CLOUD, preferCloudResult.strategyApplied)
        // Local is overwritten with Cloud (which had 3 stations from previous merge)
        assertEquals(3, repository.favoritesState.value.size)

        // --- Strategy 3: PREFER_LOCAL ---
        val singleStationLocal = listOf(createStation("ST_OVERWRITE", "Sole Local Station"))
        repository.saveCachedFavorites(singleStationLocal)
        repository = FirestoreFavoritesRepository(
            remoteDataSource = fakeRemoteDataSource,
            localStorage = sessionStorage,
            authService = fakeAuthService,
            ioDispatcher = testDispatcher
        )

        val preferLocalResult = repository.syncOnLogin(userId, ConflictResolutionStrategy.PREFER_LOCAL).getOrThrow()
        assertEquals(ConflictResolutionStrategy.PREFER_LOCAL, preferLocalResult.strategyApplied)
        // Cloud is overwritten with Local
        val cloudAfterPreferLocal = fakeRemoteDataSource.getRawDocument(userId)
        @Suppress("UNCHECKED_CAST")
        val cloudFavsLocal = cloudAfterPreferLocal!!["favorites"] as Map<String, Any>
        assertEquals(1, cloudFavsLocal.size)
        assertTrue(cloudFavsLocal.containsKey("ST_OVERWRITE"))
    }

    @Test
    fun testOptimisticCrudUpdatesLocalStateImmediatelyBeforeCloudSync() = runTest(testDispatcher) {
        val testUser = AuthUser(uid = "opt_user_1", isAnonymous = false)
        fakeAuthService.setAuthenticatedUser(testUser)

        val station = createStation("OPT_ST_1", "Optimistic Station")

        // Add station
        val addResult = repository.addFavoriteStation(station)
        assertTrue(addResult.isSuccess)

        // Local state reflects immediately (0ms)
        assertEquals(1, repository.favoritesState.value.size)
        assertEquals("OPT_ST_1", repository.favoritesState.value[0].id)
        assertTrue(repository.favoriteIdsState.value.contains("OPT_ST_1"))
        assertEquals(1, repository.getCachedFavorites().size)

        // Remove station
        val removeResult = repository.removeFavoriteStation("OPT_ST_1")
        assertTrue(removeResult.isSuccess)

        // Local state reflects removal immediately
        assertEquals(0, repository.favoritesState.value.size)
        assertFalse(repository.favoriteIdsState.value.contains("OPT_ST_1"))
        assertEquals(0, repository.getCachedFavorites().size)
    }

    @Test
    fun testTwoWayReactiveSynchronizationAcrossFavoritesAndNearbySubscribers() = runTest(testDispatcher) {
        val station1 = createStation("SYNC_1", "Sync 1")
        val station2 = createStation("SYNC_2", "Sync 2")

        val observedStates = mutableListOf<Set<String>>()
        val job = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            repository.favoriteIdsState.collect {
                observedStates.add(it)
            }
        }

        repository.addFavoriteStation(station1)
        advanceUntilIdle()
        assertTrue("Direct StateFlow value must contain SYNC_1", repository.favoriteIdsState.value.contains("SYNC_1"))
        assertTrue("Subscriber must receive SYNC_1", observedStates.any { it.contains("SYNC_1") })

        repository.addFavoriteStation(station2)
        advanceUntilIdle()
        assertEquals(2, repository.favoriteIdsState.value.size)
        assertTrue("Subscriber must receive SYNC_2", observedStates.any { it.contains("SYNC_2") && it.contains("SYNC_1") })

        repository.removeFavoriteStation("SYNC_1")
        advanceUntilIdle()
        assertEquals(1, repository.favoriteIdsState.value.size)
        assertFalse(repository.favoriteIdsState.value.contains("SYNC_1"))
        assertTrue(repository.favoriteIdsState.value.contains("SYNC_2"))
        assertTrue("Latest observed state must only contain SYNC_2", observedStates.last() == setOf("SYNC_2"))

        job.cancel()
    }

    @Test
    fun testEvcsRepositoryBackwardCompatibleDelegation() = runTest(testDispatcher) {
        val sessionManager = SessionManager(sessionStorage).apply {
            authCookie = "auth_valid"
            phpSessionId = "php_valid"
        }
        val apiClient = EvcsApiClient(
            sessionManager = sessionManager,
            baseUrl = mockServer.url("").toString().removeSuffix("/")
        )

        val delegatedRepo = EvcsRepository(
            apiClient = apiClient,
            cacheStorage = sessionStorage,
            ioDispatcher = testDispatcher,
            firestoreFavoritesRepository = repository
        )

        // 1. Verify StateFlow delegation
        assertEquals(repository.favoritesState.value, delegatedRepo.favoritesState.value)
        assertEquals(repository.favoriteIdsState.value, delegatedRepo.favoriteIdsState.value)

        // 2. Verify addFavoriteStation delegation
        val station = createStation("DEL_ST", "Delegated Station")
        val addResult = delegatedRepo.addFavoriteStation(station)
        assertTrue(addResult.isSuccess)
        assertEquals(1, repository.favoritesState.value.size)
        assertEquals(1, delegatedRepo.favoritesState.value.size)
        assertTrue(delegatedRepo.favoriteIdsState.value.contains("DEL_ST"))

        // 3. Verify getFavorites returns delegated favorites with attached distance
        val favsResult = delegatedRepo.getFavorites(userLat = 21.0, userLon = 105.8)
        assertTrue(favsResult.isSuccess)
        val stations = favsResult.getOrThrow()
        assertEquals(1, stations.size)
        assertNotNull("Distance should be attached when GPS coordinates provided", stations[0].distanceKm)

        // 4. Verify removeFavoriteStation delegation
        val removeResult = delegatedRepo.removeFavoriteStation("DEL_ST")
        assertTrue(removeResult.isSuccess)
        assertEquals(0, repository.favoritesState.value.size)
        assertEquals(0, delegatedRepo.favoritesState.value.size)
    }
}
