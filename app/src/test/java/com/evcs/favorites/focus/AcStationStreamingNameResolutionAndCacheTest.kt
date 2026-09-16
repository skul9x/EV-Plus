package com.evcs.favorites.focus

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Comprehensive verification test suite for Phase 02:
 * Streaming HTML Resolution, TTL Caching & Timeout Guard.
 *
 * Verifies:
 * 1. Streaming line parser terminates reading immediately upon finding `<title>` or `</head>`
 *    without consuming or buffering the remaining HTML body.
 * 2. In-memory TTL cache (60 min) returns canonical titles immediately on subsequent calls (0 HTTP requests).
 * 3. Cache bypass (`bypassCache = true`), cache clearing (`clearCache` / `clearHtmlCache`),
 *    and TTL expiration force fresh network retrieval.
 * 4. Tightened timeout guard (1.8s) trips gracefully to `"VinFast - $fallbackAddress"` on unresponsive servers.
 * 5. Extraction fidelity: Sanitization, multi-line titles, meta titles, and HTTP error fallback handling.
 */
class AcStationStreamingNameResolutionAndCacheTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var okHttpClient: OkHttpClient
    private val bytesReadTracker = AtomicLong(0L)

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        bytesReadTracker.set(0L)

        // Interceptor that tracks the exact number of bytes pulled through the response body stream
        val streamTrackingInterceptor = Interceptor { chain ->
            val response = chain.proceed(chain.request())
            val originalBody = response.body ?: return@Interceptor response
            val originalSource = originalBody.source()

            val countingSource = object : ForwardingSource(originalSource) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    val bytesRead = super.read(sink, byteCount)
                    if (bytesRead > 0) {
                        bytesReadTracker.addAndGet(bytesRead)
                    }
                    return bytesRead
                }
            }

            response.newBuilder()
                .body(countingSource.buffer().asResponseBody(originalBody.contentType(), originalBody.contentLength()))
                .build()
        }

        okHttpClient = OkHttpClient.Builder()
            .addNetworkInterceptor(streamTrackingInterceptor)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    // =========================================================================
    // 1. Streaming Reader Early Termination Tests
    // =========================================================================

    @Test
    fun testStreamingReader_extractsTitleWithoutConsumingRestOfHtmlBody() = runTest {
        val resolver = EvcsStationNameResolver(
            httpClient = okHttpClient,
            htmlBaseUrl = mockWebServer.url("/").toString()
        )

        // Build HTML with small head containing title, followed by 100KB of trailing body content
        val largePadding = "A".repeat(100 * 1024) // 100 KB
        val htmlContent = buildString {
            append("<!DOCTYPE html>\n")
            append("<html>\n")
            append("<head>\n")
            append("    <title>Trạm sạc VinFast - Landmark 81 Central Park - Trạm Sạc EV</title>\n")
            append("</head>\n")
            append("<body>\n")
            append("    <div class=\"large-payload\">$largePadding</div>\n")
            append("</body>\n")
            append("</html>\n")
        }

        val totalBytes = htmlContent.toByteArray(Charsets.UTF_8).size
        assertTrue("Total HTML payload should be > 100KB", totalBytes > 100_000)

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(htmlContent))

        val resolvedName = resolver.resolveStationNameFromHtml(
            locationId = "C.LM81",
            fallbackAddress = "720A Dien Bien Phu"
        )

        assertEquals("Landmark 81 Central Park", resolvedName)

        val bytesConsumed = bytesReadTracker.get()
        // The reader must have terminated early as soon as the <title> line was read!
        // A single buffer chunk (<= 8192 bytes) is read, dramatically less than the 100KB total body.
        assertTrue(
            "Streaming reader must not consume the full body (read: $bytesConsumed bytes, total: $totalBytes bytes)",
            bytesConsumed <= 8192 && bytesConsumed < totalBytes
        )
    }

    @Test
    fun testStreamingReader_terminatesEarlyOnHeadClosingTagWhenTitleIsMissing() = runTest {
        val resolver = EvcsStationNameResolver(
            httpClient = okHttpClient,
            htmlBaseUrl = mockWebServer.url("/").toString()
        )

        val largePadding = "X".repeat(50 * 1024) // 50 KB
        val htmlWithoutTitle = buildString {
            append("<!DOCTYPE html>\n")
            append("<html>\n")
            append("<head>\n")
            append("    <meta charset=\"utf-8\">\n")
            append("</head>\n")
            append("<body>\n")
            append("    <div>$largePadding</div>\n")
            append("</body>\n")
            append("</html>\n")
        }

        val totalBytes = htmlWithoutTitle.toByteArray(Charsets.UTF_8).size
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(htmlWithoutTitle))

        val resolvedName = resolver.resolveStationNameFromHtml(
            locationId = "C.NOTITLE",
            fallbackAddress = "Vo Van Kiet, Quan 1"
        )

        // Falls back cleanly to VinFast - fallbackAddress
        assertEquals("VinFast - Vo Van Kiet, Quan 1", resolvedName)

        val bytesConsumed = bytesReadTracker.get()
        assertTrue(
            "Reader must stop at </head> without reading 50KB body (consumed: $bytesConsumed, total: $totalBytes)",
            bytesConsumed <= 8192 && bytesConsumed < totalBytes
        )
    }

    // =========================================================================
    // 2. In-Memory TTL Cache Hit Tests (0ms, 0 HTTP Requests)
    // =========================================================================

    @Test
    fun testTtlCache_subsequentLookupsReturnCachedTitleWithZeroHttpRequests() = runTest {
        val resolver = EvcsStationNameResolver(
            httpClient = okHttpClient,
            htmlBaseUrl = mockWebServer.url("/").toString()
        )

        val html = """
            <!DOCTYPE html>
            <html>
            <head><title>Trạm sạc VinFast - TƯ NHÂN Nguyễn Văn Đức - Trạm Sạc EV</title></head>
            <body></body>
            </html>
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(html))

        // First call: Triggers network request and populates TTL cache
        val firstResult = resolver.resolveStationNameFromHtml(
            locationId = "C.BNI11197",
            fallbackAddress = "29 Binh Than"
        )
        assertEquals("TƯ NHÂN Nguyễn Văn Đức", firstResult)
        assertEquals("Must have dispatched exactly 1 HTTP request", 1, mockWebServer.requestCount)

        // Second call with identical ID: Must hit in-memory TTL cache
        val secondResult = resolver.resolveStationNameFromHtml(
            locationId = "C.BNI11197",
            fallbackAddress = "Different address"
        )
        assertEquals("TƯ NHÂN Nguyễn Văn Đức", secondResult)
        assertEquals("Request count must still be 1 (0 new HTTP requests)", 1, mockWebServer.requestCount)

        // Third call with normalized slug format (c.bni11197 / tram-sac-vinfast-c.bni11197.html)
        val thirdResult = resolver.resolveStationNameFromHtml(
            locationId = "tram-sac-vinfast-c.bni11197.html",
            fallbackAddress = "Yet another address"
        )
        assertEquals("TƯ NHÂN Nguyễn Văn Đức", thirdResult)
        assertEquals("Request count must still be 1 (cache hit on normalized ID)", 1, mockWebServer.requestCount)
    }

    // =========================================================================
    // 3. Cache Invalidation and Bypass Tests
    // =========================================================================

    @Test
    fun testCacheBypassAndInvalidation_forcesFreshNetworkRetrieval() = runTest {
        val resolver = EvcsStationNameResolver(
            httpClient = okHttpClient,
            htmlBaseUrl = mockWebServer.url("/").toString()
        )

        val html1 = "<html><head><title>Trạm sạc VinFast - Station Name 1 - Trạm Sạc EV</title></head></html>"
        val html2 = "<html><head><title>Trạm sạc VinFast - Station Name 2 - Trạm Sạc EV</title></head></html>"
        val html3 = "<html><head><title>Trạm sạc VinFast - Station Name 3 - Trạm Sạc EV</title></head></html>"
        val html4 = "<html><head><title>Trạm sạc VinFast - Station Name 4 - Trạm Sạc EV</title></head></html>"

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(html1))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(html2))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(html3))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(html4))

        // 1. Initial resolution -> populates cache
        val res1 = resolver.resolveStationNameFromHtml("C.LOC01", "Address")
        assertEquals("Station Name 1", res1)
        assertEquals(1, mockWebServer.requestCount)

        // 2. bypassCache == true forces fresh network call
        val res2 = resolver.resolveStationNameFromHtml("C.LOC01", "Address", bypassCache = true)
        assertEquals("Station Name 2", res2)
        assertEquals("bypassCache must trigger a new network call", 2, mockWebServer.requestCount)

        // 3. clearCache() invalidates in-memory TTL cache
        resolver.clearCache()
        val res3 = resolver.resolveStationNameFromHtml("C.LOC01", "Address")
        assertEquals("Station Name 3", res3)
        assertEquals("clearCache must force next request over network", 3, mockWebServer.requestCount)

        // 4. TTL expiration (simulate item older than 60 minutes)
        val expiredTimestamp = System.currentTimeMillis() - (EvcsStationNameResolver.HTML_CACHE_TTL_MS + 1000L)
        resolver.putCachedHtmlTitle("c.loc01", "Expired Station Name", timestampMs = expiredTimestamp)

        // Calling without bypassCache should detect expiration, evict, and fetch fresh from network
        val res4 = resolver.resolveStationNameFromHtml("C.LOC01", "Address")
        assertEquals("Station Name 4", res4)
        assertEquals("TTL expiration must trigger fresh network fetch", 4, mockWebServer.requestCount)
    }

    // =========================================================================
    // 4. Tightened Timeout Guard (1.8s) Tests
    // =========================================================================

    @Test
    fun testTimeoutGuard_tripsGracefullyToFallbackAddressWithin1800ms() = runTest {
        val resolver = EvcsStationNameResolver(
            httpClient = okHttpClient,
            htmlBaseUrl = mockWebServer.url("/").toString()
        )

        // Enqueue response with 3.5s delay (exceeding 1.8s timeout guard)
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("<html><head><title>Trạm sạc VinFast - Too Late - Trạm Sạc EV</title></head></html>")
                .setBodyDelay(3500, TimeUnit.MILLISECONDS)
        )

        val startTime = System.currentTimeMillis()
        val fallbackResult = resolver.resolveStationNameFromHtml(
            locationId = "C.TIMEOUT",
            fallbackAddress = "To 5, Thi tran Dong Anh"
        )
        val elapsed = System.currentTimeMillis() - startTime

        assertEquals("VinFast - To 5, Thi tran Dong Anh", fallbackResult)
        // Should complete well before the 3500ms delay and within reasonable timeout window (~1800ms + buffer)
        assertTrue(
            "Timeout guard must trip around 1.8s without waiting full 3.5s (elapsed: ${elapsed}ms)",
            elapsed < 3000
        )
    }

    // =========================================================================
    // 5. Extraction Fidelity & Edge Cases
    // =========================================================================

    @Test
    fun testExtractionFidelity_multiLineTitleAndMetaTitle() = runTest {
        val resolver = EvcsStationNameResolver(
            httpClient = okHttpClient,
            htmlBaseUrl = mockWebServer.url("/").toString()
        )

        // Case A: Multi-line <title> tag
        val multiLineHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>
                    Trạm sạc VinFast - Vincom Mega Mall Thảo Điền - Trạm Sạc EV
                </title>
            </head>
            <body></body>
            </html>
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(multiLineHtml))

        val name1 = resolver.resolveStationNameFromHtml("c.td01", "159 Xa Lo Ha Noi")
        assertEquals("Vincom Mega Mall Thảo Điền", name1)

        // Case B: <meta name="title"> tag
        val metaHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="title" content="Trạm sạc VinFast - Showroom Lê Văn Lương - Trạm Sạc EV">
            </head>
            <body></body>
            </html>
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(metaHtml))

        val name2 = resolver.resolveStationNameFromHtml("c.lvl02", "Le Van Luong, Ha Noi")
        assertEquals("Showroom Lê Văn Lương", name2)

        // Case C: HTTP 404 / 500 error fallback
        mockWebServer.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))
        val name404 = resolver.resolveStationNameFromHtml("c.err404", "Thành phố Bắc Ninh")
        assertEquals("VinFast - Thành phố Bắc Ninh", name404)

        mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody("Server Error"))
        val name500 = resolver.resolveStationNameFromHtml("c.err500", "Quan 7, TP.HCM")
        assertEquals("VinFast - Quan 7, TP.HCM", name500)
    }
}
