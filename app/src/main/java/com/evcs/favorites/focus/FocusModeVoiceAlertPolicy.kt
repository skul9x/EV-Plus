package com.evcs.favorites.focus

/**
 * Types of voice alerts triggered during Focus Mode navigation.
 */
enum class FocusVoiceAlert(val text: String) {
    STATION_FULL(FocusModeVoiceAlertPolicy.ALERT_TEXT_STATION_FULL),
    SLOT_AVAILABLE(FocusModeVoiceAlertPolicy.ALERT_TEXT_SLOT_AVAILABLE)
}

/**
 * Pure business logic deciding when an alert should trigger based on consecutive
 * telemetry snapshots and transition rules.
 *
 * Rules:
 * 1. Target station transitions from having slots (> 0) to 0 DC slots available:
 *    -> "Cảnh báo: Trạm sạc vừa hết chỗ!"
 * 2. Target station transitions from 0 to >= 1 DC slot available:
 *    -> "Trụ sạc vừa có súng trống!"
 * 3. Debouncing: Minimum 20 seconds between identical voice alerts to prevent notification fatigue.
 * 4. Steady-state: No alerts when slot counts remain unchanged or fluctuations are within the same category (>0 to >0).
 * 5. Audio preference: Supports mute/unmute toggle.
 */
class FocusModeVoiceAlertPolicy(
    initialAvailableSlots: Int? = null,
    val debounceWindowMs: Long = DEFAULT_DEBOUNCE_WINDOW_MS,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        const val DEFAULT_DEBOUNCE_WINDOW_MS = 20_000L // 20 seconds
        const val ALERT_TEXT_STATION_FULL = "Cảnh báo: Trạm sạc vừa hết chỗ!"
        const val ALERT_TEXT_SLOT_AVAILABLE = "Trụ sạc vừa có súng trống!"
    }

    var isMuted: Boolean = false

    private var previousAvailableDcSlots: Int? = initialAvailableSlots
    private val lastAlertTimestampMap = mutableMapOf<FocusVoiceAlert, Long>()

    /**
     * The last recorded available DC slots baseline.
     */
    val currentSlotBaseline: Int?
        get() = previousAvailableDcSlots

    /**
     * Gets the last trigger timestamp for a specific [FocusVoiceAlert].
     */
    fun getLastAlertTimestamp(alert: FocusVoiceAlert): Long? = lastAlertTimestampMap[alert]

    /**
     * Evaluates a full [FocusModeState] snapshot.
     * Offline states do not trigger slot alerts.
     */
    fun evaluate(currentState: FocusModeState, timestamp: Long = clock()): FocusVoiceAlert? {
        if (currentState.isOffline) {
            return null
        }
        return evaluate(currentState.availableDcSlots, timestamp)
    }

    /**
     * Evaluates consecutive telemetry with the new available DC slot count.
     */
    fun evaluate(currentAvailableSlots: Int, timestamp: Long = clock()): FocusVoiceAlert? {
        val prev = previousAvailableDcSlots
        previousAvailableDcSlots = currentAvailableSlots
        if (prev == null) {
            return null
        }
        return evaluateTransitionInternal(prev, currentAvailableSlots, timestamp)
    }

    /**
     * Pure evaluator testing an explicit transition from [previousAvailableSlots] to [currentAvailableSlots].
     * Updates internal tracking and debounce state.
     */
    fun evaluateTransition(
        previousAvailableSlots: Int?,
        currentAvailableSlots: Int,
        timestamp: Long = clock()
    ): FocusVoiceAlert? {
        previousAvailableDcSlots = currentAvailableSlots
        if (previousAvailableSlots == null) {
            return null
        }
        return evaluateTransitionInternal(previousAvailableSlots, currentAvailableSlots, timestamp)
    }

    private fun evaluateTransitionInternal(
        previousSlots: Int,
        currentSlots: Int,
        timestamp: Long
    ): FocusVoiceAlert? {
        val candidate = when {
            previousSlots > 0 && currentSlots == 0 -> FocusVoiceAlert.STATION_FULL
            previousSlots == 0 && currentSlots > 0 -> FocusVoiceAlert.SLOT_AVAILABLE
            else -> null
        } ?: return null

        if (isMuted) {
            return null
        }

        val lastTime = lastAlertTimestampMap[candidate]
        if (lastTime != null && (timestamp - lastTime) < debounceWindowMs) {
            // Suppressed by debouncing
            return null
        }

        lastAlertTimestampMap[candidate] = timestamp
        return candidate
    }

    /**
     * Resets internal tracking and debounce timestamps.
     */
    fun reset(newBaselineSlots: Int? = null) {
        previousAvailableDcSlots = newBaselineSlots
        lastAlertTimestampMap.clear()
    }
}
