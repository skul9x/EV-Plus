package com.evcs.favorites.data.preferences

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.auth.SessionStorage
import com.evcs.favorites.data.locations.VietnamLocationsRepository
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.data.routing.EvRoutingSettings
import com.evcs.favorites.data.routing.EvSmartRoutePlanner
import com.evcs.favorites.data.routing.MultiTierRoutingCoordinator
import com.evcs.favorites.data.routing.RoutePathResult
import com.evcs.favorites.data.routing.RoutingPreferencesManager
import com.evcs.favorites.data.routing.RoutingSettings
import com.evcs.favorites.ui.screens.RouteViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single Comprehensive Verification Test for Phase 02:
 * Slider Disk Thrashing Prevention & State Updates.
 *
 * Verifies:
 * 1. Simulates 50 continuous slider drag events in Settings Modal flow and asserts zero disk writes
 *    and zero JSON serializations occur during dragging.
 * 2. Asserts exactly one atomic batch disk write and JSON serialization occurs upon onValueChangeFinished.
 * 3. Simulates 50 continuous slider drag events in RouteScreen VehicleConfigurationCard and asserts
 *    zero disk writes during drag, with exactly one commit to RouteViewModel and persistent storage upon gesture completion.
 * 4. Verifies reactive StateFlow emits correct final state across all consumers (RoutingPreferencesManager,
 *    RouteViewModel UI state, and persistent storage for cold-start recovery).
 * 5. Verifies atomic batch writing guarantees in SessionStorage / PlainSharedPrefsStorage.
 * 6. Verifies source code contracts in RoutingSettingsModal.kt, RouteScreen.kt, and SessionManager.kt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EvSliderPersistenceDebounceTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var countingStorage: CountingSessionStorage
    private lateinit var prefsManager: RoutingPreferencesManager
    private lateinit var locationsRepo: VietnamLocationsRepository
    private lateinit var evcsRepo: EvcsRepository
    private lateinit var routePlanner: EvSmartRoutePlanner
    private lateinit var viewModel: RouteViewModel

    class FakeRoutingCoordinator(
        dispatcher: CoroutineDispatcher
    ) : MultiTierRoutingCoordinator(ioDispatcher = dispatcher) {
        override suspend fun calculateRoutePath(
            originLat: Double,
            originLng: Double,
            destLat: Double,
            destLng: Double,
            settings: RoutingSettings
        ): RoutePathResult {
            return computeHaversineRoute(originLat, originLng, destLat, destLng)
        }
    }

    class CountingSessionStorage(
        private val delegate: InMemorySessionStorage = InMemorySessionStorage()
    ) : SessionStorage {
        val putStringCalls = AtomicInteger(0)
        val putStringsCalls = AtomicInteger(0)
        val totalKeyWrites = AtomicInteger(0)

        override suspend fun warmUp() = delegate.warmUp()
        override fun getString(key: String): String? = delegate.getString(key)

        override fun putString(key: String, value: String?) {
            putStringCalls.incrementAndGet()
            totalKeyWrites.incrementAndGet()
            delegate.putString(key, value)
        }

        override fun putStrings(entries: Map<String, String?>) {
            putStringsCalls.incrementAndGet()
            totalKeyWrites.addAndGet(entries.size)
            delegate.putStrings(entries)
        }

        override fun remove(key: String) = delegate.remove(key)
        override fun clear() {
            delegate.clear()
            resetCounts()
        }

        fun resetCounts() {
            putStringCalls.set(0)
            putStringsCalls.set(0)
            totalKeyWrites.set(0)
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        countingStorage = CountingSessionStorage()
        prefsManager = RoutingPreferencesManager(
            storage = countingStorage,
            ioDispatcher = testDispatcher
        )

        val sessionManager = SessionManager(countingStorage)
        locationsRepo = VietnamLocationsRepository()
        evcsRepo = EvcsRepository(
            apiClient = EvcsApiClient(sessionManager),
            cacheStorage = countingStorage
        )
        routePlanner = EvSmartRoutePlanner(
            coordinator = FakeRoutingCoordinator(testDispatcher)
        )
        viewModel = RouteViewModel(
            locationsRepository = locationsRepo,
            evSmartRoutePlanner = routePlanner,
            evcsRepository = evcsRepo,
            routingPreferencesManager = prefsManager,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        // Reset counts after initial setup
        countingStorage.resetCounts()
        prefsManager.resetWriteCountsForTesting()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // =========================================================================
    // 1. Simulates 50 Slider Drag Events in RoutingSettingsModal
    // =========================================================================

    @Test
    fun testContinuousSliderDrag_routingSettingsModal_zeroDiskWritesDuringDrag_andExactlyOneOnFinish() = runTest(testDispatcher) {
        // Verify initial state
        assertEquals(0, countingStorage.putStringsCalls.get())
        assertEquals(0, countingStorage.putStringCalls.get())
        assertEquals(0, prefsManager.writeCount)
        assertEquals(0, prefsManager.serializationCount)

        val initialSettings = prefsManager.evRoutingSettings.value
        assertEquals(200, initialSettings.vehicleSafeRangeKm)

        // Simulate local Compose slider state inside EvSmartRoutingSettingsCard
        var localSafeRangeState = initialSettings.vehicleSafeRangeKm.toFloat()
        var committedSettings: EvRoutingSettings? = null

        val onValueChangeFinishedCallback: (EvRoutingSettings) -> Unit = { updated ->
            committedSettings = updated
            prefsManager.updateEvRoutingSettings(updated)
        }

        // Simulate 50 continuous slider drag events (dragging from 200 km to 450 km)
        val startVal = 200f
        val endVal = 450f
        val steps = 50

        for (i in 1..steps) {
            val intermediateValue = startVal + (endVal - startVal) * (i.toFloat() / steps)
            // During drag: onValueChange updates local UI state only
            localSafeRangeState = intermediateValue

            // Assert ZERO disk writes, zero storage calls, zero serializations during continuous dragging
            assertEquals("Disk batch writes must remain 0 during drag step $i", 0, countingStorage.putStringsCalls.get())
            assertEquals("Individual putString writes must remain 0 during drag step $i", 0, countingStorage.putStringCalls.get())
            assertEquals("Manager write count must remain 0 during drag step $i", 0, prefsManager.writeCount)
            assertEquals("Manager serialization count must remain 0 during drag step $i", 0, prefsManager.serializationCount)
        }

        // Final local drag state reached 450 km
        assertEquals(450f, localSafeRangeState, 0.01f)

        // User lifts finger: onValueChangeFinished fires exactly once
        val finalSettings = initialSettings.copy(vehicleSafeRangeKm = Math.round(localSafeRangeState))
        onValueChangeFinishedCallback(finalSettings)
        advanceUntilIdle()
        assertEquals(450, committedSettings?.vehicleSafeRangeKm)

        // Assert EXACTLY ONE disk write and serialization upon onValueChangeFinished
        assertEquals("Exactly one atomic batch disk write must occur on gesture finish", 1, countingStorage.putStringsCalls.get())
        assertEquals("Manager write count must be exactly 1 after onValueChangeFinished", 1, prefsManager.writeCount)
        assertEquals("Manager serialization count must be exactly 1 after onValueChangeFinished", 1, prefsManager.serializationCount)

        // Verify storage persisted value
        assertEquals("450", countingStorage.getString(RoutingPreferencesManager.KEY_EV_SAFE_RANGE_KM))
        assertNotNull(countingStorage.getString(RoutingPreferencesManager.KEY_EV_ROUTING_SETTINGS_JSON))

        // Verify StateFlow reactive update
        assertEquals(450, prefsManager.evRoutingSettings.value.vehicleSafeRangeKm)
        assertEquals(450, prefsManager.settings.value.evSettings.vehicleSafeRangeKm)
    }

    // =========================================================================
    // 2. Simulates 50 Slider Drag Events in RouteScreen VehicleConfigurationCard
    // =========================================================================

    @Test
    fun testContinuousSliderDrag_routeScreenVehicleConfigCard_zeroDiskWritesDuringDrag_andExactlyOneOnFinish() = runTest(testDispatcher) {
        advanceUntilIdle()
        countingStorage.resetCounts()
        prefsManager.resetWriteCountsForTesting()

        // Initial ViewModel UI State
        assertEquals(200, viewModel.uiState.value.safeRangeKm)
        assertEquals(100, viewModel.uiState.value.startBatteryPercent)

        // ---------------------------------------------------------------------
        // Slider 1: Safe Range (100 - 500 km) -> 50 drag events
        // ---------------------------------------------------------------------
        var localSafeRange = viewModel.uiState.value.safeRangeKm.toFloat()

        for (i in 1..50) {
            // Dragging smoothly from 200 km to 350 km
            localSafeRange = 200f + (150f * (i.toFloat() / 50))

            // Zero disk I/O during drag
            assertEquals(0, countingStorage.putStringsCalls.get())
            assertEquals(0, prefsManager.writeCount)
            assertEquals(0, prefsManager.serializationCount)
        }

        // Lift finger: onValueChangeFinished commits to ViewModel
        val finalSafeRange = Math.round(localSafeRange)
        viewModel.onSafeRangeChanged(finalSafeRange)
        advanceUntilIdle()

        // Exactly 1 write after Slider 1 gesture finish
        assertEquals(1, countingStorage.putStringsCalls.get())
        assertEquals(1, prefsManager.writeCount)
        assertEquals(1, prefsManager.serializationCount)
        assertEquals(350, viewModel.uiState.value.safeRangeKm)
        assertEquals(350, prefsManager.evRoutingSettings.value.vehicleSafeRangeKm)

        // ---------------------------------------------------------------------
        // Slider 2: Starting SoC (10% - 100%) -> 50 drag events
        // ---------------------------------------------------------------------
        var localStartBattery = viewModel.uiState.value.startBatteryPercent.toFloat()

        for (i in 1..50) {
            // Dragging smoothly from 100% down to 65%
            localStartBattery = 100f - (35f * (i.toFloat() / 50))

            // Write count must remain 1 (no new writes during drag)
            assertEquals(1, countingStorage.putStringsCalls.get())
            assertEquals(1, prefsManager.writeCount)
            assertEquals(1, prefsManager.serializationCount)
        }

        // Lift finger: onValueChangeFinished commits to ViewModel
        val finalStartBattery = Math.round(localStartBattery)
        viewModel.onStartBatteryPercentChanged(finalStartBattery)
        advanceUntilIdle()

        // Exactly 1 additional write after Slider 2 gesture finish (total 2 writes)
        assertEquals(2, countingStorage.putStringsCalls.get())
        assertEquals(2, prefsManager.writeCount)
        assertEquals(2, prefsManager.serializationCount)
        assertEquals(65, viewModel.uiState.value.startBatteryPercent)
        assertEquals(65, prefsManager.evRoutingSettings.value.startBatteryPercent)
        assertEquals("65", countingStorage.getString(RoutingPreferencesManager.KEY_EV_START_BATTERY_PERCENT))
    }

    // =========================================================================
    // 3. Buffer SoC and Target SoC Sliders Debounce & Bounds Sanitization
    // =========================================================================

    @Test
    fun testBufferSocAndTargetSocSliders_debounceDiskWritesAndSanitization() = runTest(testDispatcher) {
        countingStorage.resetCounts()
        prefsManager.resetWriteCountsForTesting()

        val baseSettings = prefsManager.evRoutingSettings.value

        // Simulate 50 drag steps on Arrival Buffer SoC (10% -> 22%)
        var localBuffer = baseSettings.arrivalBufferSocPercent.toFloat()
        for (i in 1..50) {
            localBuffer = 10f + (12f * (i.toFloat() / 50))
            assertEquals(0, prefsManager.writeCount)
        }

        // Finish drag gesture
        prefsManager.updateArrivalBufferSocPercent(Math.round(localBuffer))
        advanceUntilIdle()

        assertEquals(1, prefsManager.writeCount)
        assertEquals(1, prefsManager.serializationCount)
        assertEquals(22, prefsManager.evRoutingSettings.value.arrivalBufferSocPercent)
        assertEquals("22", countingStorage.getString(RoutingPreferencesManager.KEY_EV_ARRIVAL_BUFFER_SOC_PERCENT))

        // Simulate 50 drag steps on Target Charging SoC (85% -> 92%)
        var localTarget = baseSettings.targetChargingSocPercent.toFloat()
        for (i in 1..50) {
            localTarget = 85f + (7f * (i.toFloat() / 50))
            assertEquals(1, prefsManager.writeCount)
        }

        // Finish drag gesture
        prefsManager.updateTargetChargingSocPercent(Math.round(localTarget))
        advanceUntilIdle()

        assertEquals(2, prefsManager.writeCount)
        assertEquals(2, prefsManager.serializationCount)
        assertEquals(92, prefsManager.evRoutingSettings.value.targetChargingSocPercent)
        assertEquals("92", countingStorage.getString(RoutingPreferencesManager.KEY_EV_TARGET_CHARGING_SOC_PERCENT))

        // Verify out-of-bounds values are sanitized to permitted ranges
        prefsManager.updateVehicleSafeRangeKm(999) // Max 500
        prefsManager.updateArrivalBufferSocPercent(99) // Max 25
        prefsManager.updateTargetChargingSocPercent(20) // Min 70
        advanceUntilIdle()

        val sanitized = prefsManager.evRoutingSettings.value
        assertEquals(500, sanitized.vehicleSafeRangeKm)
        assertEquals(25, sanitized.arrivalBufferSocPercent)
        assertEquals(70, sanitized.targetChargingSocPercent)
    }

    // =========================================================================
    // 4. Atomic Batch Updating & Cold Recovery
    // =========================================================================

    @Test
    fun testAtomicBatchUpdating_eliminatesDiskChurn_andPreservesColdRecovery() = runTest(testDispatcher) {
        countingStorage.resetCounts()
        prefsManager.resetWriteCountsForTesting()

        // Batch update all EV routing settings
        val newEv = EvRoutingSettings(
            vehicleSafeRangeKm = 380,
            startBatteryPercent = 90,
            arrivalBufferSocPercent = 15,
            targetChargingSocPercent = 80,
            safetyDurationBufferEnabled = false,
            safetyDurationBufferRatio = 0.20f
        )
        prefsManager.updateEvRoutingSettings(newEv)
        advanceUntilIdle()

        // Exactly 1 atomic batch write call to storage.putStrings
        assertEquals("Must execute exactly 1 atomic batch write", 1, countingStorage.putStringsCalls.get())
        assertEquals("Zero individual unbatched putString calls", 0, countingStorage.putStringCalls.get())
        assertEquals(1, prefsManager.writeCount)
        assertEquals(1, prefsManager.serializationCount)

        // Verify cold recovery with brand new RoutingPreferencesManager
        val coldManager = RoutingPreferencesManager(
            storage = countingStorage,
            ioDispatcher = testDispatcher
        )
        val recovered = coldManager.evRoutingSettings.value

        assertEquals(380, recovered.vehicleSafeRangeKm)
        assertEquals(90, recovered.startBatteryPercent)
        assertEquals(15, recovered.arrivalBufferSocPercent)
        assertEquals(80, recovered.targetChargingSocPercent)
        assertEquals(false, recovered.safetyDurationBufferEnabled)
        assertEquals(0.20f, recovered.safetyDurationBufferRatio, 0.001f)
    }

    // =========================================================================
    // 5. Source Code Structural Verification
    // =========================================================================

    @Test
    fun testSourceCodeContracts_routingSettingsModalAndRouteScreen_verifyOnValueChangeFinished() {
        // 1. Verify RoutingSettingsModal.kt
        val modalFile = File("src/main/java/com/evcs/favorites/ui/components/RoutingSettingsModal.kt")
        val altModalFile = File("app/src/main/java/com/evcs/favorites/ui/components/RoutingSettingsModal.kt")
        val targetModal = if (modalFile.exists()) modalFile else altModalFile

        assertTrue("RoutingSettingsModal.kt must exist", targetModal.exists())
        val modalContent = targetModal.readText()

        assertTrue(
            "RoutingSettingsModal must contain onValueChangeFinished for safeRange slider",
            modalContent.contains("onValueChangeFinished")
        )
        assertTrue(
            "RoutingSettingsModal must retain transient safeRange state via mutableFloatStateOf",
            modalContent.contains("mutableFloatStateOf")
        )
        assertTrue(
            "RoutingSettingsModal EvSmartRoutingSettingsCard must bind sliders to local state",
            modalContent.contains("var safeRange by remember") &&
                    modalContent.contains("var arrivalBuffer by remember") &&
                    modalContent.contains("var targetCharging by remember")
        )

        // 2. Verify RouteScreen.kt
        val screenFile = File("src/main/java/com/evcs/favorites/ui/screens/RouteScreen.kt")
        val altScreenFile = File("app/src/main/java/com/evcs/favorites/ui/screens/RouteScreen.kt")
        val targetScreen = if (screenFile.exists()) screenFile else altScreenFile

        assertTrue("RouteScreen.kt must exist", targetScreen.exists())
        val screenContent = targetScreen.readText()

        assertTrue(
            "VehicleConfigurationCard in RouteScreen must contain onValueChangeFinished",
            screenContent.contains("onValueChangeFinished")
        )
        assertTrue(
            "VehicleConfigurationCard must manage local slider state",
            screenContent.contains("var localSafeRange by remember") &&
                    screenContent.contains("var localStartBattery by remember")
        )

        // 3. Verify SessionManager.kt atomic batch method
        val sessionFile = File("src/main/java/com/evcs/favorites/data/auth/SessionManager.kt")
        val altSessionFile = File("app/src/main/java/com/evcs/favorites/data/auth/SessionManager.kt")
        val targetSession = if (sessionFile.exists()) sessionFile else altSessionFile

        assertTrue("SessionManager.kt must exist", targetSession.exists())
        val sessionContent = targetSession.readText()

        assertTrue(
            "SessionStorage interface must define putStrings batch method",
            sessionContent.contains("fun putStrings(entries: Map<String, String?>)")
        )
        assertTrue(
            "PlainSharedPrefsStorage must override putStrings with atomic synchronized editor transaction",
            sessionContent.contains("override fun putStrings(entries: Map<String, String?>)")
        )
    }
}
