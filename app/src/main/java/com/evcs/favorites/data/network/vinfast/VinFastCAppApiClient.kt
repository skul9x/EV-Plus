package com.evcs.favorites.data.network.vinfast

import com.evcs.favorites.data.network.AppOkHttpClientProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Direct API client communicating with VinFast Connected Car (CAPP) charging station endpoints:
 * - POST /ccarcharging/api/v1/stations/search?page={page}&size={size}
 * - POST /ccarcharging/api/v1/stations/location-info
 *
 * Configured with a 5-second fast-fail timeout to allow instantaneous failover to Tier 2 (evcs.vn).
 * Reuses the shared OkHttp connection pool and dispatcher via [AppOkHttpClientProvider].
 */
open class VinFastCAppApiClient(
    val baseUrl: String = DEFAULT_BASE_URL,
    client: OkHttpClient? = null,
    headerInterceptor: VinFastHeaderInterceptor = VinFastHeaderInterceptor(),
    timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    private val json: Json = defaultJson,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val client: OkHttpClient = client ?: defaultClient(headerInterceptor, timeoutSeconds)

    companion object {
        const val DEFAULT_BASE_URL = "https://mobile.connected-car.vinfast.vn"
        const val DEFAULT_TIMEOUT_SECONDS = 5L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        val defaultJson: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
        }

        fun defaultClient(
            interceptor: VinFastHeaderInterceptor = VinFastHeaderInterceptor(),
            timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS
        ): OkHttpClient {
            return AppOkHttpClientProvider.newSharedClientBuilder()
                .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .addInterceptor(interceptor)
                .build()
        }
    }

    /**
     * Searches charging stations near the provided coordinates.
     * `page` and `size` are passed as URL query parameters.
     */
    open suspend fun searchStations(
        lat: Double,
        lon: Double,
        page: Int = 0,
        size: Int = 50,
        province: String? = null,
        district: String? = null,
        wattageTypes: List<String>? = null,
        parkingFee: Boolean? = null,
        freeParking: Boolean? = null,
        excludeFavorite: Boolean? = null
    ): Result<List<VinFastStationStatusDto>> = withContext(ioDispatcher) {
        val searchRequest = VinFastSearchRequest(
            latitude = lat,
            longitude = lon,
            province = province,
            district = district,
            wattageTypes = wattageTypes,
            parkingFee = parkingFee,
            freeParking = freeParking,
            excludeFavorite = excludeFavorite
        )

        val normalizedBaseUrl = baseUrl.trimEnd('/')
        val url = "$normalizedBaseUrl/ccarcharging/api/v1/stations/search?page=$page&size=$size"
        val requestJson = json.encodeToString(searchRequest)
        val requestBody = requestJson.toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        executeRequest(request)
    }

    /**
     * Retrieves status information for specific station location IDs.
     */
    open suspend fun getLocationInfo(
        locationIds: List<String>
    ): Result<List<VinFastStationStatusDto>> = withContext(ioDispatcher) {
        val locationRequest = VinFastLocationInfoRequest(locationIds = locationIds)
        val normalizedBaseUrl = baseUrl.trimEnd('/')
        val url = "$normalizedBaseUrl/ccarcharging/api/v1/stations/location-info"
        val requestJson = json.encodeToString(locationRequest)
        val requestBody = requestJson.toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        executeRequest(request)
    }

    private fun executeRequest(request: Request): Result<List<VinFastStationStatusDto>> {
        return try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val errorResponse = runCatching {
                        json.decodeFromString<VinFastBaseResponse<JsonElement>>(responseBody)
                    }.getOrNull()
                    val errorCode = errorResponse?.code ?: response.code
                    val errorMessage = errorResponse?.message ?: response.message.ifBlank { "HTTP ${response.code}" }
                    return Result.failure(VinFastApiException(code = errorCode, message = errorMessage))
                }

                val parsed = json.decodeFromString<VinFastBaseResponse<List<VinFastStationStatusDto>>>(responseBody)
                if (parsed.code != null && parsed.code != 200 && parsed.code != 0) {
                    return Result.failure(
                        VinFastApiException(code = parsed.code, message = parsed.message ?: "VinFast API error")
                    )
                }

                Result.success(parsed.data ?: emptyList())
            }
        } catch (e: VinFastApiException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(VinFastApiException(code = null, message = e.message ?: "Network error", cause = e))
        }
    }
}
