package com.evcs.favorites.focus

import android.content.Context
import android.content.ContextWrapper
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.preferences.FocusModePreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprehensive verification unit test for Phase 03:
 * - FocusModePreferences persistent storage & StateFlow observation
 * - Preference synchronization with FocusModeTtsManager & FocusModeVoiceAlertPolicy
 * - Fulfillment of the 3 automotive voice alert scenarios (0 slots, alternative found, 2km proximity)
 * - Complete suppression of speech and audio ducking when muted
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FocusModeVoiceAlertSettingsTest {

    private fun createFakeContext(): Context {
        return object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? = null
        }
    }

    private fun createStation(
        id: String,
        name: String,
        availableDcPlugs: Int,
        totalDcPlugs: Int = 4,
        typeWatts: Long = 150_000L,
        lat: Double = 10.762622,
        lon: Double = 106.660172
    ): Station {
        val powers = listOf(
            PowerPort(
                typeWatts = typeWatts,
                label = "DC Fast",
                availablePlugs = availableDcPlugs,
                totalPlugs = totalDcPlugs
            )
        )
        return Station(
            id = id,
            name = name,
            address = "Test Street",
            latitude = lat,
            longitude = lon,
            summary = "Trống $availableDcPlugs/$totalDcPlugs DC",
            connectors = "CCS2",
            depotStatus = "Normal",
            powers = powers,
            totalAvailablePlugs = powers.sumOf { it.availablePlugs },
            totalPlugs = powers.sumOf { it.totalPlugs }
        )
    }

    // =========================================================================
    // 1. Preferences Storage & StateFlow Verification
    // =========================================================================

    @Test
    fun testDefaultSettingValue_isTrue() {
        val storage = InMemorySessionStorage()
        val prefs = FocusModePreferences(storage)

        assertTrue("Default voice alert setting must be true", prefs.isVoiceAlertEnabled())
        assertTrue("Initial StateFlow emission must be true", prefs.voiceAlertEnabledFlow.value)
    }

    @Test
    fun testPreferenceWritingAndReadingAcrossRestarts() {
        val storage = InMemorySessionStorage()
        val session1 = FocusModePreferences(storage)

        session1.setVoiceAlertEnabled(false)
        assertFalse(session1.isVoiceAlertEnabled())
        assertEquals("false", storage.getString(FocusModePreferences.KEY_VOICE_ALERT_ENABLED))

        // Simulate app restart with same underlying storage
        val session2 = FocusModePreferences(storage)
        assertFalse("Persisted false setting must survive cold start", session2.isVoiceAlertEnabled())
        assertFalse("StateFlow after cold start must reflect persisted false", session2.voiceAlertEnabledFlow.value)

        session2.setVoiceAlertEnabled(true)
        assertTrue(session2.isVoiceAlertEnabled())
        assertEquals("true", storage.getString(FocusModePreferences.KEY_VOICE_ALERT_ENABLED))
        assertTrue(session2.voiceAlertEnabledFlow.value)
    }

    @Test
    fun testVoiceAlertEnabledFlow_emitsOnToggle() = runTest {
        val storage = InMemorySessionStorage()
        val prefs = FocusModePreferences(storage)

        val emissions = mutableListOf<Boolean>()
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val collectJob = launch(testDispatcher) {
            prefs.voiceAlertEnabledFlow.toList(emissions)
        }

        prefs.setVoiceAlertEnabled(false)
        prefs.setVoiceAlertEnabled(true)
        prefs.setVoiceAlertEnabled(false)

        assertEquals(listOf(true, false, true, false), emissions)
        collectJob.cancel()
    }

    // =========================================================================
    // 2. Service & TTS Manager Synchronization
    // =========================================================================

    @Test
    fun testServiceIntentSpecAndTtsManagerMuteSync() = runTest {
        val intentSpec = FocusModeForegroundService.getSetMutedIntentSpec(isMuted = true)
        assertEquals(FocusModeForegroundService.ACTION_SET_MUTED, intentSpec.action)
        assertEquals(FocusModeForegroundService::class.java, intentSpec.targetClass)
        assertEquals("true", intentSpec.payloadJson)

        val fakeContext = createFakeContext()
        val ttsManager = FocusModeTtsManager(
            context = fakeContext,
            audioManager = null,
            initTtsImmediately = false,
            coroutineScope = this
        )

        // When muted, ttsManager drops active ducking and rejects speech
        ttsManager.requestDuckAudioFocus()
        assertTrue(ttsManager.isAudioDuckingActive)

        ttsManager.isMuted = true
        assertFalse("Muting TTS manager must immediately abandon audio ducking", ttsManager.isAudioDuckingActive)

        // Calling speak while muted must be completely ignored
        ttsManager.speak("Cảnh báo thử nghiệm")
        assertFalse("Speaking while muted must not acquire duck focus", ttsManager.isAudioDuckingActive)
    }

    // =========================================================================
    // 3. Automotive Voice Scenario 1: Station Full (0 slots)
    // =========================================================================

    @Test
    fun testVoiceAlert_stationFullTransition_andDebounce() {
        var clockTime = 1_000L
        val policy = FocusModeVoiceAlertPolicy(
            initialAvailableSlots = 3,
            debounceWindowMs = 20_000L,
            clock = { clockTime }
        )

        val alert = policy.evaluate(currentAvailableSlots = 0, timestamp = clockTime)
        assertNotNull("Transition from 3 to 0 must trigger alert", alert)
        assertEquals(FocusVoiceAlert.STATION_FULL, alert)
        assertEquals("Cảnh báo: Trạm sạc vừa hết chỗ!", alert?.text)

        // Same slot count on next poll -> steady state, no alert
        clockTime += 5_000L
        assertNull("Steady state at 0 must not trigger alert", policy.evaluate(0, timestamp = clockTime))

        // Transition 0 -> 1 -> 0 within debounce window
        clockTime += 5_000L // 11s total (< 20s debounce)
        val slotAvailAlert = policy.evaluate(1, timestamp = clockTime)
        assertEquals(FocusVoiceAlert.SLOT_AVAILABLE, slotAvailAlert)

        clockTime += 2_000L // 13s total (< 20s from first STATION_FULL at 1s)
        val suppressedFullAlert = policy.evaluate(0, timestamp = clockTime)
        assertNull("STATION_FULL within 20s debounce window must be suppressed", suppressedFullAlert)

        // After debounce window expires (> 20s)
        clockTime += 25_000L
        val unsuppressedFullAlert = policy.evaluateTransition(1, 0, timestamp = clockTime)
        assertEquals(FocusVoiceAlert.STATION_FULL, unsuppressedFullAlert)
    }

    // =========================================================================
    // 4. Automotive Voice Scenario 2: Alternative Station Found
    // =========================================================================

    @Test
    fun testVoiceAlert_alternativeStationFound() {
        var clockTime = 1_000L
        val policy = FocusModeVoiceAlertPolicy(
            initialAvailableSlots = 0,
            debounceWindowMs = 20_000L,
            clock = { clockTime }
        )

        val altStation = createStation("alt_1", "VinFast Landmark 81", availableDcPlugs = 4)
        val recommendation = AlternativeStationRecommendation(
            station = altStation,
            distanceKm = 1.5,
            matchingPowerWatts = 150_000L,
            availableDcSlots = 4,
            totalDcSlots = 4
        )

        val alert = policy.evaluateAlternativeStation(
            targetSlots = 0,
            recommendation = recommendation,
            timestamp = clockTime
        )

        assertNotNull("Alternative station found when target is 0 must trigger alert", alert)
        assertEquals(FocusVoiceAlert.ALTERNATIVE_FOUND, alert)
        assertTrue(
            "Text must contain Vietnamese alternative notice",
            alert!!.text.startsWith("Trạm hiện tại đã hết trụ. Đã tìm thấy trạm thay thế")
        )
        assertEquals(
            "Trạm hiện tại đã hết trụ. Đã tìm thấy trạm thay thế cách 1.5 km còn 4 trụ trống.",
            alert.text
        )

        // Consecutive evaluation within debounce window must be suppressed
        clockTime += 5_000L
        val debouncedAlert = policy.evaluateAlternativeStation(
            targetSlots = 0,
            recommendation = recommendation,
            timestamp = clockTime
        )
        assertNull("Alternative alert within 20s must be debounced", debouncedAlert)

        // After debounce window expires
        clockTime += 25_000L
        val nextAlert = policy.evaluateAlternativeStation(
            targetSlots = 0,
            recommendation = recommendation,
            timestamp = clockTime
        )
        assertNotNull("Alternative alert after debounce window must trigger", nextAlert)
    }

    // =========================================================================
    // 5. Automotive Voice Scenario 3: 2km Proximity Boundary Crossing
    // =========================================================================

    @Test
    fun testVoiceAlert_proximityBoundaryCrossing() {
        var clockTime = 1_000L
        val policy = FocusModeVoiceAlertPolicy(
            initialAvailableSlots = 2,
            initialDistanceKm = 4.5,
            debounceWindowMs = 20_000L,
            clock = { clockTime }
        )

        // Distance still far (> 2.0km)
        clockTime += 5_000L
        assertNull("Distance > 2.0km must not trigger proximity reminder", policy.evaluateProximity(3.0, clockTime))

        // Drops below 2.0km for the first time
        clockTime += 5_000L
        val alert = policy.evaluateProximity(1.8, clockTime)
        assertNotNull("Crossing below 2.0km boundary must trigger proximity alert", alert)
        assertEquals(FocusVoiceAlert.PROXIMITY_REMINDER, alert)
        assertTrue(
            "Text must contain proximity notice",
            alert!!.text.startsWith("Sắp đến trạm sạc, còn")
        )
        assertEquals("Sắp đến trạm sạc, còn 1.8 km.", alert.text)

        // Subsequent polls closer to station must NOT trigger again (only once per journey)
        clockTime += 5_000L
        assertNull("Subsequent closer distance must not trigger duplicate proximity alert", policy.evaluateProximity(1.5, clockTime))

        clockTime += 30_000L // Even after 20s debounce window
        assertNull("Proximity reminder must trigger only once on journey", policy.evaluateProximity(0.8, clockTime))
    }

    // =========================================================================
    // 6. Complete Suppression When Muted
    // =========================================================================

    @Test
    fun testMutedState_completelySuppressesAllAlerts() {
        val policy = FocusModeVoiceAlertPolicy(
            initialAvailableSlots = 2,
            initialDistanceKm = 3.0
        )
        policy.isMuted = true

        val altStation = createStation("alt_1", "Trạm thay thế", availableDcPlugs = 2)
        val recommendation = AlternativeStationRecommendation(
            station = altStation,
            distanceKm = 1.2,
            matchingPowerWatts = 150_000L,
            availableDcSlots = 2,
            totalDcSlots = 2
        )

        val fullState = FocusModeState(
            targetStation = createStation("st_1", "Trạm chính", availableDcPlugs = 0),
            availableDcSlots = 0,
            totalDcSlots = 2,
            distanceRemainingKm = 1.5,
            connectionStatus = FocusConnectionStatus.CONNECTED,
            alternativeStation = recommendation,
            offlineMessage = null,
            lastUpdatedTimestamp = 1_000L,
            isAudioMuted = true
        )

        assertNull(policy.evaluate(currentAvailableSlots = 0))
        assertNull(policy.evaluateAlternativeStation(targetSlots = 0, recommendation = recommendation))
        assertNull(policy.evaluateProximity(distanceRemainingKm = 1.5))
        assertNull(policy.evaluate(fullState))
        assertTrue("evaluateAll must return empty list when muted", policy.evaluateAll(fullState).isEmpty())
    }

    // =========================================================================
    // 7. End-to-End Telemetry Engine Integration With Voice Pipeline
    // =========================================================================

    @Test
    fun testTelemetryEngine_voicePipelineIntegration() = runTest {
        var simulatedClock = 10_000L
        val initialStation = createStation("st_target", "Trạm đích", availableDcPlugs = 3, lat = 10.77, lon = 106.69)
        val fullStation = createStation("st_target", "Trạm đích", availableDcPlugs = 0, lat = 10.77, lon = 106.69)
        val candidateStation = createStation("st_alt", "Trạm thay thế Vincom", availableDcPlugs = 4, lat = 10.78, lon = 106.70)

        val emittedAlerts = mutableListOf<FocusVoiceAlert>()
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)

        val engine = FocusModeTelemetryEngine(
            initialStation = initialStation,
            fetchStationTelemetry = { _, _, _ -> Result.success(fullStation) },
            fetchNearbyCandidates = { _, _ -> Result.success(listOf(candidateStation)) },
            locationProvider = { Pair(10.765, 106.685) }, // ~1.8km to target
            clock = { simulatedClock },
            defaultDispatcher = testDispatcher,
            onVoiceAlert = { emittedAlerts.add(it) }
        )

        val collectedFlow = mutableListOf<FocusVoiceAlert>()
        val collectJob = launch(testDispatcher) {
            engine.voiceAlertEvents.toList(collectedFlow)
        }

        // Poll engine: Target slots drop to 0, recommendation found, distance < 2.0km
        engine.pollOnce()

        // Assert all 3 alerts generated and ordered properly
        assertEquals(3, emittedAlerts.size)
        assertEquals(FocusVoiceAlert.STATION_FULL, emittedAlerts[0])
        assertEquals(FocusVoiceAlert.ALTERNATIVE_FOUND, emittedAlerts[1])
        assertEquals(FocusVoiceAlert.PROXIMITY_REMINDER, emittedAlerts[2])

        assertEquals(3, collectedFlow.size)
        assertEquals(FocusVoiceAlert.STATION_FULL, collectedFlow[0])
        assertEquals(FocusVoiceAlert.ALTERNATIVE_FOUND, collectedFlow[1])
        assertEquals(FocusVoiceAlert.PROXIMITY_REMINDER, collectedFlow[2])

        // Verify muting engine suppresses further alerts
        engine.setMuted(true)
        assertTrue(engine.state.value.isAudioMuted)
        assertTrue(engine.voiceAlertPolicy?.isMuted == true)

        simulatedClock += 30_000L
        engine.pollOnce()

        // Size must not have increased
        assertEquals(3, emittedAlerts.size)
        assertEquals(3, collectedFlow.size)

        collectJob.cancel()
    }
}
