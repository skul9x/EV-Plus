package com.evcs.favorites.focus

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.navigation.MapIntentSpec
import com.evcs.favorites.navigation.MapNavigator
import com.evcs.favorites.ui.components.NativeStationDetailSheetHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Single Comprehensive Verification Test for Phase 01:
 * Turn-by-Turn Direct Navigation on Focus Mode Activation.
 *
 * Requirements covered:
 * 1. Focus Mode activation spec contains `google.navigation:q=lat,lon&mode=d` targeting `com.google.android.apps.maps`.
 * 2. Intent spec for Focus Mode matches the "Chỉ đường" (Navigate) turn-by-turn navigation format.
 * 3. `NativeStationDetailSheetHelper.launchNavigation` directly dispatches turn-by-turn navigation via `MapNavigator.navigate`.
 * 4. `NativeStationDetailSheetHelper.startFocusMode` initiates foreground service and triggers immediate direct navigation.
 * 5. Fallback intent descriptors and 3-tier fallback execution chain are preserved when Google Maps is missing or blocked.
 * 6. Non-functional: Safe, crash-free execution across all error conditions.
 */
class FocusModeDirectNavigationTest {

    private class RecordingTestContext : ContextWrapper(null) {
        val startedActivities = mutableListOf<Intent>()
        val startedServices = mutableListOf<Intent>()

        override fun getPackageName(): String = "com.evcs.favorites"

        override fun startActivity(intent: Intent?) {
            intent?.let { startedActivities.add(it) }
        }

        override fun startService(service: Intent?): ComponentName? {
            service?.let { startedServices.add(it) }
            return ComponentName("com.evcs.favorites", "com.evcs.favorites.focus.FocusModeForegroundService")
        }

        override fun startForegroundService(service: Intent?): ComponentName? {
            service?.let { startedServices.add(it) }
            return ComponentName("com.evcs.favorites", "com.evcs.favorites.focus.FocusModeForegroundService")
        }
    }

    private fun createSampleStation(
        id: String = "C.HCM0081",
        name: String = "VinFast Landmark 81",
        latitude: Double = 10.7950,
        longitude: Double = 106.7218
    ): Station {
        val powers = listOf(
            PowerPort(
                typeWatts = 150_000L,
                label = "150kW DC",
                availablePlugs = 3,
                totalPlugs = 8
            )
        )
        return Station(
            id = id,
            name = name,
            address = "720A Điện Biên Phủ, Phường 22, Bình Thạnh, TP. Hồ Chí Minh",
            latitude = latitude,
            longitude = longitude,
            summary = "Mở 24/7 • 3/8 DC khả dụng",
            connectors = "CCS2",
            depotStatus = "Normal",
            powers = powers,
            totalAvailablePlugs = 3,
            totalPlugs = 8
        )
    }

    @Test
    fun testFocusModeActivationSpec_containsTurnByTurnNavigationIntent() {
        val station = createSampleStation()
        val activationSpec = NativeStationDetailSheetHelper.buildFocusModeActivationSpec(station)

        // Validate service spec
        assertEquals(FocusModeForegroundService.ACTION_START, activationSpec.serviceIntentSpec.action)
        assertEquals(FocusModeForegroundService::class.java, activationSpec.serviceIntentSpec.targetClass)
        assertNotNull(activationSpec.serviceIntentSpec.payloadJson)

        // Validate navigation spec uses Google Maps turn-by-turn navigation
        val navSpec = activationSpec.navigationIntentSpec
        assertEquals(MapNavigator.ACTION_VIEW, navSpec.action)
        assertEquals("android.intent.action.VIEW", navSpec.action)
        assertEquals(MapNavigator.GOOGLE_MAPS_PACKAGE, navSpec.packageName)
        assertEquals("com.google.android.apps.maps", navSpec.packageName)

        val expectedUri = "google.navigation:q=10.795,106.7218&mode=d"
        assertEquals(expectedUri, navSpec.uriString)

        // Verify matches MapNavigator.getGoogleMapsIntentSpec exactly
        val expectedSpec = MapNavigator.getGoogleMapsIntentSpec(station.latitude, station.longitude)
        assertEquals(expectedSpec, navSpec)
    }

    @Test
    fun testFocusModeIntentSpec_matchesNavigateActionTurnByTurnFormat() {
        val station = createSampleStation(
            id = "C.HNI0099",
            name = "VinFast Royal City",
            latitude = 21.0028,
            longitude = 105.8167
        )

        val activationSpec = NativeStationDetailSheetHelper.buildFocusModeActivationSpec(station)
        val navSpec: MapIntentSpec = activationSpec.navigationIntentSpec

        // Verify URI generation matches MapNavigator helper
        val expectedUri = MapNavigator.buildGoogleNavigationUriString(station.latitude, station.longitude)
        assertEquals(expectedUri, navSpec.uriString)
        assertEquals("google.navigation:q=21.0028,105.8167&mode=d", navSpec.uriString)

        // Verify package and action target direct turn-by-turn Google Maps
        assertEquals(MapNavigator.ACTION_VIEW, navSpec.action)
        assertEquals(MapNavigator.GOOGLE_MAPS_PACKAGE, navSpec.packageName)

        // Verify toIntent builds non-null Intent descriptor
        val androidIntent = navSpec.toIntent()
        assertNotNull(androidIntent)
    }

    @Test
    fun testLaunchNavigation_dispatchesTurnByTurnNavigationViaMapNavigator() {
        val context = RecordingTestContext()
        val station = createSampleStation(
            latitude = 20.8449,
            longitude = 106.6881,
            name = "VinFast Hải Phòng"
        )

        // 1. Direct dispatch via Context
        val success = NativeStationDetailSheetHelper.launchNavigation(context, station)
        assertTrue("launchNavigation should succeed", success)
        assertEquals("Exactly one activity should be started", 1, context.startedActivities.size)
        assertNotNull(context.startedActivities[0])

        // 2. Dispatch with custom launcher capturing intent
        var dispatchedIntent: Intent? = null
        val launcherSuccess = NativeStationDetailSheetHelper.launchNavigation(
            context = context,
            station = station,
            intentLauncher = { intent ->
                dispatchedIntent = intent
            }
        )
        assertTrue(launcherSuccess)
        assertNotNull("Dispatched intent must not be null", dispatchedIntent)
    }

    @Test
    fun testStartFocusMode_initiatesForegroundServiceAndDirectTurnByTurnNavigation() {
        val context = RecordingTestContext()
        val station = createSampleStation(
            latitude = 16.0544,
            longitude = 108.2022,
            name = "VinFast Đà Nẵng"
        )

        var launchedIntent: Intent? = null
        NativeStationDetailSheetHelper.startFocusMode(
            context = context,
            station = station,
            intentLauncher = { intent ->
                launchedIntent = intent
            }
        )

        // Verify service intent was dispatched
        assertEquals("Service should be started", 1, context.startedServices.size)
        val serviceIntent = context.startedServices[0]
        assertNotNull("Service start Intent must be dispatched", serviceIntent)

        val activationSpec = NativeStationDetailSheetHelper.buildFocusModeActivationSpec(station)
        assertEquals(FocusModeForegroundService.ACTION_START, activationSpec.serviceIntentSpec.action)
        assertEquals(FocusModeForegroundService::class.java, activationSpec.serviceIntentSpec.targetClass)

        // Verify navigation activity was immediately dispatched
        assertNotNull("Turn-by-turn navigation activity should be started", launchedIntent)
    }

    @Test
    fun testFallbackIntentDescriptorsAndDispatchPreservedWhenGoogleMapsUnavailable() {
        val lat = 10.7950
        val lon = 106.7218
        val stationName = "VinFast Landmark 81"

        // 1. Fallback specs are preserved and correctly formatted
        val geoSpec = MapNavigator.getGeoIntentSpec(lat, lon, stationName)
        assertEquals("android.intent.action.VIEW", geoSpec.action)
        assertNull(geoSpec.packageName)
        assertTrue(geoSpec.uriString.startsWith("geo:10.795,106.7218?q=10.795,106.7218("))
        assertTrue(geoSpec.uriString.contains("VinFast%20Landmark%2081"))

        val browserSpec = MapNavigator.getBrowserIntentSpec(lat, lon)
        assertEquals("android.intent.action.VIEW", browserSpec.action)
        assertNull(browserSpec.packageName)
        assertEquals("https://www.google.com/maps/search/?api=1&query=10.795,106.7218", browserSpec.uriString)

        // 2. 3-Tier fallback execution chain in MapNavigator.navigate
        val context = RecordingTestContext()

        // Case A: Google Maps available
        var attemptsA = 0
        var launchedIntentA: Intent? = null
        val successA = MapNavigator.navigate(
            context = context,
            latitude = lat,
            longitude = lon,
            stationName = stationName,
            intentLauncher = { intent ->
                attemptsA++
                launchedIntentA = intent
            }
        )
        assertTrue(successA)
        assertEquals(1, attemptsA)
        assertNotNull(launchedIntentA)

        // Case B: Google Maps unavailable (ActivityNotFoundException) -> falls back to Geo URI
        var attemptsB = 0
        var launchedIntentB: Intent? = null
        val successB = MapNavigator.navigate(
            context = context,
            latitude = lat,
            longitude = lon,
            stationName = stationName,
            intentLauncher = { intent ->
                attemptsB++
                if (attemptsB == 1) {
                    throw ActivityNotFoundException("Google Maps app not found")
                }
                launchedIntentB = intent
            }
        )
        assertTrue(successB)
        assertEquals(2, attemptsB)
        assertNotNull(launchedIntentB)

        // Case C: Both Google Maps and generic Geo unavailable -> falls back to Browser
        var attemptsC = 0
        var launchedIntentC: Intent? = null
        val successC = MapNavigator.navigate(
            context = context,
            latitude = lat,
            longitude = lon,
            stationName = stationName,
            intentLauncher = { intent ->
                attemptsC++
                if (attemptsC <= 2) {
                    throw ActivityNotFoundException("No map handler")
                }
                launchedIntentC = intent
            }
        )
        assertTrue(successC)
        assertEquals(3, attemptsC)
        assertNotNull(launchedIntentC)

        // Case D: All launchers fail with general exception -> degrades safely to false without crashing
        var attemptsD = 0
        val successD = MapNavigator.navigate(
            context = context,
            latitude = lat,
            longitude = lon,
            stationName = stationName,
            intentLauncher = { _ ->
                attemptsD++
                throw RuntimeException("OS security restriction")
            }
        )
        assertFalse(successD)
        assertEquals(3, attemptsD)
    }
}
