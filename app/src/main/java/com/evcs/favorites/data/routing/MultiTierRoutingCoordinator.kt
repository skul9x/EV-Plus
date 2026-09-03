package com.evcs.favorites.data.routing

import com.evcs.favorites.domain.location.DistanceCalculator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

/**
 * Central orchestrator and fallback arbitrator for the 3-Tier Multi-Engine Routing System.
 *
 * Tier 1: Google Routes API v2 (live traffic aware, requires user BYOK API Key)
 * Tier 2: OSRM Table Service (free road network driving distance & duration, zero key required)
 * Tier 3: Local Haversine Calculation (offline, 0ms, straight-line distance baseline)
 *
 * Sanitizes input coordinates, honors mode configuration, and safely cascades to lower tiers
 * upon upstream network errors, quota limits, or authentication failures.
 */
open class MultiTierRoutingCoordinator(
    private val googleClient: GoogleRoutesClient = GoogleRoutesClient(),
    private val osrmClient: OsrmRoutingClient = OsrmRoutingClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    /**
     * Calculates driving metrics from origin to given destination stations according to user settings.
     *
     * @param originLat Origin latitude in degrees.
     * @param originLng Origin longitude in degrees.
     * @param destinations List of target destinations with IDs and coordinates.
     * @param settings User routing preferences, BYOK Google key, and fallback switches.
     * @return Map of stationId -> DrivingMetrics.
     */
    open suspend fun calculateRoutes(
        originLat: Double,
        originLng: Double,
        destinations: List<RoutingDestination>,
        settings: RoutingSettings
    ): Map<String, DrivingMetrics> = withContext(ioDispatcher) {
        try {
            // 1. Input Sanitization & Validation
            if (!isValidCoordinate(originLat, originLng)) {
                return@withContext emptyMap()
            }

            val validDestinations = destinations.filter { isValidCoordinate(it.latitude, it.longitude) }
            if (validDestinations.isEmpty()) {
                return@withContext emptyMap()
            }

            // 2. Mode Arbitration
            when (settings.preferredEngine) {
                RoutingEngineMode.HAVERSINE_ONLY -> {
                    computeHaversine(originLat, originLng, validDestinations)
                }
                RoutingEngineMode.OSRM_ONLY -> {
                    executeOsrm(originLat, originLng, validDestinations, settings)
                }
                RoutingEngineMode.GOOGLE_ONLY -> {
                    executeGoogleOnly(originLat, originLng, validDestinations, settings)
                }
                RoutingEngineMode.AUTO -> {
                    executeAuto(originLat, originLng, validDestinations, settings)
                }
            }
        } catch (e: Throwable) {
            emptyMap()
        }
    }

    /**
     * Executes Tier 1 (Google Routes API v2) exclusively without fallback cascades.
     */
    private suspend fun executeGoogleOnly(
        originLat: Double,
        originLng: Double,
        destinations: List<RoutingDestination>,
        settings: RoutingSettings
    ): Map<String, DrivingMetrics> {
        if (settings.googleApiKey.isBlank()) {
            return emptyMap()
        }

        val result = googleClient.computeRouteMatrix(
            apiKey = settings.googleApiKey,
            originLat = originLat,
            originLng = originLng,
            destinations = destinations
        )

        return result.getOrElse { emptyMap() }
    }

    /**
     * Executes Tier 2 (OSRM Table Service) directly, skipping Google even if an API key is present.
     * Falls back to Tier 3 (Haversine) only if autoFallbackEnabled is true.
     */
    private suspend fun executeOsrm(
        originLat: Double,
        originLng: Double,
        destinations: List<RoutingDestination>,
        settings: RoutingSettings
    ): Map<String, DrivingMetrics> {
        val result = osrmClient.computeTable(
            originLat = originLat,
            originLng = originLng,
            destinations = destinations,
            customBaseUrl = settings.customOsrmServerUrl
        )

        if (result.isSuccess) {
            return result.getOrThrow()
        }

        return if (settings.autoFallbackEnabled) {
            computeHaversine(originLat, originLng, destinations)
        } else {
            emptyMap()
        }
    }

    /**
     * Executes AUTO arbitration:
     * 1. Attempts Tier 1 (Google) if googleApiKey is present.
     * 2. If Tier 1 fails (or key is blank), cascades to Tier 2 (OSRM) when autoFallbackEnabled is true.
     * 3. If Tier 2 fails, cascades to Tier 3 (Haversine) when autoFallbackEnabled is true.
     */
    private suspend fun executeAuto(
        originLat: Double,
        originLng: Double,
        destinations: List<RoutingDestination>,
        settings: RoutingSettings
    ): Map<String, DrivingMetrics> {
        // Attempt Tier 1: Google Routes v2 if key is configured
        if (settings.googleApiKey.isNotBlank()) {
            val googleResult = googleClient.computeRouteMatrix(
                apiKey = settings.googleApiKey,
                originLat = originLat,
                originLng = originLng,
                destinations = destinations
            )

            if (googleResult.isSuccess) {
                return googleResult.getOrThrow()
            }

            // Tier 1 failed; if autoFallback is disabled, stop immediately
            if (!settings.autoFallbackEnabled) {
                return emptyMap()
            }
        }

        // Attempt Tier 2: OSRM Table Service
        val osrmResult = osrmClient.computeTable(
            originLat = originLat,
            originLng = originLng,
            destinations = destinations,
            customBaseUrl = settings.customOsrmServerUrl
        )

        if (osrmResult.isSuccess) {
            return osrmResult.getOrThrow()
        }

        // Attempt Tier 3: Local Haversine baseline calculation
        return if (settings.autoFallbackEnabled) {
            computeHaversine(originLat, originLng, destinations)
        } else {
            emptyMap()
        }
    }

    /**
     * Computes local straight-line distances using the Haversine formula (Tier 3).
     * 100% offline, zero latency, and zero network calls.
     */
    fun computeHaversine(
        originLat: Double,
        originLng: Double,
        destinations: List<RoutingDestination>
    ): Map<String, DrivingMetrics> {
        return destinations.associate { dest ->
            val distanceM = DistanceCalculator.calculateDistanceMeters(
                lat1 = originLat,
                lon1 = originLng,
                lat2 = dest.latitude,
                lon2 = dest.longitude
            ).roundToLong()

            dest.id to DrivingMetrics(
                distanceMeters = distanceM,
                durationSeconds = 0L,
                staticDurationSeconds = null,
                trafficCondition = TrafficCondition.UNKNOWN,
                engineUsed = RoutingEngineType.HAVERSINE
            )
        }
    }

    /**
     * Validates that coordinate values are numeric, within GPS ranges, and not unresolved (0.0, 0.0).
     */
    private fun isValidCoordinate(lat: Double, lng: Double): Boolean {
        if (lat.isNaN() || lng.isNaN()) return false
        if (lat == 0.0 && lng == 0.0) return false
        return lat in -90.0..90.0 && lng in -180.0..180.0
    }
}
