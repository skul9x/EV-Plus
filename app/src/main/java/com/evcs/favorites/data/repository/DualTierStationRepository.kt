package com.evcs.favorites.data.repository

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.SessionStorage
import com.evcs.favorites.data.cache.BoundedLruMap
import com.evcs.favorites.data.logging.AppDebugLogger
import com.evcs.favorites.data.logging.DebugLogLevel
import com.evcs.favorites.data.logging.DebugLogTag
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.network.vinfast.VinFastApiException
import com.evcs.favorites.data.network.vinfast.VinFastCAppApiClient
import com.evcs.favorites.data.network.vinfast.VinFastStationMapper
import com.evcs.favorites.domain.location.DistanceCalculator
import com.evcs.favorites.util.SingleFlight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Dual-tier station repository providing automated, seamless failover between
 * Tier-1 (Direct VinFast CAPP API with real-time plug status) and
 * Tier-2 (Community EVCS HMAC-signed aggregator fallback).
 *
 * Extends [EvcsRepository] to guarantee 100% binary, constructor, and ABI compatibility
 * with all existing ViewModels, DI providers, and test suites.
 */
open class DualTierStationRepository(
    apiClient: EvcsApiClient,
    val vinFastApiClient: VinFastCAppApiClient = VinFastCAppApiClient(),
    val stationMapper: VinFastStationMapper = VinFastStationMapper,
    coordinateCache: MutableMap<String, Pair<Double, Double>> = BoundedLruMap(maxCapacity = 500),
    coordinateResolver: ((locationId: String) -> Pair<Double, Double>?)? = null,
    cacheStorage: SessionStorage? = null,
    legacyStorage: SessionStorage? = null,
    autoResolveCoordinates: Boolean = false,
    delayProvider: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    singleFlight: SingleFlight = SingleFlight(ioDispatcher),
    eagerLoadCache: Boolean = false,
    firestoreFavoritesRepository: FirestoreFavoritesRepository? = null
) : EvcsRepository(
    apiClient = apiClient,
    coordinateCache = coordinateCache,
    coordinateResolver = coordinateResolver,
    cacheStorage = cacheStorage,
    legacyStorage = legacyStorage,
    autoResolveCoordinates = autoResolveCoordinates,
    delayProvider = delayProvider,
    ioDispatcher = ioDispatcher,
    singleFlight = singleFlight,
    eagerLoadCache = eagerLoadCache,
    firestoreFavoritesRepository = firestoreFavoritesRepository
) {

    companion object {
        const val SOURCE_TIER_VINFAST_DIRECT = "VINFAST_DIRECT"
        const val SOURCE_TIER_EVCS_FALLBACK = "EVCS_FALLBACK"
    }

    /**
     * Searches nearby VinFast charging stations with dual-tier fault tolerance.
     * Tier 1: VinFast Connected Car (CAPP) API with 5s fast-fail timeout.
     * Tier 2: EVCS community signed HMAC search API fallback.
     */
    override suspend fun searchNearbyVinFast(
        lat: Double,
        lon: Double
    ): Result<List<Station>> = singleFlight.execute("dual_search_${lat}_${lon}") {
        searchNearbyVinFastInternal(lat, lon)
    }

    private suspend fun searchNearbyVinFastInternal(
        lat: Double,
        lon: Double
    ): Result<List<Station>> = withContext(ioDispatcher) {
        // --- TIER 1: Direct VinFast CAPP API ---
        val tier1Result = runCatching {
            vinFastApiClient.searchStations(lat = lat, lon = lon, page = 0, size = 50)
        }.fold(
            onSuccess = { it },
            onFailure = { e ->
                if (e is CancellationException) throw e
                Result.failure(e)
            }
        )

        if (tier1Result.isSuccess) {
            val dtos = tier1Result.getOrThrow()
            val domainStations = stationMapper.toDomainStations(dtos, userLat = lat, userLon = lon)
                .map { it.copy(sourceTier = SOURCE_TIER_VINFAST_DIRECT) }

            // Cache geographic coordinates for future lookups
            for (st in domainStations) {
                val key = st.id.trim().lowercase()
                if (key.isNotEmpty() && (st.latitude != 0.0 || st.longitude != 0.0)) {
                    coordinateCache[key] = Pair(st.latitude, st.longitude)
                }
            }
            saveCachedCoordinates()

            AppDebugLogger.log(
                tag = DebugLogTag.SEARCH,
                level = DebugLogLevel.SUCCESS,
                message = "Tier 1 VinFast CAPP API search succeeded with ${domainStations.size} car stations.",
                endpointUrl = "${vinFastApiClient.baseUrl}/ccarcharging/api/v1/stations/search"
            )
            return@withContext Result.success(domainStations)
        }

        // --- TIER 2: Fallback to EVCS Community Aggregator ---
        val tier1Exception = tier1Result.exceptionOrNull()
        val errorDetails = when (tier1Exception) {
            is VinFastApiException -> "Code: ${tier1Exception.code}, Message: ${tier1Exception.message}"
            else -> tier1Exception?.message ?: "Unknown Tier 1 error"
        }

        AppDebugLogger.log(
            tag = DebugLogTag.SEARCH,
            level = DebugLogLevel.WARN,
            message = "Tier 1 VinFast CAPP API search failed ($errorDetails). Falling back to Tier 2 EVCS aggregator.",
            errorDetails = tier1Exception?.stackTraceToString() ?: errorDetails
        )

        val fallbackResult = runCatching {
            super.searchNearbyVinFast(lat, lon)
        }.fold(
            onSuccess = { it },
            onFailure = { e ->
                if (e is CancellationException) throw e
                Result.failure(e)
            }
        )

        if (fallbackResult.isSuccess) {
            val fallbackStations = fallbackResult.getOrThrow()
            val sanitizedStations = sanitizeFallbackStations(fallbackStations)
            Result.success(sanitizedStations)
        } else {
            // Both Tiers Failed -> Graceful Failure Contract
            val fallbackError = fallbackResult.exceptionOrNull() ?: Exception("Both Tier 1 and Tier 2 searches failed")
            AppDebugLogger.log(
                tag = DebugLogTag.SEARCH,
                level = DebugLogLevel.ERROR,
                message = "Both Tier 1 and Tier 2 station searches failed.",
                errorDetails = fallbackError.message
            )
            Result.failure(fallbackError)
        }
    }

    /**
     * Fetches and enriches favorite stations with real-time plug status and coordinates.
     * Uses Tier-1 VinFast CAPP API for live telemetry when favorite IDs are available,
     * seamlessly falling back to Tier-2 EVCS aggregator on failure.
     */
    override suspend fun getFavorites(
        userLat: Double?,
        userLon: Double?,
        autoResolveUnknownCoordinates: Boolean
    ): Result<List<Station>> = singleFlight.execute("dual_favorites_${userLat ?: 0.0}_${userLon ?: 0.0}") {
        getFavoritesInternal(userLat, userLon, autoResolveUnknownCoordinates)
    }

    private suspend fun getFavoritesInternal(
        userLat: Double?,
        userLon: Double?,
        autoResolveUnknownCoordinates: Boolean
    ): Result<List<Station>> = withContext(ioDispatcher) {
        val localFavorites = if (firestoreFavoritesRepository != null) {
            firestoreFavoritesRepository.favoritesState.value.ifEmpty {
                firestoreFavoritesRepository.getCachedFavorites()
            }
        } else {
            favoritesState.value.ifEmpty {
                getCachedFavorites()
            }
        }

        val favoriteIds = if (firestoreFavoritesRepository != null) {
            (firestoreFavoritesRepository.favoriteIdsState.value.ifEmpty {
                localFavorites.map { it.id }.toSet()
            }).filter { it.isNotBlank() }
        } else {
            favoriteIdsState.value.ifEmpty {
                localFavorites.map { it.id }.toSet()
            }.filter { it.isNotBlank() }
        }

        if (favoriteIds.isEmpty()) {
            return@withContext if (firestoreFavoritesRepository != null) {
                Result.success(emptyList())
            } else {
                super.getFavorites(userLat, userLon, autoResolveUnknownCoordinates)
            }
        }

        // --- TIER 1: VinFast CAPP API Live Telemetry ---
        val tier1Result = runCatching {
            vinFastApiClient.getLocationInfo(favoriteIds)
        }.fold(
            onSuccess = { it },
            onFailure = { e ->
                if (e is CancellationException) throw e
                Result.failure(e)
            }
        )

        if (tier1Result.isSuccess) {
            val dtos = tier1Result.getOrThrow()
            val mappedStations = stationMapper.toDomainStations(dtos, userLat = userLat, userLon = userLon)

            val localMap = localFavorites.associateBy { it.id }
            val enrichedStations = mappedStations.map { st ->
                val local = localMap[st.id]
                st.copy(
                    addedAt = if (local != null && local.addedAt > 0L) local.addedAt else st.addedAt,
                    sourceTier = SOURCE_TIER_VINFAST_DIRECT
                )
            }

            // Cache coordinates
            for (st in enrichedStations) {
                val key = st.id.trim().lowercase()
                if (key.isNotEmpty() && (st.latitude != 0.0 || st.longitude != 0.0)) {
                    coordinateCache[key] = Pair(st.latitude, st.longitude)
                }
            }
            saveCachedCoordinates()

            // Update in-memory state and persistent offline cache
            firestoreFavoritesRepository?.updateFavoritesInMemory(enrichedStations)

            AppDebugLogger.log(
                tag = DebugLogTag.FAVORITES,
                level = DebugLogLevel.SUCCESS,
                message = "Tier 1 VinFast CAPP favorites telemetry enriched ${enrichedStations.size} stations.",
                endpointUrl = "${vinFastApiClient.baseUrl}/ccarcharging/api/v1/stations/location-info"
            )
            return@withContext Result.success(enrichedStations)
        }

        // --- TIER 2: Fallback to EVCS Community Aggregator ---
        val tier1Exception = tier1Result.exceptionOrNull()
        val errorDetails = when (tier1Exception) {
            is VinFastApiException -> "Code: ${tier1Exception.code}, Message: ${tier1Exception.message}"
            else -> tier1Exception?.message ?: "Unknown Tier 1 error"
        }

        AppDebugLogger.log(
            tag = DebugLogTag.FAVORITES,
            level = DebugLogLevel.WARN,
            message = "Tier 1 VinFast CAPP getLocationInfo failed ($errorDetails). Falling back to Tier 2 EVCS.",
            errorDetails = tier1Exception?.stackTraceToString() ?: errorDetails
        )

        val fallbackResult = runCatching {
            super.getFavorites(userLat, userLon, autoResolveUnknownCoordinates)
        }.fold(
            onSuccess = { it },
            onFailure = { e ->
                if (e is CancellationException) throw e
                Result.failure(e)
            }
        )

        if (fallbackResult.isSuccess) {
            val stations = fallbackResult.getOrThrow().map {
                it.copy(sourceTier = SOURCE_TIER_EVCS_FALLBACK)
            }
            Result.success(stations)
        } else {
            val fallbackError = fallbackResult.exceptionOrNull() ?: Exception("Both Tier 1 and Tier 2 favorites failed")
            AppDebugLogger.log(
                tag = DebugLogTag.FAVORITES,
                level = DebugLogLevel.ERROR,
                message = "Both Tier 1 and Tier 2 favorites retrieval failed.",
                errorDetails = fallbackError.message
            )
            Result.failure(fallbackError)
        }
    }

    /**
     * Convenience ergonomics helper to retrieve enriched stations by their IDs using Tier-1 direct API,
     * failing over gracefully if unavailable.
     */
    suspend fun getStationsByIds(ids: List<String>): Result<List<Station>> = withContext(ioDispatcher) {
        if (ids.isEmpty()) return@withContext Result.success(emptyList())

        val tier1Result = runCatching {
            vinFastApiClient.getLocationInfo(ids)
        }.fold(
            onSuccess = { it },
            onFailure = { e ->
                if (e is CancellationException) throw e
                Result.failure(e)
            }
        )

        if (tier1Result.isSuccess) {
            val dtos = tier1Result.getOrThrow()
            val stations = stationMapper.toDomainStations(dtos)
                .map { it.copy(sourceTier = SOURCE_TIER_VINFAST_DIRECT) }

            for (st in stations) {
                val key = st.id.trim().lowercase()
                if (key.isNotEmpty() && (st.latitude != 0.0 || st.longitude != 0.0)) {
                    coordinateCache[key] = Pair(st.latitude, st.longitude)
                }
            }
            saveCachedCoordinates()
            Result.success(stations)
        } else {
            val error = tier1Result.exceptionOrNull()
            AppDebugLogger.log(
                tag = DebugLogTag.SEARCH,
                level = DebugLogLevel.WARN,
                message = "Tier 1 getStationsByIds failed. Falling back to local cache.",
                errorDetails = error?.message
            )
            val cachedFavorites = getCachedFavorites()
            val idSet = ids.map { it.trim().lowercase() }.toSet()
            val matched = cachedFavorites.filter { it.id.trim().lowercase() in idSet }
                .map { it.copy(sourceTier = SOURCE_TIER_EVCS_FALLBACK) }
            if (matched.isNotEmpty()) {
                Result.success(matched)
            } else {
                Result.failure(error ?: Exception("Failed to get stations by IDs"))
            }
        }
    }

    /**
     * Convenience ergonomics helper to search nearby stations using dual-tier failover.
     */
    suspend fun searchNearby(
        lat: Double,
        lon: Double,
        @Suppress("UNUSED_PARAMETER") radiusMeters: Int = 15000
    ): Result<List<Station>> {
        return searchNearbyVinFast(lat, lon)
    }

    /**
     * Filters fallback stations to strip 3.5kW/7kW motorbike plugs,
     * re-aggregates available and total plug counts for car bays,
     * drops motorbike-only stations, and ensures sourceTier = "EVCS_FALLBACK".
     */
    private fun sanitizeFallbackStations(stations: List<Station>): List<Station> {
        return stations.mapNotNull { station ->
            val carPowers = station.powers.filter { it.typeWatts > 7000L }
            if (carPowers.isEmpty() && station.powers.isNotEmpty()) {
                null
            } else {
                val available = if (carPowers.isNotEmpty()) carPowers.sumOf { it.availablePlugs } else station.totalAvailablePlugs
                val total = if (carPowers.isNotEmpty()) carPowers.sumOf { it.totalPlugs } else station.totalPlugs
                val connectors = if (carPowers.isNotEmpty()) carPowers.joinToString(", ") { it.label } else station.connectors
                station.copy(
                    powers = carPowers,
                    totalAvailablePlugs = available,
                    totalPlugs = total,
                    connectors = connectors,
                    sourceTier = SOURCE_TIER_EVCS_FALLBACK
                )
            }
        }
    }
}
