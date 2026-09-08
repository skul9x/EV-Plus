package com.evcs.favorites.data.locations

import android.content.Context
import com.evcs.favorites.domain.location.DistanceCalculator
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException
import java.text.Collator
import java.util.Locale

/**
 * Offline-first repository managing Vietnam's complete administrative hierarchy
 * (63 provinces/cities and ~700 districts) with precomputed driving centroid coordinates.
 *
 * Backed by `vietnam_locations.json` bundled directly into Android assets (`app/src/main/assets/`),
 * guaranteeing 100% offline resilience, 0ms network latency, and immunity to Cloudflare bot blocks.
 *
 * @param context Optional Android context to load assets from.
 * @param jsonContentProvider Optional provider to supply raw JSON directly (useful for tests or custom sources).
 */
class VietnamLocationsRepository(
    private val context: Context? = null,
    private val jsonContentProvider: (() -> String)? = null
) {

    @Serializable
    private data class RawCoordinate(
        val b: Double? = null,
        val c: Double? = null,
        val lat: Double? = null,
        val lng: Double? = null
    )

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * In-memory cache of parsed administrative hierarchy (Province Name -> AdministrativeProvince).
     * Lazily parsed on first access to keep cold start fast (< 50ms).
     */
    private val hierarchy: Map<String, AdministrativeProvince> by lazy {
        parseHierarchy(loadJson())
    }

    /**
     * Flattened list of (Province Name, AdministrativeDistrict) for O(N) reverse geocode nearest search.
     */
    private val flattenedDistricts: List<Pair<String, AdministrativeDistrict>> by lazy {
        hierarchy.flatMap { (provinceName, province) ->
            province.districts.map { district -> Pair(provinceName, district) }
        }
    }

    /**
     * Cached list of all 63 province/city names in alphabetical order, with major metropolises
     * (Hà Nội, Hồ Chí Minh, Đà Nẵng) pinned at the very top.
     * Thread-safe lazy initialization ensures Collator.getInstance is executed only once.
     */
    val cachedProvinces: List<String> by lazy {
        val allKeys = hierarchy.keys
        val pinned = PINNED_PROVINCES.filter { it in allKeys }
        val remaining = allKeys.filter { it !in PINNED_PROVINCES }
        val collator = Collator.getInstance(Locale("vi", "VN"))
        pinned + remaining.sortedWith(collator)
    }

    /**
     * Returns all 63 province/city names in alphabetical order, with major metropolises
     * (Hà Nội, Hồ Chí Minh, Đà Nẵng) pinned at the very top.
     */
    fun getAllProvinces(): List<String> = cachedProvinces

    /**
     * Returns the list of districts for the given province name.
     * Supports case-insensitive and trimmed name matching.
     *
     * @param provinceName Name of the province.
     * @return List of districts in the province, or empty list if province is not found.
     */
    fun getDistrictsForProvince(provinceName: String): List<AdministrativeDistrict> {
        val trimmed = provinceName.trim()
        val province = hierarchy[trimmed]
            ?: hierarchy.entries.firstOrNull { it.key.equals(trimmed, ignoreCase = true) }?.value
        return province?.districts ?: emptyList()
    }

    /**
     * Retrieves the centroid [LocationCoordinate] for a specific province and district.
     *
     * @param provinceName Name of the province.
     * @param districtName Name of the district.
     * @return [LocationCoordinate] if found, or null otherwise.
     */
    fun getCoordinate(provinceName: String, districtName: String): LocationCoordinate? {
        val districts = getDistrictsForProvince(provinceName)
        val trimmedDistrict = districtName.trim()
        val district = districts.firstOrNull { it.name.equals(trimmedDistrict, ignoreCase = true) }
        return district?.coordinate
    }

    /**
     * Finds the closest administrative district to the specified GPS coordinates using Haversine distance.
     * Enables 1-tap GPS location matching for Origin selection.
     *
     * @param lat Latitude in decimal degrees.
     * @param lng Longitude in decimal degrees.
     * @return Pair of (Province Name, AdministrativeDistrict) nearest to the coordinate, or null if coordinates are invalid.
     */
    fun findClosestDistrict(lat: Double, lng: Double): Pair<String, AdministrativeDistrict>? {
        if (lat.isNaN() || lng.isNaN()) return null

        var closestPair: Pair<String, AdministrativeDistrict>? = null
        var minDistanceKm = Double.MAX_VALUE

        for (item in flattenedDistricts) {
            val distKm = DistanceCalculator.calculateDistanceKm(
                lat1 = lat,
                lon1 = lng,
                lat2 = item.second.coordinate.lat,
                lon2 = item.second.coordinate.lng
            )
            if (distKm < minDistanceKm) {
                minDistanceKm = distKm
                closestPair = item
            }
        }
        return closestPair
    }

    /**
     * Retrieves the full [AdministrativeProvince] object by name.
     */
    fun getProvince(provinceName: String): AdministrativeProvince? {
        val trimmed = provinceName.trim()
        return hierarchy[trimmed]
            ?: hierarchy.entries.firstOrNull { it.key.equals(trimmed, ignoreCase = true) }?.value
    }

    /**
     * Total number of provinces/cities loaded.
     */
    fun getTotalProvincesCount(): Int = hierarchy.size

    /**
     * Total number of administrative districts loaded across all provinces.
     */
    fun getTotalDistrictsCount(): Int = flattenedDistricts.size

    private fun loadJson(): String {
        jsonContentProvider?.let {
            return it()
        }

        if (context != null) {
            try {
                context.assets.open(ASSET_FILE_NAME).use { stream ->
                    return stream.bufferedReader(Charsets.UTF_8).readText()
                }
            } catch (_: Throwable) {
                // In non-Robolectric unit tests Context.assets may throw, fall back to file resolution
            }
        }

        val classLoader = javaClass.classLoader ?: Thread.currentThread().contextClassLoader
        val resourceStream = classLoader?.getResourceAsStream(ASSET_FILE_NAME)
            ?: classLoader?.getResourceAsStream("assets/$ASSET_FILE_NAME")
        if (resourceStream != null) {
            resourceStream.use { stream ->
                return stream.bufferedReader(Charsets.UTF_8).readText()
            }
        }

        val candidatePaths = listOf(
            "app/src/main/assets/$ASSET_FILE_NAME",
            "src/main/assets/$ASSET_FILE_NAME",
            "../app/src/main/assets/$ASSET_FILE_NAME",
            ASSET_FILE_NAME
        )
        for (path in candidatePaths) {
            val file = File(path)
            if (file.exists() && file.isFile) {
                return file.readText(Charsets.UTF_8)
            }
        }

        throw FileNotFoundException("Could not locate $ASSET_FILE_NAME in assets, resources, or known file paths")
    }

    private fun parseHierarchy(rawJson: String): Map<String, AdministrativeProvince> {
        if (rawJson.isBlank()) return emptyMap()
        val trimmed = rawJson.trim()
        return try {
            if (trimmed.startsWith("[")) {
                val list = jsonParser.decodeFromString<List<AdministrativeProvince>>(trimmed)
                list.associateByTo(LinkedHashMap()) { it.name }
            } else {
                val rawMap = jsonParser.decodeFromString<Map<String, Map<String, RawCoordinate>>>(trimmed)
                val result = LinkedHashMap<String, AdministrativeProvince>(rawMap.size)
                for ((provinceName, districtsMap) in rawMap) {
                    val districtsList = ArrayList<AdministrativeDistrict>(districtsMap.size)
                    for ((districtName, rawCoord) in districtsMap) {
                        val lat = rawCoord.lat ?: rawCoord.b ?: 0.0
                        val lng = rawCoord.lng ?: rawCoord.c ?: 0.0
                        districtsList.add(
                            AdministrativeDistrict(
                                name = districtName,
                                coordinate = LocationCoordinate(lat = lat, lng = lng)
                            )
                        )
                    }
                    result[provinceName] = AdministrativeProvince(
                        name = provinceName,
                        districts = districtsList
                    )
                }
                result
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    companion object {
        const val ASSET_FILE_NAME = "vietnam_locations.json"

        /**
         * Major metropolitan centers prioritized at the top of selection dropdowns.
         */
        val PINNED_PROVINCES = listOf("Hà Nội", "Hồ Chí Minh", "Đà Nẵng")

        @Volatile
        private var instance: VietnamLocationsRepository? = null

        /**
         * Singleton accessor for production application context.
         */
        fun getInstance(context: Context): VietnamLocationsRepository {
            return instance ?: synchronized(this) {
                instance ?: VietnamLocationsRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
