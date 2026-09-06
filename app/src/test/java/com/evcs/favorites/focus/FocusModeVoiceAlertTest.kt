package com.evcs.favorites.focus

import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Single Comprehensive Verification Test for Phase 04:
 * Voice Alert & Audio Announcement.
 *
 * Requirements covered:
 * 1. Validates alert triggered on transition from available > 0 to available == 0:
 *    Text: "Cảnh báo: Trạm sạc vừa hết chỗ!"
 * 2. Validates alert triggered on transition from available == 0 to available >= 1:
 *    Text: "Trụ sạc vừa có súng trống!"
 * 3. Validates debouncing suppresses repeated alerts within the threshold window (20s).
 * 4. Validates no alerts on steady-state polling with unchanged slot counts.
 * 5. Validates audio preference muting suppresses announcements and unmuting restores them.
 * 6. Validates end-to-end integration with FocusModeTelemetryEngine polling and event streams.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FocusModeVoiceAlertTest {

    private fun createStationWithDcSlots(id: String, availableDcPlugs: Int, totalDcPlugs: Int = 4): Station {
        val powers = listOf(
            PowerPort(
                typeWatts = 150_000L, // 150kW DC
                label = "150kW DC",
                availablePlugs = availableDcPlugs,
                totalPlugs = totalDcPlugs
            ),
            PowerPort(
                typeWatts = 11_000L, // 11kW AC (must be filtered out)
                label = "11kW AC",
                availablePlugs = 2,
                totalPlugs = 2
            )
        )
        return Station(
            id = id,
            name = "Trạm Test $id",
            address = "Địa chỉ $id",
            latitude = 10.7769,
            longitude = 106.7009,
            summary = "Trống $availableDcPlugs/$totalDcPlugs DC",
            connectors = "CCS2",
            depotStatus = "Normal",
            powers = powers,
            totalAvailablePlugs = powers.sumOf { it.availablePlugs },
            totalPlugs = powers.sumOf { it.totalPlugs }
        )
    }

    // =========================================================================
    // 1. Transition available > 0 to available == 0 triggers STATION_FULL alert
    // =========================================================================

    @Test
    fun transitionFromAvailableToZero_triggersStationFullAlert() = runTest {
        var simulatedClock = 100_000L
        val policy = FocusModeVoiceAlertPolicy(
            initialAvailableSlots = 2,
            debounceWindowMs = 20_000L,
            clock = { simulatedClock }
        )

        // Evaluate transition to 0 slots
        val alert = policy.evaluate(currentAvailableSlots = 0, timestamp = simulatedClock)
        assertNotNull("Alert must trigger when transitioning from >0 to 0", alert)
        assertEquals(FocusVoiceAlert.STATION_FULL, alert)
        assertEquals("Cảnh báo: Trạm sạc vừa hết chỗ!", alert?.text)

        // Verify baseline is updated to 0
        assertEquals(0, policy.currentSlotBaseline)

        // Verify integration with FocusModeTelemetryEngine
        val initialStation = createStationWithDcSlots("st_full", availableDcPlugs = 2)
        val fullStation = createStationWithDcSlots("st_full", availableDcPlugs = 0)

        val emittedAlerts = mutableListOf<FocusVoiceAlert>()
        val engine = FocusModeTelemetryEngine(
            initialStation = initialStation,
            fetchStationTelemetry = { _, _, _ -> Result.success(fullStation) },
            onVoiceAlert = { emittedAlerts.add(it) },
            clock = { simulatedClock }
        )

        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val collectedFromFlow = mutableListOf<FocusVoiceAlert>()
        val collectJob = launch(testDispatcher) {
            engine.voiceAlertEvents.toList(collectedFromFlow)
        }

        // Poll engine -> slot drops to 0
        engine.pollOnce()

        assertEquals(1, emittedAlerts.size)
        assertEquals(FocusVoiceAlert.STATION_FULL, emittedAlerts[0])
        assertEquals("Cảnh báo: Trạm sạc vừa hết chỗ!", emittedAlerts[0].text)
        assertEquals(1, collectedFromFlow.size)
        assertEquals(FocusVoiceAlert.STATION_FULL, collectedFromFlow[0])

        collectJob.cancel()
    }

    // =========================================================================
    // 2. Transition available == 0 to available >= 1 triggers SLOT_AVAILABLE alert
    // =========================================================================

    @Test
    fun transitionFromZeroToAvailable_triggersSlotAvailableAlert() = runTest {
        var simulatedClock = 200_000L
        val policy = FocusModeVoiceAlertPolicy(
            initialAvailableSlots = 0,
            debounceWindowMs = 20_000L,
            clock = { simulatedClock }
        )

        // Transition from 0 to 1 DC slot available
        val alert = policy.evaluate(currentAvailableSlots = 1, timestamp = simulatedClock)
        assertNotNull("Alert must trigger when slot becomes newly available", alert)
        assertEquals(FocusVoiceAlert.SLOT_AVAILABLE, alert)
        assertEquals("Trụ sạc vừa có súng trống!", alert?.text)
        assertEquals(1, policy.currentSlotBaseline)

        // Verify transition from 0 to multiple slots available (e.g. 3)
        policy.reset(newBaselineSlots = 0)
        simulatedClock += 30_000L
        val multiSlotAlert = policy.evaluate(currentAvailableSlots = 3, timestamp = simulatedClock)
        assertEquals(FocusVoiceAlert.SLOT_AVAILABLE, multiSlotAlert)
        assertEquals("Trụ sạc vừa có súng trống!", multiSlotAlert?.text)

        // Verify integration with FocusModeTelemetryEngine
        val fullStation = createStationWithDcSlots("st_free", availableDcPlugs = 0)
        val freedStation = createStationWithDcSlots("st_free", availableDcPlugs = 1)

        val emittedAlerts = mutableListOf<FocusVoiceAlert>()
        val engine = FocusModeTelemetryEngine(
            initialStation = fullStation,
            fetchStationTelemetry = { _, _, _ -> Result.success(freedStation) },
            onVoiceAlert = { emittedAlerts.add(it) },
            clock = { simulatedClock }
        )

        engine.pollOnce()

        assertEquals(1, emittedAlerts.size)
        assertEquals(FocusVoiceAlert.SLOT_AVAILABLE, emittedAlerts[0])
        assertEquals("Trụ sạc vừa có súng trống!", emittedAlerts[0].text)
    }

    // =========================================================================
    // 3. Debouncing suppresses repeated alerts within threshold window (20s)
    // =========================================================================

    @Test
    fun debouncing_suppressesRepeatedAlertsWithinThresholdWindow() {
        var currentTime = 1_000_000L
        val debounceWindow = 20_000L // 20 seconds
        val policy = FocusModeVoiceAlertPolicy(
            initialAvailableSlots = 2,
            debounceWindowMs = debounceWindow,
            clock = { currentTime }
        )

        // 1. Initial trigger: 2 -> 0 slots at t = 0
        val alert1 = policy.evaluate(currentAvailableSlots = 0, timestamp = currentTime)
        assertEquals(FocusVoiceAlert.STATION_FULL, alert1)

        // 2. Fluctuations: 0 -> 1 at t = 5s
        currentTime += 5_000L
        val alert2 = policy.evaluate(currentAvailableSlots = 1, timestamp = currentTime)
        assertEquals(FocusVoiceAlert.SLOT_AVAILABLE, alert2)

        // 3. Rapid drop back: 1 -> 0 at t = 10s (only 10s elapsed since alert1, threshold is 20s)
        currentTime += 5_000L
        val alert3 = policy.evaluate(currentAvailableSlots = 0, timestamp = currentTime)
        assertNull("Identical STATION_FULL alert must be suppressed within 20s debounce window", alert3)

        // 4. Another bounce: 0 -> 1 at t = 15s (only 10s elapsed since alert2, threshold is 20s)
        currentTime += 5_000L
        val alert4 = policy.evaluate(currentAvailableSlots = 1, timestamp = currentTime)
        assertNull("Identical SLOT_AVAILABLE alert must be suppressed within 20s debounce window", alert4)

        // 5. Time advances past 20s threshold for STATION_FULL (t = 1_000_000 + 22_000 = 22s since alert1)
        currentTime = 1_000_000L + 22_000L
        val alert5 = policy.evaluate(currentAvailableSlots = 0, timestamp = currentTime)
        assertNotNull("STATION_FULL alert must trigger after debounce window expires (22s >= 20s)", alert5)
        assertEquals(FocusVoiceAlert.STATION_FULL, alert5)

        // 6. Time advances past 20s threshold for SLOT_AVAILABLE (t = 1_005_000 + 25_000 = 30s since alert2)
        currentTime += 8_000L // Now at t = 1_030_000
        val alert6 = policy.evaluate(currentAvailableSlots = 2, timestamp = currentTime)
        assertNotNull("SLOT_AVAILABLE alert must trigger after debounce window expires", alert6)
        assertEquals(FocusVoiceAlert.SLOT_AVAILABLE, alert6)
    }

    // =========================================================================
    // 4. Steady-state polling with unchanged slot counts generates NO alerts
    // =========================================================================

    @Test
    fun steadyStatePolling_withUnchangedSlotCounts_generatesNoAlerts() = runTest {
        var currentTime = 500_000L
        val policy = FocusModeVoiceAlertPolicy(
            initialAvailableSlots = 2,
            debounceWindowMs = 20_000L,
            clock = { currentTime }
        )

        // Consecutive polls with identical available slots (2 -> 2 -> 2)
        currentTime += 5_000L
        assertNull(policy.evaluate(currentAvailableSlots = 2, timestamp = currentTime))
        currentTime += 5_000L
        assertNull(policy.evaluate(currentAvailableSlots = 2, timestamp = currentTime))

        // Transition between non-zero available counts (e.g. 2 -> 3 -> 1 -> 2, all > 0)
        currentTime += 5_000L
        assertNull("Transition from 2 to 3 slots (>0 to >0) must not alert", policy.evaluate(3, currentTime))
        currentTime += 5_000L
        assertNull("Transition from 3 to 1 slot (>0 to >0) must not alert", policy.evaluate(1, currentTime))
        currentTime += 5_000L
        assertNull("Transition from 1 to 2 slots (>0 to >0) must not alert", policy.evaluate(2, currentTime))

        // Reset to 0 and test consecutive polls with 0 slots (0 -> 0 -> 0)
        policy.reset(newBaselineSlots = 0)
        currentTime += 30_000L
        assertNull("Steady state at 0 slots must not alert", policy.evaluate(0, currentTime))
        currentTime += 5_000L
        assertNull("Steady state at 0 slots must not alert", policy.evaluate(0, currentTime))

        // Initial snapshot without previous baseline does not alert
        val freshPolicy = FocusModeVoiceAlertPolicy(initialAvailableSlots = null)
        assertNull("Initial snapshot without previous baseline must not alert", freshPolicy.evaluate(0))

        // Telemetry Engine steady-state polling test
        val station = createStationWithDcSlots("st_steady", availableDcPlugs = 2)
        var alertCount = 0
        val engine = FocusModeTelemetryEngine(
            initialStation = station,
            fetchStationTelemetry = { _, _, _ -> Result.success(station) },
            onVoiceAlert = { alertCount++ }
        )

        // 3 consecutive polls
        engine.pollOnce()
        engine.pollOnce()
        engine.pollOnce()

        assertEquals("Zero alerts should be emitted during steady-state polling", 0, alertCount)
    }

    // =========================================================================
    // 5. Mute Audio Preference & Offline Handling
    // =========================================================================

    @Test
    fun muteAudioPreference_suppressesVoiceAlerts_andUnmuteRestoresThem() = runTest {
        var currentTime = 800_000L
        val policy = FocusModeVoiceAlertPolicy(
            initialAvailableSlots = 2,
            clock = { currentTime }
        )

        // When muted, transition to 0 should be suppressed
        policy.isMuted = true
        val suppressedAlert = policy.evaluate(currentAvailableSlots = 0, timestamp = currentTime)
        assertNull("Alert must be suppressed when isMuted = true", suppressedAlert)

        // Unmute and test transition from 0 to 1
        policy.isMuted = false
        currentTime += 30_000L
        val unmutedAlert = policy.evaluate(currentAvailableSlots = 1, timestamp = currentTime)
        assertEquals(FocusVoiceAlert.SLOT_AVAILABLE, unmutedAlert)

        // Engine setMuted integration
        val fullStation = createStationWithDcSlots("st_mute", availableDcPlugs = 0)
        val alertList = mutableListOf<FocusVoiceAlert>()
        val engine = FocusModeTelemetryEngine(
            initialStation = createStationWithDcSlots("st_mute", availableDcPlugs = 2),
            fetchStationTelemetry = { _, _, _ -> Result.success(fullStation) },
            onVoiceAlert = { alertList.add(it) }
        )

        engine.setMuted(true)
        assertTrue(engine.state.value.isAudioMuted)
        engine.pollOnce()
        assertEquals("Muted engine must emit zero alerts", 0, alertList.size)

        // Offline network failure does not trigger voice alert
        val offlinePolicy = FocusModeVoiceAlertPolicy(initialAvailableSlots = 2)
        val offlineStation = createStationWithDcSlots("st_offline", availableDcPlugs = 2)
        val offlineEngine = FocusModeTelemetryEngine(
            initialStation = offlineStation,
            fetchStationTelemetry = { _, _, _ -> Result.failure(IOException("No connection")) },
            voiceAlertPolicy = offlinePolicy,
            onVoiceAlert = { alertList.add(it) }
        )

        val offlineState = offlineEngine.pollOnce()
        assertTrue(offlineState.isOffline)
        assertEquals("Network failures must not emit voice alerts", 0, alertList.size)
    }
}
