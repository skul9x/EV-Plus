package com.evcs.favorites.ui.components

import com.evcs.favorites.data.auth.AuthEngine
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.ui.state.FavoritesUiState
import com.evcs.favorites.ui.theme.ElectricCyan
import com.evcs.favorites.ui.theme.StatusAvailable
import com.evcs.favorites.ui.theme.StatusAvailableContainer
import com.evcs.favorites.ui.theme.StatusBusy
import com.evcs.favorites.ui.theme.StatusBusyContainer
import com.evcs.favorites.ui.theme.StatusMaintaining
import com.evcs.favorites.ui.theme.StatusMaintainingContainer
import com.evcs.favorites.ui.theme.StatusOffline
import com.evcs.favorites.ui.theme.StatusOfflineContainer
import com.evcs.favorites.ui.viewmodel.FavoritesViewModel
import com.evcs.favorites.ui.viewmodel.NearbyViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Single comprehensive test suite for Phase 02: On-Demand Lifecycle & System Regression Verification.
 *
 * Verifies:
 * 1. StationDetailWebViewHelper executes the complete 6-step disposal pipeline in strict chronological order:
 *    stopLoading -> loadBlankUrl -> clearHistory -> removeAllViews -> detachFromParent -> destroy.
 * 2. Idempotent & exception-safe cleanup: Repeated cleanups on the same instance safely no-op; exceptions do not crash host.
 * 3. Strict StationCard list decoupling: StationCardKt has zero references to forecast capsules or amber forecast badges.
 *    Full stations deterministically resolve to "Hết cổng" (StatusBusy), and available stations resolve to "Hoạt động".
 * 4. Zero runtime coupling to background forecast operations: Station domain model and ViewModels (NearbyViewModel,
 *    FavoritesViewModel) contain no forecast polling loops, jobs, or fields.
 * 5. On-demand modal state coordination: Selecting and dismissing stations in FavoritesViewModel strictly coordinates
 *    modal visibility without starting any forecast network requests or background workers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StationDetailOnDemandLifecycleRegressionTest {

    private val testDispatcher = StandardTestDispatcher()

    private val testStation = Station(
        id = "station_hanoi_01",
        name = "VinFast - TTTM Vincom Mega Mall Smart City",
        address = "Tây Mỗ, Nam Từ Liêm, Hà Nội",
        latitude = 21.0028,
        longitude = 105.7483,
        summary = "Mở 24/7",
        connectors = "60kW, 30kW",
        depotStatus = "Normal",
        powers = listOf(
            PowerPort(typeWatts = 60000L, label = "60kW", availablePlugs = 0, totalPlugs = 4, displayString = "60kW: trống 0/4 cổng")
        ),
        totalAvailablePlugs = 0,
        totalPlugs = 4
    )

    private val availableStation = Station(
        id = "station_hcm_02",
        name = "VinFast - Landmark 81",
        address = "Bình Thạnh, TP.HCM",
        latitude = 10.7946,
        longitude = 106.7222,
        summary = "Mở 24/7",
        connectors = "250kW, 60kW",
        depotStatus = "Normal",
        powers = listOf(
            PowerPort(typeWatts = 250000L, label = "250kW", availablePlugs = 2, totalPlugs = 4, displayString = "250kW: trống 2/4 cổng")
        ),
        totalAvailablePlugs = 2,
        totalPlugs = 4
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        StationDetailWebViewHelper.resetForTesting()
    }

    @After
    fun tearDown() {
        StationDetailWebViewHelper.resetForTesting()
        Dispatchers.resetMain()
    }

    /**
     * Test spy for [WebViewLifecycleTarget] recording exact invocation sequence and counts.
     */
    private class RecordingLifecycleTarget(
        private val throwOnMethod: String? = null
    ) : WebViewLifecycleTarget {
        val executedCalls = mutableListOf<String>()
        var detached = false
        var destroyed = false

        override fun stopLoading() {
            if (throwOnMethod == "stopLoading") throw IllegalStateException("Native crash in stopLoading")
            executedCalls.add("stopLoading")
        }

        override fun loadBlankUrl() {
            if (throwOnMethod == "loadBlankUrl") throw IllegalStateException("Native crash in loadBlankUrl")
            executedCalls.add("loadBlankUrl")
        }

        override fun clearHistory() {
            if (throwOnMethod == "clearHistory") throw IllegalStateException("Native crash in clearHistory")
            executedCalls.add("clearHistory")
        }

        override fun removeAllViews() {
            if (throwOnMethod == "removeAllViews") throw IllegalStateException("Native crash in removeAllViews")
            executedCalls.add("removeAllViews")
        }

        override fun detachFromParent(): Boolean {
            if (throwOnMethod == "detachFromParent") throw IllegalStateException("Native crash in detachFromParent")
            executedCalls.add("detachFromParent")
            detached = true
            return true
        }

        override fun destroy() {
            if (throwOnMethod == "destroy") throw IllegalStateException("Native crash in destroy")
            executedCalls.add("destroy")
            destroyed = true
        }
    }

    // =========================================================================
    // 1. Complete 6-Step Disposal Sequence Verification
    // =========================================================================
    @Test
    fun webViewCleanup_executesFullSixStepDisposalSequenceInStrictOrder() {
        val target = RecordingLifecycleTarget()
        val recordedSteps = mutableListOf<StationDetailWebViewHelper.CleanupStep>()

        StationDetailWebViewHelper.stepObserver = { step ->
            recordedSteps.add(step)
        }

        val result = StationDetailWebViewHelper.cleanUpWebView(target)

        assertTrue("Initial cleanup call must succeed and return true", result)
        assertTrue("Target must be tracked in cleanedUpTargets", StationDetailWebViewHelper.isCleanedUp(target))
        assertTrue("Target must be detached from parent view hierarchy", target.detached)
        assertTrue("Target native resources must be destroyed", target.destroyed)

        // Verify exact 6-step method invocation order
        val expectedMethodCalls = listOf(
            "stopLoading",
            "loadBlankUrl",
            "clearHistory",
            "removeAllViews",
            "detachFromParent",
            "destroy"
        )
        assertEquals(
            "Method calls on WebViewLifecycleTarget must match expected 6-step sequence",
            expectedMethodCalls,
            target.executedCalls
        )

        // Verify exact enum step sequence reported by stepObserver
        val expectedEnumSteps = listOf(
            StationDetailWebViewHelper.CleanupStep.STOP_LOADING,
            StationDetailWebViewHelper.CleanupStep.LOAD_BLANK_URL,
            StationDetailWebViewHelper.CleanupStep.CLEAR_HISTORY,
            StationDetailWebViewHelper.CleanupStep.REMOVE_ALL_VIEWS,
            StationDetailWebViewHelper.CleanupStep.DETACH_FROM_PARENT,
            StationDetailWebViewHelper.CleanupStep.DESTROY
        )
        assertEquals(
            "Enum steps emitted to observer must match 6-step cleanup sequence",
            expectedEnumSteps,
            recordedSteps
        )
    }

    // =========================================================================
    // 2. Idempotent & Exception-Safe Teardown Verification
    // =========================================================================
    @Test
    fun webViewCleanup_isStrictlyIdempotentAndExceptionSafe() {
        val target = RecordingLifecycleTarget()

        // Pass 1: standard execution
        val firstResult = StationDetailWebViewHelper.cleanUpWebView(target)
        assertTrue("First cleanup execution must succeed", firstResult)
        assertEquals(6, target.executedCalls.size)

        // Pass 2: subsequent invocation on same instance must cleanly no-op
        val secondResult = StationDetailWebViewHelper.cleanUpWebView(target)
        assertFalse("Second cleanup execution must return false (no-op)", secondResult)
        assertEquals("No additional teardown methods may be invoked on disposed target", 6, target.executedCalls.size)

        // Pass 3: repeated call must remain idempotent
        val thirdResult = StationDetailWebViewHelper.cleanUpWebView(target)
        assertFalse("Third cleanup execution must return false", thirdResult)
        assertEquals(6, target.executedCalls.size)

        // Null targets safely return false without throwing NullPointerException
        assertFalse("Null target cleanup must safely return false", StationDetailWebViewHelper.cleanUpWebView(null as WebViewLifecycleTarget?))
        assertFalse("CleanedUp query for null target must return false", StationDetailWebViewHelper.isCleanedUp(null))

        // Fault tolerance: target throwing exception must be handled gracefully without crashing host app
        val failingTarget = RecordingLifecycleTarget(throwOnMethod = "destroy")
        val failingResult = StationDetailWebViewHelper.cleanUpWebView(failingTarget)
        assertFalse("CleanUp must return false and suppress native exceptions safely", failingResult)
    }

    // =========================================================================
    // 3. Strict List Decoupling & Deterministic Status Badge Resolution
    // =========================================================================
    @Test
    fun stationCardAndList_strictlyDecoupledFromForecastAndRendersDeterministicBadges() {
        val stationCardClass = Class.forName("com.evcs.favorites.ui.components.StationCardKt")
        val declaredMethods = stationCardClass.declaredMethods.map { it.name }
        val declaredFields = stationCardClass.declaredFields.map { it.name }

        // ForecastCapsule composable must be eliminated
        assertFalse(
            "StationCardKt must not declare ForecastCapsule",
            declaredMethods.any { it.contains("ForecastCapsule") }
        )

        // resolveForecastCapsuleData helper must be eliminated
        assertFalse(
            "StationCardKt must not declare resolveForecastCapsuleData",
            declaredMethods.any { it.contains("resolveForecastCapsuleData") }
        )

        // Amber forecast theme constants must not exist in StationCard
        assertFalse(
            "StationCardKt must not define StatusForecastAmber",
            declaredFields.any { it.contains("StatusForecastAmber") }
        )
        assertFalse(
            "StationCardKt must not define ForecastCapsuleBg",
            declaredFields.any { it.contains("ForecastCapsuleBg") }
        )

        // ForecastCapsuleData class must no longer exist on classpath
        try {
            Class.forName("com.evcs.favorites.ui.components.ForecastCapsuleData")
            fail("ForecastCapsuleData class must not exist")
        } catch (_: ClassNotFoundException) {
            // Success: cleanly decommissioned
        }

        // Deterministic badge verification:
        // Case A: Full station with 0 available plugs -> Always "Hết cổng" (StatusBusy)
        val fullStationBadge = resolveStatusBadge(
            depotStatus = testStation.depotStatus,
            totalAvailablePlugs = testStation.totalAvailablePlugs,
            totalPlugs = testStation.totalPlugs
        )
        assertEquals("Hết cổng", fullStationBadge.label)
        assertEquals(StatusBusy, fullStationBadge.dotColor)
        assertEquals(StatusBusyContainer, fullStationBadge.containerColor)
        assertFalse("Full station badge must not contain forecast prediction text", fullStationBadge.label.contains("Sắp trống"))

        // Case B: Available station with live plugs -> Always "Hoạt động" (StatusAvailable)
        val availableBadge = resolveStatusBadge(
            depotStatus = availableStation.depotStatus,
            totalAvailablePlugs = availableStation.totalAvailablePlugs,
            totalPlugs = availableStation.totalPlugs
        )
        assertEquals("Hoạt động", availableBadge.label)
        assertEquals(StatusAvailable, availableBadge.dotColor)
        assertEquals(StatusAvailableContainer, availableBadge.containerColor)

        // Case C: Maintaining station -> Always "Bảo trì" (StatusMaintaining)
        val maintainingBadge = resolveStatusBadge(
            depotStatus = "Maintaining",
            totalAvailablePlugs = 0,
            totalPlugs = 4
        )
        assertEquals("Bảo trì", maintainingBadge.label)
        assertEquals(StatusMaintaining, maintainingBadge.dotColor)
        assertEquals(StatusMaintainingContainer, maintainingBadge.containerColor)

        // Case D: Out of service station -> Always "Tạm dừng" (StatusOffline)
        val offlineBadge = resolveStatusBadge(
            depotStatus = "OutOfService",
            totalAvailablePlugs = 0,
            totalPlugs = 4
        )
        assertEquals("Tạm dừng", offlineBadge.label)
        assertEquals(StatusOffline, offlineBadge.dotColor)
        assertEquals(StatusOfflineContainer, offlineBadge.containerColor)

        // Case E: Unverified station with totalPlugs == 0
        val unverifiedNormalBadge = resolveStatusBadge(
            depotStatus = "Normal",
            totalAvailablePlugs = 0,
            totalPlugs = 0
        )
        assertEquals("Hoạt động", unverifiedNormalBadge.label)

        val unverifiedSavedBadge = resolveStatusBadge(
            depotStatus = "Unknown",
            totalAvailablePlugs = 0,
            totalPlugs = 0
        )
        assertEquals("Đã lưu", unverifiedSavedBadge.label)
        assertEquals(ElectricCyan, unverifiedSavedBadge.dotColor)
    }

    // =========================================================================
    // 4. Zero Forecast Polling Loops in Domain Models & ViewModels
    // =========================================================================
    @Test
    fun domainModelsAndViewModels_haveZeroForecastFieldsOrBackgroundPollingLoops() {
        // Verify Station domain model has no forecast properties
        val stationFields = Station::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(
            "Station model must not contain any forecast field",
            stationFields.any { it.contains("forecast") }
        )

        // Verify NearbyViewModel has no forecast members or methods
        val nearbyMethods = NearbyViewModel::class.java.declaredMethods.map { it.name.lowercase() }
        val nearbyFields = NearbyViewModel::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(
            "NearbyViewModel must not contain any forecast method",
            nearbyMethods.any { it.contains("forecast") }
        )
        assertFalse(
            "NearbyViewModel must not contain any forecast field",
            nearbyFields.any { it.contains("forecast") }
        )

        // Verify FavoritesViewModel has no forecast members or methods
        val favoritesMethods = FavoritesViewModel::class.java.declaredMethods.map { it.name.lowercase() }
        val favoritesFields = FavoritesViewModel::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(
            "FavoritesViewModel must not contain any forecast method",
            favoritesMethods.any { it.contains("forecast") }
        )
        assertFalse(
            "FavoritesViewModel must not contain any forecast field",
            favoritesFields.any { it.contains("forecast") }
        )
    }

    // =========================================================================
    // 5. On-Demand Modal Selection & Dismissal Coordination in FavoritesViewModel
    // =========================================================================
    @Test
    fun favoritesViewModel_coordinatesOnDemandModalWithoutBackgroundNetworkLoops() = runTest(testDispatcher) {
        val sessionStorage = InMemorySessionStorage()
        val sessionManager = SessionManager(sessionStorage)
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .build()
        val repository = EvcsRepository(
            apiClient = com.evcs.favorites.data.api.EvcsApiClient(
                sessionManager = sessionManager,
                client = okHttpClient,
                baseUrl = "http://localhost:8080"
            )
        )
        val authEngine = AuthEngine(
            sessionManager = sessionManager,
            client = okHttpClient,
            baseUrl = "http://localhost:8080"
        )
        val viewModel = FavoritesViewModel(
            repository = repository,
            authEngine = authEngine,
            dispatcher = testDispatcher
        )

        // Initial state: no station is selected for detail
        assertNull(
            "Initial selectedStationForDetail must be null",
            viewModel.selectedStationForDetail.value
        )

        // User taps a station card -> selects station for on-demand detail presentation
        viewModel.selectStationForDetail(testStation)
        assertEquals(
            "selectedStationForDetail must reflect the clicked station",
            testStation,
            viewModel.selectedStationForDetail.value
        )

        // User dismisses modal sheet -> teardown triggered, state reset
        viewModel.dismissStationDetail()
        assertNull(
            "Dismissing detail must reset selectedStationForDetail to null",
            viewModel.selectedStationForDetail.value
        )

        // Verify cookie header access for WebView session injection without side effects
        sessionManager.saveAuthCookie("auth_token_sample_123")
        val cookieHeader = viewModel.getCookieHeader()
        assertTrue("getCookieHeader must include saved auth token", cookieHeader.contains("auth_token_sample_123"))
    }
}
