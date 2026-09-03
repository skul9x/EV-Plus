package com.evcs.favorites.domain.model

/**
 * Supported VinFast EV charging wattage tiers.
 * Models ultra-fast DC, fast DC, and destination/home AC power ratings.
 */
enum class WattageOption(
    val watts: Long,
    val label: String
) {
    // Ultra-fast DC
    KW_360(360_000L, "360kW"),
    KW_300(300_000L, "300kW"),
    KW_250(250_000L, "250kW"),
    KW_180(180_000L, "180kW"),
    KW_150(150_000L, "150kW"),
    KW_120(120_000L, "120kW"),

    // Fast DC
    KW_80(80_000L, "80kW"),
    KW_60(60_000L, "60kW"),
    KW_40(40_000L, "40kW"),
    KW_30(30_000L, "30kW"),
    KW_20(20_000L, "20kW"),

    // AC Slow / Destination
    KW_22(22_000L, "22kW"),
    KW_11(11_000L, "11kW"),
    KW_7(7_000L, "7kW"),
    KW_3_5(3_500L, "3.5kW");

    /**
     * Checks whether the given raw EVSE wattage matches this power tier.
     * Includes support for 7kW / 7.4kW variations.
     */
    fun matchesWattage(typeWatts: Long): Boolean {
        if (this == KW_7 && (typeWatts == 7_000L || typeWatts == 7_400L)) {
            return true
        }
        return this.watts == typeWatts
    }

    companion object {
        /**
         * Resolves a [WattageOption] from the raw wattage value in watts, or null if unmapped.
         */
        fun fromWatts(typeWatts: Long): WattageOption? {
            return entries.firstOrNull { it.matchesWattage(typeWatts) }
        }
    }
}
