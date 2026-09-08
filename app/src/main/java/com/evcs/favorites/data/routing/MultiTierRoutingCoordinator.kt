package com.evcs.favorites.data.routing

import com.evcs.favorites.domain.location.DistanceCalculator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    companion object {
        const val GOOGLE_TIMEOUT_MS = 3500L
        const val OSRM_TIMEOUT_MS = 3500L
        const val AUTO_ARBITRATION_TIMEOUT_MS = 5000L
    }

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

            val validDestinations = destinations
                .filter { isValidCoordinate(it.latitude, it.longitude) }
                .distinctBy { it.id }
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
            if (e is CancellationException) throw e
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
            destinations = destinations,
            timeoutMs = GOOGLE_TIMEOUT_MS
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
            customBaseUrl = settings.customOsrmServerUrl,
            timeoutMs = OSRM_TIMEOUT_MS
        )

        if (result.isSuccess) {
            val metrics = result.getOrThrow()
            if (metrics.isNotEmpty() || !settings.autoFallbackEnabled) {
                return metrics
            }
            return computeHaversine(originLat, originLng, destinations)
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
     * 2. If Tier 1 fails (or key is blank), cascades to Tier 2 (OSRM).
     * 3. If Tier 2 fails, falls back to Tier 3 (Haversine).
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
                destinations = destinations,
                timeoutMs = GOOGLE_TIMEOUT_MS
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
            customBaseUrl = settings.customOsrmServerUrl,
            timeoutMs = OSRM_TIMEOUT_MS
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

            val duration = if (distanceM > 0) {
                (distanceM / (30.0 * 1000.0 / 3600.0)).roundToLong().coerceAtLeast(60L)
            } else {
                0L
            }

            dest.id to DrivingMetrics(
                distanceMeters = distanceM,
                durationSeconds = duration,
                staticDurationSeconds = null,
                trafficCondition = TrafficCondition.UNKNOWN,
                engineUsed = RoutingEngineType.HAVERSINE
            )
        }
    }

    /**
     * Calculates the path polyline and driving metrics between origin and destination coordinates.
     * Tier priority: OSRM primary -> Google fallback (if key provided) -> Haversine baseline.
     */
    open suspend fun calculateRoutePath(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double,
        settings: RoutingSettings = RoutingSettings()
    ): RoutePathResult = withContext(ioDispatcher) {
        if (!isValidCoordinate(originLat, originLng) || !isValidCoordinate(destLat, destLng)) {
            return@withContext RoutePathResult(
                coordinates = emptyList(),
                distanceMeters = 0L,
                durationSeconds = 0L,
                engineUsed = RoutingEngineType.HAVERSINE
            )
        }

        when (settings.preferredEngine) {
            RoutingEngineMode.HAVERSINE_ONLY -> {
                computeHaversineRoute(originLat, originLng, destLat, destLng)
            }
            RoutingEngineMode.OSRM_ONLY -> {
                val osrmRes = osrmClient.computeRoute(
                    originLat = originLat,
                    originLng = originLng,
                    destLat = destLat,
                    destLng = destLng,
                    customBaseUrl = settings.customOsrmServerUrl,
                    timeoutMs = OSRM_TIMEOUT_MS
                )
                if (osrmRes.isSuccess && osrmRes.getOrThrow().coordinates.isNotEmpty()) {
                    osrmRes.getOrThrow()
                } else if (settings.autoFallbackEnabled) {
                    computeHaversineRoute(originLat, originLng, destLat, destLng)
                } else {
                    RoutePathResult(coordinates = emptyList(), distanceMeters = 0L, durationSeconds = 0L, engineUsed = RoutingEngineType.OSRM)
                }
            }
            RoutingEngineMode.GOOGLE_ONLY -> {
                if (settings.autoFallbackEnabled) {
                    computeHaversineRoute(originLat, originLng, destLat, destLng)
                } else {
                    RoutePathResult(coordinates = emptyList(), distanceMeters = 0L, durationSeconds = 0L, engineUsed = RoutingEngineType.GOOGLE)
                }
            }
            RoutingEngineMode.AUTO -> {
                val osrmRes = osrmClient.computeRoute(
                    originLat = originLat,
                    originLng = originLng,
                    destLat = destLat,
                    destLng = destLng,
                    customBaseUrl = settings.customOsrmServerUrl,
                    timeoutMs = OSRM_TIMEOUT_MS
                )
                if (osrmRes.isSuccess && osrmRes.getOrThrow().coordinates.isNotEmpty()) {
                    return@withContext osrmRes.getOrThrow()
                }

                if (settings.autoFallbackEnabled) {
                    computeHaversineRoute(originLat, originLng, destLat, destLng)
                } else {
                    RoutePathResult(coordinates = emptyList(), distanceMeters = 0L, durationSeconds = 0L, engineUsed = RoutingEngineType.OSRM)
                }
            }
        }
    }

    /**
     * Computes a linear interpolated straight-line route between two points.
     */
    fun computeHaversineRoute(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double
    ): RoutePathResult {
        val distanceM = DistanceCalculator.calculateDistanceMeters(originLat, originLng, destLat, destLng).roundToLong()
        val durationSec = if (distanceM > 0) {
            (distanceM / (50.0 * 1000.0 / 3600.0)).roundToLong().coerceAtLeast(60L)
        } else {
            0L
        }
        val distanceKm = distanceM / 1000.0
        val stepKm = 10.0
        val steps = (distanceKm / stepKm).toInt().coerceIn(1, 100)
        val coords = (0..steps).map { i ->
            val fraction = i.toDouble() / steps
            val lat = originLat + (destLat - originLat) * fraction
            val lng = originLng + (destLng - originLng) * fraction
            RouteCoordinate(latitude = lat, longitude = lng)
        }
        return RoutePathResult(
            coordinates = coords,
            distanceMeters = distanceM,
            durationSeconds = durationSec,
            engineUsed = RoutingEngineType.HAVERSINE
        )
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

