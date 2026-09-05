package com.evcs.favorites.data.auth

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.evcs.favorites.data.network.AppOkHttpClientProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Serializable
data class SendOtpRequest(
    val action: String = "send_otp",
    val csrf: String,
    val email: String,
    val agree: Boolean = true
)

@Serializable
data class SendOtpResponse(
    val ok: Boolean,
    val resend_in: Int? = null,
    val error: String? = null,
    val dev_otp: String? = null,
    val retry_after: Int? = null
)

@Serializable
data class VerifyOtpRequest(
    val action: String = "verify_otp",
    val csrf: String,
    val email: String,
    val otp: String
)

@Serializable
data class VerifyOtpResponse(
    val ok: Boolean,
    val error: String? = null
)

/**
 * Authentication Engine handling the EVCS 3-step Email OTP flow:
 * 1. `fetchCsrfToken()`: Extracts initial CSRF token & PHPSESSID from reward.html
 * 2. `sendOtp(email)`: Triggers 6-digit OTP delivery to user email
 * 3. `verifyOtp(email, otp)`: Verifies OTP, extracts 1-year `evcs` session cookie, persists session
 *
 * Exposes `isLoggedIn` reactive StateFlow and `logout()` method.
 */
class AuthEngine(
    val sessionManager: SessionManager,
    private val client: OkHttpClient = defaultClient(),
    private val baseUrl: String = DEFAULT_BASE_URL
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://evcs.vn"
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36 EVCS/A1.57 Mobile"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

        private val CSRF_PRIMARY_REGEX = Regex("""window\.EVCS_REWARD\s*=\s*\{[^}]*csrf\s*:\s*["']([^"']+)["']""")
        private val CSRF_FALLBACK_REGEX = Regex("""csrf\s*:\s*["']([a-fA-F0-9]{16,64})["']""")

        internal fun extractCsrfToken(html: String): String? {
            val match = CSRF_PRIMARY_REGEX.find(html)
            if (match != null) {
                return match.groupValues[1]
            }
            return CSRF_FALLBACK_REGEX.find(html)?.groupValues?.get(1)
        }

        private fun defaultClient(): OkHttpClient {
            return AppOkHttpClientProvider.getSharedClient().newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    /**
     * Asynchronously verifies authentication status on [dispatcher] without blocking the Main Thread,
     * updating [isLoggedIn] StateFlow.
     */
    suspend fun checkLoggedInAsync(dispatcher: CoroutineDispatcher = Dispatchers.IO): Boolean = withContext(dispatcher) {
        val loggedIn = sessionManager.checkAuthCookieAsync(dispatcher)
        _isLoggedIn.value = loggedIn
        loggedIn
    }

    /**
     * Step 1: Fetches the reward.html page snippet to extract CSRF token and PHPSESSID.
     */
    suspend fun fetchCsrfToken(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val rewardUrl = "$baseUrl/reward.html"
            val requestBuilder = Request.Builder()
                .url(rewardUrl)
                .post("".toRequestBody())
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("X-Partial", "reward")
                .addHeader("Referer", rewardUrl)
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
                        java.io.IOException("Lỗi kết nối EVCS (HTTP ${response.code}). Vui lòng tắt VPN (ProtonVPN) hoặc đổi mạng.")
                    )
                }
                val responseBody = response.body?.string().orEmpty()
                if (responseBody.contains("Just a moment...") || responseBody.contains("challenges.cloudflare.com")) {
                    return@withContext Result.failure(
                        java.io.IOException("EVCS bị chặn bởi Cloudflare. Vui lòng tắt VPN (ProtonVPN) hoặc đổi mạng.")
                    )
                }

                // Save Set-Cookie headers (such as PHPSESSID)
                val setCookieHeaders = response.headers("Set-Cookie")
                for (header in setCookieHeaders) {
                    sessionManager.saveFromSetCookieHeader(header)
                }

                // Extract CSRF token from script tag
                val csrfToken = extractCsrfToken(responseBody)
                if (csrfToken.isNullOrBlank()) {
                    return@withContext Result.failure(
                        IllegalStateException("Không thể trích xuất mã CSRF từ phản hồi của EVCS")
                    )
                }

                sessionManager.csrfToken = csrfToken
                Result.success(csrfToken)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Step 2: Requests OTP sent to the provided email address.
     */
    suspend fun sendOtp(email: String): Result<SendOtpResponse> = withContext(Dispatchers.IO) {
        try {
            var csrf = sessionManager.csrfToken
            if (csrf.isNullOrBlank()) {
                val fetchResult = fetchCsrfToken()
                if (fetchResult.isFailure) {
                    return@withContext Result.failure(fetchResult.exceptionOrNull()!!)
                }
                csrf = fetchResult.getOrThrow()
            }

            val rewardUrl = "$baseUrl/reward.html"
            val requestPayload = SendOtpRequest(
                action = "send_otp",
                csrf = csrf,
                email = email.trim(),
                agree = true
            )
            val body = json.encodeToString(requestPayload).toRequestBody(JSON_MEDIA_TYPE)

            val requestBuilder = Request.Builder()
                .url(rewardUrl)
                .post(body)
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Referer", rewardUrl)
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
                        java.io.IOException("Lỗi gửi OTP (HTTP ${response.code}). Vui lòng tắt VPN hoặc đổi mạng.")
                    )
                }
                val responseBody = response.body?.string().orEmpty()
                if (responseBody.contains("Just a moment...") || responseBody.contains("challenges.cloudflare.com")) {
                    return@withContext Result.failure(
                        java.io.IOException("EVCS bị chặn bởi Cloudflare. Vui lòng tắt VPN (ProtonVPN).")
                    )
                }

                // Check for cookie updates
                for (header in response.headers("Set-Cookie")) {
                    sessionManager.saveFromSetCookieHeader(header)
                }

                val otpResponse = json.decodeFromString<SendOtpResponse>(responseBody)
                if (otpResponse.ok) {
                    sessionManager.userEmail = email.trim()
                    Result.success(otpResponse)
                } else {
                    Result.failure(Exception(otpResponse.error ?: "Failed to send OTP"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Step 3: Verifies the 6-digit OTP code, extracts 1-year `evcs` session cookie, and persists login.
     */
    suspend fun verifyOtp(email: String, otp: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val csrf = sessionManager.csrfToken
                ?: return@withContext Result.failure(IllegalStateException("Missing CSRF token"))

            val rewardUrl = "$baseUrl/reward.html"
            val requestPayload = VerifyOtpRequest(
                action = "verify_otp",
                csrf = csrf,
                email = email.trim(),
                otp = otp.trim()
            )
            val body = json.encodeToString(requestPayload).toRequestBody(JSON_MEDIA_TYPE)

            val requestBuilder = Request.Builder()
                .url(rewardUrl)
                .post(body)
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Referer", rewardUrl)
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
                        java.io.IOException("Lỗi xác thực (HTTP ${response.code}). Vui lòng tắt VPN hoặc đổi mạng.")
                    )
                }
                val responseBody = response.body?.string().orEmpty()
                if (responseBody.contains("Just a moment...") || responseBody.contains("challenges.cloudflare.com")) {
                    return@withContext Result.failure(
                        java.io.IOException("EVCS bị chặn bởi Cloudflare. Vui lòng tắt VPN (ProtonVPN).")
                    )
                }

                // Extract Set-Cookie headers for 1-year evcs auth cookie
                for (header in response.headers("Set-Cookie")) {
                    sessionManager.saveFromSetCookieHeader(header)
                }

                val verifyResponse = json.decodeFromString<VerifyOtpResponse>(responseBody)
                if (verifyResponse.ok) {
                    _isLoggedIn.value = true
                    sessionManager.userEmail = email.trim()
                    Result.success(true)
                } else {
                    Result.failure(Exception(verifyResponse.error ?: "Invalid OTP"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Logs out user, clears all session credentials, and updates StateFlow.
     */
    fun logout() {
        sessionManager.clearSession()
        _isLoggedIn.value = false
    }

    /**
     * Extracts CSRF token from EVCS_REWARD script in HTML response.
     */
    internal fun extractCsrfToken(html: String): String? =
        Companion.extractCsrfToken(html)
}
