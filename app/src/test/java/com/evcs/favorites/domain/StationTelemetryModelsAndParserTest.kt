package com.evcs.favorites.domain

import com.evcs.favorites.data.model.PowerPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Single Comprehensive Verification Test for Phase 01: Domain Telemetry and 24h Stats Models.
 *
 * Verifies:
 * 1. Correct parsing of [StationAccessTokens] from real EVCS curl capture (tokens, ratings, CSRF, flags).
 * 2. Parsing of active charging payload from curl capture (busy counts, ticker stripping, diacritics preservation).
 * 3. Parsing of locked charging payload from curl capture (empty busy map, isLocked flag, cleanForecast null guarantee).
 * 4. Precise computation of [StationPortStatus] with availablePorts = max(0, totalPorts - busyCount) across string and PowerPort formats.
 * 5. Immutability, data integrity, and boundary contracts of [Station24hStats].
 */
class StationTelemetryModelsAndParserTest {

    companion object {
        // Embedded captures for deterministic offline/CI execution
        private const val CAPTURE_005601_TOKEN_JSON = """{
  "apiToken": "1788544875.7EpdFVasCkEYao8YhbAoNMxwiPJhl7vUDs9GMi3cP20",
  "chargeToken": "1788545175.none.PnOCazjNe3ic0AjFAGAG-lurZuWsLfcmeSmWbcFF6m8",
  "hasGo": false,
  "hasBiz": false,
  "historyToken7": "",
  "historyToken30": "",
  "favSync": false,
  "favCsrf": "",
  "favServer": null,
  "favLimit": 10,
  "loggedIn": false,
  "detailCsrf": "",
  "pkgAction": {
    "go": "redeem_package",
    "biz": "upgrade_biz"
  },
  "ratingCsrf": "a7e7dec61eafb1d2fd6ac7ab12dbc319",
  "rating": {
    "avg": 5,
    "count": 3,
    "mine": 0
  },
  "notifyWatch": false
}"""

        private const val CAPTURE_005601_ACTIVE_CHARGING_JSON = """{
  "ticker": "<div class=\"amd-ticker amd-hasmore\" role=\"status\" aria-label=\"Trụ sắp sạc xong\"> <span class=\"amd-item\">Dự kiến <b>2</b> xe sạc trụ <b>120kW</b> sẽ xong trong <b>1-7</b> phút, <b>1</b> xe sạc trụ <b>60kW</b> sẽ xong trong <b>1</b> phút nữa</span> </div> <button type=\"button\" class=\"amd-more amd-locked\" data-lock=\"go\" aria-label=\"Xem thêm dự báo - quà tặng EVCS Go\">Xem thêm <svg xmlns=\"http://www.w3.org/2000/svg\" width=\"12\" height=\"12\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"> <path d=\"M5 19L19 5M19 19V5H5\"></path> </svg></button>",
  "busyKw": {
    "60": 4,
    "120": 5
  },
  "partial": true
}"""

        private const val CAPTURE_005946_LOCKED_CHARGING_JSON = """{
  "ticker": "<div class=\"amd-ticker amd-locked\" role=\"button\" tabindex=\"0\" data-lock=\"go\" aria-label=\"Dự báo xe sắp sạc xong - quà tặng EVCS Go\"> <span class=\"amd-item\"><span>Xem dự báo cổng sạc trống của trạm</span> <svg xmlns=\"http://www.w3.org/2000/svg\" width=\"12\" height=\"12\" viewBox=\"0 0 24 24\" fill=\"none\" style=\"opacity:0.6;margin-top:2px\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"> <path d=\"M5 19L19 5M19 19V5H5\"></path> </svg></span> </div>",
  "busyKw": {},
  "partial": true
}"""

        private const val CAPTURE_005946_TOKEN_JSON = """{
  "apiToken": "1788545100.M8nK-OGE6d5k92wAUBxHKFX6FaiBciwkt25rFUS5yKQ",
  "chargeToken": "1788545400.none.D5VkyLqBfZuQ4GOP0qs149gfv7JMlKIg9-7WIox9rek",
  "hasGo": false,
  "hasBiz": false,
  "historyToken7": "",
  "historyToken30": "",
  "favSync": false,
  "favCsrf": "",
  "favServer": null,
  "favLimit": 10,
  "loggedIn": false,
  "detailCsrf": "",
  "pkgAction": {
    "go": "redeem_package",
    "biz": "upgrade_biz"
  },
  "ratingCsrf": "a7e7dec61eafb1d2fd6ac7ab12dbc319",
  "rating": {
    "avg": 5,
    "count": 4,
    "mine": 0
  },
  "notifyWatch": false
}"""
    }

    private fun extractResponseBody(filePath: String, fallbackJson: String): String {
        val file = File(filePath)
        if (!file.exists()) return fallbackJson
        val lines = file.readLines()
        val bodyIndex = lines.indexOfFirst { it.contains("RESPONSE BODY") }
        if (bodyIndex == -1) return fallbackJson
        val jsonLines = lines.drop(bodyIndex + 1).dropWhile { it.isBlank() }
        val content = jsonLines.joinToString("\n").trim()
        return if (content.startsWith("{")) content else fallbackJson
    }

    @Test
    fun testStationTelemetryModelsAndParserComprehensive() {
        // =====================================================================
        // Verification 1: StationAccessTokens Parsing from curl_capture_20260905_005601
        // =====================================================================
        val tokenJson1 = extractResponseBody(
            "curl_capture_20260905_005601/20260905_005552_POST_tram-sac-vinfast-nq-hkd-nguyen-duc-loc-c.bni0170.h.txt",
            CAPTURE_005601_TOKEN_JSON
        )
        val tokensResult1 = StationTelemetryParser.parseAccessTokens(tokenJson1)
        assertTrue("Tokens parsing must succeed", tokensResult1.isSuccess)
        val tokens1 = tokensResult1.getOrThrow()

        assertEquals("1788545175.none.PnOCazjNe3ic0AjFAGAG-lurZuWsLfcmeSmWbcFF6m8", tokens1.chargeToken)
        assertEquals("1788544875.7EpdFVasCkEYao8YhbAoNMxwiPJhl7vUDs9GMi3cP20", tokens1.apiToken)
        assertEquals("a7e7dec61eafb1d2fd6ac7ab12dbc319", tokens1.ratingCsrf)
        assertFalse("hasGo must be false", tokens1.hasGo)
        assertFalse("hasBiz must be false", tokens1.hasBiz)
        assertEquals("", tokens1.historyToken7)
        assertEquals("", tokens1.historyToken30)

        // Rating metadata verification
        val rating1 = tokens1.rating
        assertNotNull("Rating summary must be parsed", rating1)
        assertEquals(5.0, rating1!!.avg, 0.001)
        assertEquals(3, rating1.count)
        assertEquals(0, rating1.mine)

        // Secondary token capture verification (count = 4)
        val tokenJson2 = extractResponseBody(
            "curl_capture_20260905_005946/20260905_005938_POST_tram-sac-vinfast-cty-tnhh-cung-cap-thuc-pham-sach-.txt",
            CAPTURE_005946_TOKEN_JSON
        )
        val tokensResult2 = StationTelemetryParser.parseAccessTokens(tokenJson2)
        assertTrue(tokensResult2.isSuccess)
        val tokens2 = tokensResult2.getOrThrow()
        assertEquals(4, tokens2.rating?.count)

        // Malformed JSON failure verification
        val invalidTokenResult = StationTelemetryParser.parseAccessTokens("{ \"invalid\": true }")
        assertTrue("Missing required tokens must yield failure Result", invalidTokenResult.isFailure)

        // =====================================================================
        // Verification 2: Active Charging Telemetry from curl_capture_20260905_005601
        // =====================================================================
        val activeChargingJson = extractResponseBody(
            "curl_capture_20260905_005601/20260905_005553_POST_charging.txt",
            CAPTURE_005601_ACTIVE_CHARGING_JSON
        )
        val activeResult = StationTelemetryParser.parseChargingResponse(activeChargingJson)
        assertTrue("Active charging parse must succeed", activeResult.isSuccess)
        val activeTelemetry = activeResult.getOrThrow()

        // busyByKw mapping
        assertEquals(2, activeTelemetry.busyByKw.size)
        assertEquals(4, activeTelemetry.busyByKw[60])
        assertEquals(5, activeTelemetry.busyByKw[120])
        assertFalse("Active charging payload must not be marked locked", activeTelemetry.isLocked)

        // Clean forecast sanitization: HTML tags stripped, button/svg removed, Vietnamese diacritics preserved
        val expectedForecast = "Dự kiến 2 xe sạc trụ 120kW sẽ xong trong 1-7 phút, 1 xe sạc trụ 60kW sẽ xong trong 1 phút nữa"
        assertEquals(expectedForecast, activeTelemetry.cleanForecast)
        assertNotNull(activeTelemetry.rawTicker)
        assertTrue(activeTelemetry.rawTicker!!.contains("amd-hasmore"))

        // =====================================================================
        // Verification 3: Locked Charging Telemetry from curl_capture_20260905_005946
        // =====================================================================
        val lockedChargingJson = extractResponseBody(
            "curl_capture_20260905_005946/20260905_005938_POST_charging.txt",
            CAPTURE_005946_LOCKED_CHARGING_JSON
        )
        val lockedResult = StationTelemetryParser.parseChargingResponse(lockedChargingJson)
        assertTrue("Locked charging parse must succeed", lockedResult.isSuccess)
        val lockedTelemetry = lockedResult.getOrThrow()

        // Empty busyByKw
        assertTrue("busyByKw must be empty for locked state", lockedTelemetry.busyByKw.isEmpty())
        assertTrue("isLocked must be true for amd-locked ticker", lockedTelemetry.isLocked)
        assertNull("cleanForecast must be null when locked or upsell string is detected", lockedTelemetry.cleanForecast)

        // =====================================================================
        // Verification 4: Precise Computation of StationPortStatus
        // =====================================================================
        // 4a. Connectors with multiplier "x": 60kW x 6, 120kW x 6 with busyByKw {60=4, 120=5}
        val connectorsWithMultiplier = "60kW x 6, 120kW x 6"
        val statusesA = StationTelemetryParser.derivePortStatuses(
            connectors = connectorsWithMultiplier,
            busyByKw = mapOf(60 to 4, 120 to 5)
        )
        assertEquals(2, statusesA.size)

        val port120 = statusesA.first { it.kw == 120 }
        assertEquals(120, port120.kw)
        assertEquals(6, port120.totalPorts)
        assertEquals(5, port120.busyCount)
        assertEquals(1, port120.availablePorts) // 6 - 5 = 1

        val port60 = statusesA.first { it.kw == 60 }
        assertEquals(60, port60.kw)
        assertEquals(6, port60.totalPorts)
        assertEquals(4, port60.busyCount)
        assertEquals(2, port60.availablePorts) // 6 - 4 = 2

        // 4b. Full occupancy: availablePorts = 0
        val statusesFull = StationTelemetryParser.derivePortStatuses(
            connectors = "60kW x 4",
            busyByKw = mapOf(60 to 4)
        )
        assertEquals(1, statusesFull.size)
        assertEquals(0, statusesFull[0].availablePorts)
        assertEquals(4, statusesFull[0].totalPorts)
        assertEquals(4, statusesFull[0].busyCount)

        // 4c. Over-subscription bound: availablePorts = max(0, total - busy) never negative
        val statusesOverSub = StationTelemetryParser.derivePortStatuses(
            connectors = "60kW x 2",
            busyByKw = mapOf(60 to 5)
        )
        assertEquals(0, statusesOverSub[0].availablePorts)
        assertEquals(2, statusesOverSub[0].totalPorts)
        assertEquals(5, statusesOverSub[0].busyCount)

        // 4d. Completely vacant station: availablePorts = totalPorts
        val statusesVacant = StationTelemetryParser.derivePortStatuses(
            connectors = "120kW x 4",
            busyByKw = mapOf(120 to 0)
        )
        assertEquals(4, statusesVacant[0].availablePorts)

        // 4e. Alternate connector string formats (parentheses, colons, repeats)
        val connectorFormats = listOf(
            "60kW (4), 120kW (6)",
            "4x 60kW, 6x 120kW",
            "60kW: 4 cổng, 120kW: 6 cổng",
            "60kW: trống 0/4 cổng, 120kW: trống 1/6 cổng"
        )
        for (format in connectorFormats) {
            val definitions = StationTelemetryParser.parseConnectorDefinitions(format)
            assertEquals("Format '$format' must yield 4 for 60kW", 4, definitions[60])
            assertEquals("Format '$format' must yield 6 for 120kW", 6, definitions[120])
        }

        // 4f. PowerPort list overload derivation
        val domainPowers = listOf(
            PowerPort(typeWatts = 120000L, label = "120kW", availablePlugs = 0, totalPlugs = 8),
            PowerPort(typeWatts = 60000L, label = "60kW", availablePlugs = 0, totalPlugs = 4)
        )
        val statusesFromPowers = StationTelemetryParser.derivePortStatuses(
            powers = domainPowers,
            busyByKw = mapOf(120 to 5, 60 to 3)
        )
        val status120FromPower = statusesFromPowers.first { it.kw == 120 }
        assertEquals(8, status120FromPower.totalPorts)
        assertEquals(5, status120FromPower.busyCount)
        assertEquals(3, status120FromPower.availablePorts)

        val status60FromPower = statusesFromPowers.first { it.kw == 60 }
        assertEquals(4, status60FromPower.totalPorts)
        assertEquals(3, status60FromPower.busyCount)
        assertEquals(1, status60FromPower.availablePorts)

        // =====================================================================
        // Verification 5: Immutability and Data Integrity of Station24hStats
        // =====================================================================
        val stats = Station24hStats(
            peakUsage = 9,
            avgUsage = 4,
            peakHour = "17-18h",
            fillRate = 67
        )
        assertEquals(9, stats.peakUsage)
        assertEquals(4, stats.avgUsage)
        assertEquals("17-18h", stats.peakHour)
        assertEquals(67, stats.fillRate)

        // Immutability: copy generates new instance preserving original
        val copiedStats = stats.copy(fillRate = 80)
        assertEquals(67, stats.fillRate)
        assertEquals(80, copiedStats.fillRate)
        assertEquals(stats.peakUsage, copiedStats.peakUsage)

        // Empty/zero fallback stats instance
        val fallbackStats = Station24hStats(
            peakUsage = 0,
            avgUsage = 0,
            peakHour = "-",
            fillRate = 0
        )
        assertEquals(0, fallbackStats.peakUsage)
        assertEquals("-", fallbackStats.peakHour)
        assertEquals(0, fallbackStats.fillRate)
    }
}
