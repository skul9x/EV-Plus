package com.evcs.favorites.data.network.here

import com.evcs.favorites.data.network.AppOkHttpClientProvider
import com.evcs.favorites.data.network.here.model.HereTokenResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * In-memory representation of a cached HERE access token.
 */
data class HereCachedToken(
    val accessToken: String,
    val tokenType: String = "bearer",
    val expiresInSeconds: Long,
    val acquiredAtEpochMs: Long
) {
    val expiresAtEpochMs: Long
        get() = acquiredAtEpochMs + (expiresInSeconds * 1000L)

    /**
     * Checks if the cached token is valid with proactive expiry buffer.
     * Defaults to 10 minutes (600 seconds) safety buffer as per Tier 1 spec.
     */
    fun isFresh(currentTimeMs: Long, bufferMinutes: Long = 10L): Boolean {
        val remainingMs = expiresAtEpochMs - currentTimeMs
        return remainingMs > (bufferMinutes * 60 * 1000L)
    }
}

/**
 * Manages OAuth 1.0a HMAC-SHA256 Client Credentials authentication against HERE IAM.
 * Handles:
 * - RFC 5849 compliant parameter normalization, base string creation, and HMAC-SHA256 signing.
 * - Thread-safe in-memory caching and proactive token refresh (10 minutes before expiry).
 * - Bounded network timeout (5 seconds).
 */
class HereOAuthManager(
    private val accessKeyId: String = DEFAULT_ACCESS_KEY_ID,
    private val accessKeySecret: String = DEFAULT_ACCESS_KEY_SECRET,
    private val tokenUrl: String = DEFAULT_TOKEN_URL,
    private val client: OkHttpClient = defaultClient(),
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        const val DEFAULT_TOKEN_URL = "https://account.api.here.com/oauth2/token"
        const val DEFAULT_ACCESS_KEY_ID = "jE5xt50qC7oIh1f32qMzA6hGznIU5mgH"
        const val DEFAULT_ACCESS_KEY_SECRET = "vinfast-here-secret"

        private val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()

        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        private fun defaultClient(): OkHttpClient {
            return AppOkHttpClientProvider.getSharedClient().newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.SECONDS)
                .build()
        }

        /**
         * RFC 3986 percent-encoding implementation for OAuth 1.0a.
         * Characters in [a-zA-Z0-9-._~] remain unencoded; all other octets are %XX uppercase hex.
         */
        fun percentEncode(value: String): String {
            val sb = StringBuilder()
            for (b in value.toByteArray(StandardCharsets.UTF_8)) {
                val c = b.toInt() and 0xFF
                if ((c in 'a'.code..'z'.code) || (c in 'A'.code..'Z'.code) ||
                    (c in '0'.code..'9'.code) || c == '-'.code || c == '.'.code ||
                    c == '_'.code || c == '~'.code
                ) {
                    sb.append(c.toChar())
                } else {
                    sb.append(String.format("%%%02X", c))
                }
            }
            return sb.toString()
        }

        /**
         * Normalizes request parameters according to RFC 5849 Section 3.4.1.3.2.
         * Sorts encoded pairs lexicographically by name, then by value.
         */
        fun normalizeParameters(params: Map<String, String>): String {
            return params.entries
                .map { (key, value) -> percentEncode(key) to percentEncode(value) }
                .sortedWith(compareBy({ it.first }, { it.second }))
                .joinToString("&") { "${it.first}=${it.second}" }
        }

        /**
         * Builds RFC 5849 Section 3.4.1.1 Signature Base String.
         */
        fun buildSignatureBaseString(
            httpMethod: String,
            baseUrl: String,
            normalizedParams: String
        ): String {
            // Strip any query parameters or fragment from base URL
            val cleanUrl = baseUrl.substringBefore('?').substringBefore('#')
            return "${httpMethod.uppercase()}&${percentEncode(cleanUrl)}&${percentEncode(normalizedParams)}"
        }

        /**
         * Computes HMAC-SHA256 signature and returns Base64 encoded string.
         * Signing key is percentEncode(consumerSecret) + "&" + percentEncode(tokenSecret).
         */
        fun computeHmacSha256(
            baseString: String,
            consumerSecret: String,
            tokenSecret: String = ""
        ): String {
            val signingKey = "${percentEncode(consumerSecret)}&${percentEncode(tokenSecret)}"
            val mac = Mac.getInstance("HmacSHA256")
            val keySpec = SecretKeySpec(signingKey.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
            mac.init(keySpec)
            val rawHmac = mac.doFinal(baseString.toByteArray(StandardCharsets.UTF_8))
            return Base64.getEncoder().encodeToString(rawHmac)
        }

        /**
         * Builds the RFC 5849 Section 3.5.1 Authorization header string.
         */
        fun buildAuthorizationHeader(
            consumerKey: String,
            nonce: String,
            signature: String,
            timestamp: String,
            version: String = "1.0",
            signatureMethod: String = "HMAC-SHA256"
        ): String {
            val headerParams = listOf(
                "oauth_consumer_key" to percentEncode(consumerKey),
                "oauth_nonce" to percentEncode(nonce),
                "oauth_signature" to percentEncode(signature),
                "oauth_signature_method" to percentEncode(signatureMethod),
                "oauth_timestamp" to percentEncode(timestamp),
                "oauth_version" to percentEncode(version)
            )
            return "OAuth " + headerParams.joinToString(", ") { "${it.first}=\"${it.second}\"" }
        }
    }

    private val mutex = Mutex()
    private var cachedToken: HereCachedToken? = null

    var networkRequestCount: Int = 0
        private set

    /**
     * Inspects in-memory token cache for testing.
     */
    fun getCachedToken(): HereCachedToken? = cachedToken

    /**
     * Seeds token cache for testing.
     */
    fun setCachedTokenForTesting(token: HereCachedToken?) {
        cachedToken = token
    }

    /**
     * Obtains a valid Bearer access token.
     * Returns cached token if valid and more than 10 minutes of validity remain.
     * Proactively requests a new token otherwise.
     * Thread-safe with coroutine mutex to eliminate duplicate concurrent network calls.
     */
    suspend fun getAccessToken(forceRefresh: Boolean = false): Result<String> = mutex.withLock {
        val now = timeProvider()
        val current = cachedToken
        if (!forceRefresh && current != null && current.isFresh(now, bufferMinutes = 10L)) {
            return Result.success(current.accessToken)
        }

        val fetchResult = executeTokenRequest(now)
        if (fetchResult.isSuccess) {
            val token = fetchResult.getOrThrow()
            cachedToken = token
            Result.success(token.accessToken)
        } else {
            Result.failure(fetchResult.exceptionOrNull()!!)
        }
    }

    /**
     * Executes the OAuth 1.0a signed request against HERE token endpoint.
     */
    private suspend fun executeTokenRequest(currentTimestampMs: Long): Result<HereCachedToken> = withContext(Dispatchers.IO) {
        try {
            val epochSeconds = (currentTimestampMs / 1000L).toString()
            val nonce = UUID.randomUUID().toString()

            val allParams = mapOf(
                "grant_type" to "client_credentials",
                "oauth_consumer_key" to accessKeyId,
                "oauth_nonce" to nonce,
                "oauth_signature_method" to "HMAC-SHA256",
                "oauth_timestamp" to epochSeconds,
                "oauth_version" to "1.0"
            )

            val normalizedParams = normalizeParameters(allParams)
            val baseString = buildSignatureBaseString("POST", tokenUrl, normalizedParams)
            val signature = computeHmacSha256(baseString, accessKeySecret)

            val authHeader = buildAuthorizationHeader(
                consumerKey = accessKeyId,
                nonce = nonce,
                signature = signature,
                timestamp = epochSeconds
            )

            val formBody = "grant_type=client_credentials".toRequestBody(FORM_MEDIA_TYPE)

            val request = Request.Builder()
                .url(tokenUrl)
                .post(formBody)
                .addHeader("Authorization", authHeader)
                .addHeader("Content-Type", "application/x-www-form-urlencoded")
                .addHeader("Accept", "application/json")
                .build()

            networkRequestCount++

            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("HERE OAuth token request failed with HTTP ${response.code}: $bodyString")
                    )
                }

                val tokenResponse = json.decodeFromString<HereTokenResponse>(bodyString)
                val cached = HereCachedToken(
                    accessToken = tokenResponse.accessToken,
                    tokenType = tokenResponse.tokenType,
                    expiresInSeconds = tokenResponse.expiresIn,
                    acquiredAtEpochMs = currentTimestampMs
                )
                Result.success(cached)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
