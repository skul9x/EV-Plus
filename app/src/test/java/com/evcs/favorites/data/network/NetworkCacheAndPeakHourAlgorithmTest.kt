package com.evcs.favorites.data.network

import com.evcs.favorites.domain.Station24hStatsCalculator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Phase 05 Comprehensive Test:
 * 1. AppOkHttpClientProvider properly installs and propagates persistent HTTP disk cache to client builders.
 * 2. Station24hStatsCalculator calculates peak hour accurately using single-pass O(N) accumulation across distributions.
 * 3. Station24hStatsCalculator computes full 24h usage metrics (peakUsage, avgUsage, peakHour, fillRate) with parity.
 */
class NetworkCacheAndPeakHourAlgorithmTest {

    private lateinit var tempCacheDir: File

    @Before
    fun setUp() {
        tempCacheDir = Files.createTempDirectory("okhttp_cache_test").toFile()
        AppOkHttpClientProvider.resetForTesting()
    }

    @After
    fun tearDown() {
        AppOkHttpClientProvider.resetForTesting()
        tempCacheDir.deleteRecursively()
    }

    @Test
    fun testAppOkHttpClientProvider_installsDiskCacheProperly() {
        val cacheSize = 15L * 1024 * 1024 // 15 MB
        AppOkHttpClientProvider.installDiskCache(tempCacheDir, maxSizeBytes = cacheSize)

        val sharedClient = AppOkHttpClientProvider.getSharedClient()
        val derivedClient = AppOkHttpClientProvider.newSharedClientBuilder().build()

        // 1. Verify disk cache is installed on sharedClient
        val sharedCache = sharedClient.cache
        assertNotNull("Shared client must have disk cache configured", sharedCache)
        assertEquals("Cache directory must match target", tempCacheDir.canonicalPath, sharedCache!!.directory.canonicalPath)
        assertEquals("Cache max size must match configured size", cacheSize, sharedCache.maxSize())

        // 2. Verify newSharedClientBuilder inherits the exact disk cache
        val derivedCache = derivedClient.cache
        assertNotNull("Derived client builder must inherit disk cache", derivedCache)
        assertEquals("Derived client must share same cache directory", tempCacheDir.canonicalPath, derivedCache!!.directory.canonicalPath)
        assertEquals("Derived client must share same max size", cacheSize, derivedCache.maxSize())

        // 3. Verify idempotent behavior: subsequent install calls do not overwrite already installed cache
        val secondDir = Files.createTempDirectory("okhttp_cache_test_secondary").toFile()
        try {
            AppOkHttpClientProvider.installDiskCache(secondDir, maxSizeBytes = 30L * 1024 * 1024)
            val currentClient = AppOkHttpClientProvider.getSharedClient()
            assertEquals("Cache directory must remain original", tempCacheDir.canonicalPath, currentClient.cache?.directory?.canonicalPath)
        } finally {
            secondDir.deleteRecursively()
        }
    }

    @Test
    fun testStation24hStatsCalculator_calculatesPeakHourAccurately_singlePass() {
        // 1. Empty sample -> "-"
        val emptyResult = Station24hStatsCalculator.calculate(emptyList(), totalPorts = 10)
        assertEquals("-", emptyResult.peakHour)

        // 2. All zeros or negative -> "-"
        val zeroPoints = listOf(
            Pair(1725444000000L, 0),
            Pair(1725447600000L, 0),
            Pair(1725451200000L, -2)
        )
        val zeroResult = Station24hStatsCalculator.calculate(zeroPoints, totalPorts = 10)
        assertEquals("-", zeroResult.peakHour)

        // 3. Standard peak hour distribution (Hour 17 UTC+7)
        // 1725444000000L = 2024-09-04 10:00:00 UTC = 17:00:00 UTC+7
        val normalPoints = listOf(
            Pair(1725444000000L, 8),  // 17:00 UTC+7
            Pair(1725445800000L, 12), // 17:30 UTC+7
            Pair(1725447600000L, 6),  // 18:00 UTC+7
            Pair(1725451200000L, 4)   // 19:00 UTC+7
        )
        val normalResult = Station24hStatsCalculator.calculate(normalPoints, totalPorts = 10)
        assertEquals("17-18h", normalResult.peakHour)

        // 4. Tie-breaker by highest average (both have max = 10)
        // Hour 14:00 UTC+7 (1725433200000L): counts 10, 4 -> max 10, avg 7.0
        // Hour 15:00 UTC+7 (1725436800000L): counts 10, 10 -> max 10, avg 10.0 (Winner)
        val tiePoints = listOf(
            Pair(1725433200000L, 10),
            Pair(1725435000000L, 4),
            Pair(1725436800000L, 10),
            Pair(1725438600000L, 10)
        )
        val tieResult = Station24hStatsCalculator.calculate(tiePoints, totalPorts = 10)
        assertEquals("15-16h", tieResult.peakHour)

        // 5. Equal max and equal average preserves earliest hour
        // Hour 8:00 UTC+7 (1725411600000L): count 10 -> max 10, avg 10.0
        // Hour 12:00 UTC+7 (1725426000000L): count 10 -> max 10, avg 10.0
        val equalTiePoints = listOf(
            Pair(1725411600000L, 10),
            Pair(1725426000000L, 10)
        )
        val equalTieResult = Station24hStatsCalculator.calculate(equalTiePoints, totalPorts = 10)
        assertEquals("8-9h", equalTieResult.peakHour)

        // 6. Midnight rush hour (Hour 23 -> "23-0h")
        // 1725465600000L = 2024-09-04 16:00:00 UTC = 23:00:00 UTC+7
        val midnightPoints = listOf(
            Pair(1725465600000L, 15)
        )
        val midnightResult = Station24hStatsCalculator.calculate(midnightPoints, totalPorts = 10)
        assertEquals("23-0h", midnightResult.peakHour)

        // 7. Second-based timestamp normalization (< 100_000_000_000L)
        val secondsPoints = listOf(
            Pair(1725444000L, 7) // 17:00:00 UTC+7 in seconds
        )
        val secondsResult = Station24hStatsCalculator.calculate(secondsPoints, totalPorts = 10)
        assertEquals("17-18h", secondsResult.peakHour)
    }

    @Test
    fun testStation24hStatsCalculator_full24hStatsMetrics() {
        // 1. Empty and zero total ports
        val emptyStats = Station24hStatsCalculator.calculate(emptyList(), totalPorts = 0)
        assertEquals(0, emptyStats.peakUsage)
        assertEquals(0, emptyStats.avgUsage)
        assertEquals("-", emptyStats.peakHour)
        assertEquals(0, emptyStats.fillRate)

        // 2. Ceiling rule for low averages (0.0 < rawAvg < 1.0)
        // Average = 1 / 4 = 0.25 -> avgUsage rounds up to 1
        val lowPoints = listOf(
            Pair(1725444000000L, 1),
            Pair(1725447600000L, 0),
            Pair(1725451200000L, 0),
            Pair(1725454800000L, 0)
        )
        val lowStats = Station24hStatsCalculator.calculate(lowPoints, totalPorts = 10)
        assertEquals(1, lowStats.peakUsage)
        assertEquals(1, lowStats.avgUsage)
        assertEquals(3, lowStats.fillRate) // 0.25 / 10 * 100 = 2.5% -> rounded to 3%

        // 3. Standard distribution with fillRate calculation
        // Counts: 6, 4, 5 -> rawAvg = 5.0 -> avgUsage = 5
        // fillRate with totalPorts = 8: 5.0 / 8.0 * 100 = 62.5% -> 63%
        val stdPoints = listOf(
            Pair(1725444000000L, 6),
            Pair(1725447600000L, 4),
            Pair(1725451200000L, 5)
        )
        val stdStats = Station24hStatsCalculator.calculate(stdPoints, totalPorts = 8)
        assertEquals(6, stdStats.peakUsage)
        assertEquals(5, stdStats.avgUsage)
        assertEquals(63, stdStats.fillRate)

        // 4. FillRate capping at 100% when usage exceeds total ports
        val overflowPoints = listOf(
            Pair(1725444000000L, 12),
            Pair(1725447600000L, 14)
        )
        val overflowStats = Station24hStatsCalculator.calculate(overflowPoints, totalPorts = 5)
        assertEquals(14, overflowStats.peakUsage)
        assertEquals(13, overflowStats.avgUsage)
        assertEquals(100, overflowStats.fillRate)

        // 5. Multi-day aggregation across same hour (Day 1 and Day 2)
        // Day 1 Hour 10 (1725418800000L): count 4
        // Day 2 Hour 10 (1725418800000L + 86400000L): count 8
        // Combined for Hour 10: sum = 12, cnt = 2, max = 8, avg = 6.0
        val multiDayPoints = listOf(
            Pair(1725418800000L, 4),
            Pair(1725418800000L + 86_400_000L, 8),
            Pair(1725422400000L, 3) // Hour 11: count 3
        )
        val multiDayStats = Station24hStatsCalculator.calculate(multiDayPoints, totalPorts = 10)
        assertEquals(8, multiDayStats.peakUsage)
        assertEquals("10-11h", multiDayStats.peakHour)
        assertEquals(5, multiDayStats.avgUsage) // (4 + 8 + 3) / 3 = 5.0
        assertEquals(50, multiDayStats.fillRate)
    }
}
