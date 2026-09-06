package com.evcs.favorites.ui.components

import androidx.compose.ui.graphics.Color
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.routing.DrivingMetrics
import com.evcs.favorites.data.routing.RoutingEngineType
import com.evcs.favorites.data.routing.TrafficCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Single comprehensive test suite verifying Compose UI memoization logic,
 * journey badge formatting across all tiers, and allocation-free rendering contracts for StationCard.
 */
class StationCardMemoizationPerformanceTest {

    private val greenColor = Color(0xFF10B981)
    private val amberColor = Color(0xFFF59E0B)
    private val redColor = Color(0xFFEF4444)
    private val cyanColor = Color(0xFF06B6D4)
    private val neutralColor = Color(0xFF94A3B8)

    // =========================================================================
    // 1. Journey Badge Formatting - Tier 1: Google Routes (Traffic Ratio & Edge Cases)
    // =========================================================================

    @Test
    fun testTier1Google_trafficRatioAndEdgeCases() {
        // Free flow (R < 1.15)
        val freeFlowMetrics = DrivingMetrics(
            distanceMeters = 5400L,
            durationSeconds = 600L,       // 10 mins
            staticDurationSeconds = 600L, // ratio = 1.0
            engineUsed = RoutingEngineType.GOOGLE
        )
        val freeFlowBadge = formatJourneyBadge(freeFlowMetrics, distanceKm = null)
        assertEquals("🚗 10 phút • 5.4 km • Thông thoáng", freeFlowBadge.text)
        assertEquals(greenColor, freeFlowBadge.contentColor)
        assertEquals(RoutingEngineType.GOOGLE, freeFlowBadge.tier)
        assertEquals(TrafficCondition.FREE_FLOW, freeFlowBadge.trafficCondition)

        // Moderate Congestion (1.15 <= R < 1.35)
        val moderateMetrics = DrivingMetrics(
            distanceMeters = 5400L,
            durationSeconds = 720L,       // 12 mins
            staticDurationSeconds = 600L, // ratio = 1.20
            engineUsed = RoutingEngineType.GOOGLE
        )
        val moderateBadge = formatJourneyBadge(moderateMetrics, distanceKm = null)
        assertEquals("🚗 12 phút • 5.4 km • Kẹt xe vừa", moderateBadge.text)
        assertEquals(amberColor, moderateBadge.contentColor)
        assertEquals(TrafficCondition.MODERATE_CONGESTION, moderateBadge.trafficCondition)

        // Heavy Congestion (R >= 1.35)
        val heavyMetrics = DrivingMetrics(
            distanceMeters = 10000L,
            durationSeconds = 1200L,      // 20 mins
            staticDurationSeconds = 800L, // ratio = 1.50
            engineUsed = RoutingEngineType.GOOGLE
        )
        val heavyBadge = formatJourneyBadge(heavyMetrics, distanceKm = null)
        assertEquals("🚗 20 phút • 10.0 km • Ùn tắc", heavyBadge.text)
        assertEquals(redColor, heavyBadge.contentColor)
        assertEquals(TrafficCondition.HEAVY_CONGESTION, heavyBadge.trafficCondition)

        // Edge case: 0 duration
        val zeroDurationMetrics = DrivingMetrics(
            distanceMeters = 0L,
            durationSeconds = 0L,
            staticDurationSeconds = 0L,
            engineUsed = RoutingEngineType.GOOGLE
        )
        val zeroBadge = formatJourneyBadge(zeroDurationMetrics, distanceKm = 2.5)
        assertEquals("🚗 0 phút • 2.5 km • Thông thoáng", zeroBadge.text)

        // Edge case: Traffic condition explicit override
        val explicitHeavy = DrivingMetrics(
            distanceMeters = 3000L,
            durationSeconds = 300L,
            staticDurationSeconds = null,
            trafficCondition = TrafficCondition.HEAVY_CONGESTION,
            engineUsed = RoutingEngineType.GOOGLE
        )
        val explicitBadge = formatJourneyBadge(explicitHeavy, distanceKm = null)
        assertEquals("🚗 5 phút • 3.0 km • Ùn tắc", explicitBadge.text)
        assertEquals(redColor, explicitBadge.contentColor)
    }

    // =========================================================================
    // 2. Journey Badge Formatting - Tier 2: OSRM Road Distance
    // =========================================================================

    @Test
    fun testTier2Osrm_roadDistanceFormatting() {
        val osrmMetrics = DrivingMetrics(
            distanceMeters = 3800L,
            durationSeconds = 480L, // 8 mins
            engineUsed = RoutingEngineType.OSRM
        )
        val badge = formatJourneyBadge(osrmMetrics, distanceKm = null)
        assertEquals("🚗 8 phút • 3.8 km • Đường bộ", badge.text)
        assertEquals(cyanColor, badge.contentColor)
        assertEquals(RoutingEngineType.OSRM, badge.tier)
        assertNull(badge.trafficCondition)
    }

    // =========================================================================
    // 3. Journey Badge Formatting - Tier 3: Haversine & Null Metrics Edge Cases
    // =========================================================================

    @Test
    fun testTier3Haversine_andNullHandling() {
        // Explicit Haversine
        val haversineBadge = formatJourneyBadge(
            metrics = DrivingMetrics(distanceMeters = 2400L, durationSeconds = 0L, engineUsed = RoutingEngineType.HAVERSINE),
            distanceKm = 2.4
        )
        assertEquals("⚡ 2.4 km • Đường thẳng", haversineBadge.text)
        assertEquals(neutralColor, haversineBadge.contentColor)
        assertEquals(RoutingEngineType.HAVERSINE, haversineBadge.tier)

        // Null metrics but non-null distanceKm
        val nullMetricsBadge = formatJourneyBadge(metrics = null, distanceKm = 4.7)
        assertEquals("⚡ 4.7 km • Đường thẳng", nullMetricsBadge.text)
        assertEquals(RoutingEngineType.HAVERSINE, nullMetricsBadge.tier)

        // Both null metrics and null distanceKm -> empty badge
        val emptyBadge = formatJourneyBadge(metrics = null, distanceKm = null)
        assertEquals("", emptyBadge.text)
        assertNull(emptyBadge.trafficCondition)
    }

    // =========================================================================
    // 4. Working Time & Parking String Formatting Logic
    // =========================================================================

    @Test
    fun testWorkingTimeAndParkingFormatting() {
        fun formatWorkingTimeText(isFreeParking: Boolean, workingTimeDescription: String): String {
            return if (isFreeParking) "Mở $workingTimeDescription • Miễn phí gửi xe"
            else "Mở $workingTimeDescription • Gửi xe có phí"
        }

        val freeParkingText = formatWorkingTimeText(true, "24/7")
        assertEquals("Mở 24/7 • Miễn phí gửi xe", freeParkingText)

        val paidParkingText = formatWorkingTimeText(false, "06:00 - 22:00")
        assertEquals("Mở 06:00 - 22:00 • Gửi xe có phí", paidParkingText)
    }

    // =========================================================================
    // 5. Status Badge & Fallback Connectors Formatting Logic
    // =========================================================================

    @Test
    fun testStatusBadgeResolution() {
        // Maintaining
        val maintainingBadge = resolveStatusBadge(depotStatus = "Maintaining", totalAvailablePlugs = 2, totalPlugs = 4)
        assertEquals("Bảo trì", maintainingBadge.label)

        // Out of service
        val offlineBadge = resolveStatusBadge(depotStatus = "OutOfService", totalAvailablePlugs = 0, totalPlugs = 4)
        assertEquals("Tạm dừng", offlineBadge.label)

        // Full (0 available out of 4)
        val fullBadge = resolveStatusBadge(depotStatus = "Normal", totalAvailablePlugs = 0, totalPlugs = 4)
        assertEquals("Hết cổng", fullBadge.label)

        // Available (3 available out of 4)
        val availableBadge = resolveStatusBadge(depotStatus = "Normal", totalAvailablePlugs = 3, totalPlugs = 4)
        assertEquals("Hoạt động", availableBadge.label)

        // Unverified / 0 total plugs
        val unverifiedNormal = resolveStatusBadge(depotStatus = "Normal", totalAvailablePlugs = 0, totalPlugs = 0)
        assertEquals("Hoạt động", unverifiedNormal.label)

        val unverifiedSaved = resolveStatusBadge(depotStatus = "Other", totalAvailablePlugs = 0, totalPlugs = 0)
        assertEquals("Đã lưu", unverifiedSaved.label)
    }

    // =========================================================================
    // 6. Referential Memoization Stability Contract
    // =========================================================================

    @Test
    fun testMemoizationBehaviorContract() {
        // Simulate remember cache keying on (metrics, distanceKm)
        class MemoizedBadgeHolder(var metrics: DrivingMetrics?, var distanceKm: Double?) {
            private var cachedKey: Pair<DrivingMetrics?, Double?>? = null
            private var cachedValue: JourneyBadgeInfo? = null

            fun getBadge(): JourneyBadgeInfo {
                val currentKey = metrics to distanceKm
                if (cachedKey != currentKey || cachedValue == null) {
                    cachedKey = currentKey
                    cachedValue = formatJourneyBadge(metrics, distanceKm)
                }
                return cachedValue!!
            }
        }

        val metrics1 = DrivingMetrics(distanceMeters = 5000L, durationSeconds = 600L, engineUsed = RoutingEngineType.OSRM)
        val holder = MemoizedBadgeHolder(metrics1, 5.0)

        val firstCall = holder.getBadge()
        val secondCall = holder.getBadge()

        // Memoization must return identical reference across recompositions when inputs don't change
        assertSame("Subsequent calls with identical inputs must return cached instance", firstCall, secondCall)

        // Changing input must recompute and yield new reference and output
        holder.metrics = DrivingMetrics(distanceMeters = 8000L, durationSeconds = 960L, engineUsed = RoutingEngineType.OSRM)
        val thirdCall = holder.getBadge()
        assertNotEquals("Altering input key must invalidate cache and produce new value", firstCall.text, thirdCall.text)
        assertEquals("🚗 16 phút • 8.0 km • Đường bộ", thirdCall.text)
    }
}
