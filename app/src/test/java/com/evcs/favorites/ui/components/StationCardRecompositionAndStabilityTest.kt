package com.evcs.favorites.ui.components

import androidx.compose.ui.graphics.Color
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.routing.DrivingMetrics
import com.evcs.favorites.data.routing.RoutingEngineType
import com.evcs.favorites.data.routing.TrafficCondition
import com.evcs.favorites.ui.theme.ElectricCyan
import com.evcs.favorites.ui.theme.StatusAvailable
import com.evcs.favorites.ui.theme.StatusBusy
import com.evcs.favorites.ui.theme.StatusMaintaining
import com.evcs.favorites.ui.theme.StatusOffline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 02 Comprehensive Test: Compose Recomposition & Scroll Optimization.
 *
 * Verifies:
 * 1. Compiler stability annotations (@Immutable) on all core models to allow Compose compiler to skip recomposition.
 * 2. Deterministic & memoizable behavior of formatJourneyBadge across all routing tiers (Google, OSRM, Haversine).
 * 3. Idempotence, structural equality, and correct status mapping in resolveStatusBadge.
 */
class StationCardRecompositionAndStabilityTest {

    private fun hasImmutableAnnotation(clazz: Class<*>): Boolean {
        // Direct runtime reflection check if retained as RUNTIME
        if (clazz.annotations.any { it.annotationClass.qualifiedName == "androidx.compose.runtime.Immutable" }) {
            return true
        }
        // Bytecode UTF-8 constant-pool search for BINARY retention (@Immutable has BINARY retention)
        val resourcePath = clazz.name.replace('.', '/') + ".class"
        val stream = clazz.classLoader?.getResourceAsStream(resourcePath) ?: return false
        val bytes = stream.readBytes()
        val pattern = "Landroidx/compose/runtime/Immutable;".toByteArray(Charsets.UTF_8)
        return indexOfSubarray(bytes, pattern) != -1
    }

    private fun indexOfSubarray(array: ByteArray, target: ByteArray): Int {
        if (target.isEmpty() || array.size < target.size) return -1
        for (i in 0..array.size - target.size) {
            var found = true
            for (j in target.indices) {
                if (array[i + j] != target[j]) {
                    found = false
                    break
                }
            }
            if (found) return i
        }
        return -1
    }

    @Test
    fun testStationModel_hasImmutableAnnotation_enablingCompilerSkip() {
        // Core models displayed within StationCard and list items
        assertTrue("Station must be annotated with @Immutable", hasImmutableAnnotation(Station::class.java))
        assertTrue("PowerPort must be annotated with @Immutable", hasImmutableAnnotation(PowerPort::class.java))
        assertTrue("DrivingMetrics must be annotated with @Immutable", hasImmutableAnnotation(DrivingMetrics::class.java))

        // Badge models inside StationCard
        assertTrue("JourneyBadgeInfo must be annotated with @Immutable", hasImmutableAnnotation(JourneyBadgeInfo::class.java))
        assertTrue("StatusBadgeModel must be annotated with @Immutable", hasImmutableAnnotation(StatusBadgeModel::class.java))
    }

    @Test
    fun testJourneyBadgeInfo_equalityAndCacheStability() {
        // Tier 1: Google Routes with Live Traffic (Free Flow)
        val googleFreeFlowMetrics = DrivingMetrics(
            distanceMeters = 5000L,
            durationSeconds = 600L,
            staticDurationSeconds = 600L,
            engineUsed = RoutingEngineType.GOOGLE
        )
        val badgeGoogle1 = formatJourneyBadge(googleFreeFlowMetrics, 5.0)
        val badgeGoogle2 = formatJourneyBadge(googleFreeFlowMetrics, 5.0)

        assertEquals("Same inputs must yield structurally equal badge instances", badgeGoogle1, badgeGoogle2)
        assertEquals(badgeGoogle1.hashCode(), badgeGoogle2.hashCode())
        assertTrue("Expected 10 phút and Thông thoáng", badgeGoogle1.text.contains("10 phút") && badgeGoogle1.text.contains("Thông thoáng"))
        assertEquals(RoutingEngineType.GOOGLE, badgeGoogle1.tier)
        assertEquals(TrafficCondition.FREE_FLOW, badgeGoogle1.trafficCondition)

        // Tier 1: Google Routes with Heavy Congestion (Ratio >= 1.35)
        val googleCongestionMetrics = DrivingMetrics(
            distanceMeters = 5000L,
            durationSeconds = 900L,
            staticDurationSeconds = 600L, // 900 / 600 = 1.5 >= 1.35
            engineUsed = RoutingEngineType.GOOGLE
        )
        val badgeCongestion = formatJourneyBadge(googleCongestionMetrics, 5.0)
        assertTrue("Expected Ùn tắc for delay ratio >= 1.35", badgeCongestion.text.contains("Ùn tắc"))
        assertEquals(TrafficCondition.HEAVY_CONGESTION, badgeCongestion.trafficCondition)

        // Tier 1: Google Routes with Moderate Congestion (1.15 <= Ratio < 1.35)
        val googleModerateMetrics = DrivingMetrics(
            distanceMeters = 5000L,
            durationSeconds = 750L,
            staticDurationSeconds = 600L, // 750 / 600 = 1.25
            engineUsed = RoutingEngineType.GOOGLE
        )
        val badgeModerate = formatJourneyBadge(googleModerateMetrics, 5.0)
        assertTrue("Expected Kẹt xe vừa for moderate delay", badgeModerate.text.contains("Kẹt xe vừa"))
        assertEquals(TrafficCondition.MODERATE_CONGESTION, badgeModerate.trafficCondition)

        // Tier 2: OSRM Table
        val osrmMetrics = DrivingMetrics(
            distanceMeters = 8400L,
            durationSeconds = 720L,
            engineUsed = RoutingEngineType.OSRM
        )
        val badgeOsrm1 = formatJourneyBadge(osrmMetrics, 8.4)
        val badgeOsrm2 = formatJourneyBadge(osrmMetrics, 8.4)
        assertEquals(badgeOsrm1, badgeOsrm2)
        assertEquals(RoutingEngineType.OSRM, badgeOsrm1.tier)
        assertNull(badgeOsrm1.trafficCondition)
        assertTrue("Expected Đường bộ suffix for OSRM", badgeOsrm1.text.contains("Đường bộ"))

        // Tier 3: Haversine fallback
        val haversineMetrics = DrivingMetrics(
            distanceMeters = 2500L,
            durationSeconds = 0L,
            engineUsed = RoutingEngineType.HAVERSINE
        )
        val badgeHaversine = formatJourneyBadge(haversineMetrics, 2.5)
        assertEquals(RoutingEngineType.HAVERSINE, badgeHaversine.tier)
        assertTrue("Expected Đường thẳng suffix for Haversine", badgeHaversine.text.contains("Đường thẳng"))

        // Null edge case
        val emptyBadge = formatJourneyBadge(null, null)
        assertTrue("Null inputs must produce blank badge text", emptyBadge.text.isBlank())
    }

    @Test
    fun testStatusBadgeResolution_producesStableInstances() {
        // Maintaining status
        val maintaining1 = resolveStatusBadge("Maintaining", 0, 10)
        val maintaining2 = resolveStatusBadge("Maintaining", 0, 10)
        assertEquals(maintaining1, maintaining2)
        assertEquals("Bảo trì", maintaining1.label)
        assertEquals(StatusMaintaining, maintaining1.dotColor)

        // OutOfService status
        val outOfService = resolveStatusBadge("OutOfService", 0, 10)
        assertEquals("Tạm dừng", outOfService.label)
        assertEquals(StatusOffline, outOfService.dotColor)

        // Normal status with live plugs available
        val active = resolveStatusBadge("Normal", 2, 8)
        assertEquals("Hoạt động", active.label)
        assertEquals(StatusAvailable, active.dotColor)

        // Normal status but all plugs occupied
        val busy = resolveStatusBadge("Normal", 0, 8)
        assertEquals("Hết cổng", busy.label)
        assertEquals(StatusBusy, busy.dotColor)

        // Unverified plug count (totalPlugs == 0) with Normal depot status
        val unverifiedNormal = resolveStatusBadge("Normal", 0, 0)
        assertEquals("Hoạt động", unverifiedNormal.label)
        assertEquals(StatusAvailable, unverifiedNormal.dotColor)

        // Unverified plug count (totalPlugs == 0) with non-Normal status
        val saved = resolveStatusBadge("Unknown", 0, 0)
        assertEquals("Đã lưu", saved.label)
        assertEquals(ElectricCyan, saved.dotColor)

        // Idempotency: repeated resolutions produce identical results
        val activeAgain = resolveStatusBadge("Normal", 2, 8)
        assertEquals(active, activeAgain)
        assertEquals(active.hashCode(), activeAgain.hashCode())
    }
}
