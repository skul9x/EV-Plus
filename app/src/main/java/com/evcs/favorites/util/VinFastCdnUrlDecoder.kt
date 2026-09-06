package com.evcs.favorites.util

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * High-performance multi-pass Base64 decoder for extracting direct VinFast CloudFront S3 CDN URLs
 * (`https://cpo-prod-s3.vinfastauto.com/...`) from EVCS media tokens.
 *
 * Implemented using standard [java.util.Base64] and pure regex query parsing to run seamlessly
 * across both Android (minSdk 26+) and local JVM JUnit environments without unmocked framework dependencies.
 */
object VinFastCdnUrlDecoder {

    private val FILE_PARAM_REGEX = Regex("""[?&]file=([^&#]+)""")
    private const val VINFAST_CDN_HOST = "cpo-prod-s3.vinfastauto.com"
    const val MAX_CACHE_CAPACITY = 500

    private val cacheLock = Any()
    private val lruCache = object : LinkedHashMap<String, String>(MAX_CACHE_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > MAX_CACHE_CAPACITY
        }
    }

    @Volatile
    var cacheHits: Long = 0L
        internal set

    @Volatile
    var cacheMisses: Long = 0L
        internal set

    /**
     * Decodes a raw media string into a direct VinFast CloudFront S3 CDN URL.
     *
     * Supported formats:
     * - Full URL: `https://evcs.vn/media?file=...`
     * - Relative path: `media?file=...` or `?file=...`
     * - Direct CDN URL: `https://cpo-prod-s3.vinfastauto.com/...`
     * - Raw double-base64 encoded token
     *
     * Pipeline:
     * 1. Extract raw token via regex (without android.net.Uri).
     * 2. Check in-memory bounded LRU cache for $O(1)$ hit.
     * 3. Pass 1: Base64 decode to intermediate base64 string.
     * 4. Pass 2: Base64 decode intermediate string to percent-encoded URL string.
     * 5. Pass 3: URL unescape via URLDecoder to yield direct CDN URL.
     * 6. Cache successfully resolved CDN URL.
     *
     * Gracefully returns `null` on corrupted, empty, or malformed inputs without throwing exceptions.
     */
    fun decode(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()

        // 1. Return immediately if already a direct CDN URL
        if (trimmed.contains(VINFAST_CDN_HOST, ignoreCase = true) &&
            (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true))
        ) {
            return trimmed
        }

        // 2. Extract token from URL query or use input directly
        val token = extractToken(trimmed)
        if (token.isBlank()) {
            return if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                trimmed
            } else {
                null
            }
        }

        // 3. Check cache before performing Base64 decoding
        synchronized(cacheLock) {
            val cached = lruCache[token]
            if (cached != null) {
                cacheHits++
                return cached
            }
        }

        return try {
            cacheMisses++
            // Pass 1: Decode raw Base64 token to intermediate base64 string
            val pass1Bytes = decodeBase64(token) ?: return null
            val pass1Str = String(pass1Bytes, StandardCharsets.UTF_8).trim()

            // If Pass 1 already resolved to an absolute URL (e.g. single-encoded token)
            if (isValidHttpUrl(pass1Str)) {
                val decoded = URLDecoder.decode(pass1Str, StandardCharsets.UTF_8.name())
                synchronized(cacheLock) {
                    lruCache[token] = decoded
                }
                return decoded
            }

            // Pass 2: Decode intermediate base64 string to percent-encoded URL string
            val pass2Bytes = decodeBase64(pass1Str) ?: return null
            val pass2Str = String(pass2Bytes, StandardCharsets.UTF_8).trim()

            // Pass 3: URL unescape to resolve final CDN URL
            val decodedUrl = URLDecoder.decode(pass2Str, StandardCharsets.UTF_8.name()).trim()

            if (isValidHttpUrl(decodedUrl)) {
                synchronized(cacheLock) {
                    lruCache[token] = decodedUrl
                }
                decodedUrl
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Decodes a list of raw media items, returning a list of valid direct CDN URLs.
     */
    fun decodeList(rawList: List<String>?): List<String> {
        if (rawList.isNullOrEmpty()) return emptyList()
        return rawList.mapNotNull { decode(it) }
    }

    /**
     * Clears all cached decoded URLs and resets hit/miss counters.
     */
    fun clearCache() {
        synchronized(cacheLock) {
            lruCache.clear()
            cacheHits = 0L
            cacheMisses = 0L
        }
    }

    /**
     * Returns current number of items cached in the LRU cache.
     */
    fun getCacheSize(): Int {
        return synchronized(cacheLock) {
            lruCache.size
        }
    }

    /**
     * Retrieves cached CDN URL for the given key (raw URL or token) if present.
     */
    fun getCached(key: String): String? {
        val token = extractToken(key.trim())
        return synchronized(cacheLock) {
            lruCache[token] ?: lruCache[key]
        }
    }

    /**
     * Injects a key-value mapping directly into the LRU cache (useful for testing eviction).
     */
    internal fun putCached(key: String, url: String) {
        synchronized(cacheLock) {
            lruCache[key] = url
        }
    }

    /**
     * Extracts the `file` parameter token from a URL or relative string.
     * Falls back to returning the input string if no `file` query parameter is present.
     */
    internal fun extractToken(input: String): String {
        val match = FILE_PARAM_REGEX.find(input)
        return if (match != null) {
            match.groupValues[1].trim()
        } else {
            if (input.startsWith("http://", ignoreCase = true) || input.startsWith("https://", ignoreCase = true)) {
                ""
            } else {
                input
            }
        }
    }

    private fun decodeBase64(input: String): ByteArray? {
        return try {
            Base64.getDecoder().decode(input)
        } catch (_: IllegalArgumentException) {
            try {
                Base64.getUrlDecoder().decode(input)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun isValidHttpUrl(url: String): Boolean {
        return url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)
    }
}
