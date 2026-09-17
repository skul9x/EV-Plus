package com.evcs.favorites

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.AuthEngine
import com.evcs.favorites.data.auth.FakeAuthService
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.EvsePowerRaw
import com.evcs.favorites.data.model.SearchStationRaw
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.data.repository.FakeFirestoreFavoritesDataSource
import com.evcs.favorites.data.repository.FirestoreFavoritesRepository
import com.evcs.favorites.domain.location.DistanceCalculator
import com.evcs.favorites.domain.model.AuthUser
import com.evcs.favorites.ui.state.FavoritesUiState
import com.evcs.favorites.ui.viewmodel.FavoritesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Single Comprehensive Verification Test for Phase 03:
 * 1. `getFavorites` immediately emits cached favorites (0ms) when `firestoreFavoritesRepository` is present.
 * 2. Geographic cluster coordinates are grouped correctly for favorite stations (15km radius).
 * 3. Search results are matched by location ID and merged into domain stations, re-populating missing powers and port counts.
 * 4. Enriched stations update repository state and trigger Firestore/local cache persistence.
 * 5. FavoritesViewModel pull-to-refresh triggers cluster re-enrichment and updates UI / detail sheet seamlessly without flickering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesClusterSearchAndTelemetryEnrichmentTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var fakeRemoteDataSource: FakeFirestoreFavoritesDataSource
    private lateinit var fakeAuthService: FakeAuthService
    private lateinit var firestoreRepo: FirestoreFavoritesRepository
    private lateinit var repository: EvcsRepository
    private lateinit var searchedCenters: MutableList<Pair<Double, Double>>

    private val testUserId = "user_test_awf_p03"

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        EvcsRepository.clearParsedConnectorsCache()

        sessionStorage = InMemorySessionStorage()
        sessionManager = SessionManager(sessionStorage)
        fakeRemoteDataSource = FakeFirestoreFavoritesDataSource()
        fakeAuthService = FakeAuthService(
            initialState = com.evcs.favorites.domain.model.AuthState.Authenticated(
                user = AuthUser(uid = testUserId, email = "tester@evcs.vn", isAnonymous = false)
            )
        )

        firestoreRepo = FirestoreFavoritesRepository(
            remoteDataSource = fakeRemoteDataSource,
            localStorage = sessionStorage,
            authService = fakeAuthService,
            ioDispatcher = testDispatcher,
            coroutineScope = kotlinx.coroutines.CoroutineScope(testDispatcher)
        )

        searchedCenters = CopyOnWriteArrayList()

        val fakeApiClient = object : EvcsApiClient(sessionManager) {
            override suspend fun searchStations(
                latitude: Double,
                longitude: Double,
                token: String,
                wattageTypes: List<String>?
            ): Result<List<SearchStationRaw>> {
                searchedCenters.add(Pair(latitude, longitude))

                val results = mutableListOf<SearchStationRaw>()

                // Hanoi cluster matches ST_HN_01 and ST_HN_02
                if (DistanceCalculator.calculateDistanceKm(latitude, longitude, 21.0285, 105.8542) <= 15.0) {
                    results.add(
                        SearchStationRaw(
                            locationId = "ST_HN_01",
                            stationName = "VinFast Tràng Tiền Plaza",
                            stationAddress = "24 Hai Bà Trưng, Hoàn Kiếm, Hà Nội",
                            latitude = 21.0250,
                            longitude = 105.8520,
                            depotStatus = "Normal",
                            isPublic = true,
                            isFreeParking = false,
                            workingTimeDescription = "08:00 - 22:00",
                            media = listOf("https://cpo-prod-s3.vinfastauto.com/trangtien.jpg"),
                            evsePowers = listOf(
                                EvsePowerRaw(type = 60000L, numberOfAvailableEvse = 2, totalEvse = 4),
                                EvsePowerRaw(type = 120000L, numberOfAvailableEvse = 1, totalEvse = 2)
                            )
                        )
                    )
                    results.add(
                        SearchStationRaw(
                            locationId = "ST_HN_02",
                            stationName = "VinFast Times City",
                            stationAddress = "458 Minh Khai, Hai Bà Trưng, Hà Nội",
                            latitude = 20.9950,
                            longitude = 105.8680,
                            depotStatus = "Normal",
                            isPublic = true,
                            isFreeParking = true,
                            workingTimeDescription = "24/7",
                            evsePowers = listOf(
                                EvsePowerRaw(type = 250000L, numberOfAvailableEvse = 4, totalEvse = 4)
                            )
                        )
                    )
                }

                // Da Nang cluster matches ST_DN_01
                if (DistanceCalculator.calculateDistanceKm(latitude, longitude, 16.0544, 108.2022) <= 15.0) {
                    results.add(
                        SearchStationRaw(
                            locationId = "ST_DN_01",
                            stationName = "VinFast Cầu Rồng Đà Nẵng",
                            stationAddress = "Võ Văn Kiệt, Sơn Trà, Đà Nẵng",
                            latitude = 16.0600,
                            longitude = 108.2300,
                            depotStatus = "Maintaining",
                            isPublic = true,
                            isFreeParking = true,
                            workingTimeDescription = "24/7",
                            evsePowers = listOf(
                                EvsePowerRaw(type = 60000L, numberOfAvailableEvse = 0, totalEvse = 2)
                            )
                        )
                    )
                }

                return Result.success(results)
            }
        }

        repository = EvcsRepository(
            apiClient = fakeApiClient,
            cacheStorage = sessionStorage,
            ioDispatcher = testDispatcher,
            firestoreFavoritesRepository = firestoreRepo,
            coroutineScope = kotlinx.coroutines.CoroutineScope(testDispatcher)
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testCoreClusterSearchAndTelemetryReEnrichmentPipeline() = runTest(testDispatcher) {
        // Prepare 3 cached stations across 2 distant geographic regions (Hanoi & Da Nang)
        // Hanoi: ST_HN_01 and ST_HN_02 are ~5km apart (should form 1 search cluster)
        // Da Nang: ST_DN_01 is ~600km away (should form a 2nd search cluster)
        val cachedStation1 = Station(
            id = "ST_HN_01",
            name = "Tràng Tiền Raw",
            address = "Hà Nội",
            latitude = 21.0285,
            longitude = 105.8542,
            summary = "24/7",
            connectors = "60kW, 120kW",
            depotStatus = "Unknown",
            powers = emptyList(), // Missing telemetry
            totalPlugs = 0,
            totalAvailablePlugs = 0
        )
        val cachedStation2 = Station(
            id = "ST_HN_02",
            name = "Times City Raw",
            address = "Hà Nội",
            latitude = 20.9950,
            longitude = 105.8680,
            summary = "24/7",
            connectors = "250kW",
            depotStatus = "Unknown",
            powers = emptyList(), // Missing telemetry
            totalPlugs = 0,
            totalAvailablePlugs = 0
        )
        val cachedStation3 = Station(
            id = "ST_DN_01",
            name = "Đà Nẵng Raw",
            address = "Đà Nẵng",
            latitude = 16.0544,
            longitude = 108.2022,
            summary = "24/7",
            connectors = "60kW",
            depotStatus = "Unknown",
            powers = emptyList(), // Missing telemetry
            totalPlugs = 0,
            totalAvailablePlugs = 0
        )

        firestoreRepo.saveCachedFavorites(listOf(cachedStation1, cachedStation2, cachedStation3))
        advanceUntilIdle()

        // -----------------------------------------------------------------------------------------
        // CRITERION 1: getFavorites immediately emits cached favorites (0ms non-blocking)
        // -----------------------------------------------------------------------------------------
        val userLat = 21.0285
        val userLon = 105.8542
        val immediateResult = repository.getFavorites(userLat = userLat, userLon = userLon)
        assertTrue("getFavorites must succeed immediately", immediateResult.isSuccess)

        val immediateStations = immediateResult.getOrThrow()
        assertEquals("Must immediately return all 3 cached stations", 3, immediateStations.size)
        assertEquals("ST_HN_01", immediateStations[0].id)
        assertNotNull("Distance must be computed for immediate stations", immediateStations[0].distanceKm)
        // At this immediate 0ms moment, powers are still empty before background job executes
        assertEquals(0, immediateStations[0].powers.size)

        // Verify background job was dispatched
        assertNotNull("Background cluster enrichment job must be launched", repository.clusterEnrichmentJob)

        // -----------------------------------------------------------------------------------------
        // CRITERION 2: Geographic cluster coordinates grouping
        // -----------------------------------------------------------------------------------------
        advanceUntilIdle()
        repository.clusterEnrichmentJob?.join()

        // 3 stations grouped into 2 clusters: (Hanoi ~15km, Da Nang ~600km away)
        assertEquals("Should dispatch search for exactly 2 geographic clusters", 2, searchedCenters.size)

        val hanoiCenter = searchedCenters.find {
            DistanceCalculator.calculateDistanceKm(it.first, it.second, 21.0, 105.8) < 15.0
        }
        val daNangCenter = searchedCenters.find {
            DistanceCalculator.calculateDistanceKm(it.first, it.second, 16.0, 108.2) < 15.0
        }
        assertNotNull("Must contain a cluster search center for Hanoi region", hanoiCenter)
        assertNotNull("Must contain a cluster search center for Da Nang region", daNangCenter)

        // -----------------------------------------------------------------------------------------
        // CRITERION 3: Search results matched by location ID and merged into domain stations
        // -----------------------------------------------------------------------------------------
        val enrichedList = repository.favoritesState.value
        assertEquals(3, enrichedList.size)

        val enrichedHn1 = enrichedList.first { it.id == "ST_HN_01" }
        assertEquals("Tràng Tiền Plaza", enrichedHn1.name)
        assertEquals("24 Hai Bà Trưng, Hoàn Kiếm, Hà Nội", enrichedHn1.address)
        assertEquals(2, enrichedHn1.powers.size)
        assertEquals(3, enrichedHn1.totalAvailablePlugs) // 2 + 1
        assertEquals(6, enrichedHn1.totalPlugs) // 4 + 2
        assertEquals("Normal", enrichedHn1.depotStatus)
        assertFalse("isFreeParking was updated from search", enrichedHn1.isFreeParking)
        assertEquals("08:00 - 22:00", enrichedHn1.workingTimeDescription)
        assertEquals(1, enrichedHn1.images.size)
        assertEquals("https://cpo-prod-s3.vinfastauto.com/trangtien.jpg", enrichedHn1.effectiveImage)

        val enrichedHn2 = enrichedList.first { it.id == "ST_HN_02" }
        assertEquals("Times City", enrichedHn2.name)
        assertEquals(1, enrichedHn2.powers.size)
        assertEquals(4, enrichedHn2.totalAvailablePlugs)
        assertEquals(4, enrichedHn2.totalPlugs)
        assertTrue(enrichedHn2.isFreeParking)

        val enrichedDn1 = enrichedList.first { it.id == "ST_DN_01" }
        assertEquals("Cầu Rồng Đà Nẵng", enrichedDn1.name)
        assertEquals("Maintaining", enrichedDn1.depotStatus)
        assertEquals(1, enrichedDn1.powers.size)
        assertEquals(0, enrichedDn1.totalAvailablePlugs)
        assertEquals(2, enrichedDn1.totalPlugs)

        // -----------------------------------------------------------------------------------------
        // CRITERION 4: Enriched stations update repository state, local cache, and Cloud Firestore
        // -----------------------------------------------------------------------------------------
        val cachedAfterEnrichment = firestoreRepo.getCachedFavorites()
        assertEquals(3, cachedAfterEnrichment.size)
        val cachedHn1 = cachedAfterEnrichment.first { it.id == "ST_HN_01" }
        assertEquals(6, cachedHn1.totalPlugs)
        assertEquals(3, cachedHn1.totalAvailablePlugs)
        assertEquals(2, cachedHn1.powers.size)

        // Verify Firestore remote document received the enriched data
        val firestoreDoc = fakeRemoteDataSource.getRawDocument(testUserId)
        assertNotNull("Firestore document must be synced", firestoreDoc)
        @Suppress("UNCHECKED_CAST")
        val favoritesMap = firestoreDoc!!["favorites"] as? Map<String, Any>
        assertNotNull("Firestore favorites map must not be null", favoritesMap)
        assertTrue("Firestore must contain ST_HN_01", favoritesMap!!.containsKey("ST_HN_01"))

        @Suppress("UNCHECKED_CAST")
        val firestoreHn1 = favoritesMap["ST_HN_01"] as? Map<String, Any>
        assertNotNull(firestoreHn1)
        assertEquals(6, firestoreHn1!!["total_plugs"])
        assertEquals(3, firestoreHn1["total_available_plugs"])
        assertEquals("Normal", firestoreHn1["depot_status"])

        // -----------------------------------------------------------------------------------------
        // CRITERION 5: FavoritesViewModel pull-to-refresh & seamless UI/detail sync without flickering
        // -----------------------------------------------------------------------------------------
        val authEngine = AuthEngine(sessionManager = sessionManager)
        val viewModel = FavoritesViewModel(
            repository = repository,
            authEngine = authEngine,
            authService = fakeAuthService,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher
        )

        advanceUntilIdle()

        // Verify UI is in Success state with enriched stations
        val uiState = viewModel.uiState.value
        assertTrue("UI state must be Success", uiState is FavoritesUiState.Success)
        val uiStations = (uiState as FavoritesUiState.Success).stations
        assertEquals(3, uiStations.size)

        // Select station detail
        val stationToSelect = uiStations.first { it.id == "ST_HN_01" }
        viewModel.selectStationForDetail(stationToSelect)
        assertEquals("ST_HN_01", viewModel.selectedStationForDetail.value?.id)
        assertEquals("ST_HN_01", viewModel.stationDetailCoordinator.stationDetailState.value.station?.id)

        // Trigger manual pull-to-refresh
        searchedCenters.clear()
        viewModel.refreshFavorites()
        advanceUntilIdle()
        repository.clusterEnrichmentJob?.join()
        advanceUntilIdle()

        // Verify pull-to-refresh executed cluster search queries
        assertEquals(2, searchedCenters.size)

        // Verify UI state retained selection and updated detail sheet without flickering
        val refreshedUiState = viewModel.uiState.value as FavoritesUiState.Success
        assertFalse("isRefreshing must be false after completion", refreshedUiState.isRefreshing)
        assertEquals("ST_HN_01", refreshedUiState.selectedStationForDetail?.id)
        assertEquals("ST_HN_01", viewModel.stationDetailCoordinator.stationDetailState.value.station?.id)
        assertEquals(6, viewModel.stationDetailCoordinator.stationDetailState.value.station?.totalPlugs)
    }
}
