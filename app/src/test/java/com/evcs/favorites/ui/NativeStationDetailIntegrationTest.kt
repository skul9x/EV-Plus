package com.evcs.favorites.ui

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.AuthEngine
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.data.repository.EvcsTelemetryRepository
import com.evcs.favorites.data.telemetry.EvcsTelemetryDataSource
import com.evcs.favorites.domain.Station24hStatsCalculator
import com.evcs.favorites.domain.StationAccessTokens
import com.evcs.favorites.domain.StationRating
import com.evcs.favorites.domain.StationTelemetry
import com.evcs.favorites.domain.location.LocationService
import com.evcs.favorites.ui.components.NativeStationDetailSheetHelper
import com.evcs.favorites.ui.viewmodel.FavoritesViewModel
import com.evcs.favorites.ui.viewmodel.NearbyViewModel
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Single Comprehensive Verification Test for Phase 05:
 * Screen Integration & Legacy WebView Decoupling (`NativeStationDetailIntegrationTest.kt`).
 *
 * Verifies:
 * 1. Full integration flow: Selecting a station card opens native bottom sheet with populated data and zero delay (<50ms).
 * 2. Clicking "Chỉ đường" triggers Google Maps navigation intent correctly.
 * 3. Clicking "Yêu thích" updates favorite state in repository and cloud sync across both tabs.
 * 4. Dismissing the bottom sheet cleanly clears state and tears down all jobs without leaks.
 * 5. Zero WebView references, WebCore imports, or AndroidView components remaining in station detail presentation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NativeStationDetailIntegrationTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var storage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var authEngine: AuthEngine
    private lateinit var fakeEvcsRepo: FakeEvcsRepo
    private lateinit var fakeLocationService: FakeLocationService
    private lateinit var fakeTelemetryRepo: FakeEvcsTelemetryRepository

    private val sampleStation1 = Station(
        id = "C.BNI0012",
        name = "VinFast - TTTM Dabaco Mart Quế Võ",
        address = "Phố Mới, Huyện Quế Võ, Bắc Ninh",
        latitude = 21.1438,
        longitude = 106.1662,
        summary = "Mở 24/7",
        connectors = "120kW, 60kW",
        depotStatus = "Normal",
        distanceKm = 3.2,
        powers = listOf(
            PowerPort(typeWatts = 120000, totalPlugs = 2, availablePlugs = 1),
            PowerPort(typeWatts = 60000, totalPlugs = 4, availablePlugs = 3)
        ),
        totalAvailablePlugs = 4,
        totalPlugs = 6
    )

    private val sampleStation2 = Station(
        id = "C.HN0099",
        name = "VinFast - Vinhomes Ocean Park",
        address = "Gia Lâm, Hà Nội",
        latitude = 20.9980,
        longitude = 105.9430,
        summary = "Mở 24/7",
        connectors = "250kW, 150kW",
        depotStatus = "Normal",
        distanceKm = 12.5,
        powers = listOf(
            PowerPort(typeWatts = 250000, totalPlugs = 4, availablePlugs = 0),
            PowerPort(typeWatts = 150000, totalPlugs = 4, availablePlugs = 2)
        ),
        totalAvailablePlugs = 2,
        totalPlugs = 8
    )

    class FakeEvcsRepo(
        storage: InMemorySessionStorage
    ) : EvcsRepository(
        apiClient = EvcsApiClient(SessionManager(storage)),
        cacheStorage = storage
    ) {
        val favoritesList = mutableListOf<Station>()
        var addFavoriteCalled = false
        var removeFavoriteCalled = false
        var lastToggledStationId: String? = null

        override suspend fun getFavorites(
            userLat: Double?,
            userLon: Double?,
            autoResolveUnknownCoordinates: Boolean
        ): Result<List<Station>> {
            return Result.success(favoritesList.toList())
        }

        override suspend fun addFavoriteStation(station: Station): Result<Unit> {
            addFavoriteCalled = true
            lastToggledStationId = station.id
            if (favoritesList.none { it.id == station.id }) {
                favoritesList.add(station)
            }
            return Result.success(Unit)
        }

        override suspend fun removeFavoriteStation(locationId: String): Result<Unit> {
            removeFavoriteCalled = true
            lastToggledStationId = locationId
            favoritesList.removeAll { it.id == locationId }
            return Result.success(Unit)
        }

        override suspend fun searchNearbyVinFast(
            lat: Double,
            lon: Double
        ): Result<List<Station>> {
            return Result.success(favoritesList.toList())
        }
    }

    class FakeLocationService : LocationService() {
        override fun hasLocationPermission(): Boolean = true
        override suspend fun getFreshLocation(): android.location.Location? = null
    }

    class FakeEvcsTelemetryRepository(
        sessionManager: SessionManager
    ) : EvcsTelemetryRepository(
        dataSource = EvcsTelemetryDataSource(sessionManager),
        statsCalculator = Station24hStatsCalculator,
        ioDispatcher = Dispatchers.Unconfined
    ) {
        var tokensCount = 0
        var liveChargingCount = 0
        var historyCount = 0
        var pingCount = 0

        override suspend fun fetchStationTokens(station: Station): Result<StationAccessTokens> {
            tokensCount++
            return Result.success(
                StationAccessTokens(
                    chargeToken = "charge_tok_test",
                    apiToken = "api_tok_test",
                    rating = StationRating(avg = 4.9, count = 30, mine = 5)
                )
            )
        }

        override suspend fun fetchLiveCharging(stationId: String, chargeToken: String, isVin: Boolean): Result<StationTelemetry> {
            liveChargingCount++
            return Result.success(
                StationTelemetry(
                    busyByKw = mapOf(120 to 1, 60 to 1),
                    rawTicker = "Dự kiến 1 xe sạc trụ 120kW sẽ xong trong 5 phút nữa",
                    cleanForecast = "Dự kiến 1 xe sạc trụ 120kW sẽ xong trong 5 phút nữa",
                    isLocked = false
                )
            )
        }

        override suspend fun fetch24hHistory(stationId: String, apiToken: String): Result<List<Pair<Long, Int>>> {
            historyCount++
            return Result.success(
                listOf(
                    1725400000000L to 3,
                    1725403600000L to 5,
                    1725407200000L to 6,
                    1725410800000L to 4
                )
            )
        }

        override suspend fun sendTelemetryUpdate(stationId: String, apiToken: String, totalBusyCars: Int): Result<Unit> {
            pingCount++
            return Result.success(Unit)
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        storage = InMemorySessionStorage()
        sessionManager = SessionManager(storage)
        authEngine = AuthEngine(sessionManager)
        fakeEvcsRepo = FakeEvcsRepo(storage)
        fakeLocationService = FakeLocationService()
        fakeTelemetryRepo = FakeEvcsTelemetryRepository(sessionManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // =========================================================================
    // 1. Full integration flow: Selecting station card opens native bottom sheet with zero delay
    // =========================================================================
    @Test
    fun testSelectStationCardOpensNativeSheetWithInstantDataAndFullEnrichment() = runTest(testDispatcher) {
        val favoritesViewModel = FavoritesViewModel(
            repository = fakeEvcsRepo,
            authEngine = authEngine,
            locationService = fakeLocationService,
            dispatcher = testDispatcher,
            telemetryRepository = fakeTelemetryRepo,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher
        )

        val nearbyViewModel = NearbyViewModel(
            repository = fakeEvcsRepo,
            sessionManager = sessionManager,
            locationService = fakeLocationService,
            telemetryRepository = fakeTelemetryRepo,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher
        )

        // --- Favorites Tab Flow ---
        // 1. Synchronous Instant Opening (0ms delay)
        favoritesViewModel.selectStationForDetail(sampleStation1)
        val initialDetailState = favoritesViewModel.stationDetailState.value
        assertEquals("Station must match selectedStation", sampleStation1, initialDetailState.station)
        assertTrue("Telemetry must indicate loading during Stage 1", initialDetailState.isLoadingTelemetry)
        assertTrue("Stats must indicate loading during Stage 2", initialDetailState.isLoadingStats)
        assertEquals("Initial ports must be instantly populated", 2, initialDetailState.portStatuses.size)

        // 2. Complete asynchronous stages
        advanceUntilIdle()
        val enrichedDetailState = favoritesViewModel.stationDetailState.value
        assertFalse("Telemetry loading must finish", enrichedDetailState.isLoadingTelemetry)
        assertFalse("Stats loading must finish", enrichedDetailState.isLoadingStats)
        assertNotNull("Rating must be populated from handshake", enrichedDetailState.rating)
        assertEquals(4.9, enrichedDetailState.rating?.avg ?: 0.0, 0.01)
        assertEquals(30, enrichedDetailState.rating?.count)
        assertEquals(
            "Clean forecast must be populated without lock banners",
            "Dự kiến 1 xe sạc trụ 120kW sẽ xong trong 5 phút nữa",
            enrichedDetailState.cleanForecast
        )
        assertNotNull("24h stats must be computed", enrichedDetailState.stats24h)
        assertEquals("Peak usage must match max points", 6, enrichedDetailState.stats24h?.peakUsage)

        // --- Nearby Tab Flow ---
        nearbyViewModel.selectStationForDetail(sampleStation2)
        val initialNearbyDetailState = nearbyViewModel.stationDetailState.value
        assertEquals(sampleStation2, initialNearbyDetailState.station)
        assertEquals(2, initialNearbyDetailState.portStatuses.size)

        advanceUntilIdle()
        val enrichedNearbyDetailState = nearbyViewModel.stationDetailState.value
        assertEquals(sampleStation2, enrichedNearbyDetailState.station)
        assertNotNull(enrichedNearbyDetailState.rating)
        assertNotNull(enrichedNearbyDetailState.stats24h)
    }

    // =========================================================================
    // 2. Clicking "Chỉ đường" triggers Google Maps navigation intent correctly
    // =========================================================================
    @Test
    fun testClickingNavigateGeneratesProperGoogleMapsIntentSpec() {
        val navSpec = NativeStationDetailSheetHelper.buildNavigationIntentSpec(sampleStation1)

        assertEquals("Intent action must be ACTION_VIEW", "android.intent.action.VIEW", navSpec.action)
        assertEquals("Target package must be Google Maps", "com.google.android.apps.maps", navSpec.packageName)
        assertTrue("URI must specify geo coordinates", navSpec.uriString.contains("geo:0,0?q=21.1438,106.1662"))
        assertTrue("URI must include encoded station name label", navSpec.uriString.contains("VinFast"))
    }

    // =========================================================================
    // 3. Clicking "Yêu thích" updates favorite state in repository and cloud sync
    // =========================================================================
    @Test
    fun testClickingFavoriteUpdatesRepositoryAndCloudSync() = runTest(testDispatcher) {
        fakeEvcsRepo.favoritesList.add(sampleStation1)

        val favoritesViewModel = FavoritesViewModel(
            repository = fakeEvcsRepo,
            authEngine = authEngine,
            locationService = fakeLocationService,
            dispatcher = testDispatcher,
            telemetryRepository = fakeTelemetryRepo,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher
        )

        val nearbyViewModel = NearbyViewModel(
            repository = fakeEvcsRepo,
            sessionManager = sessionManager,
            locationService = fakeLocationService,
            telemetryRepository = fakeTelemetryRepo,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher
        )

        // 1. Remove favorite from FavoritesViewModel
        favoritesViewModel.selectStationForDetail(sampleStation1)
        favoritesViewModel.removeFavorite(sampleStation1.id)
        advanceUntilIdle()

        assertTrue("removeFavorite must call repository", fakeEvcsRepo.removeFavoriteCalled)
        assertEquals("Station id must match", sampleStation1.id, fakeEvcsRepo.lastToggledStationId)
        assertNull("Selected station must clear when deleted", favoritesViewModel.selectedStationForDetail.value)

        // 2. Toggle favorite in NearbyViewModel (add favorite)
        sessionManager.saveAuthCookie("auth_token_test_123")
        fakeEvcsRepo.addFavoriteCalled = false
        fakeEvcsRepo.lastToggledStationId = null

        nearbyViewModel.toggleFavorite(sampleStation2)
        advanceUntilIdle()

        assertTrue("toggleFavorite must call repository to add", fakeEvcsRepo.addFavoriteCalled)
        assertEquals("Station id must match", sampleStation2.id, fakeEvcsRepo.lastToggledStationId)
    }

    // =========================================================================
    // 4. Dismissing bottom sheet cleanly clears state and tears down all jobs
    // =========================================================================
    @Test
    fun testDismissingSheetCleanlyClearsStateAndTearsDownActiveJobs() = runTest(testDispatcher) {
        val favoritesViewModel = FavoritesViewModel(
            repository = fakeEvcsRepo,
            authEngine = authEngine,
            locationService = fakeLocationService,
            dispatcher = testDispatcher,
            telemetryRepository = fakeTelemetryRepo,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher
        )

        favoritesViewModel.selectStationForDetail(sampleStation1)
        assertTrue(favoritesViewModel.stationDetailCoordinator.activeJob?.isActive == true)
        assertNotNull(favoritesViewModel.stationDetailState.value.station)

        // Dismiss sheet
        favoritesViewModel.dismissStationDetail()

        // Verify clean reset
        assertNull("Station must be null after dismissal", favoritesViewModel.stationDetailState.value.station)
        assertNull("Selected station must be null", favoritesViewModel.selectedStationForDetail.value)
        assertFalse("Active job must be cancelled or null", favoritesViewModel.stationDetailCoordinator.activeJob?.isActive == true)
        assertNull("Active job reference must be cleared", favoritesViewModel.stationDetailCoordinator.activeJob)
        assertTrue("Port statuses must be empty after dismiss", favoritesViewModel.stationDetailState.value.portStatuses.isEmpty())
        assertNull("Clean forecast must be cleared", favoritesViewModel.stationDetailState.value.cleanForecast)
        assertNull("24h stats must be cleared", favoritesViewModel.stationDetailState.value.stats24h)
    }

    // =========================================================================
    // 5. Zero WebView references or AndroidView components remaining in station detail presentation
    // =========================================================================
    @Test
    fun testZeroWebViewReferencesInStationDetailPresentation() {
        fun resolveFile(relativePath: String): File {
            val direct = File(relativePath)
            if (direct.exists()) return direct
            val sub = File("app/$relativePath")
            if (sub.exists()) return sub
            val parent = File("../$relativePath")
            if (parent.exists()) return parent
            return direct
        }

        // 1. Verify StationDetailModal.kt file is completely removed from codebase
        val legacyModalFile = resolveFile("app/src/main/java/com/evcs/favorites/ui/components/StationDetailModal.kt")
        assertFalse("StationDetailModal.kt must be deleted from app source", legacyModalFile.exists())

        // 2. Verify StationDetailModal class is not found by reflection
        try {
            Class.forName("com.evcs.favorites.ui.components.StationDetailModalKt")
            fail("StationDetailModalKt class should not exist in classpath")
        } catch (_: ClassNotFoundException) {
            // Success
        }

        // 3. Verify NativeStationDetailSheet.kt contains NO AndroidView or WebView references
        val nativeSheetFile = resolveFile("app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt")
        assertTrue("NativeStationDetailSheet.kt must exist", nativeSheetFile.exists())
        val nativeSheetContent = nativeSheetFile.readText()

        assertFalse(
            "NativeStationDetailSheet must not contain AndroidView",
            nativeSheetContent.contains("AndroidView")
        )
        assertFalse(
            "NativeStationDetailSheet must not import android.webkit.WebView",
            nativeSheetContent.contains("android.webkit.WebView")
        )
        assertFalse(
            "NativeStationDetailSheet must not import WebSettings",
            nativeSheetContent.contains("WebSettings")
        )
        assertFalse(
            "NativeStationDetailSheet must not contain FORECAST_OVERLAP_FIX_CSS",
            nativeSheetContent.contains("FORECAST_OVERLAP_FIX_CSS")
        )

        // 4. Verify screens (FavoritesScreen, NearbyScreen, MainActivity) have no references to StationDetailModal
        val favScreenContent = resolveFile("app/src/main/java/com/evcs/favorites/ui/screens/FavoritesScreen.kt").readText()
        val nearbyScreenContent = resolveFile("app/src/main/java/com/evcs/favorites/ui/screens/NearbyScreen.kt").readText()
        val mainActivityContent = resolveFile("app/src/main/java/com/evcs/favorites/MainActivity.kt").readText()

        assertFalse("FavoritesScreen must not reference StationDetailModal", favScreenContent.contains("StationDetailModal"))
        assertFalse("NearbyScreen must not reference StationDetailModal", nearbyScreenContent.contains("StationDetailModal"))
        assertFalse("MainActivity must not reference StationDetailModal", mainActivityContent.contains("StationDetailModal"))

        assertTrue("FavoritesScreen must use NativeStationDetailSheet", favScreenContent.contains("NativeStationDetailSheet"))
        assertTrue("NearbyScreen must use NativeStationDetailSheet", nearbyScreenContent.contains("NativeStationDetailSheet"))
    }
}
