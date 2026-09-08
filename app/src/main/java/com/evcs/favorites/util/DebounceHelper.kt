package com.evcs.favorites.util

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-performance, thread-safe debounce and in-flight action locking utility.
 *
 * Prevents rapid click spam, duplicate intent spawning, race conditions,
 * and concurrent network requests across UI interaction points and viewmodels.
 *
 * @param intervalMs Cooldown period in milliseconds before subsequent actions are permitted.
 * @param clock Injectable time provider returning monotonic milliseconds (defaults to SystemClock.elapsedRealtime).
 */
open class DebounceHelper(
    val intervalMs: Long = 1000L,
    private val clock: () -> Long = {
        try {
            SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            System.currentTimeMillis()
        }
    }
) {
    private val lock = Any()
    private var lastExecutionTime: Long? = null
    private val isInFlight = AtomicBoolean(false)

    /**
     * Returns true if an asynchronous action wrapped with [withInFlightLock] is currently in-flight.
     */
    val inFlight: Boolean
        get() = isInFlight.get()

    /**
     * Executes the given [action] if and only if the cooldown [intervalMs] has elapsed
     * since the last execution.
     *
     * @param action Lambda to execute if allowed.
     * @return `true` if the action was executed; `false` if dropped due to cooldown.
     */
    fun runIfAllowed(action: () -> Unit): Boolean {
        val allowed = synchronized(lock) {
            val now = clock()
            val last = lastExecutionTime
            if (last == null || (now - last) >= intervalMs) {
                lastExecutionTime = now
                true
            } else {
                false
            }
        }
        if (allowed) {
            action()
        }
        return allowed
    }

    /**
     * Executes a suspending [block] with an in-flight mutual exclusion lock.
     * If an action is already in-flight, subsequent concurrent calls are immediately
     * discarded and return `null`.
     *
     * @param block Asynchronous action to execute.
     * @return Result of [block] if executed, or `null` if dropped due to an active in-flight operation.
     */
    suspend fun <T> withInFlightLock(block: suspend () -> T): T? {
        if (!isInFlight.compareAndSet(false, true)) {
            return null
        }
        return try {
            block()
        } finally {
            isInFlight.set(false)
        }
    }

    /**
     * Resets internal timestamps and in-flight lock state.
     */
    fun reset() {
        synchronized(lock) {
            lastExecutionTime = null
            isInFlight.set(false)
        }
    }
}
