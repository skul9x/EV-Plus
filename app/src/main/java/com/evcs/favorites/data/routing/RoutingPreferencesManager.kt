package com.evcs.favorites.data.routing

import android.content.Context
import com.evcs.favorites.data.auth.PlainSharedPrefsStorage
import com.evcs.favorites.data.auth.SessionStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.evcs.favorites.data.network.AppOkHttpClientProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Manages persistent storage of user routing preferences and Google BYOK API Key.
 *
 * Utilizes the [SessionStorage] abstraction:
 * - [PlainSharedPrefsStorage] for zero-overhead persistence in Android production.
 * - [InMemorySessionStorage] for fast JVM unit tests.
 *
 * Provides real-time [StateFlow] reactive observation and proactive Google Cloud Routes API key validation.
 */
class RoutingPreferencesManager(
    val storage: SessionStorage,
    private val okHttpClient: OkHttpClient = AppOkHttpClientProvider.getSharedClient().newBuilder().build(),
    private val baseUrl: String = GoogleRoutesClient.DEFAULT_BASE_URL,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    val storageAccess: SessionStorage get() = storage

    private val _serializationCount = java.util.concurrent.atomic.AtomicInteger(0)
    val serializationCount: Int get() = _serializationCount.get()

    private val _writeCount = java.util.concurrent.atomic.AtomicInteger(0)
    val writeCount: Int get() = _writeCount.get()

    fun resetWriteCountsForTesting() {
        _serializationCount.set(0)
        _writeCount.set(0)
    }
    companion object {
        const val KEY_GOOGLE_API_KEY = "routing_google_api_key"
        const val KEY_PREFERRED_ENGINE = "routing_preferred_engine"
        const val KEY_AUTO_FALLBACK = "routing_auto_fallback"
        const val KEY_CUSTOM_OSRM_URL = "routing_custom_osrm_url"

        const val KEY_EV_SAFE_RANGE_KM = "routing_ev_safe_range_km"
        const val KEY_EV_START_BATTERY_PERCENT = "routing_ev_start_battery_percent"
        const val KEY_EV_ARRIVAL_BUFFER_SOC_PERCENT = "routing_ev_arrival_buffer_soc_percent"
        const val KEY_EV_TARGET_CHARGING_SOC_PERCENT = "routing_ev_target_charging_soc_percent"
        const val KEY_EV_SAFETY_DURATION_BUFFER_ENABLED = "routing_ev_safety_duration_buffer_enabled"
        const val KEY_EV_SAFETY_DURATION_BUFFER_RATIO = "routing_ev_safety_duration_buffer_ratio"
        const val KEY_EV_ROUTING_SETTINGS_JSON = "routing_ev_settings_json"

        /**
         * Factory helper for production Android application instantiation.
         */
        fun create(context: Context): RoutingPreferencesManager {
            return RoutingPreferencesManager(
                storage = PlainSharedPrefsStorage.getInstance(context, "evcs_routing_prefs")
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

    private val _evSettings = MutableStateFlow(_settings.value.evSettings)
    val evSettings: StateFlow<EvRoutingSettings> = _evSettings.asStateFlow()
    val evRoutingSettings: StateFlow<EvRoutingSettings> get() = evSettings

    /**
     * Reads persisted preferences from storage, returning default [RoutingSettings]
     * if preferences have not been customized.
     */
    fun loadSettings(): RoutingSettings {
        val apiKey = storage.getString(KEY_GOOGLE_API_KEY).orEmpty()
        val engineStr = storage.getString(KEY_PREFERRED_ENGINE)
        val engine = when {
            engineStr.isNullOrBlank() || engineStr == "AUTO" -> RoutingEngineMode.OSRM_ONLY
            else -> try {
                RoutingEngineMode.valueOf(engineStr)
            } catch (e: Exception) {
                RoutingEngineMode.OSRM_ONLY
            }
        }

        val fallbackStr = storage.getString(KEY_AUTO_FALLBACK)
        val fallback = fallbackStr?.toBooleanStrictOrNull() ?: true
        val customOsrm = storage.getString(KEY_CUSTOM_OSRM_URL)
        val evSettings = loadEvSettings()

        return RoutingSettings(
            googleApiKey = apiKey,
            preferredEngine = engine,
            autoFallbackEnabled = fallback,
            customOsrmServerUrl = customOsrm,
            evSettings = evSettings
        )
    }

    /**
     * Reads EV routing settings from storage, falling back to defaults and applying boundary clamping.
     */
    fun loadEvSettings(): EvRoutingSettings {
        val jsonStr = storage.getString(KEY_EV_ROUTING_SETTINGS_JSON)
        val parsedFromJson = if (!jsonStr.isNullOrBlank()) {
            try {
                json.decodeFromString<EvRoutingSettings>(jsonStr)
            } catch (e: Exception) {
                null
            }
        } else null

        val safeRange = storage.getString(KEY_EV_SAFE_RANGE_KM)?.toIntOrNull()
            ?: parsedFromJson?.vehicleSafeRangeKm
            ?: EvRoutingSettings.DEFAULT_VEHICLE_SAFE_RANGE_KM

        val startBattery = storage.getString(KEY_EV_START_BATTERY_PERCENT)?.toIntOrNull()
            ?: parsedFromJson?.startBatteryPercent
            ?: EvRoutingSettings.DEFAULT_START_BATTERY_PERCENT

        val arrivalBuffer = storage.getString(KEY_EV_ARRIVAL_BUFFER_SOC_PERCENT)?.toIntOrNull()
            ?: parsedFromJson?.arrivalBufferSocPercent
            ?: EvRoutingSettings.DEFAULT_ARRIVAL_BUFFER_SOC_PERCENT

        val targetSoc = storage.getString(KEY_EV_TARGET_CHARGING_SOC_PERCENT)?.toIntOrNull()
            ?: parsedFromJson?.targetChargingSocPercent
            ?: EvRoutingSettings.DEFAULT_TARGET_CHARGING_SOC_PERCENT

        val durationBufferEnabled = storage.getString(KEY_EV_SAFETY_DURATION_BUFFER_ENABLED)?.toBooleanStrictOrNull()
            ?: parsedFromJson?.safetyDurationBufferEnabled
            ?: EvRoutingSettings.DEFAULT_SAFETY_DURATION_BUFFER_ENABLED

        val durationBufferRatio = storage.getString(KEY_EV_SAFETY_DURATION_BUFFER_RATIO)?.toFloatOrNull()
            ?: parsedFromJson?.safetyDurationBufferRatio
            ?: EvRoutingSettings.DEFAULT_SAFETY_DURATION_BUFFER_RATIO

        return EvRoutingSettings(
            vehicleSafeRangeKm = safeRange,
            startBatteryPercent = startBattery,
            arrivalBufferSocPercent = arrivalBuffer,
            targetChargingSocPercent = targetSoc,
            safetyDurationBufferEnabled = durationBufferEnabled,
            safetyDurationBufferRatio = durationBufferRatio
        ).sanitized()
    }

    /**
     * Persists updated routing settings to secure storage and emits the new state to observers.
     */
    fun saveSettings(newSettings: RoutingSettings) {
        val sanitizedEv = newSettings.evSettings.sanitized()
        val sanitizedSettings = newSettings.copy(evSettings = sanitizedEv)

        _serializationCount.incrementAndGet()
        val serializedJson = try {
            json.encodeToString(sanitizedEv)
        } catch (e: Exception) {
            null
        }

        val entries = mutableMapOf<String, String?>(
            KEY_GOOGLE_API_KEY to sanitizedSettings.googleApiKey,
            KEY_PREFERRED_ENGINE to sanitizedSettings.preferredEngine.name,
            KEY_AUTO_FALLBACK to sanitizedSettings.autoFallbackEnabled.toString(),
            KEY_CUSTOM_OSRM_URL to sanitizedSettings.customOsrmServerUrl,
            KEY_EV_SAFE_RANGE_KM to sanitizedEv.vehicleSafeRangeKm.toString(),
            KEY_EV_START_BATTERY_PERCENT to sanitizedEv.startBatteryPercent.toString(),
            KEY_EV_ARRIVAL_BUFFER_SOC_PERCENT to sanitizedEv.arrivalBufferSocPercent.toString(),
            KEY_EV_TARGET_CHARGING_SOC_PERCENT to sanitizedEv.targetChargingSocPercent.toString(),
            KEY_EV_SAFETY_DURATION_BUFFER_ENABLED to sanitizedEv.safetyDurationBufferEnabled.toString(),
            KEY_EV_SAFETY_DURATION_BUFFER_RATIO to sanitizedEv.safetyDurationBufferRatio.toString()
        )
        if (serializedJson != null) {
            entries[KEY_EV_ROUTING_SETTINGS_JSON] = serializedJson
        }
        storage.putStrings(entries)
        _writeCount.incrementAndGet()

        _settings.value = sanitizedSettings
        _evSettings.value = sanitizedEv
    }

    private fun saveEvSettingsToStorage(ev: EvRoutingSettings) {
        _serializationCount.incrementAndGet()
        val serializedJson = try {
            json.encodeToString(ev)
        } catch (e: Exception) {
            null
        }

        val entries = mutableMapOf<String, String?>(
            KEY_EV_SAFE_RANGE_KM to ev.vehicleSafeRangeKm.toString(),
            KEY_EV_START_BATTERY_PERCENT to ev.startBatteryPercent.toString(),
            KEY_EV_ARRIVAL_BUFFER_SOC_PERCENT to ev.arrivalBufferSocPercent.toString(),
            KEY_EV_TARGET_CHARGING_SOC_PERCENT to ev.targetChargingSocPercent.toString(),
            KEY_EV_SAFETY_DURATION_BUFFER_ENABLED to ev.safetyDurationBufferEnabled.toString(),
            KEY_EV_SAFETY_DURATION_BUFFER_RATIO to ev.safetyDurationBufferRatio.toString()
        )
        if (serializedJson != null) {
            entries[KEY_EV_ROUTING_SETTINGS_JSON] = serializedJson
        }
        storage.putStrings(entries)
        _writeCount.incrementAndGet()
    }

    /**
     * Convenience updater for EV-specific routing configurations.
     */
    fun updateEvRoutingSettings(evSettings: EvRoutingSettings) {
        val sanitizedEv = evSettings.sanitized()
        saveEvSettingsToStorage(sanitizedEv)
        _settings.value = _settings.value.copy(evSettings = sanitizedEv)
        _evSettings.value = sanitizedEv
    }

    /**
     * Convenience updater for vehicle safe range in km.
     */
    fun updateVehicleSafeRangeKm(rangeKm: Int) {
        updateEvRoutingSettings(_settings.value.evSettings.copy(vehicleSafeRangeKm = rangeKm))
    }

    /**
     * Convenience updater for trip starting battery percentage.
     */
    fun updateStartBatteryPercent(percent: Int) {
        updateEvRoutingSettings(_settings.value.evSettings.copy(startBatteryPercent = percent))
    }

    /**
     * Convenience updater for destination arrival safety reserve buffer percentage.
     */
    fun updateArrivalBufferSocPercent(percent: Int) {
        updateEvRoutingSettings(_settings.value.evSettings.copy(arrivalBufferSocPercent = percent))
    }

    /**
     * Convenience updater for maximum target charging SoC percentage.
     */
    fun updateTargetChargingSocPercent(percent: Int) {
        updateEvRoutingSettings(_settings.value.evSettings.copy(targetChargingSocPercent = percent))
    }

    /**
     * Convenience updater for safety duration buffer toggle and ratio.
     */
    fun updateSafetyDurationBuffer(enabled: Boolean, ratio: Float = _settings.value.evSettings.safetyDurationBufferRatio) {
        updateEvRoutingSettings(
            _settings.value.evSettings.copy(
                safetyDurationBufferEnabled = enabled,
                safetyDurationBufferRatio = ratio
            )
        )
    }

    /**
     * Convenience updater for safety duration buffer toggle.
     */
    fun updateSafetyDurationBufferEnabled(enabled: Boolean) {
        updateEvRoutingSettings(_settings.value.evSettings.copy(safetyDurationBufferEnabled = enabled))
    }

    /**
     * Convenience updater for safety duration buffer ratio.
     */
    fun updateSafetyDurationBufferRatio(ratio: Float) {
        updateEvRoutingSettings(_settings.value.evSettings.copy(safetyDurationBufferRatio = ratio))
    }

    /**
     * Resets EV routing settings to default values.
     */
    fun resetEvRoutingSettings() {
        updateEvRoutingSettings(EvRoutingSettings())
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
