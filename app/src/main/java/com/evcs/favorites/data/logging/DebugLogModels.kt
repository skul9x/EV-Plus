package com.evcs.favorites.data.logging

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class DebugLogTag {
    NETWORK,
    SEARCH,
    FAVORITES,
    ROUTING,
    FOCUS_MODE
}

enum class DebugLogLevel {
    INFO,
    SUCCESS,
    WARN,
    ERROR
}

/**
 * Data structure capturing network, parsing, and domain diagnostic events for EVCS.
 */
data class DebugLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: String = currentFormattedTimestamp(),
    val tag: DebugLogTag,
    val level: DebugLogLevel,
    val message: String,
    val endpointUrl: String? = null,
    val method: String? = null,
    val statusCode: Int? = null,
    val latencyMs: Long? = null,
    val requestSnippet: String? = null,
    val responseSnippet: String? = null,
    val errorDetails: String? = null
) {
    companion object {
        private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

        fun currentFormattedTimestamp(): String {
            return synchronized(timeFormat) {
                timeFormat.format(Date())
            }
        }
    }
}
