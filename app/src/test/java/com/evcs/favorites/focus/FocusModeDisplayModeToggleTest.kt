package com.evcs.favorites.focus

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Single Comprehensive Verification Test for Phase 02:
 * Mini Pill ⇄ Full HUD 1-Tap Toggle & Touch Interaction.
 *
 * Requirements covered:
 * 1. Mini Pill State Formatting (formatMiniPillState):
 *    - Normal state (availableDcSlots = 4) -> "🟢 4" with FocusBadgeColor.GREEN.
 *    - Saturated/Full state (availableDcSlots = 0) -> "🔴 0" with FocusBadgeColor.RED.
 *    - Offline state (OFFLINE) -> "⚠️ !" with FocusBadgeColor.AMBER.
 * 2. Mini Pill Dimensions & Styling Constants:
 *    - 80dp width, 38dp height, 20dp corner radius, 18sp Bold font.
 *    - Density-based width and height scaling (e.g. 1.0f, 1.5f, 2.0f, 3.0f).
 * 3. Gesture Discrimination Math:
 *    - Effective touch slop calculation with 12dp fallback floor.
 *    - 1-tap gesture detection: movement delta <= touchSlop AND duration < 350ms.
 *    - Drag gesture detection: Euclidean delta > touchSlop.
 *    - Boundary conditions: 350ms duration threshold and diagonal Euclidean delta.
 * 4. Adaptive Edge Snapping on Mode Switch:
 *    - Snapped right in Full HUD (width 400px, x=664 on 1080px screen) collapsing to Mini Pill (width 160px)
 *      correctly recalculates x to 1080 - 160 - 16 = 904px.
 *    - Snapped right in Mini Pill (width 160px, x=904) expanding to Full HUD (width 400px)
 *      correctly recalculates x to 1080 - 400 - 16 = 664px.
 *    - Snapped left keeps x at margin (16px) across both modes.
 * 5. Display Mode State Transitions:
 *    - FocusModeDisplayMode values (MINI_PILL, FULL_HUD).
 *    - FocusModeFloatingViewManager initial default is FULL_HUD.
 *    - toggleDisplayMode() seamlessly toggles between FULL_HUD and MINI_PILL.
 *    - setDisplayMode() sets explicit modes.
 */
class FocusModeDisplayModeToggleTest {

    private class DummyTestContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    private fun createSampleStation(
        id: String = "st_01",
        name: String = "VinFast Test Station",
        availableDcPlugs: Int = 4,
        totalDcPlugs: Int = 8,
        powerKw: Long = 150L
    ): Station {
        val powers = listOf(
            PowerPort(
                typeWatts = powerKw * 1000L,
                label = "${powerKw}kW DC",
                availablePlugs = availableDcPlugs,
                totalPlugs = totalDcPlugs
            )
        )
        return Station(
            id = id,
            name = name,
            address = "123 Hanoi Street",
            latitude = 21.0285,
            longitude = 105.8542,
            summary = "Trống $availableDcPlugs/$totalDcPlugs DC",
            connectors = "CCS2",
            depotStatus = "Normal",
            powers = powers,
            totalAvailablePlugs = powers.sumOf { it.availablePlugs },
            totalPlugs = powers.sumOf { it.totalPlugs },
            distanceKm = 2.5
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Requirement 1: Mini Pill State Formatting (formatMiniPillState)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testFormatMiniPillState_outputsCleanStatusDotAndSlotCount() {
        // 1. Normal State: 4 slots available -> "🟢 4" with GREEN token
        val normalStation = createSampleStation(availableDcPlugs = 4, totalDcPlugs = 8)
        val normalState = FocusModeState.createInitial(normalStation)
        val (normalText, normalColor) = FocusModeViewLayoutHelper.formatMiniPillState(normalState)
        assertEquals("🟢 4", normalText)
        assertEquals(FocusBadgeColor.GREEN, normalColor)

        // 2. Normal State with 1 slot available
        val singleSlotStation = createSampleStation(availableDcPlugs = 1, totalDcPlugs = 6)
        val singleSlotState = FocusModeState.createInitial(singleSlotStation)
        val (singleText, singleColor) = FocusModeViewLayoutHelper.formatMiniPillState(singleSlotState)
        assertEquals("🟢 1", singleText)
        assertEquals(FocusBadgeColor.GREEN, singleColor)

        // 3. Full / Saturated State: 0 slots available -> "🔴 0" with RED token
        val fullStation = createSampleStation(availableDcPlugs = 0, totalDcPlugs = 8)
        val fullState = FocusModeState.createInitial(fullStation)
        val (fullText, fullColor) = FocusModeViewLayoutHelper.formatMiniPillState(fullState)
        assertEquals("🔴 0", fullText)
        assertEquals(FocusBadgeColor.RED, fullColor)

        // 4. Offline State: Connection lost -> "⚠️ !" with AMBER token
        val offlineState = normalState.copy(connectionStatus = FocusConnectionStatus.OFFLINE)
        val (offlineText, offlineColor) = FocusModeViewLayoutHelper.formatMiniPillState(offlineState)
        assertEquals("⚠️ !", offlineText)
        assertEquals(FocusBadgeColor.AMBER, offlineColor)
    }

    // ---------------------------------------------------------------------------------------------
    // Requirement 2: Mini Pill Dimensions & Typography Constants
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testMiniPillDimensionsAndTypographySpecs() {
        // Constants verification
        assertEquals(80, FocusModeViewLayoutHelper.MINI_PILL_WIDTH_DP)
        assertEquals(38, FocusModeViewLayoutHelper.MINI_PILL_HEIGHT_DP)
        assertEquals(20, FocusModeViewLayoutHelper.MINI_PILL_CORNER_RADIUS_DP)
        assertEquals(18f, FocusModeViewLayoutHelper.MINI_PILL_TEXT_SIZE_SP, 0.0f)
        assertEquals(350L, FocusModeViewLayoutHelper.TAP_MAX_DURATION_MS)
        assertEquals(12, FocusModeViewLayoutHelper.TOUCH_SLOP_FALLBACK_DP)

        // Width calculations across display densities
        assertEquals(80, FocusModeViewLayoutHelper.calculateMiniPillWidth(1.0f))
        assertEquals(120, FocusModeViewLayoutHelper.calculateMiniPillWidth(1.5f))
        assertEquals(160, FocusModeViewLayoutHelper.calculateMiniPillWidth(2.0f))
        assertEquals(240, FocusModeViewLayoutHelper.calculateMiniPillWidth(3.0f))

        // Height calculations across display densities
        assertEquals(38, FocusModeViewLayoutHelper.calculateMiniPillHeight(1.0f))
        assertEquals(57, FocusModeViewLayoutHelper.calculateMiniPillHeight(1.5f))
        assertEquals(76, FocusModeViewLayoutHelper.calculateMiniPillHeight(2.0f))
        assertEquals(114, FocusModeViewLayoutHelper.calculateMiniPillHeight(3.0f))
    }

    // ---------------------------------------------------------------------------------------------
    // Requirement 3: Gesture Discrimination Math
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testEffectiveTouchSlopCalculation_enforcesFallbackFloor() {
        // Density = 1.0f: floor is 12dp * 1.0 = 12px
        assertEquals(12, FocusModeViewLayoutHelper.calculateEffectiveTouchSlop(scaledTouchSlop = 8, density = 1.0f))
        assertEquals(16, FocusModeViewLayoutHelper.calculateEffectiveTouchSlop(scaledTouchSlop = 16, density = 1.0f))

        // Density = 2.0f: floor is 12dp * 2.0 = 24px
        assertEquals(24, FocusModeViewLayoutHelper.calculateEffectiveTouchSlop(scaledTouchSlop = 16, density = 2.0f))
        assertEquals(32, FocusModeViewLayoutHelper.calculateEffectiveTouchSlop(scaledTouchSlop = 32, density = 2.0f))
    }

    @Test
    fun testGestureDiscriminationMath_classifiesClicksVsDrags() {
        val touchSlop = 16

        // Case A: Pure stationary tap (dx=0, dy=0, duration=100ms) -> TAP
        assertTrue(
            "Stationary tap under 350ms must be classified as tap",
            FocusModeViewLayoutHelper.isTapGesture(dx = 0, dy = 0, durationMs = 100L, touchSlop = touchSlop)
        )
        assertFalse(
            "Stationary touch is not a drag",
            FocusModeViewLayoutHelper.isDragGesture(dx = 0, dy = 0, touchSlop = touchSlop)
        )

        // Case B: Subtle jitter below touch slop (dx=6, dy=8 -> Euclidean delta = sqrt(36+64) = 10 <= 16, duration=250ms) -> TAP
        assertTrue(
            "Jitter below touchSlop within 350ms is a tap",
            FocusModeViewLayoutHelper.isTapGesture(dx = 6, dy = 8, durationMs = 250L, touchSlop = touchSlop)
        )
        assertFalse(
            "Jitter below touchSlop is not a drag",
            FocusModeViewLayoutHelper.isDragGesture(dx = 6, dy = 8, touchSlop = touchSlop)
        )

        // Case C: Exactly at touch slop boundary (dx=16, dy=0 -> delta = 16 <= 16, duration=349ms) -> TAP
        assertTrue(
            "Movement exactly at touchSlop within 350ms is a tap",
            FocusModeViewLayoutHelper.isTapGesture(dx = 16, dy = 0, durationMs = 349L, touchSlop = touchSlop)
        )
        assertFalse(
            "Movement at touchSlop boundary is not a drag",
            FocusModeViewLayoutHelper.isDragGesture(dx = 16, dy = 0, touchSlop = touchSlop)
        )

        // Case D: Exceeds duration threshold (dx=0, dy=0, duration=350ms / 400ms) -> NOT TAP (long press / hold)
        assertFalse(
            "Duration >= 350ms must NOT be classified as tap",
            FocusModeViewLayoutHelper.isTapGesture(dx = 0, dy = 0, durationMs = 350L, touchSlop = touchSlop)
        )
        assertFalse(
            "Duration 400ms must NOT be classified as tap",
            FocusModeViewLayoutHelper.isTapGesture(dx = 0, dy = 0, durationMs = 400L, touchSlop = touchSlop)
        )

        // Case E: Exceeds touch slop (dx=20, dy=0 -> delta = 20 > 16) -> DRAG, NOT TAP
        assertTrue(
            "Movement exceeding touchSlop is a drag",
            FocusModeViewLayoutHelper.isDragGesture(dx = 20, dy = 0, touchSlop = touchSlop)
        )
        assertFalse(
            "Movement exceeding touchSlop is not a tap even if duration < 350ms",
            FocusModeViewLayoutHelper.isTapGesture(dx = 20, dy = 0, durationMs = 150L, touchSlop = touchSlop)
        )

        // Case F: Diagonal movement exceeding touch slop (dx=12, dy=12 -> Euclidean delta = sqrt(144+144) = sqrt(288) approx 16.97 > 16) -> DRAG
        assertTrue(
            "Diagonal movement exceeding touchSlop is a drag",
            FocusModeViewLayoutHelper.isDragGesture(dx = 12, dy = 12, touchSlop = touchSlop)
        )
        assertFalse(
            "Diagonal movement exceeding touchSlop is not a tap",
            FocusModeViewLayoutHelper.isTapGesture(dx = 12, dy = 12, durationMs = 200L, touchSlop = touchSlop)
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Requirement 4: Adaptive Edge Snapping on Mode Switch
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testAdaptiveEdgeSnappingOnModeChange_recalculatesXCorrectly() {
        val screenWidth = 1080
        val margin = 16

        // Case 1: Snapped right in Full HUD (width 400px, x=664 on 1080px screen) collapsing to Mini Pill (width 160px)
        // Center = 664 + 200 = 864 >= 540 (right half)
        // Expected newX = 1080 - 160 - 16 = 904px
        val collapsedX = FocusModeViewLayoutHelper.calculateAdjustedXOnModeChange(
            currentX = 664,
            oldWidth = 400,
            newWidth = 160,
            screenWidth = screenWidth,
            margin = margin
        )
        assertEquals(904, collapsedX)

        // Case 2: Snapped right in Mini Pill (width 160px, x=904) expanding to Full HUD (width 400px)
        // Center = 904 + 80 = 984 >= 540 (right half)
        // Expected newX = 1080 - 400 - 16 = 664px
        val expandedX = FocusModeViewLayoutHelper.calculateAdjustedXOnModeChange(
            currentX = 904,
            oldWidth = 160,
            newWidth = 400,
            screenWidth = screenWidth,
            margin = margin
        )
        assertEquals(664, expandedX)

        // Case 3: Snapped left in Full HUD (width 400px, x=16) collapsing to Mini Pill (width 160px)
        // Center = 16 + 200 = 216 < 540 (left half)
        // Expected newX = margin = 16px
        val leftCollapsedX = FocusModeViewLayoutHelper.calculateAdjustedXOnModeChange(
            currentX = 16,
            oldWidth = 400,
            newWidth = 160,
            screenWidth = screenWidth,
            margin = margin
        )
        assertEquals(16, leftCollapsedX)

        // Case 4: Snapped left in Mini Pill (width 160px, x=16) expanding to Full HUD (width 400px)
        // Center = 16 + 80 = 96 < 540 (left half)
        // Expected newX = margin = 16px
        val leftExpandedX = FocusModeViewLayoutHelper.calculateAdjustedXOnModeChange(
            currentX = 16,
            oldWidth = 160,
            newWidth = 400,
            screenWidth = screenWidth,
            margin = margin
        )
        assertEquals(16, leftExpandedX)

        // Case 5: Custom margin support (margin = 24px)
        val customMarginRight = FocusModeViewLayoutHelper.calculateAdjustedXOnModeChange(
            currentX = 800,
            oldWidth = 160,
            newWidth = 400,
            screenWidth = screenWidth,
            margin = 24
        )
        assertEquals(1080 - 400 - 24, customMarginRight)
    }

    // ---------------------------------------------------------------------------------------------
    // Requirement 5: Display Mode State Transitions & Floating View Manager
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testDisplayModeEnumAndInitialDefault() {
        val modes = FocusModeDisplayMode.values()
        assertEquals(2, modes.size)
        assertTrue(modes.contains(FocusModeDisplayMode.MINI_PILL))
        assertTrue(modes.contains(FocusModeDisplayMode.FULL_HUD))

        val dummyContext = DummyTestContext()
        val manager = FocusModeFloatingViewManager(
            context = dummyContext,
            windowManager = null,
            onDismiss = {},
            onReroute = {}
        )

        // Initial default mode must be FULL_HUD
        assertEquals(FocusModeDisplayMode.FULL_HUD, manager.displayMode)
    }

    @Test
    fun testToggleDisplayMode_switchesBetweenMiniPillAndFullHud() {
        val dummyContext = DummyTestContext()
        val manager = FocusModeFloatingViewManager(
            context = dummyContext,
            windowManager = null,
            onDismiss = {},
            onReroute = {}
        )

        assertEquals(FocusModeDisplayMode.FULL_HUD, manager.displayMode)

        // Toggle 1: FULL_HUD -> MINI_PILL
        val modeAfterFirstToggle = manager.toggleDisplayMode()
        assertEquals(FocusModeDisplayMode.MINI_PILL, modeAfterFirstToggle)
        assertEquals(FocusModeDisplayMode.MINI_PILL, manager.displayMode)

        // Toggle 2: MINI_PILL -> FULL_HUD
        val modeAfterSecondToggle = manager.toggleDisplayMode()
        assertEquals(FocusModeDisplayMode.FULL_HUD, modeAfterSecondToggle)
        assertEquals(FocusModeDisplayMode.FULL_HUD, manager.displayMode)

        // Explicit setDisplayMode
        manager.setDisplayMode(FocusModeDisplayMode.MINI_PILL)
        assertEquals(FocusModeDisplayMode.MINI_PILL, manager.displayMode)

        manager.setDisplayMode(FocusModeDisplayMode.FULL_HUD)
        assertEquals(FocusModeDisplayMode.FULL_HUD, manager.displayMode)
    }

    @Test
    fun testDualContainerArchitecturalStructure_exposedForInspection() {
        val dummyContext = DummyTestContext()
        val manager = FocusModeFloatingViewManager(
            context = dummyContext,
            windowManager = null,
            onDismiss = {},
            onReroute = {}
        )

        // Verify class exposes required dual-container and child button accessors
        val clazz = manager.javaClass
        assertNotNull(clazz.getMethod("toggleDisplayMode"))
        assertNotNull(clazz.getMethod("setDisplayMode", FocusModeDisplayMode::class.java))
        assertNotNull(clazz.getMethod("getDisplayMode"))
        assertNotNull(clazz.getMethod("getTestMiniPillContainer"))
        assertNotNull(clazz.getMethod("getTestMiniPillTextView"))
        assertNotNull(clazz.getMethod("getTestFullHudContainer"))
        assertNotNull(clazz.getMethod("getTestCloseButtonView"))
        assertNotNull(clazz.getMethod("getTestRerouteButtonView"))
    }
}
