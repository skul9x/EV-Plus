package com.evcs.favorites.ui.components

import androidx.compose.ui.graphics.Color
import com.evcs.favorites.data.model.ChargingForecastResponse
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.parser.StationForecastParser
import com.evcs.favorites.domain.model.ForecastSession
import com.evcs.favorites.domain.model.StationForecast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Single comprehensive test suite for Phase 03: WebView Modal Layout & UI Alignment.
 *
 * Verifies:
 * 1. Targeted CSS script injection payload formatting and styling rule correctness
 *    (`.amd-hasmore .amd-item { padding-right: 115px !important; }`).
 * 2. Security validation ensuring script injection strictly targets the EVCS domain.
 * 3. End-to-end data alignment between [StationForecast] domain model and EVCS real-time ticker expectations:
 *    - Exact ticker matching with vehicle count, wattage level, and minute durations.
 *    - Multi-range duration ticker formatting.
 *    - Structured multi-session breakdown alignment.
 *    - Status badge amber "⏱️ Sắp trống" alignment when full station has real-time forecast.
 *    - Complete end-to-end pipeline synchronization: API response -> parser -> StationCard capsule -> StationDetailModal ticker.
 */
class StationDetailModalForecastAlignmentTest {

    // =========================================================================
    // Part 1: CSS Injection Payload & Security Rule Correctness
    // =========================================================================

    @Test
    fun testCssInjectionPayload_formattingAndStylingCorrectness() {
        // Verify targeted CSS rule specifies padding-right 115px to prevent [Xem thêm ↗] button overlap
        assertEquals(
            ".amd-hasmore .amd-item { padding-right: 115px !important; }",
            FORECAST_OVERLAP_FIX_CSS
        )

        // Verify JavaScript IIFE payload structure
        assertTrue(
            "Script must be wrapped in an IIFE",
            FORECAST_OVERLAP_FIX_SCRIPT.startsWith("(function() {") &&
                FORECAST_OVERLAP_FIX_SCRIPT.endsWith("})();")
        )

        // Verify style element creation and attribute assignments
        assertTrue(
            "Script must create a style element",
            FORECAST_OVERLAP_FIX_SCRIPT.contains("var style = document.createElement('style');")
        )
        assertTrue(
            "Script must set style.type to text/css",
            FORECAST_OVERLAP_FIX_SCRIPT.contains("style.type = 'text/css';")
        )
        assertTrue(
            "Script must assign FORECAST_OVERLAP_FIX_CSS to style.innerHTML",
            FORECAST_OVERLAP_FIX_SCRIPT.contains("style.innerHTML = '$FORECAST_OVERLAP_FIX_CSS';")
        )
        assertTrue(
            "Script must append style element to document.head",
            FORECAST_OVERLAP_FIX_SCRIPT.contains("document.head.appendChild(style);")
        )
    }

    @Test
    fun testDomainSecurityFilter_onlyPermitsEvcsEndpoints() {
        // Valid EVCS domains and URLs
        assertTrue(isEvcsDomain("https://evcs.vn"))
        assertTrue(isEvcsDomain("https://evcs.vn/"))
        assertTrue(isEvcsDomain("https://evcs.vn/tram-sac-vinfast-tttm-dabaco-mart-que-vo-c.bni0012.html"))
        assertTrue(isEvcsDomain("https://api.evcs.vn/v1/stations"))
        assertTrue(isEvcsDomain("http://evcs.vn/charging"))

        // Invalid, untrusted, or malicious URLs
        assertFalse(isEvcsDomain(null))
        assertFalse(isEvcsDomain(""))
        assertFalse(isEvcsDomain("   "))
        assertFalse(isEvcsDomain("https://evil-evcs.vn.attacker.com"))
        assertFalse(isEvcsDomain("https://malicious.com?target=evcs.vn"))
        assertFalse(isEvcsDomain("https://google.com"))
        assertFalse(isEvcsDomain("javascript:alert(1)"))
    }

    // =========================================================================
    // Part 2: End-to-End Data Alignment (StationForecast <-> Webview Ticker)
    // =========================================================================

    @Test
    fun testForecastDataAlignment_singleSessionTickerExactMatch() {
        // Live ticker snippet as rendered in StationDetailModal webview DOM
        val liveTickerHtml = """
            <div class="amd-ticker amd-hasmore" role="status">
                <span class="amd-item">Dự kiến <b>1</b> xe sạc trụ <b>150kW</b> sẽ xong trong <b>13</b> phút nữa</span>
            </div>
            <button class="amd-more amd-locked">Xem thêm ↗</button>
        """.trimIndent()

        // 1. Parse into domain model via StationForecastParser
        val parsedForecast = StationForecastParser.parseForecastFromHtml(liveTickerHtml)
        assertNotNull("Forecast must not be null", parsedForecast)
        parsedForecast!!

        assertEquals(1, parsedForecast.vehicleCount)
        assertEquals(150.0, parsedForecast.wattageKw, 0.001)
        assertEquals(13, parsedForecast.minMinutes)
        assertEquals(13, parsedForecast.maxMinutes)
        assertTrue("Ticker with amd-more is a teaser", parsedForecast.isTeaser)

        // 2. Resolve visual representation for StationCard
        val capsuleData = resolveForecastCapsuleData(parsedForecast)
        assertFalse("Single session must not be multi-session", capsuleData.isMultiSession)
        assertEquals(
            "⏱️ Dự kiến 1 xe sạc trụ 150kW sẽ xong trong 13 phút nữa",
            capsuleData.singleSummary
        )

        // 3. Verify semantic text alignment: Capsule summary text contains exact ticker message
        val expectedTickerMessage = "Dự kiến 1 xe sạc trụ 150kW sẽ xong trong 13 phút nữa"
        assertTrue(capsuleData.singleSummary.contains(expectedTickerMessage))
    }

    @Test
    fun testForecastDataAlignment_rangeSessionTickerMatch() {
        val rangeTickerHtml = """
            <div class="amd-ticker amd-hasmore" role="status">
                <span class="amd-item">Dự kiến <b>2</b> xe sạc trụ <b>20kW</b> sẽ xong trong <b>7-14</b> phút nữa</span>
            </div>
            <button class="amd-more">Xem thêm ↗</button>
        """.trimIndent()

        val parsedForecast = StationForecastParser.parseForecastFromHtml(rangeTickerHtml)
        assertNotNull(parsedForecast)
        parsedForecast!!

        assertEquals(2, parsedForecast.vehicleCount)
        assertEquals(20.0, parsedForecast.wattageKw, 0.001)
        assertEquals(7, parsedForecast.minMinutes)
        assertEquals(14, parsedForecast.maxMinutes)

        val capsuleData = resolveForecastCapsuleData(parsedForecast)
        assertEquals(
            "⏱️ Dự kiến 2 xe sạc trụ 20kW sẽ xong trong 7-14 phút nữa",
            capsuleData.singleSummary
        )
        assertTrue(capsuleData.singleSummary.contains("Dự kiến 2 xe sạc trụ 20kW sẽ xong trong 7-14 phút nữa"))
    }

    @Test
    fun testForecastDataAlignment_multiSessionBreakdownMatch() {
        val multiSessionHtml = """
            <div id="stationTicker">
                <script type="application/json" data-charging-sessions>
                    [
                        {"kw":20.0,"min":7,"soc":50},
                        {"kw":20.0,"min":14,"soc":65},
                        {"kw":60.0,"min":13,"soc":70},
                        {"kw":250.0,"min":8,"soc":80}
                    ]
                </script>
            </div>
        """.trimIndent()

        val parsedForecast = StationForecastParser.parseForecastFromHtml(multiSessionHtml)
        assertNotNull(parsedForecast)
        parsedForecast!!

        assertTrue(parsedForecast.isMultiSession)
        assertEquals(4, parsedForecast.detailedSessions.size)

        val capsuleData = resolveForecastCapsuleData(parsedForecast)
        assertTrue(capsuleData.isMultiSession)
        assertEquals("⚡ DỰ KIẾN CỔNG SẮP TRỐNG:", capsuleData.header)
        assertEquals(3, capsuleData.bulletLines.size)

        // Alignment with modal sessions:
        // Group 1: 20kW (2 vehicles, 7-14 min)
        assertEquals("• 20kW:  ~7-14 phút (2 xe)", capsuleData.bulletLines[0])
        // Group 2: 60kW (1 vehicle, 13 min)
        assertEquals("• 60kW:  ~13 phút (1 xe)", capsuleData.bulletLines[1])
        // Group 3: 250kW (1 vehicle, 8 min)
        assertEquals("• 250kW: ~8 phút (1 xe)", capsuleData.bulletLines[2])
    }

    @Test
    fun testForecastDataAlignment_fullPipelineSyncFromApiToModal() {
        // Simulated API response from POST /charging
        val apiResponse = ChargingForecastResponse(
            ticker = """<div class="amd-ticker amd-hasmore"><span class="amd-item">Dự kiến <b>1</b> xe sạc trụ <b>150kW</b> sẽ xong trong <b>13</b> phút nữa</span></div><button class="amd-more">Xem thêm ↗</button>""",
            busyKw = mapOf("150" to 1),
            partial = true
        )

        // Step 1: Parse ticker
        val forecast = StationForecastParser.parseForecastFromHtml(apiResponse.ticker ?: "")
        assertNotNull(forecast)

        // Step 2: Station enriched with forecast
        val station = Station(
            id = "C.BNI0012",
            name = "VinFast - TTTM Dabaco Mart Quế Võ",
            address = "Bắc Ninh",
            latitude = 21.1438,
            longitude = 106.1662,
            summary = "Mở 24/7",
            connectors = "150kW",
            depotStatus = "Normal",
            totalAvailablePlugs = 0,
            totalPlugs = 4,
            forecast = forecast
        )

        // Step 3: Verify StationCard Status Badge is synchronized with forecast
        val badge = resolveStatusBadge(
            depotStatus = station.depotStatus,
            totalAvailablePlugs = station.totalAvailablePlugs,
            totalPlugs = station.totalPlugs,
            forecast = station.forecast
        )
        assertEquals("⏱️ Sắp trống", badge.label)
        assertEquals(Color(0xFFF59E0B), badge.dotColor)

        // Step 4: Verify StationCard ForecastCapsuleData is synchronized
        val capsule = resolveForecastCapsuleData(station.forecast!!)
        assertEquals(
            "⏱️ Dự kiến 1 xe sạc trụ 150kW sẽ xong trong 13 phút nữa",
            capsule.singleSummary
        )

        // Step 5: Verify CSS override rule matches the exact class in the ticker DOM
        val tickerClasses = "amd-ticker amd-hasmore"
        val itemClass = "amd-item"
        assertTrue(tickerClasses.contains("amd-hasmore"))
        assertTrue(FORECAST_OVERLAP_FIX_CSS.contains(".amd-hasmore"))
        assertTrue(FORECAST_OVERLAP_FIX_CSS.contains(".$itemClass"))
    }
}
