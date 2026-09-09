package com.evcs.favorites.ui

import com.evcs.favorites.data.model.Station
import com.evcs.favorites.focus.FocusModeForegroundService
import com.evcs.favorites.ui.components.NativeStationDetailSheetHelper
import com.evcs.favorites.util.DebounceHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Single comprehensive test suite for Phase 02:
 * Verifies Station Detail Quick Action split into "Chỉ Đường" and "Focus" buttons,
 * backwards-compatible label contracts, navigation/focus specs, and debounce isolation.
 */
class StationDetailActionSplitTest {

    private fun createTestStation(
        id: String = "station-split-1",
        name: String = "VinFast Long Biên",
        lat: Double = 21.035,
        lon: Double = 105.890
    ): Station {
        return Station(
            id = id,
            name = name,
            address = "Long Biên, Hà Nội",
            latitude = lat,
            longitude = lon,
            summary = "Trạm sạc VinFast 24/7",
            connectors = "60kW, 120kW",
            depotStatus = "Normal"
        )
    }

    @Test
    fun testLabelConstantsAndBackwardsCompatibility() {
        // New Split Action Button Labels
        assertEquals("Chỉ Đường", NativeStationDetailSheetHelper.LABEL_NAVIGATE_ONLY)
        assertEquals("Focus", NativeStationDetailSheetHelper.LABEL_FOCUS)

        // Preserved Legacy Constants for 100% Backwards Compatibility
        assertEquals("Chỉ đường", NativeStationDetailSheetHelper.LABEL_NAVIGATE)
        assertEquals("⚡ Focus Mode", NativeStationDetailSheetHelper.LABEL_FOCUS_MODE)
        assertEquals("⚡ DẪN ĐƯỜNG & THEO DÕI", NativeStationDetailSheetHelper.LABEL_NAVIGATE_AND_TRACK)
        assertEquals("Yêu thích", NativeStationDetailSheetHelper.LABEL_FAVORITE)
        assertEquals("Đã lưu", NativeStationDetailSheetHelper.LABEL_SAVED)
        assertEquals("Chia sẻ", NativeStationDetailSheetHelper.LABEL_SHARE)

        // Automotive Touch Target Safety
        assertTrue(
            "Automotive button height must be >= 56dp",
            NativeStationDetailSheetHelper.CAR_BUTTON_HEIGHT_DP >= 56f
        )
    }

    @Test
    fun testPureNavigationIntentSpecDispatchesToGoogleMapsWithoutFocusMode() {
        val station = createTestStation()
        val navSpec = NativeStationDetailSheetHelper.buildNavigationIntentSpec(station)

        assertEquals("android.intent.action.VIEW", navSpec.action)
        assertEquals("com.google.android.apps.maps", navSpec.packageName)
        assertNotNull(navSpec.uriString)
        assertTrue(
            "Navigation URI must target station coordinates",
            navSpec.uriString.contains("21.035") && navSpec.uriString.contains("105.89")
        )
    }

    @Test
    fun testFocusModeActivationSpecRetainsFullTelemetryPayload() {
        val station = createTestStation(id = "station-focus-99", name = "VinFast Smart City")
        val focusSpec = NativeStationDetailSheetHelper.buildFocusModeActivationSpec(station)

        assertEquals(FocusModeForegroundService.ACTION_START, focusSpec.serviceIntentSpec.action)
        assertEquals("android.intent.action.VIEW", focusSpec.navigationIntentSpec.action)
        assertEquals("com.google.android.apps.maps", focusSpec.navigationIntentSpec.packageName)
        assertTrue(
            "Focus payload JSON must contain station details",
            focusSpec.serviceIntentSpec.payloadJson?.contains("station-focus-99") == true
        )
    }

    @Test
    fun testDebounceIsolationBetweenPureNavAndFocusActions() {
        val navDebounce = DebounceHelper(intervalMs = 1000L)
        val focusDebounce = DebounceHelper(intervalMs = 1000L)

        var navCallCount = 0
        var focusCallCount = 0

        // Rapid click 1 on "Chỉ Đường"
        navDebounce.runIfAllowed { navCallCount++ }
        assertEquals(1, navCallCount)

        // Rapid click 2 on "Chỉ Đường" immediately after (should be dropped)
        navDebounce.runIfAllowed { navCallCount++ }
        assertEquals(1, navCallCount)

        // Tap on "Focus" immediately after: independent focusDebounce must allow it!
        focusDebounce.runIfAllowed { focusCallCount++ }
        assertEquals(1, focusCallCount)

        // Rapid second tap on "Focus" (should be dropped)
        focusDebounce.runIfAllowed { focusCallCount++ }
        assertEquals(1, focusCallCount)
    }

    @Test
    fun testNativeStationDetailContentComposableContract() {
        val sheetClass = Class.forName("com.evcs.favorites.ui.components.NativeStationDetailSheetKt")
        val methods = sheetClass.declaredMethods.map { it.name }

        assertTrue(
            "NativeStationDetailSheetKt must declare NativeStationDetailContent",
            methods.any { it.contains("NativeStationDetailContent") }
        )
        assertTrue(
            "NativeStationDetailSheetKt must declare NativeStationDetailSheet",
            methods.any { it.contains("NativeStationDetailSheet") }
        )
    }
}
