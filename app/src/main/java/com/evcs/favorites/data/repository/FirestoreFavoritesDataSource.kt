package com.evcs.favorites.data.repository

import com.evcs.favorites.data.model.Station
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Interface contract for remote Firestore favorites persistence.
 * Target document: `/users/{userId}/userdata/favorites`.
 * Aggregates favorites into a single document to optimize for Free Tier quotas (1 read on load, 1 write on sync).
 */
interface FirestoreFavoritesDataSource {
    /**
     * Reads the aggregated favorites document for [userId].
     * Returns null if the document does not exist ($C = \emptyset$).
     */
    suspend fun getFavoritesDocument(userId: String): Result<Map<String, Any>?>

    /**
     * Overwrites or creates the favorites document with full [favoritesMap] and [updatedAt] timestamp.
     */
    suspend fun saveAllFavorites(userId: String, favoritesMap: Map<String, Any>, updatedAt: Long): Result<Unit>

    /**
     * Atomically adds or updates a single favorite station entry under `favorites.<stationId>`.
     * Uses [FieldPath] or SetOptions.merge() to prevent dot splitting in station IDs like `C.BNI0012`.
     */
    suspend fun updateFavorite(userId: String, stationId: String, stationData: Any, updatedAt: Long): Result<Unit>

    /**
     * Atomically deletes a single favorite station entry under `favorites.<stationId>`.
     * Uses [FieldPath.of("favorites", stationId)] and [FieldValue.delete()].
     */
    suspend fun deleteFavorite(userId: String, stationId: String, updatedAt: Long): Result<Unit>

    companion object {
        fun create(
            firestoreProvider: () -> FirebaseFirestore = { FirebaseFirestore.getInstance() },
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): FirestoreFavoritesDataSource = DefaultFirestoreFavoritesDataSource(firestoreProvider, ioDispatcher)
    }
}

/**
 * Production implementation of [FirestoreFavoritesDataSource] utilizing Firebase Cloud Firestore SDK.
 */
class DefaultFirestoreFavoritesDataSource(
    private val firestoreProvider: () -> FirebaseFirestore = { FirebaseFirestore.getInstance() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : FirestoreFavoritesDataSource {

    constructor(firestore: FirebaseFirestore, ioDispatcher: CoroutineDispatcher = Dispatchers.IO) : this(
        firestoreProvider = { firestore },
        ioDispatcher = ioDispatcher
    )

    private fun getFavoritesDocumentRef(userId: String) = firestoreProvider()
        .collection("users").document(userId)
        .collection("userdata").document("favorites")

    override suspend fun getFavoritesDocument(userId: String): Result<Map<String, Any>?> = withContext(ioDispatcher) {
        try {
            val snapshot = getFavoritesDocumentRef(userId).get().await()
            if (!snapshot.exists()) {
                Result.success(null)
            } else {
                Result.success(snapshot.data)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun saveAllFavorites(
        userId: String,
        favoritesMap: Map<String, Any>,
        updatedAt: Long
    ): Result<Unit> = withContext(ioDispatcher) {
        try {
            val payload = mapOf(
                "favorites" to favoritesMap,
                "updated_at" to updatedAt
            )
            getFavoritesDocumentRef(userId).set(payload).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateFavorite(
        userId: String,
        stationId: String,
        stationData: Any,
        updatedAt: Long
    ): Result<Unit> = withContext(ioDispatcher) {
        try {
            // SetOptions.merge() merges nested maps without destroying other keys and creates doc if missing
            val payload = mapOf(
                "favorites" to mapOf(stationId to stationData),
                "updated_at" to updatedAt
            )
            getFavoritesDocumentRef(userId).set(payload, SetOptions.merge()).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteFavorite(
        userId: String,
        stationId: String,
        updatedAt: Long
    ): Result<Unit> = withContext(ioDispatcher) {
        try {
            // FieldPath.of("favorites", stationId) safely targets station ID with dots (e.g. C.BNI0012)
            getFavoritesDocumentRef(userId).update(
                FieldPath.of("favorites", stationId), FieldValue.delete(),
                FieldPath.of("updated_at"), updatedAt
            ).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * In-memory implementation of [FirestoreFavoritesDataSource] for JVM testing and preview environments.
 */
class FakeFirestoreFavoritesDataSource(
    initialDocuments: Map<String, Map<String, Any>> = emptyMap()
) : FirestoreFavoritesDataSource {

    private val documents = mutableMapOf<String, MutableMap<String, Any>>()

    init {
        initialDocuments.forEach { (userId, data) ->
            documents[userId] = deepCopyMap(data)
        }
    }

    override suspend fun getFavoritesDocument(userId: String): Result<Map<String, Any>?> {
        val doc = documents[userId]
        return Result.success(doc?.let { deepCopyMap(it) })
    }

    override suspend fun saveAllFavorites(
        userId: String,
        favoritesMap: Map<String, Any>,
        updatedAt: Long
    ): Result<Unit> {
        val doc = documents.getOrPut(userId) { mutableMapOf() }
        doc["favorites"] = favoritesMap.toMutableMap()
        doc["updated_at"] = updatedAt
        return Result.success(Unit)
    }

    override suspend fun updateFavorite(
        userId: String,
        stationId: String,
        stationData: Any,
        updatedAt: Long
    ): Result<Unit> {
        val doc = documents.getOrPut(userId) { mutableMapOf() }
        @Suppress("UNCHECKED_CAST")
        val favs = (doc["favorites"] as? MutableMap<String, Any>) ?: mutableMapOf<String, Any>().also {
            doc["favorites"] = it
        }
        favs[stationId] = stationData
        doc["updated_at"] = updatedAt
        return Result.success(Unit)
    }

    override suspend fun deleteFavorite(
        userId: String,
        stationId: String,
        updatedAt: Long
    ): Result<Unit> {
        val doc = documents[userId] ?: return Result.success(Unit)
        @Suppress("UNCHECKED_CAST")
        val favs = doc["favorites"] as? MutableMap<String, Any>
        favs?.remove(stationId)
        doc["updated_at"] = updatedAt
        return Result.success(Unit)
    }

    fun getRawDocument(userId: String): Map<String, Any>? = documents[userId]?.let { deepCopyMap(it) }

    @Suppress("UNCHECKED_CAST")
    private fun deepCopyMap(map: Map<String, Any>): MutableMap<String, Any> {
        val copy = mutableMapOf<String, Any>()
        for ((k, v) in map) {
            copy[k] = when (v) {
                is Map<*, *> -> deepCopyMap(v as Map<String, Any>)
                is List<*> -> v.toMutableList()
                else -> v
            }
        }
        return copy
    }
}

/**
 * Converts a domain [Station] to a map suitable for Cloud Firestore storage.
 */
fun Station.toFirestoreMap(fallbackTimestamp: Long = System.currentTimeMillis() / 1000L): Map<String, Any> {
    val timestamp = if (addedAt > 0L) addedAt else fallbackTimestamp
    return mapOf(
        "added_at" to timestamp,
        "name" to name,
        "address" to address,
        "lat" to latitude,
        "lon" to longitude,
        "summary" to summary,
        "connectors" to connectors,
        "images" to images
    )
}

/**
 * Parses the raw Firestore document data into a list of [Station]s.
 * Supports dual parsing:
 * - Map: rich station metadata.
 * - Number: scalar timestamp with fallback to [localCacheFallback] for metadata enrichment.
 */
fun parseFirestoreFavoritesDocument(
    documentData: Map<String, Any>?,
    localCacheFallback: Map<String, Station> = emptyMap()
): List<Station> {
    if (documentData == null) return emptyList()
    @Suppress("UNCHECKED_CAST")
    val rawFavorites = documentData["favorites"] as? Map<String, Any> ?: return emptyList()

    return rawFavorites.mapNotNull { (stationId, value) ->
        when (value) {
            is Map<*, *> -> {
                val addedAt = (value["added_at"] as? Number)?.toLong() ?: 0L
                val name = value["name"] as? String ?: stationId
                val address = value["address"] as? String ?: ""
                val lat = (value["lat"] as? Number)?.toDouble()
                    ?: (value["latitude"] as? Number)?.toDouble() ?: 0.0
                val lon = (value["lon"] as? Number)?.toDouble()
                    ?: (value["longitude"] as? Number)?.toDouble() ?: 0.0
                val summary = value["summary"] as? String ?: "24/7"
                val connectors = value["connectors"] as? String ?: ""
                val images = (value["images"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()

                Station(
                    id = stationId,
                    name = name,
                    address = address,
                    latitude = lat,
                    longitude = lon,
                    summary = summary,
                    connectors = connectors,
                    depotStatus = "Normal",
                    powers = EvcsRepository.parseConnectorsToPowers(connectors),
                    images = images,
                    image = images.firstOrNull(),
                    addedAt = addedAt
                )
            }
            is Number -> {
                val addedAt = value.toLong()
                val cached = localCacheFallback[stationId]
                if (cached != null) {
                    cached.copy(addedAt = addedAt)
                } else {
                    Station(
                        id = stationId,
                        name = stationId,
                        address = "",
                        latitude = 0.0,
                        longitude = 0.0,
                        summary = "24/7",
                        connectors = "",
                        depotStatus = "Unknown",
                        addedAt = addedAt
                    )
                }
            }
            else -> null
        }
    }
}
