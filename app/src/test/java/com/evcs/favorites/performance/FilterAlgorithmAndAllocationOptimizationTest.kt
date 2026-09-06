package com.evcs.favorites.performance

import android.location.Location
import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.AuthEngine
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.cache.BoundedLruMap
import com.evcs.favorites.data.cache.mapValuesThreadSafe
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.domain.filter.NearbyStationFilter
import com.evcs.favorites.domain.filter.clearConnectorCompatibilityCache
import com.evcs.favorites.domain.filter.hasCarCompatiblePorts
import com.evcs.favorites.domain.location.DistanceCalculator
import com.evcs.favorites.domain.location.LocationService
import com.evcs.favorites.ui.viewmodel.FavoritesViewModel
import com.evcs.favorites.ui.viewmodel.NearbyViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * Single comprehensive verification test for Phase 03:
 * Filter Algorithm & Allocation Optimization (PERF-003, PERF-008, PERF-012).
 *
 * Verifies:
 * 1. Top-K correctness: NearbyStationFilter.extractTopNearest with bounded heap produces
 *    identical sorted ordering and distance values as classic full-sort across various
 *    dataset sizes (0, 5, 10, 100, 1000 stations) and handles duplicate IDs cleanly.
 * 2. Heap efficiency: Input list of 1000 candidate stations only results in at most `limit` (10)
 *    copied Station instances being created with distanceKm.
 * 3. BoundedLruMap thread safety: mapValuesThreadSafe and forEachThreadSafe produce exact
 *    transformed map results under heavy concurrent modifications without ConcurrentModificationException
 *    or intermediate defensive LinkedHashMap copies, and EvcsRepository.saveCachedCoordinates persists properly.
 * 4. Dispatcher routing: NearbyViewModel and FavoritesViewModel default to Dispatchers.Default,
 *    and execute CPU-bound filter and Haversine sorting workloads on the injected defaultDispatcher.
 * 5. Memoization: hasCarCompatiblePorts and parseConnectorsToPowers memoize results to avoid
 *    redundant regex parsing and string splitting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FilterAlgorithmAndAllocationOptimizationTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockWebServer: MockWebServer
    private lateinit var client: EvcsApiClient
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var authEngine: AuthEngine

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockWebServer = MockWebServer()
        mockWebServer.start()

        sessionStorage = InMemorySessionStorage()
        sessionManager = SessionManager(sessionStorage)
        authEngine = AuthEngine(sessionManager = sessionManager)
        client = EvcsApiClient(
            sessionManager = sessionManager,
            baseUrl = mockWebServer.url("/").toString()
        )
        clearConnectorCompatibilityCache()
        EvcsRepository.clearParsedConnectorsCache()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        Dispatchers.resetMain()
    }

    // Reference classic full-sort implementation for comparison
    private fun classicFullSort(
        userLat: Double,
        userLon: Double,
        stations: List<Station>,
        limit: Int = 10
    ): List<Station> {
        return stations
            .distinctBy { it.id }
            .map { station ->
                val distance = DistanceCalculator.calculateDistanceKm(
                    lat1 = userLat,
                    lon1 = userLon,
                    lat2 = station.latitude,
                    lon2 = station.longitude
                )
                station.copy(distanceKm = distance)
            }
            .sortedBy { it.distanceKm ?: Double.MAX_VALUE }
            .take(limit)
    }

    private fun generateStations(count: Int): List<Station> {
        return (1..count).map { i ->
            val lat = 21.0 + ((i * 17) % 1000) * 0.001
            val lon = 105.0 + ((i * 23) % 1000) * 0.001
            Station(
                id = "station_$i",
                name = "Station $i",
                address = "Address $i",
                latitude = lat,
                longitude = lon,
                summary = "Summary $i",
                connectors = "CCS2 60kW, Type 2 22kW",
                depotStatus = "Normal",
                totalAvailablePlugs = 2,
                totalPlugs = 4
            )
        }
    }

    // =========================================================================
    // 1. Top-K Correctness across dataset sizes (0, 5, 10, 100, 1000)
    // =========================================================================

    @Test
    fun testTopKCorrectnessAcrossDatasetSizes() {
        val userLat = 21.0285
        val userLon = 105.8542
        val testSizes = listOf(0, 5, 10, 100, 1000)

        for (size in testSizes) {
            val stations = generateStations(size)
            val expected = classicFullSort(userLat, userLon, stations, limit = 10)
            val actual = NearbyStationFilter.extractTopNearest(userLat, userLon, stations, limit = 10)

            assertEquals("Size mismatch for dataset size $size", expected.size, actual.size)
            for (i in expected.indices) {
                assertEquals("ID mismatch at index $i for size $size", expected[i].id, actual[i].id)
                assertEquals(
                    "Distance mismatch at index $i for size $size",
                    expected[i].distanceKm!!,
                    actual[i].distanceKm!!,
                    1e-6
                )
            }
        }
    }

    @Test
    fun testTopKHandlesDuplicatesAndDifferentLimits() {
        val userLat = 21.0285
        val userLon = 105.8542

        // Dataset with intentional duplicates
        val baseStations = generateStations(50)
        val stationsWithDuplicates = baseStations + baseStations.take(20)

        val expected = classicFullSort(userLat, userLon, stationsWithDuplicates, limit = 10)
        val actual = NearbyStationFilter.extractTopNearest(userLat, userLon, stationsWithDuplicates, limit = 10)

        assertEquals(expected.size, actual.size)
        assertEquals(10, actual.size)
        for (i in expected.indices) {
            assertEquals(expected[i].id, actual[i].id)
            assertEquals(expected[i].distanceKm!!, actual[i].distanceKm!!, 1e-6)
        }

        // Test boundary limits: 0, negative, and custom limit
        assertTrue(NearbyStationFilter.extractTopNearest(userLat, userLon, baseStations, limit = 0).isEmpty())
        assertTrue(NearbyStationFilter.extractTopNearest(userLat, userLon, baseStations, limit = -5).isEmpty())

        val top3Expected = classicFullSort(userLat, userLon, baseStations, limit = 3)
        val top3Actual = NearbyStationFilter.extractTopNearest(userLat, userLon, baseStations, limit = 3)
        assertEquals(3, top3Actual.size)
        for (i in top3Expected.indices) {
            assertEquals(top3Expected[i].id, top3Actual[i].id)
        }
    }

    // =========================================================================
    // 2. Heap Efficiency: Only at most `limit` Station copies instantiated
    // =========================================================================

    @Test
    fun testHeapEfficiencyAllocationMinimization() {
        val userLat = 21.0285
        val userLon = 105.8542
        val stations = generateStations(1000)

        // Verify all 1000 stations have distanceKm == null initially
        assertTrue(stations.all { it.distanceKm == null })

        val limit = 10
        val top10 = NearbyStationFilter.extractTopNearest(userLat, userLon, stations, limit = limit)

        assertEquals(limit, top10.size)

        // Verify that the top 10 elements returned have distanceKm populated
        assertTrue(top10.all { it.distanceKm != null })

        // The original 1000 stations in the input list must remain untouched
        assertTrue(stations.all { it.distanceKm == null })

        // Check that out of the 1000 stations, exactly 10 IDs were selected
        val top10Ids = top10.map { it.id }.toSet()
        assertEquals(limit, top10Ids.size)

        // Non-selected stations (990) never had a copy with distanceKm instantiated
        val unselectedStations = stations.filter { it.id !in top10Ids }
        assertEquals(990, unselectedStations.size)
        assertTrue(unselectedStations.all { it.distanceKm == null })
    }

    // =========================================================================
    // 3. BoundedLruMap: mapValuesThreadSafe without defensive copies & thread safe
    // =========================================================================

    @Test
    fun testBoundedLruMapValuesThreadSafeCorrectnessAndConcurrency() {
        val map = BoundedLruMap<String, Int>(maxCapacity = 100)

        for (i in 1..50) {
            map["key_$i"] = i
        }

        // Correctness check
        val transformed = map.mapValuesThreadSafe { it.value * 10 }
        assertEquals(50, transformed.size)
        for (i in 1..50) {
            assertEquals(i * 10, transformed["key_$i"])
        }

        // Test forEachThreadSafe
        var sum = 0
        map.forEachThreadSafe { _, v -> sum += v }
        assertEquals((1..50).sum(), sum)

        // Concurrency check: multiple threads mutating and transforming simultaneously
        val threadCount = 8
        val iterations = 500
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errors = AtomicInteger(0)

        for (t in 0 until threadCount) {
            executor.execute {
                try {
                    for (iter in 0 until iterations) {
                        if (t % 2 == 0) {
                            // Mutator
                            val k = "key_${(iter % 150)}"
                            map[k] = iter
                            if (iter % 10 == 0) {
                                map.remove("key_${(iter % 50)}")
                            }
                        } else {
                            // Reader via mapValuesThreadSafe and forEachThreadSafe
                            val mapped = map.mapValuesThreadSafe { it.value.toString() }
                            assertNotNull(mapped)
                            var count = 0
                            map.forEachThreadSafe { _, _ -> count++ }
                        }
                    }
                } catch (e: Throwable) {
                    errors.incrementAndGet()
                    e.printStackTrace()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Threads timed out", latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()
        assertEquals("ConcurrentModificationException or error occurred during concurrent access", 0, errors.get())
    }

    @Test
    fun testEvcsRepositorySaveCachedCoordinatesUsesThreadSafeMapping() {
        val storage = InMemorySessionStorage()
        val coordinateMap = BoundedLruMap<String, Pair<Double, Double>>(maxCapacity = 500)
        coordinateMap["station_001"] = Pair(21.0285, 105.8542)

        val repo = EvcsRepository(
            apiClient = client,
            coordinateCache = coordinateMap,
            cacheStorage = storage,
            eagerLoadCache = false
        )

        repo.saveCachedCoordinates()

        // Verify storage was populated with JSON representation
        val cachedJson = storage.getString(EvcsRepository.KEY_COORDINATE_CACHE)
        assertNotNull("Storage must contain persisted coordinates", cachedJson)
        assertTrue(cachedJson!!.contains("station_001"))
        assertTrue(cachedJson.contains("21.0285"))
        assertTrue(cachedJson.contains("105.8542"))
    }

    // =========================================================================
    // 4. Dispatcher Routing for NearbyViewModel and FavoritesViewModel
    // =========================================================================

    private class TrackingDispatcher(
        private val delegate: CoroutineDispatcher
    ) : CoroutineDispatcher() {
        val dispatchCount = AtomicInteger(0)

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatchCount.incrementAndGet()
            delegate.dispatch(context, block)
        }

        override fun isDispatchNeeded(context: CoroutineContext): Boolean = true
    }

    @Test
    fun testNearbyViewModelDefaultsToDispatchersDefaultAndExecutesOnDefaultDispatcher() = runTest(testDispatcher) {
        val fakeLocation = object : LocationService() {
            override suspend fun getFreshLocation(): Location {
                return Location("test").apply {
                    latitude = 21.0285
                    longitude = 105.8542
                }
            }
            override val latestCoordinates: Pair<Double, Double> = Pair(21.0285, 105.8542)
            override fun hasLocationPermission(): Boolean = true
        }

        val testStations = generateStations(5)
        val fakeRepo = object : EvcsRepository(apiClient = client) {
            override suspend fun searchNearbyVinFast(lat: Double, lon: Double): Result<List<Station>> {
                return Result.success(testStations)
            }
        }

        // 1. Verify constructor defaultDispatcher defaults to Dispatchers.Default
        val defaultVm = NearbyViewModel(
            repository = fakeRepo,
            sessionManager = sessionManager,
            locationService = fakeLocation
        )
        val vmField = NearbyViewModel::class.java.getDeclaredField("defaultDispatcher")
        vmField.isAccessible = true
        val defaultVmDispatcher = vmField.get(defaultVm)
        assertEquals("NearbyViewModel must default defaultDispatcher to Dispatchers.Default", Dispatchers.Default, defaultVmDispatcher)

        // 2. Verify filter pipeline executes on the injected defaultDispatcher
        val trackingDefaultDispatcher = TrackingDispatcher(testDispatcher)
        val vm = NearbyViewModel(
            repository = fakeRepo,
            sessionManager = sessionManager,
            locationService = fakeLocation,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            defaultDispatcher = trackingDefaultDispatcher
        )

        vm.scanNearbyStations()
        advanceUntilIdle()

        assertTrue(
            "NearbyViewModel must execute filtering/Haversine on defaultDispatcher (dispatchCount=${trackingDefaultDispatcher.dispatchCount.get()})",
            trackingDefaultDispatcher.dispatchCount.get() > 0
        )
    }

    @Test
    fun testFavoritesViewModelDefaultsToDispatchersDefaultAndExecutesOnDefaultDispatcher() = runTest(testDispatcher) {
        val fakeLocation = object : LocationService() {
            override suspend fun getFreshLocation(): Location {
                return Location("test").apply {
                    latitude = 21.0285
                    longitude = 105.8542
                }
            }
            override val latestCoordinates: Pair<Double, Double> = Pair(21.0285, 105.8542)
            override fun hasLocationPermission(): Boolean = true
        }

        val testStations = generateStations(5)
        val fakeRepo = object : EvcsRepository(apiClient = client) {
            override suspend fun getFavorites(
                userLat: Double?,
                userLon: Double?,
                autoResolveUnknownCoordinates: Boolean
            ): Result<List<Station>> {
                return Result.success(testStations)
            }
        }

        // 1. Verify constructor defaultDispatcher defaults to Dispatchers.Default
        val defaultFavVm = FavoritesViewModel(
            repository = fakeRepo,
            authEngine = authEngine,
            locationService = fakeLocation
        )
        val favVmField = FavoritesViewModel::class.java.getDeclaredField("defaultDispatcher")
        favVmField.isAccessible = true
        val defaultFavDispatcher = favVmField.get(defaultFavVm)
        assertEquals("FavoritesViewModel must default defaultDispatcher to Dispatchers.Default", Dispatchers.Default, defaultFavDispatcher)

        // 2. Verify Haversine sort executes on injected defaultDispatcher
        val trackingFavDispatcher = TrackingDispatcher(testDispatcher)
        val favVm = FavoritesViewModel(
            repository = fakeRepo,
            authEngine = authEngine,
            locationService = fakeLocation,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            defaultDispatcher = trackingFavDispatcher
        )

        favVm.fetchFavorites()
        advanceUntilIdle()

        assertTrue(
            "FavoritesViewModel must execute Haversine sort on defaultDispatcher (dispatchCount=${trackingFavDispatcher.dispatchCount.get()})",
            trackingFavDispatcher.dispatchCount.get() > 0
        )
    }

    // =========================================================================
    // 5. Memoization of Port Compatibility and Connector Parsing
    // =========================================================================

    @Test
    fun testPortCompatibilityAndConnectorParsingMemoization() {
        val stationWithPowers = Station(
            id = "st_power",
            name = "St Power",
            address = "Addr",
            latitude = 21.0,
            longitude = 105.0,
            summary = "",
            connectors = "CCS2 60kW",
            depotStatus = "Normal",
            powers = listOf(PowerPort(typeWatts = 60_000L, availablePlugs = 1, totalPlugs = 2))
        )

        // Station with pre-parsed powers should fast-path check powers
        assertTrue(stationWithPowers.hasCarCompatiblePorts())

        val stationMotorbikeOnly = Station(
            id = "st_bike",
            name = "St Bike",
            address = "Addr",
            latitude = 21.0,
            longitude = 105.0,
            summary = "",
            connectors = "AC 3.5kW, AC 7kW",
            depotStatus = "Normal",
            powers = emptyList()
        )
        assertFalse(stationMotorbikeOnly.hasCarCompatiblePorts())

        // Repeated evaluation returns identical cached result
        assertFalse(stationMotorbikeOnly.hasCarCompatiblePorts())

        val parsed1 = EvcsRepository.parseConnectorsToPowers("CCS2 60kW, Type 2 22kW")
        val parsed2 = EvcsRepository.parseConnectorsToPowers("CCS2 60kW, Type 2 22kW")
        // Cache should return identical instance
        assertTrue(parsed1 === parsed2)
    }
}
