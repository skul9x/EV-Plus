package com.evcs.favorites.data.telemetry

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsTelemetryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Comprehensive test suite verifying EVCS Telemetry Protocol Migration across
 * EvcsTelemetryDataSource and EvcsTelemetryRepository (Phase 02).
 *
 * Verifies:
 * - Content-Type: text/plain; charset=utf-8 on /update
 * - Tokens t, a, b, p, u formatted accurately without x-t header
 * - 3-parameter and 5-parameter overloads maintain 100% binary/source compatibility
 * - Batch updates with items array and root t token
 * - Non-Cancellable coroutines context protection during fire-and-forget sync
 * - REST GET /history retrieval and 2D matrix decoding
 * - RFC 6265bis Host-only cookie isolation for www2.evcs.vn
 * - Integrity token retention on StationDetailTelemetrySnapshot
 * - Comprehensive HTTP error handling (403, 500, malformed JSON)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EvcsTelemetryProtocolDataSourceAndRepositoryTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var testSessionManager: SessionManager
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var dataSource: EvcsTelemetryDataSource
    private lateinit var repository: EvcsTelemetryRepository
    private lateinit var baseUrl: String
    private lateinit var socketBaseUrl: String

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        val storage = InMemorySessionStorage().apply {
            putString(SessionManager.KEY_DEVICE_ID, "uuid-hardware-device-123")
        }
        testSessionManager = SessionManager(storage).apply {
            phpSessionId = "phpsess_456"
            authCookie = "auth_cookie_789"
            hostTpCookie = "host_tp_secret_cookie"
        }

        baseUrl = mockWebServer.url("/").toString().removeSuffix("/")
        socketBaseUrl = baseUrl

        dataSource = EvcsTelemetryDataSource(
            sessionManager = testSessionManager,
            client = okHttpClient,
            baseUrl = baseUrl,
            socketBaseUrl = socketBaseUrl,
            ioDispatcher = testDispatcher
        )

        repository = EvcsTelemetryRepository(
            dataSource = dataSource,
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    // =========================================================================
    // 1. Modern Telemetry Update (5-parameter overload)
    // =========================================================================

    @Test
    fun sendTelemetryUpdate_modern5Param_sendsTextPlainWithoutXtHeaderAndFormatsTokensAndPower() = runTest(testDispatcher) {
        mockWebServer.enqueue(MockResponse().setResponseCode(204))

        val result = dataSource.sendTelemetryUpdate(
            stationId = "C.BNI0311",
            apiToken = "1795527375.5-uH5Po3aepZ9xl37I8o8qdaiXPhFyhwlgKvrykezSA",
            totalBusy = 6,
            chargingKw = mapOf(60 to 2, 120 to 4),
            uToken = "1795527076.HQSPQdAzqnnfFI98wkP6KlYyt5_iGvdN8qVC-vH4Pws"
        )

        assertTrue("sendTelemetryUpdate should return success on 204", result.isSuccess)

        val recorded = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull("Expected HTTP request on MockWebServer", recorded)
        assertEquals("POST", recorded!!.method)
        assertEquals("/update", recorded.path)

        // Strict protocol check: Content-Type must be text/plain
        val contentType = recorded.getHeader("Content-Type")
        assertNotNull(contentType)
        assertTrue("Content-Type must start with text/plain, got: $contentType", contentType!!.startsWith("text/plain"))

        // Strict protocol check: NO x-t header allowed on /update
        assertNull("x-t header must NOT be sent on modernized /update", recorded.getHeader("x-t"))

        // Browser headers matching real-world curl capture
        assertEquals(EvcsApiClient.USER_AGENT_BROWSER, recorded.getHeader("User-Agent"))
        assertEquals(baseUrl, recorded.getHeader("Origin"))
        assertEquals("$baseUrl/", recorded.getHeader("Referer"))

        // Body format validation: {"t":"...","a":"...","b":...,"p":{"60":2,"120":4},"u":"..."}
        val expectedPayload = """{"t":"1795527375.5-uH5Po3aepZ9xl37I8o8qdaiXPhFyhwlgKvrykezSA","a":"C.BNI0311","b":6,"p":{"60":2,"120":4},"u":"1795527076.HQSPQdAzqnnfFI98wkP6KlYyt5_iGvdN8qVC-vH4Pws"}"""
        assertEquals(expectedPayload, recorded.body.readUtf8())
    }

    // =========================================================================
    // 2. Legacy 3-parameter Overload Compatibility
    // =========================================================================

    @Test
    fun sendTelemetryUpdate_legacy3Param_formatsEmptyPowerAndNullUToken() = runTest(testDispatcher) {
        mockWebServer.enqueue(MockResponse().setResponseCode(204))

        val result = dataSource.sendTelemetryUpdate(
            stationId = "st_legacy_999",
            apiToken = "token_abc_123",
            totalBusy = 3
        )

        assertTrue(result.isSuccess)

        val recorded = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(recorded)
        assertEquals("POST", recorded!!.method)
        assertEquals("/update", recorded.path)
        assertNull(recorded.getHeader("x-t"))

        val contentType = recorded.getHeader("Content-Type")
        assertNotNull(contentType)
        assertTrue(contentType!!.startsWith("text/plain"))

        // When 3-parameter overload is called, p is {} and u is ""
        val expectedPayload = """{"t":"token_abc_123","a":"st_legacy_999","b":3,"p":{},"u":""}"""
        assertEquals(expectedPayload, recorded.body.readUtf8())
    }

    // =========================================================================
    // 3. Batch Telemetry Update
    // =========================================================================

    @Test
    fun sendBatchTelemetryUpdate_sendsBatchArrayPayloadWithRootToken() = runTest(testDispatcher) {
        mockWebServer.enqueue(MockResponse().setResponseCode(204))

        val items = listOf(
            TelemetryBatchItem(
                a = "C.BNI0311",
                b = 5,
                p = mapOf("60" to 2, "120" to 3),
                u = "u_token_1"
            ),
            TelemetryBatchItem(
                a = "C.BNI12830",
                b = 1,
                p = mapOf("30" to 1),
                u = "u_token_2"
            ),
            TelemetryBatchItem(
                a = "C.BNI10054",
                b = 0,
                p = emptyMap(),
                u = ""
            )
        )

        val result = dataSource.sendBatchTelemetryUpdate(
            apiToken = "1795527350.XbmJs974Macy6GbD_FCgBO2puie7p0BCz4ZYGKiI1D4",
            items = items
        )

        assertTrue(result.isSuccess)

        val recorded = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(recorded)
        assertEquals("POST", recorded!!.method)
        assertEquals("/update", recorded.path)
        assertNull(recorded.getHeader("x-t"))

        val contentType = recorded.getHeader("Content-Type")
        assertNotNull(contentType)
        assertTrue(contentType!!.startsWith("text/plain"))

        val expectedPayload = """{"t":"1795527350.XbmJs974Macy6GbD_FCgBO2puie7p0BCz4ZYGKiI1D4","items":[{"a":"C.BNI0311","b":5,"p":{"60":2,"120":3},"u":"u_token_1"},{"a":"C.BNI12830","b":1,"p":{"30":1},"u":"u_token_2"},{"a":"C.BNI10054","b":0,"p":{},"u":""}]}"""
        assertEquals(expectedPayload, recorded.body.readUtf8())
    }

    // =========================================================================
    // 4. Fire-and-Forget Non-Cancellable Protection & HTTP Failures
    // =========================================================================

    @Test
    fun sendTelemetryUpdate_http500_returnsFailureResult() = runTest(testDispatcher) {
        mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        val result = dataSource.sendTelemetryUpdate(
            stationId = "C.ERR01",
            apiToken = "tok_err",
            totalBusy = 1
        )

        assertTrue("Expected failure on HTTP 500", result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue("Exception should be IOException", ex is IOException)
        assertTrue("Message should mention HTTP 500", ex?.message?.contains("500") == true)
    }

    @Test
    fun sendTelemetryUpdate_nonCancellableContext_completesWhenParentScopeIsCancelled() = runTest(testDispatcher) {
        mockWebServer.enqueue(MockResponse().setResponseCode(204))

        val job = launch {
            dataSource.sendTelemetryUpdate("st_cancel_test", "token_nc", 4)
        }

        testScheduler.runCurrent()
        job.cancelAndJoin()

        // Because of NonCancellable inside withContext, the update completes and reaches server
        val recorded = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull("Request should have been dispatched to server despite parent cancellation", recorded)
        assertEquals("/update", recorded!!.path)
        assertEquals("""{"t":"token_nc","a":"st_cancel_test","b":4,"p":{},"u":""}""", recorded.body.readUtf8())
    }

    // =========================================================================
    // 5. REST 24h History Retrieval (GET /history)
    // =========================================================================

    @Test
    fun fetch24hHistory_success_sendsCorrectHeadersAndParsesPoints() = runTest(testDispatcher) {
        val historyJson = """
            [
              [1795440708099, 6],
              [1795442395858, 5],
              [1795444918075, 3],
              [1795460602065, 0]
            ]
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(historyJson))

        val result = dataSource.fetch24hHistory(
            stationId = "C.BNI0311",
            apiToken = "1795527375.5-uH5Po3aepZ9xl37I8o8qdaiXPhFyhwlgKvrykezSA"
        )

        assertTrue("Expected successful 24h history fetch", result.isSuccess)
        val points = result.getOrThrow()
        assertEquals(4, points.size)
        assertEquals(1795440708099L to 6, points[0])
        assertEquals(1795442395858L to 5, points[1])
        assertEquals(1795444918075L to 3, points[2])
        assertEquals(1795460602065L to 0, points[3])

        val recorded = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(recorded)
        assertEquals("GET", recorded!!.method)
        assertEquals(
            "/history?sid=C.BNI0311&t=1795527375.5-uH5Po3aepZ9xl37I8o8qdaiXPhFyhwlgKvrykezSA",
            recorded.path
        )
        assertEquals(EvcsApiClient.USER_AGENT_BROWSER, recorded.getHeader("User-Agent"))
        assertEquals(baseUrl, recorded.getHeader("Origin"))
        assertEquals("$baseUrl/", recorded.getHeader("Referer"))

        // RFC 6265bis Host-only isolation: Cookie header is NOT sent to www2.evcs.vn
        assertNull("Cookie header must not be sent on history GET request", recorded.getHeader("Cookie"))
    }

    @Test
    fun fetch24hHistory_http403_returnsFailureResult() = runTest(testDispatcher) {
        mockWebServer.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))

        val result = dataSource.fetch24hHistory("C.BNI0311", "expired_token")
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is IOException)
        assertTrue(ex?.message?.contains("HTTP 403") == true)
    }

    @Test
    fun fetch24hHistory_malformedJson_returnsFailureResult() = runTest(testDispatcher) {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("<html>Not Found</html>"))

        val result = dataSource.fetch24hHistory("C.BNI0311", "token")
        assertTrue(result.isFailure)
    }

    @Test
    fun fetch24hHistory_negativeCounts_clampedToZero() = runTest(testDispatcher) {
        val historyJson = """[[1795440708099, -5], [1795442395858, 2]]"""
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(historyJson))

        val result = dataSource.fetch24hHistory("C.BNI0311", "token")
        assertTrue(result.isSuccess)
        val points = result.getOrThrow()
        assertEquals(2, points.size)
        assertEquals(1795440708099L to 0, points[0])
        assertEquals(1795442395858L to 2, points[1])
    }

    // =========================================================================
    // 6. EvcsTelemetryRepository Delegation & Integration
    // =========================================================================

    @Test
    fun repository_sendTelemetryUpdate_3Param_delegatesToDataSource() = runTest(testDispatcher) {
        mockWebServer.enqueue(MockResponse().setResponseCode(204))

        val result = repository.sendTelemetryUpdate("st_repo_3", "token_repo", 4)
        assertTrue(result.isSuccess)

        val recorded = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(recorded)
        assertEquals("/update", recorded!!.path)
        assertEquals("""{"t":"token_repo","a":"st_repo_3","b":4,"p":{},"u":""}""", recorded.body.readUtf8())
    }

    @Test
    fun repository_sendTelemetryUpdate_5Param_delegatesToDataSource() = runTest(testDispatcher) {
        mockWebServer.enqueue(MockResponse().setResponseCode(204))

        val result = repository.sendTelemetryUpdate(
            stationId = "st_repo_5",
            apiToken = "token_5p",
            totalBusy = 7,
            chargingKw = mapOf(30 to 1, 60 to 6),
            uToken = "u_sig_repo"
        )
        assertTrue(result.isSuccess)

        val recorded = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(recorded)
        assertEquals("/update", recorded!!.path)
        assertEquals("""{"t":"token_5p","a":"st_repo_5","b":7,"p":{"30":1,"60":6},"u":"u_sig_repo"}""", recorded.body.readUtf8())
    }

    @Test
    fun repository_sendBatchTelemetryUpdate_delegatesToDataSource() = runTest(testDispatcher) {
        mockWebServer.enqueue(MockResponse().setResponseCode(204))

        val items = listOf(TelemetryBatchItem("st_b1", 2, mapOf("60" to 2), "u1"))
        val result = repository.sendBatchTelemetryUpdate("batch_token_repo", items)
        assertTrue(result.isSuccess)

        val recorded = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(recorded)
        assertEquals("/update", recorded!!.path)
        assertEquals("""{"t":"batch_token_repo","items":[{"a":"st_b1","b":2,"p":{"60":2},"u":"u1"}]}""", recorded.body.readUtf8())
    }

    @Test
    fun repository_fetch24hHistory_delegatesToDataSource() = runTest(testDispatcher) {
        val historyJson = """[[1795440708099, 4]]"""
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(historyJson))

        val result = repository.fetch24hHistory("st_repo_hist", "tok_hist")
        assertTrue(result.isSuccess)
        val points = result.getOrThrow()
        assertEquals(1, points.size)
        assertEquals(1795440708099L to 4, points[0])
    }

    @Test
    fun repository_fetchStationTelemetrySnapshot_retainsUToken() = runTest(testDispatcher) {
        // Response 1: POST /{slug}.html returns access tokens
        val tokenResponse = """{"chargeToken":"ctok_123","apiToken":"atok_456"}"""
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(tokenResponse))

        // Response 2: POST /charging returns charging telemetry with uToken
        val chargingResponse = """{"chargingKw":{"60":2,"120":1},"u":"u_integrity_secure_token_999"}"""
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(chargingResponse))

        val station = Station(
            id = "st_snapshot_01",
            name = "Trạm Test Phường Mão Điền",
            address = "Bắc Ninh",
            latitude = 21.05,
            longitude = 106.12,
            summary = "",
            connectors = "60kW x 4, 120kW x 2",
            depotStatus = "Normal",
            evse = "VinFast",
            powers = listOf(
                PowerPort(typeWatts = 60000L, label = "60kW", availablePlugs = 4, totalPlugs = 4),
                PowerPort(typeWatts = 120000L, label = "120kW", availablePlugs = 2, totalPlugs = 2)
            ),
            totalPlugs = 6
        )

        val result = repository.fetchStationTelemetrySnapshot(station)
        assertTrue("fetchStationTelemetrySnapshot should succeed", result.isSuccess)

        val snapshot = result.getOrThrow()
        assertEquals("atok_456", snapshot.tokens.apiToken)
        assertEquals("ctok_123", snapshot.tokens.chargeToken)

        // Verify uToken is retained on both snapshot and inner telemetry
        assertEquals("u_integrity_secure_token_999", snapshot.uToken)
        assertEquals("u_integrity_secure_token_999", snapshot.telemetry.uToken)

        // Verify port derivation
        val kw60Port = snapshot.portStatuses.find { it.kw == 60 }
        assertNotNull(kw60Port)
        assertEquals(2, kw60Port!!.busyCount)
        assertEquals(2, kw60Port.availablePorts)
        assertEquals(4, kw60Port.totalPorts)

        val kw120Port = snapshot.portStatuses.find { it.kw == 120 }
        assertNotNull(kw120Port)
        assertEquals(1, kw120Port!!.busyCount)
        assertEquals(1, kw120Port.availablePorts)
        assertEquals(2, kw120Port.totalPorts)
    }
}
