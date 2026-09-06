package com.evcs.favorites.data.network.vinfast

import android.content.Context
import android.os.Build
import okhttp3.Interceptor
import okhttp3.Response
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Manages persistent device identifier for VinFast CAPP requests.
 */
object VinFastDeviceIdProvider {
    @Volatile
    private var cachedDeviceId: String? = null

    fun getDeviceId(context: Context? = null): String {
        cachedDeviceId?.let { return it }
        synchronized(this) {
            cachedDeviceId?.let { return it }
            if (context != null) {
                try {
                    val prefs = context.getSharedPreferences("vinfast_capp_prefs", Context.MODE_PRIVATE)
                    val existing = prefs.getString("device_id", null)
                    if (!existing.isNullOrBlank()) {
                        cachedDeviceId = existing
                        return existing
                    }
                    val newId = UUID.randomUUID().toString()
                    prefs.edit().putString("device_id", newId).apply()
                    cachedDeviceId = newId
                    return newId
                } catch (_: Throwable) {
                    // Fallback to in-memory UUID if Context/SharedPreferences fails (e.g. unit test)
                }
            }
            val fallbackId = UUID.randomUUID().toString()
            cachedDeviceId = fallbackId
            return fallbackId
        }
    }

    /**
     * For unit tests only: allows overriding deviceId or resetting.
     */
    fun setDeviceIdForTesting(id: String?) {
        cachedDeviceId = id
    }
}

/**
 * Injects official VinFast CAPP headers matching production TokenInterceptor from the VinFast APK.
 */
class VinFastHeaderInterceptor(
    private val deviceIdProvider: () -> String = { VinFastDeviceIdProvider.getDeviceId() },
    private val tokenProvider: (() -> String?)? = null,
    private val appVersion: String = DEFAULT_APP_VERSION
) : Interceptor {

    companion object {
        const val DEFAULT_APP_VERSION = "2.25.7"
        const val SERVICE_NAME_CAPP = "CAPP"
        const val PLATFORM_ANDROID = "android"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val deviceId = deviceIdProvider().ifBlank { VinFastDeviceIdProvider.getDeviceId() }
        val model = try {
            Build.MODEL?.takeIf { it.isNotBlank() } ?: "android"
        } catch (_: Throwable) {
            "android"
        }
        val release = try {
            Build.VERSION.RELEASE?.takeIf { it.isNotBlank() } ?: "14"
        } catch (_: Throwable) {
            "14"
        }
        val locale = try {
            Locale.getDefault().toString().ifBlank { "vi_VN" }
        } catch (_: Throwable) {
            "vi_VN"
        }
        val timezone = try {
            TimeZone.getDefault().id.ifBlank { "Asia/Ho_Chi_Minh" }
        } catch (_: Throwable) {
            "Asia/Ho_Chi_Minh"
        }

        val builder = originalRequest.newBuilder()
            .header("X-APP-VERSION", appVersion)
            .header("X-SERVICE-NAME", SERVICE_NAME_CAPP)
            .header("X-Device-Platform", PLATFORM_ANDROID)
            .header("X-Device-Family", model)
            .header("X-Device-Identifier", deviceId)
            .header("X-Device-Locale", locale)
            .header("X-Device-OS-Version", "android $release")
            .header("X-TIMESTAMP", System.currentTimeMillis().toString())
            .header("X-Timezone", timezone)
            .header("User-Agent", "android - $deviceId - $appVersion")
            .header("Content-Type", "application/json; charset=UTF-8")
            .header("Accept", "application/json")

        val token = tokenProvider?.invoke()
        if (!token.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $token")
        }

        return chain.proceed(builder.build())
    }
}
