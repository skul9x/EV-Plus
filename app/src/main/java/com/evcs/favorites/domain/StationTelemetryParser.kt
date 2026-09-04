package com.evcs.favorites.domain

import com.evcs.favorites.data.model.PowerPort
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Parser and normalizer for EVCS live station telemetry, authentication tokens, and port status.
 */
object StationTelemetryParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private const val PROMOTIONAL_UPSELL_TEXT = "Xem dự báo cổng sạc trống của trạm"

    private val BUTTON_REGEX = Regex("""<button[\s\S]*?</button>""", RegexOption.IGNORE_CASE)
    private val SVG_REGEX = Regex("""<svg[\s\S]*?</svg>""", RegexOption.IGNORE_CASE)
    private val HTML_TAG_REGEX = Regex("""<[^>]+>""")
    private val WHITESPACE_REGEX = Regex("""\s+""")

    private val KW_REGEX = Regex("""(\d+(?:\.\d+)?)\s*kW""", RegexOption.IGNORE_CASE)
    private val MULTIPLIER_BEFORE_REGEX = Regex("""(\d+)\s*(?:x|\*)\s*(\d+(?:\.\d+)?)\s*kW""", RegexOption.IGNORE_CASE)
    private val MULTIPLIER_AFTER_REGEX = Regex("""kW\s*(?:x|\*)\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val PAREN_COUNT_REGEX = Regex("""\(\s*(\d+)\s*\)""")
    private val COLON_COUNT_REGEX = Regex(""":\s*(\d+)\s*(?:cổng|trụ|port)?""")
    private val SLASH_TOTAL_REGEX = Regex("""/\s*(\d+)\s*(?:cổng|trụ|port)?""")

    @Serializable
    private data class AccessTokensResponseRaw(
        val chargeToken: String? = null,
        val apiToken: String? = null,
        val rating: StationRatingRaw? = null,
        val ratingCsrf: String? = null,
        val hasGo: Boolean = false,
        val hasBiz: Boolean = false,
        val historyToken7: String? = null,
        val historyToken30: String? = null
    )

    @Serializable
    private data class StationRatingRaw(
        val avg: Double = 0.0,
        val count: Int = 0,
        val mine: Int = 0
    )

    @Serializable
    private data class ChargingResponseRaw(
        val ticker: String? = null,
        val busyKw: Map<String, Int> = emptyMap(),
        val partial: Boolean = false
    )

    /**
     * Parses session access and telemetry tokens returned from `POST /{slug}.html` with `X-Partial: user`.
     */
    fun parseAccessTokens(jsonString: String): Result<StationAccessTokens> = runCatching {
        val raw = json.decodeFromString<AccessTokensResponseRaw>(jsonString)
        val chargeToken = raw.chargeToken?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing chargeToken in response")
        val apiToken = raw.apiToken?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing apiToken in response")

        StationAccessTokens(
            chargeToken = chargeToken,
            apiToken = apiToken,
            rating = raw.rating?.let {
                StationRating(
                    avg = it.avg,
                    count = it.count,
                    mine = it.mine
                )
            },
            ratingCsrf = raw.ratingCsrf,
            hasGo = raw.hasGo,
            hasBiz = raw.hasBiz,
            historyToken7 = raw.historyToken7,
            historyToken30 = raw.historyToken30
        )
    }

    /**
     * Parses real-time charging telemetry payload returned by `POST /charging`.
     */
    fun parseChargingResponse(jsonString: String): Result<StationTelemetry> = runCatching {
        val raw = json.decodeFromString<ChargingResponseRaw>(jsonString)

        val busyByKw = raw.busyKw.mapNotNull { (k, v) ->
            val kw = k.toDoubleOrNull()?.toInt() ?: return@mapNotNull null
            kw to v
        }.toMap()

        val locked = isLockedTicker(raw.ticker)
        val cleanForecast = if (locked) null else sanitizeForecast(raw.ticker)

        StationTelemetry(
            busyByKw = busyByKw,
            rawTicker = raw.ticker,
            cleanForecast = cleanForecast,
            isLocked = locked
        )
    }

    /**
     * Checks if the ticker payload contains locked styling or upsell promotional text.
     */
    fun isLockedTicker(tickerHtml: String?): Boolean {
        if (tickerHtml.isNullOrBlank()) return false
        if (tickerHtml.contains("amd-ticker amd-locked") || tickerHtml.contains(PROMOTIONAL_UPSELL_TEXT, ignoreCase = true)) {
            return true
        }
        val tickerDivMatch = Regex("""<div[^>]*class=["']([^"']+)["'][^>]*>""").find(tickerHtml)
        if (tickerDivMatch != null) {
            val classes = tickerDivMatch.groupValues[1].split(" ")
            if (classes.contains("amd-locked")) {
                return true
            }
        }
        return false
    }

    /**
     * Strips HTML markup (`<b>`, `<span>`, `<button>`, `<svg>`) and unescapes entities, preserving
     * Vietnamese diacritics, numbers, and punctuation. Returns null if locked or empty.
     */
    fun sanitizeForecast(tickerHtml: String?): String? {
        if (tickerHtml.isNullOrBlank()) return null
        if (isLockedTicker(tickerHtml)) return null

        var cleaned = BUTTON_REGEX.replace(tickerHtml, "")
        cleaned = SVG_REGEX.replace(cleaned, "")
        cleaned = HTML_TAG_REGEX.replace(cleaned, "")
        cleaned = unescapeHtml(cleaned)
        cleaned = WHITESPACE_REGEX.replace(cleaned, " ").trim()

        return if (cleaned.isBlank() || cleaned.contains(PROMOTIONAL_UPSELL_TEXT, ignoreCase = true)) {
            null
        } else {
            cleaned
        }
    }

    /**
     * Parses connector definitions (e.g. "60kW x 4, 120kW x 6" or "120kW, 60kW") and derives
     * available port counts per kW tier using `availablePorts = max(0, totalPorts - busyCount)`.
     */
    fun derivePortStatuses(connectors: String?, busyByKw: Map<Int, Int>): List<StationPortStatus> {
        val portConfigs = parseConnectorDefinitions(connectors)
        val allKws = if (portConfigs.isNotEmpty() || busyByKw.isNotEmpty()) {
            (portConfigs.keys + busyByKw.keys).filter { it > 0 }.distinct().sortedDescending()
        } else {
            emptyList()
        }

        return allKws.map { kw ->
            val totalPorts = portConfigs[kw] ?: maxOf(busyByKw[kw] ?: 0, 1)
            val busyCount = busyByKw[kw] ?: 0
            val available = maxOf(0, totalPorts - busyCount)
            StationPortStatus(
                kw = kw,
                availablePorts = available,
                totalPorts = totalPorts,
                busyCount = busyCount
            )
        }
    }

    /**
     * Overload accepting parsed [PowerPort] list from station model.
     */
    fun derivePortStatuses(powers: List<PowerPort>, busyByKw: Map<Int, Int>): List<StationPortStatus> {
        val portMap = powers.filter { it.typeWatts > 0 }.associateBy { (it.typeWatts / 1000).toInt() }
        val allKws = (portMap.keys + busyByKw.keys).filter { it > 0 }.distinct().sortedDescending()

        return allKws.map { kw ->
            val power = portMap[kw]
            val totalPorts = power?.totalPlugs?.takeIf { it > 0 } ?: maxOf(busyByKw[kw] ?: 0, 1)
            val busyCount = busyByKw[kw] ?: 0
            val available = maxOf(0, totalPorts - busyCount)
            StationPortStatus(
                kw = kw,
                availablePorts = available,
                totalPorts = totalPorts,
                busyCount = busyCount
            )
        }
    }

    /**
     * Extracts kW rating to total port count mapping from static connector strings.
     */
    fun parseConnectorDefinitions(connectors: String?): Map<Int, Int> {
        if (connectors.isNullOrBlank()) return emptyMap()
        val counts = mutableMapOf<Int, Int>()

        val parts = connectors.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        for (part in parts) {
            val kwMatch = KW_REGEX.find(part) ?: continue
            val kw = kwMatch.groupValues[1].toDoubleOrNull()?.toInt() ?: continue

            val count = when {
                MULTIPLIER_BEFORE_REGEX.find(part) != null -> {
                    MULTIPLIER_BEFORE_REGEX.find(part)!!.groupValues[1].toIntOrNull() ?: 1
                }
                MULTIPLIER_AFTER_REGEX.find(part) != null -> {
                    MULTIPLIER_AFTER_REGEX.find(part)!!.groupValues[1].toIntOrNull() ?: 1
                }
                PAREN_COUNT_REGEX.find(part) != null -> {
                    PAREN_COUNT_REGEX.find(part)!!.groupValues[1].toIntOrNull() ?: 1
                }
                SLASH_TOTAL_REGEX.find(part) != null -> {
                    SLASH_TOTAL_REGEX.find(part)!!.groupValues[1].toIntOrNull() ?: 1
                }
                COLON_COUNT_REGEX.find(part) != null -> {
                    COLON_COUNT_REGEX.find(part)!!.groupValues[1].toIntOrNull() ?: 1
                }
                else -> 1
            }
            counts[kw] = (counts[kw] ?: 0) + count
        }
        return counts
    }

    private fun unescapeHtml(text: String): String {
        return text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
    }
}
