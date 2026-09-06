package com.evcs.favorites.focus

import androidx.compose.runtime.Immutable
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Network and connectivity status of the Focus Mode telemetry stream.
 */
enum class FocusConnectionStatus {
    CONNECTED,
    OFFLINE
}

/**
 * Recommendation for an alternative charging station when the target station DC slots are saturated (0 available).
 */
@Immutable
@Serializable
data class AlternativeStationRecommendation(
    val station: Station,
    val distanceKm: Double,
    val matchingPowerWatts: Long,
    val availableDcSlots: Int,
    val totalDcSlots: Int
) {
    val matchingPowerKw: Int
        get() = (matchingPowerWatts / 1000L).toInt()

    val displayRerouteLabel: String
        get() {
            val distStr = if (distanceKm < 1.0) {
                "${(distanceKm * 1000).toInt()}m"
            } else {
                String.format(Locale.US, "%.1fkm", distanceKm)
            }
            return "Đổi trạm: ${station.name} (+$distStr)"
        }
}

/**
 * Immutable state representing the live telemetry, distance tracking, and reroute suggestions
 * for a targeted EV charging station in Focus Mode.
 */
@Immutable
@Serializable
data class FocusModeState(
    val targetStation: Station,
    val availableDcSlots: Int = 0,
    val totalDcSlots: Int = 0,
    val distanceRemainingKm: Double? = null,
    val connectionStatus: FocusConnectionStatus = FocusConnectionStatus.CONNECTED,
    val alternativeStation: AlternativeStationRecommendation? = null,
    val offlineMessage: String? = null,
    val lastUpdatedTimestamp: Long = 0L,
    val isAudioMuted: Boolean = false
) {
    val isOffline: Boolean
        get() = connectionStatus == FocusConnectionStatus.OFFLINE

    val isConnected: Boolean
        get() = connectionStatus == FocusConnectionStatus.CONNECTED

    val hasAvailableDcSlots: Boolean
        get() = availableDcSlots > 0

    val isDcFull: Boolean
        get() = totalDcSlots > 0 && availableDcSlots == 0

    /**
     * Highest DC power tier available at the target station (>= 20kW), in Watts.
     */
    val maxDcPowerWatts: Long
        get() = targetStation.powers
            .filter { FocusModeDcFilter.isDcPort(it) }
            .maxOfOrNull { it.typeWatts } ?: 0L

    val maxDcPowerKw: Int
        get() = (maxDcPowerWatts / 1000L).toInt()

    /**
     * Formatted status badge string for UI presentation:
     * - Normal: "🟢 2/8 Trống (150kW)"
     * - Full: "🔴 HẾT CHỖ!"
     * - Offline: "⚠️ Mất kết nối - Dữ liệu lúc HH:mm"
     */
    val statusBadgeText: String
        get() {
            if (isOffline) {
                return offlineMessage ?: "⚠️ Mất kết nối"
            }
            if (isDcFull) {
                return "🔴 HẾT CHỖ!"
            }
            val kwLabel = if (maxDcPowerKw > 0) " (${maxDcPowerKw}kW)" else ""
            return "🟢 $availableDcSlots/$totalDcSlots Trống$kwLabel"
        }

    companion object {
        const val OFFLINE_MESSAGE_PREFIX = "⚠️ Mất kết nối - Dữ liệu lúc "

        /**
         * Formats the standard offline fallback message with the last valid data timestamp:
         * e.g. "⚠️ Mất kết nối - Dữ liệu lúc 14:35".
         */
        fun formatOfflineTimestamp(
            timestampMillis: Long,
            timeZone: TimeZone = TimeZone.getDefault(),
            locale: Locale = Locale.getDefault()
        ): String {
            val sdf = SimpleDateFormat("HH:mm", locale).apply {
                this.timeZone = timeZone
            }
            val timeStr = sdf.format(Date(timestampMillis))
            return "$OFFLINE_MESSAGE_PREFIX$timeStr"
        }

        /**
         * Creates an initial [FocusModeState] from a target station.
         */
        fun createInitial(
            targetStation: Station,
            distanceRemainingKm: Double? = targetStation.effectiveDistanceKm,
            timestamp: Long = System.currentTimeMillis()
        ): FocusModeState {
            val (available, total) = FocusModeDcFilter.calculateDcSlots(targetStation)
            return FocusModeState(
                targetStation = targetStation,
                availableDcSlots = available,
                totalDcSlots = total,
                distanceRemainingKm = distanceRemainingKm,
                connectionStatus = FocusConnectionStatus.CONNECTED,
                alternativeStation = null,
                offlineMessage = null,
                lastUpdatedTimestamp = timestamp
            )
        }
    }
}
