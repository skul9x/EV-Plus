package com.evcs.favorites

import android.content.Context
import android.webkit.WebView
import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.AuthEngine
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.ui.components.AndroidWebViewLifecycleTarget
import com.evcs.favorites.ui.components.StationDetailWebViewHelper
import com.evcs.favorites.ui.components.WebViewLifecycleTarget
import com.evcs.favorites.ui.state.FavoritesUiState
import com.evcs.favorites.ui.viewmodel.FavoritesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
import java.util.concurrent.TimeUnit

/**
 * Single comprehensive test suite for Phase 02: Deterministic WebView Lifecycle & Native Memory Leak Prevention.
 *
 * Verifies:
 * 1. StationDetailWebViewHelper invokes the complete disposal pipeline in strict order:
 *    stopLoading -> loadUrl(about:blank) -> clearHistory -> removeAllViews -> detach from parent -> destroy.
 * 2. Idempotency & Safety: Multiple disposal calls safely no-op without throwing exceptions or crashing.
 * 3. Chromium renderer crash interception: onRenderProcessGone disposes dead views cleanly and returns true.
 * 4. Integration with FavoritesViewModel: selectStationForDetail, dismissStationDetail, removeFavorite, and logout
 *    deterministically manage detail state and signal modal teardown.
 * 5. AndroidWebViewLifecycleTarget platform delegation safety.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StationDetailWebViewLifecycleTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockServer: MockWebServer
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var apiClient: EvcsApiClient
    private lateinit var repository: EvcsRepository
    private lateinit var authEngine: AuthEngine
    private lateinit var viewModel: FavoritesViewModel

    private val sampleStation1 = Station(
        id = "C.BNI0012",
        name = "VinFast - TTTM Dabaco Mart Quế Võ",
        address = "Bắc Ninh",
        latitude = 21.1438,
        longitude = 106.1662,
        summary = "Mở 24/7",
        connectors = "30kW, 20kW",
        depotStatus = "Normal",
        totalAvailablePlugs = 2,
        totalPlugs = 4
    )

    private val sampleStation2 = Station(
        id = "RAB0045",
        name = "Rabbit E-Mobility - Khách Sạn TTC Cần Thơ",
        address = "Cần Thơ",
        latitude = 10.0345,
        longitude = 105.7891,
        summary = "Mở 24/7",
        connectors = "60kW",
        depotStatus = "Normal",
        totalAvailablePlugs = 1,
        totalPlugs = 2
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockServer = MockWebServer()
        mockServer.start()

        StationDetailWebViewHelper.resetForTesting()

        sessionStorage = InMemorySessionStorage()
        sessionManager = SessionManager(sessionStorage)
        sessionManager.saveAuthCookie("test_auth_token_xyz")
        sessionManager.saveSession("test_session_id_xyz", "test_csrf_token")

        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .build()

        val baseUrl = mockServer.url("/").toString().removeSuffix("/")
        apiClient = EvcsApiClient(
            sessionManager = sessionManager,
            client = okHttpClient,
            baseUrl = baseUrl
        )

        repository = EvcsRepository(apiClient = apiClient)
        authEngine = AuthEngine(
            sessionManager = sessionManager,
            client = okHttpClient,
            baseUrl = baseUrl
        )
        viewModel = FavoritesViewModel(
            repository = repository,
            authEngine = authEngine,
            dispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        StationDetailWebViewHelper.resetForTesting()
        mockServer.shutdown()
        Dispatchers.resetMain()
    }

    /**
     * Test double for [WebViewLifecycleTarget] that records call order and invocation counts.
     */
    private class RecordingLifecycleTarget : WebViewLifecycleTarget {
        val calls = mutableListOf<String>()
        var detached = false
        var destroyed = false

        override fun stopLoading() {
            calls.add("stopLoading")
        }

        override fun loadBlankUrl() {
            calls.add("loadUrl(about:blank)")
        }

        override fun clearHistory() {
            calls.add("clearHistory")
        }

        override fun removeAllViews() {
            calls.add("removeAllViews")
        }

        override fun detachFromParent(): Boolean {
            calls.add("detachFromParent")
            detached = true
            return true
        }

        override fun destroy() {
            calls.add("destroy")
            destroyed = true
        }
    }

    // =========================================================================
    // Part 1: Deterministic Pipeline Invocations in Strict Sequence
    // =========================================================================

    @Test
    fun testCleanupWebView_executesAllStepsInStrictOrder() {
        val target = RecordingLifecycleTarget()
        val recordedSteps = mutableListOf<StationDetailWebViewHelper.CleanupStep>()

        StationDetailWebViewHelper.stepObserver = { step ->
            recordedSteps.add(step)
        }

        // Execute cleanup
        val result = StationDetailWebViewHelper.cleanUpWebView(target)

        assertTrue("First cleanup execution must succeed and return true", result)
        assertTrue("Target must be marked as cleaned up", StationDetailWebViewHelper.isCleanedUp(target))
        assertTrue("Target must have been detached from parent hierarchy", target.detached)
        assertTrue("Target native resource must have been destroyed", target.destroyed)

        // Verify exact method sequence
        val expectedMethodCalls = listOf(
            "stopLoading",
            "loadUrl(about:blank)",
            "clearHistory",
            "removeAllViews",
            "detachFromParent",
            "destroy"
        )
        assertEquals(expectedMethodCalls, target.calls)

        // Verify observer enum sequence
        val expectedEnumSteps = listOf(
            StationDetailWebViewHelper.CleanupStep.STOP_LOADING,
            StationDetailWebViewHelper.CleanupStep.LOAD_BLANK_URL,
            StationDetailWebViewHelper.CleanupStep.CLEAR_HISTORY,
            StationDetailWebViewHelper.CleanupStep.REMOVE_ALL_VIEWS,
            StationDetailWebViewHelper.CleanupStep.DETACH_FROM_PARENT,
            StationDetailWebViewHelper.CleanupStep.DESTROY
        )
        assertEquals(expectedEnumSteps, recordedSteps)
    }

    // =========================================================================
    // Part 2: Idempotency & Crash-Safe Exception Handling
    // =========================================================================

    @Test
    fun testCleanupWebView_isStrictlyIdempotentAndExceptionSafe() {
        val target = RecordingLifecycleTarget()

        // First call executes pipeline
        val firstResult = StationDetailWebViewHelper.cleanUpWebView(target)
        assertTrue("First execution should return true", firstResult)
        assertEquals(6, target.calls.size)

        // Second call on identical instance must no-op and return false
        val secondResult = StationDetailWebViewHelper.cleanUpWebView(target)
        assertFalse("Subsequent execution must return false (no-op)", secondResult)
        assertEquals("No additional methods should be invoked on second cleanup call", 6, target.calls.size)

        // Third call must also no-op safely
        val thirdResult = StationDetailWebViewHelper.cleanUpWebView(target)
        assertFalse(thirdResult)
        assertEquals(6, target.calls.size)

        // Null targets must safely return false without NullPointerException
        assertFalse(StationDetailWebViewHelper.cleanUpWebView(null as WebViewLifecycleTarget?))
        assertFalse(StationDetailWebViewHelper.cleanUpWebView(null as WebView?))

        // Fault injection target: throws exception during teardown
        val failingTarget = object : WebViewLifecycleTarget {
            override fun stopLoading() = throw IllegalStateException("Simulated native WebCore crash")
            override fun loadBlankUrl() {}
            override fun clearHistory() {}
            override fun removeAllViews() {}
            override fun detachFromParent() = false
            override fun destroy() {}
        }

        val failingResult = StationDetailWebViewHelper.cleanUpWebView(failingTarget)
        assertFalse("Failing target must return false instead of propagating exception", failingResult)
    }

    // =========================================================================
    // Part 3: Chromium Renderer Crash Interception via onRenderProcessGone
    // =========================================================================

    @Test
    fun testSafeWebViewClient_interceptsRendererCrashAndPerformsDisposal() {
        val recordingTarget = RecordingLifecycleTarget()
        var rendererCrashObserved = false

        // Configure target factory hook so safe client disposes via our recording target
        StationDetailWebViewHelper.targetFactory = { _ -> recordingTarget }

        val safeClient = StationDetailWebViewHelper.createSafeWebViewClient(
            onRendererCrashAction = {
                rendererCrashObserved = true
            }
        )

        // Create a stub WebView instance (handled cleanly by stub jar with isReturnDefaultValues = true)
        val dummyContext = DummyContext()
        val testWebView = try {
            WebView(dummyContext)
        } catch (_: Throwable) {
            null
        }

        // Trigger onRenderProcessGone with null or dummy view
        val handled = safeClient.onRenderProcessGone(testWebView, null)

        assertTrue(
            "onRenderProcessGone MUST return true to prevent Android OS from terminating host app process",
            handled
        )
        assertTrue("Renderer crash action callback must be invoked", rendererCrashObserved)

        if (testWebView != null) {
            assertTrue("WebView instance must be registered as cleaned up", StationDetailWebViewHelper.isCleanedUp(testWebView))
            assertEquals(
                listOf(
                    "stopLoading",
                    "loadUrl(about:blank)",
                    "clearHistory",
                    "removeAllViews",
                    "detachFromParent",
                    "destroy"
                ),
                recordingTarget.calls
            )
        }
    }

    // =========================================================================
    // Part 4: Coordination with FavoritesViewModel Lifecycle & State
    // =========================================================================

    @Test
    fun testFavoritesViewModel_selectionDismissalAndLogoutLifecycleStateCoordination() = runTest(testDispatcher) {
        // 4a. Initial state: no station selected
        assertNull(
            "Initial selectedStationForDetail must be null",
            viewModel.selectedStationForDetail.value
        )

        // Simulate server response and load favorites
        val mockJson = """
        {
          "sync": true,
          "csrf": "test_csrf_token",
          "server": [
            {
              "locationId": "C.BNI0012",
              "name": "VinFast - TTTM Dabaco Mart Quế Võ",
              "address": "Bắc Ninh",
              "connectors": "30kW, 20kW"
            },
            {
              "locationId": "RAB0045",
              "name": "Rabbit E-Mobility - Khách Sạn TTC Cần Thơ",
              "address": "Cần Thơ",
              "connectors": "60kW"
            }
          ]
        }
        """.trimIndent()
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(mockJson))

        val fetchJob = viewModel.fetchFavorites()
        testDispatcher.scheduler.advanceUntilIdle()
        fetchJob.join()

        val loadedState = viewModel.uiState.value as FavoritesUiState.Success
        assertEquals(2, loadedState.stations.size)
        assertNull(loadedState.selectedStationForDetail)

        // 4b. Select station -> opens modal sheet
        viewModel.selectStationForDetail(sampleStation1)
        assertEquals(sampleStation1, viewModel.selectedStationForDetail.value)
        val selectedState1 = viewModel.uiState.value as FavoritesUiState.Success
        assertEquals(sampleStation1, selectedState1.selectedStationForDetail)

        // 4c. Dismiss modal sheet -> triggers teardown and clears state
        viewModel.dismissStationDetail()
        assertNull("Dismissing station detail must clear selectedStationForDetail", viewModel.selectedStationForDetail.value)
        val dismissedState = viewModel.uiState.value as FavoritesUiState.Success
        assertNull("UI state selectedStationForDetail must be null after dismissal", dismissedState.selectedStationForDetail)

        // 4d. Removing active selected station automatically clears selection and dismisses modal
        viewModel.selectStationForDetail(sampleStation1)
        assertEquals(sampleStation1, viewModel.selectedStationForDetail.value)

        viewModel.removeFavorite(sampleStation1.id)
        assertNull(
            "Removing the currently selected station must reset selection to null and close detail view",
            viewModel.selectedStationForDetail.value
        )
        val afterRemoveState = viewModel.uiState.value as FavoritesUiState.Success
        assertNull(afterRemoveState.selectedStationForDetail)
        assertEquals(1, afterRemoveState.stations.size)
        assertEquals(sampleStation2.id, afterRemoveState.stations[0].id)

        // 4e. Logout resets selection state and transitions UI state to LoggedOut
        viewModel.selectStationForDetail(sampleStation2)
        assertEquals(sampleStation2, viewModel.selectedStationForDetail.value)

        viewModel.logout()
        assertNull(
            "Logout must immediately clear active station detail selection",
            viewModel.selectedStationForDetail.value
        )
        assertTrue(
            "UI state must transition to LoggedOut upon logout",
            viewModel.uiState.value is FavoritesUiState.LoggedOut
        )
    }

    // =========================================================================
    // Part 5: AndroidWebViewLifecycleTarget Platform Delegation Safety
    // =========================================================================

    @Test
    fun testAndroidWebViewLifecycleTarget_delegationCompletesWithoutExceptions() {
        val dummyContext = DummyContext()
        val testWebView = try {
            WebView(dummyContext)
        } catch (_: Throwable) {
            null
        }

        if (testWebView != null) {
            val target = AndroidWebViewLifecycleTarget(testWebView)
            // Execute each target method to verify no stub or null pointer crashes occur
            target.stopLoading()
            target.loadBlankUrl()
            target.clearHistory()
            target.removeAllViews()
            target.detachFromParent()
            target.destroy()
        }
    }

    private class DummyContext : android.content.ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }
}
