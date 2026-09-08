package com.evcs.favorites.data.routing

import androidx.compose.runtime.Immutable
import com.evcs.favorites.data.model.Station
import kotlinx.serialization.Serializable
import java.io.Serializable as JavaSerializable

/**
 * Immutable session data representing a multi-stop EV navigation itinerary and current leg progression.
 *
 * Fully serializable across Android Intents and JVM testing without framework stubs.
 *
 * @property plan The complete planned EV corridor route with stops and energy profile.
 * @property currentLegIndex The index of the current leg:
 *   - 0: Origin -> Stop 1 (or Destination if 0 stops)
 *   - 1: Stop 1 -> Stop 2
 *   - ...
 *   - N: Stop N -> Final Destination
 * @property originLabel Origin descriptive label (e.g. "Hà Nội, Hoàn Kiếm").
 * @property destinationLabel Destination descriptive label (e.g. "Đà Nẵng, Hải Châu").
 */
@Immutable
@Serializable
data class RouteSessionData(
    val plan: EvSmartRoutePlan,
    val currentLegIndex: Int = 0,
    val originLabel: String = "",
    val destinationLabel: String = ""
) : JavaSerializable {

    /**
     * Total number of legs in the trip.
     * If there are N charging stops, there are N + 1 legs (or 1 leg if 0 stops).
     */
    val totalLegs: Int
        get() = plan.stops.size + 1

    /**
     * Current target stop waypoint if the current leg points to an intermediate charging stop,
     * or null if heading to the final destination.
     */
    val currentStop: EvRouteStop?
        get() = if (currentLegIndex < plan.stops.size) plan.stops[currentLegIndex] else null

    /**
     * Whether the current leg is the final leg to the destination.
     */
    val isFinalLeg: Boolean
        get() = currentLegIndex >= plan.stops.size

    /**
     * Whether all legs in the itinerary have completed.
     */
    val isCompleted: Boolean
        get() = currentLegIndex >= totalLegs

    /**
     * The target waypoint Station for the current leg (either intermediate charging stop or destination).
     */
    val currentTargetStation: Station
        get() {
            val stop = currentStop
            return if (stop != null) {
                stop.station
            } else {
                Station(
                    id = "dest_final",
                    name = destinationLabel.ifBlank { "Điểm đến" },
                    address = destinationLabel,
                    latitude = plan.destinationLat,
                    longitude = plan.destinationLng,
                    summary = "Điểm đến cuối cùng",
                    connectors = "",
                    depotStatus = "Normal",
                    totalAvailablePlugs = 0,
                    totalPlugs = 0
                )
            }
        }

    /**
     * Returns human-readable progression label for driver HUD / notifications.
     */
    val progressionLabel: String
        get() = if (!isFinalLeg) {
            "Chặng ${currentLegIndex + 1}/$totalLegs: Đến ${currentTargetStation.name}"
        } else {
            "Chặng cuối $totalLegs/$totalLegs: Đến ${currentTargetStation.name}"
        }

    /**
     * Advances to the next leg in the itinerary.
     * Returns an updated [RouteSessionData] instance, or null if the trip has already reached the final destination.
     */
    fun advanceToNextLeg(): RouteSessionData? {
        val nextIndex = currentLegIndex + 1
        return if (nextIndex < totalLegs) {
            copy(currentLegIndex = nextIndex)
        } else {
            null
        }
    }
}
