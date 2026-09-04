package com.evcs.favorites.data.routing

import android.content.Context
import com.evcs.favorites.data.auth.EncryptedSharedPrefsStorage
import com.evcs.favorites.data.auth.SessionStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Manages secure persistent storage of user routing preferences and Google BYOK API Key.
 *
 * Utilizes the [SessionStorage] abstraction:
 * - [EncryptedSharedPrefsStorage] for AES-256 encrypted persistence in Android production.
 * - [InMemorySessionStorage] for fast JVM unit tests.
 *
 * Provides real-time [StateFlow] reactive observation and proactive Google Cloud Routes API key validation.
 */
class RoutingPreferencesManager(
    private val storage: SessionStorage,
    private val okHttpClient: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = GoogleRoutesClient.DEFAULT_BASE_URL,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    companion object {
        const val KEY_GOOGLE_API_KEY = "routing_google_api_key"
        const val KEY_PREFERRED_ENGINE = "routing_preferred_engine"
        const val KEY_AUTO_FALLBACK = "routing_auto_fallback"
        const val KEY_CUSTOM_OSRM_URL = "routing_custom_osrm_url"

        /**
         * Factory helper for production Android application instantiation.
         */
        fun create(context: Context): RoutingPreferencesManager {
            return RoutingPreferencesManager(
                storage = EncryptedSharedPrefsStorage.getInstance(context)
            )
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<RoutingSettings> = _settings.asStateFlow()

    /**
     * Reads persisted preferences from storage, returning default [RoutingSettings]
     * if preferences have not been customized.
     */
    fun loadSettings(): RoutingSettings {
        val apiKey = storage.getString(KEY_GOOGLE_API_KEY).orEmpty()
        val engineStr = storage.getString(KEY_PREFERRED_ENGINE)
        val engine = engineStr?.let {
            try {
                RoutingEngineMode.valueOf(it)
            } catch (e: Exception) {
                RoutingEngineMode.AUTO
            }
        } ?: RoutingEngineMode.AUTO

        val fallbackStr = storage.getString(KEY_AUTO_FALLBACK)
        val fallback = fallbackStr?.toBooleanStrictOrNull() ?: true
        val customOsrm = storage.getString(KEY_CUSTOM_OSRM_URL)

        return RoutingSettings(
            googleApiKey = apiKey,
            preferredEngine = engine,
            autoFallbackEnabled = fallback,
            customOsrmServerUrl = customOsrm
        )
    }

    /**
     * Persists updated routing settings to secure storage and emits the new state to observers.
     */
    fun saveSettings(newSettings: RoutingSettings) {
        storage.putString(KEY_GOOGLE_API_KEY, newSettings.googleApiKey)
        storage.putString(KEY_PREFERRED_ENGINE, newSettings.preferredEngine.name)
        storage.putString(KEY_AUTO_FALLBACK, newSettings.autoFallbackEnabled.toString())
        if (newSettings.customOsrmServerUrl != null) {
            storage.putString(KEY_CUSTOM_OSRM_URL, newSettings.customOsrmServerUrl)
        } else {
            storage.remove(KEY_CUSTOM_OSRM_URL)
        }
        _settings.value = newSettings
    }

    /**
     * Convenience updater for the Google API Key.
     */
    fun updateGoogleApiKey(apiKey: String) {
        saveSettings(_settings.value.copy(googleApiKey = apiKey))
    }

    /**
     * Convenience updater for the preferred routing engine mode.
     */
    fun updatePreferredEngine(mode: RoutingEngineMode) {
        saveSettings(_settings.value.copy(preferredEngine = mode))
    }

    /**
     * Convenience updater for auto-fallback cascading.
     */
    fun updateAutoFallback(enabled: Boolean) {
        saveSettings(_settings.value.copy(autoFallbackEnabled = enabled))
    }

    /**
     * Sends a minimal 1-element probe request to verify the validity of the Google Cloud Routes API key.
     * Returns actionable error messages for HTTP 400, 403 (Billing, Routes API, Restrictions), and 429.
     */
    suspend fun validateGoogleApiKey(apiKey: String): Result<Boolean> = withContext(ioDispatcher) {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isBlank()) {
            return@withContext Result.failure(
                RoutingApiException(400, "Khóa API Google không hợp lệ. Vui lòng kiểm tra lại ký tự khóa.")
            )
        }

        val probePayload = GoogleRouteMatrixRequest(
            origins = listOf(
                GoogleRouteWaypointWrapper(
                    waypoint = GoogleRouteWaypoint(
                        location = GoogleRouteLocation(
                            latLng = GoogleLatLng(latitude = 10.762622, longitude = 106.660172)
                        )
                    )
                )
            ),
            destinations = listOf(
                GoogleRouteWaypointWrapper(
                    waypoint = GoogleRouteWaypoint(
                        location = GoogleRouteLocation(
                            latLng = GoogleLatLng(latitude = 10.772622, longitude = 106.670172)
                        )
                    )
                )
            ),
            travelMode = "DRIVE",
            routingPreference = "TRAFFIC_AWARE"
        )

        val jsonBody = json.encodeToString(probePayload)
        val endpointUrl = "${baseUrl.trimEnd('/')}/distanceMatrix/v2:computeRouteMatrix"

        val request = Request.Builder()
            .url(endpointUrl)
            .addHeader("Content-Type", "application/json")
            .addHeader(GoogleRoutesClient.HEADER_API_KEY, trimmedKey)
            .addHeader(GoogleRoutesClient.HEADER_FIELD_MASK, GoogleRoutesClient.FIELD_MASK_VALUE)
            .addHeader(GoogleRoutesClient.HEADER_ANDROID_PACKAGE, GoogleRoutesClient.ANDROID_PACKAGE_VALUE)
            .post(jsonBody.toRequestBody(jsonMediaType))
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    try {
                        val elements = json.decodeFromString<List<GoogleRouteMatrixElement>>(responseBody)
                        val first = elements.firstOrNull()
                        if (first?.status != null && first.status.code != 0) {
                            val msg = mapErrorMessage(400, first.status.message.orEmpty())
                            return@withContext Result.failure(RoutingApiException(400, msg))
                        }
                    } catch (e: Exception) {
                        // Response code is 200 OK, proceed
                    }
                    return@withContext Result.success(true)
                }

                val statusCode = response.code
                val errorMessage = mapErrorMessage(statusCode, responseBody)
                return@withContext Result.failure(RoutingApiException(statusCode, errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun mapErrorMessage(statusCode: Int, responseBody: String): String {
        val lower = responseBody.lowercase()
        return when (statusCode) {
            400 -> "Khóa API Google không hợp lệ. Vui lòng kiểm tra lại ký tự khóa."
            403 -> {
                when {
                    lower.contains("billing") ->
                        "Dự án Google Cloud chưa kích hoạt thanh toán (Billing)."
                    lower.contains("routes") || lower.contains("service_disabled") || lower.contains("not enabled") || lower.contains("has not been used") ->
                        "Chưa kích hoạt 'Routes API' trên dự án Google Cloud của bạn."
                    lower.contains("restriction") || lower.contains("blocked") || lower.contains("ip") || lower.contains("package") || lower.contains("android") ->
                        "Khóa API bị giới hạn ứng dụng hoặc IP. Vui lòng kiểm tra cài đặt hạn chế trên Google Cloud."
                    else ->
                        "Khóa API bị giới hạn ứng dụng hoặc IP. Vui lòng kiểm tra cài đặt hạn chế trên Google Cloud."
                }
            }
            429 -> "Vượt quá hạn ngạch yêu cầu của Google Cloud API."
            else -> "Lỗi kết nối Google Cloud Routes API (HTTP $statusCode)."
        }
    }
}
