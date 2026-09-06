package com.evcs.favorites.ui.components

import androidx.compose.ui.graphics.Color
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.telemetry.EvcsTelemetryDataSource
import com.evcs.favorites.domain.StationAccessTokens
import com.evcs.favorites.domain.StationTelemetry
import com.evcs.favorites.data.repository.EvcsTelemetryRepository
import com.evcs.favorites.domain.Station24hStatsCalculator
import com.evcs.favorites.domain.StationRating
import com.evcs.favorites.ui.theme.EmeraldPrimary
import com.evcs.favorites.ui.viewmodel.StationDetailCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
 * Single Comprehensive Verification Test for Phase 03:
 * Refresh Rotation Animation & Interactive Reload Feedback UX.
 *
 * Verifies:
 * 1. Refresh button spec resolution (idle vs active live-syncing states, accessibility descriptions, tints, gating).
 * 2. Continuous rotation angle logic and reset-to-zero when idle.
 * 3. Manual refresh transition: StationDetailUiState transitions to isRefreshing = true on manual reload.
 * 4. Clean reset: isRefreshing resets to false upon completion of telemetry and stats stages.
 * 5. Concurrent reload spam prevention: coordinator debounces/ignores duplicate calls while isRefreshing is active.
 * 6. Resilience on network failure: isRefreshing resets to false even on token/telemetry failure.
 * 7. Resilience on stats timeout: isRefreshing resets to false when stats request times out.
 * 8. Clean dismissal: dismissing the station detail sheet cancels active refresh and resets state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NativeStationDetailRefreshFeedbackTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var storage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var fakeTelemetryRepo: FakeEvcsTelemetryRepository
    private lateinit var coordinator: StationDetailCoordinator

    private val sampleStation = Station(
        id = "station_vinfast_01",
        name = "VinFast - Landmark 81",
        address = "720A Điện Biên Phủ, Vinhomes Tân Cảng, Bình Thạnh, TP.HCM",
        latitude = 10.7946,
        longitude = 106.7222,
        summary = "Mở 24/7 • Sạc siêu nhanh 250kW",
        connectors = "250kW, 60kW",
        depotStatus = "Normal",
        powers = listOf(
            PowerPort(
                typeWatts = 250000L,
                label = "250kW",
                availablePlugs = 2,
                totalPlugs = 4,
                displayString = "250kW: trống 2/4 cổng"
            ),
            PowerPort(
                typeWatts = 60000L,
                label = "60kW",
                availablePlugs = 1,
                totalPlugs = 2,
                displayString = "60kW: trống 1/2 cổng"
            )
        ),
        totalAvailablePlugs = 3,
        totalPlugs = 6
    )

    class FakeEvcsTelemetryRepository(
        sessionManager: SessionManager
    ) : EvcsTelemetryRepository(
        dataSource = EvcsTelemetryDataSource(sessionManager),
        statsCalculator = Station24hStatsCalculator,
        ioDispatcher = Dispatchers.Unconfined
    ) {
        var tokensResult: Result<StationAccessTokens> = Result.success(
            StationAccessTokens(
                chargeToken = "charge_tok_test",
                apiToken = "api_tok_test",
                rating = StationRating(avg = 4.9, count = 42, mine = 5)
            )
        )

        var liveChargingResult: Result<StationTelemetry> = Result.success(
            StationTelemetry(
                busyByKw = mapOf(250 to 2, 60 to 1),
                rawTicker = "<b>Đang sạc ổn định</b>",
                cleanForecast = "Đang sạc ổn định",
                isLocked = false
            )
        )

        var historyResult: Result<List<Pair<Long, Int>>> = Result.success(
            listOf(
                1725400000000L to 3,
                1725403600000L to 4
            )
        )

        var historyDelayMs: Long = 0L

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

        override suspend fun fetch24hHistory(
            stationId: String,
            apiToken: String
        ): Result<List<Pair<Long, Int>>> {
            if (historyDelayMs > 0L) {
                delay(historyDelayMs)
            }
            return historyResult
        }

        override suspend fun sendTelemetryUpdate(
            stationId: String,
            apiToken: String,
            totalBusy: Int
        ): Result<Unit> {
            return Result.success(Unit)
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        storage = InMemorySessionStorage()
        sessionManager = SessionManager(storage)
        fakeTelemetryRepo = FakeEvcsTelemetryRepository(sessionManager)
        coordinator = StationDetailCoordinator(
            coroutineScope = testScope,
            telemetryRepository = fakeTelemetryRepo,
            ioDispatcher = testDispatcher,
            mainDispatcher = testDispatcher,
            statsTimeoutMs = 3000L
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // =========================================================================
    // 1. Refresh Button Specification & Visual State Helper Logic
    // =========================================================================

    @Test
    fun testRefreshButtonSpecResolution_idleAndRefreshingStates() {
        val normalTint = Color(0xFF49454F)
        val activeTint = EmeraldPrimary

        // Idle State: isRefreshing = false
        val idleSpec = NativeStationDetailSheetHelper.resolveRefreshButtonSpec(
            isRefreshing = false,
            normalTint = normalTint,
            refreshingTint = activeTint
        )
        assertFalse("Spec must indicate not refreshing", idleSpec.isRefreshing)
        assertTrue("Button must be enabled when idle to accept user clicks", idleSpec.isEnabled)
        assertEquals("Label must be 'Tải lại' when idle", NativeStationDetailSheetHelper.LABEL_RELOAD, idleSpec.contentDescription)
        assertEquals("Content description must be 'Tải lại'", "Tải lại", idleSpec.contentDescription)
        assertEquals("Tint must be standard onSurfaceVariant color", normalTint, idleSpec.tint)
        assertEquals("Rotation loop duration must be 1000ms", 1000, idleSpec.rotationDurationMs)

        // Active State: isRefreshing = true
        val refreshingSpec = NativeStationDetailSheetHelper.resolveRefreshButtonSpec(
            isRefreshing = true,
            normalTint = normalTint,
            refreshingTint = activeTint
        )
        assertTrue("Spec must indicate refreshing", refreshingSpec.isRefreshing)
        assertFalse("Button must be disabled when refreshing to block multi-tap spam", refreshingSpec.isEnabled)
        assertEquals("Content description must announce 'Đang tải lại'", NativeStationDetailSheetHelper.LABEL_RELOADING, refreshingSpec.contentDescription)
        assertEquals("Content description must be 'Đang tải lại'", "Đang tải lại", refreshingSpec.contentDescription)
        assertEquals("Tint must switch to EmeraldPrimary during live sync", activeTint, refreshingSpec.tint)
        assertEquals("Rotation loop duration must be 1000ms", 1000, refreshingSpec.rotationDurationMs)
    }

    @Test
    fun testRefreshRotationAngleResolutionAndClickGating() {
        // When not refreshing: angle strictly snaps to 0 degrees regardless of transition value
        val idleAngle = NativeStationDetailSheetHelper.resolveRefreshRotationAngle(
            isRefreshing = false,
            animatedAngle = 270f
        )
        assertEquals("Idle state must reset rotation angle to 0 degrees", 0f, idleAngle, 0.001f)

        // When refreshing: angle reflects animated transition value
        val activeAngle = NativeStationDetailSheetHelper.resolveRefreshRotationAngle(
            isRefreshing = true,
            animatedAngle = 270f
        )
        assertEquals("Active refreshing state must follow animated rotation", 270f, activeAngle, 0.001f)

        // Permission to trigger reload
        assertTrue("Refresh should be allowed when not refreshing", NativeStationDetailSheetHelper.shouldAllowRefresh(false))
        assertFalse("Refresh must be blocked when already refreshing", NativeStationDetailSheetHelper.shouldAllowRefresh(true))
    }

    // =========================================================================
    // 2. Coordinator Refresh State Transitions & Clean Reset
    // =========================================================================

    @Test
    fun testCoordinator_manualRefreshTransitionsAndCleanResetUponCompletion() = testScope.runTest {
        // Initial station selection and pipeline completion
        coordinator.selectStationForDetail(sampleStation)
        advanceUntilIdle()

        val initialLoadedState = coordinator.stationDetailState.value
        assertFalse("isRefreshing must be false initially", initialLoadedState.isRefreshing)
        assertFalse("isLoadingTelemetry must be false after initial load", initialLoadedState.isLoadingTelemetry)
        assertFalse("isLoadingStats must be false after initial load", initialLoadedState.isLoadingStats)

        // Step 1: User taps manual refresh button
        val refreshJob = coordinator.refreshStationDetail()
        assertNotNull("Refresh job must be launched", refreshJob)

        // Step 2: Immediate verification of refreshing feedback state
        val activelyRefreshingState = coordinator.stationDetailState.value
        assertTrue("isRefreshing must immediately transition to true", activelyRefreshingState.isRefreshing)
        assertTrue("isLoadingTelemetry must be true during refresh", activelyRefreshingState.isLoadingTelemetry)
        assertTrue("isLoadingStats must be true during refresh", activelyRefreshingState.isLoadingStats)
        assertNull("Error must be cleared on refresh", activelyRefreshingState.error)

        // Step 3: Advance coroutine dispatcher to complete stages
        advanceUntilIdle()

        // Step 4: Verification of clean reset upon completion
        val completedState = coordinator.stationDetailState.value
        assertFalse("isRefreshing must cleanly reset to false", completedState.isRefreshing)
        assertFalse("isLoadingTelemetry must reset to false", completedState.isLoadingTelemetry)
        assertFalse("isLoadingStats must reset to false", completedState.isLoadingStats)
        assertEquals(4.9, completedState.rating?.avg ?: 0.0, 0.001)
        assertEquals("Đang sạc ổn định", completedState.cleanForecast)
    }

    // =========================================================================
    // 3. Concurrent Reload Multi-Tap Prevention (Debouncing / Deduplication)
    // =========================================================================

    @Test
    fun testCoordinator_duplicateReloadIgnoredWhenAlreadyRefreshing() = testScope.runTest {
        // Complete initial selection
        coordinator.selectStationForDetail(sampleStation)
        advanceUntilIdle()

        // Configure telemetry to take some time
        fakeTelemetryRepo.historyDelayMs = 1500L

        // Tap 1: Initial manual reload triggers
        val primaryJob = coordinator.refreshStationDetail()
        assertNotNull("Primary refresh job must be started", primaryJob)
        assertTrue("isRefreshing must be true", coordinator.stationDetailState.value.isRefreshing)

        // Tap 2 & Tap 3: Rapid consecutive taps while refresh is active
        val duplicateJob1 = coordinator.refreshStationDetail()
        val duplicateJob2 = coordinator.refreshStationDetail()

        assertNull("Duplicate reload trigger must be ignored and return null", duplicateJob1)
        assertNull("Subsequent duplicate reload trigger must also be ignored", duplicateJob2)
        assertTrue("Original refresh job must remain active", primaryJob?.isActive == true)

        // Advance until completion
        advanceUntilIdle()

        val finishedState = coordinator.stationDetailState.value
        assertFalse("isRefreshing must be false after completion", finishedState.isRefreshing)

        // Tap 4: Once completed, refresh can be cleanly triggered again
        fakeTelemetryRepo.historyDelayMs = 0L
        val nextJob = coordinator.refreshStationDetail()
        assertNotNull("New refresh job must succeed after previous completes", nextJob)
        assertTrue("isRefreshing transitions back to true", coordinator.stationDetailState.value.isRefreshing)
        advanceUntilIdle()
        assertFalse("isRefreshing returns to false", coordinator.stationDetailState.value.isRefreshing)
    }

    // =========================================================================
    // 4. Coordinator Error & Timeout Clean Reset Verification
    // =========================================================================

    @Test
    fun testCoordinator_resetsIsRefreshingToFalseOnTokenFailureOrException() = testScope.runTest {
        // Initial setup
        coordinator.selectStationForDetail(sampleStation)
        advanceUntilIdle()

        // Simulate network failure on tokens handshake
        fakeTelemetryRepo.tokensResult = Result.failure(RuntimeException("EVCS 503 Service Unavailable"))

        val failedJob = coordinator.refreshStationDetail()
        assertNotNull("Refresh job started", failedJob)
        assertTrue("isRefreshing is initially true", coordinator.stationDetailState.value.isRefreshing)

        advanceUntilIdle()

        val stateAfterFailure = coordinator.stationDetailState.value
        assertFalse("isRefreshing must cleanly reset to false on network failure", stateAfterFailure.isRefreshing)
        assertFalse("isLoadingTelemetry must reset to false on failure", stateAfterFailure.isLoadingTelemetry)
        assertFalse("isLoadingStats must reset to false on failure", stateAfterFailure.isLoadingStats)
        assertEquals("EVCS 503 Service Unavailable", stateAfterFailure.error)
    }

    @Test
    fun testCoordinator_resetsIsRefreshingToFalseOnStatsTimeout() = testScope.runTest {
        coordinator.selectStationForDetail(sampleStation)
        advanceUntilIdle()

        // Simulate stats hanging beyond the 3000ms timeout
        fakeTelemetryRepo.historyDelayMs = 5000L

        coordinator.refreshStationDetail()
        assertTrue("isRefreshing is true during active reload", coordinator.stationDetailState.value.isRefreshing)

        // Advance 3100ms past timeout
        advanceTimeBy(3100L)
        advanceUntilIdle()

        val timedOutState = coordinator.stationDetailState.value
        assertFalse("isRefreshing must cleanly reset to false even when stats timeout", timedOutState.isRefreshing)
        assertFalse("isLoadingStats must reset to false", timedOutState.isLoadingStats)
    }

    @Test
    fun testCoordinator_dismissStationDetailResetsRefreshingAndCancelsActiveJob() = testScope.runTest {
        coordinator.selectStationForDetail(sampleStation)
        advanceUntilIdle()

        fakeTelemetryRepo.historyDelayMs = 2000L
        val refreshJob = coordinator.refreshStationDetail()
        assertNotNull(refreshJob)
        assertTrue(coordinator.stationDetailState.value.isRefreshing)

        // Dismiss sheet while reload is in progress
        coordinator.dismissStationDetail()

        val dismissedState = coordinator.stationDetailState.value
        assertFalse("isRefreshing must be reset to false upon dismissal", dismissedState.isRefreshing)
        assertNull("Station must be cleared on dismissal", dismissedState.station)
        assertNull("activeJob must be null on dismissal", coordinator.activeJob)
        assertTrue("Active coroutine job must be cancelled", refreshJob?.isCancelled == true)
    }
}
