package com.evcs.favorites.ui

import android.content.pm.ActivityInfo
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.preferences.OrientationPreferences
import com.evcs.favorites.data.preferences.StartupOrientation
import com.evcs.favorites.util.OrientationHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive verification test for Phase 01:
 * Startup Orientation Setting, Persistence & Activity Lifecycle Enforcement.
 *
 * Verifies:
 * 1. Default orientation is StartupOrientation.SYSTEM.
 * 2. Persistence write/read across all enum values (SYSTEM, LANDSCAPE, PORTRAIT).
 * 3. Invalid/corrupt/unknown string values safely fall back to SYSTEM.
 * 4. Reactive StateFlow emits new orientation values upon update.
 * 5. OrientationHelper.toActivityInfoOrientation accurately maps all enum values to target ActivityInfo flags.
 * 6. OrientationHelper.fromActivityInfoOrientation reverse-maps constants safely.
 * 7. Display titles, descriptions, and automotive recommendation badges.
 */
class StartupOrientationSettingsTest {

    private lateinit var storage: InMemorySessionStorage
    private lateinit var preferences: OrientationPreferences

    @Before
    fun setUp() {
        storage = InMemorySessionStorage()
        preferences = OrientationPreferences(storage)
    }

    @Test
    fun testDefaultOrientation_isSystem() {
        assertEquals(StartupOrientation.SYSTEM, OrientationPreferences.DEFAULT_ORIENTATION)
        assertEquals(StartupOrientation.SYSTEM, preferences.getStartupOrientation())
        assertEquals(StartupOrientation.SYSTEM, preferences.startupOrientationFlow.value)
    }

    @Test
    fun testPersistence_writeAndReadAcrossAllEnumValues() {
        // SYSTEM
        preferences.setStartupOrientation(StartupOrientation.SYSTEM)
        assertEquals(StartupOrientation.SYSTEM, preferences.getStartupOrientation())
        assertEquals("SYSTEM", storage.getString(OrientationPreferences.KEY_STARTUP_ORIENTATION))

        // LANDSCAPE
        preferences.setStartupOrientation(StartupOrientation.LANDSCAPE)
        assertEquals(StartupOrientation.LANDSCAPE, preferences.getStartupOrientation())
        assertEquals("LANDSCAPE", storage.getString(OrientationPreferences.KEY_STARTUP_ORIENTATION))

        // PORTRAIT
        preferences.setStartupOrientation(StartupOrientation.PORTRAIT)
        assertEquals(StartupOrientation.PORTRAIT, preferences.getStartupOrientation())
        assertEquals("PORTRAIT", storage.getString(OrientationPreferences.KEY_STARTUP_ORIENTATION))
    }

    @Test
    fun testCorruptAndInvalidStorageValues_safelyFallbackToSystem() {
        val invalidValues = listOf(
            null,
            "",
            "   ",
            "INVALID_VALUE",
            "123",
            "SENSOR_LANDSCAPE",
            "AUTO",
            "null"
        )

        for (invalid in invalidValues) {
            storage.putString(OrientationPreferences.KEY_STARTUP_ORIENTATION, invalid)
            val orientation = preferences.getStartupOrientation()
            assertEquals(
                "Value '$invalid' must safely fall back to SYSTEM",
                StartupOrientation.SYSTEM,
                orientation
            )
        }
    }

    @Test
    fun testCaseInsensitiveStorageParsing() {
        val testCases = mapOf(
            "landscape" to StartupOrientation.LANDSCAPE,
            "LANDSCAPE" to StartupOrientation.LANDSCAPE,
            "Landscape" to StartupOrientation.LANDSCAPE,
            "  landscape  " to StartupOrientation.LANDSCAPE,
            "portrait" to StartupOrientation.PORTRAIT,
            "PORTRAIT" to StartupOrientation.PORTRAIT,
            "system" to StartupOrientation.SYSTEM,
            "SYSTEM" to StartupOrientation.SYSTEM
        )

        for ((raw, expected) in testCases) {
            storage.putString(OrientationPreferences.KEY_STARTUP_ORIENTATION, raw)
            assertEquals(expected, preferences.getStartupOrientation())
        }
    }

    @Test
    fun testReactiveStateFlow_emitsNewValuesUponUpdate() {
        assertEquals(StartupOrientation.SYSTEM, preferences.startupOrientationFlow.value)

        preferences.setStartupOrientation(StartupOrientation.LANDSCAPE)
        assertEquals(StartupOrientation.LANDSCAPE, preferences.startupOrientationFlow.value)

        preferences.setStartupOrientation(StartupOrientation.PORTRAIT)
        assertEquals(StartupOrientation.PORTRAIT, preferences.startupOrientationFlow.value)

        preferences.setStartupOrientation(StartupOrientation.SYSTEM)
        assertEquals(StartupOrientation.SYSTEM, preferences.startupOrientationFlow.value)
    }

    @Test
    fun testOrientationHelper_toActivityInfoOrientation_accurateMapping() {
        // SYSTEM -> SCREEN_ORIENTATION_UNSPECIFIED (-1)
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            OrientationHelper.toActivityInfoOrientation(StartupOrientation.SYSTEM)
        )

        // LANDSCAPE -> SCREEN_ORIENTATION_LANDSCAPE (0)
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            OrientationHelper.toActivityInfoOrientation(StartupOrientation.LANDSCAPE)
        )

        // PORTRAIT -> SCREEN_ORIENTATION_PORTRAIT (1)
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            OrientationHelper.toActivityInfoOrientation(StartupOrientation.PORTRAIT)
        )
    }

    @Test
    fun testOrientationHelper_fromActivityInfoOrientation_reverseMapping() {
        assertEquals(
            StartupOrientation.LANDSCAPE,
            OrientationHelper.fromActivityInfoOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        )
        assertEquals(
            StartupOrientation.LANDSCAPE,
            OrientationHelper.fromActivityInfoOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
        )
        assertEquals(
            StartupOrientation.LANDSCAPE,
            OrientationHelper.fromActivityInfoOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE)
        )

        assertEquals(
            StartupOrientation.PORTRAIT,
            OrientationHelper.fromActivityInfoOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        )
        assertEquals(
            StartupOrientation.PORTRAIT,
            OrientationHelper.fromActivityInfoOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT)
        )
        assertEquals(
            StartupOrientation.PORTRAIT,
            OrientationHelper.fromActivityInfoOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT)
        )

        assertEquals(
            StartupOrientation.SYSTEM,
            OrientationHelper.fromActivityInfoOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        )
        assertEquals(
            StartupOrientation.SYSTEM,
            OrientationHelper.fromActivityInfoOrientation(9999)
        )
    }

    @Test
    fun testOrientationMetadata_titlesAndBadges() {
        // SYSTEM
        assertEquals("Mặc định hệ thống (Tự xoay)", StartupOrientation.SYSTEM.title)
        assertNull(StartupOrientation.SYSTEM.badge)
        assertNotNull(StartupOrientation.SYSTEM.description)

        // LANDSCAPE (Automotive Box recommended)
        assertEquals("Luôn mở màn hình ngang (Landscape)", StartupOrientation.LANDSCAPE.title)
        assertEquals("Khuyên dùng cho Android Box ô tô", StartupOrientation.LANDSCAPE.badge)
        assertNotNull(StartupOrientation.LANDSCAPE.description)

        // PORTRAIT (Phone recommended)
        assertEquals("Luôn mở màn hình dọc (Portrait)", StartupOrientation.PORTRAIT.title)
        assertEquals("Khuyên dùng cho điện thoại", StartupOrientation.PORTRAIT.badge)
        assertNotNull(StartupOrientation.PORTRAIT.description)
    }

    @Test
    fun testStorageKeyAndPreferencesNameConstants() {
        assertEquals("startup_orientation", OrientationPreferences.KEY_STARTUP_ORIENTATION)
        assertEquals("evcs_orientation_prefs", OrientationPreferences.PREFS_NAME)
    }
}
