package com.evcs.favorites.data.logging

import com.evcs.favorites.BuildConfig
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

/**
 * OkHttp Interceptor that systematically captures outgoing requests and incoming responses,
 * recording latencies and peeking response bodies safely without consuming stream data.
 *
 * Defaults to enabled only in debug builds ([BuildConfig.DEBUG]) to avoid body peeking,
 * string buffering, and latency logging overhead in production release builds.
 */
class DebugLoggingInterceptor(
    var enabled: Boolean = BuildConfig.DEBUG,
    private val maxBodySnippetLength: Int = 500,
    private val maxPeekBytes: Long = 4096L
) : Interceptor {

    constructor(maxBodySnippetLength: Int, maxPeekBytes: Long = 4096L) : this(
        enabled = BuildConfig.DEBUG,
        maxBodySnippetLength = maxBodySnippetLength,
        maxPeekBytes = maxPeekBytes
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!enabled) {
            return chain.proceed(request)
        }

        val startTime = System.currentTimeMillis()
        val method = request.method
        val url = request.url.toString()
        val tag = resolveTag(request)
        val requestSnippet = extractRequestSnippet(request)

        try {
            val response = chain.proceed(request)
            val latencyMs = System.currentTimeMillis() - startTime

            val responseSnippet = try {
                val peeked = response.peekBody(maxPeekBytes).string()
                truncate(peeked, maxBodySnippetLength)
            } catch (e: Exception) {
                null
            }

            val level = if (response.isSuccessful) DebugLogLevel.INFO else DebugLogLevel.WARN
            val message = "$method $url -> HTTP ${response.code} (${latencyMs}ms)"

            AppDebugLogger.log(
                DebugLogEntry(
                    tag = tag,
                    level = level,
                    message = message,
                    endpointUrl = url,
                    method = method,
                    statusCode = response.code,
                    latencyMs = latencyMs,
                    requestSnippet = requestSnippet,
                    responseSnippet = responseSnippet
                )
            )

            return response
        } catch (e: Exception) {
            val latencyMs = System.currentTimeMillis() - startTime
            val message = "$method $url -> THẤT BẠI (${latencyMs}ms): ${e.message}"

            AppDebugLogger.log(
                DebugLogEntry(
                    tag = tag,
                    level = DebugLogLevel.ERROR,
                    message = message,
                    endpointUrl = url,
                    method = method,
                    latencyMs = latencyMs,
                    requestSnippet = requestSnippet,
                    errorDetails = e.message ?: e.toString()
                )
            )
            throw e
        }
    }

    private fun resolveTag(request: Request): DebugLogTag {
        val url = request.url.toString()
        val partialHeader = request.header("X-Partial")

        return when {
            url.contains("here.com") || url.contains("/ev/") -> DebugLogTag.FOCUS_MODE
            url.contains("/search") -> DebugLogTag.SEARCH
            url.contains("/favorite") || partialHeader.equals("fav", ignoreCase = true) -> DebugLogTag.FAVORITES
            url.contains("/table/v1/driving") || url.contains("osrm") || url.contains("google") -> DebugLogTag.ROUTING
            else -> DebugLogTag.NETWORK
        }
    }

    private fun extractRequestSnippet(request: Request): String? {
        val body = request.body ?: return null
        return try {
            val buffer = Buffer()
            body.writeTo(buffer)
            val content = buffer.readUtf8()
            truncate(content, maxBodySnippetLength)
        } catch (e: Exception) {
            null
        }
    }

    private fun truncate(text: String, maxLength: Int): String {
        return if (text.length > maxLength) {
            text.take(maxLength) + "..."
        } else {
            text
        }
    }
}
