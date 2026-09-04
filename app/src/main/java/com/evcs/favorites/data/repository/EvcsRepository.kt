package com.evcs.favorites.data.repository

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.api.RateLimitException
import com.evcs.favorites.data.auth.SessionStorage
import com.evcs.favorites.data.cache.ForecastCache
import com.evcs.favorites.data.logging.AppDebugLogger
import com.evcs.favorites.data.logging.DebugLogEntry
import com.evcs.favorites.data.logging.DebugLogLevel
import com.evcs.favorites.data.logging.DebugLogTag
import com.evcs.favorites.data.model.ChargingForecastResponse
import com.evcs.favorites.data.model.FavoriteStationRaw
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.SearchStationRaw
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.parser.StationForecastParser
import com.evcs.favorites.domain.location.DistanceCalculator
import com.evcs.favorites.domain.model.StationForecast
import com.evcs.favorites.util.SingleFlight
import com.evcs.favorites.util.StationNameSanitizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinate pair for JSON serialization in persistent cache.
 */
@Serializable
data class CoordinatePair(val latitude: Double, val longitude: Double)

/**
 * Repository coordinating network fetch of favorite stations and
 * real-time enrichment via targeted cluster-based signed HMAC search API queries,
 * with offline caching support and station coordinate persistence.
 *
 * Implements graceful degradation: if search API fails or times out,
 * saved favorites are still returned with their basic metadata.
 * If offline or network unavailable, returns cached JSON snapshot.
 */
open class EvcsRepository(
    private val apiClient: EvcsApiClient,
    private val coordinateCache: MutableMap<String, Pair<Double, Double>> = mutableMapOf(),
    private val coordinateResolver: ((locationId: String) -> Pair<Double, Double>?)? = null,
    private val cacheStorage: SessionStorage? = null,
    private val autoResolveCoordinates: Boolean = false,
    val forecastCache: ForecastCache = ForecastCache(),
    val forecastSemaphore: Semaphore = Semaphore(2),
    private val delayProvider: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    val singleFlight: SingleFlight = SingleFlight(ioDispatcher)
) {
    val globalRateLimitedUntil: AtomicLong = AtomicLong(0L)
    companion object {
        // Default coordinates: Hanoi Center (Hoan Kiem)
        const val DEFAULT_LAT = 21.0285
        const val DEFAULT_LON = 105.8542
        const val KEY_OFFLINE_FAVORITES = "evcs_offline_favorites_snapshot"
        const val KEY_COORDINATE_CACHE = "evcs_station_coordinates_cache"
        const val CLUSTER_SEARCH_RADIUS_KM = 15.0

        /**
         * Parses connector string (e.g. "120kW, 60kW, 7kW") into fallback [PowerPort] list
         * with clean connector tags and no synthetic 0/0 counts.
         */
        fun parseConnectorsToPowers(connectors: String?): List<PowerPort> {
            if (connectors.isNullOrBlank()) return emptyList()

            return connectors.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { part ->
                    val kwMatch = Regex("""(\d+(?:\.\d+)?)\s*kW""", RegexOption.IGNORE_CASE).find(part)
                    val watts = kwMatch?.let {
                        (it.groupValues[1].toDoubleOrNull() ?: 0.0) * 1000.0
                    }?.toLong() ?: 0L

                    PowerPort(
                        typeWatts = watts,
                        label = part,
                        availablePlugs = 0,
                        totalPlugs = 0,
                        displayString = part
                    )
                }
        }
    }

    private val _favoritesState = MutableStateFlow<List<Station>>(emptyList())
    val favoritesState: StateFlow<List<Station>> = _favoritesState.asStateFlow()

    private val _favoriteIdsState = MutableStateFlow<Set<String>>(emptySet())
    val favoriteIdsState: StateFlow<Set<String>> = _favoriteIdsState.asStateFlow()

    init {
        val persisted = loadCachedCoordinates()
        for ((k, v) in persisted) {
            if (!coordinateCache.containsKey(k)) {
                coordinateCache[k] = v
            }
        }
        val cached = getCachedFavorites()
        if (cached.isNotEmpty()) {
            _favoritesState.value = cached
            _favoriteIdsState.value = cached.map { it.id }.toSet()
        }
    }

    /**
     * Retrieves cached coordinates map from persistent storage.
     */
    fun loadCachedCoordinates(): Map<String, Pair<Double, Double>> {
        val rawJson = cacheStorage?.getString(KEY_COORDINATE_CACHE) ?: return emptyMap()
        return try {
            val map = EvcsApiClient.json.decodeFromString<Map<String, CoordinatePair>>(rawJson)
            map.mapValues { Pair(it.value.latitude, it.value.longitude) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * Persists coordinateCache to storage.
     */
    fun saveCachedCoordinates() {
        try {
            val mapToSave = coordinateCache.mapValues { CoordinatePair(it.value.first, it.value.second) }
            val json = EvcsApiClient.json.encodeToString(mapToSave)
            cacheStorage?.putString(KEY_COORDINATE_CACHE, json)
        } catch (e: Exception) {
            // Safe degrade
        }
    }

    /**
     * Clears cached coordinates from memory and persistent storage.
     */
    fun clearCoordinateCache() {
        coordinateCache.clear()
        cacheStorage?.remove(KEY_COORDINATE_CACHE)
    }

    /**
     * Returns an immutable copy of current coordinate cache.
     */
    fun getCachedCoordinates(): Map<String, Pair<Double, Double>> = coordinateCache.toMap()

    /**
     * Retrieves cached favorites list from persistent storage if available.
     */
    fun getCachedFavorites(): List<Station> {
        val rawJson = cacheStorage?.getString(KEY_OFFLINE_FAVORITES) ?: return emptyList()
        return try {
            EvcsApiClient.json.decodeFromString<List<Station>>(rawJson)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Saves a JSON snapshot of the favorites list to offline storage.
     */
    fun saveCachedFavorites(stations: List<Station>) {
        try {
            val json = EvcsApiClient.json.encodeToString(stations)
            cacheStorage?.putString(KEY_OFFLINE_FAVORITES, json)
        } catch (e: Exception) {
            // Safe degrade
        }
    }

    /**
     * Clears cached favorites snapshot and resets in-memory favorites flows.
     */
    fun clearOfflineCache() {
        cacheStorage?.remove(KEY_OFFLINE_FAVORITES)
        _favoritesState.value = emptyList()
        _favoriteIdsState.value = emptySet()
    }

    /**
     * Fetches user's favorite stations, enriches them with real-time plug status
     * and GPS coordinates from targeted geographic cluster searches via the signed HMAC search API,
     * and handles fallbacks. If network fails, gracefully serves from offline cache.
     *
     * @param userLat User's current latitude, if available.
     * @param userLon User's current longitude, if available.
     * @param autoResolveUnknownCoordinates When true, fetches station detail HTML for stations with unknown coordinates.
     * @return Result containing list of enriched domain [Station] models.
     */
    open suspend fun getFavorites(
        userLat: Double? = null,
        userLon: Double? = null,
        autoResolveUnknownCoordinates: Boolean = autoResolveCoordinates
    ): Result<List<Station>> {
        val flightKey = "favorites_${userLat ?: 0.0}_${userLon ?: 0.0}_$autoResolveUnknownCoordinates"
        return singleFlight.execute(flightKey) {
            getFavoritesInternal(userLat, userLon, autoResolveUnknownCoordinates)
        }
    }

    private suspend fun getFavoritesInternal(
        userLat: Double?,
        userLon: Double?,
        autoResolveUnknownCoordinates: Boolean
    ): Result<List<Station>> {
        // 1. Fetch saved favorites from POST /favorite.html
        val favResult = apiClient.fetchFavorites()
        if (favResult.isFailure) {
            val cached = getCachedFavorites()
            if (cached.isNotEmpty()) {
                val enrichedCached = if (userLat != null && userLon != null) {
                    cached.map { st ->
                        if (st.latitude != 0.0 || st.longitude != 0.0) {
                            DistanceCalculator.attachDistance(st, userLat, userLon)
                        } else st
                    }
                } else {
                    cached
                }
                _favoritesState.value = enrichedCached
                _favoriteIdsState.value = enrichedCached.map { it.id }.toSet()
                return Result.success(enrichedCached)
            }
            return Result.failure(favResult.exceptionOrNull()!!)
        }

        val favResponse = favResult.getOrThrow()
        val rawFavorites = favResponse.server.orEmpty()
        if (rawFavorites.isEmpty()) {
            _favoritesState.value = emptyList()
            _favoriteIdsState.value = emptySet()
            saveCachedFavorites(emptyList())
            return Result.success(emptyList())
        }

        // 2. Resolve coordinates for favorite stations if auto-resolve is enabled
        if (autoResolveUnknownCoordinates) {
            for (fav in rawFavorites) {
                val favKey = fav.locationId.trim().lowercase()
                val cached = coordinateCache[favKey] ?: coordinateResolver?.invoke(fav.locationId)
                if (cached == null || (cached.first == 0.0 && cached.second == 0.0)) {
                    val resolved = try {
                        apiClient.fetchStationCoordinates(fav.name, fav.locationId).getOrNull()
                    } catch (e: Exception) {
                        null
                    }
                    if (resolved != null && (resolved.first != 0.0 || resolved.second != 0.0)) {
                        coordinateCache[favKey] = resolved
                    }
                }
            }
            saveCachedCoordinates()
        }

        // 3. Collect known coordinates for favorite stations
        val knownCoords = mutableListOf<Pair<Double, Double>>()
        for (fav in rawFavorites) {
            val favKey = fav.locationId.trim().lowercase()
            val coords = coordinateCache[favKey] ?: coordinateResolver?.invoke(fav.locationId)
            if (coords != null && (coords.first != 0.0 || coords.second != 0.0)) {
                knownCoords.add(coords)
            }
        }

        // 4. Determine geographic clusters for search queries
        val pointsToCluster = if (userLat != null && userLon != null && userLat != 0.0 && userLon != 0.0) {
            knownCoords + Pair(userLat, userLon)
        } else {
            knownCoords
        }

        val searchCenters = if (pointsToCluster.isNotEmpty()) {
            DistanceCalculator.clusterCoordinates(pointsToCluster, maxDistanceKm = CLUSTER_SEARCH_RADIUS_KM)
        } else {
            listOf(Pair(DEFAULT_LAT, DEFAULT_LON))
        }

        // 5. Execute targeted search queries per cluster asynchronously in Dispatchers.IO
        val searchStations = withContext(Dispatchers.IO) {
            coroutineScope {
                searchCenters.map { (lat, lon) ->
                    async {
                        apiClient.searchStations(lat, lon).getOrDefault(emptyList())
                    }
                }.awaitAll().flatten()
            }
        }

        // Cache coordinates from search stations
        for (st in searchStations) {
            val key = st.effectiveLocationId.trim().lowercase()
            if (key.isNotEmpty() && (st.latitude != 0.0 || st.longitude != 0.0)) {
                coordinateCache[key] = Pair(st.latitude, st.longitude)
            }
        }
        saveCachedCoordinates()

        // 6. Merge favorites with search stations
        val searchMap = searchStations.associateBy { it.effectiveLocationId.trim().lowercase() }

        var domainStations = rawFavorites.map { fav ->
            val favKey = fav.locationId.trim().lowercase()
            val matchedSearch = searchMap[favKey]

            mergeToDomainStation(fav, matchedSearch)
        }

        // 7. Attach distances if user location is available
        if (userLat != null && userLon != null) {
            domainStations = DistanceCalculator.attachDistances(domainStations, userLat, userLon)
        }

        // 8. Persist offline snapshot
        saveCachedFavorites(domainStations)
        _favoritesState.value = domainStations
        _favoriteIdsState.value = domainStations.map { it.id }.toSet()

        return Result.success(domainStations)
    }

    /**
     * Resolves center coordinates for the search API:
     * 1. User's current GPS location if provided
     * 2. First favorite with cached coordinates
     * 3. Default fallback (Hanoi)
     */
    private fun determineSearchCenter(
        userLat: Double?,
        userLon: Double?,
        favorites: List<FavoriteStationRaw>
    ): Pair<Double, Double> {
        if (userLat != null && userLon != null && userLat != 0.0 && userLon != 0.0) {
            return Pair(userLat, userLon)
        }

        for (fav in favorites) {
            val cached = coordinateCache[fav.locationId.trim().lowercase()]
                ?: coordinateResolver?.invoke(fav.locationId)
            if (cached != null && (cached.first != 0.0 || cached.second != 0.0)) {
                return cached
            }
        }

        return Pair(DEFAULT_LAT, DEFAULT_LON)
    }

    /**
     * Merges a raw favorite with matched search station details.
     * Falls back to coordinate cache and parsed connectors if search data is missing.
     */
    internal fun mergeToDomainStation(
        fav: FavoriteStationRaw,
        search: SearchStationRaw?
    ): Station {
        val favKey = fav.locationId.trim().lowercase()

        // Resolve coordinates
        val (latitude, longitude) = when {
            search != null && (search.latitude != 0.0 || search.longitude != 0.0) -> {
                coordinateCache[favKey] = Pair(search.latitude, search.longitude)
                Pair(search.latitude, search.longitude)
            }
            coordinateCache.containsKey(favKey) -> {
                coordinateCache[favKey]!!
            }
            else -> {
                coordinateResolver?.invoke(fav.locationId) ?: Pair(0.0, 0.0)
            }
        }

        return if (search != null) {
            val domainPowers = search.evsePowers.map { it.toDomainPowerPort() }
            val availablePlugs = domainPowers.sumOf { it.availablePlugs }
            val totalPlugs = domainPowers.sumOf { it.totalPlugs }

            Station(
                id = fav.locationId,
                name = StationNameSanitizer.sanitize(search.stationName?.ifBlank { fav.name } ?: fav.name),
                address = search.stationAddress?.ifBlank { fav.address } ?: fav.address,
                latitude = latitude,
                longitude = longitude,
                summary = fav.summary ?: search.workingTimeDescription ?: "24/7",
                connectors = fav.connectors ?: domainPowers.joinToString(", ") { it.label },
                depotStatus = search.depotStatus ?: "Normal",
                powers = domainPowers,
                totalAvailablePlugs = availablePlugs,
                totalPlugs = totalPlugs,
                image = fav.image ?: search.media?.firstOrNull(),
                isPublic = search.isPublic ?: true,
                isFreeParking = search.isFreeParking ?: true,
                workingTimeDescription = search.workingTimeDescription ?: "24/7"
            )
        } else {
            // Station outside search radius or search failed - graceful fallback
            val fallbackPowers = parseConnectorsToPowers(fav.connectors)
            Station(
                id = fav.locationId,
                name = StationNameSanitizer.sanitize(fav.name),
                address = fav.address,
                latitude = latitude,
                longitude = longitude,
                summary = fav.summary ?: "Mở 24/7",
                connectors = fav.connectors ?: "",
                depotStatus = "Unknown",
                powers = fallbackPowers,
                totalAvailablePlugs = 0,
                totalPlugs = fallbackPowers.sumOf { it.totalPlugs },
                image = fav.image
            )
        }
    }

    /**
     * Appends or updates a favorite station in the in-memory state and persistent cache,
     * then dispatches cloud sync via [EvcsApiClient.saveFavorites].
     * Rolls back in-memory state and persistent storage if cloud sync fails.
     */
    open suspend fun addFavoriteStation(station: Station): Result<Unit> = withContext(ioDispatcher) {
        val previousFavorites = _favoritesState.value
        val previousIds = _favoriteIdsState.value
        try {
            val current = previousFavorites.toMutableList()
            val existingIndex = current.indexOfFirst { it.id.equals(station.id, ignoreCase = true) }
            if (existingIndex >= 0) {
                current[existingIndex] = station
            } else {
                current.add(station)
            }
            _favoritesState.value = current
            _favoriteIdsState.value = current.map { it.id }.toSet()
            saveCachedFavorites(current)

            val rawFavorites = current.map { it.toFavoriteStationRaw() }
            val syncResult = apiClient.saveFavorites(rawFavorites)
            if (syncResult.isFailure) {
                _favoritesState.value = previousFavorites
                _favoriteIdsState.value = previousIds
                saveCachedFavorites(previousFavorites)
                return@withContext Result.failure(
                    syncResult.exceptionOrNull() ?: IOException("Failed to sync favorites to cloud")
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _favoritesState.value = previousFavorites
            _favoriteIdsState.value = previousIds
            saveCachedFavorites(previousFavorites)
            Result.failure(e)
        }
    }

    /**
     * Removes a station from the in-memory favorites state and persistent cache,
     * then dispatches cloud sync via [EvcsApiClient.saveFavorites].
     * Rolls back in-memory state and persistent storage if cloud sync fails.
     */
    open suspend fun removeFavoriteStation(locationId: String): Result<Unit> = withContext(ioDispatcher) {
        val previousFavorites = _favoritesState.value
        val previousIds = _favoriteIdsState.value
        try {
            val current = previousFavorites.toMutableList()
            current.removeAll { it.id.equals(locationId, ignoreCase = true) }
            _favoritesState.value = current
            _favoriteIdsState.value = current.map { it.id }.toSet()
            saveCachedFavorites(current)

            val rawFavorites = current.map { it.toFavoriteStationRaw() }
            val syncResult = apiClient.saveFavorites(rawFavorites)
            if (syncResult.isFailure) {
                _favoritesState.value = previousFavorites
                _favoriteIdsState.value = previousIds
                saveCachedFavorites(previousFavorites)
                return@withContext Result.failure(
                    syncResult.exceptionOrNull() ?: IOException("Failed to sync favorites to cloud")
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _favoritesState.value = previousFavorites
            _favoriteIdsState.value = previousIds
            saveCachedFavorites(previousFavorites)
            Result.failure(e)
        }
    }

    /**
     * Searches for nearby VinFast charging stations around the specified GPS coordinates,
     * updates the in-memory and persistent coordinate cache with any returned station positions,
     * and maps the raw search results to domain [Station] models with calculated distance.
     *
     * @param lat User's latitude
     * @param lon User's longitude
     * @return Result containing list of domain [Station] models.
     */
    open suspend fun searchNearbyVinFast(
        lat: Double,
        lon: Double
    ): Result<List<Station>> = singleFlight.execute("search_${lat}_${lon}") {
        searchNearbyVinFastInternal(lat, lon)
    }

    private suspend fun searchNearbyVinFastInternal(
        lat: Double,
        lon: Double
    ): Result<List<Station>> = withContext(Dispatchers.IO) {
        val searchResult = apiClient.searchStations(latitude = lat, longitude = lon)
        if (searchResult.isFailure) {
            return@withContext Result.failure(
                searchResult.exceptionOrNull() ?: IOException("Failed to search nearby VinFast stations")
            )
        }

        val rawStations = searchResult.getOrThrow()

        // Cache coordinates from search stations
        for (st in rawStations) {
            val key = st.effectiveLocationId.trim().lowercase()
            if (key.isNotEmpty() && (st.latitude != 0.0 || st.longitude != 0.0)) {
                coordinateCache[key] = Pair(st.latitude, st.longitude)
            }
        }
        saveCachedCoordinates()

        val domainStations = rawStations.map { raw ->
            raw.toDomainStation(userLat = lat, userLon = lon)
        }

        Result.success(domainStations)
    }

    /**
     * Checks if an error represents a transient failure eligible for retry.
     */
    private fun isTransientFailure(throwable: Throwable): Boolean {
        if (throwable is RateLimitException) return false
        val msg = throwable.message.orEmpty()
        if (msg.contains("HTTP 400") ||
            msg.contains("HTTP 401") ||
            msg.contains("HTTP 403") ||
            msg.contains("HTTP 404") ||
            msg.contains("HTTP 429") ||
            msg.contains("1015")
        ) {
            return false
        }
        return throwable is IOException ||
                msg.contains("HTTP 5") ||
                msg.contains("timeout", ignoreCase = true)
    }

    /**
     * Checks whether global rate-limit cooldown is currently active.
     */
    fun isGlobalRateLimited(): Boolean {
        val repoBlocked = globalRateLimitedUntil.get()
        val apiBlocked = apiClient.globalRateLimitedUntil.get()
        val now = System.currentTimeMillis()
        return (repoBlocked > 0L && now < repoBlocked) || (apiBlocked > 0L && now < apiBlocked)
    }

    /**
     * Resets active global rate limit cooldown.
     */
    fun resetRateLimitCooldown() {
        globalRateLimitedUntil.set(0L)
        apiClient.resetRateLimitCooldown()
    }

    /**
     * Clears all cached station forecasts and failure cooldowns.
     */
    fun clearForecastCache() {
        forecastCache.clearAll()
    }

    /**
     * Invalidates forecast cache and failure cooldown for a specific station.
     */
    fun invalidateForecast(stationId: String) {
        forecastCache.invalidate(stationId)
    }

    /**
     * Fetches charging forecast for a station with in-memory TTL caching (3 minutes),
     * failure cooldown (1 minute), and transient failure exponential backoff retry.
     * Silent degradation: guarantees zero exceptions propagated to UI, returning Result.success(null) on error.
     *
     * @param station Target station to query forecast for.
     * @param forceRefresh When true, clears cache and failure cooldown for this station before fetching.
     * @return Result containing StationForecast if available, or null if no forecast or in failure/cooldown.
     */
    open suspend fun fetchStationForecast(
        station: Station,
        forceRefresh: Boolean = false
    ): Result<StationForecast?> = singleFlight.execute("forecast_${station.id}") {
        fetchStationForecastInternal(station, forceRefresh)
    }

    private suspend fun fetchStationForecastInternal(
        station: Station,
        forceRefresh: Boolean = false
    ): Result<StationForecast?> = withContext(ioDispatcher) {
        try {
            if (isGlobalRateLimited()) {
                return@withContext Result.success(null)
            }

            if (forceRefresh) {
                forecastCache.invalidate(station.id)
            } else {
                val cached = forecastCache.get(station.id)
                if (cached != null) {
                    return@withContext Result.success(cached)
                }
            }

            if (forecastCache.isInCooldown(station.id)) {
                return@withContext Result.success(null)
            }

            forecastSemaphore.withPermit {
                if (isGlobalRateLimited()) {
                    return@withPermit Result.success(null)
                }

                // Re-check cache in case a concurrent request already populated it
                if (!forceRefresh) {
                    val cached = forecastCache.get(station.id)
                    if (cached != null) {
                        return@withPermit Result.success(cached)
                    }
                }

                val retryDelays = listOf(1000L, 2000L)
                var result: Result<ChargingForecastResponse>? = null

                for (attempt in 0..retryDelays.size) {
                    if (attempt > 0) {
                        val baseDelay = retryDelays[attempt - 1]
                        val jitter = kotlin.random.Random.nextLong(0, 301)
                        delayProvider(baseDelay + jitter)
                    }

                    result = apiClient.fetchChargingForecast(station.name, station.id, isVinFast = true)
                    if (result.isSuccess) {
                        break
                    }

                    val ex = result.exceptionOrNull() ?: IOException("Network error")
                    if (!isTransientFailure(ex)) {
                        break
                    }
                }

                if (result == null || result.isFailure) {
                    val ex = result?.exceptionOrNull()
                    if (ex is CancellationException) throw ex
                    val errorMsg = ex?.message.orEmpty()
                    if (ex is RateLimitException) {
                        val cooldownSec = ex.retryAfterSeconds
                        val cooldownUntil = System.currentTimeMillis() + (cooldownSec * 1000L)
                        globalRateLimitedUntil.set(cooldownUntil)
                        apiClient.globalRateLimitedUntil.set(cooldownUntil)
                        AppDebugLogger.log(
                            DebugLogEntry(
                                tag = DebugLogTag.FORECAST,
                                level = DebugLogLevel.WARN,
                                message = "Cloudflare giới hạn tần suất (HTTP 429). Tạm dừng yêu cầu dự báo sạc nền trong $cooldownSec giây.",
                                errorDetails = errorMsg
                            )
                        )
                    } else if (errorMsg.contains("429") || errorMsg.contains("1015")) {
                        val cooldownUntil = System.currentTimeMillis() + 60_000L
                        globalRateLimitedUntil.set(cooldownUntil)
                        apiClient.globalRateLimitedUntil.set(cooldownUntil)
                        AppDebugLogger.log(
                            DebugLogEntry(
                                tag = DebugLogTag.FORECAST,
                                level = DebugLogLevel.WARN,
                                message = "Cloudflare giới hạn tần suất (HTTP 429). Tạm dừng yêu cầu dự báo sạc nền trong 60 giây.",
                                errorDetails = errorMsg
                            )
                        )
                    }

                    forecastCache.recordFailure(station.id)
                    AppDebugLogger.log(
                        DebugLogEntry(
                            tag = DebugLogTag.FORECAST,
                            level = DebugLogLevel.ERROR,
                            message = "Lỗi mạng/token dự báo sạc trạm ${station.name}: ${ex?.message}",
                            errorDetails = ex?.message ?: ex?.toString()
                        )
                    )
                    return@withPermit Result.success(null)
                }

                val forecastResponse = result.getOrNull()
                val ticker = forecastResponse?.ticker.orEmpty()
                val forecast = if (ticker.isNotBlank()) {
                    try {
                        val parsed = StationForecastParser.parseForecastFromHtml(ticker)
                        if (parsed != null) {
                            AppDebugLogger.log(
                                DebugLogEntry(
                                    tag = DebugLogTag.FORECAST,
                                    level = DebugLogLevel.SUCCESS,
                                    message = parsed.formatSingleSummary(),
                                    parsedForecastSummary = parsed.formatSingleSummary(),
                                    responseSnippet = ticker
                                )
                            )
                        } else {
                            val isTeaserOrLocked = ticker.contains("amd-locked", ignoreCase = true) ||
                                    ticker.contains("cổng sạc trống", ignoreCase = true)
                            if (isTeaserOrLocked) {
                                AppDebugLogger.log(
                                    DebugLogEntry(
                                        tag = DebugLogTag.FORECAST,
                                        level = DebugLogLevel.INFO,
                                        message = "Trạm chưa có xe sạc hoặc yêu cầu tài khoản EVCS Go (ticker locked)",
                                        responseSnippet = ticker
                                    )
                                )
                            } else {
                                AppDebugLogger.log(
                                    DebugLogEntry(
                                        tag = DebugLogTag.FORECAST,
                                        level = DebugLogLevel.WARN,
                                        message = "Không phân tích được ticker sạc (parser trả về null)",
                                        responseSnippet = ticker
                                    )
                                )
                            }
                        }
                        parsed
                    } catch (e: Exception) {
                        AppDebugLogger.log(
                            DebugLogEntry(
                                tag = DebugLogTag.FORECAST,
                                level = DebugLogLevel.WARN,
                                message = "Phân tích cú pháp ticker sạc thất bại: ${e.message}",
                                responseSnippet = ticker,
                                errorDetails = e.message ?: e.toString()
                            )
                        )
                        null
                    }
                } else {
                    AppDebugLogger.log(
                        DebugLogEntry(
                            tag = DebugLogTag.FORECAST,
                            level = DebugLogLevel.INFO,
                            message = "Không có dữ liệu ticker sạc (trụ có thể đang rảnh hoặc không có phiên sạc)",
                            responseSnippet = ticker
                        )
                    )
                    null
                }

                if (forecast != null) {
                    forecastCache.put(station.id, forecast)
                }

                Result.success(forecast)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            forecastCache.recordFailure(station.id)
            AppDebugLogger.log(
                DebugLogEntry(
                    tag = DebugLogTag.FORECAST,
                    level = DebugLogLevel.ERROR,
                    message = "Lỗi ngoại lệ khi xử lý dự báo sạc trạm ${station.name}: ${e.message}",
                    errorDetails = e.message ?: e.toString()
                )
            )
            Result.success(null)
        }
    }

    /**
     * Batch enriches a list of stations with real-time charging forecasts.
     * Concurrency is throttled to at most 2 simultaneous network requests via Semaphore(2)
     * and staggered with pacing delay (250ms) to avoid triggering server rate limits.
     * Instantly aborts the entire batch upon encountering HTTP 429 / RateLimitException.
     *
     * @param stations Target stations to enrich.
     * @param forceRefresh When true, invalidates cache and cooldowns for fresh fetching.
     * @param onStationUpdated Optional callback invoked as each station is progressively updated.
     * @return Complete list of stations enriched with forecasts where available.
     */
    open suspend fun enrichStationsWithForecast(
        stations: List<Station>,
        forceRefresh: Boolean = false,
        onStationUpdated: ((Station) -> Unit)? = null
    ): List<Station> = withContext(ioDispatcher) {
        if (stations.isEmpty() || isGlobalRateLimited()) return@withContext stations

        val enrichedStations = java.util.concurrent.atomic.AtomicReferenceArray(stations.toTypedArray())

        try {
            coroutineScope {
                stations.mapIndexed { index, station ->
                    async {
                        if (isGlobalRateLimited()) {
                            this@coroutineScope.cancel(CancellationException("Global rate limit active, aborting forecast batch"))
                            return@async station
                        }
                        if (index > 0) {
                            delayProvider(index * 250L)
                        }
                        if (isGlobalRateLimited()) {
                            this@coroutineScope.cancel(CancellationException("Global rate limit active, aborting forecast batch"))
                            return@async station
                        }

                        val result = fetchStationForecast(station, forceRefresh)
                        val forecast = result.getOrNull()
                        val enriched = if (forecast != null) {
                            station.copy(forecast = forecast)
                        } else {
                            station
                        }
                        enrichedStations.set(index, enriched)
                        onStationUpdated?.invoke(enriched)

                        if (isGlobalRateLimited()) {
                            this@coroutineScope.cancel(CancellationException("Rate limit 429 triggered during forecast batch, aborting"))
                        }
                        enriched
                    }
                }.awaitAll()
            }
        } catch (e: CancellationException) {
            AppDebugLogger.log(
                DebugLogEntry(
                    tag = DebugLogTag.FORECAST,
                    level = DebugLogLevel.WARN,
                    message = "Hủy toàn bộ batch dự báo sạc do kích hoạt giới hạn tần suất (HTTP 429)",
                    errorDetails = e.message
                )
            )
        }

        (0 until stations.size).map { i -> enrichedStations.get(i) }
    }
}

/**
 * Transforms a raw search station [SearchStationRaw] directly to a domain [Station] model.
 */
fun SearchStationRaw.toDomainStation(
    userLat: Double? = null,
    userLon: Double? = null
): Station {
    val domainPowers = evsePowers.map { it.toDomainPowerPort() }
    val availablePlugs = domainPowers.sumOf { it.availablePlugs }
    val totalPlugs = domainPowers.sumOf { it.totalPlugs }
    val connectorsStr = domainPowers.joinToString(", ") { it.label }

    val dist = if (userLat != null && userLon != null && latitude != 0.0 && longitude != 0.0) {
        DistanceCalculator.calculateDistanceKm(userLat, userLon, latitude, longitude)
    } else {
        distance
    }

    return Station(
        id = effectiveLocationId,
        name = StationNameSanitizer.sanitize(stationName),
        address = stationAddress.orEmpty(),
        latitude = latitude,
        longitude = longitude,
        summary = workingTimeDescription ?: "24/7",
        connectors = connectorsStr,
        depotStatus = depotStatus ?: "Normal",
        powers = domainPowers,
        totalAvailablePlugs = availablePlugs,
        totalPlugs = totalPlugs,
        image = media?.firstOrNull(),
        isPublic = isPublic ?: true,
        isFreeParking = isFreeParking ?: true,
        workingTimeDescription = workingTimeDescription ?: "24/7",
        distanceKm = dist
    )
}

/**
 * Transforms a domain [Station] to [FavoriteStationRaw] for cloud serialization.
 */
fun Station.toFavoriteStationRaw(): FavoriteStationRaw {
    return FavoriteStationRaw(
        locationId = id,
        name = StationNameSanitizer.sanitize(name),
        address = address,
        summary = summary.ifBlank { null },
        connectors = connectors.ifBlank { null },
        image = image
    )
}

/**
 * Typealias representing the repository per the Phase 05 specification.
 */
typealias FavoritesRepository = EvcsRepository

