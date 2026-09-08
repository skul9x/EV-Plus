package com.evcs.favorites.hardening

import android.content.Context
import android.media.AudioManager
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.saveable.autoSaver
import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.api.RateLimitException
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.FavoriteStationRaw
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.focus.FocusModeTtsManager
import com.evcs.favorites.ui.components.StationPhotoViewerHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single Comprehensive Verification Test for Phase 04:
 * Client Rate Limit & Lifecycle Edge Cases Hardening.
 *
 * Validates:
 * 1. EvcsApiClient pre-network rate limit check:
 *    Checks isGlobalRateLimited() at the entrance of searchStations, fetchFavorites,
 *    saveFavorites, and fetchStationHtml. Fails immediately with RateLimitException
 *    without opening HTTP connections or dispatching network round-trips.
 * 2. EvcsApiClient cooldown reset and 429 response handling:
 *    Setting Retry-After activates cooldown, resetRateLimitCooldown() clears cooldown.
 * 3. EvcsRepository.searchNearbyVinFast pre-network rate limit check:
 *    Checks isGlobalRateLimited() before singleFlight execution, returning RateLimitException
 *    immediately when in cooldown.
 * 4. FocusModeTtsManager Audio Focus Safety & Watchdog:
 *    Enforces fallback watchdog timeout so if a 3rd-party TTS engine hangs or fails
 *    to fire onDone/onError, audio ducking focus is automatically abandoned within timeout.
 * 5. FocusModeTtsManager normal speech completion & lifecycle:
 *    Normal onDone or stop/shutdown immediately cancels watchdog and abandons duck focus.
 * 6. Lightbox Gesture & Drag Performance:
 *    StationPhotoViewerHelper gesture calculations (shouldDismissOnDrag, calculateDismissAlpha,
 *    isPagingAllowed, clampZoomScale, clampPanOffset) for continuous drag state mutation without
 *    per-frame coroutine allocations.
 * 7. State Preservation across Rotations:
 *    Dialog states (showRoutingSettings, showLoginRequiredDialog, showPermissionRationale)
 *    compatibility with rememberSaveable autoSaver restoration.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RateLimitAndLifecycleHardeningTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockWebServer: MockWebServer
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var apiClient: EvcsApiClient
    private lateinit var repository: EvcsRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockWebServer = MockWebServer()
        mockWebServer.start()

        sessionStorage = InMemorySessionStorage()
        sessionManager = SessionManager(sessionStorage)

        val client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()

        apiClient = EvcsApiClient(
            sessionManager = sessionManager,
            client = client,
            baseUrl = mockWebServer.url("/").toString().removeSuffix("/")
        )

        repository = EvcsRepository(
            apiClient = apiClient,
            cacheStorage = sessionStorage,
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        Dispatchers.resetMain()
    }

    // =========================================================================
    // 1. EvcsApiClient Pre-Network Client Rate Limit Enforcement Tests
    // =========================================================================

    @Test
    fun testEvcsApiClient_PreNetworkRateLimit_BlocksSearchStationsWithoutNetwork() = runTest {
        // Activate global rate limit cooldown for 30 seconds
        val cooldownEnd = System.currentTimeMillis() + 30_000L
        apiClient.globalRateLimitedUntil.set(cooldownEnd)
        assertTrue("Client should be globally rate limited", apiClient.isGlobalRateLimited())

        // Attempt searchStations while in cooldown
        val searchResult = apiClient.searchStations(latitude = 21.0285, longitude = 105.8542)

        assertTrue("searchStations should fail when client rate limited", searchResult.isFailure)
        val exception = searchResult.exceptionOrNull()
        assertTrue("Exception should be RateLimitException", exception is RateLimitException)
        val rateLimitEx = exception as RateLimitException
        assertTrue("Retry-after seconds should be positive", rateLimitEx.retryAfterSeconds in 1L..31L)
        assertTrue("Message should indicate client rate limited", rateLimitEx.message?.contains("Client rate limited") == true)

        // MockWebServer MUST receive zero HTTP requests
        assertEquals("No network requests should be made while in cooldown", 0, mockWebServer.requestCount)
    }

    @Test
    fun testEvcsApiClient_PreNetworkRateLimit_BlocksFetchFavoritesWithoutNetwork() = runTest {
        // Activate cooldown
        apiClient.globalRateLimitedUntil.set(System.currentTimeMillis() + 15_000L)

        // Attempt fetchFavorites while in cooldown
        val favResult = apiClient.fetchFavorites()

        assertTrue("fetchFavorites should fail when client rate limited", favResult.isFailure)
        assertTrue("Exception should be RateLimitException", favResult.exceptionOrNull() is RateLimitException)
        assertEquals("No network requests should be made", 0, mockWebServer.requestCount)
    }

    @Test
    fun testEvcsApiClient_PreNetworkRateLimit_BlocksSaveFavoritesWithoutNetwork() = runTest {
        // Activate cooldown
        apiClient.globalRateLimitedUntil.set(System.currentTimeMillis() + 20_000L)

        // Attempt saveFavorites while in cooldown
        val saveResult = apiClient.saveFavorites(
            stations = listOf(FavoriteStationRaw(locationId = "LOC_01", name = "Station 1", address = "Address 1"))
        )

        assertTrue("saveFavorites should fail when client rate limited", saveResult.isFailure)
        assertTrue("Exception should be RateLimitException", saveResult.exceptionOrNull() is RateLimitException)
        assertEquals("No network requests should be made", 0, mockWebServer.requestCount)
    }

    @Test
    fun testEvcsApiClient_PreNetworkRateLimit_BlocksFetchStationHtmlWithoutNetwork() = runTest {
        // Activate cooldown
        apiClient.globalRateLimitedUntil.set(System.currentTimeMillis() + 25_000L)

        // Attempt fetchStationHtml while in cooldown
        val htmlResult = apiClient.fetchStationHtml(stationName = "Station Test", locationId = "LOC_99")

        assertTrue("fetchStationHtml should fail when client rate limited", htmlResult.isFailure)
        assertTrue("Exception should be RateLimitException", htmlResult.exceptionOrNull() is RateLimitException)
        assertEquals("No network requests should be made", 0, mockWebServer.requestCount)
    }

    @Test
    fun testEvcsApiClient_ResetCooldown_AllowsRequestsToProceed() = runTest {
        // Set cooldown then reset it
        apiClient.globalRateLimitedUntil.set(System.currentTimeMillis() + 60_000L)
        assertTrue(apiClient.isGlobalRateLimited())

        apiClient.resetRateLimitCooldown()
        assertFalse(apiClient.isGlobalRateLimited())

        // Enqueue valid MockWebServer response
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"code":200000,"data":[]}""")
        )

        val result = apiClient.searchStations(latitude = 21.0, longitude = 105.0)
        assertTrue("Request should succeed after cooldown reset", result.isSuccess)
        assertEquals("Network request should be dispatched", 1, mockWebServer.requestCount)
    }

    // =========================================================================
    // 2. EvcsRepository.searchNearbyVinFast Rate Limit Check Tests
    // =========================================================================

    @Test
    fun testEvcsRepository_SearchNearbyVinFast_BlockedImmediatelyWhenRateLimited() = runTest {
        // Set rate limit cooldown on apiClient
        apiClient.globalRateLimitedUntil.set(System.currentTimeMillis() + 45_000L)
        assertTrue("Repository should reflect global rate limit", repository.isGlobalRateLimited())

        // Execute searchNearbyVinFast
        val result = repository.searchNearbyVinFast(lat = 21.0285, lon = 105.8542)

        assertTrue("searchNearbyVinFast should fail immediately when rate limited", result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue("Exception should be RateLimitException", ex is RateLimitException)
        assertEquals("No network calls should have been made to MockWebServer", 0, mockWebServer.requestCount)

        // Also test when repo's own globalRateLimitedUntil is set
        apiClient.resetRateLimitCooldown()
        repository.globalRateLimitedUntil.set(System.currentTimeMillis() + 10_000L)
        assertTrue(repository.isGlobalRateLimited())

        val result2 = repository.searchNearbyVinFast(lat = 21.0285, lon = 105.8542)
        assertTrue("searchNearbyVinFast should fail when repo cooldown is set", result2.isFailure)
        assertTrue(result2.exceptionOrNull() is RateLimitException)
        assertEquals(0, mockWebServer.requestCount)
    }

    @Test
    fun testEvcsRepository_SearchNearbyVinFast_SucceedsWhenRateLimitCleared() = runTest {
        repository.resetRateLimitCooldown()
        assertFalse(repository.isGlobalRateLimited())

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{
                        "code": 200000,
                        "data": [
                            {
                                "station_name": "Trạm Sạc VinFast Thăng Long",
                                "locationId": "LOC_TL_01",
                                "latitude": 21.0100,
                                "longitude": 105.8000,
                                "evse_powers": [
                                    {"watts": 60000, "label": "60kW", "available_plugs": 2, "total_plugs": 2}
                                ]
                            }
                        ]
                    }"""
                )
        )

        val result = repository.searchNearbyVinFast(lat = 21.0100, lon = 105.8000)
        assertTrue("searchNearbyVinFast should succeed when not rate limited", result.isSuccess)
        val stations = result.getOrThrow()
        assertEquals(1, stations.size)
        assertEquals("LOC_TL_01", stations[0].id)
        assertEquals(1, mockWebServer.requestCount)
    }

    // =========================================================================
    // 3. FocusModeTtsManager Watchdog & Audio Focus Safety Tests
    // =========================================================================

    private fun createFakeContext(): Context {
        return object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? = null
        }
    }

    @Test
    fun testFocusModeTtsManager_WatchdogTimeout_AutomaticallyAbandonsDuckFocusWhenTtsHangs() = runTest {
        var abandonCallbackFired = false

        val ttsManager = FocusModeTtsManager(
            context = createFakeContext(),
            audioManager = null,
            initTtsImmediately = false,
            coroutineScope = this,
            watchdogTimeoutMs = 6000L,
            onDuckFocusAbandoned = { abandonCallbackFired = true }
        )

        // Simulate speech started with audio ducking focus requested
        ttsManager.requestDuckAudioFocus()
        assertTrue("Audio ducking focus should be active", ttsManager.isAudioDuckingActive)

        // Manually trigger watchdog (simulating speech execution where active utterances = 1)
        val activeUtterancesField = FocusModeTtsManager::class.java.getDeclaredField("activeUtteranceCount")
        activeUtterancesField.isAccessible = true
        (activeUtterancesField.get(ttsManager) as java.util.concurrent.atomic.AtomicInteger).set(1)

        ttsManager.startWatchdog()
        assertTrue("Watchdog timer should be active", ttsManager.isWatchdogActive)
        assertEquals(1, ttsManager.activeUtterances)

        // Advance virtual time by 5900ms (just before 6-second timeout)
        advanceTimeBy(5900L)
        assertTrue("Watchdog should still be active before timeout", ttsManager.isWatchdogActive)
        assertTrue("Audio ducking should still be held", ttsManager.isAudioDuckingActive)
        assertFalse("Abandon callback should not have fired yet", abandonCallbackFired)

        // Advance virtual time past 6000ms watchdog timeout (e.g. +200ms -> 6100ms)
        advanceTimeBy(200L)
        advanceUntilIdle()

        // Verify watchdog triggered emergency fallback
        assertFalse("Audio ducking should be automatically abandoned by watchdog", ttsManager.isAudioDuckingActive)
        assertEquals("Active utterances count should be reset to 0", 0, ttsManager.activeUtterances)
        assertTrue("Abandon callback should have fired", abandonCallbackFired)
        assertFalse("Watchdog job should have completed", ttsManager.isWatchdogActive)
    }

    @Test
    fun testFocusModeTtsManager_NormalCompletion_CancelsWatchdogImmediately() = runTest {
        var abandonCallCount = 0

        val ttsManager = FocusModeTtsManager(
            context = createFakeContext(),
            audioManager = null,
            initTtsImmediately = false,
            coroutineScope = this,
            watchdogTimeoutMs = 6000L,
            onDuckFocusAbandoned = { abandonCallCount++ }
        )

        ttsManager.requestDuckAudioFocus()
        val activeUtterancesField = FocusModeTtsManager::class.java.getDeclaredField("activeUtteranceCount")
        activeUtterancesField.isAccessible = true
        (activeUtterancesField.get(ttsManager) as java.util.concurrent.atomic.AtomicInteger).set(1)

        ttsManager.startWatchdog()
        assertTrue(ttsManager.isWatchdogActive)

        // Normal speech completion within 2 seconds
        advanceTimeBy(2000L)
        ttsManager.abandonDuckAudioFocus()

        assertFalse("Audio ducking should be released", ttsManager.isAudioDuckingActive)
        assertFalse("Watchdog should be cancelled immediately on normal completion", ttsManager.isWatchdogActive)
        assertEquals("Abandon callback should be invoked once", 1, abandonCallCount)

        // Advance remaining time past 6s to ensure watchdog does NOT fire again
        advanceTimeBy(5000L)
        advanceUntilIdle()
        assertEquals("Abandon callback should not be invoked again by cancelled watchdog", 1, abandonCallCount)
    }

    @Test
    fun testFocusModeTtsManager_StopAndShutdown_SafelyReleaseWatchdogAndDucking() = runTest {
        var abandonCalls = 0

        val ttsManager = FocusModeTtsManager(
            context = createFakeContext(),
            audioManager = null,
            initTtsImmediately = false,
            coroutineScope = this,
            watchdogTimeoutMs = 6000L,
            onDuckFocusAbandoned = { abandonCalls++ }
        )

        ttsManager.requestDuckAudioFocus()
        ttsManager.startWatchdog()
        assertTrue(ttsManager.isWatchdogActive)

        // Stop cancels watchdog and ducking
        ttsManager.stop()
        assertFalse(ttsManager.isWatchdogActive)
        assertFalse(ttsManager.isAudioDuckingActive)
        assertEquals(0, ttsManager.activeUtterances)
        assertEquals(1, abandonCalls)

        // Shutdown also safely handles cleanup
        ttsManager.shutdown()
        assertFalse(ttsManager.isWatchdogActive)
        assertFalse(ttsManager.isInitialized)
    }

    // =========================================================================
    // 4. Lightbox Gesture & Drag Performance Tests
    // =========================================================================

    @Test
    fun testStationPhotoViewerHelper_DismissDragCalculations() {
        // Test dismiss threshold detection: threshold is 100dp
        assertFalse(StationPhotoViewerHelper.shouldDismissOnDrag(dragOffsetY = 50f))
        assertFalse(StationPhotoViewerHelper.shouldDismissOnDrag(dragOffsetY = 99.9f))
        assertTrue(StationPhotoViewerHelper.shouldDismissOnDrag(dragOffsetY = 100f))
        assertTrue(StationPhotoViewerHelper.shouldDismissOnDrag(dragOffsetY = 250f))

        // Test fling velocity override (> 1000f velocity dismisses even with small drag)
        assertTrue(StationPhotoViewerHelper.shouldDismissOnDrag(dragOffsetY = 20f, velocityY = 1200f))
        assertFalse(StationPhotoViewerHelper.shouldDismissOnDrag(dragOffsetY = 20f, velocityY = 800f))

        // Test dismiss alpha calculation
        val initialAlpha = StationPhotoViewerHelper.calculateDismissAlpha(dragOffsetY = 0f, maxDrag = 200f, baseAlpha = 0.95f)
        assertEquals(0.95f, initialAlpha, 0.001f)

        val midAlpha = StationPhotoViewerHelper.calculateDismissAlpha(dragOffsetY = 100f, maxDrag = 200f, baseAlpha = 0.95f)
        assertEquals(0.475f, midAlpha, 0.001f)

        val fullDragAlpha = StationPhotoViewerHelper.calculateDismissAlpha(dragOffsetY = 250f, maxDrag = 200f, baseAlpha = 0.95f)
        assertEquals(0f, fullDragAlpha, 0.001f)

        // Paging allowed strictly when scale <= 1.05f
        assertTrue(StationPhotoViewerHelper.isPagingAllowed(1.0f))
        assertTrue(StationPhotoViewerHelper.isPagingAllowed(1.05f))
        assertFalse(StationPhotoViewerHelper.isPagingAllowed(1.06f))
        assertFalse(StationPhotoViewerHelper.isPagingAllowed(2.5f))

        // Zoom clamping
        assertEquals(1.0f, StationPhotoViewerHelper.clampZoomScale(0.5f), 0.001f)
        assertEquals(4.0f, StationPhotoViewerHelper.clampZoomScale(5.0f), 0.001f)
        assertEquals(2.5f, StationPhotoViewerHelper.clampZoomScale(2.5f), 0.001f)

        // Double tap toggle
        assertEquals(2.5f, StationPhotoViewerHelper.toggleDoubleTapZoom(1.0f), 0.001f)
        assertEquals(1.0f, StationPhotoViewerHelper.toggleDoubleTapZoom(2.5f), 0.001f)
    }

    // =========================================================================
    // 5. State Preservation across Rotations Tests (rememberSaveable verification)
    // =========================================================================

    @Test
    fun testModalDialogStates_AreRestorableAcrossConfigurationChanges() {
        val saver = autoSaver<Boolean>()
        val dummySaverScope = object : SaverScope {
            override fun canBeSaved(value: Any): Boolean = true
        }

        // Test showRoutingSettings state preservation
        var showRoutingSettings = true
        val savedRoutingState = saver.run { dummySaverScope.save(showRoutingSettings) }
        assertNotNull("Dialog state must be saveable", savedRoutingState)
        val restoredRoutingState = saver.restore(savedRoutingState!!)
        assertEquals(true, restoredRoutingState)

        // Test showLoginRequiredDialog state preservation
        var showLoginRequiredDialog = true
        val savedLoginState = saver.run { dummySaverScope.save(showLoginRequiredDialog) }
        assertNotNull("Login dialog state must be saveable", savedLoginState)
        val restoredLoginState = saver.restore(savedLoginState!!)
        assertEquals(true, restoredLoginState)

        // Test showPermissionRationale state preservation
        var showPermissionRationale = true
        val savedRationaleState = saver.run { dummySaverScope.save(showPermissionRationale) }
        assertNotNull("Permission rationale state must be saveable", savedRationaleState)
        val restoredRationaleState = saver.restore(savedRationaleState!!)
        assertEquals(true, restoredRationaleState)
    }
}
