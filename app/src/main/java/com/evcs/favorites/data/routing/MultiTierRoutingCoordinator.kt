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
        const val CACHE_TTL_MS = 60_000L
        const val MAX_CACHE_DISPLACEMENT_METERS = 50.0
    }

    private val cacheLock = Any()
    internal val cachedMetrics = java.util.concurrent.ConcurrentHashMap<String, DrivingMetrics>()
    var cachedOriginLat: Double? = null
        internal set
    var cachedOriginLng: Double? = null
        internal set
    var cacheTimestamp: Long = 0L
        internal set

    internal var timeProvider: () -> Long = { System.currentTimeMillis() }

    /**
     * Clears all in-memory routing cache entries and origin state.
     */
    fun clearRoutingCache() {
        synchronized(cacheLock) {
            cachedMetrics.clear()
            cachedOriginLat = null
            cachedOriginLng = null
            cacheTimestamp = 0L
        }
    }

    /**
     * Backward-compatible 4-parameter calculateRoutes delegating to the cached 5-parameter method.
     */
    open suspend fun calculateRoutes(
        originLat: Double,
        originLng: Double,
        destinations: List<RoutingDestination>,
        settings: RoutingSettings
    ): Map<String, DrivingMetrics> {
        return calculateRoutes(originLat, originLng, destinations, settings, forceRefresh = false)
    }

    /**
     * Calculates driving metrics from origin to given destination stations according to user settings,
     * applying coordinate displacement and TTL caching.
     *
     * @param originLat Origin latitude in degrees.
     * @param originLng Origin longitude in degrees.
     * @param destinations List of target destinations with IDs and coordinates.
     * @param settings User routing preferences, BYOK Google key, and fallback switches.
     * @param forceRefresh When true, bypasses in-memory cache and performs remote calculation.
     * @return Map of stationId -> DrivingMetrics.
     */
    open suspend fun calculateRoutes(
        originLat: Double,
        originLng: Double,
        destinations: List<RoutingDestination>,
        settings: RoutingSettings,
        forceRefresh: Boolean
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

            // 2. Coordinate Displacement & TTL Cache Check
            synchronized(cacheLock) {
                if (!forceRefresh) {
                    val lastLat = cachedOriginLat
                    val lastLng = cachedOriginLng
                    val lastTime = cacheTimestamp
                    val now = timeProvider()
                    if (lastLat != null && lastLng != null && (now - lastTime) <= CACHE_TTL_MS) {
                        val displacement = DistanceCalculator.calculateDistanceMeters(lastLat, lastLng, originLat, originLng)
                        if (displacement <= MAX_CACHE_DISPLACEMENT_METERS && validDestinations.all { cachedMetrics.containsKey(it.id) }) {
                            return@withContext validDestinations.associate { it.id to cachedMetrics[it.id]!! }
                        }
                    }
                }
            }

            // 3. Execution (Subclass override or engine arbitration)
            val results = if (hasSubclassOverridden4Arg()) {
                calculateRoutes(originLat, originLng, validDestinations, settings)
            } else {
                executeEngineArbitration(originLat, originLng, validDestinations, settings)
            }

            // 4. Update Cache on Successful Calculations
            if (results.isNotEmpty()) {
                synchronized(cacheLock) {
                    val lastLat = cachedOriginLat
                    val lastLng = cachedOriginLng
                    val now = timeProvider()
                    val isDisplaced = lastLat == null || lastLng == null ||
                        DistanceCalculator.calculateDistanceMeters(lastLat, lastLng, originLat, originLng) > MAX_CACHE_DISPLACEMENT_METERS
                    val isExpired = (now - cacheTimestamp) > CACHE_TTL_MS

                    if (isDisplaced || isExpired || forceRefresh) {
                        cachedMetrics.clear()
                    }
                    cachedMetrics.putAll(results)
                    cachedOriginLat = originLat
                    cachedOriginLng = originLng
                    cacheTimestamp = now
                }
            }

            results
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            emptyMap()
        }
    }

    private fun hasSubclassOverridden4Arg(): Boolean {
        return try {
            val method = this.javaClass.getMethod(
                "calculateRoutes",
                Double::class.javaPrimitiveType,
                Double::class.javaPrimitiveType,
                List::class.java,
                RoutingSettings::class.java,
                kotlin.coroutines.Continuation::class.java
            )
            method.declaringClass != MultiTierRoutingCoordinator::class.java
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun executeEngineArbitration(
        originLat: Double,
        originLng: Double,
        destinations: List<RoutingDestination>,
        settings: RoutingSettings
    ): Map<String, DrivingMetrics> {
        return when (settings.preferredEngine) {
            RoutingEngineMode.HAVERSINE_ONLY -> {
                computeHaversine(originLat, originLng, destinations)
            }
            RoutingEngineMode.OSRM_ONLY -> {
                executeOsrm(originLat, originLng, destinations, settings)
            }
            RoutingEngineMode.GOOGLE_ONLY -> {
                executeGoogleOnly(originLat, originLng, destinations, settings)
            }
            RoutingEngineMode.AUTO -> {
                executeAuto(originLat, originLng, destinations, settings)
            }
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
     * Validates that coordinate values are numeric, within GPS ranges, and not unresolved (0.0, 0.0).
     */
    private fun isValidCoordinate(lat: Double, lng: Double): Boolean {
        if (lat.isNaN() || lng.isNaN()) return false
        if (lat == 0.0 && lng == 0.0) return false
        return lat in -90.0..90.0 && lng in -180.0..180.0
    }
}
