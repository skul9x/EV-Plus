package com.evcs.favorites.util

import com.evcs.favorites.data.auth.AuthEngine
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.domain.StationTelemetryParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Single Comprehensive Verification Test for Phase 04: Regex Pre-compilation & CPU Optimization.
 *
 * Verifies:
 * 1. SessionManager extracts standard (evcs, PHPSESSID) and arbitrary cached cookies accurately across formats.
 * 2. StationTelemetryParser evaluates locked tickers consistently using pre-compiled TICKER_DIV_REGEX.
 * 3. AuthEngine extracts primary EVCS_REWARD and fallback CSRF tokens accurately without dynamic regex compilation.
 * 4. All static regex matchers are thread-safe and deterministic under high concurrent loads.
 */
class PrecompiledRegexPerformanceTest {

    @Test
    fun testSessionManager_extractCookieValue_extractsAllStandardCookiesAccurately() {
        runBlocking {
            val storage = InMemorySessionStorage()
            val sessionManager = SessionManager(storage)

            // 1. Standard evcs cookie extraction
            val evcsHeader1 = "evcs=auth_cookie_token_12345; Path=/; Domain=evcs.vn; Secure; HttpOnly"
            assertEquals("auth_cookie_token_12345", sessionManager.extractCookieValue(evcsHeader1, "evcs"))
            assertEquals("auth_cookie_token_12345", SessionManager.extractCookieValue(evcsHeader1, "evcs"))

            // 2. Standard PHPSESSID cookie extraction
            val phpHeader1 = "PHPSESSID=session_secret_998877; Path=/; HttpOnly"
            assertEquals("session_secret_998877", sessionManager.extractCookieValue(phpHeader1, "PHPSESSID"))
            assertEquals("session_secret_998877", SessionManager.extractCookieValue(phpHeader1, "PHPSESSID"))

            // 3. Multi-cookie composite header with standard and custom cookies
            val multiHeader = "theme=dark; evcs=multi_evcs_token; other_id=42; PHPSESSID=multi_php_session; custom_cid=client_999"
            assertEquals("multi_evcs_token", sessionManager.extractCookieValue(multiHeader, "evcs"))
            assertEquals("multi_php_session", sessionManager.extractCookieValue(multiHeader, "PHPSESSID"))
            assertEquals("client_999", sessionManager.extractCookieValue(multiHeader, "custom_cid"))
            // Subsequent extraction of custom_cid to verify cache retrieval
            assertEquals("client_999", sessionManager.extractCookieValue(multiHeader, "custom_cid"))

            // 4. Formatting edge cases (spaces around semicolon, start of line, trailing characters)
            val spacedHeader = "   ;   evcs=spaced_token_abc   ;   PHPSESSID=spaced_php_xyz"
            assertEquals("spaced_token_abc", sessionManager.extractCookieValue(spacedHeader, "evcs"))
            assertEquals("spaced_php_xyz", sessionManager.extractCookieValue(spacedHeader, "PHPSESSID"))

            // 5. Negative and mismatch cases
            val mismatchHeader = "some_evcs=prefix_val; evcs_other=suffix_val; test=123"
            assertNull(sessionManager.extractCookieValue(mismatchHeader, "evcs"))
            assertNull(sessionManager.extractCookieValue(mismatchHeader, "PHPSESSID"))
            assertNull(sessionManager.extractCookieValue("", "evcs"))
            assertNull(sessionManager.extractCookieValue("random_string_without_cookie", "evcs"))

            // 6. Integration with SessionManager state saving
            sessionManager.saveFromSetCookieHeader("evcs=state_evcs; PHPSESSID=state_php")
            assertEquals("state_evcs", sessionManager.authCookie)
            assertEquals("state_php", sessionManager.phpSessionId)

            sessionManager.saveAuthCookie("evcs=updated_evcs; Path=/")
            assertEquals("updated_evcs", sessionManager.authCookie)

            sessionManager.saveSession("PHPSESSID=updated_php; Path=/", "test_csrf")
            assertEquals("updated_php", sessionManager.phpSessionId)
            assertEquals("test_csrf", sessionManager.csrfToken)

            // 7. Concurrent evaluation to verify thread safety of pre-compiled regexes & ConcurrentHashMap cache
            val concurrentJobs = (1..100).map { id ->
                async(Dispatchers.Default) {
                    val header = "evcs=concurrent_evcs_$id; PHPSESSID=concurrent_php_$id; dyn_key_$id=dyn_val_$id"
                    val evcs = sessionManager.extractCookieValue(header, "evcs")
                    val php = sessionManager.extractCookieValue(header, "PHPSESSID")
                    val dyn = sessionManager.extractCookieValue(header, "dyn_key_$id")
                    assertEquals("concurrent_evcs_$id", evcs)
                    assertEquals("concurrent_php_$id", php)
                    assertEquals("dyn_val_$id", dyn)
                }
            }
            concurrentJobs.awaitAll()
        }
    }

    @Test
    fun testStationTelemetryParser_isLockedTicker_detectsLockedStateConsistently() {
        runBlocking {
            // 1. Blank and null handling
            assertFalse(StationTelemetryParser.isLockedTicker(null))
            assertFalse(StationTelemetryParser.isLockedTicker(""))
            assertFalse(StationTelemetryParser.isLockedTicker("   \n\t  "))

            // 2. Direct string triggers
            assertTrue(StationTelemetryParser.isLockedTicker("amd-ticker amd-locked"))
            assertTrue(StationTelemetryParser.isLockedTicker("prefix amd-ticker amd-locked suffix"))
            assertTrue(StationTelemetryParser.isLockedTicker("<div>Xem dự báo cổng sạc trống của trạm</div>"))
            assertTrue(StationTelemetryParser.isLockedTicker("<p>xem DỰ BÁO cổng SẠC trống CỦA trạm</p>"))

            // 3. Pre-compiled TICKER_DIV_REGEX evaluation with double quotes
            val lockedDivDoubleQuote = """<div class="station-ticker-banner amd-locked highlight">Đang bảo trì</div>"""
            assertTrue(StationTelemetryParser.isLockedTicker(lockedDivDoubleQuote))

            // 4. Pre-compiled TICKER_DIV_REGEX evaluation with single quotes
            val lockedDivSingleQuote = """<div class='custom-box amd-locked'>Thông tin khóa</div>"""
            assertTrue(StationTelemetryParser.isLockedTicker(lockedDivSingleQuote))

            // 5. Unlocked and non-matching divs
            val unlockedDiv = """<div class="station-ticker-banner amd-unlocked highlight">4/8 cổng trống</div>"""
            assertFalse(StationTelemetryParser.isLockedTicker(unlockedDiv))

            val similarClassDiv = """<div class="amd-locked-extra amd-notlocked">Đang sạc</div>"""
            assertFalse(StationTelemetryParser.isLockedTicker(similarClassDiv))

            val plainHtmlNoDiv = """<span>3/6 cổng đang sạc</span>"""
            assertFalse(StationTelemetryParser.isLockedTicker(plainHtmlNoDiv))

            // 6. Integration with sanitizeForecast
            assertNull(StationTelemetryParser.sanitizeForecast(lockedDivDoubleQuote))
            assertNull(StationTelemetryParser.sanitizeForecast("<div>Xem dự báo cổng sạc trống của trạm</div>"))

            val cleanOutput = StationTelemetryParser.sanitizeForecast("""<div class="unlocked"><b>Trạm:</b> 2 cổng trống</div>""")
            assertEquals("Trạm: 2 cổng trống", cleanOutput)

            // 7. Concurrent evaluation across multiple coroutines
            val concurrentJobs = (1..100).map { id ->
                async(Dispatchers.Default) {
                    val lockedPayload = """<div id="st_$id" class="port-status amd-locked active">Khóa $id</div>"""
                    val unlockedPayload = """<div id="st_$id" class="port-status amd-open active">Mở $id</div>"""
                    assertTrue(StationTelemetryParser.isLockedTicker(lockedPayload))
                    assertFalse(StationTelemetryParser.isLockedTicker(unlockedPayload))
                }
            }
            concurrentJobs.awaitAll()
        }
    }

    @Test
    fun testAuthEngine_extractCsrf_extractsFromScriptAndPayload() {
        runBlocking {
            val sessionManager = SessionManager(InMemorySessionStorage())
            val authEngine = AuthEngine(sessionManager)

            // 1. Primary EVCS_REWARD regex with double quotes
            val primaryHtml1 = """
                <html>
                    <head>
                        <script>
                            window.EVCS_REWARD = {
                                csrf: "e8a9f01234567890abcdef1234567890",
                                version: "1.0.0"
                            };
                        </script>
                    </head>
                </html>
            """.trimIndent()
            val expectedToken1 = "e8a9f01234567890abcdef1234567890"
            assertEquals(expectedToken1, authEngine.extractCsrfToken(primaryHtml1))
            assertEquals(expectedToken1, AuthEngine.extractCsrfToken(primaryHtml1))

            // 2. Primary EVCS_REWARD regex with single quotes and variable spacing
            val primaryHtml2 = """
                <script>window.EVCS_REWARD={mode:'prod',csrf:'0123456789abcdef0123456789abcdef'};</script>
            """.trimIndent()
            val expectedToken2 = "0123456789abcdef0123456789abcdef"
            assertEquals(expectedToken2, authEngine.extractCsrfToken(primaryHtml2))
            assertEquals(expectedToken2, AuthEngine.extractCsrfToken(primaryHtml2))

            // 3. Fallback regex for raw JS object/payload snippets without EVCS_REWARD wrapper
            val fallbackPayload1 = """{ status: "ok", csrf: "fedcba9876543210fedcba9876543210" }"""
            val expectedFallback1 = "fedcba9876543210fedcba9876543210"
            assertEquals(expectedFallback1, authEngine.extractCsrfToken(fallbackPayload1))
            assertEquals(expectedFallback1, AuthEngine.extractCsrfToken(fallbackPayload1))

            // 4. Fallback regex with 16-character minimum and up to 64-character hex token
            val fallback16Char = """var obj = { csrf: '1122334455667788' };"""
            assertEquals("1122334455667788", authEngine.extractCsrfToken(fallback16Char))

            val fallback64Char = "csrf: \"1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef\""
            assertEquals("1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef", authEngine.extractCsrfToken(fallback64Char))

            // 5. Negative cases: missing or invalid CSRF formats
            assertNull(authEngine.extractCsrfToken("<html><body><div>No CSRF here</div></body></html>"))
            assertNull(authEngine.extractCsrfToken("""csrf: "short_hex"""")) // < 16 chars
            assertNull(authEngine.extractCsrfToken("""csrf: "invalid_non_hex_characters_1234567890""""))

            // 6. Concurrent evaluation across multiple coroutines
            val concurrentJobs = (1..100).map { id ->
                async(Dispatchers.Default) {
                    val hex32 = "%032x".format(id)
                    val html = """window.EVCS_REWARD = { index: $id, csrf: "$hex32" };"""
                    val extracted = authEngine.extractCsrfToken(html)
                    assertEquals(hex32, extracted)
                }
            }
            concurrentJobs.awaitAll()
        }
    }
}
