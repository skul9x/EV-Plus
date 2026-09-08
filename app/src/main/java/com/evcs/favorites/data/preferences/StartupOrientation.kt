package com.evcs.favorites.data.preferences

/**
 * Screen orientation configuration for application startup and runtime display.
 *
 * Designed specifically for automotive environments (Android Box & in-dash head units)
 * where accelerometer sensors may be absent, faulty, or susceptible to erratic flipping
 * on road banking, slopes, and bumps.
 */
enum class StartupOrientation(
    val title: String,
    val badge: String? = null,
    val description: String? = null
) {
    SYSTEM(
        title = "Mặc định hệ thống (Tự xoay)",
        badge = null,
        description = "Màn hình tự xoay theo hướng thiết bị hoặc cảm biến xe"
    ),
    LANDSCAPE(
        title = "Luôn mở màn hình ngang (Landscape)",
        badge = "Khuyên dùng cho Android Box ô tô",
        description = "Cố định hướng ngang, không bị lật khi xe rung lắc hoặc qua dốc"
    ),
    PORTRAIT(
        title = "Luôn mở màn hình dọc (Portrait)",
        badge = "Khuyên dùng cho điện thoại",
        description = "Cố định hướng dọc cho điện thoại cầm tay"
    );

    companion object {
        /**
         * Resolves a [StartupOrientation] from a persisted storage string key.
         * Safely falls back to [SYSTEM] if the key is null, blank, or unrecognized.
         */
        fun fromStorageKey(key: String?): StartupOrientation {
            if (key.isNullOrBlank()) return SYSTEM
            return entries.firstOrNull { it.name.equals(key.trim(), ignoreCase = true) } ?: SYSTEM
        }

        /**
         * Backward-compatible alias for [fromStorageKey].
         */
        fun fromString(key: String?): StartupOrientation = fromStorageKey(key)
    }
}
