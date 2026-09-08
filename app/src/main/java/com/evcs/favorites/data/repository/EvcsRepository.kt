package com.evcs.favorites.data.repository

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.api.RateLimitException
import com.evcs.favorites.data.auth.SessionStorage
import com.evcs.favorites.data.model.FavoriteStationRaw
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.SearchStationRaw
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.domain.location.DistanceCalculator
import com.evcs.favorites.util.SingleFlight
import com.evcs.favorites.util.StationNameSanitizer
import com.evcs.favorites.util.VinFastCdnUrlDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import com.evcs.favorites.data.cache.BoundedLruMap
import com.evcs.favorites.data.cache.mapValuesThreadSafe
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
    private val coordinateCache: MutableMap<String, Pair<Double, Double>> = BoundedLruMap(maxCapacity = 500),
    private val coordinateResolver: ((locationId: String) -> Pair<Double, Double>?)? = null,
    private val cacheStorage: SessionStorage? = null,
    private val legacyStorage: SessionStorage? = null,
    private val autoResolveCoordinates: Boolean = false,
    private val delayProvider: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    val singleFlight: SingleFlight = SingleFlight(ioDispatcher),
    private val eagerLoadCache: Boolean = false,
    val firestoreFavoritesRepository: FirestoreFavoritesRepository? = null
) {

    val globalRateLimitedUntil: AtomicLong = AtomicLong(0L)
    companion object {
        // Default coordinates: Hanoi Center (Hoan Kiem)
        const val DEFAULT_LAT = 21.0285
        const val DEFAULT_LON = 105.8542
        const val KEY_OFFLINE_FAVORITES = "evcs_offline_favorites_snapshot"
        const val KEY_COORDINATE_CACHE = "evcs_station_coordinates_cache"
        const val CLUSTER_SEARCH_RADIUS_KM = 15.0

        // Pre-compiled regex for connector power extraction (PERF-UI-02)
        val KW_REGEX = Regex("""(\d+(?:\.\d+)?)\s*kW""", RegexOption.IGNORE_CASE)

        private val parsedConnectorsCache = ConcurrentHashMap<String, List<PowerPort>>()

        /**
         * Clears parsed connectors cache. Primarily used for testing.
         */
        fun clearParsedConnectorsCache() {
            parsedConnectorsCache.clear()
        }

        /**
         * Parses connector string (e.g. "120kW, 60kW, 7kW") into fallback [PowerPort] list
         * with clean connector tags and no synthetic 0/0 counts.
         * Memoizes results to prevent redundant regex evaluation and string splitting.
         */
        fun parseConnectorsToPowers(connectors: String?): List<PowerPort> {
            if (connectors.isNullOrBlank()) return emptyList()

            return parsedConnectorsCache.computeIfAbsent(connectors) { conn ->
                conn.split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { part ->
                        val kwMatch = KW_REGEX.find(part)
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
    }

    private val _favoritesState = MutableStateFlow<List<Station>>(emptyList())
    val favoritesState: StateFlow<List<Station>> = firestoreFavoritesRepository?.favoritesState ?: _favoritesState.asStateFlow()

    private val _favoriteIdsState = MutableStateFlow<Set<String>>(emptySet())
    val favoriteIdsState: StateFlow<Set<String>> = firestoreFavoritesRepository?.favoriteIdsState ?: _favoriteIdsState.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    init {
        if (eagerLoadCache) {
            loadCacheInternal()
            _isInitialized.value = true
        }
    }

    private fun loadCacheInternal() {
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
     * Asynchronously loads cached coordinates and favorites from persistent storage off the main thread.
     * Populates [coordinateCache], [_favoritesState], and [_favoriteIdsState] on the specified [dispatcher].
     */
    suspend fun initializeAsync(dispatcher: CoroutineDispatcher = Dispatchers.IO) = withContext(dispatcher) {
        loadCacheInternal()
        _isInitialized.value = true
    }

    /**
     * Non-suspending helper to trigger cache initialization in the provided [CoroutineScope].
     */
    fun initialize(scope: CoroutineScope, dispatcher: CoroutineDispatcher = Dispatchers.IO): Job {
        return scope.launch(dispatcher) {
            initializeAsync(dispatcher)
        }
    }

    /**
     * Retrieves cached coordinates map from persistent storage.
     */
    fun loadCachedCoordinates(): Map<String, Pair<Double, Double>> {
        var rawJson = cacheStorage?.getString(KEY_COORDINATE_CACHE)
        if (rawJson == null && legacyStorage != null) {
            val legacyJson = legacyStorage.getString(KEY_COORDINATE_CACHE)
            if (legacyJson != null) {
                cacheStorage?.putString(KEY_COORDINATE_CACHE, legacyJson)
                legacyStorage.remove(KEY_COORDINATE_CACHE)
                rawJson = legacyJson
            }
        }
        if (rawJson == null) return emptyMap()
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
            val mapToSave = coordinateCache.mapValuesThreadSafe { CoordinatePair(it.value.first, it.value.second) }
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
        if (firestoreFavoritesRepository != null) {
            return firestoreFavoritesRepository.getCachedFavorites()
        }
        var rawJson = cacheStorage?.getString(KEY_OFFLINE_FAVORITES)
        if (rawJson == null && legacyStorage != null) {
            val legacyJson = legacyStorage.getString(KEY_OFFLINE_FAVORITES)
            if (legacyJson != null) {
                cacheStorage?.putString(KEY_OFFLINE_FAVORITES, legacyJson)
                legacyStorage.remove(KEY_OFFLINE_FAVORITES)
                rawJson = legacyJson
            }
        }
        if (rawJson == null) return emptyList()
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
        if (firestoreFavoritesRepository != null) {
            val cached = firestoreFavoritesRepository.favoritesState.value.ifEmpty {
                firestoreFavoritesRepository.getCachedFavorites()
            }
            val enriched = if (userLat != null && userLon != null) {
                DistanceCalculator.attachDistances(cached, userLat, userLon)
            } else {
                cached
            }
            return Result.success(enriched)
        }
        val latStr = "%.4f".format(Locale.US, userLat ?: 0.0)
        val lonStr = "%.4f".format(Locale.US, userLon ?: 0.0)
        val flightKey = "favorites_${latStr}_${lonStr}_$autoResolveUnknownCoordinates"
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
            val missingFavorites = rawFavorites.filter { fav ->
                val favKey = fav.locationId.trim().lowercase()
                val cached = coordinateCache[favKey] ?: coordinateResolver?.invoke(fav.locationId)
                cached == null || (cached.first == 0.0 && cached.second == 0.0)
            }.distinctBy { it.locationId.trim().lowercase() }

            if (missingFavorites.isNotEmpty()) {
                val semaphore = Semaphore(4)
                val resolvedList = coroutineScope {
                    missingFavorites.map { fav ->
                        async {
                            semaphore.withPermit {
                                val favKey = fav.locationId.trim().lowercase()
                                val resolved = try {
                                    apiClient.fetchStationCoordinates(fav.name, fav.locationId).getOrNull()
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    null
                                }
                                if (resolved != null && (resolved.first != 0.0 || resolved.second != 0.0)) {
                                    favKey to resolved
                                } else {
                                    null
                                }
                            }
                        }
                    }.awaitAll().filterNotNull()
                }

                for ((key, coords) in resolvedList) {
                    coordinateCache[key] = coords
                }
                saveCachedCoordinates()
            }
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
            val decodedImages = VinFastCdnUrlDecoder.decodeList(search.media)
            val fallbackFavImages = fav.image?.let { listOf(it) } ?: emptyList()
            val resolvedImages = if (decodedImages.isNotEmpty()) decodedImages else fallbackFavImages
            val resolvedImage = decodedImages.firstOrNull() ?: fav.image

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
                images = resolvedImages,
                image = resolvedImage,
                isPublic = search.isPublic ?: true,
                isFreeParking = search.isFreeParking ?: true,
                workingTimeDescription = search.workingTimeDescription ?: "24/7",
                evse = search.evse ?: "VinFast"
            )
        } else {
            // Station outside search radius or search failed - graceful fallback
            val fallbackPowers = parseConnectorsToPowers(fav.connectors)
            val fallbackFavImages = fav.image?.let { listOf(it) } ?: emptyList()
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
                images = fallbackFavImages,
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
        if (firestoreFavoritesRepository != null) {
            return@withContext firestoreFavoritesRepository.addFavoriteStation(station)
        }
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
        if (firestoreFavoritesRepository != null) {
            return@withContext firestoreFavoritesRepository.removeFavoriteStation(locationId)
        }
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
    ): Result<List<Station>> {
        if (isGlobalRateLimited()) {
            val repoBlocked = globalRateLimitedUntil.get()
            val apiBlocked = apiClient.globalRateLimitedUntil.get()
            val blockedUntil = maxOf(repoBlocked, apiBlocked)
            val remainingSeconds = ((blockedUntil - System.currentTimeMillis() + 999L) / 1000L).coerceAtLeast(1L)
            return Result.failure(
                RateLimitException(
                    retryAfterSeconds = remainingSeconds,
                    message = "Search nearby VinFast rate limited: Cooldown active for ${remainingSeconds}s"
                )
            )
        }
        return singleFlight.execute("search_${"%.4f".format(Locale.US, lat)}_${"%.4f".format(Locale.US, lon)}") {
            searchNearbyVinFastInternal(lat, lon)
        }
    }

    private suspend fun searchNearbyVinFastInternal(
        lat: Double,
        lon: Double
    ): Result<List<Station>> = withContext(Dispatchers.IO) {
        if (isGlobalRateLimited()) {
            val repoBlocked = globalRateLimitedUntil.get()
            val apiBlocked = apiClient.globalRateLimitedUntil.get()
            val blockedUntil = maxOf(repoBlocked, apiBlocked)
            val remainingSeconds = ((blockedUntil - System.currentTimeMillis() + 999L) / 1000L).coerceAtLeast(1L)
            return@withContext Result.failure(
                RateLimitException(
                    retryAfterSeconds = remainingSeconds,
                    message = "Search nearby VinFast rate limited: Cooldown active for ${remainingSeconds}s"
                )
            )
        }
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

    val decodedImages = VinFastCdnUrlDecoder.decodeList(media)

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
        images = decodedImages,
        image = decodedImages.firstOrNull(),
        isPublic = isPublic ?: true,
        isFreeParking = isFreeParking ?: true,
        workingTimeDescription = workingTimeDescription ?: "24/7",
        distanceKm = dist,
        evse = evse ?: "VinFast"
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

