package com.evcs.favorites.data.parser

import com.evcs.favorites.domain.model.ForecastSession
import com.evcs.favorites.domain.model.StationForecast
import kotlinx.serialization.json.Json

/**
 * Parser for server-side rendered (SSR) station detail pages and ticker snippets.
 * Extracts real-time vehicle completion forecast and structured charging sessions.
 */
object StationForecastParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    // Matches the outer container <div id="stationTicker"...> ... </div>
    private val TICKER_WRAPPER_REGEX = Regex(
        pattern = """id=["']stationTicker["'][^>]*>([\s\S]*?)(?:</div>\s*<div\b|$)""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    // Sentence extractor capturing full forecast phrase starting with "Dự kiến" and ending with "phút nữa"
    private val FULL_SENTENCE_REGEX = Regex(
        pattern = """(Dự\s*kiến\s+[\s\S]*?phút\s*nữa)""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    // Individual clause regex matching "(\d+) xe sạc trụ ([0-9.]+)kW sẽ xong trong (\d+)(?:-(\d+))? phút"
    private val CLAUSE_REGEX = Regex(
        pattern = """(\d+)\s*xe\s*sạc\s*trụ\s*([0-9.]+)\s*k?W\s*sẽ\s*xong\s*trong\s*(\d+)(?:\s*[-–—]\s*(\d+))?\s*phút""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    // Regex for stripped clean text (single clause fallback)
    private val FORECAST_CLEAN_REGEX = Regex(
        pattern = """Dự\s*kiến\s*(\d+)\s*xe\s*sạc\s*trụ\s*([0-9.]+)\s*k?W\s*sẽ\s*xong\s*trong\s*(\d+)(?:\s*[-–—]\s*(\d+))?\s*phút\s*nữa""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    // Regex to match directly against raw HTML with possible inline tags (single clause fallback)
    private val FORECAST_RAW_HTML_REGEX = Regex(
        pattern = """Dự\s*kiến\s*(?:<[^>]+>)*\s*(\d+)\s*(?:<[^>]+>)*\s*xe\s*sạc\s*trụ\s*(?:<[^>]+>)*\s*([0-9.]+)\s*(?:<[^>]+>)*\s*k?W\s*(?:<[^>]+>)*\s*sẽ\s*xong\s*trong\s*(?:<[^>]+>)*\s*(\d+)(?:\s*(?:<[^>]+>)*\s*[-–—]\s*(?:<[^>]+>)*\s*(\d+))?\s*(?:<[^>]+>)*\s*phút\s*nữa""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    // Structured JSON charging sessions from script tag
    private val SESSIONS_SCRIPT_REGEX = Regex(
        pattern = """<script[^>]*data-charging-sessions[^>]*>([\s\S]*?)</script>""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    // Structured JSON charging sessions from HTML attribute (single-quoted or double-quoted)
    private val SESSIONS_ATTR_SINGLE_REGEX = Regex(
        pattern = """data-charging-sessions\s*=\s*'([^']*)'""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val SESSIONS_ATTR_DOUBLE_REGEX = Regex(
        pattern = """data-charging-sessions\s*=\s*"([^"]*)"""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    // Fallback JSON regex directly matching array bracket
    private val SESSIONS_FALLBACK_REGEX = Regex(
        pattern = """data-charging-sessions[^>]*>\s*(\[\s*\{[\s\S]*?\}\s*\])""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    /**
     * Parses an HTML string (SSR station detail page or ticker snippet) to extract [StationForecast].
     * Returns null if no valid forecast or sessions are found, or if locked without forecast.
     */
    fun parseForecastFromHtml(html: String): StationForecast? {
        if (html.isBlank()) return null

        // Extract ticker block or fallback to full html
        val tickerBlock = TICKER_WRAPPER_REGEX.find(html)?.groupValues?.get(1) ?: html

        // Parse structured sessions first
        val detailedSessions = parseDetailedSessions(html).ifEmpty {
            parseDetailedSessions(tickerBlock)
        }

        val stripped = stripHtmlTags(tickerBlock).ifBlank { stripHtmlTags(html) }
        val fullSentenceMatch = FULL_SENTENCE_REGEX.find(stripped)
        val candidateSentence = fullSentenceMatch?.groupValues?.get(1)?.trim()

        val clauses = CLAUSE_REGEX.findAll(candidateSentence ?: stripped).toList()

        // Fallback single-clause regex matchers
        var textMatch = FORECAST_RAW_HTML_REGEX.find(tickerBlock)
            ?: (if (tickerBlock !== html) FORECAST_RAW_HTML_REGEX.find(html) else null)

        if (textMatch == null) {
            textMatch = FORECAST_CLEAN_REGEX.find(stripped)
                ?: (if (tickerBlock !== html) FORECAST_CLEAN_REGEX.find(stripHtmlTags(html)) else null)
        }

        // If neither clause, text forecast, nor sessions found, return null
        if (clauses.isEmpty() && textMatch == null && detailedSessions.isEmpty()) {
            return null
        }

        val isTeaser = tickerBlock.contains("amd-more", ignoreCase = true) ||
                tickerBlock.contains("amd-hasmore", ignoreCase = true) ||
                html.contains("amd-more", ignoreCase = true) ||
                html.contains("amd-hasmore", ignoreCase = true) ||
                tickerBlock.contains("amd-locked", ignoreCase = true) ||
                html.contains("amd-locked", ignoreCase = true)

        if (clauses.isNotEmpty()) {
            val primaryClause = clauses.first()
            val vehicleCount = primaryClause.groupValues[1].toIntOrNull() ?: 1
            val wattageKw = primaryClause.groupValues[2].toDoubleOrNull() ?: 0.0
            val minMinutes = primaryClause.groupValues[3].toIntOrNull() ?: 0
            val maxMinutes = primaryClause.groupValues[4].takeIf { it.isNotBlank() }?.toIntOrNull() ?: minMinutes

            val cleanRawText = candidateSentence ?: stripHtmlTags(primaryClause.value)

            // Synthesize detailedSessions if there are multiple clauses and no structured JSON was provided
            val effectiveSessions = if (detailedSessions.isNotEmpty()) {
                detailedSessions
            } else if (clauses.size > 1) {
                val generated = mutableListOf<ForecastSession>()
                for (clause in clauses) {
                    val count = clause.groupValues[1].toIntOrNull() ?: 1
                    val kw = clause.groupValues[2].toDoubleOrNull() ?: 0.0
                    val minM = clause.groupValues[3].toIntOrNull() ?: 0
                    val maxM = clause.groupValues[4].takeIf { it.isNotBlank() }?.toIntOrNull() ?: minM
                    if (count <= 1) {
                        generated.add(ForecastSession(kw = kw, min = minM))
                    } else {
                        generated.add(ForecastSession(kw = kw, min = minM))
                        for (i in 1 until count - 1) {
                            val interpolated = minM + ((maxM - minM) * i) / (count - 1)
                            generated.add(ForecastSession(kw = kw, min = interpolated))
                        }
                        generated.add(ForecastSession(kw = kw, min = maxM))
                    }
                }
                generated
            } else {
                emptyList()
            }

            return StationForecast(
                rawText = cleanRawText,
                vehicleCount = vehicleCount,
                wattageKw = wattageKw,
                minMinutes = minMinutes,
                maxMinutes = maxMinutes,
                isTeaser = isTeaser,
                detailedSessions = effectiveSessions
            )
        }

        if (textMatch != null) {
            val vehicleCount = textMatch.groupValues[1].toIntOrNull() ?: 1
            val wattageKw = textMatch.groupValues[2].toDoubleOrNull() ?: 0.0
            val minMinutes = textMatch.groupValues[3].toIntOrNull() ?: 0
            val maxMinutes = textMatch.groupValues[4].takeIf { it.isNotBlank() }?.toIntOrNull() ?: minMinutes

            val cleanRawText = stripHtmlTags(textMatch.value)

            return StationForecast(
                rawText = cleanRawText,
                vehicleCount = vehicleCount,
                wattageKw = wattageKw,
                minMinutes = minMinutes,
                maxMinutes = maxMinutes,
                isTeaser = isTeaser,
                detailedSessions = detailedSessions
            )
        }

        // Synthesize forecast from detailedSessions when text snippet is absent
        val vehicleCount = detailedSessions.size
        val firstKw = detailedSessions.first().kw
        val minMinutes = detailedSessions.minOf { it.min }
        val maxMinutes = detailedSessions.maxOf { it.min }
        val kwFormatted = if (firstKw % 1.0 == 0.0) "${firstKw.toInt()}kW" else "${firstKw}kW"
        val timeRange = if (minMinutes == maxMinutes) "$minMinutes phút" else "$minMinutes-$maxMinutes phút"
        val syntheticRawText = "Dự kiến $vehicleCount xe sạc trụ $kwFormatted sẽ xong trong $timeRange nữa"

        return StationForecast(
            rawText = syntheticRawText,
            vehicleCount = vehicleCount,
            wattageKw = firstKw,
            minMinutes = minMinutes,
            maxMinutes = maxMinutes,
            isTeaser = isTeaser,
            detailedSessions = detailedSessions
        )
    }

    private fun parseDetailedSessions(content: String): List<ForecastSession> {
        val rawJson = SESSIONS_SCRIPT_REGEX.find(content)?.groupValues?.get(1)
            ?: SESSIONS_ATTR_SINGLE_REGEX.find(content)?.groupValues?.get(1)
            ?: SESSIONS_ATTR_DOUBLE_REGEX.find(content)?.groupValues?.get(1)
            ?: SESSIONS_FALLBACK_REGEX.find(content)?.groupValues?.get(1)
            ?: return emptyList()

        val sanitized = rawJson.replace("&quot;", "\"")
            .replace("&amp;", "&")
            .trim()

        return try {
            json.decodeFromString<List<ForecastSession>>(sanitized)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun stripHtmlTags(input: String): String {
        return input.replace(Regex("<[^>]+>"), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
