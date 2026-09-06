package com.evcs.favorites.build

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.evcs.favorites.ui.theme.AppIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 07 Verification Test: APK Bloat Reduction & Baseline Profiles Optimization.
 *
 * Verifies:
 * 1. Monolithic `material-icons-extended` is absent from `app/build.gradle.kts`.
 * 2. `material-icons-core` and `profileinstaller` runtime dependencies are present.
 * 3. AGP 8.2+ and legacy `baseline-prof.txt` files exist, are non-empty, and contain valid ART rules.
 * 4. `AppIcons` resolves all required icon vectors with valid dimensions without runtime exceptions.
 */
class BuildOptimizationAndBaselineProfileTest {

    @Test
    fun testBuildDependenciesOptimization() {
        val gradleFile = findFileInProject("app/build.gradle.kts")
        assertTrue("build.gradle.kts must exist", gradleFile.exists())

        val content = gradleFile.readText()

        // 1. Ensure material-icons-extended is completely absent
        assertFalse(
            "Monolithic material-icons-extended must not be present in dependencies",
            content.contains("androidx.compose.material:material-icons-extended")
        )

        // 2. Ensure material-icons-core is present
        assertTrue(
            "material-icons-core must be explicitly declared",
            content.contains("androidx.compose.material:material-icons-core")
        )

        // 3. Ensure profileinstaller is present for runtime AOT compilation
        assertTrue(
            "androidx.profileinstaller:profileinstaller must be declared",
            content.contains("androidx.profileinstaller:profileinstaller")
        )
    }

    @Test
    fun testBaselineProfileArtifactsAndSyntax() {
        val agp82Profile = findFileInProject("app/src/main/baselineProfiles/baseline-prof.txt")
        val legacyProfile = findFileInProject("app/src/main/baseline-prof.txt")

        assertTrue("AGP 8.2+ baseline-prof.txt must exist", agp82Profile.exists())
        assertTrue("Legacy AGP baseline-prof.txt must exist", legacyProfile.exists())

        val agpLines = agp82Profile.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        val legacyLines = legacyProfile.readLines().filter { it.isNotBlank() && !it.startsWith("#") }

        assertTrue("AGP 8.2+ baseline-prof.txt must contain rules", agpLines.isNotEmpty())
        assertTrue("Legacy baseline-prof.txt must contain rules", legacyLines.isNotEmpty())

        // Validate ART profile syntax format (e.g. HSPL..., PL..., L...)
        val artRulePattern = Regex("""^(HSPL|PL|L)[a-zA-Z0-9_/$\-]+;->.*""")
        for (line in agpLines) {
            assertTrue("Line in baseline profile must follow valid ART rule syntax: $line", artRulePattern.matches(line))
        }

        // Verify critical CUJ classes are targeted
        val combined = agpLines.joinToString("\n")
        assertTrue("Must include MainActivity initialization", combined.contains("MainActivity;-><init>()V"))
        assertTrue("Must include MainActivity onCreate", combined.contains("MainActivity;->onCreate"))
        assertTrue("Must include FavoritesScreenKt", combined.contains("FavoritesScreenKt;->*"))
        assertTrue("Must include NearbyScreenKt", combined.contains("NearbyScreenKt;->*"))
        assertTrue("Must include StationCardKt", combined.contains("StationCardKt;->*"))
        assertTrue("Must include DistanceCalculator", combined.contains("DistanceCalculator;->*"))
    }

    @Test
    fun testAppIconsAllRequiredVectorsResolve() {
        val iconMap: Map<String, ImageVector> = mapOf(
            // Core UI (8)
            "AccessTime" to AppIcons.AccessTime,
            "AccountCircle" to AppIcons.AccountCircle,
            "Bolt" to AppIcons.Bolt,
            "CheckCircle" to AppIcons.CheckCircle,
            "CloudOff" to AppIcons.CloudOff,
            "ContentCopy" to AppIcons.ContentCopy,
            "DeleteOutline" to AppIcons.DeleteOutline,
            "DirectionsCar" to AppIcons.DirectionsCar,

            // Status & Error (6)
            "Error" to AppIcons.Error,
            "ErrorOutline" to AppIcons.ErrorOutline,
            "EvStation" to AppIcons.EvStation,
            "ExpandLess" to AppIcons.ExpandLess,
            "ExpandMore" to AppIcons.ExpandMore,
            "FilterListOff" to AppIcons.FilterListOff,

            // Action & Navigation (11)
            "Key" to AppIcons.Key,
            "Lightbulb" to AppIcons.Lightbulb,
            "Logout" to AppIcons.Logout,
            "Navigation" to AppIcons.Navigation,
            "NearMe" to AppIcons.NearMe,
            "OpenInNew" to AppIcons.OpenInNew,
            "Save" to AppIcons.Save,
            "Security" to AppIcons.Security,
            "Settings" to AppIcons.Settings,
            "Terminal" to AppIcons.Terminal,
            "Tune" to AppIcons.Tune,

            // Outlined navigation (2)
            "FavoriteBorder" to AppIcons.FavoriteBorder,
            "LocationOn" to AppIcons.LocationOn
        )

        // Verifies all 25+2 icons resolve without null
        assertEquals("Must provide all 27 required icon definitions", 27, iconMap.size)

        for ((name, icon) in iconMap) {
            assertNotNull("Icon $name must not be null", icon)
            assertEquals("Icon $name must have 24.dp default width", 24.dp, icon.defaultWidth)
            assertEquals("Icon $name must have 24.dp default height", 24.dp, icon.defaultHeight)
            assertEquals("Icon $name must have 24f viewport width", 24f, icon.viewportWidth)
            assertEquals("Icon $name must have 24f viewport height", 24f, icon.viewportHeight)
            assertNotNull("Icon $name root vector group must not be null", icon.root)
        }

        // Also verify nested Outlined and aliased accessors
        assertNotNull(AppIcons.Outlined.FavoriteBorder)
        assertNotNull(AppIcons.Outlined.LocationOn)
        assertNotNull(AppIcons.FavoriteBorderOutlined)
        assertNotNull(AppIcons.LocationOnOutlined)
    }

    private fun findFileInProject(relativePath: String): File {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(userDir, relativePath),
            File(userDir.parentFile, relativePath),
            File("/home/skul9x/Desktop/Code/EV-Plus-main", relativePath)
        )
        return candidates.firstOrNull { it.exists() } ?: candidates.first()
    }
}
