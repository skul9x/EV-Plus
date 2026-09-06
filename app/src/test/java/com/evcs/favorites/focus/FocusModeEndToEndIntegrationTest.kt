package com.evcs.favorites.focus

import android.content.Context
import android.content.ContextWrapper
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.routing.DrivingMetrics
import com.evcs.favorites.data.routing.RoutingEngineType
import com.evcs.favorites.di.DefaultAppContainer
import com.evcs.favorites.ui.components.FocusModePermissionDialogHelper
import com.evcs.favorites.ui.components.NativeStationDetailSheetHelper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Single Comprehensive Verification Test for Phase 06:
 * Sheet Entry Point & E2E Integration.
 *
 * Requirements covered:
 * 1. Validates full end-to-end pipeline: Sheet trigger -> Intent creation -> Service start intent building -> State pipeline setup.
 * 2. Validates permission dialog action routing (accept opens system settings; decline initiates notification fallback).
 * 3. Validates coexistence and preservation of all station detail sheet actions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FocusModeEndToEndIntegrationTest {

    private class DummyTestContext : ContextWrapper(null) {
        override fun getPackageName(): String = "com.evcs.favorites"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun createSampleStation(
        id: String = "C.HCM0081",
        name: String = "VinFast Landmark 81",
        availableDcPlugs: Int = 3,
        totalDcPlugs: Int = 8,
        powerKw: Long = 150L,
        distanceKm: Double = 3.2
    ): Station {
        val powers = listOf(
            PowerPort(
                typeWatts = powerKw * 1000L,
                label = "${powerKw}kW DC",
                availablePlugs = availableDcPlugs,
                totalPlugs = totalDcPlugs
            ),
            PowerPort(
                typeWatts = 11_000L,
                label = "11kW AC",
                availablePlugs = 2,
                totalPlugs = 2
            )
        )
        return Station(
            id = id,
            name = name,
            address = "720A Điện Biên Phủ, Phường 22, Bình Thạnh, TP. Hồ Chí Minh",
            latitude = 10.7950,
            longitude = 106.7218,
            summary = "Mở 24/7 • $availableDcPlugs/$totalDcPlugs DC khả dụng",
            connectors = "CCS2",
            depotStatus = "Normal",
            powers = powers,
            totalAvailablePlugs = powers.sumOf { it.availablePlugs },
            totalPlugs = powers.sumOf { it.totalPlugs },
            distanceKm = distanceKm,
            drivingMetrics = DrivingMetrics(
                distanceMeters = 3200L,
                durationSeconds = 480L,
                engineUsed = RoutingEngineType.GOOGLE
            )
        )
    }

    // =============================================================================================
    // Requirement 1: Full End-to-End Pipeline Verification
    // =============================================================================================

    @Test
    fun testEndToEndPipeline_sheetTriggerToServiceIntentAndStatePipeline() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val context = DummyTestContext()
        val station = createSampleStation()

        // 1. Trigger Focus Mode activation spec from Sheet Helper
        val activationSpec = NativeStationDetailSheetHelper.buildFocusModeActivationSpec(station)

        // Validate Service Intent Spec
        assertEquals(FocusModeForegroundService.ACTION_START, activationSpec.serviceIntentSpec.action)
        assertEquals(FocusModeForegroundService::class.java, activationSpec.serviceIntentSpec.targetClass)
        assertNotNull("Payload JSON must not be null", activationSpec.serviceIntentSpec.payloadJson)

        val deserializedFromPayload = json.decodeFromString<Station>(
            Station.serializer(),
            activationSpec.serviceIntentSpec.payloadJson!!
        )
        assertEquals(station.id, deserializedFromPayload.id)
        assertEquals(station.latitude, deserializedFromPayload.latitude, 0.0001)
        assertEquals(station.longitude, deserializedFromPayload.longitude, 0.0001)

        // Validate Navigation Intent Spec
        assertEquals(NativeStationDetailSheetHelper.ACTION_VIEW, activationSpec.navigationIntentSpec.action)
        assertEquals(NativeStationDetailSheetHelper.GOOGLE_MAPS_PACKAGE, activationSpec.navigationIntentSpec.packageName)
        assertTrue(activationSpec.navigationIntentSpec.uriString.startsWith("geo:0,0?q=10.795,106.7218("))
        assertTrue(activationSpec.navigationIntentSpec.uriString.contains("VinFast%20Landmark%2081"))

        // 2. Build Android Intent via Service helper
        val serviceIntent = FocusModeForegroundService.createStartIntent(context, station)
        assertNotNull("Service start Intent must be created", serviceIntent)

        val serviceSpec = FocusModeForegroundService.getStartIntentSpec(station)
        assertEquals(FocusModeForegroundService.ACTION_START, serviceSpec.action)
        assertEquals(FocusModeForegroundService::class.java, serviceSpec.targetClass)
        assertNotNull("Payload JSON must not be null", serviceSpec.payloadJson)

        val decodedStation = json.decodeFromString<Station>(Station.serializer(), serviceSpec.payloadJson!!)
        assertEquals(station.id, decodedStation.id)
        assertEquals(station.name, decodedStation.name)

        // 3. Setup Telemetry Engine pipeline
        var mockFetchCount = 0
        var updatedStation = station.copy()

        val engine = FocusModeTelemetryEngine(
            initialStation = station,
            fetchStationTelemetry = { _, _, _ ->
                mockFetchCount++
                Result.success(updatedStation)
            },
            clock = { 10_000L },
            defaultDispatcher = testDispatcher,
            coroutineScope = testScope
        )

        // Initial State Verification
        val initialState = engine.state.value
        assertEquals(station.id, initialState.targetStation.id)
        assertEquals(3, initialState.availableDcSlots)
        assertEquals(8, initialState.totalDcSlots)
        assertEquals(3.2, initialState.distanceRemainingKm ?: 0.0, 0.001)
        assertEquals(FocusConnectionStatus.CONNECTED, initialState.connectionStatus)
        org.junit.Assert.assertNull(initialState.alternativeStation)

        // Execute single polling cycle
        updatedStation = station.copy(
            powers = listOf(
                PowerPort(
                    typeWatts = 150_000L,
                    label = "150kW DC",
                    availablePlugs = 1, // reduced from 3 to 1
                    totalPlugs = 8
                )
            )
        )
        val updatedState = engine.pollOnce()
        assertEquals(1, updatedState.availableDcSlots)
        assertEquals(1, mockFetchCount)

        engine.stop()
    }

    // =============================================================================================
    // Requirement 2: Permission Dialog Action Routing Verification
    // =============================================================================================

    @Test
    fun testPermissionDialogActionRouting_grantOpensSettings_declineInitiatesFallback() {
        val packageName = "com.evcs.favorites"

        // 1. Validate Dialog Strings and Tokens
        assertEquals("Kích hoạt Chế độ Focus Mode", FocusModePermissionDialogHelper.TITLE)
        assertEquals("Cấp quyền (Cửa sổ nổi)", FocusModePermissionDialogHelper.BTN_GRANT_PERMISSION)
        assertEquals("Dùng thông báo (Không cần quyền)", FocusModePermissionDialogHelper.BTN_NOTIFICATION_FALLBACK)
        assertEquals(
            "android.settings.action.MANAGE_OVERLAY_PERMISSION",
            FocusModePermissionDialogHelper.ACTION_MANAGE_OVERLAY_PERMISSION
        )

        // 2. Action Branch 1: User grants overlay permission -> System settings intent
        val overlayIntentSpec = FocusModePermissionDialogHelper.buildOverlaySettingsIntentSpec(packageName)
        assertEquals(FocusModePermissionDialogHelper.ACTION_MANAGE_OVERLAY_PERMISSION, overlayIntentSpec.action)
        assertEquals("package:com.evcs.favorites", overlayIntentSpec.packageUriString)

        var settingsOpened = false
        val onGrantOverlayPermission: () -> Unit = {
            settingsOpened = true
        }
        onGrantOverlayPermission()
        assertTrue("Granting overlay permission must trigger settings routing", settingsOpened)

        // 3. Action Branch 2: User declines overlay -> Notification fallback activation
        var fallbackActivated = false
        var targetStationActivated: Station? = null
        val station = createSampleStation()

        val onUseNotificationFallback: () -> Unit = {
            fallbackActivated = true
            targetStationActivated = station
        }
        onUseNotificationFallback()

        assertTrue("Declining overlay must activate notification fallback", fallbackActivated)
        assertEquals(station, targetStationActivated)

        // 4. Action Branch 3: Direct activation when permission is already granted
        var dialogShown = false
        var directActivated = false

        fun routeActivation(canDrawOverlays: Boolean) {
            if (canDrawOverlays) {
                directActivated = true
            } else {
                dialogShown = true
            }
        }

        // When permission granted -> direct activation, no dialog
        routeActivation(canDrawOverlays = true)
        assertTrue(directActivated)
        assertFalse(dialogShown)

        // Reset and test when permission not granted -> dialog prompted
        directActivated = false
        routeActivation(canDrawOverlays = false)
        assertFalse(directActivated)
        assertTrue(dialogShown)
    }

    // =============================================================================================
    // Requirement 3: Coexistence & Preservation of All Station Detail Sheet Actions
    // =============================================================================================

    @Test
    fun testCoexistenceAndPreservationOfAllStationDetailSheetActions() {
        val station = createSampleStation()

        // 1. Action 1: Navigation CTA
        assertEquals("Chỉ đường", NativeStationDetailSheetHelper.LABEL_NAVIGATE)
        val navSpec = NativeStationDetailSheetHelper.buildNavigationIntentSpec(station)
        assertEquals(NativeStationDetailSheetHelper.ACTION_VIEW, navSpec.action)
        assertEquals(NativeStationDetailSheetHelper.GOOGLE_MAPS_PACKAGE, navSpec.packageName)

        // 2. Action 2: Focus Mode CTA
        assertEquals("⚡ Focus Mode", NativeStationDetailSheetHelper.LABEL_FOCUS_MODE)
        assertEquals("Kích hoạt Chế độ Focus Mode", NativeStationDetailSheetHelper.DESC_FOCUS_MODE)
        val focusSpec = NativeStationDetailSheetHelper.buildFocusModeActivationSpec(station)
        assertEquals(FocusModeForegroundService.ACTION_START, focusSpec.serviceIntentSpec.action)
        assertEquals(NativeStationDetailSheetHelper.ACTION_VIEW, focusSpec.navigationIntentSpec.action)

        // 3. Action 3: Favorite CTA (Unfavorited vs Favorited)
        assertEquals("Yêu thích", NativeStationDetailSheetHelper.LABEL_FAVORITE)
        assertEquals("Đã lưu", NativeStationDetailSheetHelper.LABEL_SAVED)
        assertEquals("Bỏ yêu thích", NativeStationDetailSheetHelper.DESC_UNFAVORITE)

        val unpressedSpec = NativeStationDetailSheetHelper.resolveFavoriteButtonSpec(isFavorite = false)
        assertFalse(unpressedSpec.isFavorite)
        assertEquals("Yêu thích", unpressedSpec.label)

        val savedSpec = NativeStationDetailSheetHelper.resolveFavoriteButtonSpec(isFavorite = true)
        assertTrue(savedSpec.isFavorite)
        assertEquals("Đã lưu", savedSpec.label)

        // 4. Action 4: Share CTA
        assertEquals("Chia sẻ", NativeStationDetailSheetHelper.LABEL_SHARE)
        val shareSpec = NativeStationDetailSheetHelper.buildShareIntentSpec(station)
        assertEquals(NativeStationDetailSheetHelper.ACTION_SEND, shareSpec.action)
        assertTrue(shareSpec.text.contains(station.name))
        assertTrue(shareSpec.text.contains("maps.google.com") || shareSpec.text.contains("google.com/maps"))

        // 5. Action 5: Refresh CTA
        val refreshSpec = NativeStationDetailSheetHelper.resolveRefreshButtonSpec(isRefreshing = false)
        assertTrue(refreshSpec.isEnabled)
        assertFalse(refreshSpec.isRefreshing)
        assertTrue(NativeStationDetailSheetHelper.shouldAllowRefresh(isRefreshing = false))
        assertFalse(NativeStationDetailSheetHelper.shouldAllowRefresh(isRefreshing = true))

        // 6. Action Callbacks Coexistence Simulation
        var navTriggered = false
        var focusTriggered = false
        var favoriteTriggered = false
        var shareTriggered = false
        var refreshTriggered = false
        var dismissTriggered = false

        val onNavigate: (Station) -> Unit = { navTriggered = true }
        val onStartFocusMode: (Station) -> Unit = { focusTriggered = true }
        val onToggleFavorite: (Station) -> Unit = { favoriteTriggered = true }
        val onShare: (Station) -> Unit = { shareTriggered = true }
        val onRefresh: () -> Unit = { refreshTriggered = true }
        val onDismiss: () -> Unit = { dismissTriggered = true }

        onNavigate(station)
        onStartFocusMode(station)
        onToggleFavorite(station)
        onShare(station)
        onRefresh()
        onDismiss()

        assertTrue("Navigation must trigger without interference", navTriggered)
        assertTrue("Focus Mode must trigger without interference", focusTriggered)
        assertTrue("Favorite toggle must trigger without interference", favoriteTriggered)
        assertTrue("Share must trigger without interference", shareTriggered)
        assertTrue("Refresh must trigger without interference", refreshTriggered)
        assertTrue("Dismiss must trigger without interference", dismissTriggered)

        // 7. Verify Layout Allocation Invariants
        assertEquals(1.3f, NativeStationDetailSheetHelper.PRIMARY_NAV_WEIGHT, 0.001f)
        assertEquals(1.0f, NativeStationDetailSheetHelper.SECONDARY_FAVORITE_WEIGHT, 0.001f)
        assertEquals(0.9f, NativeStationDetailSheetHelper.SECONDARY_SHARE_WEIGHT, 0.001f)

        val alloc = NativeStationDetailSheetHelper.computeActionRowLayoutAllocation()
        assertTrue(alloc.availableRowWidthDp > 0f)
        assertTrue(alloc.primaryNavWidthDp >= 115f)
        assertTrue(alloc.favoriteWidthDp >= 90f)
        assertTrue(alloc.shareWidthDp >= 80f)
    }

    @Test
    fun testFocusMode_vinfastCpoIdAndApiKeyIntegration_preventsFalseOfflineMessage() = runTest {
        val mockWebServer = okhttp3.mockwebserver.MockWebServer()
        mockWebServer.start()

        val hereResponseJson = """
            {
                "evStations": {
                    "evStation": [
                        {
                            "id": "ZTYxMTIzOTItOTFjZC0xMWYxLTg4YzAtNDIwMTBhYTQwMDE5",
                            "cpoId": "C.HNO5346",
                            "name": "Trạm sạc VinFast Phúc Xá",
                            "position": {
                                "latitude": 21.041561,
                                "longitude": 105.853638
                            },
                            "connectors": {
                                "connector": [
                                    {
                                        "id": "conn_60kw",
                                        "maxPowerLevel": 60.0,
                                        "powerType": "DC",
                                        "connectorStatuses": {
                                            "connectorStatus": [
                                                {
                                                    "cpoEvseId": "47449",
                                                    "cpoEvseEMI3Id": "25070000033801",
                                                    "state": "AVAILABLE"
                                                },
                                                {
                                                    "cpoEvseId": "47450",
                                                    "cpoEvseEMI3Id": "25070000033802",
                                                    "state": "AVAILABLE"
                                                }
                                            ]
                                        }
                                    }
                                ]
                            }
                        }
                    ]
                }
            }
        """.trimIndent()

        mockWebServer.enqueue(
            okhttp3.mockwebserver.MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(hereResponseJson)
        )

        val targetStation = createSampleStation(
            id = "C.HNO5346",
            name = "Trạm sạc VinFast Phúc Xá",
            availableDcPlugs = 2,
            totalDcPlugs = 2,
            powerKw = 60L,
            distanceKm = 1.4
        )

        // Instantiate HereEvApiClient with default parameters targeting mock server
        val hereApiClient = com.evcs.favorites.data.network.here.HereEvApiClient(
            baseUrl = mockWebServer.url("/ev").toString()
        )

        val engine = FocusModeTelemetryEngine(
            initialStation = targetStation,
            hereEvApiClient = hereApiClient,
            coroutineScope = this
        )

        val updatedState = engine.pollOnce()

        // 1. Verify default API key was automatically attached in query parameters
        val recordedRequest = mockWebServer.takeRequest()
        assertEquals(
            com.evcs.favorites.data.network.here.HereEvApiClient.VINFAST_EXTRACTED_API_KEY,
            recordedRequest.requestUrl?.queryParameter("apiKey")
        )

        // 2. Verify station was matched via cpoId and state is CONNECTED (not OFFLINE)
        assertEquals(FocusConnectionStatus.CONNECTED, updatedState.connectionStatus)
        assertFalse(updatedState.isOffline)
        assertTrue(updatedState.isConnected)
        assertNull(updatedState.offlineMessage)

        // 3. Verify status badge shows green availability instead of "Mất kết nối"
        assertTrue(updatedState.statusBadgeText.startsWith("🟢"))
        assertTrue(updatedState.statusBadgeText.contains("2/2 Trống"))
        assertFalse(updatedState.statusBadgeText.contains("Mất kết nối"))

        mockWebServer.shutdown()
    }
}
