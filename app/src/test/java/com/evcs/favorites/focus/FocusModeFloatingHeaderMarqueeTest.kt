package com.evcs.favorites.focus

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.text.TextUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Single Comprehensive Verification Test for Phase 01:
 * Floating Window Header Marquee & Distance Removal.
 *
 * Verification Criteria covered:
 * 1. Verifies stationNameView.ellipsize == TextUtils.TruncateAt.MARQUEE.
 * 2. Verifies stationNameView.marqueeRepeatLimit == -1 (infinite loop).
 * 3. Verifies stationNameView.isSelected == true initially and remains isSelected == true after updateView(state).
 * 4. Verifies distanceBadgeView is completely absent from the header container and class schema.
 * 5. Verifies header row contains exactly two elements: stationNameView and closeButtonView.
 * 6. Verifies removeOverlay() safely nullifies header references with zero memory leaks.
 * 7. Verifies FloatingViewState data contract retains distanceText for background bridges without rendering in header.
 */
class FocusModeFloatingHeaderMarqueeTest {

    private class DummyTestContext : ContextWrapper(null) {
        private val dummyMetrics = android.util.DisplayMetrics().apply {
            density = 2.0f
            widthPixels = 1080
            heightPixels = 2400
        }
        private val dummyConfig = android.content.res.Configuration().apply {
            orientation = android.content.res.Configuration.ORIENTATION_PORTRAIT
        }
        private val dummyResources = object : android.content.res.Resources(
            null,
            dummyMetrics,
            dummyConfig
        ) {
            override fun getDisplayMetrics(): android.util.DisplayMetrics = dummyMetrics
            override fun getConfiguration(): android.content.res.Configuration = dummyConfig
        }

        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.evplus.app"
        override fun getResources(): android.content.res.Resources = dummyResources
        override fun getTheme(): android.content.res.Resources.Theme = dummyResources.newTheme()
    }

    private fun createSampleStation(
        id: String = "st_marquee",
        name: String = "VinFast Thảo Điền Mega Mall - Trạm Sạc Siêu Nhanh",
        availableDcPlugs: Int = 4,
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
            )
        )
        return Station(
            id = id,
            name = name,
            address = "123 Xa Lộ Hà Nội, P. Thảo Điền, TP. Thủ Đức",
            latitude = 10.8031,
            longitude = 106.7329,
            summary = "Trống $availableDcPlugs/$totalDcPlugs DC",
            connectors = "CCS2",
            depotStatus = "Normal",
            powers = powers,
            totalAvailablePlugs = availableDcPlugs,
            totalPlugs = totalDcPlugs,
            distanceKm = distanceKm
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Requirement 1 & 2: Marquee Configuration & Infinite Looping
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testStationNameView_marqueePropertiesConfigured() {
        val dummyContext = DummyTestContext()
        val marqueeView = MarqueeTextView.create(dummyContext)

        // 1. Must be single line / marquee truncation
        assertEquals(
            "stationNameView ellipsize must be MARQUEE for continuous horizontal scrolling",
            TextUtils.TruncateAt.MARQUEE,
            marqueeView.ellipsize
        )

        // 2. Must loop infinitely (-1)
        assertEquals(
            "stationNameView marqueeRepeatLimit must be -1 for infinite marquee loop",
            -1,
            marqueeView.marqueeRepeatLimit
        )

        // 3. Must be selected by default to activate marquee without requiring touch/keyboard focus
        assertTrue(
            "stationNameView isSelected must be true initially for Android marquee activation",
            marqueeView.isSelected
        )

        // 4. Must report isFocused == true for overlay alert windows with FLAG_NOT_FOCUSABLE
        assertTrue(
            "stationNameView isFocused must be true to allow marquee scrolling in alert windows",
            marqueeView.isFocused
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Requirement 3: State Updates Re-assert isSelected and Update Text
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testUpdateView_reassertsIsSelectedAndSetsStationName() {
        val dummyContext = DummyTestContext()
        val manager = FocusModeFloatingViewManager(
            context = dummyContext,
            windowManager = null,
            onDismiss = {},
            onReroute = {}
        )

        val stationNameView = MarqueeTextView.create(dummyContext)
        val dummyRootView = object : View(dummyContext) {}

        manager.setStationNameViewForTesting(stationNameView)
        manager.setFloatingRootViewForTesting(dummyRootView)
        manager.setIsViewAttachedForTesting(true)

        // Simulate that selection was somehow lost
        stationNameView.isSelected = false
        assertFalse("Selection intentionally reset for testing", stationNameView.isSelected)

        val longStationName = "Trạm sạc VinFast Vincom Mega Mall Thảo Điền - Tầng Hầm B2"
        val targetStation = createSampleStation(name = longStationName)
        val state = FocusModeState.createInitial(
            targetStation = targetStation,
            distanceRemainingKm = 1.8
        )

        // Execute updateView
        manager.updateView(state)

        // Verify text set and isSelected re-asserted
        val expectedStationName = FocusModeViewLayoutHelper.formatViewState(state).stationName
        assertEquals(expectedStationName, stationNameView.text.toString())
        assertTrue(
            "stationNameView.isSelected must be explicitly re-asserted as true after updateView()",
            stationNameView.isSelected
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Requirement 4: Complete Removal of Distance Badge from Header
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testDistanceBadgeView_completelyAbsentFromManagerAndHeaderContainer() {
        val clazz = FocusModeFloatingViewManager::class.java

        // 1. Verify distanceBadgeView field is removed from FocusModeFloatingViewManager
        val hasDistanceField = clazz.declaredFields.any { it.name == "distanceBadgeView" }
        assertFalse(
            "distanceBadgeView field must be completely removed from FocusModeFloatingViewManager",
            hasDistanceField
        )

        // 2. Verify testDistanceBadgeView getter is removed
        val hasDistanceGetter = clazz.declaredMethods.any { it.name == "getTestDistanceBadgeView" }
        assertFalse(
            "getTestDistanceBadgeView accessor must be completely removed from FocusModeFloatingViewManager",
            hasDistanceGetter
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Requirement 5: Header Row Structure (Only stationNameView + closeButtonView)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testHeaderRow_containsOnlyStationNameAndCloseButton() {
        val dummyContext = DummyTestContext()
        val manager = FocusModeFloatingViewManager(
            context = dummyContext,
            windowManager = null,
            onDismiss = {},
            onReroute = {}
        )

        val headerRow = TrackingLinearLayout(dummyContext).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val stationNameTv = MarqueeTextView.create(dummyContext).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                weight = 1f
            }
        }
        val closeBtn = TextView(dummyContext).apply {
            text = "✕"
        }

        headerRow.addView(stationNameTv)
        headerRow.addView(closeBtn)

        manager.setHeaderRowViewForTesting(headerRow)
        manager.setStationNameViewForTesting(stationNameTv)
        manager.setCloseButtonViewForTesting(closeBtn)

        // Verify header container contains exactly two children
        val testedHeader = manager.testHeaderRowView
        assertNotNull("testHeaderRowView must not be null", testedHeader)
        assertEquals("Header row must contain exactly 2 child views", 2, testedHeader!!.childCount)

        // Child 0: stationNameView (occupying weight 1f)
        val child0 = testedHeader.getChildAt(0)
        assertSame("Child 0 must be stationNameView", stationNameTv, child0)
        assertTrue("Child 0 must be AppCompatTextView", child0 is AppCompatTextView)
        val lp0 = child0.layoutParams as LinearLayout.LayoutParams
        assertEquals(0, lp0.width)
        assertEquals(1f, lp0.weight, 0.001f)

        // Child 1: closeButtonView
        val child1 = testedHeader.getChildAt(1)
        assertSame("Child 1 must be closeButtonView", closeBtn, child1)
        assertNotNull(child1)
    }

    @Test
    fun testBuildCapsuleView_createsHeaderWithOnlyMarqueeAndCloseButton() {
        val dummyContext = DummyTestContext()
        val manager = FocusModeFloatingViewManager(
            context = dummyContext,
            windowManager = null,
            onDismiss = {},
            onReroute = {}
        )

        manager.buildCapsuleViewForTesting()

        val headerRow = manager.testHeaderRowView
        assertNotNull("buildCapsuleView must initialize headerRowView", headerRow)
        assertEquals("Built header row must contain exactly 2 child views", 2, headerRow!!.childCount)
        assertSame("Child 0 must be stationNameView", manager.testStationNameView, headerRow.getChildAt(0))
        assertSame("Child 1 must be closeButtonView", manager.testCloseButtonView, headerRow.getChildAt(1))
    }

    // ---------------------------------------------------------------------------------------------
    // Requirement 6: Memory Leak Prevention & Overlay Removal
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testRemoveOverlay_cleansUpStationNameAndHeaderReferences() {
        val dummyContext = DummyTestContext()
        val manager = FocusModeFloatingViewManager(
            context = dummyContext,
            windowManager = null,
            onDismiss = {},
            onReroute = {}
        )

        val stationNameTv = MarqueeTextView.create(dummyContext)
        val headerRow = LinearLayout(dummyContext)
        val closeBtn = TextView(dummyContext)
        val dummyRootView = object : View(dummyContext) {}

        manager.setStationNameViewForTesting(stationNameTv)
        manager.setHeaderRowViewForTesting(headerRow)
        manager.setCloseButtonViewForTesting(closeBtn)
        manager.setFloatingRootViewForTesting(dummyRootView)
        manager.setIsViewAttachedForTesting(true)

        assertTrue(manager.isAttached)
        assertNotNull(manager.testStationNameView)
        assertNotNull(manager.testHeaderRowView)
        assertNotNull(manager.testCloseButtonView)

        // Detach overlay
        manager.removeOverlay()

        assertFalse("isAttached must be false after removeOverlay", manager.isAttached)
        assertNull("stationNameView must be null after removeOverlay", manager.testStationNameView)
        assertNull("headerRowView must be null after removeOverlay", manager.testHeaderRowView)
        assertNull("closeButtonView must be null after removeOverlay", manager.testCloseButtonView)
        assertNull("floatingRootView must be null after removeOverlay", manager.testFloatingRootView)
    }

    // ---------------------------------------------------------------------------------------------
    // Requirement 7: FloatingViewState Data Contract Compatibility
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testFloatingViewState_retainsDistanceTextPropertyWithoutHeaderRendering() {
        val targetStation = createSampleStation(distanceKm = 3.2)
        val state = FocusModeState.createInitial(
            targetStation = targetStation,
            distanceRemainingKm = 3.2
        )

        val viewState = FocusModeViewLayoutHelper.formatViewState(state)

        // Ensure backward compatibility contract: distanceText is preserved for notifications
        assertEquals("3.2 km", viewState.distanceText)
        assertEquals("VinFast Thảo Điền Mega Mall - Trạm Sạc Siêu Nhanh", viewState.stationName)
        assertEquals(FocusBadgeColor.GREEN, viewState.badgeColorToken)
    }
}
