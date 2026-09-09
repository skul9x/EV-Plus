package com.evcs.favorites.ui

import androidx.compose.ui.graphics.Color
import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.AuthEngine
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.data.repository.EvcsTelemetryRepository
import com.evcs.favorites.data.repository.StationDetailTelemetrySnapshot
import com.evcs.favorites.data.telemetry.EvcsTelemetryDataSource
import com.evcs.favorites.domain.Station24hStatsCalculator
import com.evcs.favorites.domain.StationAccessTokens
import com.evcs.favorites.domain.StationPortStatus
import com.evcs.favorites.domain.StationRating
import com.evcs.favorites.domain.StationTelemetry
import com.evcs.favorites.domain.location.LocationService
import com.evcs.favorites.ui.components.StationCardHelper
import com.evcs.favorites.ui.state.FavoritesUiState
import com.evcs.favorites.ui.viewmodel.FavoritesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Single Comprehensive Verification Test for Phase 03:
 * Station Card Charger Distribution Line & Favorites Realtime Enrichment.
 *
 * Requirements verified:
 * 1. Aggregation of power ports yields sorted distribution pairs without duplicates.
 * 2. Formatted power distribution summary produces expected structure with graceful fallback to connectors.
 * 3. Annotated string builder assigns contrasting styles to power vs count and separators.
 * 4. Favorites on-demand enrichment updates Station instances with non-zero live telemetry plugs.
 * 5. 2-way sync-back from StationDetailCoordinator updates target station in FavoritesUiState.Success.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StationCardPowerDistributionAndFavoritesRealtimeTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var storage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var authEngine: AuthEngine
    private lateinit var fakeEvcsRepo: FakeEvcsRepo
    private lateinit var fakeLocationService: FakeLocationService
    private lateinit var fakeTelemetryRepo: FakeEvcsTelemetryRepository

    class FakeEvcsRepo(
        storage: InMemorySessionStorage
    ) : EvcsRepository(
        apiClient = EvcsApiClient(SessionManager(storage)),
        cacheStorage = storage
    ) {
        var stationsToReturn: List<Station> = emptyList()
            set(value) {
                field = value
                val favField = EvcsRepository::class.java.getDeclaredField("_favoritesState")
                favField.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                (favField.get(this) as MutableStateFlow<List<Station>>).value = value
            }

        override suspend fun getFavorites(
            userLat: Double?,
            userLon: Double?,
            autoResolveUnknownCoordinates: Boolean
        ): Result<List<Station>> {
            return Result.success(stationsToReturn)
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
        var customSnapshot: StationDetailTelemetrySnapshot? = null

        var tokensResult: Result<StationAccessTokens> = Result.success(
            StationAccessTokens(
                chargeToken = "test_charge_token",
                apiToken = "test_api_token",
                rating = StationRating(avg = 4.9, count = 30, mine = 5)
            )
        )

        var liveChargingResult: Result<StationTelemetry> = Result.success(
            StationTelemetry(
                busyByKw = mapOf(120 to 2, 60 to 1),
                rawTicker = "Có 2 cổng 120kW",
                cleanForecast = "Có 2 cổng 120kW",
                isLocked = false
            )
        )

        override suspend fun fetchStationTokens(station: Station): Result<StationAccessTokens> {
            return tokensResult
        }

        override suspend fun fetchLiveCharging(
            stationId: String,
            chargeToken: String,
            isVin: Boolean
        ): Result<StationTelemetry> {
            return liveChargingResult
        }

        override suspend fun fetchStationTelemetrySnapshot(
            station: Station,
            totalPorts: Int
        ): Result<StationDetailTelemetrySnapshot> {
            customSnapshot?.let { return Result.success(it) }
            return super.fetchStationTelemetrySnapshot(station, totalPorts)
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        storage = InMemorySessionStorage()
        sessionManager = SessionManager(storage).apply {
            authCookie = "valid_test_token"
            userEmail = "test@evcs.vn"
        }
        authEngine = AuthEngine(sessionManager)
        fakeEvcsRepo = FakeEvcsRepo(storage)
        fakeLocationService = FakeLocationService()
        fakeTelemetryRepo = FakeEvcsTelemetryRepository(sessionManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): FavoritesViewModel {
        return FavoritesViewModel(
            repository = fakeEvcsRepo,
            authEngine = authEngine,
            locationService = fakeLocationService,
            dispatcher = testDispatcher,
            telemetryRepository = fakeTelemetryRepo,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher
        )
    }

    @Test
    fun aggregation_of_power_ports_yields_sorted_distribution_pairs_without_duplicates() {
        // Multi-gun configuration with duplicate power tiers
        val powers = listOf(
            PowerPort(typeWatts = 60_000L, label = "60kW", totalPlugs = 2, availablePlugs = 1),
            PowerPort(typeWatts = 60_000L, label = "60kW", totalPlugs = 2, availablePlugs = 1),
            PowerPort(typeWatts = 120_000L, label = "120kW", totalPlugs = 6, availablePlugs = 4),
            PowerPort(typeWatts = 30_000L, label = "30kW", totalPlugs = 20, availablePlugs = 15)
        )

        val result = StationCardHelper.formatPowerDistributionSummary(powers, "")

        // Must aggregate duplicate 60kW ports (2 + 2 = 4) and sort descending by wattage: 120kW > 60kW > 30kW
        assertEquals(3, result.size)
        assertEquals(Pair("120kW", 6), result[0])
        assertEquals(Pair("60kW", 4), result[1])
        assertEquals(Pair("30kW", 20), result[2])
    }

    @Test
    fun formatted_power_distribution_summary_falls_back_to_connectors_with_expected_structure() {
        // Empty powers list -> parse connectors fallback
        val connectors = "120kW, 60kW, 30kW"
        val result = StationCardHelper.formatPowerDistributionSummary(emptyList(), connectors)

        assertEquals(3, result.size)
        assertEquals(Pair("120kW", 1), result[0])
        assertEquals(Pair("60kW", 1), result[1])
        assertEquals(Pair("30kW", 1), result[2])

        // Connectors with duplicates: "60kW, 60kW" -> Pair("60kW", 2)
        val duplicateConnectors = "60kW, 60kW"
        val dupResult = StationCardHelper.formatPowerDistributionSummary(emptyList(), duplicateConnectors)
        assertEquals(1, dupResult.size)
        assertEquals(Pair("60kW", 2), dupResult[0])

        // Empty connectors and empty powers -> empty list
        val emptyResult = StationCardHelper.formatPowerDistributionSummary(emptyList(), "")
        assertTrue(emptyResult.isEmpty())
    }

    @Test
    fun annotated_string_builder_assigns_contrasting_styles_to_power_vs_count() {
        val distribution = listOf(
            Pair("120kW", 6),
            Pair("60kW", 10),
            Pair("30kW", 20)
        )
        val powerColor = Color(0xFFE0E0E0)
        val countColor = Color(0xFF00E676)
        val separatorColor = Color(0xFF757575)

        val annotated = StationCardHelper.buildPowerDistributionAnnotatedString(
            distribution = distribution,
            powerColor = powerColor,
            countColor = countColor,
            separatorColor = separatorColor
        )

        // Expected formatted text: "120kW x 6 | 60kW x 10 | 30kW x 20"
        assertEquals("120kW x 6 | 60kW x 10 | 30kW x 20", annotated.text)

        // Verify span styles contain distinct power and count colors
        val powerStyles = annotated.spanStyles.filter { it.item.color == powerColor }
        val countStyles = annotated.spanStyles.filter { it.item.color == countColor }
        val separatorStyles = annotated.spanStyles.filter { it.item.color == separatorColor }

        assertEquals(3, powerStyles.size)
        assertEquals(3, countStyles.size)
        assertEquals(2, separatorStyles.size)
    }

    @Test
    fun favorites_enrichment_updates_station_instances_with_live_telemetry_plugs() = runTest(testDispatcher) {
        val initialStation = Station(
            id = "station_001",
            name = "Trạm Sạc Vincom Landmark 81",
            address = "720A Điện Biên Phủ, P. 22, Bình Thạnh, TP.HCM",
            latitude = 10.795,
            longitude = 106.721,
            summary = "Trạm sạc Vincom Landmark 81",
            connectors = "120kW, 60kW",
            depotStatus = "Normal",
            powers = listOf(
                PowerPort(typeWatts = 120_000L, label = "120kW", totalPlugs = 0, availablePlugs = 0),
                PowerPort(typeWatts = 60_000L, label = "60kW", totalPlugs = 0, availablePlugs = 0)
            ),
            totalAvailablePlugs = 0,
            totalPlugs = 0
        )
        fakeEvcsRepo.stationsToReturn = listOf(initialStation)

        fakeTelemetryRepo.customSnapshot = StationDetailTelemetrySnapshot(
            tokens = StationAccessTokens("tok_charge", "tok_api", StationRating(5.0, 10, 5)),
            telemetry = StationTelemetry(mapOf(120 to 2, 60 to 1), "Live", "Live", false),
            portStatuses = listOf(
                StationPortStatus(kw = 120, availablePorts = 4, totalPorts = 6, busyCount = 2),
                StationPortStatus(kw = 60, availablePorts = 2, totalPorts = 4, busyCount = 2)
            ),
            stats24h = null
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Initial load should trigger doFetchFavorites and enrichFavoritesWithTelemetry
        val state = viewModel.uiState.value
        assertTrue("Expected FavoritesUiState.Success but got $state", state is FavoritesUiState.Success)
        val success = state as FavoritesUiState.Success

        val enrichedStation = success.stations.first()
        assertEquals(6, enrichedStation.totalAvailablePlugs) // 4 + 2
        assertEquals(10, enrichedStation.totalPlugs) // 6 + 4
        assertTrue(enrichedStation.hasLiveTelemetry)

        val port120 = enrichedStation.powers.find { it.typeWatts == 120_000L }
        assertNotNull(port120)
        assertEquals(4, port120!!.availablePlugs)
        assertEquals(6, port120.totalPlugs)

        val port60 = enrichedStation.powers.find { it.typeWatts == 60_000L }
        assertNotNull(port60)
        assertEquals(2, port60!!.availablePlugs)
        assertEquals(4, port60.totalPlugs)
    }

    @Test
    fun two_way_sync_back_updates_target_station_in_favorites_ui_state() = runTest(testDispatcher) {
        val testStation = Station(
            id = "station_002",
            name = "Trạm Sạc Vincom Đồng Khởi",
            address = "72 Lê Thánh Tôn, Bến Nghé, Quận 1, TP.HCM",
            latitude = 10.777,
            longitude = 106.702,
            summary = "Trạm sạc Đồng Khởi",
            connectors = "120kW",
            depotStatus = "Normal",
            powers = listOf(
                PowerPort(typeWatts = 120_000L, label = "120kW", totalPlugs = 4, availablePlugs = 0)
            ),
            totalAvailablePlugs = 0,
            totalPlugs = 4
        )
        fakeEvcsRepo.stationsToReturn = listOf(testStation)

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Configure telemetry for station detail coordinator
        fakeTelemetryRepo.liveChargingResult = Result.success(
            StationTelemetry(
                busyByKw = mapOf(120 to 1),
                rawTicker = "Live 2",
                cleanForecast = "Live 2",
                isLocked = false
            )
        )

        // Select station for detail inspection -> triggers coordinator load
        viewModel.selectStationForDetail(testStation)
        advanceUntilIdle()

        // 2-way sync-back must update station_002 in FavoritesUiState.Success
        val successState = viewModel.uiState.value as FavoritesUiState.Success
        val targetStation = successState.stations.first { it.id == "station_002" }

        assertEquals(3, targetStation.totalAvailablePlugs)
        assertEquals(4, targetStation.totalPlugs)
        assertTrue(targetStation.hasLiveTelemetry)

        val targetPort = targetStation.powers.first()
        assertEquals(3, targetPort.availablePlugs)
        assertEquals(4, targetPort.totalPlugs)
    }
}
