package com.evcs.favorites.focus

import java.util.Locale

/**
 * Types of voice alerts triggered during Focus Mode navigation.
 */
enum class FocusVoiceAlert(private val defaultText: String) {
    STATION_FULL(FocusModeVoiceAlertPolicy.ALERT_TEXT_STATION_FULL),
    SLOT_AVAILABLE(FocusModeVoiceAlertPolicy.ALERT_TEXT_SLOT_AVAILABLE),
    ALTERNATIVE_FOUND(FocusModeVoiceAlertPolicy.ALERT_TEXT_ALTERNATIVE_FOUND),
    PROXIMITY_REMINDER(FocusModeVoiceAlertPolicy.ALERT_TEXT_PROXIMITY_REMINDER);

    @Volatile
    private var customText: String? = null

    val text: String
        get() = customText ?: defaultText

    fun withText(text: String): FocusVoiceAlert {
        this.customText = text
        return this
    }
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
 * 3. Alternative station found when target station is full:
 *    -> "Trạm hiện tại đã hết trụ. Đã tìm thấy trạm thay thế cách [X] km còn [Y] trụ trống."
 * 4. 2km Proximity reminder when distance drops below 2.0km for the first time:
 *    -> "Sắp đến trạm sạc, còn [X] km."
 * 5. Debouncing: Minimum 20 seconds between identical voice alerts to prevent notification fatigue.
 * 6. Steady-state: No alerts when slot counts remain unchanged or fluctuations are within the same category (>0 to >0).
 * 7. Audio preference: Supports mute/unmute toggle.
 */
class FocusModeVoiceAlertPolicy(
    initialAvailableSlots: Int? = null,
    initialDistanceKm: Double? = null,
    val debounceWindowMs: Long = DEFAULT_DEBOUNCE_WINDOW_MS,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        const val DEFAULT_DEBOUNCE_WINDOW_MS = 20_000L // 20 seconds
        const val PROXIMITY_THRESHOLD_KM = 2.0
        const val ALERT_TEXT_STATION_FULL = "Cảnh báo: Trạm sạc vừa hết chỗ!"
        const val ALERT_TEXT_SLOT_AVAILABLE = "Trụ sạc vừa có súng trống!"
        const val ALERT_TEXT_ALTERNATIVE_FOUND = "Trạm hiện tại đã hết trụ. Đã tìm thấy trạm thay thế còn trụ trống."
        const val ALERT_TEXT_PROXIMITY_REMINDER = "Sắp đến trạm sạc."

        /**
         * Formats alternative station recommendation announcement in Vietnamese.
         */
        fun formatAlternativeFoundText(distanceKm: Double, availableSlots: Int): String {
            val distStr = if (distanceKm % 1.0 == 0.0) "${distanceKm.toInt()}" else String.format(Locale.US, "%.1f", distanceKm)
            return "Trạm hiện tại đã hết trụ. Đã tìm thấy trạm thay thế cách $distStr km còn $availableSlots trụ trống."
        }

        /**
         * Formats 2km proximity arrival reminder announcement in Vietnamese.
         */
        fun formatProximityReminderText(distanceKm: Double): String {
            val distStr = if (distanceKm % 1.0 == 0.0) "${distanceKm.toInt()}" else String.format(Locale.US, "%.1f", distanceKm)
            return "Sắp đến trạm sạc, còn $distStr km."
        }
    }

    var isMuted: Boolean = false

    private var previousAvailableDcSlots: Int? = initialAvailableSlots
    private var previousDistanceRemainingKm: Double? = initialDistanceKm
    private var hasTriggeredProximityAlert: Boolean = false
    private val lastAlertTimestampMap = mutableMapOf<FocusVoiceAlert, Long>()

    /**
     * The last recorded available DC slots baseline.
     */
    val currentSlotBaseline: Int?
        get() = previousAvailableDcSlots

    /**
     * The last recorded distance baseline.
     */
    val currentDistanceBaseline: Double?
        get() = previousDistanceRemainingKm

    /**
     * Gets the last trigger timestamp for a specific [FocusVoiceAlert].
     */
    fun getLastAlertTimestamp(alert: FocusVoiceAlert): Long? = lastAlertTimestampMap[alert]

    /**
     * Evaluates a full [FocusModeState] snapshot.
     * Offline states and muted states do not trigger alerts.
     */
    fun evaluate(currentState: FocusModeState, timestamp: Long = clock()): FocusVoiceAlert? {
        if (currentState.isOffline || isMuted) {
            return null
        }
        val slotAlert = evaluate(currentState.availableDcSlots, timestamp)
        if (slotAlert != null) return slotAlert

        val altAlert = evaluateAlternativeStation(currentState.availableDcSlots, currentState.alternativeStation, timestamp)
        if (altAlert != null) return altAlert

        return evaluateProximity(currentState.distanceRemainingKm, timestamp)
    }

    /**
     * Evaluates all potential alerts for a full [FocusModeState] snapshot.
     * Returns all non-suppressed alerts triggered during this telemetry cycle.
     */
    fun evaluateAll(currentState: FocusModeState, timestamp: Long = clock()): List<FocusVoiceAlert> {
        if (currentState.isOffline || isMuted) {
            return emptyList()
        }
        val results = mutableListOf<FocusVoiceAlert>()
        evaluate(currentState.availableDcSlots, timestamp)?.let { results.add(it) }
        evaluateAlternativeStation(currentState.availableDcSlots, currentState.alternativeStation, timestamp)?.let { results.add(it) }
        evaluateProximity(currentState.distanceRemainingKm, timestamp)?.let { results.add(it) }
        return results
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
     * Evaluates alternative station availability when target station is full.
     */
    fun evaluateAlternativeStation(
        targetSlots: Int,
        recommendation: AlternativeStationRecommendation?,
        timestamp: Long = clock()
    ): FocusVoiceAlert? {
        if (isMuted) {
            return null
        }

        if (targetSlots == 0 && recommendation != null && recommendation.availableDcSlots > 0) {
            val candidate = FocusVoiceAlert.ALTERNATIVE_FOUND
            val lastTime = lastAlertTimestampMap[candidate]
            if (lastTime != null && (timestamp - lastTime) < debounceWindowMs) {
                return null
            }
            lastAlertTimestampMap[candidate] = timestamp
            return candidate.withText(
                formatAlternativeFoundText(recommendation.distanceKm, recommendation.availableDcSlots)
            )
        }

        return null
    }

    /**
     * Evaluates 2km proximity arrival reminder when remaining distance drops below 2.0km for the first time.
     */
    fun evaluateProximity(
        distanceRemainingKm: Double?,
        timestamp: Long = clock()
    ): FocusVoiceAlert? {
        if (isMuted || distanceRemainingKm == null || distanceRemainingKm.isNaN() || distanceRemainingKm <= 0.1) {
            return null
        }

        val prevDist = previousDistanceRemainingKm
        previousDistanceRemainingKm = distanceRemainingKm

        if (!hasTriggeredProximityAlert && (prevDist == null || prevDist > PROXIMITY_THRESHOLD_KM) && distanceRemainingKm <= PROXIMITY_THRESHOLD_KM) {
            val candidate = FocusVoiceAlert.PROXIMITY_REMINDER
            val lastTime = lastAlertTimestampMap[candidate]
            if (lastTime != null && (timestamp - lastTime) < debounceWindowMs) {
                return null
            }
            hasTriggeredProximityAlert = true
            lastAlertTimestampMap[candidate] = timestamp
            return candidate.withText(formatProximityReminderText(distanceRemainingKm))
        }

        return null
    }

    /**
     * Resets internal tracking and debounce timestamps.
     */
    fun reset(newBaselineSlots: Int? = null, newDistanceKm: Double? = null) {
        previousAvailableDcSlots = newBaselineSlots
        previousDistanceRemainingKm = newDistanceKm
        hasTriggeredProximityAlert = false
        lastAlertTimestampMap.clear()
    }
}
