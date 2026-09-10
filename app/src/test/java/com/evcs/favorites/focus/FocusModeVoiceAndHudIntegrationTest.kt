package com.evcs.favorites.focus

import android.content.Context
import android.content.ContextWrapper
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.routing.OsrmRoutingClient
import com.evcs.favorites.navigation.MapNavigator
import com.evcs.favorites.util.DebounceHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Single Comprehensive Integration Test for Phase 03:
 * Vietnamese TTS Alert Voice Scripting & Floating HUD Integration.
 *
 * Verifies the 3 core criteria:
 * 1. Vietnamese TTS alert script exactly matches approved standardized wording:
 *    "Trạm bạn muốn tới hiện tại đã hết cổng. Gợi ý đổi sang [Tên trạm B], cách [X] cây số, đi mất [Y] phút, còn [Z] cổng [P]kW"
 *    and verifies accurate durationMinutes calculation from seconds.
 * 2. Floating HUD displays and formats reroute button correctly with OSRM driving distance
 *    and handles 1-tap user invocation with 1000ms debouncing.
 * 3. End-to-end flow: target saturation -> user tap -> on-demand OSRM resolution ->
 *    Vietnamese voice announcement emission -> navigation launch -> engine target station update.
 */
class FocusModeVoiceAndHudIntegrationTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var osrmClient: OsrmRoutingClient

    private class DummyTestContext : ContextWrapper(null) {
        private val dummyMetrics = android.util.DisplayMetrics().apply {
            density = 2.0f
            widthPixels = 1080
            heightPixels = 2400
        }
        private val dummyConfig = android.content.res.Configuration().apply {
            orientation = android.content.res.Configuration.ORIENTATION_PORTRAIT
        }
        private val dummyResources = object : android.content.res.Resources(
            null,
            dummyMetrics,
            dummyConfig
        ) {
            override fun getDisplayMetrics(): android.util.DisplayMetrics = dummyMetrics
            override fun getConfiguration(): android.content.res.Configuration = dummyConfig
        }

        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.evplus.app"
        override fun getResources(): android.content.res.Resources = dummyResources
        override fun getTheme(): android.content.res.Resources.Theme = dummyResources.newTheme()
    }

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(500, TimeUnit.MILLISECONDS)
            .readTimeout(500, TimeUnit.MILLISECONDS)
            .callTimeout(1000, TimeUnit.MILLISECONDS)
            .build()
        osrmClient = OsrmRoutingClient(
            okHttpClient = okHttpClient,
            defaultBaseUrl = mockServer.url("/").toString()
        )
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    private fun createTestStation(
        id: String,
        name: String,
        lat: Double,
        lon: Double,
        powerKw: Long = 150L,
        availableSlots: Int = 1,
        totalSlots: Int = 2,
        depotStatus: String = "Normal"
    ): Station {
        val powers = listOf(
            PowerPort(
                typeWatts = powerKw * 1000L,
                label = "${powerKw}kW",
                availablePlugs = availableSlots,
                totalPlugs = totalSlots
            )
        )
        return Station(
            id = id,
            name = name,
            address = "Address $name",
            latitude = lat,
            longitude = lon,
            summary = "Trống $availableSlots/$totalSlots cổng sạc DC",
            connectors = "${powerKw}kW",
            depotStatus = depotStatus,
            powers = powers,
            totalAvailablePlugs = availableSlots,
            totalPlugs = totalSlots
        )
    }

    // =========================================================================
    // Criterion 1: Vietnamese TTS Voice Scripting & Duration Minutes Calculation
    // =========================================================================

    @Test
    fun testVietnameseTtsAlertScript_matchesExactWordingAndDurationCalculation() {
        // 1. Exact sentence format verification
        val scriptDecimalDistance = FocusModeVoiceAlertPolicy.formatAlternativeFoundText(
            stationName = "VinFast Pearl Plaza",
            distanceKm = 1.5,
            durationMinutes = 4,
            availableSlots = 3,
            powerKw = 150
        )
        assertEquals(
            "Trạm bạn muốn tới hiện tại đã hết cổng. Gợi ý đổi sang VinFast Pearl Plaza, cách 1.5 cây số, đi mất 4 phút, còn 3 cổng 150kW",
            scriptDecimalDistance
        )

        // 2. Integer distance formatting (e.g. 2.0 -> "2")
        val scriptIntegerDistance = FocusModeVoiceAlertPolicy.formatAlternativeFoundText(
            stationName = "VinFast Landmark 81",
            distanceKm = 2.0,
            durationMinutes = 5,
            availableSlots = 2,
            powerKw = 60
        )
        assertEquals(
            "Trạm bạn muốn tới hiện tại đã hết cổng. Gợi ý đổi sang VinFast Landmark 81, cách 2 cây số, đi mất 5 phút, còn 2 cổng 60kW",
            scriptIntegerDistance
        )

        // 3. Sub-kilometer distance formatting (e.g. 0.8 -> "0.8")
        val scriptSubKmDistance = FocusModeVoiceAlertPolicy.formatAlternativeFoundText(
            stationName = "VinFast Thảo Điền",
            distanceKm = 0.8,
            durationMinutes = 2,
            availableSlots = 1,
            powerKw = 250
        )
        assertEquals(
            "Trạm bạn muốn tới hiện tại đã hết cổng. Gợi ý đổi sang VinFast Thảo Điền, cách 0.8 cây số, đi mất 2 phút, còn 1 cổng 250kW",
            scriptSubKmDistance
        )

        // 4. Backward-compatible 2-argument signature
        val legacyScript = FocusModeVoiceAlertPolicy.formatAlternativeFoundText(
            distanceKm = 1.5,
            availableSlots = 4
        )
        assertTrue(legacyScript.contains("1.5"))
        assertTrue(legacyScript.contains("4"))
        assertTrue(legacyScript.startsWith("Trạm bạn muốn tới hiện tại đã hết cổng."))

        // 5. Duration minutes calculation rules
        assertEquals(1, AlternativeStationRecommendation.calculateDurationMinutes(null))
        assertEquals(1, AlternativeStationRecommendation.calculateDurationMinutes(0L))
        assertEquals(1, AlternativeStationRecommendation.calculateDurationMinutes(30L))
        assertEquals(1, AlternativeStationRecommendation.calculateDurationMinutes(59L)) // < 60s default to 1 min
        assertEquals(1, AlternativeStationRecommendation.calculateDurationMinutes(60L)) // 60s = 1 min
        assertEquals(2, AlternativeStationRecommendation.calculateDurationMinutes(110L)) // 110s / 60 ~ 1.83 -> 2 mins
        assertEquals(3, AlternativeStationRecommendation.calculateDurationMinutes(150L)) // 150s / 60 = 2.5 -> 3 mins
        assertEquals(4, AlternativeStationRecommendation.calculateDurationMinutes(240L)) // 240s / 60 = 4 mins
        assertEquals(12, AlternativeStationRecommendation.calculateDurationMinutes(720L)) // 720s / 60 = 12 mins

        // 6. Policy evaluation with AlternativeStationRecommendation
        var testTime = 1_000_000L
        val policy = FocusModeVoiceAlertPolicy(
            initialAvailableSlots = 2,
            clock = { testTime }
        )

        val altStation = createTestStation(
            id = "alt_station",
            name = "VinFast Cantavil",
            lat = 10.8010,
            lon = 106.7400,
            powerKw = 150L,
            availableSlots = 3,
            totalSlots = 4
        )
        val recommendation = AlternativeStationRecommendation(
            station = altStation,
            distanceKm = 2.4,
            matchingPowerWatts = 150_000L,
            availableDcSlots = 3,
            totalDcSlots = 4,
            drivingDurationSeconds = 240L,
            drivingDistanceMeters = 2400L
        )

        val alert = policy.evaluateAlternativeStation(
            targetSlots = 0,
            recommendation = recommendation,
            timestamp = testTime
        )
        assertNotNull("Alert must be triggered when target is full and alt is available", alert)
        assertEquals(FocusVoiceAlert.ALTERNATIVE_FOUND, alert)
        assertEquals(
            "Trạm bạn muốn tới hiện tại đã hết cổng. Gợi ý đổi sang VinFast Cantavil, cách 2.4 cây số, đi mất 4 phút, còn 3 cổng 150kW",
            alert!!.text
        )

        // Suppressed by debouncing within 20s
        testTime += 5_000L
        val debouncedAlert = policy.evaluateAlternativeStation(
            targetSlots = 0,
            recommendation = recommendation,
            timestamp = testTime
        )
        assertNull("Alert must be suppressed within debounce window", debouncedAlert)
    }

    // =========================================================================
    // Criterion 2: Floating HUD Reroute Button Formatting & 1-Tap Debounce
    // =========================================================================

    @Test
    fun testFloatingHud_rerouteButtonFormattingAnd1TapDebounce() {
        val dummyContext = DummyTestContext()
        var rerouteInvokedCount = 0
        var lastRerouteStation: AlternativeStationRecommendation? = null

        val manager = FocusModeFloatingViewManager(
            context = dummyContext,
            windowManager = null,
            onDismiss = {},
            onReroute = { rec ->
                rerouteInvokedCount++
                lastRerouteStation = rec
            }
        )

        var simulatedTime = 50_000L
        manager.setRerouteDebounceHelperForTesting(
            DebounceHelper(intervalMs = 1000L, clock = { simulatedTime })
        )

        val targetStation = createTestStation(
            id = "target_sat",
            name = "Vincom Đồng Khởi",
            lat = 10.7780,
            lon = 106.7020,
            powerKw = 150L,
            availableSlots = 0,
            totalSlots = 4
        )

        val altStation = createTestStation(
            id = "alt_pearl",
            name = "VinFast Pearl Plaza",
            lat = 10.8000,
            lon = 106.7150,
            powerKw = 150L,
            availableSlots = 3,
            totalSlots = 6
        )

        val altRec = AlternativeStationRecommendation(
            station = altStation,
            distanceKm = 2.4,
            matchingPowerWatts = 150_000L,
            availableDcSlots = 3,
            totalDcSlots = 6,
            drivingDurationSeconds = 240L,
            drivingDistanceMeters = 2400L
        )

        val saturatedState = FocusModeState(
            targetStation = targetStation,
            availableDcSlots = 0,
            totalDcSlots = 4,
            distanceRemainingKm = 1.0,
            connectionStatus = FocusConnectionStatus.CONNECTED,
            alternativeStation = altRec
        )

        // Verify FloatingViewState formatting with OSRM driving distance
        val viewState = FocusModeViewLayoutHelper.formatViewState(saturatedState)
        assertTrue("Reroute CTA must be enabled when DC slots saturated and alt present", viewState.isRerouteAvailable)
        assertNotNull(viewState.rerouteButtonText)
        assertEquals("🔄 Đổi trạm: Pearl Plaza (+2.4km)", viewState.rerouteButtonText)

        // Test updateView updates alternative station in manager
        manager.updateView(saturatedState)
        assertEquals("alt_pearl", manager.alternativeStation?.station?.id)

        // 1-Tap user invocation
        val firstTapAllowed = manager.triggerReroute()
        assertTrue("First user tap must succeed", firstTapAllowed)
        assertEquals(1, rerouteInvokedCount)
        assertEquals("alt_pearl", lastRerouteStation?.station?.id)

        // Rapid click spam within 1000ms cooldown (e.g. at +200ms)
        simulatedTime += 200L
        val rapidTapAllowed = manager.triggerReroute()
        assertFalse("Click within 1000ms cooldown must be throttled", rapidTapAllowed)
        assertEquals(1, rerouteInvokedCount)

        // Tap after 1000ms cooldown (e.g. at +1000ms)
        simulatedTime += 1000L
        val subsequentTapAllowed = manager.triggerReroute()
        assertTrue("User tap after debounce cooldown must succeed", subsequentTapAllowed)
        assertEquals(2, rerouteInvokedCount)
    }

    // =========================================================================
    // Criterion 3: Full Flow: Saturation -> Tap -> OSRM Resolution -> Alert & State
    // =========================================================================

    @Test
    fun testFullFlow_fromTargetSaturationToUserTapAndOsrmReroute() = runBlocking {
        val driverLat = 10.7769
        val driverLon = 106.7009

        // Target station is saturated
        val targetStation = createTestStation(
            id = "target_full",
            name = "Vincom Đồng Khởi",
            lat = 10.7780,
            lon = 106.7020,
            powerKw = 150L,
            availableSlots = 0,
            totalSlots = 4
        )

        val candHighway = createTestStation(
            id = "cand_highway",
            name = "VinFast Điện Biên Phủ",
            lat = 10.7950,
            lon = 106.7120,
            powerKw = 150L,
            availableSlots = 2,
            totalSlots = 4
        )

        val candidates = listOf(candHighway)

        // Mock OSRM Table Service response for origin + 1 destination:
        // Duration: 240s (4 mins), Distance: 2400m (2.4km)
        val osrmResponseJson = """
            {
              "code": "Ok",
              "durations": [
                [0.0, 240.0]
              ],
              "distances": [
                [0.0, 2400.0]
              ]
            }
        """.trimIndent()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(osrmResponseJson)
        )

        var emittedVoiceAlert: FocusVoiceAlert? = null
        var navigatedStation: Station? = null

        val engine = FocusModeTelemetryEngine(
            initialStation = targetStation,
            fetchStationTelemetry = { _, _, _ -> Result.success(targetStation) },
            fetchNearbyCandidates = { _, _ -> Result.success(candidates) },
            locationProvider = { Pair(driverLat, driverLon) },
            onVoiceAlert = { alert -> emittedVoiceAlert = alert },
            osrmRoutingClient = osrmClient
        )

        // Verify initial state: saturated DC slots
        val polledState = engine.pollOnce()
        assertEquals(0, polledState.availableDcSlots)
        assertEquals(0, mockServer.requestCount) // OSRM not yet invoked

        // User taps reroute button -> trigger full reroute flow
        val executedRecommendation = engine.executeRerouteFlow(
            driverLat = driverLat,
            driverLon = driverLon,
            onNavigate = { station -> navigatedStation = station }
        )

        // 1. Verify OSRM Table was queried on-demand
        assertEquals(1, mockServer.requestCount)
        assertNotNull("Executed recommendation must not be null", executedRecommendation)
        assertEquals("cand_highway", executedRecommendation!!.station.id)
        assertEquals(2400L, executedRecommendation.drivingDistanceMeters)
        assertEquals(240L, executedRecommendation.drivingDurationSeconds)
        assertEquals(4, executedRecommendation.durationMinutes)

        // 2. Verify voice alert was emitted with exact approved wording
        assertNotNull("Voice alert must be emitted during reroute flow", emittedVoiceAlert)
        assertEquals(FocusVoiceAlert.ALTERNATIVE_FOUND, emittedVoiceAlert)
        assertEquals(
            "Trạm bạn muốn tới hiện tại đã hết cổng. Gợi ý đổi sang Điện Biên Phủ, cách 2.4 cây số, đi mất 4 phút, còn 2 cổng 150kW",
            emittedVoiceAlert!!.text
        )

        // 3. Verify turn-by-turn navigation was dispatched to target alternative station
        assertNotNull("Navigation callback must be invoked", navigatedStation)
        assertEquals("cand_highway", navigatedStation!!.id)
        val navSpec = MapNavigator.getGoogleMapsIntentSpec(navigatedStation!!.latitude, navigatedStation!!.longitude)
        assertEquals("android.intent.action.VIEW", navSpec.action)
        assertEquals("com.google.android.apps.maps", navSpec.packageName)
        assertEquals(
            "google.navigation:q=${navigatedStation!!.latitude},${navigatedStation!!.longitude}&mode=d",
            navSpec.uriString
        )

        // 4. Verify engine target station and state were updated cleanly
        val updatedState = engine.state.first()
        assertEquals("cand_highway", updatedState.targetStation.id)
        assertEquals("Điện Biên Phủ", updatedState.targetStation.name)
        assertEquals(2, updatedState.availableDcSlots)
        assertEquals(4, updatedState.totalDcSlots)
        assertNull("Alternative station should be reset after target station switch", updatedState.alternativeStation)
    }
}
