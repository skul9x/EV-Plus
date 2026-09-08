package com.evcs.favorites.hardening

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.AuthEngine
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.focus.AlternativeStationRecommendation
import com.evcs.favorites.focus.FocusModeFloatingViewManager
import com.evcs.favorites.navigation.MapNavigator
import com.evcs.favorites.ui.components.FavoritesProfileHeaderHelper
import com.evcs.favorites.ui.components.NativeStationDetailSheetHelper
import com.evcs.favorites.ui.screens.LoginScreenHelper
import com.evcs.favorites.ui.state.FavoritesUiState
import com.evcs.favorites.ui.viewmodel.FavoritesViewModel
import com.evcs.favorites.util.DebounceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single Comprehensive Verification Test for Phase 01: Action Debounce & Throttling Engine.
 *
 * Validates:
 * 1. DebounceHelper cooldown throttling and atomic execution.
 * 2. DebounceHelper in-flight mutual exclusion locking across coroutines.
 * 3. Thread-safe concurrency behavior under high multi-threaded contention.
 * 4. MapNavigator 1-Tap navigation intent dispatch throttling within 1000ms cooldown window.
 * 5. NativeStationDetailSheetHelper Focus Mode debouncing preventing duplicate service starts.
 * 6. FocusModeFloatingViewManager reroute button debounce and alternative station handling.
 * 7. FavoritesViewModel OTP verification atomic in-flight guard preventing race conditions.
 * 8. LoginScreenHelper UI auto-submit and button enabled state guards.
 * 9. FavoritesProfileHeaderHelper Google Sign-In button disabled state during authentication.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActionDebounceAndThrottlingTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockServer: MockWebServer
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var baseUrl: String
    private lateinit var apiClient: EvcsApiClient
    private lateinit var repository: EvcsRepository
    private lateinit var authEngine: AuthEngine

    private val sampleStation = Station(
        id = "TEST_STATION_01",
        name = "VinFast Test Station",
        address = "123 Hanoi Street",
        latitude = 21.0285,
        longitude = 105.8542,
        summary = "Test Station",
        connectors = "CCS2",
        depotStatus = "Normal",
        powers = listOf(PowerPort(typeWatts = 60000L, label = "60kW", availablePlugs = 2, totalPlugs = 4, displayString = "60kW: trống 2/4"))
    )

    private val sampleAlternative = AlternativeStationRecommendation(
        station = sampleStation,
        distanceKm = 0.5,
        matchingPowerWatts = 60000L,
        availableDcSlots = 2,
        totalDcSlots = 4
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockServer = MockWebServer()
        mockServer.start()

        sessionStorage = InMemorySessionStorage()
        sessionManager = SessionManager(sessionStorage)

        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        baseUrl = mockServer.url("/").toString().removeSuffix("/")

        apiClient = EvcsApiClient(
            sessionManager = sessionManager,
            client = okHttpClient,
            baseUrl = baseUrl
        )

        repository = EvcsRepository(apiClient)
        authEngine = AuthEngine(sessionManager, okHttpClient, baseUrl)
    }

    @After
    fun tearDown() {
        MapNavigator.resetDebounceForTesting()
        NativeStationDetailSheetHelper.resetFocusDebounceForTesting()
        mockServer.shutdown()
        Dispatchers.resetMain()
    }

    // =========================================================================
    // 1. DebounceHelper Cooldown & Deterministic Clock Tests
    // =========================================================================

    @Test
    fun testDebounceHelper_cooldownAndThrottling() {
        var mockTime = 1000L
        val debounce = DebounceHelper(intervalMs = 1000L, clock = { mockTime })

        var executionCount = 0

        // 1. First execution must succeed immediately
        val firstAllowed = debounce.runIfAllowed { executionCount++ }
        assertTrue("First action must be allowed", firstAllowed)
        assertEquals(1, executionCount)

        // 2. Rapid repeated calls within cooldown period (< 1000ms) must be dropped
        mockTime = 1200L
        val secondAllowed = debounce.runIfAllowed { executionCount++ }
        assertFalse("Action within 200ms must be dropped", secondAllowed)
        assertEquals(1, executionCount)

        mockTime = 1999L
        val thirdAllowed = debounce.runIfAllowed { executionCount++ }
        assertFalse("Action within 999ms must be dropped", thirdAllowed)
        assertEquals(1, executionCount)

        // 3. Execution after cooldown (>= 1000ms elapsed) must succeed
        mockTime = 2000L
        val fourthAllowed = debounce.runIfAllowed { executionCount++ }
        assertTrue("Action after 1000ms cooldown must be allowed", fourthAllowed)
        assertEquals(2, executionCount)

        // 4. Reset allows immediate execution even without advancing time
        debounce.reset()
        val afterResetAllowed = debounce.runIfAllowed { executionCount++ }
        assertTrue("Action immediately after reset must be allowed", afterResetAllowed)
        assertEquals(3, executionCount)
    }

    // =========================================================================
    // 2. DebounceHelper In-Flight Mutual Exclusion Lock Tests
    // =========================================================================

    @Test
    fun testDebounceHelper_withInFlightLock() = runTest(testDispatcher) {
        val debounce = DebounceHelper()
        assertFalse("Should not be in flight initially", debounce.inFlight)

        var blockCompleted = false

        // Launch long-running task wrapped in withInFlightLock
        val job1 = launch {
            val result = debounce.withInFlightLock {
                delay(500)
                blockCompleted = true
                "RESULT_SUCCESS"
            }
            assertEquals("RESULT_SUCCESS", result)
        }

        testScheduler.advanceTimeBy(100)
        assertTrue("DebounceHelper should report in-flight while job is running", debounce.inFlight)

        // Concurrent invocation while job1 is in-flight must return null and be dropped
        var duplicateExecuted = false
        val job2 = async {
            debounce.withInFlightLock {
                duplicateExecuted = true
                "DUPLICATE"
            }
        }

        val job2Result = job2.await()
        assertNull("Duplicate concurrent invocation must return null", job2Result)
        assertFalse("Duplicate block must not have executed", duplicateExecuted)

        // Advance until job1 completes
        testScheduler.advanceTimeBy(500)
        job1.join()

        assertTrue(blockCompleted)
        assertFalse("Should not be in flight after completion", debounce.inFlight)

        // Subsequent call after completion succeeds
        val postResult = debounce.withInFlightLock { "SUBSEQUENT_SUCCESS" }
        assertEquals("SUBSEQUENT_SUCCESS", postResult)
    }

    // =========================================================================
    // 3. DebounceHelper High-Contention Multi-Thread Safety
    // =========================================================================

    @Test
    fun testDebounceHelper_threadSafeConcurrency() {
        val debounce = DebounceHelper(intervalMs = 1000L)
        val threadCount = 30
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val successfulRuns = AtomicInteger(0)

        for (i in 0 until threadCount) {
            executor.submit {
                try {
                    startLatch.await()
                    if (debounce.runIfAllowed { }) {
                        successfulRuns.incrementAndGet()
                    }
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        // Fire all threads simultaneously
        startLatch.countDown()
        assertTrue("Threads must complete execution", doneLatch.await(5, TimeUnit.SECONDS))
        executor.shutdown()

        // Exactly one thread should succeed during the immediate window
        assertEquals("Strictly one thread must be allowed during concurrent contention", 1, successfulRuns.get())
    }

    // =========================================================================
    // 4. MapNavigator Navigation Intent Throttling Tests
    // =========================================================================

    @Test
    fun testMapNavigator_navigationIntentThrottledWithinCooldown() {
        var testTime = 10_000L
        MapNavigator.setDebounceHelperForTesting(DebounceHelper(intervalMs = 1000L, clock = { testTime }))

        val dummyContext = DummyContext()
        var launchCount = 0

        // 1. Initial 1-Tap navigation click -> dispatches intent
        val firstResult = MapNavigator.navigate(
            context = dummyContext,
            latitude = sampleStation.latitude,
            longitude = sampleStation.longitude,
            stationName = sampleStation.name,
            intentLauncher = { launchCount++ }
        )
        assertTrue("First navigation intent should succeed", firstResult)
        assertEquals(1, launchCount)

        // 2. Rapid repeated click within 300ms -> dropped
        testTime += 300L
        val secondResult = MapNavigator.navigate(
            context = dummyContext,
            latitude = sampleStation.latitude,
            longitude = sampleStation.longitude,
            stationName = sampleStation.name,
            intentLauncher = { launchCount++ }
        )
        assertFalse("Rapid navigation intent within cooldown must be throttled", secondResult)
        assertEquals("Intent launcher must not have been invoked", 1, launchCount)

        // 3. Click after cooldown (1000ms elapsed) -> succeeds
        testTime += 1000L
        val thirdResult = MapNavigator.navigate(
            context = dummyContext,
            latitude = sampleStation.latitude,
            longitude = sampleStation.longitude,
            stationName = sampleStation.name,
            intentLauncher = { launchCount++ }
        )
        assertTrue("Navigation intent after cooldown must succeed", thirdResult)
        assertEquals(2, launchCount)
    }

    // =========================================================================
    // 5. NativeStationDetailSheetHelper Focus Mode Debounce Tests
    // =========================================================================

    @Test
    fun testNativeStationDetailSheetHelper_focusModeDebouncing() {
        var testTime = 20_000L
        NativeStationDetailSheetHelper.setFocusDebounceHelperForTesting(
            DebounceHelper(intervalMs = 1000L, clock = { testTime })
        )
        MapNavigator.setDebounceHelperForTesting(
            DebounceHelper(intervalMs = 1000L, clock = { testTime })
        )

        val dummyContext = DummyContext()
        var launchCount = 0

        // 1. First Focus Mode launch -> starts service & navigation
        val firstCall = NativeStationDetailSheetHelper.startFocusMode(
            context = dummyContext,
            station = sampleStation,
            intentLauncher = { launchCount++ }
        )
        assertTrue("First startFocusMode must be permitted", firstCall)
        assertEquals(1, launchCount)

        // 2. Rapid spam click within 400ms -> dropped
        testTime += 400L
        val secondCall = NativeStationDetailSheetHelper.startFocusMode(
            context = dummyContext,
            station = sampleStation,
            intentLauncher = { launchCount++ }
        )
        assertFalse("Rapid startFocusMode must be dropped to prevent duplicate services", secondCall)
        assertEquals(1, launchCount)

        // 3. After cooldown (1000ms elapsed) -> allowed
        testTime += 1000L
        val thirdCall = NativeStationDetailSheetHelper.startFocusMode(
            context = dummyContext,
            station = sampleStation,
            intentLauncher = { launchCount++ }
        )
        assertTrue("startFocusMode after cooldown must succeed", thirdCall)
        assertEquals(2, launchCount)
    }

    // =========================================================================
    // 6. FocusModeFloatingViewManager Reroute Button Debounce Tests
    // =========================================================================

    @Test
    fun testFocusModeFloatingViewManager_rerouteDebounced() {
        val dummyContext = DummyContext()
        var rerouteCount = 0

        val manager = FocusModeFloatingViewManager(
            context = dummyContext,
            windowManager = null,
            onDismiss = {},
            onReroute = { rerouteCount++ }
        )

        var testTime = 50_000L
        manager.setRerouteDebounceHelperForTesting(
            DebounceHelper(intervalMs = 1000L, clock = { testTime })
        )

        // Null alternative station returns false
        manager.setAlternativeStationForTesting(null)
        assertFalse("triggerReroute without alternative station should return false", manager.triggerReroute())
        assertEquals(0, rerouteCount)

        // Set alternative station and test debounce
        manager.setAlternativeStationForTesting(sampleAlternative)

        // First click -> executes reroute
        val firstAllowed = manager.triggerReroute()
        assertTrue("First reroute trigger should succeed", firstAllowed)
        assertEquals(1, rerouteCount)

        // Rapid click within 200ms -> dropped
        testTime += 200L
        val secondAllowed = manager.triggerReroute()
        assertFalse("Reroute trigger within cooldown must be throttled", secondAllowed)
        assertEquals(1, rerouteCount)

        // Click after 1000ms -> allowed
        testTime += 1000L
        val thirdAllowed = manager.triggerReroute()
        assertTrue("Reroute trigger after cooldown must succeed", thirdAllowed)
        assertEquals(2, rerouteCount)
    }

    // =========================================================================
    // 7. FavoritesViewModel OTP Verification Race Condition Prevention
    // =========================================================================

    @Test
    fun testFavoritesViewModel_verifyOtpConcurrentRaceConditionGuard() = runTest(testDispatcher) {
        sessionManager.csrfToken = "test_csrf_token"

        // Enqueue Mock OTP verification response & favorites response
        val otpResponse = """{"ok":true}"""
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Set-Cookie", "evcs=jwt_test_cookie; Max-Age=31536000; Path=/")
                .setBody(otpResponse)
        )
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"quota":10,"favorites":[]}"""))

        val viewModel = FavoritesViewModel(
            repository = repository,
            authEngine = authEngine,
            locationService = null,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        assertFalse("isOtpVerificationInFlight should be false initially", viewModel.isOtpVerificationInFlight)

        // 1. Trigger initial OTP verification
        val job1 = viewModel.verifyOtp("123456", "user@example.com")
        assertTrue("isOtpVerificationInFlight must be true while verifying", viewModel.isOtpVerificationInFlight)
        assertTrue(viewModel.uiState.value is FavoritesUiState.VerifyingOtp)

        // 2. Concurrent invocation before job1 finishes -> must be discarded immediately
        val job2 = viewModel.verifyOtp("123456", "user@example.com")
        assertTrue("job2 should be completed/discarded immediately", job2.isCompleted)

        // Finish network requests
        testScheduler.advanceUntilIdle()
        job1.join()

        assertFalse("isOtpVerificationInFlight must be reset to false after completion", viewModel.isOtpVerificationInFlight)
        assertEquals("Only 2 requests (OTP verify + fetch favorites) should have been received by server", 2, mockServer.requestCount)
    }

    // =========================================================================
    // 8. LoginScreenHelper UI Auto-Submit and Button Guards
    // =========================================================================

    @Test
    fun testLoginScreenHelper_otpSubmitGuards() {
        // When not verifying and OTP length is 6 -> Enabled and Auto-Submit allowed
        assertTrue(LoginScreenHelper.isOtpSubmitEnabled(otpLength = 6, isVerifying = false))
        assertTrue(LoginScreenHelper.shouldAutoSubmit(otpLength = 6, isVerifying = false))

        // When verifying (in-flight) -> Disabled and Auto-Submit suppressed
        assertFalse(LoginScreenHelper.isOtpSubmitEnabled(otpLength = 6, isVerifying = true))
        assertFalse(LoginScreenHelper.shouldAutoSubmit(otpLength = 6, isVerifying = true))

        // When OTP length < 6 -> Disabled
        assertFalse(LoginScreenHelper.isOtpSubmitEnabled(otpLength = 5, isVerifying = false))
        assertFalse(LoginScreenHelper.shouldAutoSubmit(otpLength = 5, isVerifying = false))
        assertFalse(LoginScreenHelper.isOtpSubmitEnabled(otpLength = 0, isVerifying = false))
    }

    // =========================================================================
    // 9. FavoritesProfileHeaderHelper Google Sign-In Guard Tests
    // =========================================================================

    @Test
    fun testFavoritesProfileHeaderHelper_googleSignInGuard() {
        // When authentication is in-flight -> Button disabled to prevent duplicate credentials calls
        assertFalse(FavoritesProfileHeaderHelper.isSignInButtonEnabled(isSigningIn = true))

        // When idle -> Button enabled
        assertTrue(FavoritesProfileHeaderHelper.isSignInButtonEnabled(isSigningIn = false))
    }

    // =========================================================================
    // Dummy Context for Unit Testing Intent Dispatcher
    // =========================================================================

    private class DummyContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun startActivity(intent: Intent?) {}
        override fun startService(service: Intent?): android.content.ComponentName? = null
        override fun startForegroundService(service: Intent?): android.content.ComponentName? = null
    }
}
