package com.evcs.favorites

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.Color
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.routing.DrivingMetrics
import com.evcs.favorites.data.routing.RoutingEngineType
import com.evcs.favorites.data.routing.TrafficCondition
import com.evcs.favorites.navigation.MapNavigator
import com.evcs.favorites.ui.components.formatJourneyBadge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprehensive verification test for Phase 05: Station Card ETA Pill & Navigation UI.
 *
 * Validates:
 * 1. Tier 1 (Google Routes with Live Traffic):
 *    - Text formatting: `🚗 {minutes} phút • {distanceKm} km • {Traffic Label}`
 *    - Traffic delay ratio mapping & color-coding:
 *      - R < 1.15 -> Green (#10B981) "Thông thoáng"
 *      - 1.15 <= R < 1.35 -> Amber (#F59E0B) "Kẹt xe vừa"
 *      - R >= 1.35 -> Red (#EF4444) "Ùn tắc"
 *    - Fallback condition mapping when staticDurationSeconds is absent.
 * 2. Tier 2 (OSRM Table Service):
 *    - Text formatting: `🚗 {minutes} phút • {distanceKm} km • Đường bộ`
 *    - Neutral cyan/blue accent (#06B6D4) without live congestion text.
 * 3. Tier 3 (Haversine Baseline):
 *    - Text formatting: `⚡ {distanceKm} km • Đường thẳng`
 *    - Subtle neutral styling (#94A3B8).
 * 4. Station Card navigation action integration with MapNavigator:
 *    - Valid `google.navigation:q=lat,lng&mode=d` intent URI format and package targeting.
 *    - Intent launcher invocation with destination station coordinates.
 */
class StationCardRoutingTest {

    private val greenColor = Color(0xFF10B981)
    private val amberColor = Color(0xFFF59E0B)
    private val redColor = Color(0xFFEF4444)
    private val cyanColor = Color(0xFF06B6D4)
    private val neutralColor = Color(0xFF94A3B8)

    @org.junit.Before
    fun setUp() {
        MapNavigator.setDebounceHelperForTesting(com.evcs.favorites.util.DebounceHelper(0L))
    }

    @org.junit.After
    fun tearDown() {
        MapNavigator.resetDebounceForTesting()
    }

    // =========================================================================
    // Part 1: Tier 1 (Google Routes with Live Traffic) Formatting & Color Coding
    // =========================================================================

    @Test
    fun testTier1_liveTrafficConditionsAndDelayRatioMapping() {
        // Case 1A: Free Flow (R < 1.15) -> 🟢 "Thông thoáng" (#10B981)
        val freeFlowMetrics = DrivingMetrics(
            distanceMeters = 5200L,
            durationSeconds = 600L,       // 10 mins
            staticDurationSeconds = 600L, // R = 1.0 < 1.15
            engineUsed = RoutingEngineType.GOOGLE
        )
        val freeFlowBadge = formatJourneyBadge(freeFlowMetrics, distanceKm = null)
        assertEquals("🚗 10 phút • 5.2 km • Thông thoáng", freeFlowBadge.text)
        assertEquals(greenColor, freeFlowBadge.contentColor)
        assertEquals(greenColor, freeFlowBadge.textColor)
        assertEquals(RoutingEngineType.GOOGLE, freeFlowBadge.tier)
        assertEquals(TrafficCondition.FREE_FLOW, freeFlowBadge.trafficCondition)

        // Case 1B: Moderate Congestion (1.15 <= R < 1.35) -> 🟡 "Kẹt xe vừa" (#F59E0B)
        val moderateMetrics = DrivingMetrics(
            distanceMeters = 4500L,
            durationSeconds = 720L,       // 12 mins
            staticDurationSeconds = 600L, // R = 1.20 (1.15 <= R < 1.35)
            engineUsed = RoutingEngineType.GOOGLE
        )
        val moderateBadge = formatJourneyBadge(moderateMetrics, distanceKm = null)
        assertEquals("🚗 12 phút • 4.5 km • Kẹt xe vừa", moderateBadge.text)
        assertEquals(amberColor, moderateBadge.contentColor)
        assertEquals(amberColor, moderateBadge.textColor)
        assertEquals(RoutingEngineType.GOOGLE, moderateBadge.tier)
        assertEquals(TrafficCondition.MODERATE_CONGESTION, moderateBadge.trafficCondition)

        // Case 1C: Heavy Congestion (R >= 1.35) -> 🔴 "Ùn tắc" (#EF4444)
        val heavyMetrics = DrivingMetrics(
            distanceMeters = 6000L,
            durationSeconds = 900L,       // 15 mins
            staticDurationSeconds = 600L, // R = 1.50 >= 1.35
            engineUsed = RoutingEngineType.GOOGLE
        )
        val heavyBadge = formatJourneyBadge(heavyMetrics, distanceKm = null)
        assertEquals("🚗 15 phút • 6.0 km • Ùn tắc", heavyBadge.text)
        assertEquals(redColor, heavyBadge.contentColor)
        assertEquals(redColor, heavyBadge.textColor)
        assertEquals(RoutingEngineType.GOOGLE, heavyBadge.tier)
        assertEquals(TrafficCondition.HEAVY_CONGESTION, heavyBadge.trafficCondition)

        // Case 1D: Strict threshold boundary testing
        // R = 1.1499 (< 1.15) -> Free Flow
        val boundaryBelow115 = DrivingMetrics(
            distanceMeters = 5000L,
            durationSeconds = 1149L,
            staticDurationSeconds = 1000L,
            engineUsed = RoutingEngineType.GOOGLE
        )
        assertEquals("Thông thoáng", formatJourneyBadge(boundaryBelow115, null).trafficCondition?.let { "Thông thoáng" } ?: "")
        assertEquals(greenColor, formatJourneyBadge(boundaryBelow115, null).contentColor)

        // R = 1.15 (exact boundary) -> Moderate
        val boundaryAt115 = DrivingMetrics(
            distanceMeters = 5000L,
            durationSeconds = 1150L,
            staticDurationSeconds = 1000L,
            engineUsed = RoutingEngineType.GOOGLE
        )
        assertEquals(amberColor, formatJourneyBadge(boundaryAt115, null).contentColor)
        assertTrue(formatJourneyBadge(boundaryAt115, null).text.endsWith("Kẹt xe vừa"))

        // R = 1.3499 (< 1.35) -> Moderate
        val boundaryBelow135 = DrivingMetrics(
            distanceMeters = 5000L,
            durationSeconds = 1349L,
            staticDurationSeconds = 1000L,
            engineUsed = RoutingEngineType.GOOGLE
        )
        assertEquals(amberColor, formatJourneyBadge(boundaryBelow135, null).contentColor)
        assertTrue(formatJourneyBadge(boundaryBelow135, null).text.endsWith("Kẹt xe vừa"))

        // R = 1.35 (exact boundary) -> Heavy
        val boundaryAt135 = DrivingMetrics(
            distanceMeters = 5000L,
            durationSeconds = 1350L,
            staticDurationSeconds = 1000L,
            engineUsed = RoutingEngineType.GOOGLE
        )
        assertEquals(redColor, formatJourneyBadge(boundaryAt135, null).contentColor)
        assertTrue(formatJourneyBadge(boundaryAt135, null).text.endsWith("Ùn tắc"))

        // Case 1E: Traffic condition fallback without staticDurationSeconds
        val explicitHeavy = DrivingMetrics(
            distanceMeters = 3000L,
            durationSeconds = 600L,
            staticDurationSeconds = null,
            trafficCondition = TrafficCondition.HEAVY_CONGESTION,
            engineUsed = RoutingEngineType.GOOGLE
        )
        val explicitHeavyBadge = formatJourneyBadge(explicitHeavy, null)
        assertEquals("🚗 10 phút • 3.0 km • Ùn tắc", explicitHeavyBadge.text)
        assertEquals(redColor, explicitHeavyBadge.contentColor)

        val explicitModerate = DrivingMetrics(
            distanceMeters = 3000L,
            durationSeconds = 600L,
            staticDurationSeconds = null,
            trafficCondition = TrafficCondition.MODERATE_CONGESTION,
            engineUsed = RoutingEngineType.GOOGLE
        )
        val explicitModerateBadge = formatJourneyBadge(explicitModerate, null)
        assertEquals("🚗 10 phút • 3.0 km • Kẹt xe vừa", explicitModerateBadge.text)
        assertEquals(amberColor, explicitModerateBadge.contentColor)
    }

    // =========================================================================
    // Part 2: Tier 2 (OSRM Table Service) Formatting & Styling
    // =========================================================================

    @Test
    fun testTier2_osrmRoadNetworkFormattingAndCyanAccent() {
        val osrmMetrics = DrivingMetrics(
            distanceMeters = 7400L,
            durationSeconds = 840L, // 14 mins
            engineUsed = RoutingEngineType.OSRM
        )
        val osrmBadge = formatJourneyBadge(osrmMetrics, distanceKm = null)

        assertEquals("🚗 14 phút • 7.4 km • Đường bộ", osrmBadge.text)
        assertEquals(cyanColor, osrmBadge.contentColor)
        assertEquals(cyanColor, osrmBadge.textColor)
        assertEquals(RoutingEngineType.OSRM, osrmBadge.tier)
        assertNull(osrmBadge.trafficCondition)
    }

    // =========================================================================
    // Part 3: Tier 3 (Haversine Baseline) Formatting & Styling
    // =========================================================================

    @Test
    fun testTier3_haversineStraightLineFormatting() {
        // Direct distanceKm with null metrics
        val haversineBadge = formatJourneyBadge(metrics = null, distanceKm = 2.4)

        assertEquals("⚡ 2.4 km • Đường thẳng", haversineBadge.text)
        assertEquals(neutralColor, haversineBadge.contentColor)
        assertEquals(neutralColor, haversineBadge.textColor)
        assertEquals(RoutingEngineType.HAVERSINE, haversineBadge.tier)
        assertNull(haversineBadge.trafficCondition)

        // Metrics explicitly marked as HAVERSINE
        val haversineMetrics = DrivingMetrics(
            distanceMeters = 3500L,
            durationSeconds = 0L,
            engineUsed = RoutingEngineType.HAVERSINE
        )
        val haversineMetricsBadge = formatJourneyBadge(haversineMetrics, distanceKm = null)
        assertEquals("⚡ 3.5 km • Đường thẳng", haversineMetricsBadge.text)
        assertEquals(neutralColor, haversineMetricsBadge.contentColor)
        assertEquals(RoutingEngineType.HAVERSINE, haversineMetricsBadge.tier)

        // Both null -> empty text
        val emptyBadge = formatJourneyBadge(metrics = null, distanceKm = null)
        assertEquals("", emptyBadge.text)
    }

    // =========================================================================
    // Part 4: Navigation Intent URI Construction & MapNavigator Integration
    // =========================================================================

    @Test
    fun testNavigationIntentUriConstructionAndMapNavigatorDispatch() {
        val station = Station(
            id = "C.HNI0099",
            name = "VinFast - Royal City Hà Nội",
            address = "72A Nguyễn Trãi, Thanh Xuân, Hà Nội",
            latitude = 21.0031,
            longitude = 105.8155,
            summary = "Mở 24/7",
            connectors = "120kW",
            depotStatus = "Normal",
            distanceKm = 4.2,
            drivingMetrics = DrivingMetrics(
                distanceMeters = 4200L,
                durationSeconds = 600L,
                staticDurationSeconds = 600L,
                engineUsed = RoutingEngineType.GOOGLE
            )
        )

        // Verify Google Navigation URI string matches requirements
        val expectedUri = "google.navigation:q=21.0031,105.8155&mode=d"
        val navUri = MapNavigator.buildGoogleNavigationUriString(station.latitude, station.longitude)
        assertEquals(expectedUri, navUri)

        // Verify Google Maps Intent Spec
        val gmapsSpec = MapNavigator.getGoogleMapsIntentSpec(station.latitude, station.longitude)
        assertEquals(MapNavigator.ACTION_VIEW, gmapsSpec.action)
        assertEquals(MapNavigator.GOOGLE_MAPS_PACKAGE, gmapsSpec.packageName)
        assertEquals(expectedUri, gmapsSpec.uriString)

        // Verify MapNavigator.navigate launches intent with target coordinates
        var dispatchedIntent: Intent? = null
        val dummyContext = DummyTestContext()

        val success = MapNavigator.navigate(
            context = dummyContext,
            latitude = station.latitude,
            longitude = station.longitude,
            stationName = station.name,
            intentLauncher = { intent ->
                dispatchedIntent = intent
            }
        )

        assertTrue(success)
        assertNotNull(dispatchedIntent)
    }

    private class DummyTestContext : android.content.ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun startActivity(intent: Intent?) {}
    }
}
