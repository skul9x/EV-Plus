package com.evcs.favorites.ui

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.AuthEngine
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.network.here.model.HereAddress
import com.evcs.favorites.data.network.here.model.HereConnector
import com.evcs.favorites.data.network.here.model.HereConnectorStatus
import com.evcs.favorites.data.network.here.model.HereEvStation
import com.evcs.favorites.data.network.here.model.HereModelMapper
import com.evcs.favorites.data.network.here.model.HerePosition
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.data.repository.EvcsTelemetryRepository
import com.evcs.favorites.data.routing.DrivingMetrics
import com.evcs.favorites.data.routing.MultiTierRoutingCoordinator
import com.evcs.favorites.data.routing.RoutingDestination
import com.evcs.favorites.data.routing.RoutingEngineType
import com.evcs.favorites.data.routing.RoutingSettings
import com.evcs.favorites.data.routing.TrafficCondition
import com.evcs.favorites.data.telemetry.EvcsTelemetryDataSource
import com.evcs.favorites.domain.Station24hStatsCalculator
import com.evcs.favorites.domain.StationAccessTokens
import com.evcs.favorites.domain.StationPortStatus
import com.evcs.favorites.domain.StationRating
import com.evcs.favorites.domain.StationTelemetry
import com.evcs.favorites.ui.state.FavoritesUiState
import com.evcs.favorites.ui.viewmodel.FavoritesViewModel
import com.evcs.favorites.ui.viewmodel.StationDetailCoordinator
import com.evcs.favorites.util.StationUrlBuilder
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
import java.io.IOException

/**
 * Single Comprehensive Verification Test for Phase 02:
 * Station Detail State Synchronization & AC Station Safety.
 *
 * Verifies:
 * 1. HereModelMapper assigns dynamic EVSE (AC, third-party CPO, VinFast) without hardcoding "VinFast",
 *    preventing invalid VinFast URLs in StationUrlBuilder.
 * 2. StationDetailCoordinator safely handles AC and third-party stations without triggering failing web scraping
 *    requests or error toast states, and gracefully handles HTTP 404 responses.
 * 3. StationDetailCoordinator.updateStation preserves port statuses while synchronizing updated drivingMetrics.
 * 4. FavoritesViewModel propagates computed driving metrics and telemetry updates to StationDetailCoordinator
 *    when a detail sheet is active.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesDetailStateAndAcSafetyTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var storage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var authEngine: AuthEngine
    private lateinit var fakeTelemetryRepo: FakeEvcsTelemetryRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        storage = InMemorySessionStorage()
        sessionManager = SessionManager(storage)
        authEngine = AuthEngine(sessionManager)
        fakeTelemetryRepo = FakeEvcsTelemetryRepository(sessionManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // =========================================================================
    // Criterion 1: HereModelMapper dynamic EVSE attribution & URL safety
    // =========================================================================

    @Test
    fun testHereModelMapper_assignsDynamicEvse_preventsInvalidVinFastUrls() {
        // 1. Pure AC Station from third-party / HERE
        val hereAcStation = HereEvStation(
            id = "here_ac_001",
            cpoId = null,
            name = "Evolt AC Charging Hub",
            address = HereAddress(street = "123 Le Loi", city = "Da Nang"),
            position = HerePosition(latitude = 16.0544, longitude = 108.2022),
            rawConnectors = listOf(
                HereConnector(
                    id = "c1",
                    maxPowerLevel = 22.0,
                    powerType = "AC_3_PHASE",
                    numberOfAvailable = 2,
                    numberOfConnectors = 2,
                    rawStatuses = listOf(
                        HereConnectorStatus(state = "AVAILABLE"),
                        HereConnectorStatus(state = "AVAILABLE")
                    )
                )
            )
        )
        val mappedAcStation = HereModelMapper.mapStationToDomain(hereAcStation, dcOnly = false)
        assertEquals("AC", mappedAcStation.evse)
        assertFalse("Must not be hardcoded VinFast", mappedAcStation.evse.equals("VinFast", ignoreCase = true))

        val acUrl = StationUrlBuilder.buildStationDetailUrl(mappedAcStation)
        assertFalse("AC station URL must not contain /tram-sac-vinfast-", acUrl.contains("/tram-sac-vinfast-"))
        assertTrue("AC station URL must contain -c. partner format", acUrl.contains("-c."))

        // 2. Explicit third-party CPO Station (e.g. EVN)
        val hereEvnStation = HereEvStation(
            id = "evn_002",
            cpoId = "EVN",
            name = "EVN Quick Charge",
            address = HereAddress(street = "456 Tran Phu", city = "Ha Noi"),
            position = HerePosition(latitude = 21.0285, longitude = 105.8542),
            rawConnectors = listOf(
                HereConnector(
                    id = "c2",
                    maxPowerLevel = 60.0,
                    powerType = "DC",
                    numberOfAvailable = 1,
                    numberOfConnectors = 2,
                    rawStatuses = listOf(
                        HereConnectorStatus(state = "AVAILABLE"),
                        HereConnectorStatus(state = "OCCUPIED")
                    )
                )
            )
        )
        val mappedEvnStation = HereModelMapper.mapStationToDomain(hereEvnStation, dcOnly = true)
        assertEquals("EVN", mappedEvnStation.evse)

        val evnUrl = StationUrlBuilder.buildStationDetailUrl(mappedEvnStation)
        assertFalse("EVN station URL must not generate VinFast slug", evnUrl.contains("/tram-sac-vinfast-"))
        assertTrue("EVN station URL must contain evn slug", evnUrl.contains("/tram-sac-evn"))

        // 3. VinFast Station Attribution
        val hereVfStation = HereEvStation(
            id = "vf_003",
            cpoId = "VinFast",
            name = "Trạm sạc VinFast Times City",
            address = HereAddress(street = "458 Minh Khai", city = "Ha Noi"),
            position = HerePosition(latitude = 20.9984, longitude = 105.8675),
            rawConnectors = listOf(
                HereConnector(
                    id = "c3",
                    maxPowerLevel = 60.0,
                    powerType = "DC",
                    numberOfAvailable = 4,
                    numberOfConnectors = 4,
                    rawStatuses = listOf(
                        HereConnectorStatus(state = "AVAILABLE")
                    )
                )
            )
        )
        val mappedVfStation = HereModelMapper.mapStationToDomain(hereVfStation, dcOnly = true)
        assertEquals("VinFast", mappedVfStation.evse)

        val vfUrl = StationUrlBuilder.buildStationDetailUrl(mappedVfStation)
        assertTrue("VinFast station URL must generate tram-sac-vinfast-", vfUrl.contains("/tram-sac-vinfast-"))
    }

    // =========================================================================
    // Criterion 2: StationDetailCoordinator AC / Partner Safety & 404 Resilience
    // =========================================================================

    @Test
    fun testStationDetailCoordinator_safelyHandlesAcAndPartnerStationsWithoutWebScrapingOrErrors() = runTest(testDispatcher) {
        val coordinator = StationDetailCoordinator(
            coroutineScope = this,
            telemetryRepository = fakeTelemetryRepo,
            ioDispatcher = testDispatcher,
            mainDispatcher = testDispatcher
        )

        // Case A: Pure AC Station (22kW only) -> Must bypass web scraping tokens
        val acStation = Station(
            id = "ac_test_01",
            name = "Evolt AC Charging Post",
            address = "123 Nguyen Hue",
            latitude = 10.7769,
            longitude = 106.7009,
            summary = "Trống 2/2 cổng",
            connectors = "22kW",
            depotStatus = "Normal",
            powers = listOf(
                PowerPort(typeWatts = 22000L, label = "22kW", availablePlugs = 2, totalPlugs = 2)
            ),
            totalAvailablePlugs = 2,
            totalPlugs = 2,
            evse = "AC"
        )

        coordinator.selectStationForDetail(acStation)
        advanceUntilIdle()

        val acState = coordinator.stationDetailState.value
        assertEquals("fetchStationTokens must be bypassed for pure AC stations", 0, fakeTelemetryRepo.fetchTokensCount)
        assertFalse("isLoadingTelemetry must be false", acState.isLoadingTelemetry)
        assertFalse("isLoadingStats must be false", acState.isLoadingStats)
        assertNull("Error must be null", acState.error)
        assertEquals("Initial 22kW port status must be retained", 1, acState.portStatuses.size)
        assertEquals(22, acState.portStatuses[0].kw)
        assertEquals(2, acState.portStatuses[0].availablePorts)

        // Case B: Third-Party Station (EVSE = "EVN") -> Must bypass web scraping tokens
        val evnStation = Station(
            id = "evn_test_02",
            name = "EVN Fast Charger",
            address = "456 Bach Dang",
            latitude = 16.0678,
            longitude = 108.2208,
            summary = "Trống 1/2 cổng",
            connectors = "60kW",
            depotStatus = "Normal",
            powers = listOf(
                PowerPort(typeWatts = 60000L, label = "60kW", availablePlugs = 1, totalPlugs = 2)
            ),
            totalAvailablePlugs = 1,
            totalPlugs = 2,
            evse = "EVN"
        )

        coordinator.selectStationForDetail(evnStation)
        advanceUntilIdle()

        val evnState = coordinator.stationDetailState.value
        assertEquals("fetchStationTokens must not be called for non-VinFast station", 0, fakeTelemetryRepo.fetchTokensCount)
        assertNull("Error must be null for non-VinFast station", evnState.error)
        assertFalse(evnState.isLoadingTelemetry)

        // Case C: VinFast Station experiencing HTTP 404 -> Must gracefully handle without perpetual error
        val vfStation = Station(
            id = "vf_404_test",
            name = "VinFast Station With 404 Web Scrape",
            address = "789 Le Duan",
            latitude = 21.0200,
            longitude = 105.8400,
            summary = "Trống 2/4 cổng",
            connectors = "60kW, 150kW",
            depotStatus = "Normal",
            powers = listOf(
                PowerPort(typeWatts = 60000L, label = "60kW", availablePlugs = 1, totalPlugs = 2),
                PowerPort(typeWatts = 150000L, label = "150kW", availablePlugs = 1, totalPlugs = 2)
            ),
            totalAvailablePlugs = 2,
            totalPlugs = 4,
            evse = "VinFast"
        )

        fakeTelemetryRepo.tokensResult = Result.failure(IOException("Failed to fetch station tokens: HTTP 404"))
        coordinator.selectStationForDetail(vfStation)
        advanceUntilIdle()

        val vfState = coordinator.stationDetailState.value
        assertEquals("fetchStationTokens was called for VinFast station", 1, fakeTelemetryRepo.fetchTokensCount)
        assertFalse("Loading state must complete", vfState.isLoadingTelemetry)
        assertNull("HTTP 404 must be handled gracefully without setting error state", vfState.error)
        assertEquals("Initial ports must remain accessible", 2, vfState.portStatuses.size)
    }

    // =========================================================================
    // Criterion 3: updateStation preserves portStatuses and syncs drivingMetrics
    // =========================================================================

    @Test
    fun testStationDetailCoordinator_updateStation_preservesPortStatusesAndSyncsDrivingMetrics() = runTest(testDispatcher) {
        val coordinator = StationDetailCoordinator(
            coroutineScope = this,
            telemetryRepository = fakeTelemetryRepo,
            ioDispatcher = testDispatcher,
            mainDispatcher = testDispatcher
        )

        val initialStation = Station(
            id = "sync_test_01",
            name = "VinFast Metropolis",
            address = "29 Lieu Giai, Ba Dinh, Ha Noi",
            latitude = 21.0333,
            longitude = 105.8142,
            summary = "Trống 3/4 cổng",
            connectors = "60kW, 150kW",
            depotStatus = "Normal",
            powers = listOf(
                PowerPort(typeWatts = 60000L, label = "60kW", availablePlugs = 2, totalPlugs = 2),
                PowerPort(typeWatts = 150000L, label = "150kW", availablePlugs = 1, totalPlugs = 2)
            ),
            totalAvailablePlugs = 3,
            totalPlugs = 4,
            drivingMetrics = null
        )

        coordinator.selectStationForDetail(initialStation)
        advanceUntilIdle()

        val stateBefore = coordinator.stationDetailState.value
        assertEquals(2, stateBefore.portStatuses.size)
        assertNull(stateBefore.station?.drivingMetrics)

        // Enrich station with driving metrics (e.g. from routing pipeline)
        val drivingMetrics = DrivingMetrics(
            durationSeconds = 720L,
            distanceMeters = 5400L,
            trafficCondition = TrafficCondition.FREE_FLOW,
            engineUsed = RoutingEngineType.GOOGLE
        )
        val enrichedStation = initialStation.copy(
            drivingMetrics = drivingMetrics
        )

        coordinator.updateStation(enrichedStation)

        val stateAfter = coordinator.stationDetailState.value
        assertNotNull("Driving metrics must be synchronized", stateAfter.station?.drivingMetrics)
        assertEquals(720L, stateAfter.station?.drivingMetrics?.durationSeconds)
        assertEquals(5.4, stateAfter.station?.effectiveDistanceKm ?: 0.0, 0.001)

        // Verify portStatuses list is preserved
        assertEquals("Port statuses count must be preserved", 2, stateAfter.portStatuses.size)
        assertEquals(150, stateAfter.portStatuses[0].kw)
        assertEquals(60, stateAfter.portStatuses[1].kw)
    }

    // =========================================================================
    // Criterion 4: FavoritesViewModel propagates routing & telemetry to coordinator
    // =========================================================================

    @Test
    fun testFavoritesViewModel_propagatesRoutingAndTelemetryToCoordinator() = runTest(testDispatcher) {
        val testStation = Station(
            id = "fav_sync_01",
            name = "VinFast Royal City",
            address = "72A Nguyen Trai, Thanh Xuan, Ha Noi",
            latitude = 20.9998,
            longitude = 105.8188,
            summary = "Trống 2/4 cổng",
            connectors = "60kW",
            depotStatus = "Normal",
            powers = listOf(
                PowerPort(typeWatts = 60000L, label = "60kW", availablePlugs = 2, totalPlugs = 4)
            ),
            totalAvailablePlugs = 2,
            totalPlugs = 4,
            evse = "VinFast"
        )

        val fakeRoutingCoordinator = object : MultiTierRoutingCoordinator() {
            override suspend fun calculateRoutes(
                originLat: Double,
                originLng: Double,
                destinations: List<RoutingDestination>,
                settings: RoutingSettings,
                forceRefresh: Boolean
            ): Map<String, DrivingMetrics> {
                return mapOf(
                    testStation.id to DrivingMetrics(
                        durationSeconds = 900L,
                        distanceMeters = 7500L,
                        trafficCondition = TrafficCondition.FREE_FLOW,
                        engineUsed = RoutingEngineType.OSRM
                    )
                )
            }
        }

        val fakeRepo = object : EvcsRepository(
            apiClient = EvcsApiClient(sessionManager),
            cacheStorage = storage
        ) {
            override suspend fun getFavorites(
                userLat: Double?,
                userLon: Double?,
                autoResolveUnknownCoordinates: Boolean
            ): Result<List<Station>> {
                return Result.success(listOf(testStation))
            }
        }

        val viewModel = FavoritesViewModel(
            repository = fakeRepo,
            authEngine = authEngine,
            dispatcher = testDispatcher,
            routingCoordinator = fakeRoutingCoordinator,
            telemetryRepository = fakeTelemetryRepo,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher
        )

        // 1. Load favorites into UI state
        viewModel.fetchFavorites()
        advanceUntilIdle()

        assertTrue("ViewModel state must be Success", viewModel.uiState.value is FavoritesUiState.Success)

        // 2. Select station for detail sheet
        viewModel.selectStationForDetail(testStation)
        advanceUntilIdle()

        val detailStateInitial = viewModel.stationDetailState.value
        assertEquals("Selected station is open in detail sheet", testStation.id, detailStateInitial.station?.id)
        assertNull("Initial driving metrics are null", detailStateInitial.station?.drivingMetrics)

        // 3. Execute routing pipeline via calculateDrivingRoutes
        viewModel.calculateDrivingRoutes(
            stations = listOf(testStation),
            userLat = 21.0000,
            userLon = 105.8000
        )
        advanceUntilIdle()

        // 4. Verify routing metrics are propagated into active stationDetailCoordinator
        val detailStateAfterRouting = viewModel.stationDetailState.value
        assertNotNull("Driving metrics must be updated in coordinator", detailStateAfterRouting.station?.drivingMetrics)
        assertEquals(900L, detailStateAfterRouting.station?.drivingMetrics?.durationSeconds)
        assertEquals(7.5, detailStateAfterRouting.station?.effectiveDistanceKm ?: 0.0, 0.001)

        // 5. Trigger live telemetry update
        val livePortStatuses = listOf(
            StationPortStatus(kw = 60, availablePorts = 4, totalPorts = 4, busyCount = 0)
        )
        viewModel.updateStationWithTelemetry(testStation.id, livePortStatuses)
        advanceUntilIdle()

        // 6. Verify telemetry changes are reflected in coordinator
        val detailStateAfterTelemetry = viewModel.stationDetailState.value
        assertEquals("Coordinator station totalAvailablePlugs updated", 4, detailStateAfterTelemetry.station?.totalAvailablePlugs)
        assertEquals("Coordinator station powers updated", 4, detailStateAfterTelemetry.station?.powers?.first()?.availablePlugs)
        assertEquals("Driving metrics must still be retained", 900L, detailStateAfterTelemetry.station?.drivingMetrics?.durationSeconds)
    }

    // =========================================================================
    // Fake EvcsTelemetryRepository for isolated fast unit testing
    // =========================================================================

    class FakeEvcsTelemetryRepository(
        sessionManager: SessionManager
    ) : EvcsTelemetryRepository(
        dataSource = EvcsTelemetryDataSource(sessionManager),
        statsCalculator = Station24hStatsCalculator,
        ioDispatcher = Dispatchers.Unconfined
    ) {
        var fetchTokensCount: Int = 0

        var tokensResult: Result<StationAccessTokens> = Result.success(
            StationAccessTokens(
                chargeToken = "charge_tok_test",
                apiToken = "api_tok_test",
                rating = StationRating(avg = 5.0, count = 10, mine = 5)
            )
        )

        override suspend fun fetchStationTokens(station: Station): Result<StationAccessTokens> {
            fetchTokensCount++
            return tokensResult
        }

        override suspend fun fetchLiveCharging(
            stationId: String,
            chargeToken: String,
            isVin: Boolean
        ): Result<StationTelemetry> {
            return Result.success(
                StationTelemetry(
                    busyByKw = emptyMap(),
                    cleanForecast = "Hoạt động bình thường",
                    isLocked = false
                )
            )
        }
    }
}
