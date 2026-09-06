package com.evcs.favorites.performance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 06 Comprehensive Verification Test:
 * Baseline Profile & Benchmark Architecture (PERF-006).
 *
 * Verifies:
 * 1. baseline-prof.txt exists and contains valid ART rule syntax (HSPL, HSP, Lcom/evcs/..., etc.).
 * 2. The baseline profile contains entries for all critical user journeys:
 *    - MainActivity
 *    - NearbyScreen
 *    - FavoritesScreen
 *    - NativeStationDetailSheet
 *    - DistanceCalculator
 *    - Plus core pipeline entries (Compose runtime/UI, Coil, OkHttp, Coroutines, Serialization).
 * 3. Total active rule count exceeds 50 lines (confirming it is a comprehensive profile, not a stub).
 * 4. docs/benchmarks/macrobenchmark_guide.md exists and documents Cold Startup, Frame Timing / JankStats,
 *    and Baseline Profile generation automation commands.
 */
class BaselineProfileAndBenchmarkConfigTest {

    private fun resolveFile(vararg candidatePaths: String): File {
        for (path in candidatePaths) {
            val file = File(path)
            if (file.exists()) return file
        }
        val userDir = System.getProperty("user.dir") ?: "."
        for (path in candidatePaths) {
            val file = File(userDir, path)
            if (file.exists()) return file
        }
        throw AssertionError("Could not find any file from candidates: ${candidatePaths.joinToString()} (user.dir=$userDir)")
    }

    private fun loadBaselineProfile(): Pair<File, List<String>> {
        val file = resolveFile(
            "app/src/main/baseline-prof.txt",
            "src/main/baseline-prof.txt",
            "../app/src/main/baseline-prof.txt"
        )
        val lines = file.readLines()
        return file to lines
    }

    @Test
    fun testBaselineProfileExistsAndHasSufficientVolume() {
        val (file, lines) = loadBaselineProfile()
        assertTrue("baseline-prof.txt must exist", file.exists())

        val activeRules = lines.map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

        // Must exceed 50 rules (replaces the 7-line stub)
        assertTrue(
            "Baseline profile must contain more than 50 rules, but found: ${activeRules.size}",
            activeRules.size > 50
        )
    }

    @Test
    fun testBaselineProfileSyntaxAndArtPrefixesAreValid() {
        val (_, lines) = loadBaselineProfile()
        val activeRules = lines.map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

        // Valid ART rule prefixes: H (hot), S (startup), P (post-startup), L (class descriptor)
        val validPrefixRegex = Regex("^(H?S?P?L)[a-zA-Z0-9_$/]+;.*")

        for (rule in activeRules) {
            assertTrue(
                "Rule '$rule' does not adhere to standard ART baseline profile syntax",
                validPrefixRegex.matches(rule)
            )
            // Ensure class semicolon terminator exists
            assertTrue(
                "Rule '$rule' must contain class delimiter semicolon ';'",
                rule.contains(";")
            )
        }
    }

    @Test
    fun testBaselineProfileCoversAllCriticalUserJourneys() {
        val (_, lines) = loadBaselineProfile()
        val content = lines.joinToString("\n")

        val criticalUserJourneys = listOf(
            "MainActivity",
            "NearbyScreen",
            "FavoritesScreen",
            "NativeStationDetailSheet",
            "DistanceCalculator"
        )

        for (journey in criticalUserJourneys) {
            assertTrue(
                "baseline-prof.txt must contain rule for critical user journey '$journey'",
                content.contains(journey)
            )
        }
    }

    @Test
    fun testBaselineProfileCoversHotExecutionPathsAndLibraries() {
        val (_, lines) = loadBaselineProfile()
        val content = lines.joinToString("\n")

        // 1. Core UI and Application
        assertTrue("Profile must include EvPlusApplication", content.contains("EvPlusApplication"))
        assertTrue("Profile must include StationCard", content.contains("StationCard"))
        assertTrue("Profile must include SmartFilterBar", content.contains("SmartFilterBar"))

        // 2. Domain Filter & Routing Engines
        assertTrue("Profile must include NearbyStationFilter", content.contains("NearbyStationFilter"))
        assertTrue("Profile must include MultiTierRoutingCoordinator", content.contains("MultiTierRoutingCoordinator"))
        assertTrue("Profile must include RoutingPreferencesManager", content.contains("RoutingPreferencesManager"))

        // 3. Network & Image Utilities
        assertTrue("Profile must include AppOkHttpClientProvider", content.contains("AppOkHttpClientProvider"))
        assertTrue("Profile must include EvcsApiClient", content.contains("EvcsApiClient"))
        assertTrue("Profile must include VinFastCdnUrlDecoder", content.contains("VinFastCdnUrlDecoder"))

        // 4. Jetpack Compose Foundation & UI Runtime
        assertTrue("Profile must include Composer", content.contains("androidx/compose/runtime/Composer"))
        assertTrue("Profile must include Recomposer", content.contains("androidx/compose/runtime/Recomposer"))
        assertTrue("Profile must include SnapshotStateList", content.contains("SnapshotStateList"))
        assertTrue("Profile must include LayoutNode", content.contains("androidx/compose/ui/node/LayoutNode"))

        // 5. Coil Image Pipeline
        assertTrue("Profile must include Coil AsyncImage", content.contains("coil/compose/AsyncImage"))
        assertTrue("Profile must include Coil ImageLoader", content.contains("coil/ImageLoader") || content.contains("coil/RealImageLoader"))
        assertTrue("Profile must include Coil MemoryCache", content.contains("coil/memory/MemoryCache"))
        assertTrue("Profile must include Coil DiskCache", content.contains("coil/disk/DiskCache"))

        // 6. OkHttp 4.x Connection & Dispatchers
        assertTrue("Profile must include OkHttpClient", content.contains("okhttp3/OkHttpClient"))
        assertTrue("Profile must include RealCall", content.contains("okhttp3/internal/connection/RealCall"))
        assertTrue("Profile must include Dispatcher", content.contains("okhttp3/Dispatcher"))
        assertTrue("Profile must include ConnectionPool", content.contains("okhttp3/ConnectionPool"))

        // 7. Kotlinx Coroutines & Serialization
        assertTrue("Profile must include Dispatchers", content.contains("kotlinx/coroutines/Dispatchers"))
        assertTrue("Profile must include StateFlow", content.contains("StateFlow"))
        assertTrue("Profile must include Json parser", content.contains("kotlinx/serialization/json/Json"))
        assertTrue("Profile must include Json Decoder", content.contains("StreamingJsonDecoder") || content.contains("Decoder"))
    }

    @Test
    fun testMacrobenchmarkDocumentationExistsAndIsComprehensive() {
        val guideFile = resolveFile(
            "docs/benchmarks/macrobenchmark_guide.md",
            "../docs/benchmarks/macrobenchmark_guide.md"
        )
        assertTrue("macrobenchmark_guide.md must exist in docs/benchmarks/", guideFile.exists())

        val text = guideFile.readText()

        // Verify Cold Startup documentation
        assertTrue("Guide must document Cold Startup measurement", text.contains("StartupTimingMetric") && text.contains("COLD"))

        // Verify Frame Timing & Jank documentation
        assertTrue("Guide must document FrameTimingMetric", text.contains("FrameTimingMetric"))

        // Verify Baseline profile generation rule & gradle targets
        assertTrue("Guide must document BaselineProfileRule", text.contains("BaselineProfileRule"))
        assertTrue("Guide must document generateBaselineProfile task", text.contains("generateBaselineProfile"))
        assertTrue("Guide must document module setup or gradle config", text.contains("macrobenchmark") && text.contains("benchmarkRule"))
    }
}
