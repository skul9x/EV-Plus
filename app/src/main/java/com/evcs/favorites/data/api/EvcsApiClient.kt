package com.evcs.favorites.data.api

import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.crypto.EvcsHmacSigner
import com.evcs.favorites.data.model.FavoriteStationRaw
import com.evcs.favorites.data.model.FavoritesResponse
import com.evcs.favorites.data.model.SaveFavoritesRequest
import com.evcs.favorites.data.model.SearchRequest
import com.evcs.favorites.data.model.SearchResponse
import com.evcs.favorites.data.model.SearchStationRaw
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.util.StationUrlBuilder
import com.evcs.favorites.data.logging.DebugLoggingInterceptor
import com.evcs.favorites.data.network.AppOkHttpClientProvider
import com.evcs.favorites.util.RetryAfterParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * OkHttp Client managing requests to the EVCS backend endpoints:
 * 1. `POST /favorite.html` with auth cookies & `X-Partial: fav`
 * 2. `POST /search?t=...` signed with HMAC-SHA256
 */
open class EvcsApiClient(
    private val sessionManager: SessionManager,
    private val client: OkHttpClient = defaultClient(),
    private val baseUrl: String = DEFAULT_BASE_URL
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://evcs.vn"
        const val DEFAULT_SEARCH_TOKEN = "eepe5dp9zpipl102"
        const val USER_AGENT_BROWSER =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36 EVCS/A1.57 Mobile"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

        private fun defaultClient(): OkHttpClient {
            return AppOkHttpClientProvider.getSharedClient().newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .addInterceptor(DebugLoggingInterceptor())
                .build()
        }

        /**
         * Validates if lat/lon numbers are in valid geographic range and not (0.0, 0.0).
         */
        fun isValidCoordinate(lat: Double?, lon: Double?): Boolean {
            if (lat == null || lon == null) return false
            if (lat.isNaN() || lon.isNaN()) return false
            if (lat == 0.0 && lon == 0.0) return false
            return lat in -90.0..90.0 && lon in -180.0..180.0
        }

        // Pre-compiled regex patterns for coordinate and metadata parsing (PERF-CPU-01)
        val LAT_META_NAME_FIRST_REGEX = Regex(
            """<meta[^>]+(?:name|property)=["'](?:place:location:latitude|og:latitude|latitude)["'][^>]+content=["']([0-9.-]+)["']""",
            RegexOption.IGNORE_CASE
        )
        val LAT_META_CONTENT_FIRST_REGEX = Regex(
            """<meta[^>]+content=["']([0-9.-]+)["'][^>]+(?:name|property)=["'](?:place:location:latitude|og:latitude|latitude)["']""",
            RegexOption.IGNORE_CASE
        )
        val LON_META_NAME_FIRST_REGEX = Regex(
            """<meta[^>]+(?:name|property)=["'](?:place:location:longitude|og:longitude|longitude)["'][^>]+content=["']([0-9.-]+)["']""",
            RegexOption.IGNORE_CASE
        )
        val LON_META_CONTENT_FIRST_REGEX = Regex(
            """<meta[^>]+content=["']([0-9.-]+)["'][^>]+(?:name|property)=["'](?:place:location:longitude|og:longitude|longitude)["']""",
            RegexOption.IGNORE_CASE
        )
        val GEO_POS_NAME_FIRST_REGEX = Regex(
            """<meta[^>]+(?:name|property)=["']geo\.position["'][^>]+content=["']([0-9.-]+)[;,]\s*([0-9.-]+)["']""",
            RegexOption.IGNORE_CASE
        )
        val GEO_POS_CONTENT_FIRST_REGEX = Regex(
            """<meta[^>]+content=["']([0-9.-]+)[;,]\s*([0-9.-]+)["'][^>]+(?:name|property)=["']geo\.position["']""",
            RegexOption.IGNORE_CASE
        )
        val JSON_LAT_LON_REGEX = Regex(""""latitude"\s*:\s*([0-9.-]+)[\s\S]*?"longitude"\s*:\s*([0-9.-]+)""")
        val JSON_LON_LAT_REGEX = Regex(""""longitude"\s*:\s*([0-9.-]+)[\s\S]*?"latitude"\s*:\s*([0-9.-]+)""")
        val JSON_SHORT_LAT_LNG_REGEX = Regex(""""lat"\s*:\s*([0-9.-]+)[\s\S]*?"(?:lng|lon|long)"\s*:\s*([0-9.-]+)""")
        val MAP_QUERY_REGEX = Regex(
            """(?:(?:maps\.google\.com|google\.com/maps)[^"'>]*?[?&;](?:q|query|ll)=|geo:|navigation:q=)([0-9.-]+)[,;]([0-9.-]+)""",
            RegexOption.IGNORE_CASE
        )
        val DATA_LAT_REGEX = Regex("""data-(?:lat|latitude)=["']([0-9.-]+)["']""", RegexOption.IGNORE_CASE)
        val DATA_LON_REGEX = Regex("""data-(?:lng|lon|longitude)=["']([0-9.-]+)["']""", RegexOption.IGNORE_CASE)
        val ADDRESS_META_NAME_FIRST_REGEX = Regex(
            """<meta[^>]+(?:name|property)=["'](?:business:contact_data:street_address|og:street-address|address)["'][^>]+content=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        val ADDRESS_META_CONTENT_FIRST_REGEX = Regex(
            """<meta[^>]+content=["']([^"']+)["'][^>]+(?:name|property)=["'](?:business:contact_data:street_address|og:street-address|address)["']""",
            RegexOption.IGNORE_CASE
        )
        val WORKING_TIME_META_REGEX = Regex(
            """<meta[^>]+(?:name|property)=["']working-time["'][^>]+content=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )

        /**
         * Parses GPS coordinates from station detail HTML or metadata:
         * 1. Meta tags (place:location:latitude, og:latitude, latitude, geo.position)
         * 2. Embedded JSON or JavaScript ("latitude": 21.1452, "longitude": 106.1553)
         * 3. Short format ("lat": 21.1452, "lng": 106.1553)
         * 4. Maps / Navigation links (google.com/maps?query=..., geo:..., navigation:q=...)
         * 5. Data attributes (data-lat="...", data-lng="...")
         */
        fun parseCoordinatesFromHtml(html: String): Pair<Double, Double>? {
            if (html.isBlank()) return null

            // 1. Meta tags: place:location:latitude & place:location:longitude, og:latitude & og:longitude
            val latMeta = LAT_META_NAME_FIRST_REGEX.find(html) ?: LAT_META_CONTENT_FIRST_REGEX.find(html)
            val lonMeta = LON_META_NAME_FIRST_REGEX.find(html) ?: LON_META_CONTENT_FIRST_REGEX.find(html)

            if (latMeta != null && lonMeta != null) {
                val lat = latMeta.groupValues[1].toDoubleOrNull()
                val lon = lonMeta.groupValues[1].toDoubleOrNull()
                if (isValidCoordinate(lat, lon)) return Pair(lat!!, lon!!)
            }

            // 2. geo.position meta tag: content="21.1452;106.1553" or "21.1452, 106.1553"
            val geoPos = GEO_POS_NAME_FIRST_REGEX.find(html) ?: GEO_POS_CONTENT_FIRST_REGEX.find(html)
            if (geoPos != null) {
                val lat = geoPos.groupValues[1].toDoubleOrNull()
                val lon = geoPos.groupValues[2].toDoubleOrNull()
                if (isValidCoordinate(lat, lon)) return Pair(lat!!, lon!!)
            }

            // 3. Embedded JSON / JavaScript: "latitude": 21.1452, "longitude": 106.1553
            val jsonLatLon = JSON_LAT_LON_REGEX.find(html)
            if (jsonLatLon != null) {
                val lat = jsonLatLon.groupValues[1].toDoubleOrNull()
                val lon = jsonLatLon.groupValues[2].toDoubleOrNull()
                if (isValidCoordinate(lat, lon)) return Pair(lat!!, lon!!)
            }
            val jsonLonLat = JSON_LON_LAT_REGEX.find(html)
            if (jsonLonLat != null) {
                val lon = jsonLonLat.groupValues[1].toDoubleOrNull()
                val lat = jsonLonLat.groupValues[2].toDoubleOrNull()
                if (isValidCoordinate(lat, lon)) return Pair(lat!!, lon!!)
            }

            // 4. Short form lat / lng: "lat": 21.1452, "lng": 106.1553
            val jsonLatLng = JSON_SHORT_LAT_LNG_REGEX.find(html)
            if (jsonLatLng != null) {
                val lat = jsonLatLng.groupValues[1].toDoubleOrNull()
                val lon = jsonLatLng.groupValues[2].toDoubleOrNull()
                if (isValidCoordinate(lat, lon)) return Pair(lat!!, lon!!)
            }

            // 5. Google Maps / navigation query links: query=lat,lon or q=lat,lon or geo:lat,lon
            val mapMatch = MAP_QUERY_REGEX.find(html)
            if (mapMatch != null) {
                val lat = mapMatch.groupValues[1].toDoubleOrNull()
                val lon = mapMatch.groupValues[2].toDoubleOrNull()
                if (isValidCoordinate(lat, lon)) return Pair(lat!!, lon!!)
            }

            // 6. Data attributes: data-lat="..." data-lng="..."
            val dataLatMatch = DATA_LAT_REGEX.find(html)
            val dataLonMatch = DATA_LON_REGEX.find(html)
            if (dataLatMatch != null && dataLonMatch != null) {
                val lat = dataLatMatch.groupValues[1].toDoubleOrNull()
                val lon = dataLonMatch.groupValues[1].toDoubleOrNull()
                if (isValidCoordinate(lat, lon)) return Pair(lat!!, lon!!)
            }

            return null
        }

        /**
         * Parses lightweight station detail metadata including coordinates and address from HTML.
         */
        fun parseStationMetadataFromHtml(html: String): StationDetailMetadata? {
            val coords = parseCoordinatesFromHtml(html) ?: return null
            val addressMatch = ADDRESS_META_NAME_FIRST_REGEX.find(html) ?: ADDRESS_META_CONTENT_FIRST_REGEX.find(html)
            val address = addressMatch?.groupValues?.get(1)?.trim()

            val workingTimeMatch = WORKING_TIME_META_REGEX.find(html)
            val workingTime = workingTimeMatch?.groupValues?.get(1)?.trim()

            return StationDetailMetadata(
                latitude = coords.first,
                longitude = coords.second,
                address = address,
                workingTime = workingTime
            )
        }
    }

    val globalRateLimitedUntil: AtomicLong = AtomicLong(0L)

    fun isGlobalRateLimited(): Boolean {
        val blockedUntil = globalRateLimitedUntil.get()
        return blockedUntil > 0L && System.currentTimeMillis() < blockedUntil
    }

    fun checkRateLimitOrThrow() {
        if (isGlobalRateLimited()) {
            val blockedUntil = globalRateLimitedUntil.get()
            val remainingSeconds = ((blockedUntil - System.currentTimeMillis() + 999L) / 1000L).coerceAtLeast(1L)
            throw RateLimitException(
                retryAfterSeconds = remainingSeconds,
                message = "Client rate limited: Cooldown active for ${remainingSeconds}s"
            )
        }
    }

    fun resetRateLimitCooldown() {
        globalRateLimitedUntil.set(0L)
    }

    /**
     * Step 1: Fetches user's saved favorite stations from `POST /favorite.html`.
     */
    open suspend fun fetchFavorites(): Result<FavoritesResponse> = withContext(Dispatchers.IO) {
        try {
            checkRateLimitOrThrow()
            val url = "$baseUrl/favorite.html"
            val requestBuilder = Request.Builder()
                .url(url)
                .post("".toRequestBody())
                .addHeader("User-Agent", USER_AGENT_BROWSER)
                .addHeader("X-Partial", "fav")
                .addHeader("Referer", url)
                .addHeader("Origin", baseUrl)
                .addHeader("Sec-Fetch-Dest", "empty")
                .addHeader("Sec-Fetch-Mode", "cors")
                .addHeader("Sec-Fetch-Site", "same-origin")

            val cookieHeader = sessionManager.getCookieHeader()
            if (cookieHeader.isNotBlank()) {
                requestBuilder.addHeader("Cookie", cookieHeader)
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (response.code == 429) {
                    val responseBody = response.body?.string().orEmpty()
                    val retryAfter = RetryAfterParser.parseRetryAfter(response.header("Retry-After"), responseBody)
                    val cooldownUntil = System.currentTimeMillis() + (retryAfter * 1000L)
                    globalRateLimitedUntil.set(cooldownUntil)
                    throw RateLimitException(
                        retryAfterSeconds = retryAfter,
                        message = "Favorites rate limited: HTTP 429 (Retry-After: ${retryAfter}s)"
                    )
                }

                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("Failed to fetch favorites: HTTP ${response.code}")
                    )
                }

                val responseBody = response.body?.string().orEmpty()

                // Persist any updated session cookies from Set-Cookie headers
                for (cookie in response.headers("Set-Cookie")) {
                    sessionManager.saveFromSetCookieHeader(cookie)
                }

                val parsed = json.decodeFromString<FavoritesResponse>(responseBody)
                Result.success(parsed)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Saves or syncs the user's favorite stations list to the cloud via `POST /favorite.html`.
     *
     * @param stations Complete list of favorite stations to persist in cloud.
     * @param csrf CSRF token for the session.
     * @return Result containing true if successful.
     */
    suspend fun saveFavorites(
        stations: List<FavoriteStationRaw>,
        csrf: String = sessionManager.csrfToken.orEmpty()
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            checkRateLimitOrThrow()
            val url = "$baseUrl/favorite.html"
            val payload = SaveFavoritesRequest(
                action = "save",
                csrf = csrf,
                stations = stations
            )
            val jsonString = json.encodeToString(payload)
            val requestBuilder = Request.Builder()
                .url(url)
                .post(jsonString.toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("User-Agent", USER_AGENT_BROWSER)
                .addHeader("Content-Type", "application/json")
                .addHeader("Referer", url)
                .addHeader("Origin", baseUrl)
                .addHeader("Sec-Fetch-Dest", "empty")
                .addHeader("Sec-Fetch-Mode", "cors")
                .addHeader("Sec-Fetch-Site", "same-origin")

            val cookieHeader = sessionManager.getCookieHeader()
            if (cookieHeader.isNotBlank()) {
                requestBuilder.addHeader("Cookie", cookieHeader)
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("Failed to save favorites: HTTP ${response.code}")
                    )
                }

                // Persist any updated session cookies from Set-Cookie headers
                for (cookie in response.headers("Set-Cookie")) {
                    sessionManager.saveFromSetCookieHeader(cookie)
                }

                Result.success(true)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Queries native background search API for real-time station metrics.
     * Signs the request with HMAC-SHA256 according to EVCS protocol.
     */
    open suspend fun searchStations(
        latitude: Double,
        longitude: Double,
        token: String = DEFAULT_SEARCH_TOKEN
    ): Result<List<SearchStationRaw>> = searchStations(latitude, longitude, token, null)

    /**
     * Queries native background search API with optional wattage filtering (e.g. FAST, SUPER_FAST).
     */
    open suspend fun searchStations(
        latitude: Double,
        longitude: Double,
        wattageTypes: List<String>?
    ): Result<List<SearchStationRaw>> = searchStations(latitude, longitude, DEFAULT_SEARCH_TOKEN, wattageTypes)

    /**
     * Full implementation of background search API with token and optional wattage filtering.
     */
    open suspend fun searchStations(
        latitude: Double,
        longitude: Double,
        token: String,
        wattageTypes: List<String>?
    ): Result<List<SearchStationRaw>> = withContext(Dispatchers.IO) {
        try {
            checkRateLimitOrThrow()
            val url = "$baseUrl/search?t=$token"
            val payload = SearchRequest(
                latitude = latitude,
                longitude = longitude,
                wattageTypes = wattageTypes
            )
            val jsonString = json.encodeToString(payload)
            val timestamp = System.currentTimeMillis().toString()
            val signedHeaders = EvcsHmacSigner.createSignedHeaders(jsonString, timestamp)

            val requestBuilder = Request.Builder()
                .url(url)
                .post(jsonString.toRequestBody(JSON_MEDIA_TYPE))

            for ((headerName, headerValue) in signedHeaders) {
                requestBuilder.addHeader(headerName, headerValue)
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (response.code == 429) {
                    val responseBody = response.body?.string().orEmpty()
                    val retryAfter = RetryAfterParser.parseRetryAfter(response.header("Retry-After"), responseBody)
                    val cooldownUntil = System.currentTimeMillis() + (retryAfter * 1000L)
                    globalRateLimitedUntil.set(cooldownUntil)
                    throw RateLimitException(
                        retryAfterSeconds = retryAfter,
                        message = "Search API rate limited: HTTP 429 (Retry-After: ${retryAfter}s)"
                    )
                }

                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("Search API request failed with HTTP ${response.code}")
                    )
                }

                val responseBody = response.body?.string().orEmpty()
                val parsed = json.decodeFromString<SearchResponse>(responseBody)

                if (parsed.code != 200000 && parsed.code != 0) {
                    return@withContext Result.failure(
                        IOException("Search API returned error code ${parsed.code}: ${parsed.error}")
                    )
                }

                val stations = parsed.data.orEmpty()
                Result.success(stations)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetches the raw HTML content of a station detail page.
     * Uses canonical URL from StationUrlBuilder and mobile browser headers to avoid bot blocks.
     *
     * @param stationName Name of the charging station.
     * @param locationId Unique station identifier.
     * @param evse Station provider/operator name (default "VinFast").
     * @return Result containing raw HTML string if successful, or failure exception.
     */
    open suspend fun fetchStationHtml(
        stationName: String,
        locationId: String,
        evse: String = "VinFast"
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            checkRateLimitOrThrow()
            val url = StationUrlBuilder.buildStationDetailUrl(stationName, locationId, baseUrl, evse)
            val requestBuilder = Request.Builder()
                .url(url)
                .get()
                .addHeader("User-Agent", USER_AGENT_BROWSER)
                .addHeader("Referer", "$baseUrl/")
                .addHeader("Origin", baseUrl)

            val cookieHeader = sessionManager.getCookieHeader()
            if (cookieHeader.isNotBlank()) {
                requestBuilder.addHeader("Cookie", cookieHeader)
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (response.code == 429) {
                    val responseBody = response.body?.string().orEmpty()
                    val retryAfter = RetryAfterParser.parseRetryAfter(response.header("Retry-After"), responseBody)
                    val cooldownUntil = System.currentTimeMillis() + (retryAfter * 1000L)
                    globalRateLimitedUntil.set(cooldownUntil)
                    throw RateLimitException(
                        retryAfterSeconds = retryAfter,
                        message = "Station detail HTML rate limited: HTTP 429 (Retry-After: ${retryAfter}s)"
                    )
                }

                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("Failed to fetch station detail HTML: HTTP ${response.code}")
                    )
                }

                val html = response.body?.string().orEmpty()
                Result.success(html)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Overload for domain [Station] model.
     */
    open suspend fun fetchStationHtml(station: Station): Result<String> =
        fetchStationHtml(station.name, station.id, station.evse)

    /**
     * Fetches station detail HTML page and resolves its GPS coordinates.
     *
     * @param stationName Name of the charging station.
     * @param locationId Unique station identifier.
     * @param evse Station provider/operator name (default "VinFast").
     * @return Result containing Pair(latitude, longitude) if resolved, or null/failure.
     */
    suspend fun fetchStationCoordinates(
        stationName: String,
        locationId: String,
        evse: String = "VinFast"
    ): Result<Pair<Double, Double>?> {
        val htmlResult = fetchStationHtml(stationName, locationId, evse)
        if (htmlResult.isFailure) {
            return Result.failure(htmlResult.exceptionOrNull()!!)
        }
        val coords = parseCoordinatesFromHtml(htmlResult.getOrThrow())
        return Result.success(coords)
    }

    /**
     * Fetches station detail HTML page and extracts lightweight metadata.
     */
    suspend fun fetchStationDetailMetadata(
        stationName: String,
        locationId: String,
        evse: String = "VinFast"
    ): Result<StationDetailMetadata?> {
        val htmlResult = fetchStationHtml(stationName, locationId, evse)
        if (htmlResult.isFailure) {
            return Result.failure(htmlResult.exceptionOrNull()!!)
        }
        val metadata = parseStationMetadataFromHtml(htmlResult.getOrThrow())
        return Result.success(metadata)
    }
}

/**
 * Lightweight station detail metadata parsed from EVCS detail page.
 */
@Serializable
data class StationDetailMetadata(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val address: String? = null,
    val workingTime: String? = null
)

