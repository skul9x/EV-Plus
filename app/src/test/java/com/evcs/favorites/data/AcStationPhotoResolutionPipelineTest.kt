package com.evcs.favorites.data

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.SearchStationRaw
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsTelemetryRepository
import com.evcs.favorites.data.telemetry.EvcsTelemetryDataSource
import com.evcs.favorites.focus.EvcsStationNameResolver
import com.evcs.favorites.ui.viewmodel.StationDetailCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single comprehensive test file verifying Phase 01:
 * AC Station Photo Resolution Pipeline & StationDetailCoordinator Integration.
 *
 * Core verification criteria:
 * 1. AC station with `images = emptyList()` successfully resolves and decodes photos when matching station exists.
 * 2. Matching strategy 1: Location ID / station slug matching against VinFast search results.
 * 3. Matching strategy 2: Fallback to GPS coordinate proximity matching (<= 100 meters).
 * 4. Matching strategy 3: Secondary fallback to EVCS HTML detail page (`tram-sac-vinfast-{id}.html`).
 * 5. Multi-pass decoding of extracted media items to direct CloudFront S3 CDN URLs (`https://cpo-prod-s3.vinfastauto.com/...`).
 * 6. Preserves original server ordering without kW tier filtering.
 * 7. In-memory caching per locationId / coordinate to prevent duplicate network calls.
 * 8. `StationDetailCoordinator` emits state update with enriched `station.images` and `station.image`.
 * 9. Network failure or empty server response degrades gracefully without crash or blocking telemetry.
 * 10. Instant opening (0ms) and clean lifecycle cancellation upon detail sheet dismissal.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AcStationPhotoResolutionPipelineTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var sessionManager: SessionManager
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private fun createDoubleBase64Token(targetUrl: String): String {
        val urlEncoded = URLEncoder.encode(targetUrl, StandardCharsets.UTF_8.name())
        val pass1Bytes = Base64.getEncoder().encode(urlEncoded.toByteArray(StandardCharsets.UTF_8))
        val pass1Str = String(pass1Bytes, StandardCharsets.UTF_8)
        val tokenBytes = Base64.getEncoder().encode(pass1Str.toByteArray(StandardCharsets.UTF_8))
        return String(tokenBytes, StandardCharsets.UTF_8)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockWebServer = MockWebServer()
        mockWebServer.start()

        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()

        val storage = InMemorySessionStorage().apply {
            putString(SessionManager.KEY_DEVICE_ID, "dev_photo_pipeline_test")
        }
        sessionManager = SessionManager(storage).apply {
            phpSessionId = "sess_photo_test"
            authCookie = "auth_photo_test"
        }
    }

    @After
    fun tearDown() {
        testScope.cancel()
        Dispatchers.resetMain()
        mockWebServer.shutdown()
    }

    // =========================================================================
    // 1. Strategy 1: Match by locationId and Slug with Preserved Server Ordering
    // =========================================================================

    @Test
    fun testPhotoResolution_matchesByLocationIdAndPreservesServerOrder() = testScope.runTest {
        val cdnPhoto1 = "https://cpo-prod-s3.vinfastauto.com/charging-station-car/depot/images/ac_post_01.jpg"
        val cdnPhoto2 = "https://cpo-prod-s3.vinfastauto.com/charging-station-car/depot/images/ac_post_02.jpg"
        val cdnPhoto3 = "https://cpo-prod-s3.vinfastauto.com/charging-station-car/depot/images/ac_post_03.jpg"

        val token1 = createDoubleBase64Token(cdnPhoto1)
        val token2 = createDoubleBase64Token(cdnPhoto2)
        val token3 = createDoubleBase64Token(cdnPhoto3)

        val rawStations = listOf(
            SearchStationRaw(
                locationId = "C.BNI11197",
                stationName = "Trạm sạc VinFast Nguyễn Văn Cừ",
                latitude = 21.1856,
                longitude = 106.0742,
                media = listOf(token1, token2, token3)
            )
        )

        val resolver = EvcsStationNameResolver(
            searchStationsProvider = { _, _ -> Result.success(rawStations) },
            httpClient = okHttpClient,
            htmlBaseUrl = mockWebServer.url("/").toString()
        )

        val resolved = resolver.resolveStationPhotos(
            stationId = "C.BNI11197",
            latitude = 21.1856,
            longitude = 106.0742,
            stationName = "VinFast Nguyễn Văn Cừ"
        )

        assertEquals(3, resolved.size)
        // Original server order must be preserved exactly
        assertEquals(cdnPhoto1, resolved[0])
        assertEquals(cdnPhoto2, resolved[1])
        assertEquals(cdnPhoto3, resolved[2])
    }

    // =========================================================================
    // 2. Strategy 2: GPS Coordinate Proximity Fallback Matching (<= 100 meters)
    // =========================================================================

    @Test
    fun testPhotoResolution_fallbackToProximityMatchingUnder100Meters() = testScope.runTest {
        val cdnPhoto = "https://cpo-prod-s3.vinfastauto.com/charging-station-car/depot/images/proximity_ac.jpg"
        val token = createDoubleBase64Token(cdnPhoto)

        // Raw search station at coordinates (~40 meters away from query candidate)
        val rawStations = listOf(
            SearchStationRaw(
                locationId = "RAW_SEARCH_ID_999",
                stationName = "Trạm sạc VinFast Proximity Test",
                latitude = 10.7725,
                longitude = 106.6980,
                media = listOf(token)
            )
        )

        val resolver = EvcsStationNameResolver(
            searchStationsProvider = { _, _ -> Result.success(rawStations) },
            httpClient = okHttpClient,
            htmlBaseUrl = mockWebServer.url("/").toString()
        )

        // Candidate AC station from HERE EV API has different ID (e.g. HERE place ID)
        val hereCandidateId = "here_ac_node_777"
        val resolved = resolver.resolveStationPhotos(
            stationId = hereCandidateId,
            latitude = 10.7728, // ~35-40m offset
            longitude = 106.6981,
            stationName = "VinFast Proximity Test"
        )

        assertEquals(1, resolved.size)
        assertEquals(cdnPhoto, resolved[0])
    }

    // =========================================================================
    // 3. Strategy 3: Secondary Fallback to EVCS HTML Detail Page
    // =========================================================================

    @Test
    fun testPhotoResolution_secondaryFallbackToEvcsHtmlDetailPage() = testScope.runTest {
        val cdnPhotoA = "https://cpo-prod-s3.vinfastauto.com/charging-station-car/depot/images/html_post_a.jpg"
        val cdnPhotoB = "https://cpo-prod-s3.vinfastauto.com/charging-station-car/depot/images/html_post_b.jpg"

        val tokenA = createDoubleBase64Token(cdnPhotoA)
        val tokenB = createDoubleBase64Token(cdnPhotoB)

        // HTML mock response containing media tokens in both <img> tags and JSON
        val mockHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Trạm sạc VinFast - Hộ kinh doanh Đức Bình - Trạm Sạc EV</title>
            </head>
            <body>
                <div class="gallery">
                    <img src="/media?file=$tokenA" alt="Trụ sạc AC" />
                    <img src="https://evcs.vn/media?file=$tokenB" alt="Trụ sạc AC 2" />
                </div>
            </body>
            </html>
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(mockHtml))

        // Search API returns empty, forcing fallback to HTML
        val resolver = EvcsStationNameResolver(
            searchStationsProvider = { _, _ -> Result.success(emptyList()) },
            httpClient = okHttpClient,
            htmlBaseUrl = mockWebServer.url("/").toString()
        )

        val resolved = resolver.resolveStationPhotos(
            stationId = "C.BNI0088",
            latitude = 21.0500,
            longitude = 105.8500
        )

        assertEquals(2, resolved.size)
        assertEquals(cdnPhotoA, resolved[0])
        assertEquals(cdnPhotoB, resolved[1])

        val req = mockWebServer.takeRequest()
        assertEquals("/tram-sac-vinfast-c.bni0088.html", req.path)
        assertEquals(EvcsApiClient.USER_AGENT_BROWSER, req.getHeader("User-Agent"))
    }

    // =========================================================================
    // 4. In-Memory Caching Prevents Duplicate Network Calls
    // =========================================================================

    @Test
    fun testPhotoResolution_inMemoryCachingPreventsDuplicateCalls() = testScope.runTest {
        val cdnPhoto = "https://cpo-prod-s3.vinfastauto.com/charging-station-car/depot/images/cached_ac.jpg"
        val token = createDoubleBase64Token(cdnPhoto)

        val networkCallCount = AtomicInteger(0)
        val resolver = EvcsStationNameResolver(
            searchStationsProvider = { _, _ ->
                networkCallCount.incrementAndGet()
                Result.success(
                    listOf(
                        SearchStationRaw(
                            locationId = "CACHE_ID_01",
                            stationName = "VinFast Cached Station",
                            latitude = 16.0544,
                            longitude = 108.2022,
                            media = listOf(token)
                        )
                    )
                )
            },
            httpClient = okHttpClient,
            htmlBaseUrl = mockWebServer.url("/").toString()
        )

        // First call: Triggers search provider
        val firstResult = resolver.resolveStationPhotos(
            stationId = "CACHE_ID_01",
            latitude = 16.0544,
            longitude = 108.2022
        )
        assertEquals(1, firstResult.size)
        assertEquals(1, networkCallCount.get())

        // Second call: Returns directly from cache without hitting searchStationsProvider
        val secondResult = resolver.resolveStationPhotos(
            stationId = "CACHE_ID_01",
            latitude = 16.0544,
            longitude = 108.2022
        )
        assertEquals(1, secondResult.size)
        assertEquals(cdnPhoto, secondResult[0])
        assertEquals(1, networkCallCount.get()) // Unchanged
    }

    // =========================================================================
    // 5. StationDetailCoordinator Integration & State Update
    // =========================================================================

    @Test
    fun testStationDetailCoordinator_enrichesEmptyImagesConcurrently() = testScope.runTest {
        val cdnPhoto1 = "https://cpo-prod-s3.vinfastauto.com/charging-station-car/depot/images/ac_enriched_1.jpg"
        val cdnPhoto2 = "https://cpo-prod-s3.vinfastauto.com/charging-station-car/depot/images/ac_enriched_2.jpg"
        val token1 = createDoubleBase64Token(cdnPhoto1)
        val token2 = createDoubleBase64Token(cdnPhoto2)

        val rawStations = listOf(
            SearchStationRaw(
                locationId = "AC_STATION_COORD",
                stationName = "VinFast AC Station Enriched",
                latitude = 10.8231,
                longitude = 106.6297,
                media = listOf(token1, token2)
            )
        )

        val resolver = EvcsStationNameResolver(
            searchStationsProvider = { _, _ -> Result.success(rawStations) },
            httpClient = okHttpClient,
            htmlBaseUrl = mockWebServer.url("/").toString()
        )

        // Mock telemetry HTTP endpoint
        val tokenResponseJson = """
            {
                "rating": {"avg": 4.8, "count": 10, "mine": 0},
                "chargeToken": "chg_token_test",
                "apiToken": "api_token_test"
            }
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(tokenResponseJson))

        val liveChargingJson = """
            {
                "busyByKw": {"11": 1}
            }
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(liveChargingJson))

        val telemetryRepo = EvcsTelemetryRepository(
            dataSource = EvcsTelemetryDataSource(
                sessionManager = sessionManager,
                client = okHttpClient,
                baseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
                ioDispatcher = testDispatcher
            ),
            ioDispatcher = testDispatcher
        )

        val coordinator = StationDetailCoordinator(
            coroutineScope = this,
            telemetryRepository = telemetryRepo,
            photoResolver = resolver,
            ioDispatcher = testDispatcher,
            mainDispatcher = testDispatcher
        )

        // Candidate AC station with empty images
        val candidateAcStation = Station(
            id = "AC_STATION_COORD",
            name = "VinFast AC Station Enriched",
            address = "789 Lê Đức Thọ, Gò Vấp",
            latitude = 10.8231,
            longitude = 106.6297,
            summary = "24/7",
            connectors = "11kW x 2",
            depotStatus = "Normal",
            powers = listOf(
                PowerPort(typeWatts = 11000L, label = "11kW", availablePlugs = 2, totalPlugs = 2)
            ),
            totalPlugs = 2,
            images = emptyList() // Initially empty!
        )

        // 1. Instant 0ms opening
        coordinator.selectStationForDetail(candidateAcStation)
        val instantState = coordinator.stationDetailState.value
        assertNotNull(instantState.station)
        assertTrue(instantState.station!!.images.isEmpty())
        assertNull(instantState.station!!.image)
        assertTrue(instantState.isLoadingTelemetry)

        // 2. Advance coroutines to complete Stage 1 telemetry and background photo resolution
        testScheduler.advanceUntilIdle()

        val finalState = coordinator.stationDetailState.value
        val enrichedStation = finalState.station
        assertNotNull(enrichedStation)
        assertEquals(2, enrichedStation!!.images.size)
        assertEquals(cdnPhoto1, enrichedStation.images[0])
        assertEquals(cdnPhoto2, enrichedStation.images[1])
        assertEquals(cdnPhoto1, enrichedStation.image)
        assertFalse(finalState.isLoadingTelemetry)
        assertEquals(4.8, finalState.rating?.avg ?: 0.0, 0.01)
    }

    // =========================================================================
    // 6. Graceful Degradation on Network Failure or Empty Server Media
    // =========================================================================

    @Test
    fun testGracefulDegradation_networkFailureDoesNotBlockTelemetryOrCrash() = testScope.runTest {
        val resolver = EvcsStationNameResolver(
            searchStationsProvider = { _, _ -> Result.failure(java.io.IOException("Search network timeout")) },
            httpClient = okHttpClient,
            htmlBaseUrl = mockWebServer.url("/").toString()
        )

        val tokenResponseJson = """
            {
                "rating": {"avg": 4.5, "count": 5, "mine": 0},
                "chargeToken": "chg_ok",
                "apiToken": "api_ok"
            }
        """.trimIndent()

        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val partial = request.getHeader("X-Partial")
                return when {
                    partial == "user" -> MockResponse().setResponseCode(200).setBody(tokenResponseJson)
                    path.contains("/charging") -> MockResponse().setResponseCode(200).setBody("""{"busyByKw": {}}""")
                    // HTML photo resolution request fails with 500
                    else -> MockResponse().setResponseCode(500).setBody("Server Error")
                }
            }
        }

        val telemetryRepo = EvcsTelemetryRepository(
            dataSource = EvcsTelemetryDataSource(
                sessionManager = sessionManager,
                client = okHttpClient,
                baseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
                ioDispatcher = testDispatcher
            ),
            ioDispatcher = testDispatcher
        )

        val coordinator = StationDetailCoordinator(
            coroutineScope = this,
            telemetryRepository = telemetryRepo,
            photoResolver = resolver,
            ioDispatcher = testDispatcher,
            mainDispatcher = testDispatcher
        )

        val stationWithoutPhotos = Station(
            id = "AC_NO_PHOTO",
            name = "Trạm AC Không Có Ảnh",
            address = "Xã Đàn, Hà Nội",
            latitude = 21.0180,
            longitude = 105.8340,
            summary = "24/7",
            connectors = "22kW x 1",
            depotStatus = "Normal",
            totalPlugs = 1,
            images = emptyList()
        )

        coordinator.selectStationForDetail(stationWithoutPhotos)
        testScheduler.advanceUntilIdle()

        val state = coordinator.stationDetailState.value
        assertNotNull(state.station)
        assertTrue(state.station!!.images.isEmpty())
        assertNull(state.error)
        assertFalse(state.isLoadingTelemetry)
        assertEquals(4.5, state.rating?.avg ?: 0.0, 0.01)
    }

    // =========================================================================
    // 7. Lifecycle Cancellation upon Dismissal
    // =========================================================================

    @Test
    fun testLifecycleCancellation_dismissalCancelsActiveAndPhotoJobs() = testScope.runTest {
        val resolver = EvcsStationNameResolver(
            searchStationsProvider = { _, _ ->
                kotlinx.coroutines.delay(10_000)
                Result.success(emptyList())
            },
            httpClient = okHttpClient
        )

        val telemetryRepo = EvcsTelemetryRepository(
            dataSource = EvcsTelemetryDataSource(
                sessionManager = sessionManager,
                client = okHttpClient,
                baseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
                ioDispatcher = testDispatcher
            ),
            ioDispatcher = testDispatcher
        )

        val coordinator = StationDetailCoordinator(
            coroutineScope = this,
            telemetryRepository = telemetryRepo,
            photoResolver = resolver,
            ioDispatcher = testDispatcher,
            mainDispatcher = testDispatcher
        )

        val station = Station(
            id = "VF_DISMISS",
            name = "Trạm Huỷ",
            address = "TP.HCM",
            latitude = 10.7,
            longitude = 106.7,
            summary = "24/7",
            connectors = "11kW x 1",
            depotStatus = "Normal",
            totalPlugs = 1,
            images = emptyList()
        )

        coordinator.selectStationForDetail(station)
        val activeJob = coordinator.activeJob
        val photoJob = coordinator.photoJob

        assertNotNull(activeJob)
        assertNotNull(photoJob)
        assertTrue(activeJob!!.isActive)
        assertTrue(photoJob!!.isActive)

        coordinator.dismissStationDetail()

        assertTrue(activeJob.isCancelled)
        assertTrue(photoJob.isCancelled)
        assertNull(coordinator.activeJob)
        assertNull(coordinator.photoJob)
        assertNull(coordinator.stationDetailState.value.station)
    }
}
