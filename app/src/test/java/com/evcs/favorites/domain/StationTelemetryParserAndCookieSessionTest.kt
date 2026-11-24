package com.evcs.favorites.domain

import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Single Comprehensive Verification Test for Phase 01:
 * Telemetry Parser Resilience, Integrity Token & RFC 6265bis Cookie Session.
 *
 * Verifications:
 * 1. StationTelemetryParser @JsonNames compatibility ("chargingKw" modern vs "busyKw" legacy).
 * 2. StationTelemetryParser null safety with empty/null/missing chargingKw payloads.
 * 3. Integrity token ("u") extraction into StationTelemetry.uToken and fallback to null.
 * 4. StationTelemetryParser 24h history time-series matrix deserialization, coordinate sizing, clamping, and error handling.
 * 5. SessionManager RFC 6265bis __Host-tp cookie extraction, quote stripping, and storage persistence.
 * 6. SessionManager cookie header generation with RFC 6265bis __Host-tp inclusion order.
 * 7. SessionManager clearSession wipe verification preserving deviceId.
 */
class StationTelemetryParserAndCookieSessionTest {

    private lateinit var storage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager

    @Before
    fun setUp() {
        storage = InMemorySessionStorage()
        sessionManager = SessionManager(storage)
    }

    // =========================================================================
    // 1. StationTelemetryParser: @JsonNames Modern ("chargingKw") & Legacy ("busyKw")
    // =========================================================================

    @Test
    fun testParseChargingResponse_modernChargingKw_deserializesCorrectly() {
        val modernJson = """
            {
                "ticker": "<div class=\"amd-ticker\"><span>Xe đang sạc</span></div>",
                "chargingKw": {
                    "60": 3,
                    "120": 4
                },
                "u": "integrity_token_modern_999",
                "totalPorts": 10,
                "charging": 7,
                "free": 3,
                "partial": false
            }
        """.trimIndent()

        val result = StationTelemetryParser.parseChargingResponse(modernJson)
        assertTrue("Parsing modern chargingKw payload must succeed", result.isSuccess)
        val telemetry = result.getOrThrow()

        assertEquals(2, telemetry.busyByKw.size)
        assertEquals(3, telemetry.busyByKw[60])
        assertEquals(4, telemetry.busyByKw[120])
        assertEquals("integrity_token_modern_999", telemetry.uToken)
        assertFalse(telemetry.isLocked)
        assertEquals("Xe đang sạc", telemetry.cleanForecast)
    }

    @Test
    fun testParseChargingResponse_legacyBusyKw_deserializesCorrectly() {
        val legacyJson = """
            {
                "ticker": "<div class=\"amd-ticker\"><span>Sắp hoàn thành</span></div>",
                "busyKw": {
                    "60": 1,
                    "250": 2
                },
                "partial": true
            }
        """.trimIndent()

        val result = StationTelemetryParser.parseChargingResponse(legacyJson)
        assertTrue("Parsing legacy busyKw payload must succeed", result.isSuccess)
        val telemetry = result.getOrThrow()

        assertEquals(2, telemetry.busyByKw.size)
        assertEquals(1, telemetry.busyByKw[60])
        assertEquals(2, telemetry.busyByKw[250])
        assertNull("Missing u token must result in null uToken", telemetry.uToken)
        assertFalse(telemetry.isLocked)
        assertEquals("Sắp hoàn thành", telemetry.cleanForecast)
    }

    // =========================================================================
    // 2. StationTelemetryParser: Null Safety & Offline Stations
    // =========================================================================

    @Test
    fun testParseChargingResponse_nullAndEmptyBusyKw_handlesGracefully() {
        // Case A: chargingKw is explicit null
        val nullChargingJson = """
            {
                "ticker": null,
                "chargingKw": null,
                "partial": true
            }
        """.trimIndent()

        val resultNull = StationTelemetryParser.parseChargingResponse(nullChargingJson)
        assertTrue("Explicit null chargingKw must succeed without crashing", resultNull.isSuccess)
        val telemetryNull = resultNull.getOrThrow()
        assertTrue("busyByKw must be empty map", telemetryNull.busyByKw.isEmpty())
        assertNull(telemetryNull.rawTicker)
        assertNull(telemetryNull.cleanForecast)
        assertNull(telemetryNull.uToken)

        // Case B: busyKw/chargingKw is completely omitted
        val omittedJson = """
            {
                "u": "token_only"
            }
        """.trimIndent()

        val resultOmitted = StationTelemetryParser.parseChargingResponse(omittedJson)
        assertTrue("Omitted chargingKw/busyKw must succeed", resultOmitted.isSuccess)
        val telemetryOmitted = resultOmitted.getOrThrow()
        assertTrue(telemetryOmitted.busyByKw.isEmpty())
        assertEquals("token_only", telemetryOmitted.uToken)

        // Case C: Decimal keys in kW mapping
        val decimalKeyJson = """
            {
                "chargingKw": {
                    "60.0": 2,
                    "120": 5,
                    "invalid_kw": 3
                }
            }
        """.trimIndent()

        val resultDecimal = StationTelemetryParser.parseChargingResponse(decimalKeyJson)
        assertTrue(resultDecimal.isSuccess)
        val telemetryDecimal = resultDecimal.getOrThrow()
        assertEquals(2, telemetryDecimal.busyByKw.size)
        assertEquals(2, telemetryDecimal.busyByKw[60])
        assertEquals(5, telemetryDecimal.busyByKw[120])
    }

    // =========================================================================
    // 3. StationTelemetryParser: 24-hour History Time-Series Deserialization
    // =========================================================================

    @Test
    fun testParse24hHistory_validMatrix_parsesAndClampsValues() {
        val jsonMatrix = """
            [
                [1732440000000, 4],
                [1732441800000, 6],
                [1732443600000, -2],
                [1732445400000, 0]
            ]
        """.trimIndent()

        val result = StationTelemetryParser.parse24hHistory(jsonMatrix)
        assertTrue("Valid history matrix parsing must succeed", result.isSuccess)
        val points = result.getOrThrow()

        assertEquals(4, points.size)
        assertEquals(Pair(1732440000000L, 4), points[0])
        assertEquals(Pair(1732441800000L, 6), points[1])
        // Negative usage point must be clamped to 0
        assertEquals(Pair(1732443600000L, 0), points[2])
        assertEquals(Pair(1732445400000L, 0), points[3])
    }

    @Test
    fun testParse24hHistory_edgeCasesAndMalformedData() {
        // Case A: Empty array
        val emptyResult = StationTelemetryParser.parse24hHistory("[]")
        assertTrue(emptyResult.isSuccess)
        assertTrue(emptyResult.getOrThrow().isEmpty())

        // Case B: Blank string
        val blankResult = StationTelemetryParser.parse24hHistory("   ")
        assertTrue(blankResult.isSuccess)
        assertTrue(blankResult.getOrThrow().isEmpty())

        // Case C: Sub-arrays with fewer than 2 elements (ignored safely)
        val irregularMatrix = """
            [
                [1732440000000, 5],
                [1732441800000],
                [],
                [1732443600000, 8, 999]
            ]
        """.trimIndent()
        val irregularResult = StationTelemetryParser.parse24hHistory(irregularMatrix)
        assertTrue(irregularResult.isSuccess)
        val points = irregularResult.getOrThrow()
        assertEquals(2, points.size)
        assertEquals(Pair(1732440000000L, 5), points[0])
        assertEquals(Pair(1732443600000L, 8), points[1])

        // Case D: Malformed JSON syntax
        val malformedResult = StationTelemetryParser.parse24hHistory("{ not an array }")
        assertTrue("Malformed JSON must return failure result", malformedResult.isFailure)
    }

    // =========================================================================
    // 4. SessionManager: RFC 6265bis __Host-tp Cookie Extraction & Quote Stripping
    // =========================================================================

    @Test
    fun testExtractCookieValue_hostTpAndQuoteStripping() {
        // Standard unquoted __Host-tp cookie
        val headerUnquoted = "__Host-tp=token_abc_123; Path=/; Secure; HttpOnly"
        val valueUnquoted = SessionManager.extractCookieValue(headerUnquoted, SessionManager.KEY_HOST_TP)
        assertEquals("token_abc_123", valueUnquoted)

        // Quoted __Host-tp cookie (RFC 6265bis quote-wrapped value)
        val headerQuoted = "__Host-tp=\"token_quoted_456\"; Path=/; Secure; HttpOnly"
        val valueQuoted = SessionManager.extractCookieValue(headerQuoted, SessionManager.KEY_HOST_TP)
        assertEquals("token_quoted_456", valueQuoted)

        // Semicolon-prefixed in middle of compound Set-Cookie
        val compoundHeader = "other=123; __Host-tp=token_compound_789; Secure"
        val valueCompound = SessionManager.extractCookieValue(compoundHeader, SessionManager.KEY_HOST_TP)
        assertEquals("token_compound_789", valueCompound)
    }

    @Test
    fun testSaveFromSetCookieHeader_persistsAllCookieTypes() {
        val setCookieHeader = "__Host-tp=\"jwt.payload.signature\"; Secure; Path=/; HttpOnly"
        sessionManager.saveFromSetCookieHeader(setCookieHeader)

        assertEquals("jwt.payload.signature", sessionManager.hostTpCookie)
        assertEquals("jwt.payload.signature", storage.getString(SessionManager.KEY_HOST_TP))

        // Update with evcs and PHPSESSID in subsequent headers
        sessionManager.saveFromSetCookieHeader("evcs=evcs_auth_token_99; Path=/; Secure")
        sessionManager.saveFromSetCookieHeader("PHPSESSID=php_session_id_88; Path=/")

        assertEquals("jwt.payload.signature", sessionManager.hostTpCookie)
        assertEquals("evcs_auth_token_99", sessionManager.authCookie)
        assertEquals("php_session_id_88", sessionManager.phpSessionId)
    }

    // =========================================================================
    // 5. SessionManager: Cookie Header Formatting & RFC 6265bis Order
    // =========================================================================

    @Test
    fun testGetCookieHeader_includesHostTpWithCorrectOrder() {
        val did = sessionManager.deviceId
        assertNotNull(did)

        // 1. Initial state: only deviceId
        assertEquals("evcs_did=$did", sessionManager.getCookieHeader())

        // 2. Set __Host-tp only
        sessionManager.hostTpCookie = "host_tp_sec_token"
        assertEquals("__Host-tp=host_tp_sec_token; evcs_did=$did", sessionManager.getCookieHeader())

        // 3. Set authCookie and phpSessionId
        sessionManager.authCookie = "evcs_auth_value"
        sessionManager.phpSessionId = "phpsess_value"

        val expectedHeader = "PHPSESSID=phpsess_value; evcs=evcs_auth_value; __Host-tp=host_tp_sec_token; evcs_did=$did"
        assertEquals(expectedHeader, sessionManager.getCookieHeader())
    }

    // =========================================================================
    // 6. SessionManager: clearSession & Persistence Integrity
    // =========================================================================

    @Test
    fun testClearSession_wipesHostTpAndCredentialsWhilePreservingDeviceId() {
        val did = sessionManager.deviceId

        sessionManager.authCookie = "evcs_test"
        sessionManager.phpSessionId = "phpsess_test"
        sessionManager.hostTpCookie = "host_tp_test"
        sessionManager.csrfToken = "csrf_test"
        sessionManager.userEmail = "user@test.com"

        // Verify all exist in storage
        assertNotNull(storage.getString(SessionManager.KEY_AUTH_COOKIE))
        assertNotNull(storage.getString(SessionManager.KEY_PHP_SESSION))
        assertNotNull(storage.getString(SessionManager.KEY_HOST_TP))
        assertNotNull(storage.getString(SessionManager.KEY_CSRF_TOKEN))
        assertNotNull(storage.getString(SessionManager.KEY_USER_EMAIL))

        // Execute clearSession
        sessionManager.clearSession()

        // Credentials & session cookies must be null
        assertNull(sessionManager.authCookie)
        assertNull(sessionManager.phpSessionId)
        assertNull(sessionManager.hostTpCookie)
        assertNull(sessionManager.csrfToken)
        assertNull(sessionManager.userEmail)

        assertNull(storage.getString(SessionManager.KEY_AUTH_COOKIE))
        assertNull(storage.getString(SessionManager.KEY_PHP_SESSION))
        assertNull(storage.getString(SessionManager.KEY_HOST_TP))
        assertNull(storage.getString(SessionManager.KEY_CSRF_TOKEN))
        assertNull(storage.getString(SessionManager.KEY_USER_EMAIL))

        // DeviceId must be preserved
        assertEquals(did, sessionManager.deviceId)
        assertEquals("evcs_did=$did", sessionManager.getCookieHeader())
    }
}
