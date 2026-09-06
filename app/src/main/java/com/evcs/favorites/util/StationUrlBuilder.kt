package com.evcs.favorites.util

import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.Station
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer

/**
 * Canonical URL builder and session cookie formatting utility for EVCS station details.
 *
 * Implements the official EVCS station URL specification:
 * - VinFast stations: `https://evcs.vn/tram-sac-${slug}-${locationId.lowercase()}.html`
 * - Partner stations: `https://evcs.vn/tram-sac-${slug}-c.${locationId}.html`
 */
object StationUrlBuilder {

    const val BASE_URL = "https://evcs.vn"

    // Pre-compiled regex patterns for slug generation (PERF-CPU-01)
    val DIACRITICS_REGEX = Regex("[\\u0300-\\u036f]")
    val NON_ALPHANUMERIC_REGEX = Regex("[^a-z0-9]")
    val WHITESPACE_REGEX = Regex("\\s+")
    val CONSECUTIVE_DASHES_REGEX = Regex("-+")

    /**
     * Converts a station name string into an EVCS URL slug:
     * - Decomposes unicode diacritics (NFD form)
     * - Strips combining accent marks
     * - Normalizes Vietnamese characters ('đ' -> 'd', 'Đ' -> 'd')
     * - Lowercases and strips non-alphanumeric characters
     * - Trims and collapses multiple spaces or hyphens into a single hyphen
     */
    fun slugify(input: String?): String {
        if (input.isNullOrBlank()) return ""
        val normalized = Normalizer.normalize(input, Normalizer.Form.NFD)
        return normalized
            .replace(DIACRITICS_REGEX, "")
            .replace('đ', 'd')
            .replace('Đ', 'd')
            .lowercase()
            .replace(NON_ALPHANUMERIC_REGEX, " ")
            .trim()
            .replace(WHITESPACE_REGEX, "-")
            .replace(CONSECUTIVE_DASHES_REGEX, "-")
    }

    /**
     * Constructs the canonical station detail URL for a station by name and location ID.
     */
    fun buildStationDetailUrl(
        name: String,
        locationId: String,
        baseUrl: String = BASE_URL,
        evse: String? = null
    ): String {
        val sanitizedName = StationNameSanitizer.sanitize(name)
        val slug = slugify(sanitizedName)
        val cleanLocId = locationId.trim()
        var normalizedSlug = slug
        while (normalizedSlug.startsWith("tram-sac-")) {
            normalizedSlug = normalizedSlug.removePrefix("tram-sac-")
        }
        if (normalizedSlug == "tram-sac") {
            normalizedSlug = ""
        }

        val isVinFast = evse?.equals("VinFast", ignoreCase = true) ?: normalizedSlug.startsWith("vinfast")

        return if (isVinFast) {
            var cleanVinFastSlug = normalizedSlug
            while (cleanVinFastSlug.startsWith("vinfast-")) {
                cleanVinFastSlug = cleanVinFastSlug.removePrefix("vinfast-")
            }
            if (cleanVinFastSlug == "vinfast") {
                cleanVinFastSlug = ""
            }
            val prefix = if (cleanVinFastSlug.isNotEmpty()) {
                "$baseUrl/tram-sac-vinfast-$cleanVinFastSlug"
            } else {
                "$baseUrl/tram-sac-vinfast"
            }
            "$prefix-${cleanLocId.lowercase()}.html"
        } else {
            val prefix = if (!evse.isNullOrBlank()) {
                val providerSlug = slugify(evse).ifBlank { "partner" }
                var cleanSlug = normalizedSlug
                while (cleanSlug.startsWith("$providerSlug-")) {
                    cleanSlug = cleanSlug.removePrefix("$providerSlug-")
                }
                if (cleanSlug == providerSlug) {
                    cleanSlug = ""
                }
                if (cleanSlug.isNotEmpty()) {
                    "$baseUrl/tram-sac-$providerSlug-$cleanSlug"
                } else {
                    "$baseUrl/tram-sac-$providerSlug"
                }
            } else {
                if (normalizedSlug.isNotEmpty()) "$baseUrl/tram-sac-$normalizedSlug" else "$baseUrl/tram-sac"
            }

            val cleanPartnerId = if (cleanLocId.startsWith("c.", ignoreCase = true)) {
                cleanLocId.substring(2).lowercase()
            } else {
                try {
                    URLEncoder.encode(cleanLocId, StandardCharsets.UTF_8.name())
                        .replace("+", "%20")
                } catch (e: Exception) {
                    cleanLocId
                }
            }
            "$prefix-c.$cleanPartnerId.html"
        }
    }

    /**
     * Overload for domain [Station] model.
     */
    fun buildStationDetailUrl(station: Station, baseUrl: String = BASE_URL): String {
        return buildStationDetailUrl(station.name, station.id, baseUrl, station.evse)
    }

    /**
     * Constructs formatted Cookie header string for WebView session injection.
     * Format: `PHPSESSID=...; evcs=...; evcs_did=...`
     */
    fun buildCookieHeader(
        phpSessionId: String?,
        authCookie: String?,
        deviceId: String? = null
    ): String {
        val pairs = mutableListOf<String>()
        if (!phpSessionId.isNullOrBlank()) {
            pairs.add("PHPSESSID=$phpSessionId")
        }
        if (!authCookie.isNullOrBlank()) {
            pairs.add("evcs=$authCookie")
        }
        if (!deviceId.isNullOrBlank()) {
            pairs.add("evcs_did=$deviceId")
        }
        return pairs.joinToString("; ")
    }

    /**
     * Extracts and constructs the formatted Cookie header from a [SessionManager].
     */
    fun buildCookieHeader(sessionManager: SessionManager): String {
        return sessionManager.getCookieHeader()
    }
}
