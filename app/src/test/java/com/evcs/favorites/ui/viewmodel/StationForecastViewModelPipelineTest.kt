package com.evcs.favorites.ui.viewmodel

import android.location.Location
import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.AuthEngine
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.cache.ForecastCache
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.data.routing.DrivingMetrics
import com.evcs.favorites.data.routing.MultiTierRoutingCoordinator
import com.evcs.favorites.data.routing.RoutingDestination
import com.evcs.favorites.data.routing.RoutingEngineType
import com.evcs.favorites.data.routing.RoutingSettings
import com.evcs.favorites.data.routing.TrafficCondition
import com.evcs.favorites.domain.location.LocationService
import com.evcs.favorites.domain.model.StationForecast
import com.evcs.favorites.domain.model.WattageOption
import com.evcs.favorites.ui.state.FavoritesUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
import org.junit.Before
import org.junit.Test

/**
 * Single comprehensive unit test verifying the ViewModel forecast enrichment pipeline
 * for Phase 03 across NearbyViewModel and FavoritesViewModel.
 *
 * Verifies:
 * 1. Top 5 nearest selection among full stations (totalPlugs > 0 && totalAvailablePlugs == 0) from visible list.
 * 2. Stations with available plugs > 0 or unverified totalPlugs == 0 are strictly excluded from forecast fetching.
 * 3. State progressively updates station with parsed forecast while preserving driving metrics, connectors, and sort order.
 * 4. Cancel-on-rescan, cancel-on-filter-change, and cancel-on-displacement behavior.
 * 5. forceRefresh clears cache and triggers fresh forecast retrieval.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StationForecastViewModelPipelineTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var authEngine: AuthEngine
    private lateinit var fakeLocationService: FakeLocationService
    private lateinit var fakeCoordinator: FakeRoutingCoordinator
    private lateinit var fakeRepository: TestEvcsRepository

    class FakeLocationService : LocationService() {
        var permissionGranted: Boolean = true
        var locationToReturn: Location? = null
            set(value) {
                field = value
                setLocation(value)
            }

        override fun hasLocationPermission(): Boolean = permissionGranted
        override suspend fun getFreshLocation(): Location? {
            setLocation(locationToReturn)
            return locationToReturn
        }
    }

    class FakeRoutingCoordinator : MultiTierRoutingCoordinator() {
        var callCount = 0

        override suspend fun calculateRoutes(
            originLat: Double,
            originLng: Double,
            destinations: List<RoutingDestination>,
            settings: RoutingSettings
        ): Map<String, DrivingMetrics> {
            callCount++
            return destinations.associate { d ->
                val index = d.id.filter { it.isDigit() }.toIntOrNull() ?: 1
                d.id to DrivingMetrics(
                    distanceMeters = (index * 1000).toLong(),
                    durationSeconds = (index * 120).toLong(),
                    trafficCondition = TrafficCondition.FREE_FLOW,
                    engineUsed = RoutingEngineType.OSRM
                )
            }
        }
    }

    class TestEvcsRepository(
        sessionStorage: InMemorySessionStorage,
        val testDispatcher: kotlinx.coroutines.CoroutineDispatcher,
        val timeProvider: () -> Long = { System.currentTimeMillis() }
    ) : EvcsRepository(
        apiClient = EvcsApiClient(SessionManager(sessionStorage)),
        cacheStorage = sessionStorage,
        forecastCache = ForecastCache(timeProvider = timeProvider),
        ioDispatcher = testDispatcher
    ) {
        var nearbyResult: Result<List<Station>> = Result.success(emptyList())
        var favoritesResult: Result<List<Station>> = Result.success(emptyList())

        val requestedForecastStationIds = mutableListOf<String>()
        val forceRefreshFlags = mutableListOf<Boolean>()
        var forecastResultProvider: (Station, Boolean) -> StationForecast? = { station, _ ->
            StationForecast(
                wattageKw = 60.0,
                vehicleCount = 2,
                minMinutes = 25,
                maxMinutes = 25,
                rawText = "Khoảng 25 phút nữa có thể có cổng trống (${station.name})"
            )
        }

        var delayDuringForecast: Long = 0L

        override suspend fun searchNearbyVinFast(lat: Double, lon: Double): Result<List<Station>> {
            return nearbyResult
        }

        override suspend fun getFavorites(
            userLat: Double?,
            userLon: Double?,
            autoResolveUnknownCoordinates: Boolean
        ): Result<List<Station>> {
            return favoritesResult
        }

        override suspend fun fetchStationForecast(
            station: Station,
            forceRefresh: Boolean
        ): Result<StationForecast?> {
            requestedForecastStationIds.add(station.id)
            forceRefreshFlags.add(forceRefresh)

            if (delayDuringForecast > 0) {
                delay(delayDuringForecast)
            }

            if (forceRefresh) {
                forecastCache.invalidate(station.id)
            } else {
                val cached = forecastCache.get(station.id)
                if (cached != null) {
                    return Result.success(cached)
                }
            }

            val forecast = forecastResultProvider(station, forceRefresh)
            if (forecast != null) {
                forecastCache.put(station.id, forecast)
            }
            return Result.success(forecast)
        }

        override suspend fun enrichStationsWithForecast(
            stations: List<Station>,
            forceRefresh: Boolean,
            onStationUpdated: ((Station) -> Unit)?
        ): List<Station> {
            return stations.map { station ->
                val result = fetchStationForecast(station, forceRefresh)
                val forecast = result.getOrNull()
                val enriched = if (forecast != null) station.copy(forecast = forecast) else station
                onStationUpdated?.invoke(enriched)
                enriched
            }
        }
    }

    private fun createMockLocation(lat: Double, lon: Double): Location {
        return object : Location("gps") {
            override fun getLatitude(): Double = lat
            override fun getLongitude(): Double = lon
        }
    }

    private fun createStation(
        id: String,
        name: String,
        lat: Double,
        lon: Double,
        availablePlugs: Int,
        totalPlugs: Int,
        distanceKm: Double = 1.0,
        drivingMetrics: DrivingMetrics? = null
    ): Station {
        val powers = if (totalPlugs > 0) {
            listOf(
                PowerPort(
                    typeWatts = 60_000L,
                    label = "60kW",
                    availablePlugs = availablePlugs,
                    totalPlugs = totalPlugs,
                    displayString = "60kW: trống $availablePlugs/$totalPlugs"
                )
            )
        } else emptyList()

        return Station(
            id = id,
            name = name,
            address = "Địa chỉ $id",
            latitude = lat,
            longitude = lon,
            summary = "24/7",
            connectors = if (totalPlugs > 0) "60kW" else "",
            depotStatus = "Normal",
            powers = powers,
            totalAvailablePlugs = availablePlugs,
            totalPlugs = totalPlugs,
            distanceKm = distanceKm,
            drivingMetrics = drivingMetrics
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        sessionStorage = InMemorySessionStorage()
        sessionManager = SessionManager(sessionStorage).apply {
            authCookie = "valid_auth_cookie"
        }
        authEngine = AuthEngine(sessionManager)
        fakeLocationService = FakeLocationService().apply {
            permissionGranted = true
            locationToReturn = createMockLocation(21.0, 105.0)
        }
        fakeCoordinator = FakeRoutingCoordinator()
        fakeRepository = TestEvcsRepository(sessionStorage, testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // -------------------------------------------------------------------------
    // NearbyViewModel Tests
    // -------------------------------------------------------------------------

    @Test
    fun testNearby_strictlySelectsTop5NearestFullStationsAndExcludesAvailableOrZeroPlugs() = runTest {
        // Create 10 stations:
        // - 7 full stations (totalPlugs > 0 && available == 0) at distances 1..7 km
        // - 2 stations with available plugs > 0 at distance 1.5, 2.5 km
        // - 1 station with totalPlugs == 0 at distance 0.5 km
        val stations = listOf(
            createStation("st_zero", "Zero Plugs", 21.001, 105.001, availablePlugs = 0, totalPlugs = 0, distanceKm = 0.5),
            createStation("st_full_1", "Full 1", 21.002, 105.002, availablePlugs = 0, totalPlugs = 4, distanceKm = 1.0),
            createStation("st_avail_1", "Available 1", 21.003, 105.003, availablePlugs = 2, totalPlugs = 4, distanceKm = 1.5),
            createStation("st_full_2", "Full 2", 21.004, 105.004, availablePlugs = 0, totalPlugs = 4, distanceKm = 2.0),
            createStation("st_avail_2", "Available 2", 21.005, 105.005, availablePlugs = 1, totalPlugs = 2, distanceKm = 2.5),
            createStation("st_full_3", "Full 3", 21.006, 105.006, availablePlugs = 0, totalPlugs = 6, distanceKm = 3.0),
            createStation("st_full_4", "Full 4", 21.007, 105.007, availablePlugs = 0, totalPlugs = 4, distanceKm = 4.0),
            createStation("st_full_5", "Full 5", 21.008, 105.008, availablePlugs = 0, totalPlugs = 2, distanceKm = 5.0),
            createStation("st_full_6", "Full 6", 21.009, 105.009, availablePlugs = 0, totalPlugs = 4, distanceKm = 6.0),
            createStation("st_full_7", "Full 7", 21.010, 105.010, availablePlugs = 0, totalPlugs = 4, distanceKm = 7.0)
        )
        fakeRepository.nearbyResult = Result.success(stations)

        val viewModel = NearbyViewModel(
            repository = fakeRepository,
            sessionManager = sessionManager,
            locationService = fakeLocationService,
            routingCoordinator = fakeCoordinator,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        viewModel.scanNearbyStations()
        advanceUntilIdle()

        // 1. Target Selection Strategy verification:
        // Strictly the first 5 full stations in sorted order: st_full_1, st_full_2, st_full_3, st_full_4, st_full_5
        assertEquals(5, fakeRepository.requestedForecastStationIds.size)
        val expectedIds = listOf("st_full_1", "st_full_2", "st_full_3", "st_full_4", "st_full_5")
        assertEquals(expectedIds, fakeRepository.requestedForecastStationIds)

        // Verifies excluded stations:
        assertFalse("Available stations must be excluded", fakeRepository.requestedForecastStationIds.contains("st_avail_1"))
        assertFalse("Available stations must be excluded", fakeRepository.requestedForecastStationIds.contains("st_avail_2"))
        assertFalse("Stations with 0 plugs must be excluded", fakeRepository.requestedForecastStationIds.contains("st_zero"))
        assertFalse("6th full station must be excluded (strictly take 5)", fakeRepository.requestedForecastStationIds.contains("st_full_6"))
        assertFalse("7th full station must be excluded (strictly take 5)", fakeRepository.requestedForecastStationIds.contains("st_full_7"))

        // 2. Progressive state update & driving metrics preservation verification:
        val displayStations = viewModel.uiState.value.top10DisplayStations
        for (id in expectedIds) {
            val st = displayStations.first { it.id == id }
            assertNotNull("Enriched full station must have forecast", st.forecast)
            assertEquals(60.0, st.forecast!!.wattageKw, 0.001)
            assertEquals(25, st.forecast!!.minMinutes)
            assertNotNull("Driving metrics must be preserved during forecast enrichment", st.drivingMetrics)
            assertEquals("Connectors string must be preserved", "60kW", st.connectors)
        }

        // Available stations retain null forecast
        val availSt = displayStations.first { it.id == "st_avail_1" }
        assertNull("Available station should have null forecast", availSt.forecast)
        assertNotNull("Available station preserves driving metrics", availSt.drivingMetrics)
    }

    @Test
    fun testNearby_cancellationOnRescanAndFilterToggles() = runTest {
        val stations = (1..6).map {
            createStation("st_full_$it", "Full $it", 21.0 + it * 0.001, 105.0 + it * 0.001, availablePlugs = 0, totalPlugs = 4)
        }
        fakeRepository.nearbyResult = Result.success(stations)
        fakeRepository.delayDuringForecast = 5000L // Long delay to verify in-flight cancellation

        val viewModel = NearbyViewModel(
            repository = fakeRepository,
            sessionManager = sessionManager,
            locationService = fakeLocationService,
            routingCoordinator = fakeCoordinator,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        viewModel.scanNearbyStations()
        testScheduler.advanceTimeBy(100)

        val activeForecastJob = viewModel.forecastJob
        assertNotNull("forecastJob should be active during enrichment", activeForecastJob)
        assertTrue("forecastJob should be active", activeForecastJob!!.isActive)

        // Rescan cancels active forecastJob
        viewModel.scanNearbyStations()
        assertTrue("Active forecastJob must be cancelled when scanNearbyStations() is called", activeForecastJob.isCancelled)

        // Toggle filter cancels active forecastJob
        testScheduler.advanceTimeBy(100)
        val secondForecastJob = viewModel.forecastJob
        assertNotNull("secondForecastJob should be active", secondForecastJob)
        viewModel.toggleWattageFilter(WattageOption.KW_60)
        assertTrue("Active forecastJob must be cancelled when toggleWattageFilter() is called", secondForecastJob!!.isCancelled)

        // Clear filter cancels active forecastJob
        testScheduler.advanceTimeBy(100)
        val thirdForecastJob = viewModel.forecastJob
        assertNotNull("thirdForecastJob should be active", thirdForecastJob)
        viewModel.clearWattageFilters()
        assertTrue("Active forecastJob must be cancelled when clearWattageFilters() is called", thirdForecastJob!!.isCancelled)
    }

    @Test
    fun testNearby_manualRefreshPassesForceRefreshTrueAndUpdatesForecast() = runTest {
        var currentForecastMinutes = 20
        fakeRepository.forecastResultProvider = { _, _ ->
            StationForecast(
                wattageKw = 60.0,
                vehicleCount = 1,
                minMinutes = currentForecastMinutes,
                maxMinutes = currentForecastMinutes,
                rawText = "Khoảng $currentForecastMinutes phút nữa"
            )
        }

        val stations = listOf(
            createStation("st_1", "Trạm 1", 21.001, 105.001, availablePlugs = 0, totalPlugs = 2)
        )
        fakeRepository.nearbyResult = Result.success(stations)

        val viewModel = NearbyViewModel(
            repository = fakeRepository,
            sessionManager = sessionManager,
            locationService = fakeLocationService,
            routingCoordinator = fakeCoordinator,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        // Initial scan: forceRefresh is false
        viewModel.scanNearbyStations()
        advanceUntilIdle()

        assertEquals(1, fakeRepository.forceRefreshFlags.size)
        assertFalse("Initial scan should have forceRefresh = false", fakeRepository.forceRefreshFlags[0])
        val initialForecast = viewModel.uiState.value.top10DisplayStations.first().forecast
        assertEquals(20, initialForecast?.minMinutes)

        // Update provider value and pull-to-refresh
        currentForecastMinutes = 45
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(2, fakeRepository.forceRefreshFlags.size)
        assertTrue("Manual refresh must trigger with forceRefresh = true", fakeRepository.forceRefreshFlags[1])

        val refreshedForecast = viewModel.uiState.value.top10DisplayStations.first().forecast
        assertEquals("Refreshed station should reflect fresh forecast value (45 mins)", 45, refreshedForecast?.minMinutes)
    }

    // -------------------------------------------------------------------------
    // FavoritesViewModel Tests
    // -------------------------------------------------------------------------

    @Test
    fun testFavorites_strictlySelectsTop5NearestFullStationsSortedByEta() = runTest {
        // Create 8 full stations and 2 available stations
        val stations = (1..10).map { i ->
            val isFull = i != 2 && i != 5
            createStation(
                id = "fav_st_$i",
                name = "Fav Trạm $i",
                lat = 21.0 + i * 0.001,
                lon = 105.0 + i * 0.001,
                availablePlugs = if (isFull) 0 else 2,
                totalPlugs = 4,
                distanceKm = i.toDouble()
            )
        }
        fakeRepository.favoritesResult = Result.success(stations)

        val viewModel = FavoritesViewModel(
            repository = fakeRepository,
            authEngine = authEngine,
            locationService = fakeLocationService,
            routingCoordinator = fakeCoordinator,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        viewModel.fetchFavorites()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is FavoritesUiState.Success)
        val successState = viewModel.uiState.value as FavoritesUiState.Success

        // Top 5 full stations strictly requested
        assertEquals(5, fakeRepository.requestedForecastStationIds.size)
        val expectedIds = listOf("fav_st_1", "fav_st_3", "fav_st_4", "fav_st_6", "fav_st_7")
        assertEquals(expectedIds, fakeRepository.requestedForecastStationIds)

        // Verify available stations excluded
        assertFalse(fakeRepository.requestedForecastStationIds.contains("fav_st_2"))
        assertFalse(fakeRepository.requestedForecastStationIds.contains("fav_st_5"))

        // Verify drivingMetrics and ETA ordering preserved
        for (id in expectedIds) {
            val st = successState.stations.first { it.id == id }
            assertNotNull("Full favorite station must have forecast attached", st.forecast)
            assertEquals(60.0, st.forecast!!.wattageKw, 0.001)
            assertNotNull("Driving metrics must be preserved during forecast enrichment", st.drivingMetrics)
        }

        // Verify ordering is shortest ETA first
        for (i in 0 until successState.stations.size - 1) {
            val eta1 = successState.stations[i].drivingMetrics?.durationSeconds ?: Long.MAX_VALUE
            val eta2 = successState.stations[i + 1].drivingMetrics?.durationSeconds ?: Long.MAX_VALUE
            assertTrue("Stations must remain sorted by shortest ETA ascending", eta1 <= eta2)
        }
    }

    @Test
    fun testFavorites_cancellationOnDisplacementAndRefresh() = runTest {
        val stations = (1..5).map {
            createStation("fav_st_$it", "Fav $it", 21.001 * it, 105.001 * it, availablePlugs = 0, totalPlugs = 4)
        }
        fakeRepository.favoritesResult = Result.success(stations)
        fakeRepository.delayDuringForecast = 5000L

        val viewModel = FavoritesViewModel(
            repository = fakeRepository,
            authEngine = authEngine,
            locationService = fakeLocationService,
            routingCoordinator = fakeCoordinator,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        viewModel.fetchFavorites()
        testScheduler.advanceTimeBy(100)

        val activeJob = viewModel.forecastJob
        assertNotNull("forecastJob should be active during favorites enrichment", activeJob)

        // Displacement > 200m triggers location update and cancels previous forecastJob
        viewModel.updateUserLocation(21.005, 105.005)
        assertTrue("Active forecastJob must be cancelled on displacement > 200m", activeJob!!.isCancelled)

        // Manual refreshFavorites cancels active forecastJob
        testScheduler.advanceTimeBy(100)
        val secondJob = viewModel.forecastJob
        assertNotNull("secondJob should be active", secondJob)
        viewModel.refreshFavorites()
        assertTrue("Active forecastJob must be cancelled on refreshFavorites()", secondJob!!.isCancelled)
    }

    @Test
    fun testFavorites_forceRefreshClearsCacheAndUpdatesForecast() = runTest {
        var forecastMinutes = 15
        fakeRepository.forecastResultProvider = { station, _ ->
            StationForecast(
                wattageKw = 250.0,
                vehicleCount = 1,
                minMinutes = forecastMinutes,
                maxMinutes = forecastMinutes,
                rawText = "Khoảng $forecastMinutes phút nữa (${station.id})"
            )
        }

        val stations = listOf(
            createStation("fav_refresh_1", "Fav 1", 21.001, 105.001, availablePlugs = 0, totalPlugs = 2)
        )
        fakeRepository.favoritesResult = Result.success(stations)

        val viewModel = FavoritesViewModel(
            repository = fakeRepository,
            authEngine = authEngine,
            locationService = fakeLocationService,
            routingCoordinator = fakeCoordinator,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        viewModel.fetchFavorites()
        advanceUntilIdle()

        var currentState = viewModel.uiState.value as FavoritesUiState.Success
        assertEquals(15, currentState.stations.first().forecast?.minMinutes)

        // Update forecast value and trigger refreshFavorites()
        forecastMinutes = 50
        viewModel.refreshFavorites()
        advanceUntilIdle()

        currentState = viewModel.uiState.value as FavoritesUiState.Success
        val updatedForecast = currentState.stations.first().forecast
        assertNotNull("Forecast should be attached", updatedForecast)
        assertEquals(250.0, updatedForecast!!.wattageKw, 0.001)
        assertEquals("Forecast should be updated to 50 minutes", 50, updatedForecast.minMinutes)
    }
}
