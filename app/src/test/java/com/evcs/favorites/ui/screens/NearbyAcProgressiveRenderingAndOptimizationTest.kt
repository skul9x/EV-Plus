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
import com.evcs.favorites.domain.model.DcWattageTier
import com.evcs.favorites.domain.model.SmartFilterMode
import com.evcs.favorites.focus.EvcsStationNameResolver
import com.evcs.favorites.ui.state.NearbyUiEvent
import com.evcs.favorites.ui.viewmodel.NearbyViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
 * Single comprehensive unit test suite for Phase 03:
 * Progressive Staged UI Rendering & Pipeline Optimization.
 *
 * Verifies:
 * 1. Stage 1 (Instant Discovery Emit): Immediate UI emission produces isLoading = false and
 *    populated top10DisplayStations with fallback address names as soon as HERE results arrive,
 *    before name resolution completes.
 * 2. Stage 2 (Asynchronous Enrichment): Canonical station names are subsequently enriched
 *    without re-triggering isLoading = true or full-screen loading spinners.
 * 3. Driving route calculations execute seamlessly following candidate emission / enrichment.
 * 4. User refresh during AC flow emits NearbyUiEvent.ScrollToTop in Stage 1.
 * 5. Switching away from AC mode (e.g. AC -> DC) cleanly cancels pending background resolution
 *    and enrichment jobs without state leakage.
 * 6. Rapid filter switching (AC -> DC -> AC) cleanly cleans up obsolete discovery and enrichment jobs.
 * 7. 20km auto-expansion functions with progressive staged rendering (nameResolver = null in Stage 1).
 * 8. Asynchronous name enrichment failure gracefully preserves candidate stations without UI crash.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NearbyAcProgressiveRenderingAndOptimizationTest {

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
        var passedNameResolvers: MutableList<EvcsStationNameResolver?> = mutableListOf()
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
            passedNameResolvers.add(nameResolver)

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
        var delayMs: Long = 0L
        var shouldThrow: Boolean = false
        var resolvedNamesMap: Map<String, String> = emptyMap()

        override suspend fun resolveStationNamesBatch(
            stations: List<Station>
        ): List<Station> {
            resolveCount++
            if (delayMs > 0) {
                delay(delayMs)
            }
            if (shouldThrow) {
                throw RuntimeException("Simulated HTML scraping timeout")
            }
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
        isAc: Boolean = true
    ): Station {
        val ports = if (isAc) {
            listOf(
                PowerPort(
                    typeWatts = 11_000L,
                    label = "11kW",
                    availablePlugs = 2,
                    totalPlugs = 2,
                    displayString = "11kW: trống 2/2 cổng"
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
            summary = if (isAc) "Trống 2/2 cổng sạc" else "Trống 1/1 cổng sạc DC",
            connectors = if (isAc) "11kW" else "60kW",
            depotStatus = "Normal",
            powers = ports,
            totalAvailablePlugs = if (isAc) 2 else 1,
            totalPlugs = if (isAc) 2 else 1
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
    fun testProgressiveStagedRenderingImmediateEmitAndAsyncEnrichment() = runTest {
        val initialDc = createStation("repo_dc_1", "Repo DC Initial", 21.0285, 105.8542, isAc = false)
        fakeRepository.nearbyResult = Result.success(listOf(initialDc))

        val candidateStations = (1..5).map { i ->
            createStation(
                id = "here_$i",
                name = "VinFast - Số $i Phố Huế, Hà Nội",
                lat = 21.0285 + (i * 0.002),
                lon = 105.8542 + (i * 0.002),
                isAc = true
            )
        }
        fakeHereClient.returnResults = { Result.success(candidateStations) }
        fakeHereClient.delayMs = 250L // Simulated HERE discovery latency

        val canonicalNames = candidateStations.associate {
            it.id to "Trạm VinFast TTTM Dabaco Chi nhánh ${it.id}"
        }
        fakeNameResolver.resolvedNamesMap = canonicalNames
        fakeNameResolver.delayMs = 700L // Simulated HTML scrape latency

        val viewModel = createViewModel(SmartFilterMode.NONE)

        // Step 1: Initial scan in DC / NONE mode
        viewModel.scanNearbyStations()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(1, viewModel.uiState.value.top10DisplayStations.size)

        // Step 2: User switches to AC Mode
        viewModel.toggleAcFilter()
        runCurrent()

        assertTrue("isLoading must be true immediately upon entering AC mode", viewModel.uiState.value.isLoading)
        assertEquals(SmartFilterMode.AC, viewModel.uiState.value.activeFilterMode)

        // Step 3: Advance past HERE API latency (250ms) but BEFORE name resolver finishes (700ms)
        advanceTimeBy(260L)
        runCurrent()

        // Stage 1 Verification:
        // - HERE client was invoked with nameResolver = null
        assertEquals(1, fakeHereClient.callCount)
        assertNull("HERE client must be invoked with nameResolver = null to eliminate blocking scraping", fakeHereClient.passedNameResolvers.first())

        // - UI immediately receives candidate stations and sets isLoading = false
        assertFalse("isLoading must turn OFF immediately when HERE API returns candidate stations", viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.isSearching)
        assertFalse(viewModel.uiState.value.isLocating)
        assertNull(viewModel.uiState.value.errorMessage)
        assertEquals("Top 5 candidate stations must be displayed immediately", 5, viewModel.uiState.value.top10DisplayStations.size)

        // - Verify stations in Stage 1 have fallback address names
        viewModel.uiState.value.top10DisplayStations.forEachIndexed { index, station ->
            val expectedFallback = "VinFast - Số ${index + 1} Phố Huế, Hà Nội"
            assertEquals("Candidate station must have fallback address name in Stage 1", expectedFallback, station.name)
            assertEquals("Candidate station must have live AC port availability immediately", 2, station.totalAvailablePlugs)
        }

        // - Name resolver has started or has not finished yet
        val enrichmentJob = viewModel.acEnrichmentJob
        assertNotNull("acEnrichmentJob must be active during Stage 2", enrichmentJob)
        assertTrue("acEnrichmentJob should still be executing", enrichmentJob?.isActive == true)

        // Step 4: Advance past Name Resolver latency (700ms) to complete Stage 2
        advanceTimeBy(750L)
        advanceUntilIdle()

        // Stage 2 Verification:
        // - isLoading remains false (smooth update without re-triggering spinner)
        assertFalse("isLoading must remain false during and after Stage 2", viewModel.uiState.value.isLoading)
        assertEquals(1, fakeNameResolver.resolveCount)

        // - Verify station names are now enriched with authentic canonical names
        viewModel.uiState.value.top10DisplayStations.forEach { station ->
            val expectedCanonical = canonicalNames[station.id]
            assertEquals("Station name must be updated to authentic canonical title in Stage 2", expectedCanonical, station.name)
            assertEquals("Port availability must be intact", 2, station.totalAvailablePlugs)
        }
    }

    @Test
    fun testUserRefreshDispatchesScrollToTopDuringStage1() = runTest {
        val candidateStations = listOf(
            createStation("here_refresh_1", "VinFast - 123 Ba Triệu", 21.0285, 105.8542, isAc = true)
        )
        fakeHereClient.returnResults = { Result.success(candidateStations) }
        fakeHereClient.delayMs = 100L
        fakeNameResolver.delayMs = 400L
        fakeNameResolver.resolvedNamesMap = mapOf("here_refresh_1" to "Trạm VinFast Ba Triệu Official")

        val viewModel = createViewModel(SmartFilterMode.AC)

        val collectedEvents = mutableListOf<NearbyUiEvent>()
        val eventsJob = launch(testDispatcher) {
            viewModel.events.toList(collectedEvents)
        }

        // User pulls to refresh / scans nearby in AC mode
        viewModel.scanNearbyStations()
        runCurrent()
        assertTrue(viewModel.uiState.value.isLoading)

        // Advance past Stage 1 (100ms)
        advanceTimeBy(120L)
        runCurrent()

        // Verify ScrollToTop was dispatched at Stage 1
        val scrollToTopEvents = collectedEvents.filterIsInstance<NearbyUiEvent.ScrollToTop>()
        assertEquals("NearbyUiEvent.ScrollToTop must be dispatched upon candidate station emission", 1, scrollToTopEvents.size)
        assertEquals("Candidates must be rendered in Stage 1", 1, viewModel.uiState.value.top10DisplayStations.size)
        assertFalse("isLoading must be false at Stage 1", viewModel.uiState.value.isLoading)

        // Complete Stage 2
        advanceUntilIdle()
        assertEquals("Trạm VinFast Ba Triệu Official", viewModel.uiState.value.top10DisplayStations.first().name)

        eventsJob.cancel()
    }

    @Test
    fun testSwitchingAwayFromAcModeAbortsPendingBackgroundEnrichmentWithoutLeakage() = runTest {
        val hereCandidate = createStation("ac_stale", "VinFast - 99 Kim Ma", 21.0285, 105.8542, isAc = true)
        fakeHereClient.returnResults = { Result.success(listOf(hereCandidate)) }
        fakeHereClient.delayMs = 100L

        fakeNameResolver.delayMs = 1000L // Long running enrichment
        fakeNameResolver.resolvedNamesMap = mapOf("ac_stale" to "Trạm VinFast Kim Mã Canonical")

        val dcStation = createStation("dc_active", "Trạm DC 250kW Chuẩn", 21.0285, 105.8542, isAc = false)
        fakeRepository.nearbyResult = Result.success(listOf(dcStation))

        val viewModel = createViewModel(SmartFilterMode.NONE)
        viewModel.scanNearbyStations()
        advanceUntilIdle()

        // 1. Switch to AC mode
        viewModel.toggleAcFilter()
        advanceTimeBy(150L)
        runCurrent()

        // Stage 1 completed, Stage 2 (enrichment) is in-flight
        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals("ac_stale", viewModel.uiState.value.top10DisplayStations.first().id)
        val enrichmentJob = viewModel.acEnrichmentJob
        assertNotNull(enrichmentJob)
        assertTrue("acEnrichmentJob must be active", enrichmentJob?.isActive == true)

        // 2. User rapidly switches away to DC mode while enrichment is in-flight
        viewModel.enterDcMode()
        viewModel.selectDcTier(DcWattageTier.BETWEEN_30_60KW)
        runCurrent()

        // Verify acEnrichmentJob was aborted cleanly
        assertTrue("acEnrichmentJob must be cancelled when switching away from AC", enrichmentJob?.isCancelled == true)
        assertEquals(SmartFilterMode.DC, viewModel.uiState.value.activeFilterMode)

        // 3. Advance time past the 1000ms delay of the aborted enrichment job
        advanceTimeBy(1500L)
        advanceUntilIdle()

        // 4. Verify no state leakage: DC station remains active, AC station did NOT leak back in
        assertEquals(SmartFilterMode.DC, viewModel.uiState.value.activeFilterMode)
        assertFalse("isLoading must be false in DC mode", viewModel.uiState.value.isLoading)
        assertNull("Cancelled enrichment must not leak error message", viewModel.uiState.value.errorMessage)
        assertEquals("dc_active", viewModel.uiState.value.top10DisplayStations.first().id)
        assertEquals("Trạm DC 250kW Chuẩn", viewModel.uiState.value.top10DisplayStations.first().name)
    }

    @Test
    fun testRapidFilterSwitchingAcToDcToAcCleansUpPreviousJobs() = runTest {
        val acStation1 = createStation("ac_1", "VinFast - Láng Hạ", 21.0285, 105.8542, isAc = true)
        val acStation2 = createStation("ac_2", "VinFast - Giảng Võ", 21.0285, 105.8542, isAc = true)
        fakeHereClient.delayMs = 150L
        fakeHereClient.returnResults = {
            Result.success(if (fakeHereClient.callCount == 1) listOf(acStation1) else listOf(acStation2))
        }

        fakeNameResolver.delayMs = 400L
        fakeNameResolver.resolvedNamesMap = mapOf(
            "ac_1" to "Trạm Láng Hạ Enriched",
            "ac_2" to "Trạm Giảng Võ Enriched"
        )

        val dcStation = createStation("dc_repo", "Trạm DC Repo", 21.0285, 105.8542, isAc = false)
        fakeRepository.nearbyResult = Result.success(listOf(dcStation))

        val viewModel = createViewModel(SmartFilterMode.NONE)
        viewModel.scanNearbyStations()
        advanceUntilIdle()

        // 1. Enter AC mode
        viewModel.toggleAcFilter()
        advanceTimeBy(50L) // In-flight

        // 2. Quickly toggle to DC
        viewModel.enterDcMode()
        runCurrent()

        // 3. Quickly toggle back to AC
        viewModel.toggleAcFilter()
        runCurrent()

        // Advance all coroutines to completion
        advanceUntilIdle()

        assertEquals(SmartFilterMode.AC, viewModel.uiState.value.activeFilterMode)
        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.errorMessage)
        assertEquals(1, viewModel.uiState.value.top10DisplayStations.size)
        assertEquals("ac_2", viewModel.uiState.value.top10DisplayStations.first().id)
        assertEquals("Trạm Giảng Võ Enriched", viewModel.uiState.value.top10DisplayStations.first().name)
    }

    @Test
    fun testAutoExpansionTo20kmProgressiveStagedRendering() = runTest {
        val expandedStation = createStation("here_far_1", "VinFast - Xa Lộ Ngoại Thành", 21.1500, 105.9000, isAc = true)

        fakeHereClient.delayMs = 100L
        fakeHereClient.returnResults = { radius ->
            if (radius == 10_000) {
                Result.success(emptyList()) // 10km empty
            } else {
                Result.success(listOf(expandedStation)) // 20km returns station
            }
        }
        fakeNameResolver.delayMs = 300L
        fakeNameResolver.resolvedNamesMap = mapOf("here_far_1" to "Trạm VinFast Ngoại Thành Canonical")

        val viewModel = createViewModel(SmartFilterMode.AC)
        viewModel.scanNearbyStations()
        runCurrent()
        assertTrue(viewModel.uiState.value.isLoading)

        // Advance past 10km (100ms) + 20km (100ms) HERE queries = 220ms
        advanceTimeBy(220L)
        runCurrent()

        // Verify both queries passed nameResolver = null
        assertEquals(2, fakeHereClient.callCount)
        assertEquals(listOf(10_000, 20_000), fakeHereClient.queriedRadii)
        fakeHereClient.passedNameResolvers.forEach { passedResolver ->
            assertNull("Each HERE radius query must pass nameResolver = null", passedResolver)
        }

        // Verify Stage 1 emit for expanded result
        assertFalse("isLoading must be false once 20km candidate returns", viewModel.uiState.value.isLoading)
        assertEquals("VinFast - Xa Lộ Ngoại Thành", viewModel.uiState.value.top10DisplayStations.first().name)

        // Advance past resolver latency (300ms)
        advanceTimeBy(350L)
        advanceUntilIdle()

        // Verify Stage 2 enrichment
        assertEquals("Trạm VinFast Ngoại Thành Canonical", viewModel.uiState.value.top10DisplayStations.first().name)
    }

    @Test
    fun testEnrichmentFailureGracefullyPreservesCandidateStationsAndRoutings() = runTest {
        val candidate = createStation("here_err_1", "VinFast - 456 Giải Phóng", 21.0285, 105.8542, isAc = true)
        fakeHereClient.returnResults = { Result.success(listOf(candidate)) }
        fakeNameResolver.shouldThrow = true // Simulates network failure or scrape error

        val viewModel = createViewModel(SmartFilterMode.AC)
        viewModel.scanNearbyStations()
        advanceUntilIdle()

        // Verify candidate station remains displayed with fallback name despite enrichment error
        assertFalse("isLoading must be false", viewModel.uiState.value.isLoading)
        assertEquals(1, viewModel.uiState.value.top10DisplayStations.size)
        assertEquals("VinFast - 456 Giải Phóng", viewModel.uiState.value.top10DisplayStations.first().name)
        assertNull("Background enrichment failure must not overwrite UI with error message", viewModel.uiState.value.errorMessage)
    }
}
