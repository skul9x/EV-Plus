package com.evcs.favorites

import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.routing.GoogleRoutesClient
import com.evcs.favorites.data.routing.RoutingApiException
import com.evcs.favorites.data.routing.RoutingEngineMode
import com.evcs.favorites.data.routing.RoutingPreferencesManager
import com.evcs.favorites.data.routing.RoutingSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
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

/**
 * Comprehensive verification test for Phase 03: Secure BYOK Preferences & Settings UI.
 *
 * Validates:
 * 1. Default Preferences:
 *    - Default settings initialize with googleApiKey = "", preferredEngine = AUTO,
 *      autoFallbackEnabled = true, and customOsrmServerUrl = null.
 * 2. Secure Persistence & Cold-Load:
 *    - Saving new settings persists all fields to SessionStorage.
 *    - A fresh manager instance cold-loads the persisted values correctly.
 * 3. Reactive StateFlow Updates:
 *    - Updates via saveSettings and convenience helpers propagate immediately.
 * 4. Google API Key Connection Validation (MockWebServer):
 *    - Blank API key returns immediate validation error.
 *    - HTTP 200 with valid route elements returns success.
 *    - Request headers and payload structure are formatted correctly.
 *    - HTTP 400 returns actionable invalid key message.
 *    - HTTP 403 (Billing disabled) returns billing activation guidance.
 *    - HTTP 403 (Routes API disabled) returns Routes API activation guidance.
 *    - HTTP 403 (API restrictions) returns restriction guidance.
 *    - HTTP 429 returns quota exceeded notification.
 *    - HTTP 200 with element-level RPC error returns failure.
 *    - Network connection failure returns failure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutingPreferencesManagerTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var storage: InMemorySessionStorage
    private lateinit var manager: RoutingPreferencesManager
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
        storage = InMemorySessionStorage()
        manager = RoutingPreferencesManager(
            storage = storage,
            baseUrl = mockServer.url("/").toString(),
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    // ==========================================
    // 1. Persistence & StateFlow Tests
    // ==========================================

    @Test
    fun testDefaultSettings_initializesWithExpectedDefaults() {
        val settings = manager.settings.value

        assertEquals("", settings.googleApiKey)
        assertEquals(RoutingEngineMode.AUTO, settings.preferredEngine)
        assertTrue(settings.autoFallbackEnabled)
        assertNull(settings.customOsrmServerUrl)
    }

    @Test
    fun testSaveAndRetrieveSettings_persistsCorrectlyAcrossInstances() {
        val updated = RoutingSettings(
            googleApiKey = "AIzaSyCustomKey987654321",
            preferredEngine = RoutingEngineMode.GOOGLE_ONLY,
            autoFallbackEnabled = false,
            customOsrmServerUrl = "https://custom-osrm.myev.vn/table"
        )

        manager.saveSettings(updated)

        // 1. Verify in-memory StateFlow was updated immediately
        assertEquals(updated, manager.settings.value)

        // 2. Verify underlying storage contains matching keys
        assertEquals("AIzaSyCustomKey987654321", storage.getString(RoutingPreferencesManager.KEY_GOOGLE_API_KEY))
        assertEquals("GOOGLE_ONLY", storage.getString(RoutingPreferencesManager.KEY_PREFERRED_ENGINE))
        assertEquals("false", storage.getString(RoutingPreferencesManager.KEY_AUTO_FALLBACK))
        assertEquals("https://custom-osrm.myev.vn/table", storage.getString(RoutingPreferencesManager.KEY_CUSTOM_OSRM_URL))

        // 3. Verify cold-boot persistence with brand new manager instance
        val newManager = RoutingPreferencesManager(
            storage = storage,
            baseUrl = mockServer.url("/").toString(),
            ioDispatcher = testDispatcher
        )
        val loaded = newManager.loadSettings()

        assertEquals("AIzaSyCustomKey987654321", loaded.googleApiKey)
        assertEquals(RoutingEngineMode.GOOGLE_ONLY, loaded.preferredEngine)
        assertFalse(loaded.autoFallbackEnabled)
        assertEquals("https://custom-osrm.myev.vn/table", loaded.customOsrmServerUrl)
    }

    @Test
    fun testConvenienceUpdaters_modifyTargetFieldsAndPropagate() = runTest(testDispatcher) {
        // Update key
        manager.updateGoogleApiKey("AIzaSyKey111")
        assertEquals("AIzaSyKey111", manager.settings.first().googleApiKey)
        assertEquals(RoutingEngineMode.AUTO, manager.settings.first().preferredEngine)

        // Update mode
        manager.updatePreferredEngine(RoutingEngineMode.OSRM_ONLY)
        assertEquals("AIzaSyKey111", manager.settings.first().googleApiKey)
        assertEquals(RoutingEngineMode.OSRM_ONLY, manager.settings.first().preferredEngine)

        // Update fallback
        manager.updateAutoFallback(false)
        assertFalse(manager.settings.first().autoFallbackEnabled)
    }

    // ==========================================
    // 2. Google API Key Validation Tests
    // ==========================================

    @Test
    fun testValidateGoogleApiKey_blankOrWhitespaceKey_returnsFailure() = runTest(testDispatcher) {
        val emptyResult = manager.validateGoogleApiKey("")
        assertTrue(emptyResult.isFailure)
        assertTrue(emptyResult.exceptionOrNull()?.message?.contains("Khóa API Google không hợp lệ") == true)

        val whitespaceResult = manager.validateGoogleApiKey("   ")
        assertTrue(whitespaceResult.isFailure)
        assertTrue(whitespaceResult.exceptionOrNull()?.message?.contains("Khóa API Google không hợp lệ") == true)

        assertEquals(0, mockServer.requestCount)
    }

    @Test
    fun testValidateGoogleApiKey_http200Success_returnsSuccessAndValidatesHeaders() = runTest(testDispatcher) {
        val mockResponseBody = """
            [
              {
                "originIndex": 0,
                "destinationIndex": 0,
                "status": {},
                "condition": "ROUTE_EXISTS",
                "distanceMeters": 3500,
                "duration": "240s",
                "staticDuration": "200s"
              }
            ]
        """.trimIndent()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(mockResponseBody)
        )

        val result = manager.validateGoogleApiKey("AIzaSyValidApiKey12345")

        assertTrue("Expected validation success", result.isSuccess)
        assertEquals(true, result.getOrNull())

        // Verify request dispatched to mockServer
        val request = mockServer.takeRequest()
        assertTrue(request.path?.contains("/distanceMatrix/v2:computeRouteMatrix") == true)
        assertEquals("AIzaSyValidApiKey12345", request.getHeader(GoogleRoutesClient.HEADER_API_KEY))
        assertEquals(GoogleRoutesClient.ANDROID_PACKAGE_VALUE, request.getHeader(GoogleRoutesClient.HEADER_ANDROID_PACKAGE))
        assertEquals(GoogleRoutesClient.FIELD_MASK_VALUE, request.getHeader(GoogleRoutesClient.HEADER_FIELD_MASK))
        assertTrue(request.body.readUtf8().contains("10.762622"))
    }

    @Test
    fun testValidateGoogleApiKey_http400InvalidKey_returnsDescriptiveError() = runTest(testDispatcher) {
        val errorBody = """
            {
              "error": {
                "code": 400,
                "message": "API key not valid. Please pass a valid API key.",
                "status": "INVALID_ARGUMENT"
              }
            }
        """.trimIndent()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody(errorBody)
        )

        val result = manager.validateGoogleApiKey("AIzaSyInvalidKey")

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(ex is RoutingApiException)
        assertEquals(400, (ex as RoutingApiException).statusCode)
        assertEquals("Khóa API Google không hợp lệ. Vui lòng kiểm tra lại ký tự khóa.", ex.message)
    }

    @Test
    fun testValidateGoogleApiKey_http403BillingNotEnabled_returnsBillingGuidance() = runTest(testDispatcher) {
        val errorBody = """
            {
              "error": {
                "code": 403,
                "message": "Billing has not been enabled on this project. Visit https://console.cloud.google.com/billing to enable billing.",
                "status": "PERMISSION_DENIED"
              }
            }
        """.trimIndent()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody(errorBody)
        )

        val result = manager.validateGoogleApiKey("AIzaSyNoBillingKey")

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull() as? RoutingApiException
        assertNotNull(ex)
        assertEquals(403, ex?.statusCode)
        assertEquals("Dự án Google Cloud chưa kích hoạt thanh toán (Billing).", ex?.message)
    }

    @Test
    fun testValidateGoogleApiKey_http403RoutesApiDisabled_returnsApiActivationGuidance() = runTest(testDispatcher) {
        val errorBody = """
            {
              "error": {
                "code": 403,
                "message": "Routes API has not been used in project 1098234 before or it is disabled.",
                "status": "PERMISSION_DENIED",
                "details": [
                  {
                    "@type": "type.googleapis.com/google.rpc.ErrorInfo",
                    "reason": "SERVICE_DISABLED"
                  }
                ]
              }
            }
        """.trimIndent()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody(errorBody)
        )

        val result = manager.validateGoogleApiKey("AIzaSyNoRoutesKey")

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull() as? RoutingApiException
        assertNotNull(ex)
        assertEquals(403, ex?.statusCode)
        assertEquals("Chưa kích hoạt 'Routes API' trên dự án Google Cloud của bạn.", ex?.message)
    }

    @Test
    fun testValidateGoogleApiKey_http403KeyRestrictions_returnsRestrictionGuidance() = runTest(testDispatcher) {
        val errorBody = """
            {
              "error": {
                "code": 403,
                "message": "Requests from this Android client application <empty> are blocked by API key restrictions.",
                "status": "PERMISSION_DENIED"
              }
            }
        """.trimIndent()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody(errorBody)
        )

        val result = manager.validateGoogleApiKey("AIzaSyRestrictedKey")

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull() as? RoutingApiException
        assertNotNull(ex)
        assertEquals(403, ex?.statusCode)
        assertEquals("Khóa API bị giới hạn ứng dụng hoặc IP. Vui lòng kiểm tra cài đặt hạn chế trên Google Cloud.", ex?.message)
    }

    @Test
    fun testValidateGoogleApiKey_http429QuotaExceeded_returnsQuotaMessage() = runTest(testDispatcher) {
        val errorBody = """
            {
              "error": {
                "code": 429,
                "message": "Quota exceeded for quota metric 'Queries' and limit 'Queries per minute'.",
                "status": "RESOURCE_EXHAUSTED"
              }
            }
        """.trimIndent()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Content-Type", "application/json")
                .setBody(errorBody)
        )

        val result = manager.validateGoogleApiKey("AIzaSyOverQuotaKey")

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull() as? RoutingApiException
        assertNotNull(ex)
        assertEquals(429, ex?.statusCode)
        assertEquals("Vượt quá hạn ngạch yêu cầu của Google Cloud API.", ex?.message)
    }

    @Test
    fun testValidateGoogleApiKey_http200WithElementRpcError_returnsFailure() = runTest(testDispatcher) {
        val bodyWithRpcError = """
            [
              {
                "originIndex": 0,
                "destinationIndex": 0,
                "status": {
                  "code": 3,
                  "message": "Invalid API key provided"
                }
              }
            ]
        """.trimIndent()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(bodyWithRpcError)
        )

        val result = manager.validateGoogleApiKey("AIzaSyInvalidKeyInside")

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertEquals("Khóa API Google không hợp lệ. Vui lòng kiểm tra lại ký tự khóa.", ex?.message)
    }

    @Test
    fun testValidateGoogleApiKey_networkException_returnsFailureGracefully() = runTest(testDispatcher) {
        // Shutdown server prematurely to force network connection failure
        mockServer.shutdown()

        val result = manager.validateGoogleApiKey("AIzaSyNetworkFailKey")

        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull())
    }
}
