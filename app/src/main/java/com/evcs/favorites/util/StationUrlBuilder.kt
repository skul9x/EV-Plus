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
            .replace(Regex("[\\u0300-\\u036f]"), "")
            .replace('đ', 'd')
            .replace('Đ', 'd')
            .lowercase()
            .replace(Regex("[^a-z0-9]"), " ")
            .trim()
            .replace(Regex("\\s+"), "-")
            .replace(Regex("-+"), "-")
    }

    /**
     * Constructs the canonical station detail URL for a station by name and location ID.
     */
    fun buildStationDetailUrl(name: String, locationId: String, baseUrl: String = BASE_URL): String {
        val sanitizedName = StationNameSanitizer.sanitize(name)
        val slug = slugify(sanitizedName)
        val cleanLocId = locationId.trim()
        return if (slug.startsWith("vinfast")) {
            "$baseUrl/tram-sac-$slug-${cleanLocId.lowercase()}.html"
        } else {
            val encodedId = try {
                URLEncoder.encode(cleanLocId, StandardCharsets.UTF_8.name())
                    .replace("+", "%20")
            } catch (e: Exception) {
                cleanLocId
            }
            "$baseUrl/tram-sac-$slug-c.$encodedId.html"
        }
    }

    /**
     * Overload for domain [Station] model.
     */
    fun buildStationDetailUrl(station: Station, baseUrl: String = BASE_URL): String {
        return buildStationDetailUrl(station.name, station.id, baseUrl)
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
