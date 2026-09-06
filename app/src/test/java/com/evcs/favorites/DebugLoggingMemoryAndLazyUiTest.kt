package com.evcs.favorites

import com.evcs.favorites.data.logging.AppDebugLogger
import com.evcs.favorites.data.logging.DebugLogEntry
import com.evcs.favorites.data.logging.DebugLogLevel
import com.evcs.favorites.data.logging.DebugLogTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 02 Verification Test:
 * Core Verifications:
 * 1. High-frequency logging (100+ rapid events) does not allocate intermediate lists when there are no observers.
 * 2. Calling flush() immediately synchronizes _logsFlow.value with buffer contents.
 * 3. Buffer strictly enforces MAX_CAPACITY = 500 with FIFO eviction.
 * 4. Lazy item key generator in DebugLogViewerCard guarantees distinct, stable keys for Compose recycling.
 */
class DebugLoggingMemoryAndLazyUiTest {

    @Before
    fun setUp() = runBlocking {
        var retries = 100
        while (AppDebugLogger.subscriptionCount.value > 0 && retries-- > 0) {
            delay(10)
        }
        AppDebugLogger.clear()
    }

    @After
    fun tearDown() = runBlocking {
        AppDebugLogger.clear()
        var retries = 100
        while (AppDebugLogger.subscriptionCount.value > 0 && retries-- > 0) {
            delay(10)
        }
    }

    @Test
    fun testHighFrequencyLoggingWithoutObserversDoesNotAllocateIntermediateSnapshots() {
        // Given: No observers are subscribed to logsFlow
        assertEquals(0, AppDebugLogger.subscriptionCount.value)
        assertEquals(0, AppDebugLogger.logsFlow.value.size)
        assertEquals(0, AppDebugLogger.getLogs().size)

        // When: High-frequency burst of 150 events is logged
        for (i in 1..150) {
            AppDebugLogger.log(
                tag = DebugLogTag.NETWORK,
                level = DebugLogLevel.INFO,
                message = "Rapid burst event #$i"
            )
        }

        // Then: logsFlow.value must NOT have been updated (zero intermediate snapshot allocations)
        assertEquals(
            "StateFlow value should remain empty without subscribers to prevent GC allocation churn",
            0,
            AppDebugLogger.logsFlow.value.size
        )
        // But the internal buffer successfully stores all 150 entries
        val storedLogs = AppDebugLogger.getLogs()
        assertEquals(150, storedLogs.size)
        assertEquals("Rapid burst event #1", storedLogs.first().message)
        assertEquals("Rapid burst event #150", storedLogs.last().message)
    }

    @Test
    fun testFlushImmediatelySynchronizesLogsFlowWithBufferContents() {
        // Given: 120 logs recorded without observers
        for (i in 1..120) {
            AppDebugLogger.log(
                tag = DebugLogTag.NETWORK,
                level = DebugLogLevel.SUCCESS,
                message = "Forecast calculation #$i"
            )
        }
        assertEquals(0, AppDebugLogger.logsFlow.value.size)
        assertEquals(120, AppDebugLogger.getLogs().size)

        // When: flush() is explicitly called (e.g., when UI modal opens or for assertions)
        AppDebugLogger.flush()

        // Then: logsFlow.value is immediately updated with all entries
        assertEquals(120, AppDebugLogger.logsFlow.value.size)
        assertEquals("Forecast calculation #1", AppDebugLogger.logsFlow.value.first().message)
        assertEquals("Forecast calculation #120", AppDebugLogger.logsFlow.value.last().message)
    }

    @Test
    fun testFifoEvictionEnforcesMaxCapacity500() {
        // When: Inserting 600 entries (exceeding MAX_CAPACITY = 500)
        for (i in 1..600) {
            AppDebugLogger.log(
                tag = DebugLogTag.ROUTING,
                level = DebugLogLevel.INFO,
                message = "Routing step #$i"
            )
        }

        val storedLogs = AppDebugLogger.getLogs()
        assertEquals(
            "Buffer capacity must be strictly capped at 500",
            AppDebugLogger.MAX_CAPACITY,
            storedLogs.size
        )

        // The oldest 100 entries (1..100) must be evicted, keeping 101..600
        assertEquals("Routing step #101", storedLogs.first().message)
        assertEquals("Routing step #600", storedLogs.last().message)

        // Calling flush() propagates the capped 500-item snapshot to logsFlow
        AppDebugLogger.flush()
        val flowLogs = AppDebugLogger.logsFlow.value
        assertEquals(500, flowLogs.size)
        assertEquals("Routing step #101", flowLogs.first().message)
        assertEquals("Routing step #600", flowLogs.last().message)
    }

    @Test
    fun testStableAndDistinctItemKeysForLazyColumnRecycling() {
        // Given: Multiple debug log entries created across tags and levels
        for (i in 1..50) {
            AppDebugLogger.log(
                tag = if (i % 2 == 0) DebugLogTag.SEARCH else DebugLogTag.FAVORITES,
                level = if (i % 3 == 0) DebugLogLevel.WARN else DebugLogLevel.INFO,
                message = "Log item $i",
                endpointUrl = "https://api.evcs.com/v1/test/$i"
            )
        }
        AppDebugLogger.flush()

        val logs = AppDebugLogger.logsFlow.value
        assertEquals(50, logs.size)

        // When evaluating the item key selector lambda used in LazyColumn: key = { it.id }
        val itemKeys = logs.map { it.id }

        // Then:
        // 1. All keys are non-blank
        assertTrue("Every entry must have a valid non-blank key", itemKeys.all { it.isNotBlank() })

        // 2. All keys are completely unique (no collisions preventing Compose LazyColumn crashes)
        val uniqueKeys = itemKeys.toSet()
        assertEquals("All 50 item keys must be distinct for stable recycling", 50, uniqueKeys.size)

        // 3. Keys remain invariant across successive reads and filtering operations
        val snapshotTwo = AppDebugLogger.getLogs()
        assertEquals(logs.map { it.id }, snapshotTwo.map { it.id })
    }

    @Test
    fun testObserverSubscriptionAndThrottledBurstHandling() = runBlocking {
        val testScope = CoroutineScope(Dispatchers.Default + Job())

        try {
            // Subscribe an active collector to simulate open UI modal
            val collectJob = testScope.launch {
                AppDebugLogger.logsFlow.collect { }
            }

            // Allow subscription to establish
            var retries = 50
            while (AppDebugLogger.subscriptionCount.value == 0 && retries-- > 0) {
                kotlinx.coroutines.delay(10)
            }
            assertTrue(
                "Subscription count should be > 0 with active collector",
                AppDebugLogger.subscriptionCount.value > 0
            )

            // Emit a rapid burst of 50 logs within a few milliseconds
            for (i in 1..50) {
                AppDebugLogger.log(
                    tag = DebugLogTag.NETWORK,
                    level = DebugLogLevel.INFO,
                    message = "Throttled event #$i"
                )
            }

            // Immediately flush to synchronize deterministic state
            AppDebugLogger.flush()
            assertEquals(50, AppDebugLogger.logsFlow.value.size)
            assertEquals("Throttled event #1", AppDebugLogger.logsFlow.value.first().message)
            assertEquals("Throttled event #50", AppDebugLogger.logsFlow.value.last().message)

            // Test clear resets state
            AppDebugLogger.clear()
            assertEquals(0, AppDebugLogger.logsFlow.value.size)
            assertEquals(0, AppDebugLogger.getLogs().size)
            assertTrue(AppDebugLogger.getFormattedLogText().contains("Chưa có nhật ký"))

            collectJob.cancelAndJoin()
        } finally {
            testScope.cancel()
        }
    }
}
