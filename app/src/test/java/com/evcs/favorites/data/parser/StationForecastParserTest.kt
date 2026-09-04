package com.evcs.favorites.data.parser

import com.evcs.favorites.data.model.Station
import com.evcs.favorites.domain.model.ForecastSession
import com.evcs.favorites.domain.model.StationForecast
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StationForecastParserTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Test
    fun `verify range minute parse with html tags`() {
        val html = """
            <div id="stationTicker" style="min-height:30px">
              <div class="amd-ticker amd-hasmore" role="status" aria-label="Trụ sắp sạc xong">
                <span class="amd-item">Dự kiến <b>2</b> xe sạc trụ <b>20kW</b> sẽ xong trong <b>7-14</b> phút nữa</span>
              </div>
              <button type="button" class="amd-more amd-locked" data-lock="go" aria-label="Xem thêm dự báo - quà tặng EVCS Go">
                Xem thêm
              </button>
            </div>
            <div class="px-4">Other content</div>
        """.trimIndent()

        val forecast = StationForecastParser.parseForecastFromHtml(html)
        assertNotNull(forecast)
        assertEquals(2, forecast!!.vehicleCount)
        assertEquals(20.0, forecast.wattageKw, 0.001)
        assertEquals(7, forecast.minMinutes)
        assertEquals(14, forecast.maxMinutes)
        assertTrue(forecast.isTeaser)
        assertFalse(forecast.isMultiSession)
        assertEquals(
            "⏱️ Dự kiến 2 xe sạc trụ 20kW sẽ xong trong 7-14 phút nữa",
            forecast.formatSingleSummary()
        )
    }

    @Test
    fun `verify single minute parse without range`() {
        val html = """
            <div id="stationTicker">
              <div class="amd-ticker amd-hasmore" role="status">
                <span class="amd-item">Dự kiến <b>1</b> xe sạc trụ <b>60kW</b> sẽ xong trong <b>13</b> phút nữa</span>
              </div>
            </div>
        """.trimIndent()

        val forecast = StationForecastParser.parseForecastFromHtml(html)
        assertNotNull(forecast)
        assertEquals(1, forecast!!.vehicleCount)
        assertEquals(60.0, forecast.wattageKw, 0.001)
        assertEquals(13, forecast.minMinutes)
        assertEquals(13, forecast.maxMinutes)
        assertTrue(forecast.isTeaser)
        assertFalse(forecast.isMultiSession)
        assertEquals(
            "⏱️ Dự kiến 1 xe sạc trụ 60kW sẽ xong trong 13 phút nữa",
            forecast.formatSingleSummary()
        )
    }

    @Test
    fun `verify multi-session json extraction and grouped power lines matching 1_md`() {
        val html = """
            <div id="stationTicker">
              <div class="amd-ticker amd-hasmore">
                <span class="amd-item">Dự kiến 2 xe sạc trụ 20kW sẽ xong trong 7-14 phút nữa</span>
              </div>
            </div>
            <script type="application/json" data-charging-sessions>
            [
              {"kw": 20, "min": 7, "soc": 80},
              {"kw": 20, "min": 14, "soc": 85},
              {"kw": 60, "min": 13, "soc": 70},
              {"kw": 250, "min": 8, "soc": 90}
            ]
            </script>
        """.trimIndent()

        val forecast = StationForecastParser.parseForecastFromHtml(html)
        assertNotNull(forecast)
        assertEquals(4, forecast!!.detailedSessions.size)
        assertTrue(forecast.isMultiSession)

        // Groups sorted descending by kw: 250kW, 60kW, 20kW
        val groupsDesc = forecast.getGroupedPowerForecasts(descending = true)
        assertEquals(3, groupsDesc.size)

        // 250kW: 1 vehicle, 8 min
        val g250 = groupsDesc[0]
        assertEquals(250.0, g250.kw, 0.001)
        assertEquals(1, g250.vehicleCount)
        assertEquals(8, g250.minMinutes)
        assertEquals(8, g250.maxMinutes)
        assertEquals("• 250kW: ~8 phút (1 xe)", g250.formatBulletLine())

        // 60kW: 1 vehicle, 13 min
        val g60 = groupsDesc[1]
        assertEquals(60.0, g60.kw, 0.001)
        assertEquals(1, g60.vehicleCount)
        assertEquals(13, g60.minMinutes)
        assertEquals(13, g60.maxMinutes)
        assertEquals("• 60kW:  ~13 phút (1 xe)", g60.formatBulletLine())

        // 20kW: 2 vehicles, 7-14 min
        val g20 = groupsDesc[2]
        assertEquals(20.0, g20.kw, 0.001)
        assertEquals(2, g20.vehicleCount)
        assertEquals(7, g20.minMinutes)
        assertEquals(14, g20.maxMinutes)
        assertEquals("• 20kW:  ~7-14 phút (2 xe)", g20.formatBulletLine())

        // Groups sorted ascending by kw also matches 1.md bullet formatting
        val groupsAsc = forecast.getGroupedPowerForecasts(descending = false)
        assertEquals("• 20kW:  ~7-14 phút (2 xe)", groupsAsc[0].formatBulletLine())
        assertEquals("• 60kW:  ~13 phút (1 xe)", groupsAsc[1].formatBulletLine())
        assertEquals("• 250kW: ~8 phút (1 xe)", groupsAsc[2].formatBulletLine())
    }

    @Test
    fun `verify sessions parsed from html attribute data-charging-sessions`() {
        val html = """
            <div id="stationTicker" data-charging-sessions='[{"kw": 30, "min": 10, "soc": 50}]'>
              <div class="amd-ticker amd-hasmore">
                <span class="amd-item">Dự kiến 1 xe sạc trụ 30kW sẽ xong trong 10 phút nữa</span>
              </div>
            </div>
        """.trimIndent()

        val forecast = StationForecastParser.parseForecastFromHtml(html)
        assertNotNull(forecast)
        assertEquals(1, forecast!!.detailedSessions.size)
        assertEquals(30.0, forecast.detailedSessions.first().kw, 0.001)
        assertEquals(10, forecast.detailedSessions.first().min)
    }

    @Test
    fun `verify locked or empty ticker returns null safely`() {
        val lockedHtml = """
            <div id="stationTicker" style="min-height:30px">
              <div class="amd-ticker amd-locked" role="button" tabindex="0" data-lock="go" aria-label="Dự báo xe sắp sạc xong - quà tặng EVCS Go">
                <span class="amd-item"><span>Xem dự báo cổng sạc trống của trạm</span></span>
              </div>
            </div>
        """.trimIndent()

        assertNull(StationForecastParser.parseForecastFromHtml(lockedHtml))
        assertNull(StationForecastParser.parseForecastFromHtml(""))
        assertNull(StationForecastParser.parseForecastFromHtml("   "))
        assertNull(StationForecastParser.parseForecastFromHtml("<html><body>No ticker here</body></html>"))
    }

    @Test
    fun `verify kotlinx serialization round-trip for station containing station-forecast`() {
        val forecast = StationForecast(
            rawText = "Dự kiến 2 xe sạc trụ 20kW sẽ xong trong 7-14 phút nữa",
            vehicleCount = 2,
            wattageKw = 20.0,
            minMinutes = 7,
            maxMinutes = 14,
            isTeaser = true,
            detailedSessions = listOf(
                ForecastSession(kw = 20.0, min = 7, soc = 80),
                ForecastSession(kw = 20.0, min = 14, soc = 85)
            )
        )

        val station = Station(
            id = "C.BNI0012",
            name = "Trạm VinFast TTTM Dabaco Mart Quế Võ",
            address = "Quốc lộ 18, TT. Phố Mới, Quế Võ, Bắc Ninh",
            latitude = 21.145,
            longitude = 106.155,
            summary = "20kW, 60kW, 250kW",
            connectors = "CCS2",
            depotStatus = "Normal",
            totalAvailablePlugs = 0,
            totalPlugs = 8,
            forecast = forecast
        )

        val serialized = json.encodeToString(station)
        assertTrue(serialized.contains("C.BNI0012"))
        assertTrue(serialized.contains("Dự kiến 2 xe sạc trụ 20kW"))

        val deserialized = json.decodeFromString<Station>(serialized)
        assertEquals(station.id, deserialized.id)
        assertNotNull(deserialized.forecast)
        assertEquals(2, deserialized.forecast!!.vehicleCount)
        assertEquals(20.0, deserialized.forecast!!.wattageKw, 0.001)
        assertEquals(7, deserialized.forecast!!.minMinutes)
        assertEquals(14, deserialized.forecast!!.maxMinutes)
        assertEquals(2, deserialized.forecast!!.detailedSessions.size)
    }

    @Test
    fun `verify execution speed performance under 5ms per document`() {
        val sampleHtml = """
            <!DOCTYPE html>
            <html>
            <body>
            <div id="stationTicker" style="min-height:30px">
              <div class="amd-ticker amd-hasmore" role="status" aria-label="Trụ sắp sạc xong">
                <span class="amd-item">Dự kiến <b>2</b> xe sạc trụ <b>20kW</b> sẽ xong trong <b>7-14</b> phút nữa</span>
              </div>
              <button type="button" class="amd-more amd-locked" data-lock="go">Xem thêm</button>
            </div>
            <script type="application/json" data-charging-sessions>
            [
              {"kw": 20, "min": 7, "soc": 80},
              {"kw": 20, "min": 14, "soc": 85}
            ]
            </script>
            <div>Filler HTML to simulate real document size</div>
            </body>
            </html>
        """.trimIndent()

        // Warm up JVM
        repeat(50) {
            StationForecastParser.parseForecastFromHtml(sampleHtml)
        }

        val iterations = 100
        val startTime = System.nanoTime()
        repeat(iterations) {
            StationForecastParser.parseForecastFromHtml(sampleHtml)
        }
        val elapsedMillis = (System.nanoTime() - startTime) / 1_000_000.0
        val avgMillis = elapsedMillis / iterations

        // Must execute well under 5ms per document
        assertTrue("Parser too slow: ${avgMillis}ms per document", avgMillis < 5.0)
    }
}
