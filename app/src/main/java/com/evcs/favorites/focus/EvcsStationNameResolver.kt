package com.evcs.favorites.focus

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.model.SearchStationRaw
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.domain.location.DistanceCalculator
import com.evcs.favorites.util.StationNameSanitizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Resolver utility resolving and caching authentic, standard charging station names from evcs.vn.
 *
 * Prevents generic station names such as "Trạm sạc VinFast" or "VinFast" from Here EV API
 * from masking specific location identifiers (e.g. "VinFast Landmark 81").
 *
 * Provides in-memory caching and matching by station/location ID or coordinate proximity (<= 100 meters).
 */
class EvcsStationNameResolver(
    private val searchStationsProvider: (suspend (lat: Double, lon: Double) -> Result<List<SearchStationRaw>>)? = null,
    private val apiClient: EvcsApiClient? = null
) {
    data class CachedStationCoord(
        val latitude: Double,
        val longitude: Double,
        val name: String,
        val stationId: String? = null
    )

    private val idToNameCache = ConcurrentHashMap<String, String>()
    private val coordCache = CopyOnWriteArrayList<CachedStationCoord>()

    companion object {
        const val MAX_COORDINATE_MATCH_METERS = 100.0

        private val GENERIC_NAMES = setOf(
            "trạm sạc vinfast",
            "tram sac vinfast",
            "trạm sạc",
            "tram sac",
            "trụ sạc",
            "tru sac",
            "vinfast",
            "trạm sạc xe điện",
            "tram sac xe dien",
            "trạm sạc xe điện vinfast",
            "tram sac xe dien vinfast"
        )

        /**
         * Determines if a station name is generic (e.g. "Trạm sạc VinFast", "VinFast", or empty)
         * and should be enriched/resolved with authentic evcs.vn station naming.
         */
        fun isGenericStationName(name: String?): Boolean {
            if (name.isNullOrBlank()) return true
            val rawTrimmed = name.trim().lowercase()
            if (rawTrimmed in GENERIC_NAMES) return true
            val sanitized = StationNameSanitizer.sanitize(name).trim().lowercase()
            if (sanitized.isBlank()) return true
            if (sanitized in GENERIC_NAMES) return true
            return false
        }
    }

    /**
     * Caches an authentic station name mapping for both ID and coordinates.
     */
    fun cacheStationName(id: String?, latitude: Double, longitude: Double, name: String) {
        val cleanName = StationNameSanitizer.sanitize(name).ifBlank { name }.trim()
        if (cleanName.isBlank() || isGenericStationName(cleanName)) return

        if (!id.isNullOrBlank()) {
            idToNameCache[id.trim().lowercase()] = cleanName
        }
        if (latitude != 0.0 && longitude != 0.0) {
            coordCache.removeIf {
                DistanceCalculator.calculateDistanceMeters(latitude, longitude, it.latitude, it.longitude) <= 50.0
            }
            coordCache.add(CachedStationCoord(latitude, longitude, cleanName, id))
        }
    }

    /**
     * Caches station name from a known [Station] domain object if it is non-generic.
     */
    fun cacheStation(station: Station) {
        cacheStationName(station.id, station.latitude, station.longitude, station.name)
    }

    /**
     * Caches all non-generic stations from a list of raw evcs.vn search results.
     */
    fun cacheSearchResults(stations: List<SearchStationRaw>) {
        for (st in stations) {
            val name = st.stationName
            if (!name.isNullOrBlank() && !isGenericStationName(name)) {
                cacheStationName(
                    id = st.effectiveLocationId,
                    latitude = st.latitude,
                    longitude = st.longitude,
                    name = name
                )
            }
        }
    }

    /**
     * Attempts to resolve an authentic name from memory cache using ID or GPS coordinates (<= 100m).
     */
    fun resolveFromCache(stationId: String?, latitude: Double, longitude: Double): String? {
        if (!stationId.isNullOrBlank()) {
            val cached = idToNameCache[stationId.trim().lowercase()]
            if (cached != null) return cached
        }
        if (latitude != 0.0 && longitude != 0.0) {
            val match = coordCache.filter {
                DistanceCalculator.calculateDistanceMeters(latitude, longitude, it.latitude, it.longitude) <= MAX_COORDINATE_MATCH_METERS
            }.minByOrNull {
                DistanceCalculator.calculateDistanceMeters(latitude, longitude, it.latitude, it.longitude)
            }
            if (match != null) {
                return match.name
            }
        }
        return null
    }

    /**
     * Synchronously resolves and enriches a [Station] name from in-memory cache without network access.
     */
    fun resolveStationSync(station: Station): Station {
        if (!isGenericStationName(station.name)) {
            cacheStation(station)
            val clean = StationNameSanitizer.sanitize(station.name).ifBlank { station.name }
            return station.copy(name = clean)
        }
        val cached = resolveFromCache(station.id, station.latitude, station.longitude)
        return if (cached != null) {
            station.copy(name = cached)
        } else {
            station
        }
    }

    /**
     * Asynchronously resolves the authentic evcs.vn name for a station.
     * 1. If fallbackName is non-generic, returns it immediately (0ms).
     * 2. Checks in-memory cache by ID and coordinates (0ms).
     * 3. Queries evcs.vn search API around coordinates, caches results, and finds matching station.
     * 4. Gracefully degrades to fallbackName on failure.
     */
    suspend fun resolveStationName(
        stationId: String?,
        latitude: Double,
        longitude: Double,
        fallbackName: String = ""
    ): String {
        val cleanFallback = StationNameSanitizer.sanitize(fallbackName).ifBlank { fallbackName }.trim()
        if (!isGenericStationName(cleanFallback)) {
            cacheStationName(stationId, latitude, longitude, cleanFallback)
            return cleanFallback
        }

        val cached = resolveFromCache(stationId, latitude, longitude)
        if (cached != null) {
            return cached
        }

        if (latitude == 0.0 && longitude == 0.0) {
            return cleanFallback.ifBlank { fallbackName }
        }

        try {
            val searchResult = when {
                searchStationsProvider != null -> searchStationsProvider.invoke(latitude, longitude)
                apiClient != null -> apiClient.searchStations(latitude, longitude)
                else -> Result.failure(IllegalStateException("No search provider available"))
            }

            if (searchResult.isSuccess) {
                val results = searchResult.getOrThrow()
                cacheSearchResults(results)

                // 1. Match by locationId
                val matchedById = if (!stationId.isNullOrBlank()) {
                    results.firstOrNull {
                        it.effectiveLocationId.isNotBlank() &&
                                it.effectiveLocationId.equals(stationId.trim(), ignoreCase = true)
                    }
                } else null

                // 2. Match by GPS coordinate proximity <= 100 meters
                val matchedByProximity = if (matchedById == null) {
                    results.filter {
                        DistanceCalculator.calculateDistanceMeters(latitude, longitude, it.latitude, it.longitude) <= MAX_COORDINATE_MATCH_METERS
                    }.minByOrNull {
                        DistanceCalculator.calculateDistanceMeters(latitude, longitude, it.latitude, it.longitude)
                    }
                } else null

                val matched = matchedById ?: matchedByProximity
                val rawName = matched?.stationName
                if (!rawName.isNullOrBlank()) {
                    val sanitized = StationNameSanitizer.sanitize(rawName).ifBlank { rawName }.trim()
                    if (!isGenericStationName(sanitized)) {
                        cacheStationName(stationId ?: matched.effectiveLocationId, latitude, longitude, sanitized)
                        return sanitized
                    }
                }
            }
        } catch (e: Exception) {
            // Graceful resilience: retain existing name without crashing
        }

        return cleanFallback.ifBlank { fallbackName }
    }

    /**
     * Resolves a [Station] instance, updating its name with authentic evcs.vn name.
     */
    suspend fun resolveStation(station: Station): Station {
        if (!isGenericStationName(station.name)) {
            cacheStation(station)
            val clean = StationNameSanitizer.sanitize(station.name).ifBlank { station.name }
            return station.copy(name = clean)
        }
        val resolvedName = resolveStationName(
            stationId = station.id,
            latitude = station.latitude,
            longitude = station.longitude,
            fallbackName = station.name
        )
        return station.copy(name = resolvedName)
    }

    /**
     * Enriches candidate station identified by Here EV API with standard evcs.vn name.
     */
    suspend fun enrichCandidateStation(station: Station): Station {
        return resolveStation(station)
    }

    /**
     * Clears all in-memory station name caches.
     */
    fun clearCache() {
        idToNameCache.clear()
        coordCache.clear()
    }
}
