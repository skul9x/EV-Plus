package com.evcs.favorites.focus

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.model.SearchStationRaw
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.network.AppOkHttpClientProvider
import com.evcs.favorites.domain.location.DistanceCalculator
import com.evcs.favorites.util.StationNameSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Resolver utility resolving and caching authentic, standard charging station names from evcs.vn.
 *
 * Prevents generic station names such as "Trạm sạc VinFast" or "VinFast" from Here EV API
 * from masking specific location identifiers (e.g. "VinFast Landmark 81").
 *
 * Provides in-memory caching and matching by station/location ID or coordinate proximity (<= 100 meters),
 * as well as direct canonical HTML name resolution without persistent caching for hybrid AC workflows.
 */
open class EvcsStationNameResolver(
    private val searchStationsProvider: (suspend (lat: Double, lon: Double) -> Result<List<SearchStationRaw>>)? = null,
    private val apiClient: EvcsApiClient? = null,
    private val httpClient: OkHttpClient = AppOkHttpClientProvider.getSharedClient(),
    private val htmlBaseUrl: String = DEFAULT_HTML_BASE_URL
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
        const val DEFAULT_HTML_BASE_URL = "https://evcs.vn"
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

        private val TITLE_TAG_REGEX = Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)
        private val META_NAME_TITLE_REGEX = Regex("""<meta[^>]*name=["']title["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val META_CONTENT_FIRST_REGEX = Regex("""<meta[^>]*content=["']([^"']+)["'][^>]*name=["']title["']""", RegexOption.IGNORE_CASE)
        private val HTML_TITLE_PREFIX_REGEX = Regex("""^(?:Trạm|Tram)\s+(?:sạc|sac)\s+VinFast\s*[-–—:]\s*""", RegexOption.IGNORE_CASE)
        private val HTML_TITLE_SUFFIX_REGEX = Regex("""\s*[-–—:]\s*(?:Trạm|Tram)\s+(?:Sạc|Sac)\s+EV\s*$""", RegexOption.IGNORE_CASE)

        /**
         * Extracts raw title from HTML string via <title> tag or <meta name="title">.
         */
        fun extractTitleFromHtml(html: String): String? {
            val raw = TITLE_TAG_REGEX.find(html)?.groupValues?.getOrNull(1)
                ?: META_NAME_TITLE_REGEX.find(html)?.groupValues?.getOrNull(1)
                ?: META_CONTENT_FIRST_REGEX.find(html)?.groupValues?.getOrNull(1)
                ?: return null

            val unescaped = raw.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .trim()

            return unescaped.ifBlank { null }
        }

        /**
         * Cleans HTML title by stripping "Trạm sạc VinFast - " prefix and " - Trạm Sạc EV" suffix,
         * followed by sanitization through [StationNameSanitizer].
         */
        fun cleanExtractedTitle(title: String): String {
            val withoutPrefix = title.replace(HTML_TITLE_PREFIX_REGEX, "").trim()
            val withoutSuffix = withoutPrefix.replace(HTML_TITLE_SUFFIX_REGEX, "").trim()
            val sanitized = StationNameSanitizer.sanitize(withoutSuffix).ifBlank { withoutSuffix }.trim()
            return sanitized
        }

        /**
         * Formats fallback name using "VinFast - $fallbackAddress".
         */
        fun formatFallbackAddress(fallbackAddress: String = ""): String {
            val clean = fallbackAddress.trim()
            return when {
                clean.isBlank() -> "VinFast - "
                clean.startsWith("VinFast - ", ignoreCase = true) -> clean
                clean.startsWith("VinFast", ignoreCase = true) -> "VinFast - ${clean.removePrefix("VinFast").trim().removePrefix("-").trim()}"
                else -> "VinFast - $clean"
            }
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
     * Resolves the canonical station name from the EVCS HTML detail page:
     * `https://evcs.vn/tram-sac-vinfast-${locationId.lowercase()}.html`.
     *
     * Uses [EvcsApiClient.USER_AGENT_BROWSER] mobile headers.
     * Extracts title text from `<title>` or `<meta name="title">`, stripping prefix
     * "Trạm sạc VinFast - " and suffix " - Trạm Sạc EV".
     * If request fails, times out (5s), or HTML lacks title, falls back to "VinFast - ${fallbackAddress}".
     *
     * Note: Per user specification, results are resolved live and NOT retained in persistent or in-memory cache.
     */
    suspend fun resolveStationNameFromHtml(
        locationId: String,
        fallbackAddress: String = ""
    ): String = withContext(Dispatchers.IO) {
        val cleanLocationId = locationId.trim().lowercase()
            .removePrefix("tram-sac-vinfast-")
            .removeSuffix(".html")

        if (cleanLocationId.isBlank()) {
            return@withContext formatFallbackAddress(fallbackAddress)
        }

        val cleanBase = htmlBaseUrl.trimEnd('/')
        val url = "$cleanBase/tram-sac-vinfast-$cleanLocationId.html"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", EvcsApiClient.USER_AGENT_BROWSER)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .get()
            .build()

        val htmlBody = withTimeoutOrNull(5000L) {
            try {
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        response.body?.string()
                    } else {
                        null
                    }
                }
            } catch (e: Exception) {
                null
            }
        }

        if (htmlBody.isNullOrBlank()) {
            return@withContext formatFallbackAddress(fallbackAddress)
        }

        val rawTitle = extractTitleFromHtml(htmlBody)
        if (rawTitle.isNullOrBlank()) {
            return@withContext formatFallbackAddress(fallbackAddress)
        }

        val cleanedName = cleanExtractedTitle(rawTitle)
        if (cleanedName.isBlank()) {
            return@withContext formatFallbackAddress(fallbackAddress)
        }

        cleanedName
    }

    /**
     * Concurrently resolves canonical names for a list of candidate stations using [coroutineScope]
     * and [async]/[awaitAll] with bounded 5-second timeout.
     */
    open suspend fun resolveStationNamesBatch(stations: List<Station>): List<Station> = coroutineScope {
        stations.map { station ->
            async {
                val resolved = resolveStationNameFromHtml(
                    locationId = station.id,
                    fallbackAddress = station.address
                )
                station.copy(name = resolved)
            }
        }.awaitAll()
    }

    /**
     * Clears all in-memory station name caches.
     */
    fun clearCache() {
        idToNameCache.clear()
        coordCache.clear()
    }
}
