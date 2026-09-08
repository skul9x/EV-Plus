package com.evcs.favorites.data.logging

import com.evcs.favorites.data.auth.SessionStorage
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class AppDebugLoggerPipelineTest {

    private lateinit var mockWebServer: MockWebServer

    private class InMemorySessionStorage : SessionStorage {
        private val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String?) {
            if (value != null) map[key] = value else map.remove(key)
        }
        override fun remove(key: String) { map.remove(key) }
        override fun clear() { map.clear() }
    }

    @Before
    fun setUp() {
        AppDebugLogger.clear()
        mockWebServer = MockWebServer()
        mockWebServer.start()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        AppDebugLogger.clear()
    }

    @Test
    fun testCircularBufferFifoEvictionAndClear() {
        AppDebugLogger.clear()
        assertEquals(0, AppDebugLogger.getLogs().size)
        assertEquals(0, AppDebugLogger.logsFlow.value.size)

        // Insert 510 entries into the 500-capacity buffer
        for (i in 1..510) {
            AppDebugLogger.log(
                tag = DebugLogTag.NETWORK,
                level = DebugLogLevel.INFO,
                message = "Test entry $i"
            )
        }

        AppDebugLogger.flush()
        val logs = AppDebugLogger.getLogs()
        assertEquals(500, logs.size)
        assertEquals(500, AppDebugLogger.logsFlow.value.size)

        // The oldest 10 entries (1..10) should have been evicted
        assertEquals("Test entry 11", logs.first().message)
        assertEquals("Test entry 510", logs.last().message)

        // Clear buffer
        AppDebugLogger.clear()
        assertEquals(0, AppDebugLogger.getLogs().size)
        assertEquals(0, AppDebugLogger.logsFlow.value.size)
    }

    @Test
    fun testOkHttpInterceptorRecordsRequestAndPeeksBodySafely() {
        val client = OkHttpClient.Builder()
            .addInterceptor(DebugLoggingInterceptor())
            .build()

        val responseBodyJson = """{"status":"ok","count":42}"""
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(responseBodyJson)
        )

        val targetUrl = mockWebServer.url("/search?t=test").toString()
        val request = Request.Builder().url(targetUrl).get().build()

        val response = client.newCall(request).execute()
        // Stream must be unconsumed and readable
        val consumedBody = response.body?.string().orEmpty()
        assertEquals(responseBodyJson, consumedBody)

        val logs = AppDebugLogger.getLogs()
        assertEquals(1, logs.size)
        val log = logs.first()
        assertEquals(DebugLogTag.SEARCH, log.tag)
        assertEquals(DebugLogLevel.INFO, log.level)
        assertEquals(200, log.statusCode)
        assertEquals("GET", log.method)
        assertEquals(targetUrl, log.endpointUrl)
        assertTrue(log.latencyMs != null && log.latencyMs!! >= 0)
        assertEquals(responseBodyJson, log.responseSnippet)
    }

    @Test
    fun testFormattedLogTextExport() {
        AppDebugLogger.clear()
        assertEquals("Chưa có nhật ký hoạt động mạng.", AppDebugLogger.getFormattedLogText())

        AppDebugLogger.log(
            tag = DebugLogTag.ROUTING,
            level = DebugLogLevel.INFO,
            message = "OSRM Matrix calculation completed",
            endpointUrl = "https://router.project-osrm.org/table/v1/driving/...",
            method = "GET",
            statusCode = 200,
            latencyMs = 120,
            requestSnippet = "sources=0&annotations=duration,distance",
            responseSnippet = """{"code":"Ok"}"""
        )

        AppDebugLogger.log(
            tag = DebugLogTag.SEARCH,
            level = DebugLogLevel.SUCCESS,
            message = "Tìm thấy 5 trạm sạc gần đây",
            responseSnippet = """[{"id":"st_1"}]"""
        )

        val formatted = AppDebugLogger.getFormattedLogText()
        assertTrue(formatted.contains("=== EVCS DEBUG LOGS ==="))
        assertTrue(formatted.contains("Tổng số mục: 2"))
        assertTrue(formatted.contains("[INFO] [ROUTING]"))
        assertTrue(formatted.contains("https://router.project-osrm.org/table/v1/driving/..."))
        assertTrue(formatted.contains("[SUCCESS] [SEARCH]"))
        assertTrue(formatted.contains("Tìm thấy 5 trạm sạc gần đây"))
        assertTrue(formatted.contains("----------------------------------------"))
    }
}
