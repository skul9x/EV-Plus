package com.evcs.favorites.domain

import kotlin.math.roundToInt

/**
 * 24-hour historical usage statistics calculator matching EVCS production logic (detail.26082102.js).
 */
object Station24hStatsCalculator {

    private const val VIETNAM_OFFSET_MS = 25_200_000L // UTC+7 (+7 hours in ms)
    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 86_400_000L

    /**
     * Calculates 24h usage statistics (Peak, Average, Peak Rush Hour, Fill Rate) from time-series points.
     *
     * @param points List of (timestamp, vehicleCount) pairs.
     * @param totalPorts Total available charging plugs for this station.
     */
    fun calculate(points: List<Pair<Long, Int>>, totalPorts: Int): Station24hStats {
        val peakUsage = points.maxOfOrNull { it.second } ?: 0
        val rawAvg = if (points.isNotEmpty()) points.map { it.second }.average() else 0.0
        val avgUsage = if (rawAvg > 0.0 && rawAvg < 1.0) 1 else rawAvg.roundToInt()

        val fillRate = if (totalPorts > 0) {
            minOf(100, maxOf(0, (rawAvg / totalPorts.toDouble() * 100.0).roundToInt()))
        } else {
            0
        }

        val peakHour = calculatePeakHour(points)

        return Station24hStats(
            peakUsage = peakUsage,
            avgUsage = avgUsage,
            peakHour = peakHour,
            fillRate = fillRate
        )
    }

    /**
     * Groups points by hour in Vietnam timezone (UTC+7, offset +25,200,000ms):
     * Hour h in 0..23, Day m = (timestamp + 25200000) / 86400000.
     * Each bucket (24 * m + h) tracks sum, cnt, max.
     * Aggregated across hours 0..23: finds hour h with highest peak vehicle count,
     * breaking ties by highest hourly average.
     * Formatted as "$h-${(h + 1) % 24}h" (e.g. "17-18h"), or "-" if sample is empty or all 0.
     */
    private fun calculatePeakHour(points: List<Pair<Long, Int>>): String {
        if (points.isEmpty() || points.all { it.second <= 0 }) {
            return "-"
        }

        class Bucket(var sum: Long = 0L, var cnt: Int = 0, var max: Int = 0)
        val buckets = mutableMapOf<Long, Bucket>()

        for ((rawTs, count) in points) {
            val tsMs = if (rawTs < 100_000_000_000L) rawTs * 1000L else rawTs
            val localMs = tsMs + VIETNAM_OFFSET_MS
            val m = localMs / DAY_MS
            val h = (((localMs / HOUR_MS) % 24 + 24) % 24).toInt()
            val bucketKey = 24L * m + h

            val b = buckets.getOrPut(bucketKey) { Bucket() }
            b.sum += count
            b.cnt += 1
            b.max = maxOf(b.max, count)
        }

        class HourAccumulator {
            var sum: Long = 0L
            var cnt: Int = 0
            var max: Int = 0
            var hasData: Boolean = false

            fun add(bucket: Bucket) {
                hasData = true
                sum += bucket.sum
                cnt += bucket.cnt
                if (bucket.max > max) max = bucket.max
            }

            val avg: Double
                get() = if (cnt > 0) sum.toDouble() / cnt else 0.0
        }

        val acc = Array(24) { HourAccumulator() }
        for ((key, bucket) in buckets) {
            val h = (((key % 24L) + 24L) % 24L).toInt()
            acc[h].add(bucket)
        }

        var bestHour = -1
        var bestMax = -1
        var bestAvg = -1.0

        for (h in 0..23) {
            val a = acc[h]
            if (!a.hasData || a.max <= 0) continue
            if (a.max > bestMax || (a.max == bestMax && a.avg > bestAvg)) {
                bestHour = h
                bestMax = a.max
                bestAvg = a.avg
            }
        }

        if (bestHour == -1) {
            return "-"
        }

        val nextH = (bestHour + 1) % 24
        return "$bestHour-${nextH}h"
    }
}
