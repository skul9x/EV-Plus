package com.evcs.favorites.data.cache

import com.evcs.favorites.domain.model.StationForecast
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache entry holding [StationForecast] and its creation timestamp.
 */
data class ForecastCacheEntry(
    val forecast: StationForecast,
    val timestamp: Long
)

/**
 * Thread-safe in-memory cache for station forecasts with:
 * - 3-minute (180,000 ms) TTL for valid forecasts.
 * - 1-minute (60,000 ms) failure cooldown tracker for transient network failures.
 */
open class ForecastCache(
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        const val TTL_MS = 180_000L // 3 minutes
        const val COOLDOWN_MS = 60_000L // 1 minute
    }

    private val cache = ConcurrentHashMap<String, ForecastCacheEntry>()
    private val failureCooldowns = ConcurrentHashMap<String, Long>()

    /**
     * Gets valid cached forecast if present and age < 180s.
     * Returns null if not present or expired.
     */
    fun get(stationId: String): StationForecast? {
        val key = stationId.trim().lowercase()
        val entry = cache[key] ?: return null
        val now = timeProvider()
        return if (now - entry.timestamp < TTL_MS) {
            entry.forecast
        } else {
            cache.remove(key)
            null
        }
    }

    /**
     * Gets cache entry with timestamp if present and not expired.
     */
    fun getEntry(stationId: String): ForecastCacheEntry? {
        val key = stationId.trim().lowercase()
        val entry = cache[key] ?: return null
        val now = timeProvider()
        return if (now - entry.timestamp < TTL_MS) {
            entry
        } else {
            cache.remove(key)
            null
        }
    }

    /**
     * Stores forecast in cache with current timestamp and clears any active failure cooldown.
     */
    fun put(stationId: String, forecast: StationForecast) {
        val key = stationId.trim().lowercase()
        cache[key] = ForecastCacheEntry(forecast, timeProvider())
        failureCooldowns.remove(key)
    }

    /**
     * Checks whether the station is currently in failure cooldown (< 60s).
     */
    fun isInCooldown(stationId: String): Boolean {
        val key = stationId.trim().lowercase()
        val failedTime = failureCooldowns[key] ?: return false
        val now = timeProvider()
        return if (now - failedTime < COOLDOWN_MS) {
            true
        } else {
            failureCooldowns.remove(key)
            false
        }
    }

    /**
     * Records a failure for the station, starting a 1-minute cooldown.
     */
    fun recordFailure(stationId: String) {
        val key = stationId.trim().lowercase()
        failureCooldowns[key] = timeProvider()
    }

    /**
     * Invalidates both cache and failure cooldown for the specified station.
     */
    fun invalidate(stationId: String) {
        val key = stationId.trim().lowercase()
        cache.remove(key)
        failureCooldowns.remove(key)
    }

    /**
     * Clears all cached forecasts and failure cooldowns.
     */
    fun clearAll() {
        cache.clear()
        failureCooldowns.clear()
    }

    /**
     * Current count of active cached forecasts.
     */
    fun size(): Int = cache.size
}
