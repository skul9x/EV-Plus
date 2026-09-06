package com.evcs.favorites.util

/**
 * Utility for sanitizing station names returned from raw search APIs or stored favorites,
 * stripping leading distance prefixes and separators.
 */
object StationNameSanitizer {

    private val GUILLEMET_PREFIX_REGEX = Regex("""^(?:[^»\r\n]*»\s*)+""")
    private val DISTANCE_PREFIX_REGEX = Regex(
        """^(?:\s*[~≈]?\s*[\d.,]+\s*(?:km|m)\b(?:\s*[-:>»]\s*|\s+))+""",
        RegexOption.IGNORE_CASE
    )
    private val STATION_PREFIX_REGEX = Regex(
        """^(?:(?:\s*(?:Trạm|Tram)\s+(?:sạc|sac)(?:\s+xe\s+(?:điện|dien))?|\s*(?:Trụ|Tru)\s+(?:sạc|sac))\s*[-:–—]?\s*)+""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Strips distance prefixes (e.g. "5.4km » ", "500m - ", "~9.1km : ") and common Vietnamese
     * charging station prefixes ("Trạm sạc", "Trạm sạc xe điện", "Trụ sạc") from a station name.
     * Returns empty string if input is null or blank.
     */
    fun sanitize(name: String?): String {
        if (name.isNullOrBlank()) return ""
        var cleaned = name.trim()
        if (cleaned.contains('»')) {
            cleaned = cleaned.replace(GUILLEMET_PREFIX_REGEX, "")
        }
        cleaned = cleaned.replace(DISTANCE_PREFIX_REGEX, "")
        cleaned = cleaned.replace(STATION_PREFIX_REGEX, "")
        return cleaned.trim()
    }
}
