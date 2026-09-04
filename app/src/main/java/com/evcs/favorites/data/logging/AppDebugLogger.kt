package com.evcs.favorites.data.logging

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Thread-safe in-memory circular buffer managing debug logs.
 * Holds at most 500 entries (FIFO eviction) and provides reactive StateFlow,
 * formatted plain-text export for sharing / clipboard copy, and buffer reset.
 */
object AppDebugLogger {
    const val MAX_CAPACITY = 500
    const val THROTTLE_INTERVAL_MS = 300L

    private val buffer = ArrayDeque<DebugLogEntry>(MAX_CAPACITY)
    private val lock = Any()
    private var lastEmitTimeMs = 0L
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var trailingEmitJob: Job? = null

    private val _logsFlow = MutableStateFlow<List<DebugLogEntry>>(emptyList())
    val logsFlow: StateFlow<List<DebugLogEntry>> = _logsFlow.asStateFlow()
    val subscriptionCount: StateFlow<Int> = _logsFlow.subscriptionCount

    /**
     * Appends an entry into the buffer, discarding the oldest element if max capacity is exceeded.
     * When there are no active subscribers (_logsFlow.subscriptionCount.value == 0), no snapshot
     * list is allocated or emitted. When subscribers are present, emissions are throttled to a minimum
     * interval of [THROTTLE_INTERVAL_MS].
     */
    fun log(entry: DebugLogEntry) {
        synchronized(lock) {
            if (buffer.size >= MAX_CAPACITY) {
                buffer.removeFirst()
            }
            buffer.addLast(entry)

            if (_logsFlow.subscriptionCount.value > 0) {
                val now = System.currentTimeMillis()
                val elapsed = now - lastEmitTimeMs
                if (elapsed >= THROTTLE_INTERVAL_MS) {
                    trailingEmitJob?.cancel()
                    trailingEmitJob = null
                    _logsFlow.value = buffer.toList()
                    lastEmitTimeMs = now
                } else if (trailingEmitJob == null) {
                    val remainingDelay = THROTTLE_INTERVAL_MS - elapsed
                    trailingEmitJob = coroutineScope.launch {
                        delay(remainingDelay)
                        synchronized(lock) {
                            trailingEmitJob = null
                            if (_logsFlow.subscriptionCount.value > 0) {
                                _logsFlow.value = buffer.toList()
                                lastEmitTimeMs = System.currentTimeMillis()
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Convenience overload to record a log without manually instantiating DebugLogEntry.
     */
    fun log(
        tag: DebugLogTag,
        level: DebugLogLevel,
        message: String,
        endpointUrl: String? = null,
        method: String? = null,
        statusCode: Int? = null,
        latencyMs: Long? = null,
        requestSnippet: String? = null,
        responseSnippet: String? = null,
        errorDetails: String? = null
    ) {
        log(
            DebugLogEntry(
                tag = tag,
                level = level,
                message = message,
                endpointUrl = endpointUrl,
                method = method,
                statusCode = statusCode,
                latencyMs = latencyMs,
                requestSnippet = requestSnippet,
                responseSnippet = responseSnippet,
                errorDetails = errorDetails
            )
        )
    }

    /**
     * Returns a snapshot of all currently stored log entries.
     */
    fun getLogs(): List<DebugLogEntry> {
        return synchronized(lock) {
            buffer.toList()
        }
    }

    /**
     * Immediately pushes the latest snapshot of the buffer into [logsFlow].
     * Useful for deterministic testing and when UI observers become active.
     */
    fun flush() {
        synchronized(lock) {
            trailingEmitJob?.cancel()
            trailingEmitJob = null
            _logsFlow.value = buffer.toList()
            lastEmitTimeMs = System.currentTimeMillis()
        }
    }

    /**
     * Purges all logs from the buffer and resets the reactive StateFlow.
     */
    fun clear() {
        synchronized(lock) {
            trailingEmitJob?.cancel()
            trailingEmitJob = null
            buffer.clear()
            _logsFlow.value = emptyList()
            lastEmitTimeMs = 0L
        }
    }

    /**
     * Formats the stored logs into human-readable plain text suitable for
     * Android Share Intent and Clipboard copying.
     */
    fun getFormattedLogText(): String {
        val currentLogs = synchronized(lock) { buffer.toList() }
        if (currentLogs.isEmpty()) {
            return "Chưa có nhật ký hoạt động mạng."
        }

        val sb = StringBuilder()
        sb.append("=== EVCS DEBUG LOGS ===\n")
        sb.append("Tổng số mục: ${currentLogs.size}\n\n")

        for ((index, entry) in currentLogs.withIndex()) {
            sb.append("#${index + 1} [${entry.timestamp}] [${entry.level}] [${entry.tag}]\n")
            sb.append("Nội dung: ${entry.message}\n")
            if (!entry.endpointUrl.isNullOrBlank()) {
                val methodStr = entry.method ?: "GET"
                val statusStr = entry.statusCode?.toString() ?: "-"
                val latencyStr = entry.latencyMs?.let { "${it}ms" } ?: "-"
                sb.append("Endpoint: $methodStr ${entry.endpointUrl} (HTTP $statusStr, $latencyStr)\n")
            }
            if (!entry.requestSnippet.isNullOrBlank()) {
                sb.append("Request: ${entry.requestSnippet}\n")
            }
            if (!entry.responseSnippet.isNullOrBlank()) {
                sb.append("Response: ${entry.responseSnippet}\n")
            }
            if (!entry.errorDetails.isNullOrBlank()) {
                sb.append("Chi tiết lỗi: ${entry.errorDetails}\n")
            }
            sb.append("----------------------------------------\n")
        }
        return sb.toString()
    }
}
