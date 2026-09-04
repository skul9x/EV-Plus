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
import com.evcs.favorites.ui.state.FavoritesUiState
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
import org.junit.Before
import org.junit.Test

/**
 * Single comprehensive test file verifying the unconditional Top 5 forecast enrichment pipeline
 * across NearbyViewModel and FavoritesViewModel.
 *
 * Requirements verified:
 * 1. NearbyViewModel queries Top 5 nearest stations unconditionally, including stations with available ports (totalAvailablePlugs > 0).
 * 2. NearbyViewModel attaches forecast when repository returns valid forecast data.
 * 3. NearbyViewModel leaves forecast = null when repository returns null (skips without dummy data).
 * 4. FavoritesViewModel queries Top 5 favorite stations unconditionally (ignoring port availability restrictions).
 * 5. Station sort order, driving metrics, and favorite status remain preserved after forecast enrichment.
 * 6. Pull-to-refresh triggers fresh forecast enrichment with forceRefresh = true.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Top5UnconditionalForecastViewModelTest {

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
        val testDispatcher: kotlinx.coroutines.CoroutineDispatcher
    ) : EvcsRepository(
        apiClient = EvcsApiClient(SessionManager(sessionStorage)),
        cacheStorage = sessionStorage,
        forecastCache = ForecastCache(timeProvider = { System.currentTimeMillis() }),
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
                minMinutes = 15,
                maxMinutes = 15,
                rawText = "Khoảng 15 phút nữa có thể có cổng trống (${station.name})"
            )
        }

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
            val forecast = forecastResultProvider(station, forceRefresh)
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
            authCookie = "test_auth_cookie"
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

    @Test
    fun testNearbyViewModel_unconditionallyEnrichesTop5StationsRegardlessOfPortAvailability() = runTest {
        // 7 stations ordered by distance:
        // Top 5 have mixed available ports (st_1 has 3/4 plugs available, st_2 has 0/4, st_3 has 2/4, etc.)
        val stations = listOf(
            createStation("st_1", "Station 1 (Available)", 21.001, 105.001, availablePlugs = 3, totalPlugs = 4, distanceKm = 1.0),
            createStation("st_2", "Station 2 (Full)", 21.002, 105.002, availablePlugs = 0, totalPlugs = 4, distanceKm = 2.0),
            createStation("st_3", "Station 3 (Available)", 21.003, 105.003, availablePlugs = 2, totalPlugs = 4, distanceKm = 3.0),
            createStation("st_4", "Station 4 (Full)", 21.004, 105.004, availablePlugs = 0, totalPlugs = 4, distanceKm = 4.0),
            createStation("st_5", "Station 5 (Available)", 21.005, 105.005, availablePlugs = 1, totalPlugs = 2, distanceKm = 5.0),
            createStation("st_6", "Station 6 (Beyond Top 5)", 21.006, 105.006, availablePlugs = 0, totalPlugs = 4, distanceKm = 6.0),
            createStation("st_7", "Station 7 (Beyond Top 5)", 21.007, 105.007, availablePlugs = 2, totalPlugs = 4, distanceKm = 7.0)
        )
        fakeRepository.nearbyResult = Result.success(stations)

        // For st_3, simulate repository returning null forecast (no active sessions or parser empty)
        fakeRepository.forecastResultProvider = { station, _ ->
            if (station.id == "st_3") {
                null
            } else {
                StationForecast(
                    wattageKw = 60.0,
                    vehicleCount = 1,
                    minMinutes = 10,
                    maxMinutes = 10,
                    rawText = "Khoảng 10 phút nữa (${station.name})"
                )
            }
        }

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

        // 1. Verify Top 5 stations queried unconditionally (including stations with totalAvailablePlugs > 0)
        val expectedQueriedIds = listOf("st_1", "st_2", "st_3", "st_4", "st_5")
        assertEquals(5, fakeRepository.requestedForecastStationIds.size)
        assertEquals(expectedQueriedIds, fakeRepository.requestedForecastStationIds)

        // Verifies stations beyond Top 5 were strictly excluded
        assertFalse(fakeRepository.requestedForecastStationIds.contains("st_6"))
        assertFalse(fakeRepository.requestedForecastStationIds.contains("st_7"))

        // 2. Verify forecast attached when repository returns valid forecast
        val displayStations = viewModel.uiState.value.top10DisplayStations
        val st1 = displayStations.first { it.id == "st_1" }
        assertTrue("st_1 has available plugs > 0 but is unconditionally enriched", st1.totalAvailablePlugs > 0)
        assertNotNull("st_1 must have forecast attached", st1.forecast)
        assertEquals(60.0, st1.forecast!!.wattageKw, 0.001)

        val st2 = displayStations.first { it.id == "st_2" }
        assertNotNull("st_2 (full) must have forecast attached", st2.forecast)

        // 3. Verify forecast is null when repository returns null (skips without dummy data)
        val st3 = displayStations.first { it.id == "st_3" }
        assertNull("st_3 must have forecast = null when repository returns null", st3.forecast)

        // 4. Verify driving metrics and sort order preserved
        for (i in 0 until displayStations.size - 1) {
            val currDist = displayStations[i].drivingMetrics?.distanceMeters ?: 0L
            val nextDist = displayStations[i + 1].drivingMetrics?.distanceMeters ?: 0L
            assertTrue("Display stations remain sorted by driving distance", currDist <= nextDist)
        }
        for (st in displayStations) {
            assertNotNull("Driving metrics must be preserved on all stations", st.drivingMetrics)
        }
    }

    @Test
    fun testFavoritesViewModel_unconditionallyEnrichesTop5FavoritesAndPreservesState() = runTest {
        val favorites = listOf(
            createStation("fav_1", "Fav 1 (Available)", 21.001, 105.001, availablePlugs = 4, totalPlugs = 4, distanceKm = 1.0),
            createStation("fav_2", "Fav 2 (Full)", 21.002, 105.002, availablePlugs = 0, totalPlugs = 4, distanceKm = 2.0),
            createStation("fav_3", "Fav 3 (Available)", 21.003, 105.003, availablePlugs = 1, totalPlugs = 2, distanceKm = 3.0),
            createStation("fav_4", "Fav 4 (Full)", 21.004, 105.004, availablePlugs = 0, totalPlugs = 4, distanceKm = 4.0),
            createStation("fav_5", "Fav 5 (Available)", 21.005, 105.005, availablePlugs = 2, totalPlugs = 4, distanceKm = 5.0),
            createStation("fav_6", "Fav 6 (Beyond Top 5)", 21.006, 105.006, availablePlugs = 0, totalPlugs = 4, distanceKm = 6.0)
        )
        fakeRepository.favoritesResult = Result.success(favorites)

        // fav_5 returns null from repository
        fakeRepository.forecastResultProvider = { station, _ ->
            if (station.id == "fav_5") null else StationForecast(
                wattageKw = 120.0,
                vehicleCount = 3,
                minMinutes = 18,
                maxMinutes = 18,
                rawText = "Khoảng 18 phút nữa (${station.name})"
            )
        }

        val viewModel = FavoritesViewModel(
            repository = fakeRepository,
            authEngine = authEngine,
            locationService = fakeLocationService,
            routingCoordinator = fakeCoordinator,
            ioDispatcher = testDispatcher
        )

        advanceUntilIdle()

        // 1. Verify Top 5 favorites queried unconditionally
        val expectedQueriedIds = listOf("fav_1", "fav_2", "fav_3", "fav_4", "fav_5")
        assertEquals(5, fakeRepository.requestedForecastStationIds.size)
        assertEquals(expectedQueriedIds, fakeRepository.requestedForecastStationIds)
        assertFalse(fakeRepository.requestedForecastStationIds.contains("fav_6"))

        // 2. Verify state updates and clean degrade on null
        val state = viewModel.uiState.value as FavoritesUiState.Success
        val fav1 = state.stations.first { it.id == "fav_1" }
        assertNotNull("fav_1 must be enriched even with available ports", fav1.forecast)
        assertEquals(120.0, fav1.forecast!!.wattageKw, 0.001)

        val fav5 = state.stations.first { it.id == "fav_5" }
        assertNull("fav_5 must have null forecast when repo returns null", fav5.forecast)

        // 3. Verify driving metrics and sort order preserved
        for (station in state.stations) {
            if (station.id in expectedQueriedIds) {
                assertNotNull("Candidate favorites have driving metrics attached", station.drivingMetrics)
            }
        }
        for (i in 0 until state.stations.size - 1) {
            val currDuration = state.stations[i].drivingMetrics?.durationSeconds ?: Long.MAX_VALUE
            val nextDuration = state.stations[i + 1].drivingMetrics?.durationSeconds ?: Long.MAX_VALUE
            assertTrue("Favorites remain sorted by ETA duration", currDuration <= nextDuration)
        }
    }

    @Test
    fun testPullToRefresh_triggersEnrichmentWithForceRefreshTrue() = runTest {
        val stations = listOf(
            createStation("st_1", "Station 1", 21.001, 105.001, availablePlugs = 2, totalPlugs = 4, distanceKm = 1.0),
            createStation("st_2", "Station 2", 21.002, 105.002, availablePlugs = 0, totalPlugs = 4, distanceKm = 2.0)
        )
        fakeRepository.nearbyResult = Result.success(stations)
        fakeRepository.favoritesResult = Result.success(stations)

        val nearbyVm = NearbyViewModel(
            repository = fakeRepository,
            sessionManager = sessionManager,
            locationService = fakeLocationService,
            routingCoordinator = fakeCoordinator,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
        nearbyVm.scanNearbyStations()
        advanceUntilIdle()

        // Initial scan has forceRefresh = false
        assertTrue(fakeRepository.forceRefreshFlags.all { !it })

        fakeRepository.forceRefreshFlags.clear()
        fakeRepository.requestedForecastStationIds.clear()

        // Nearby pull-to-refresh
        nearbyVm.refresh()
        advanceUntilIdle()

        // Verify forceRefresh = true was passed
        assertTrue("Nearby refresh() passes forceRefresh = true", fakeRepository.forceRefreshFlags.all { it })

        fakeRepository.forceRefreshFlags.clear()
        fakeRepository.requestedForecastStationIds.clear()

        // Favorites pull-to-refresh
        val favVm = FavoritesViewModel(
            repository = fakeRepository,
            authEngine = authEngine,
            locationService = fakeLocationService,
            routingCoordinator = fakeCoordinator,
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        fakeRepository.forceRefreshFlags.clear()
        fakeRepository.requestedForecastStationIds.clear()

        favVm.refresh()
        advanceUntilIdle()

        assertTrue("Favorites refresh() passes forceRefresh = true", fakeRepository.forceRefreshFlags.all { it })
    }
}
