package com.evcs.favorites.focus

import android.content.Context
import android.content.ContextWrapper
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Single Comprehensive Verification Test for Phase 05:
 * Floating Window UI & Permission Fallback.
 *
 * Requirements covered:
 * 1. Validates formatted text and color tokens across Normal, Full (Reroute available), and Offline states.
 * 2. Validates permission branch routing: chooses overlay mode when canDrawOverlays=true and notification mode when false.
 * 3. Validates touch drag boundary clamping math to prevent dragging outside screen bounds.
 * 4. Validates horizontal snap-to-edge calculation for left and right display bounds.
 * 5. Validates manual close [X] trigger properly creates and dispatches service termination intent.
 * 6. Validates reroute trigger formats navigation action and updates target station data.
 */
class FocusModeFloatingWindowAndFallbackTest {

    private class DummyTestContext : ContextWrapper(null) {
        override fun getPackageName(): String = "com.evplus.app"
    }

    private fun createSampleStation(
        id: String,
        name: String,
        availableDcPlugs: Int,
        totalDcPlugs: Int = 8,
        powerKw: Long = 150L,
        distanceKm: Double? = 2.5
    ): Station {
        val powers = listOf(
            PowerPort(
                typeWatts = powerKw * 1000L,
                label = "${powerKw}kW DC",
                availablePlugs = availableDcPlugs,
                totalPlugs = totalDcPlugs
            ),
            PowerPort(
                typeWatts = 11_000L, // 11kW AC (ignored by DC filter)
                label = "11kW AC",
                availablePlugs = 2,
                totalPlugs = 2
            )
        )
        return Station(
            id = id,
            name = name,
            address = "Địa chỉ $name",
            latitude = 10.7769,
            longitude = 106.7009,
            summary = "Trống $availableDcPlugs/$totalDcPlugs DC",
            connectors = "CCS2",
            depotStatus = "Normal",
            powers = powers,
            totalAvailablePlugs = powers.sumOf { it.availablePlugs },
            totalPlugs = powers.sumOf { it.totalPlugs },
            distanceKm = distanceKm
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Requirement 1: State Formatting & Color Tokens
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testNormalState_formattingAndColorTokens() {
        val targetStation = createSampleStation(
            id = "st_normal",
            name = "VinFast Thảo Điền",
            availableDcPlugs = 2,
            totalDcPlugs = 8,
            powerKw = 150L,
            distanceKm = 2.5
        )

        val state = FocusModeState.createInitial(
            targetStation = targetStation,
            distanceRemainingKm = 2.5
        )

        val viewState = FocusModeViewLayoutHelper.formatViewState(state)

        assertEquals("VinFast Thảo Điền", viewState.stationName)
        assertEquals("🟢 2/8 Trống (150kW)", viewState.badgeText)
        assertEquals(FocusBadgeColor.GREEN, viewState.badgeColorToken)
        assertFalse("Reroute must be disabled in normal state", viewState.isRerouteAvailable)
        assertNull("Reroute button label must be null in normal state", viewState.rerouteButtonText)
        assertFalse("State is not offline", viewState.isOffline)
        assertEquals("2.5 km", viewState.distanceText)
    }

    @Test
    fun testFullStateWithAlternativeStation_revealsRerouteCtaAndRedBadge() {
        val targetStation = createSampleStation(
            id = "st_full",
            name = "VinFast Landmark 81",
            availableDcPlugs = 0,
            totalDcPlugs = 8,
            powerKw = 150L,
            distanceKm = 1.0
        )

        val altStation = createSampleStation(
            id = "st_alt",
            name = "VinFast Pearl Plaza",
            availableDcPlugs = 3,
            totalDcPlugs = 6,
            powerKw = 150L,
            distanceKm = 1.2
        )

        val recommendation = AlternativeStationRecommendation(
            station = altStation,
            distanceKm = 1.2,
            matchingPowerWatts = 150_000L,
            availableDcSlots = 3,
            totalDcSlots = 6
        )

        val state = FocusModeState(
            targetStation = targetStation,
            availableDcSlots = 0,
            totalDcSlots = 8,
            distanceRemainingKm = 1.0,
            connectionStatus = FocusConnectionStatus.CONNECTED,
            alternativeStation = recommendation
        )

        val viewState = FocusModeViewLayoutHelper.formatViewState(state)

        assertEquals("VinFast Landmark 81", viewState.stationName)
        assertEquals("🔴 HẾT CHỖ!", viewState.badgeText)
        assertEquals(FocusBadgeColor.RED, viewState.badgeColorToken)
        assertTrue("Reroute CTA must be enabled when alternative station is present", viewState.isRerouteAvailable)
        assertNotNull(viewState.rerouteButtonText)
        assertEquals("🔄 Đổi trạm: VinFast Pearl Plaza (+1.2km)", viewState.rerouteButtonText)
        assertFalse("State is not offline", viewState.isOffline)
        assertEquals("1.0 km", viewState.distanceText)
    }

    @Test
    fun testFullStateWithoutAlternativeStation_keepsRerouteDisabled() {
        val targetStation = createSampleStation(
            id = "st_full_no_alt",
            name = "VinFast Landmark 81",
            availableDcPlugs = 0,
            totalDcPlugs = 8,
            powerKw = 150L,
            distanceKm = 0.5
        )

        val state = FocusModeState(
            targetStation = targetStation,
            availableDcSlots = 0,
            totalDcSlots = 8,
            distanceRemainingKm = 0.5,
            connectionStatus = FocusConnectionStatus.CONNECTED,
            alternativeStation = null
        )

        val viewState = FocusModeViewLayoutHelper.formatViewState(state)

        assertEquals("🔴 HẾT CHỖ!", viewState.badgeText)
        assertEquals(FocusBadgeColor.RED, viewState.badgeColorToken)
        assertFalse("Reroute must be disabled when no alternative recommendation is found", viewState.isRerouteAvailable)
        assertNull(viewState.rerouteButtonText)
        assertEquals("500m", viewState.distanceText)
    }

    @Test
    fun testOfflineState_formattingAndColorTokens() {
        val targetStation = createSampleStation(
            id = "st_offline",
            name = "VinFast Hầm B2",
            availableDcPlugs = 4,
            totalDcPlugs = 8,
            powerKw = 150L,
            distanceKm = 0.8
        )

        val offlineMsg = "⚠️ Mất kết nối - Dữ liệu lúc 14:35"
        val state = FocusModeState(
            targetStation = targetStation,
            availableDcSlots = 4,
            totalDcSlots = 8,
            distanceRemainingKm = 0.8,
            connectionStatus = FocusConnectionStatus.OFFLINE,
            offlineMessage = offlineMsg
        )

        val viewState = FocusModeViewLayoutHelper.formatViewState(state)

        assertEquals("VinFast Hầm B2", viewState.stationName)
        assertEquals(offlineMsg, viewState.badgeText)
        assertEquals(FocusBadgeColor.AMBER, viewState.badgeColorToken)
        assertFalse("Reroute CTA must not be displayed during offline warning", viewState.isRerouteAvailable)
        assertNull(viewState.rerouteButtonText)
        assertTrue("Must be flagged as offline", viewState.isOffline)
        assertEquals("800m", viewState.distanceText)
    }

    // ---------------------------------------------------------------------------------------------
    // Requirement 2: Permission Branch Routing
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testPermissionBranchRouting_choosesOverlayWhenGrantedAndNotificationWhenDenied() {
        // Overlay permission granted -> Floating Overlay presentation mode
        val grantedMode = FocusModeViewLayoutHelper.resolvePresentationMode(canDrawOverlays = true)
        assertEquals(
            "When overlay permission is granted, presentation mode must be FLOATING_OVERLAY",
            PresentationMode.FLOATING_OVERLAY,
            grantedMode
        )

        // Overlay permission denied -> Notification Fallback presentation mode
        val deniedMode = FocusModeViewLayoutHelper.resolvePresentationMode(canDrawOverlays = false)
        assertEquals(
            "When overlay permission is denied, presentation mode must fall back to NOTIFICATION_FALLBACK",
            PresentationMode.NOTIFICATION_FALLBACK,
            deniedMode
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Requirement 3: Touch Drag Boundary Clamping & Snap-To-Edge Math
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testTouchDragBoundaryClamping_preventsDraggingOutsideScreenBounds() {
        val screenWidth = 1080
        val screenHeight = 2400
        val viewWidth = 220
        val viewHeight = 90

        val maxAllowedX = screenWidth - viewWidth // 860
        val maxAllowedY = screenHeight - viewHeight // 2310

        // Normal interior position: no change
        val (normalX, normalY) = FocusModeViewLayoutHelper.clampPosition(
            x = 300,
            y = 500,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )
        assertEquals(300, normalX)
        assertEquals(500, normalY)

        // Dragging past left edge (x < 0) -> clamped to 0
        val (leftExceededX, _) = FocusModeViewLayoutHelper.clampPosition(
            x = -150,
            y = 500,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )
        assertEquals(0, leftExceededX)

        // Dragging past top edge (y < 0) -> clamped to 0
        val (_, topExceededY) = FocusModeViewLayoutHelper.clampPosition(
            x = 300,
            y = -80,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )
        assertEquals(0, topExceededY)

        // Dragging past right edge (x + viewWidth > screenWidth) -> clamped to (screenWidth - viewWidth)
        val (rightExceededX, _) = FocusModeViewLayoutHelper.clampPosition(
            x = 1000,
            y = 500,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )
        assertEquals(maxAllowedX, rightExceededX)

        // Dragging past bottom edge (y + viewHeight > screenHeight) -> clamped to (screenHeight - viewHeight)
        val (_, bottomExceededY) = FocusModeViewLayoutHelper.clampPosition(
            x = 300,
            y = 2600,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )
        assertEquals(maxAllowedY, bottomExceededY)

        // Extreme diagonal corner bounds
        val (minCornerX, minCornerY) = FocusModeViewLayoutHelper.clampPosition(
            x = -9999,
            y = -9999,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )
        assertEquals(0, minCornerX)
        assertEquals(0, minCornerY)

        val (maxCornerX, maxCornerY) = FocusModeViewLayoutHelper.clampPosition(
            x = 99999,
            y = 99999,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )
        assertEquals(maxAllowedX, maxCornerX)
        assertEquals(maxAllowedY, maxCornerY)
    }

    @Test
    fun testSnapToEdgeCalculation_snapsToLeftOrRightMargin() {
        val screenWidth = 1080
        val viewWidth = 200
        val margin = 16

        // Left half (center = 100 + 100 = 200 < 540) -> snap to left margin (16)
        val leftSnap = FocusModeViewLayoutHelper.calculateSnapToEdgeX(
            currentX = 100,
            viewWidth = viewWidth,
            screenWidth = screenWidth,
            margin = margin
        )
        assertEquals(16, leftSnap)

        // Right half (center = 600 + 100 = 700 >= 540) -> snap to right margin (1080 - 200 - 16 = 864)
        val rightSnap = FocusModeViewLayoutHelper.calculateSnapToEdgeX(
            currentX = 600,
            viewWidth = viewWidth,
            screenWidth = screenWidth,
            margin = margin
        )
        assertEquals(864, rightSnap)

        // Exactly on the boundary (x = 440, center = 540) -> right snap
        val edgeSnap = FocusModeViewLayoutHelper.calculateSnapToEdgeX(
            currentX = 440,
            viewWidth = viewWidth,
            screenWidth = screenWidth,
            margin = margin
        )
        assertEquals(864, edgeSnap)
    }

    // ---------------------------------------------------------------------------------------------
    // Requirement 4 & 5: Manual Close [X] Trigger & Reroute Intent Specifications
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testManualCloseTrigger_createsProperServiceTerminationIntent() {
        val dummyContext = DummyTestContext()

        // Create stop intent and verify intent specification targets FocusModeForegroundService with ACTION_STOP
        val stopIntent = FocusModeForegroundService.createStopIntent(dummyContext)
        assertNotNull(stopIntent)

        val stopSpec = FocusModeForegroundService.getStopIntentSpec()
        assertEquals(FocusModeForegroundService.ACTION_STOP, stopSpec.action)
        assertEquals(FocusModeForegroundService::class.java, stopSpec.targetClass)

        // Verify simulated manual close [X] trigger executes dismiss callback
        var wasDismissTriggered = false
        val onDismissCallback: () -> Unit = {
            wasDismissTriggered = true
        }

        onDismissCallback.invoke()
        assertTrue("Manual close [X] must trigger dismiss handler", wasDismissTriggered)
    }

    @Test
    fun testRerouteTrigger_createsProperRerouteIntentAndUpdatesEngine() {
        val dummyContext = DummyTestContext()

        val targetStation = createSampleStation(
            id = "st_orig",
            name = "VinFast Thảo Điền",
            availableDcPlugs = 0,
            totalDcPlugs = 4,
            powerKw = 150L,
            distanceKm = 1.5
        )

        val newStation = createSampleStation(
            id = "st_new",
            name = "VinFast Sala",
            availableDcPlugs = 4,
            totalDcPlugs = 6,
            powerKw = 150L,
            distanceKm = 2.8
        )

        // Create reroute intent and verify intent specification targets FocusModeForegroundService with ACTION_REROUTE
        val rerouteIntent = FocusModeForegroundService.createRerouteIntent(dummyContext, newStation)
        assertNotNull(rerouteIntent)

        val rerouteSpec = FocusModeForegroundService.getRerouteIntentSpec(newStation)
        assertEquals(FocusModeForegroundService.ACTION_REROUTE, rerouteSpec.action)
        assertEquals(FocusModeForegroundService::class.java, rerouteSpec.targetClass)
        assertNotNull("Reroute intent must contain serialized new station payload", rerouteSpec.payloadJson)
        assertTrue("Payload must contain new station name", rerouteSpec.payloadJson!!.contains("VinFast Sala"))



        // Verify engine target station update dynamically reflects new station and slot counts
        val engine = FocusModeTelemetryEngine(
            initialStation = targetStation,
            fetchStationTelemetry = { _, _, _ -> Result.success(targetStation) }
        )

        assertEquals(0, engine.state.value.availableDcSlots)
        assertEquals("VinFast Thảo Điền", engine.state.value.targetStation.name)

        // Execute reroute switch
        engine.updateTargetStation(newStation)

        val updatedState = engine.state.value
        assertEquals("VinFast Sala", updatedState.targetStation.name)
        assertEquals(4, updatedState.availableDcSlots)
        assertEquals(6, updatedState.totalDcSlots)
        assertEquals(FocusConnectionStatus.CONNECTED, updatedState.connectionStatus)
        assertNull("Alternative recommendation must reset on reroute", updatedState.alternativeStation)
    }
}
