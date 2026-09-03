package com.evcs.favorites

import com.evcs.favorites.data.auth.AuthEngine
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import kotlinx.coroutines.test.runTest
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
 * Single Comprehensive Verification Test for Phase 01:
 * - SessionManager UUID generation, persistence, and Cookie header formatting
 * - CSRF token & PHPSESSID extraction from reward.html response
 * - OTP send request formatting
 * - OTP verification request formatting and 1-year evcs cookie extraction
 * - StateFlow isLoggedIn transitions and logout
 */
class AuthEngineTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var storage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var authEngine: AuthEngine

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        storage = InMemorySessionStorage()
        sessionManager = SessionManager(storage)

        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        val baseUrl = mockWebServer.url("").toString().removeSuffix("/")
        authEngine = AuthEngine(
            sessionManager = sessionManager,
            client = client,
            baseUrl = baseUrl
        )
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun testSessionManagerDeviceIdAndCookieHeaderFormatting() {
        // 1. Validate deviceId auto-generation and persistence
        val deviceId1 = sessionManager.deviceId
        assertNotNull(deviceId1)
        assertTrue("UUID should match standard format", deviceId1.matches(Regex("[0-9a-fA-F-]{36}")))
        assertEquals("Subsequent calls must return identical persisted deviceId", deviceId1, sessionManager.deviceId)

        // Initial cookie header contains only deviceId
        assertEquals("evcs_did=$deviceId1", sessionManager.getCookieHeader())

        // 2. Set PHPSESSID
        sessionManager.phpSessionId = "test_phpsessid_123"
        assertEquals(
            "PHPSESSID=test_phpsessid_123; evcs_did=$deviceId1",
            sessionManager.getCookieHeader()
        )

        // 3. Set evcs 1-year auth cookie with full Set-Cookie header formatting
        val rawSetCookie = "evcs=81093f07e42cb6fff6%3A1c54cb89212a1f600415a2d61761a6b19b11da3d87cf6188b6c96c4fbb770e66; expires=Fri, 03 Sep 2027 03:16:09 GMT; Max-Age=31536000; path=/; secure; HttpOnly; SameSite=Lax"
        sessionManager.saveAuthCookie(rawSetCookie)

        val expectedAuthCookie = "81093f07e42cb6fff6%3A1c54cb89212a1f600415a2d61761a6b19b11da3d87cf6188b6c96c4fbb770e66"
        assertEquals(expectedAuthCookie, sessionManager.authCookie)
        assertTrue(sessionManager.hasAuthCookie())

        // Validate full cookie header formatting
        val fullCookieHeader = sessionManager.getCookieHeader()
        assertEquals(
            "PHPSESSID=test_phpsessid_123; evcs=$expectedAuthCookie; evcs_did=$deviceId1",
            fullCookieHeader
        )

        // 4. Validate clearSession retains deviceId but wipes credentials
        sessionManager.clearSession()
        assertNull(sessionManager.authCookie)
        assertNull(sessionManager.phpSessionId)
        assertFalse(sessionManager.hasAuthCookie())
        assertEquals(deviceId1, sessionManager.deviceId)
    }

    @Test
    fun testCsrfExtractionFromRewardPage() = runTest {
        val sampleRewardHtml = """
            <!DOCTYPE html>
            <html>
            <head><title>Quà tặng</title></head>
            <body>
            <div class="evcs-card rw-card"></div>
            <script>window.EVCS_REWARD={csrf:"8853a8de30243db3766ef26586ab3f8a" };</script>
            </body>
            </html>
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Set-Cookie", "PHPSESSID=b39087166a09b6c62b34df6ef47925b2; path=/")
                .setBody(sampleRewardHtml)
        )

        val result = authEngine.fetchCsrfToken()
        assertTrue("fetchCsrfToken should succeed", result.isSuccess)
        val csrf = result.getOrThrow()
        assertEquals("8853a8de30243db3766ef26586ab3f8a", csrf)
        assertEquals("8853a8de30243db3766ef26586ab3f8a", sessionManager.csrfToken)
        assertEquals("b39087166a09b6c62b34df6ef47925b2", sessionManager.phpSessionId)

        // Verify request headers
        val recordedRequest = mockWebServer.takeRequest()
        assertEquals("/reward.html", recordedRequest.path)
        assertEquals("POST", recordedRequest.method)
        assertEquals("reward", recordedRequest.getHeader("X-Partial"))
        assertEquals(AuthEngine.USER_AGENT, recordedRequest.getHeader("User-Agent"))
        assertEquals("empty", recordedRequest.getHeader("Sec-Fetch-Dest"))
        assertEquals("cors", recordedRequest.getHeader("Sec-Fetch-Mode"))
        assertEquals("same-origin", recordedRequest.getHeader("Sec-Fetch-Site"))
    }

    @Test
    fun testSendOtpSuccessAndFailure() = runTest {
        sessionManager.csrfToken = "test_csrf_token"
        sessionManager.phpSessionId = "test_phpsessid"

        // 1. Success case
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"ok": true, "resend_in": 60, "dev_otp": "654321"}""")
        )

        val sendResult = authEngine.sendOtp("skul9x@gmail.com")
        assertTrue(sendResult.isSuccess)
        val otpResp = sendResult.getOrThrow()
        assertTrue(otpResp.ok)
        assertEquals(60, otpResp.resend_in)
        assertEquals("654321", otpResp.dev_otp)
        assertEquals("skul9x@gmail.com", sessionManager.userEmail)

        // Verify request payload and headers
        val request = mockWebServer.takeRequest()
        val requestBody = request.body.readUtf8()
        assertTrue(requestBody.contains("\"action\":\"send_otp\""))
        assertTrue(requestBody.contains("\"csrf\":\"test_csrf_token\""))
        assertTrue(requestBody.contains("\"email\":\"skul9x@gmail.com\""))
        assertTrue(requestBody.contains("\"agree\":true"))
        assertTrue(request.getHeader("Cookie")!!.contains("PHPSESSID=test_phpsessid"))

        // 2. Failure case
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"ok": false, "error": "Email không hợp lệ"}""")
        )

        val failResult = authEngine.sendOtp("invalid_email")
        assertTrue(failResult.isFailure)
        assertEquals("Email không hợp lệ", failResult.exceptionOrNull()?.message)
    }

    @Test
    fun testVerifyOtpExtractionOf1YearEvcsCookieAndStateFlowTransitions() = runTest {
        sessionManager.csrfToken = "test_csrf_token"
        sessionManager.phpSessionId = "test_phpsessid"

        assertFalse("Initial state must be logged out", authEngine.isLoggedIn.value)

        // Mock verification response with 1-year evcs cookie
        val oneYearCookie = "evcs=81093f07e42cb6fff6%3A1c54cb89212a1f600415a2d61761a6b19b11da3d87cf6188b6c96c4fbb770e66; expires=Fri, 03 Sep 2027 03:16:09 GMT; Max-Age=31536000; path=/; secure; HttpOnly; SameSite=Lax"
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Set-Cookie", oneYearCookie)
                .setBody("""{"ok": true}""")
        )

        val verifyResult = authEngine.verifyOtp("skul9x@gmail.com", "123456")
        assertTrue("verifyOtp should succeed", verifyResult.isSuccess)
        assertTrue(verifyResult.getOrThrow())

        // Verify request payload
        val request = mockWebServer.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"action\":\"verify_otp\""))
        assertTrue(body.contains("\"csrf\":\"test_csrf_token\""))
        assertTrue(body.contains("\"email\":\"skul9x@gmail.com\""))
        assertTrue(body.contains("\"otp\":\"123456\""))

        // Verify evcs cookie was saved to sessionManager
        val expectedEvcs = "81093f07e42cb6fff6%3A1c54cb89212a1f600415a2d61761a6b19b11da3d87cf6188b6c96c4fbb770e66"
        assertEquals(expectedEvcs, sessionManager.authCookie)
        assertTrue(sessionManager.hasAuthCookie())

        // Verify isLoggedIn StateFlow transitioned to true
        assertTrue("isLoggedIn must transition to true after verifyOtp", authEngine.isLoggedIn.value)

        // Verify logout clears credentials and transitions isLoggedIn to false
        authEngine.logout()
        assertFalse("isLoggedIn must transition to false after logout", authEngine.isLoggedIn.value)
        assertNull(sessionManager.authCookie)
        assertFalse(sessionManager.hasAuthCookie())
    }
}
