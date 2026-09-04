package com.evcs.favorites.data.api

import java.io.IOException

/**
 * Exception representing HTTP 429 Too Many Requests, specifically Cloudflare Error 1015
 * or EVCS server rate limiting.
 *
 * @param retryAfterSeconds Cooldown duration in seconds parsed from Retry-After header or response body.
 * @param isCloudflare1015 True if response indicates Cloudflare 1015 rate limit, false otherwise.
 * @param message Human-readable error description.
 */
class RateLimitException(
    val retryAfterSeconds: Long,
    val isCloudflare1015: Boolean = true,
    message: String = "HTTP 429 Too Many Requests (Retry-After: ${retryAfterSeconds}s)"
) : IOException(message)
