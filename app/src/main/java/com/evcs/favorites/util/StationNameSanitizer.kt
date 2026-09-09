package com.evcs.favorites.util

/**
 * Utility for sanitizing station names returned from raw search APIs or stored favorites,
 * stripping leading distance prefixes, station type indicators, and VinFast brand prefixes.
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
    private val VINFAST_PREFIX_REGEX = Regex(
        """^(?:\s*Vin(?:\s*[-–—]\s*|\s+)?Fast\b\s*[-:–—]*\s*)+""",
        RegexOption.IGNORE_CASE
    )
    private val LEADING_DELIMITER_REGEX = Regex("""^[\s-:–—»]+""")

    /**
     * Strips distance prefixes (e.g. "5.4km » ", "500m - ", "~9.1km : "), common Vietnamese
     * charging station prefixes ("Trạm sạc", "Trạm sạc xe điện", "Trụ sạc"), and redundant
     * VinFast brand prefixes ("VinFast - ", "VINFAST: ", "Vin Fast ") from a station name.
     *
     * Order of sanitization:
     * 1. Guillemet removal
     * 2. Distance prefix removal
     * 3. Station/Post prefix removal
     * 4. VinFast brand prefix removal
     * 5. Secondary station prefix removal
     * 6. Delimiter trimming
     *
     * Returns empty string if input is null or blank.
     * Preserves original station name if sanitization results in an empty string (fallback safety).
     */
    fun sanitize(name: String?): String {
        if (name.isNullOrBlank()) return ""
        var cleaned = name.trim()
        if (cleaned.contains('»')) {
            cleaned = cleaned.replace(GUILLEMET_PREFIX_REGEX, "")
        }
        cleaned = cleaned.replace(DISTANCE_PREFIX_REGEX, "")
        cleaned = cleaned.replace(STATION_PREFIX_REGEX, "")
        cleaned = cleaned.replace(VINFAST_PREFIX_REGEX, "")
        cleaned = cleaned.replace(STATION_PREFIX_REGEX, "")
        cleaned = cleaned.replace(LEADING_DELIMITER_REGEX, "")
        cleaned = cleaned.trim()

        return if (cleaned.isBlank()) name.trim() else cleaned
    }
}

