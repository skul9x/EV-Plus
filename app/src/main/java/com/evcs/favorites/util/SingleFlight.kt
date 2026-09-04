package com.evcs.favorites.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Generic, thread-safe, coroutine-aware single-flight request coalescing engine.
 * Coalesces duplicate concurrent operations with identical keys so that only one
 * underlying operation is executed, sharing the result among all concurrent callers.
 */
class SingleFlight(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    constructor(dispatcher: CoroutineDispatcher) : this(CoroutineScope(SupervisorJob() + dispatcher))

    internal val inFlight = ConcurrentHashMap<String, Deferred<Any?>>()

    /**
     * Executes [block] or coalesces into an existing in-flight execution for [key].
     *
     * Thread-safe and coroutine cancellation-safe: caller cancellation does not
     * cancel the underlying operation for remaining or subsequent callers.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> execute(key: String, block: suspend () -> T): T {
        val newDeferred = scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                withContext(NonCancellable) {
                    inFlight.remove(key)
                }
            }
        }
        val existing = inFlight.putIfAbsent(key, newDeferred)
        val deferred = if (existing != null) {
            existing
        } else {
            newDeferred.start()
            newDeferred
        }
        return deferred.await() as T
    }
}
