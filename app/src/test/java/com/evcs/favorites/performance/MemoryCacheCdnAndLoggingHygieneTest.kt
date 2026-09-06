package com.evcs.favorites.performance

import com.evcs.favorites.EvPlusApplication
import com.evcs.favorites.data.logging.AppDebugLogger
import com.evcs.favorites.data.logging.DebugLogLevel
import com.evcs.favorites.data.logging.DebugLogTag
import com.evcs.favorites.data.logging.DebugLoggingInterceptor
import com.evcs.favorites.util.VinFastCdnUrlDecoder
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Phase 05 Verification Test:
 * Memory Caching, CDN Decoding & Logging Hygiene (PERF-007, PERF-010, PERF-011).
 *
 * Verifies:
 * 1. CDN Cache Hit: First call to [VinFastCdnUrlDecoder.decode] decodes the token;
 *    second call returns the exact cached string with zero additional decoding passes.
 * 2. Cache Eviction: Exceeding the 500-item capacity evicts the least-recently-used
 *    item without memory leaks or unbounded growth.
 * 3. Release Logging Bypass: When enabled = false (simulating release build),
 *    [DebugLoggingInterceptor] passes through requests without peeking or modifying [AppDebugLogger],
 *    and [AppDebugLogger] ignores entries when isEnabled = false.
 * 4. Coil Memory Config: [EvPlusApplication] configures Coil MemoryCache to 15% (0.15) of JVM heap.
 */
class MemoryCacheCdnAndLoggingHygieneTest {

    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setUp() {
        VinFastCdnUrlDecoder.clearCache()
        AppDebugLogger.clear()
        AppDebugLogger.isEnabled = true
        mockWebServer = MockWebServer()
        mockWebServer.start()
    }

    @After
    fun tearDown() {
        try {
            mockWebServer.shutdown()
        } catch (_: Exception) {
        }
        VinFastCdnUrlDecoder.clearCache()
        AppDebugLogger.clear()
        AppDebugLogger.isEnabled = true
    }

    private fun createDoubleBase64Token(targetUrl: String): String {
        val urlEncoded = URLEncoder.encode(targetUrl, StandardCharsets.UTF_8.name())
        val pass1Bytes = Base64.getEncoder().encode(urlEncoded.toByteArray(StandardCharsets.UTF_8))
        val pass1Str = String(pass1Bytes, StandardCharsets.UTF_8)
        val tokenBytes = Base64.getEncoder().encode(pass1Str.toByteArray(StandardCharsets.UTF_8))
        return String(tokenBytes, StandardCharsets.UTF_8)
    }

    private fun resolveProjectFile(relativeSubpath: String): File {
        val candidates = listOf(
            relativeSubpath,
            "app/$relativeSubpath",
            "../$relativeSubpath",
            "../app/$relativeSubpath"
        )
        for (cand in candidates) {
            val f = File(cand)
            if (f.exists()) return f
        }
        val userDir = System.getProperty("user.dir") ?: "."
        for (cand in candidates) {
            val f = File(userDir, cand)
            if (f.exists()) return f
        }
        throw AssertionError("Could not find file: $relativeSubpath in candidate paths from user.dir=$userDir")
    }

    @Test
    fun testPhase05_MemoryCacheCdnDecodingAndLoggingHygiene() {
        // =====================================================================
        // Criterion 1: CDN Cache Hit
        // =====================================================================
        assertEquals("Cache should start empty", 0, VinFastCdnUrlDecoder.getCacheSize())
        assertEquals(0L, VinFastCdnUrlDecoder.cacheHits)
        assertEquals(0L, VinFastCdnUrlDecoder.cacheMisses)

        val cdnTarget = "https://cpo-prod-s3.vinfastauto.com/charging-station-car/depot/images/landmark_81.jpg"
        val token = createDoubleBase64Token(cdnTarget)
        val fullUrl = "https://evcs.vn/media?file=$token"

        // First decode: Cache miss -> performs multi-pass Base64 decode and populates LRU cache
        val firstResult = VinFastCdnUrlDecoder.decode(fullUrl)
        assertEquals(cdnTarget, firstResult)
        assertEquals(1, VinFastCdnUrlDecoder.getCacheSize())
        assertEquals(1L, VinFastCdnUrlDecoder.cacheMisses)
        assertEquals(0L, VinFastCdnUrlDecoder.cacheHits)
        assertEquals(cdnTarget, VinFastCdnUrlDecoder.getCached(token))

        // Second decode with exact same URL: Cache hit -> returns cached string in O(1)
        val secondResult = VinFastCdnUrlDecoder.decode(fullUrl)
        assertEquals(cdnTarget, secondResult)
        assertEquals("Cache size should remain 1", 1, VinFastCdnUrlDecoder.getCacheSize())
        assertEquals("Cache misses must NOT increase", 1L, VinFastCdnUrlDecoder.cacheMisses)
        assertEquals("Cache hits must increment to 1", 1L, VinFastCdnUrlDecoder.cacheHits)

        // Third decode with raw token directly: Also hits cache because token is the normalized key
        val thirdResult = VinFastCdnUrlDecoder.decode(token)
        assertEquals(cdnTarget, thirdResult)
        assertEquals("Cache misses must NOT increase", 1L, VinFastCdnUrlDecoder.cacheMisses)
        assertEquals("Cache hits must increment to 2", 2L, VinFastCdnUrlDecoder.cacheHits)

        // Direct CDN URLs bypass decode and cache entirely
        val directCdn = "https://cpo-prod-s3.vinfastauto.com/images/direct.png"
        assertEquals(directCdn, VinFastCdnUrlDecoder.decode(directCdn))
        assertEquals("Direct CDN must not alter cache size", 1, VinFastCdnUrlDecoder.getCacheSize())

        // =====================================================================
        // Criterion 2: Cache Eviction (500 capacity LRU)
        // =====================================================================
        VinFastCdnUrlDecoder.clearCache()
        assertEquals(0, VinFastCdnUrlDecoder.getCacheSize())
        assertEquals(VinFastCdnUrlDecoder.MAX_CACHE_CAPACITY, 500)

        // Populate exactly 500 entries (0..499)
        for (i in 0 until 500) {
            VinFastCdnUrlDecoder.putCached("token_$i", "https://cpo-prod-s3.vinfastauto.com/img_$i.jpg")
        }
        assertEquals(500, VinFastCdnUrlDecoder.getCacheSize())
        assertNotNull("token_0 should be present initially", VinFastCdnUrlDecoder.getCached("token_0"))
        assertNotNull("token_499 should be present", VinFastCdnUrlDecoder.getCached("token_499"))

        // Access token_0 so it becomes most recently used, leaving token_1 as the least recently used
        assertNotNull(VinFastCdnUrlDecoder.getCached("token_0"))

        // Insert 501st entry -> should evict token_1 (the LRU entry), NOT token_0
        VinFastCdnUrlDecoder.putCached("token_500", "https://cpo-prod-s3.vinfastauto.com/img_500.jpg")
        assertEquals(500, VinFastCdnUrlDecoder.getCacheSize())
        assertNotNull("token_0 should still be retained due to recent access", VinFastCdnUrlDecoder.getCached("token_0"))
        assertNull("token_1 should have been evicted as the LRU entry", VinFastCdnUrlDecoder.getCached("token_1"))
        assertNotNull("token_500 should be present", VinFastCdnUrlDecoder.getCached("token_500"))

        // Insert another batch of 10 items (501..510)
        for (i in 501..510) {
            VinFastCdnUrlDecoder.putCached("token_$i", "https://cpo-prod-s3.vinfastauto.com/img_$i.jpg")
        }
        assertEquals("Capacity must never exceed 500", 500, VinFastCdnUrlDecoder.getCacheSize())

        // =====================================================================
        // Criterion 3: Release Logging Bypass
        // =====================================================================
        // Case A: DebugLoggingInterceptor with enabled = false (simulating release build)
        val releaseInterceptor = DebugLoggingInterceptor(enabled = false)
        val client = OkHttpClient.Builder()
            .addInterceptor(releaseInterceptor)
            .build()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"status":"success","items":[1,2,3]}""")
        )

        val requestUrl = mockWebServer.url("/search?q=hanoi").toString()
        val request = Request.Builder().url(requestUrl).get().build()
        val response = client.newCall(request).execute()

        assertEquals(200, response.code)
        assertEquals("""{"status":"success","items":[1,2,3]}""", response.body?.string())
        assertEquals(
            "Release interceptor must bypass AppDebugLogger entirely",
            0,
            AppDebugLogger.getLogs().size
        )

        // Case B: AppDebugLogger with isEnabled = false
        AppDebugLogger.isEnabled = false
        AppDebugLogger.log(
            tag = DebugLogTag.NETWORK,
            level = DebugLogLevel.INFO,
            message = "This message must be completely ignored in release mode"
        )
        assertEquals(
            "Disabled AppDebugLogger must not retain any entries",
            0,
            AppDebugLogger.getLogs().size
        )
        assertEquals(
            "logsFlow must remain empty when logger is disabled",
            0,
            AppDebugLogger.logsFlow.value.size
        )

        // Re-enable and verify logging works again
        AppDebugLogger.isEnabled = true
        AppDebugLogger.log(
            tag = DebugLogTag.NETWORK,
            level = DebugLogLevel.INFO,
            message = "Debug log entry recorded"
        )
        assertEquals(1, AppDebugLogger.getLogs().size)
        assertEquals("Debug log entry recorded", AppDebugLogger.getLogs().first().message)

        // =====================================================================
        // Criterion 4: Coil Memory Cache Configuration (15% JVM Heap)
        // =====================================================================
        assertEquals(
            "Coil memory cache percentage constant must be 0.15 (15%)",
            0.15,
            EvPlusApplication.COIL_MEMORY_CACHE_PERCENT,
            0.0001
        )

        val app = EvPlusApplication()
        val imageLoader = app.newImageLoader()
        assertNotNull("ImageLoader must be created successfully", imageLoader)
        assertNotNull("MemoryCache must be configured on ImageLoader", imageLoader.memoryCache)
        assertNotNull("DiskCache must be configured on ImageLoader", imageLoader.diskCache)

        // Verify source code reflects 15% heap setting
        val appSourceFile = resolveProjectFile("src/main/java/com/evcs/favorites/EvPlusApplication.kt")
        assertTrue("EvPlusApplication.kt must exist", appSourceFile.exists())
        val sourceText = appSourceFile.readText()
        assertTrue(
            "EvPlusApplication must configure maxSizePercent with 15% heap",
            sourceText.contains("COIL_MEMORY_CACHE_PERCENT") && sourceText.contains("0.15")
        )
        assertTrue(
            "EvPlusApplication must NOT use old 0.25 (25%) memory cache setting",
            !sourceText.contains("maxSizePercent(0.25)")
        )
    }
}
