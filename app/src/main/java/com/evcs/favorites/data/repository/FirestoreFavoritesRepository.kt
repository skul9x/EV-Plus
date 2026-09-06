package com.evcs.favorites.data.repository

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.AuthService
import com.evcs.favorites.data.auth.SessionStorage
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.domain.model.AuthState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Strategy for resolving conflicts when local favorites and cloud favorites diverge on login.
 */
enum class ConflictResolutionStrategy {
    /**
     * Union of all stations ($L \cup C$), preserving the earliest added_at timestamp.
     * Writes union to both local cache and Cloud Firestore.
     */
    MERGE,

    /**
     * Overwrites local cache with cloud data ($L \leftarrow C$).
     */
    PREFER_CLOUD,

    /**
     * Overwrites cloud document with local data ($C \leftarrow L$).
     */
    PREFER_LOCAL
}

/**
 * Result data class returned by login synchronization.
 */
data class FavoritesSyncResult(
    val branch: Int,
    val strategyApplied: ConflictResolutionStrategy? = null,
    val stations: List<Station> = emptyList()
)

/**
 * Local-First repository coordinating instant 0ms offline favorites caching
 * and Cloud Firestore synchronization under `/users/{userId}/userdata/favorites`.
 */
class FirestoreFavoritesRepository(
    private val remoteDataSource: FirestoreFavoritesDataSource,
    private val localStorage: SessionStorage? = null,
    private val authService: AuthService? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    coroutineScope: CoroutineScope? = null
) {
    private val scope = coroutineScope ?: CoroutineScope(SupervisorJob() + ioDispatcher)

    companion object {
        const val KEY_OFFLINE_FAVORITES = "evcs_offline_favorites_snapshot"
    }

    private val _favoritesState = MutableStateFlow<List<Station>>(emptyList())
    val favoritesState: StateFlow<List<Station>> = _favoritesState.asStateFlow()

    private val _favoriteIdsState = MutableStateFlow<Set<String>>(emptySet())
    val favoriteIdsState: StateFlow<Set<String>> = _favoriteIdsState.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    val currentUserId: String?
        get() = authService?.currentUser?.takeIf { !it.isAnonymous }?.uid

    init {
        // Step 1: Immediate 0ms load from local storage
        val cached = getCachedFavorites()
        if (cached.isNotEmpty()) {
            _favoritesState.value = cached
            _favoriteIdsState.value = cached.map { it.id }.toSet()
        }

        // Step 2: Observe auth changes to automatically trigger login sync pipeline
        if (authService != null) {
            scope.launch {
                authService.authState.collect { authState ->
                    if (authState is AuthState.Authenticated && !authState.user.isAnonymous) {
                        syncOnLogin(authState.user.uid)
                    }
                }
            }
        }
    }

    /**
     * Retrieves cached favorites list from persistent storage.
     */
    fun getCachedFavorites(): List<Station> {
        val rawJson = localStorage?.getString(KEY_OFFLINE_FAVORITES) ?: return emptyList()
        return try {
            EvcsApiClient.json.decodeFromString<List<Station>>(rawJson)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Saves a JSON snapshot of the favorites list to local storage.
     */
    fun saveCachedFavorites(stations: List<Station>) {
        try {
            val json = EvcsApiClient.json.encodeToString(stations)
            localStorage?.putString(KEY_OFFLINE_FAVORITES, json)
        } catch (e: Exception) {
            // Safe degrade
        }
    }

    /**
     * Clears local offline cache.
     */
    fun clearOfflineCache() {
        localStorage?.remove(KEY_OFFLINE_FAVORITES)
        _favoritesState.value = emptyList()
        _favoriteIdsState.value = emptySet()
    }

    /**
     * Updates in-memory StateFlows and persists the favorites snapshot to local cache.
     */
    fun updateFavoritesInMemory(stations: List<Station>) {
        _favoritesState.value = stations
        _favoriteIdsState.value = stations.map { it.id }.toSet()
        saveCachedFavorites(stations)
    }

    /**
     * Executes the Login Sync Decision Matrix (adhering to thuattoan.txt and docs/BRIEF.md):
     * - Branch 1 ($L = \emptyset, C = \emptyset$): Stop, no action.
     * - Branch 2 ($L \neq \emptyset, C = \emptyset$): Auto-upload $L \rightarrow C$.
     * - Branch 3 ($L = \emptyset, C \neq \emptyset$): Auto-download $C \rightarrow L$.
     * - Branch 4 ($L \neq \emptyset, C \neq \emptyset$): Execute conflict resolution strategy.
     */
    suspend fun syncOnLogin(
        userId: String,
        strategy: ConflictResolutionStrategy = ConflictResolutionStrategy.MERGE
    ): Result<FavoritesSyncResult> = withContext(ioDispatcher) {
        _isSyncing.value = true
        try {
            val localFavorites = _favoritesState.value.ifEmpty { getCachedFavorites() }
            val localMap = localFavorites.associateBy { it.id }

            val cloudDocResult = remoteDataSource.getFavoritesDocument(userId)
            if (cloudDocResult.isFailure) {
                _isSyncing.value = false
                return@withContext Result.failure(cloudDocResult.exceptionOrNull()!!)
            }

            val cloudDoc = cloudDocResult.getOrNull()
            val cloudFavorites = parseFirestoreFavoritesDocument(cloudDoc, localMap)

            val now = System.currentTimeMillis() / 1000L

            when {
                // Branch 1: L = ∅ and C = ∅
                localFavorites.isEmpty() && cloudFavorites.isEmpty() -> {
                    _isSyncing.value = false
                    Result.success(FavoritesSyncResult(branch = 1, stations = emptyList()))
                }

                // Branch 2: L ≠ ∅ and C = ∅ -> Auto-Upload L -> C
                localFavorites.isNotEmpty() && cloudFavorites.isEmpty() -> {
                    val uploadMap = localFavorites.associate { it.id to it.toFirestoreMap(now) }
                    remoteDataSource.saveAllFavorites(userId, uploadMap, now)
                    _isSyncing.value = false
                    Result.success(FavoritesSyncResult(branch = 2, stations = localFavorites))
                }

                // Branch 3: L = ∅ and C ≠ ∅ -> Auto-Download C -> L
                localFavorites.isEmpty() && cloudFavorites.isNotEmpty() -> {
                    saveCachedFavorites(cloudFavorites)
                    _favoritesState.value = cloudFavorites
                    _favoriteIdsState.value = cloudFavorites.map { it.id }.toSet()
                    _isSyncing.value = false
                    Result.success(FavoritesSyncResult(branch = 3, stations = cloudFavorites))
                }

                // Branch 4: L ≠ ∅ and C ≠ ∅ -> Conflict resolution
                else -> {
                    val resultStations = when (strategy) {
                        ConflictResolutionStrategy.MERGE -> {
                            val allIds = (localFavorites.map { it.id } + cloudFavorites.map { it.id }).distinct()
                            val cloudMap = cloudFavorites.associateBy { it.id }

                            val mergedList = allIds.map { id ->
                                val l = localMap[id]
                                val c = cloudMap[id]
                                when {
                                    l != null && c != null -> {
                                        val earliestAddedAt = minOf(
                                            if (l.addedAt > 0) l.addedAt else Long.MAX_VALUE,
                                            if (c.addedAt > 0) c.addedAt else Long.MAX_VALUE
                                        ).takeIf { it != Long.MAX_VALUE } ?: now

                                        // Prefer richer metadata from local or cloud
                                        val base = if (l.powers.isNotEmpty() || l.images.isNotEmpty() || l.address.isNotBlank()) l else c
                                        base.copy(addedAt = earliestAddedAt)
                                    }
                                    l != null -> l.copy(addedAt = if (l.addedAt > 0) l.addedAt else now)
                                    c != null -> c.copy(addedAt = if (c.addedAt > 0) c.addedAt else now)
                                    else -> throw IllegalStateException("Unexpected null station")
                                }
                            }

                            // Write merged union to local cache
                            saveCachedFavorites(mergedList)
                            _favoritesState.value = mergedList
                            _favoriteIdsState.value = mergedList.map { it.id }.toSet()

                            // Write merged union to Cloud Firestore
                            val mergedCloudMap = mergedList.associate { it.id to it.toFirestoreMap(now) }
                            remoteDataSource.saveAllFavorites(userId, mergedCloudMap, now)

                            mergedList
                        }

                        ConflictResolutionStrategy.PREFER_CLOUD -> {
                            // Overwrite local cache with cloud data
                            saveCachedFavorites(cloudFavorites)
                            _favoritesState.value = cloudFavorites
                            _favoriteIdsState.value = cloudFavorites.map { it.id }.toSet()
                            cloudFavorites
                        }

                        ConflictResolutionStrategy.PREFER_LOCAL -> {
                            // Overwrite cloud document with local data
                            val localCloudMap = localFavorites.associate { it.id to it.toFirestoreMap(now) }
                            remoteDataSource.saveAllFavorites(userId, localCloudMap, now)
                            localFavorites
                        }
                    }

                    _isSyncing.value = false
                    Result.success(
                        FavoritesSyncResult(
                            branch = 4,
                            strategyApplied = strategy,
                            stations = resultStations
                        )
                    )
                }
            }
        } catch (e: Exception) {
            _isSyncing.value = false
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * Adds or updates a favorite station.
     * Performs optimistic local update for 0ms UI reactivity, followed by asynchronous cloud update if authenticated.
     */
    suspend fun addFavoriteStation(
        station: Station,
        userId: String? = currentUserId
    ): Result<Unit> = withContext(ioDispatcher) {
        val now = System.currentTimeMillis() / 1000L
        val updatedStation = station.copy(addedAt = if (station.addedAt > 0L) station.addedAt else now)

        val current = _favoritesState.value.toMutableList()
        val index = current.indexOfFirst { it.id.equals(station.id, ignoreCase = true) }
        if (index >= 0) {
            current[index] = updatedStation
        } else {
            current.add(updatedStation)
        }

        // 1. Optimistic local-first update (0ms UI feedback)
        _favoritesState.value = current
        _favoriteIdsState.value = current.map { it.id }.toSet()
        saveCachedFavorites(current)

        // 2. Cloud update if authenticated
        val targetUserId = userId ?: currentUserId
        if (targetUserId != null) {
            try {
                remoteDataSource.updateFavorite(
                    userId = targetUserId,
                    stationId = station.id,
                    stationData = updatedStation.toFirestoreMap(now),
                    updatedAt = now
                )
            } catch (e: Exception) {
                // In local-first architecture, retain local state offline
            }
        }

        Result.success(Unit)
    }

    /**
     * Removes a favorite station by [stationId].
     * Performs optimistic local update for 0ms UI reactivity, followed by asynchronous cloud update if authenticated.
     */
    suspend fun removeFavoriteStation(
        stationId: String,
        userId: String? = currentUserId
    ): Result<Unit> = withContext(ioDispatcher) {
        val current = _favoritesState.value.toMutableList()
        current.removeAll { it.id.equals(stationId, ignoreCase = true) }

        // 1. Optimistic local-first update (0ms UI feedback)
        _favoritesState.value = current
        _favoriteIdsState.value = current.map { it.id }.toSet()
        saveCachedFavorites(current)

        // 2. Cloud update if authenticated
        val targetUserId = userId ?: currentUserId
        val now = System.currentTimeMillis() / 1000L
        if (targetUserId != null) {
            try {
                remoteDataSource.deleteFavorite(
                    userId = targetUserId,
                    stationId = stationId,
                    updatedAt = now
                )
            } catch (e: Exception) {
                // In local-first architecture, retain local state offline
            }
        }

        Result.success(Unit)
    }
}
