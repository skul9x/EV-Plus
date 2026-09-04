package com.evcs.favorites.util

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Utility to parse rate-limiting cooldown delay (in seconds) from HTTP Retry-After headers
 * and Cloudflare Error 1015 response bodies.
 */
object RetryAfterParser {
    const val DEFAULT_RETRY_AFTER_SECONDS: Long = 60L

    private val CLOUDFLARE_RETRY_AFTER_REGEX = Regex(
        """"retry_after"\s*:\s*(\d+)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Parses the retry delay in seconds from HTTP headers or response body.
     *
     * 1. If [headerValue] is an integer string (e.g. "60"), returns that value.
     * 2. If [headerValue] is an RFC 1123 / RFC 7231 HTTP-date (e.g. "Wed, 21 Oct 2026 07:28:00 GMT"),
     *    calculates (targetEpochSeconds - currentEpochSeconds), clamped to at least 0.
     * 3. If header is absent or unparseable, inspects [responseBody] for Cloudflare Error 1015 JSON:
     *    `"retry_after": <seconds>`.
     * 4. Defaults to [DEFAULT_RETRY_AFTER_SECONDS] (60s) if neither header nor body yields a valid duration.
     */
    fun parseRetryAfter(
        headerValue: String?,
        responseBody: String? = null,
        currentTimeMs: Long = System.currentTimeMillis()
    ): Long {
        if (!headerValue.isNullOrBlank()) {
            val trimmed = headerValue.trim()

            // 1. Integer seconds
            val seconds = trimmed.toLongOrNull()
            if (seconds != null && seconds >= 0) {
                return seconds
            }

            // 2. HTTP-Date (RFC 1123 / RFC 7231)
            try {
                val dateTime = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME)
                val targetEpochMs = dateTime.toInstant().toEpochMilli()
                val deltaSec = (targetEpochMs - currentTimeMs) / 1000L
                return maxOf(0L, deltaSec)
            } catch (_: Exception) {
                // Not an RFC 1123 date, fall through
            }
        }

        // 3. Cloudflare Error 1015 JSON response body inspection
        if (!responseBody.isNullOrBlank()) {
            val match = CLOUDFLARE_RETRY_AFTER_REGEX.find(responseBody)
            if (match != null) {
                val sec = match.groupValues[1].toLongOrNull()
                if (sec != null && sec >= 0) {
                    return sec
                }
            }
        }

        // 4. Fallback default
        return DEFAULT_RETRY_AFTER_SECONDS
    }
}
