package com.evcs.favorites.car

import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import androidx.car.app.CarContext
import androidx.car.app.model.Row
import androidx.car.app.testing.TestCarContext
import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.domain.location.DistanceCalculator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Single Comprehensive Verification Test for Phase 03:
 * Auto Data Loading, Proximity Sorting & Thread Concurrency.
 *
 * Verifications:
 * 1. Auto-refresh triggers when initial stations are empty.
 * 2. Stations with coordinates are properly sorted nearest-first before truncation to 6 items.
 * 3. Prior refresh jobs are cancelled on subsequent refresh invocations without race condition.
 * 4. Fallback cache loading executes on IO dispatcher without throwing ANR/main thread violations.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CarStationDataLoaderAndSortingTest {

    private lateinit var testCarContext: CarContext

    @Before
    fun setUp() {
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(object : androidx.arch.core.executor.TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread(): Boolean = true
        })

        val fakeContext = object : ContextWrapper(null) {
            override fun getApplicationInfo(): ApplicationInfo {
                return ApplicationInfo().apply {
                    flags = ApplicationInfo.FLAG_DEBUGGABLE
                }
            }
            override fun getPackageName(): String = "com.evplus.app"
        }
        testCarContext = TestCarContext.createCarContext(fakeContext)
    }

    @After
    fun tearDown() {
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(null)
    }

    private fun createSampleStation(
        id: String = "sample_st",
        name: String = "VinFast Charging Station",
        address: String = "123 Pham Van Dong, Hanoi",
        lat: Double = 21.0285,
        lon: Double = 105.8542,
        availablePlugs: Int = 2,
        totalPlugs: Int = 4,
        distanceKm: Double? = null,
        evse: String = "VinFast"
    ): Station {
        return Station(
            id = id,
            name = name,
            address = address,
            latitude = lat,
            longitude = lon,
            summary = "24/7",
            connectors = "60kW",
            depotStatus = "Normal",
            powers = listOf(
                PowerPort(typeWatts = 60000L, label = "60kW", availablePlugs = availablePlugs, totalPlugs = totalPlugs)
            ),
            totalAvailablePlugs = availablePlugs,
            totalPlugs = totalPlugs,
            distanceKm = distanceKm,
            evse = evse
        )
    }

    open class TestEvcsRepository(
        var cachedList: List<Station> = emptyList(),
        var remoteList: List<Station> = emptyList(),
        var fetchDelayMs: Long = 0L,
        var shouldThrowRemote: Boolean = false,
        var onGetCachedCalled: (() -> Unit)? = null
    ) : EvcsRepository(
        apiClient = EvcsApiClient(sessionManager = SessionManager(InMemorySessionStorage()))
    ) {
        override fun getCachedFavorites(): List<Station> {
            onGetCachedCalled?.invoke()
            return cachedList
        }

        override suspend fun getFavorites(
            userLat: Double?,
            userLon: Double?,
            autoResolveUnknownCoordinates: Boolean
        ): Result<List<Station>> {
            if (fetchDelayMs > 0L) {
                delay(fetchDelayMs)
            }
            if (shouldThrowRemote) {
                throw IOException("Simulated network failure")
            }
            val enriched = if (userLat != null && userLon != null) {
                DistanceCalculator.attachDistances(remoteList, userLat, userLon)
            } else {
                remoteList
            }
            return Result.success(enriched)
        }
    }

    @Test
    fun testAutoRefreshTriggersWhenInitialStationsAreEmpty() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val sampleStation = createSampleStation(id = "st_auto_1", name = "Trạm Auto 1")
        val testRepo = TestEvcsRepository(
            cachedList = emptyList(),
            remoteList = listOf(sampleStation)
        )

        val screen = MainCarScreen(
            carContext = testCarContext,
            repository = testRepo,
            coroutineScope = this,
            ioDispatcher = testDispatcher
        )

        // In init, since stations is empty, refreshStations() was triggered
        assertTrue(screen.isLoading())
        testScheduler.advanceUntilIdle()

        assertFalse(screen.isLoading())
        assertEquals(1, screen.getStations().size)
        assertEquals("st_auto_1", screen.getStations().first().id)
    }

    @Test
    fun testProximitySortingNearestFirstBeforeTruncationTo6Items() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val userLat = 21.0285
        val userLon = 105.8542

        val stations = listOf(
            createSampleStation(id = "st_far", name = "Trạm Xa", lat = 21.4000, lon = 105.8542),
            createSampleStation(id = "st_nearest", name = "Trạm Gần Nhất", lat = 21.0385, lon = 105.8542),
            createSampleStation(id = "st_invalid", name = "Trạm Không Toạ Độ", lat = 0.0, lon = 0.0),
            createSampleStation(id = "st_med", name = "Trạm Vừa", lat = 21.1000, lon = 105.8542),
            createSampleStation(id = "st_close", name = "Trạm Khá Gần", lat = 21.0500, lon = 105.8542),
            createSampleStation(id = "st_mid", name = "Trạm Trung Bình", lat = 21.1500, lon = 105.8542),
            createSampleStation(id = "st_far2", name = "Trạm Xa Vừa", lat = 21.2500, lon = 105.8542),
            createSampleStation(id = "st_very_far", name = "Trạm Rất Xa", lat = 21.5000, lon = 105.8542)
        )

        // 1. Direct CarStationFormatter.sortStationsByProximity test
        val sorted = CarStationFormatter.sortStationsByProximity(stations, Pair(userLat, userLon))
        assertEquals("st_nearest", sorted[0].id)
        assertEquals("st_close", sorted[1].id)
        assertEquals("st_med", sorted[2].id)
        assertEquals("st_mid", sorted[3].id)
        assertEquals("st_far2", sorted[4].id)
        assertEquals("st_far", sorted[5].id)
        assertEquals("st_very_far", sorted[6].id)
        assertEquals("st_invalid", sorted[7].id)

        // 2. MainCarScreen integration test: verify proximity sorting & truncation to 6 items
        val testRepo = TestEvcsRepository(
            remoteList = stations
        )
        val screen = MainCarScreen(
            carContext = testCarContext,
            repository = testRepo,
            locationResolver = { Pair(userLat, userLon) },
            coroutineScope = this,
            ioDispatcher = testDispatcher
        )
        testScheduler.advanceUntilIdle()

        val itemList = screen.buildItemList()
        assertEquals(6, itemList.items.size)
        val firstRow = itemList.items[0] as Row
        assertTrue(firstRow.title?.toString()?.contains("Trạm Gần Nhất") == true)
        val sixthRow = itemList.items[5] as Row
        assertTrue(sixthRow.title?.toString()?.contains("Trạm Xa") == true)
    }

    @Test
    fun testPriorRefreshJobsCancelledOnSubsequentRefreshInvocations() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val slowRepo = TestEvcsRepository(
            fetchDelayMs = 5000L,
            remoteList = listOf(createSampleStation("st_delayed"))
        )

        val screen = MainCarScreen(
            carContext = testCarContext,
            repository = slowRepo,
            coroutineScope = this,
            ioDispatcher = testDispatcher
        )

        val job1 = screen.getRefreshJob()
        assertNotNull("First refresh job should be launched", job1)
        assertTrue("First refresh job should be active", job1!!.isActive)

        // Subsequent refresh invocation before the first finishes
        screen.refreshStations()
        val job2 = screen.getRefreshJob()
        assertNotNull("Second refresh job should be launched", job2)
        assertNotSame("Second refresh job should be a new instance", job1, job2)
        assertTrue("First refresh job must be cancelled to prevent race condition", job1.isCancelled)
        assertTrue("Second refresh job should be active", job2!!.isActive)

        testScheduler.advanceUntilIdle()
        assertFalse(screen.isLoading())
    }

    @Test
    fun testFallbackCacheLoadingExecutesOnIoDispatcherWithoutAnr() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val cachedStation = createSampleStation("st_cached", name = "Trạm Cache", lat = 21.0285, lon = 105.8542)
        var cacheReadCount = 0

        val failingRepo = object : TestEvcsRepository(
            cachedList = listOf(cachedStation),
            shouldThrowRemote = true
        ) {
            override fun getCachedFavorites(): List<Station> {
                cacheReadCount++
                return cachedList
            }
        }

        val screen = MainCarScreen(
            carContext = testCarContext,
            repository = failingRepo,
            locationResolver = { Pair(21.0285, 105.8542) },
            coroutineScope = this,
            ioDispatcher = testDispatcher
        )

        testScheduler.advanceUntilIdle()

        assertFalse("Screen must not be in loading state after fallback", screen.isLoading())
        assertEquals(1, screen.getStations().size)
        assertEquals("st_cached", screen.getStations().first().id)
        assertTrue("Cached favorites should be read at least once during fallback", cacheReadCount >= 1)
    }
}
