package com.evcs.favorites.ui.screens

import android.location.Location
import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.network.here.HereEvApiClient
import com.evcs.favorites.data.preferences.SmartFilterPreferences
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.domain.location.LocationService
import com.evcs.favorites.domain.model.CustomFilterConfig
import com.evcs.favorites.domain.model.CustomFilterMode
import com.evcs.favorites.domain.model.DcWattageTier
import com.evcs.favorites.domain.model.QuickChipOption
import com.evcs.favorites.domain.model.SmartFilterMode
import com.evcs.favorites.focus.EvcsStationNameResolver
import com.evcs.favorites.ui.viewmodel.NearbyViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Single comprehensive test suite verifying Phase 02:
 * Nearby Hybrid AC Flow Integration & Loading UX.
 *
 * Verifies:
 * 1. Switching to SmartFilterMode.AC triggers loading state isLoading = true.
 * 2. Completion of hybrid pipeline emits Top 10 stations with authentic names and sets isLoading = false.
 * 3. 10km empty-state auto-expansion to 20km before concluding empty results.
 * 4. Switching to SmartFilterMode.DC or NONE reverts to repository flow without invoking HERE client.
 * 5. Rapid filter switching cleanly cancels obsolete AC network queries without race conditions or error leakage.
 * 6. QuickChipOption.AC under CustomFilterMode.QUICK_CHIP also routes through hybrid AC pipeline.
 * 7. Correct data contract preservation: HERE GPS coordinates for navigation and canonical station.id for EVCS web detail.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NearbyHybridAcFlowAndLoadingUiStateTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var storage: InMemorySessionStorage
    private lateinit var smartFilterPreferences: SmartFilterPreferences
    private lateinit var sessionManager: SessionManager
    private lateinit var fakeRepository: FakeEvcsRepository
    private lateinit var fakeLocationService: FakeLocationService
    private lateinit var fakeHereClient: FakeHereEvApiClient
    private lateinit var fakeNameResolver: FakeEvcsStationNameResolver

    class FakeLocationService : LocationService() {
        var permissionGranted: Boolean = true
        var locationToReturn: Location? = null

        override fun hasLocationPermission(): Boolean = permissionGranted
        override suspend fun getFreshLocation(): Location? = locationToReturn
    }

    class FakeEvcsRepository(
        sessionStorage: InMemorySessionStorage
    ) : EvcsRepository(
        apiClient = EvcsApiClient(SessionManager(sessionStorage)),
        cacheStorage = sessionStorage
    ) {
        var repoCallCount: Int = 0
        var nearbyResult: Result<List<Station>> = Result.success(emptyList())

        override suspend fun searchNearbyVinFast(lat: Double, lon: Double): Result<List<Station>> {
            repoCallCount++
            return nearbyResult
        }
    }

    class FakeHereEvApiClient : HereEvApiClient() {
        var callCount: Int = 0
        var queriedRadii: MutableList<Int> = mutableListOf()
        var delayMs: Long = 0L
        var returnResults: (Int) -> Result<List<Station>> = { Result.success(emptyList()) }

        override suspend fun fetchNearbyAvailableAcStations(
            latitude: Double,
            longitude: Double,
            radiusMeters: Int,
            maxLimit: Int,
            nameResolver: EvcsStationNameResolver?
        ): Result<List<Station>> {
            callCount++
            queriedRadii.add(radiusMeters)
            if (delayMs > 0) {
                delay(delayMs)
            }
            val baseResult = returnResults(radiusMeters)
            if (baseResult.isFailure) return baseResult

            val stations = baseResult.getOrThrow()
            val resolvedStations = if (nameResolver != null && stations.isNotEmpty()) {
                nameResolver.resolveStationNamesBatch(stations)
            } else {
                stations
            }
            return Result.success(resolvedStations)
        }
    }

    class FakeEvcsStationNameResolver : EvcsStationNameResolver() {
        var resolveCount: Int = 0
        var resolvedNamesMap: Map<String, String> = emptyMap()

        override suspend fun resolveStationNamesBatch(
            stations: List<Station>
        ): List<Station> {
            resolveCount++
            return stations.map { st ->
                val resolved = resolvedNamesMap[st.id]
                if (resolved != null) st.copy(name = resolved) else st
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
        isAc: Boolean = false
    ): Station {
        val ports = if (isAc) {
            listOf(
                PowerPort(
                    typeWatts = 11_000L,
                    label = "11kW",
                    availablePlugs = 1,
                    totalPlugs = 1,
                    displayString = "11kW: trống 1/1 cổng"
                )
            )
        } else {
            listOf(
                PowerPort(
                    typeWatts = 60_000L,
                    label = "60kW",
                    availablePlugs = 1,
                    totalPlugs = 1,
                    displayString = "60kW: trống 1/1 cổng"
                )
            )
        }
        return Station(
            id = id,
            name = name,
            address = "Địa chỉ $name",
            latitude = lat,
            longitude = lon,
            summary = "Trụ sạc $name",
            connectors = if (isAc) "11kW" else "60kW",
            depotStatus = "Normal",
            powers = ports,
            totalAvailablePlugs = 1,
            totalPlugs = 1
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        storage = InMemorySessionStorage()
        smartFilterPreferences = SmartFilterPreferences(storage)
        sessionManager = SessionManager(storage)
        fakeRepository = FakeEvcsRepository(storage)
        fakeLocationService = FakeLocationService().apply {
            locationToReturn = createMockLocation(21.0285, 105.8542)
        }
        fakeHereClient = FakeHereEvApiClient()
        fakeNameResolver = FakeEvcsStationNameResolver()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        initialSmartMode: SmartFilterMode = SmartFilterMode.NONE
    ): NearbyViewModel {
        smartFilterPreferences.saveActiveFilterMode(initialSmartMode)
        return NearbyViewModel(
            repository = fakeRepository,
            sessionManager = sessionManager,
            locationService = fakeLocationService,
            smartFilterPreferences = smartFilterPreferences,
            hereEvApiClient = fakeHereClient,
            stationNameResolver = fakeNameResolver,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher,
            routingDebounceMs = 0L
        )
    }

    @Test
    fun testSwitchingToAcFilterEmitsLoadingAndResolvesAuthenticNames() = runTest {
        val repoStation = createStation("repo_1", "Repo DC Station", 21.0285, 105.8542, isAc = false)
        fakeRepository.nearbyResult = Result.success(listOf(repoStation))

        val hereRawStations = (1..10).map { i ->
            createStation(
                id = "here_$i",
                name = "HERE Raw Address $i", // Raw HERE fallback name before HTML resolver
                lat = 21.0285 + (i * 0.001),
                lon = 105.8542 + (i * 0.001),
                isAc = true
            )
        }
        fakeHereClient.returnResults = { Result.success(hereRawStations) }
        fakeHereClient.delayMs = 200L // Simulate async network call

        val resolvedNames = hereRawStations.associate { it.id to "Trạm EVCS VinFast Authentic ${it.id}" }
        fakeNameResolver.resolvedNamesMap = resolvedNames

        val viewModel = createViewModel(SmartFilterMode.NONE)

        // 1. Initial Scan in NONE mode
        viewModel.scanNearbyStations()
        advanceUntilIdle()

        assertEquals(1, fakeRepository.repoCallCount)
        assertEquals(0, fakeHereClient.callCount)
        assertFalse("isLoading must be false after initial repo scan", viewModel.uiState.value.isLoading)
        assertEquals(1, viewModel.uiState.value.top10DisplayStations.size)
        assertEquals("repo_1", viewModel.uiState.value.top10DisplayStations.first().id)

        // 2. Switch to AC mode
        viewModel.toggleAcFilter()

        // Verify isLoading = true immediately upon switching
        assertTrue("isLoading must be true immediately upon toggling AC filter", viewModel.uiState.value.isLoading)
        assertEquals(SmartFilterMode.AC, viewModel.uiState.value.activeFilterMode)

        // Advance past simulated HERE & HTML resolver network latency
        advanceUntilIdle()

        // 3. Verify completion of hybrid pipeline
        assertFalse("isLoading must flip to false after resolution completes", viewModel.uiState.value.isLoading)
        assertEquals(1, fakeHereClient.callCount)
        assertEquals(1, fakeNameResolver.resolveCount)

        val finalStations = viewModel.uiState.value.stations
        assertEquals("Top 10 AC stations must be published to uiState.stations", 10, finalStations.size)
        assertEquals(10, viewModel.uiState.value.top10DisplayStations.size)

        // Verify authentic EVCS names were resolved
        finalStations.forEach { station ->
            assertTrue(
                "Station name '${station.name}' must be genuine EVCS resolved name",
                station.name.startsWith("Trạm EVCS VinFast Authentic")
            )
        }

        // Verify HERE coordinates preserved for GPS navigation
        assertEquals(21.0295, finalStations[0].latitude, 0.0001)
        assertEquals(105.8552, finalStations[0].longitude, 0.0001)
        assertEquals("here_1", finalStations[0].id)
    }

    @Test
    fun testEmpty10kmAutoExpandsTo20kmSearchRadius() = runTest {
        val expandedStation = createStation("here_20km_1", "Trạm AC 15km", 21.1500, 105.9000, isAc = true)

        fakeHereClient.returnResults = { radius ->
            if (radius == 10_000) {
                Result.success(emptyList()) // 10km returns empty
            } else {
                Result.success(listOf(expandedStation)) // 20km returns station
            }
        }
        fakeNameResolver.resolvedNamesMap = mapOf("here_20km_1" to "Trạm VinFast Ngoại Thành AC")

        val viewModel = createViewModel(SmartFilterMode.AC)
        viewModel.scanNearbyStations()
        advanceUntilIdle()

        assertEquals("Should query 10km then 20km", 2, fakeHereClient.callCount)
        assertEquals(listOf(10_000, 20_000), fakeHereClient.queriedRadii)
        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(1, viewModel.uiState.value.stations.size)
        assertEquals("Trạm VinFast Ngoại Thành AC", viewModel.uiState.value.stations.first().name)
    }

    @Test
    fun testSwitchingToNonAcModesBypassesHereClientAndRestoresRepoStations() = runTest {
        val repoStationDc = createStation("repo_dc", "Repo DC 60kW", 21.0285, 105.8542, isAc = false)
        fakeRepository.nearbyResult = Result.success(listOf(repoStationDc))

        val hereStationAc = createStation("here_ac", "HERE AC", 21.0285, 105.8542, isAc = true)
        fakeHereClient.returnResults = { Result.success(listOf(hereStationAc)) }

        val viewModel = createViewModel(SmartFilterMode.NONE)
        viewModel.scanNearbyStations()
        advanceUntilIdle()

        assertEquals(1, fakeRepository.repoCallCount)
        assertEquals(0, fakeHereClient.callCount)

        // Toggle to AC
        viewModel.toggleAcFilter()
        advanceUntilIdle()
        assertEquals(1, fakeHereClient.callCount)
        assertEquals("here_ac", viewModel.uiState.value.stations.first().id)

        // Switch to DC mode
        val hereCallsBefore = fakeHereClient.callCount
        viewModel.enterDcMode()
        viewModel.selectDcTier(DcWattageTier.BETWEEN_30_60KW)
        advanceUntilIdle()

        // Verify HERE client was NOT called for DC mode
        assertEquals("HERE client must not be called when entering DC mode", hereCallsBefore, fakeHereClient.callCount)
        assertFalse("isLoading must be false in DC mode", viewModel.uiState.value.isLoading)
        assertEquals(SmartFilterMode.DC, viewModel.uiState.value.activeFilterMode)
        assertEquals(DcWattageTier.BETWEEN_30_60KW, viewModel.uiState.value.selectedDcTier)

        // Toggle back to NONE
        viewModel.clearSmartFilter()
        advanceUntilIdle()
        assertEquals("HERE client must not be called when clearing filter", hereCallsBefore, fakeHereClient.callCount)
        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(SmartFilterMode.NONE, viewModel.uiState.value.activeFilterMode)
    }

    @Test
    fun testRapidFilterSwitchingCancelsObsoleteAcJobCleanly() = runTest {
        fakeHereClient.delayMs = 1000L // Long running AC query
        fakeHereClient.returnResults = {
            Result.success(listOf(createStation("slow_ac", "Slow AC", 21.0285, 105.8542, isAc = true)))
        }

        val repoStation = createStation("fast_repo", "Fast Repo DC", 21.0285, 105.8542, isAc = false)
        fakeRepository.nearbyResult = Result.success(listOf(repoStation))

        val viewModel = createViewModel(SmartFilterMode.NONE)
        viewModel.scanNearbyStations()
        advanceUntilIdle()

        // 1. Trigger AC filter (starts 1000ms job)
        viewModel.toggleAcFilter()
        advanceTimeBy(100L) // In-flight
        assertTrue("isLoading should be true while AC is running", viewModel.uiState.value.isLoading)

        // 2. Rapidly switch to DC before AC finishes
        viewModel.enterDcMode()
        viewModel.selectDcTier(DcWattageTier.BETWEEN_30_60KW)

        // Advance time past the 1000ms threshold of the cancelled AC job
        advanceTimeBy(1500L)
        advanceUntilIdle()

        // 3. Verify state integrity: DC mode active, no error message leaked, isLoading false
        assertEquals(SmartFilterMode.DC, viewModel.uiState.value.activeFilterMode)
        assertFalse("isLoading must be false after switching to DC", viewModel.uiState.value.isLoading)
        assertNull("Cancelled AC query must not leak error message", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun testCustomQuickChipAcTriggersHybridPipeline() = runTest {
        val hereStation = createStation("custom_ac", "Custom AC Station", 21.0285, 105.8542, isAc = true)
        fakeHereClient.returnResults = { Result.success(listOf(hereStation)) }
        fakeNameResolver.resolvedNamesMap = mapOf("custom_ac" to "Trạm Sạc VinFast Custom AC")

        val viewModel = createViewModel(SmartFilterMode.NONE)
        viewModel.scanNearbyStations()
        advanceUntilIdle()

        val acConfig = CustomFilterConfig(
            mode = CustomFilterMode.QUICK_CHIP,
            quickChip = QuickChipOption.AC
        )

        // Apply custom filter with QuickChipOption.AC
        viewModel.saveAndApplyCustomFilter(acConfig)

        // Verify isAcModeActive recognizes Custom QuickChip AC
        assertTrue("isAcModeActive must return true for Custom QuickChip AC", viewModel.isAcModeActive())

        advanceUntilIdle()

        assertEquals(1, fakeHereClient.callCount)
        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(1, viewModel.uiState.value.stations.size)
        assertEquals("Trạm Sạc VinFast Custom AC", viewModel.uiState.value.stations.first().name)
        assertEquals("Tìm thấy 1 trạm có cổng AC khả dụng", viewModel.uiState.value.filterSummaryPillText)
    }
}
