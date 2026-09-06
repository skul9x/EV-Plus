package com.evcs.favorites.data.routing

import kotlinx.serialization.Serializable

/**
 * User routing preferences mode.
 */
@Serializable
enum class RoutingEngineMode {
    AUTO,
    GOOGLE_ONLY,
    OSRM_ONLY,
    HAVERSINE_ONLY
}

/**
 * User configuration and BYOK preferences for routing calculations.
 */
@Serializable
data class RoutingSettings(
    val googleApiKey: String = "",
    val preferredEngine: RoutingEngineMode = RoutingEngineMode.OSRM_ONLY,
    val autoFallbackEnabled: Boolean = true,
    val customOsrmServerUrl: String? = null
)

/**
 * Target destination point for multi-destination routing matrices.
 */
@Serializable
data class RoutingDestination(
    val id: String,
    val latitude: Double,
    val longitude: Double
)

/**
 * Exception thrown or encapsulated when routing network providers fail.
 */
class RoutingApiException(
    val statusCode: Int,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

// ==========================================
// Google Routes API v2 DTOs
// ==========================================

@Serializable
data class GoogleRouteMatrixRequest(
    val origins: List<GoogleRouteWaypointWrapper>,
    val destinations: List<GoogleRouteWaypointWrapper>,
    val travelMode: String = "DRIVE",
    val routingPreference: String = "TRAFFIC_AWARE"
)

@Serializable
data class GoogleRouteWaypointWrapper(
    val waypoint: GoogleRouteWaypoint
)

@Serializable
data class GoogleRouteWaypoint(
    val location: GoogleRouteLocation
)

@Serializable
data class GoogleRouteLocation(
    val latLng: GoogleLatLng
)

@Serializable
data class GoogleLatLng(
    val latitude: Double,
    val longitude: Double
)

@Serializable
data class GoogleRouteMatrixElement(
    val originIndex: Int = 0,
    val destinationIndex: Int = 0,
    val status: GoogleRpcStatus? = null,
    val condition: String? = null, // "ROUTE_EXISTS", "ROUTE_NOT_FOUND"
    val distanceMeters: Long? = null,
    val duration: String? = null, // e.g. "185s"
    val staticDuration: String? = null // e.g. "150s"
)

@Serializable
data class GoogleRpcStatus(
    val code: Int = 0,
    val message: String? = null
)

// ==========================================
// OSRM Table Service DTOs
// ==========================================

@Serializable
data class OsrmTableResponse(
    val code: String = "",
    val durations: List<List<Double?>>? = null,
    val distances: List<List<Double?>>? = null,
    val message: String? = null
)
