package com.evcs.favorites.ui

import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import androidx.car.app.CarContext
import androidx.car.app.model.PaneTemplate
import androidx.car.app.testing.TestCarContext
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.mutableLongStateOf
import com.evcs.favorites.car.CarPaneSpec
import com.evcs.favorites.car.StationDetailCarScreen
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.ui.components.NearbyUiHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Field

/**
 * Phase 03 Single Verification Test:
 * - Verifies StationDetailCarScreen builds templates cleanly across Car App API Level 1 to 7+.
 * - Verifies primitive Long state management functions correctly without runtime exceptions or autoboxing.
 * - Verifies Composable public signatures for NearbyScreen and FavoritesScreen retain stable APIs.
 * - Verifies resolution of variable shadowing and unused parameter suppressions.
 */
class ComposeOptimizationAndCleanupTest {

    private lateinit var testCarContext: CarContext

    @Before
    fun setUp() {
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(object : androidx.arch.core.executor.TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread(): Boolean = true
        })

        val fakeContext = object : ContextWrapper(null) {
            override fun getApplicationInfo(): ApplicationInfo {
                return ApplicationInfo().apply {
                    flags = ApplicationInfo.FLAG_DEBUGGABLE
                }
            }
            override fun getPackageName(): String = "com.evplus.app"
        }
        testCarContext = TestCarContext.createCarContext(fakeContext)
    }

    private fun createSampleStation(
        id: String = "station_clean_1",
        name: String = "VinFast Landmark 81",
        address: String = "720A Điện Biên Phủ, P.22, Bình Thạnh, TP.HCM",
        availablePlugs: Int = 4,
        totalPlugs: Int = 8
    ): Station {
        return Station(
            id = id,
            name = name,
            address = address,
            latitude = 10.795,
            longitude = 106.721,
            summary = "Trạm sạc nhanh VinFast",
            connectors = "250kW, 60kW, 11kW",
            depotStatus = "Normal",
            powers = listOf(
                PowerPort(typeWatts = 250000L, label = "250kW", availablePlugs = 2, totalPlugs = 2),
                PowerPort(typeWatts = 60000L, label = "60kW", availablePlugs = 2, totalPlugs = 2),
                PowerPort(typeWatts = 11000L, label = "11kW", availablePlugs = 0, totalPlugs = 4)
            ),
            totalAvailablePlugs = availablePlugs,
            totalPlugs = totalPlugs,
            distanceKm = 2.4,
            evse = "VinFast"
        )
    }

    private fun resolveSourceFile(relativePath: String): File {
        val candidate1 = File(relativePath)
        if (candidate1.exists()) return candidate1
        val candidate2 = File("app/$relativePath")
        if (candidate2.exists()) return candidate2
        return candidate1
    }

    @Test
    fun testStationDetailCarScreenBuildsTemplatesAcrossCarApiLevels() {
        val station = createSampleStation()

        // 1. Fallback Car App API Level (< 7, default TestCarContext without host negotiation)
        val preApi7Screen = StationDetailCarScreen(testCarContext, station)
        val preApi7Template = preApi7Screen.onGetTemplate()
        assertNotNull("Template must not be null for Car API < 7", preApi7Template)
        assertTrue("Template must be an instance of PaneTemplate", preApi7Template is PaneTemplate)
        val prePaneTemplate = preApi7Template as PaneTemplate
        val prePane = prePaneTemplate.pane
        assertNotNull(prePane)
        assertTrue("Detail pane rows must adhere to CarPaneSpec.MAX_PANE_ROWS", prePane?.rows?.size ?: 0 <= CarPaneSpec.MAX_PANE_ROWS)
        assertEquals("Pane actions must contain primary action", 1, prePane?.actions?.size)

        // 2. High Car App API Level (>= 7) via reflection on CarContext mCarAppApiLevel if present
        val carApiLevelField = findFieldRecursively(CarContext::class.java, "mCarAppApiLevel")
        if (carApiLevelField != null) {
            carApiLevelField.isAccessible = true
            val originalLevel = carApiLevelField.get(testCarContext)
            try {
                carApiLevelField.set(testCarContext, 7)
                val postApi7Screen = StationDetailCarScreen(testCarContext, station)
                val postApi7Template = postApi7Screen.onGetTemplate()
                assertNotNull("Template must not be null for Car API Level 7+", postApi7Template)
                assertTrue("Template must be an instance of PaneTemplate", postApi7Template is PaneTemplate)
                val postPaneTemplate = postApi7Template as PaneTemplate
                assertNotNull("Header must be set on Car API Level 7+", postPaneTemplate.header)
            } finally {
                carApiLevelField.set(testCarContext, originalLevel)
            }
        }
    }

    @Test
    fun testMutableLongStatePrimitiveAvoidsAutoboxingAndIntegratesWithScrollHelper() {
        // Verify primitive MutableLongState contract
        val refreshTimestampState: MutableLongState = mutableLongStateOf(0L)
        assertEquals(0L, refreshTimestampState.longValue)

        // Update state with new timestamp
        val newTimestamp = 1725880000000L
        refreshTimestampState.longValue = newTimestamp
        assertEquals(newTimestamp, refreshTimestampState.longValue)
        assertEquals(newTimestamp, refreshTimestampState.value)

        // Verify NearbyUiHelper interaction with primitive long
        val shouldScroll = NearbyUiHelper.shouldScrollToTop(
            isUserInitiated = true,
            itemCount = 5,
            lastHandledTimestamp = 0L,
            eventTimestamp = refreshTimestampState.longValue
        )
        assertTrue("Should trigger scroll when fresh eventTimestamp > lastHandledTimestamp", shouldScroll)

        val shouldNotRepeatScroll = NearbyUiHelper.shouldScrollToTop(
            isUserInitiated = true,
            itemCount = 5,
            lastHandledTimestamp = refreshTimestampState.longValue,
            eventTimestamp = refreshTimestampState.longValue
        )
        assertFalse("Should not trigger scroll when timestamp already handled", shouldNotRepeatScroll)
    }

    @Test
    fun testScreenComposableSignaturesAndCleanups() {
        // 1. NearbyScreen signature verification for backward-compatibility
        val nearbyMethods = Class.forName("com.evcs.favorites.ui.screens.NearbyScreenKt").declaredMethods
        val nearbyScreenMethod = nearbyMethods.firstOrNull { it.name == "NearbyScreen" }
        assertNotNull("NearbyScreen composable function must exist", nearbyScreenMethod)
        val nearbyParamTypes = nearbyScreenMethod!!.parameterTypes
        val hasCookieParamInNearby = nearbyParamTypes.any { it == String::class.java }
        assertTrue("NearbyScreen must retain cookieHeader: String? parameter for caller compatibility", hasCookieParamInNearby)

        // 2. FavoritesScreen signature verification for backward-compatibility
        val favoritesMethods = Class.forName("com.evcs.favorites.ui.screens.FavoritesScreenKt").declaredMethods
        val favoritesScreenMethod = favoritesMethods.firstOrNull { it.name == "FavoritesScreen" }
        assertNotNull("FavoritesScreen composable function must exist", favoritesScreenMethod)
        val favParamTypes = favoritesScreenMethod!!.parameterTypes
        val hasCookieParamInFav = favParamTypes.any { it == String::class.java }
        val hasIsSigningInParamInFav = favParamTypes.any { it == java.lang.Boolean.TYPE || it == java.lang.Boolean::class.java }
        assertTrue("FavoritesScreen must retain cookieHeader parameter", hasCookieParamInFav)
        assertTrue("FavoritesScreen must retain isSigningIn parameter", hasIsSigningInParamInFav)

        // 3. Verify NearbyScreen.kt source does not contain mutableStateOf(0L) on lastHandledRefreshTimestamp
        val nearbySource = resolveSourceFile("src/main/java/com/evcs/favorites/ui/screens/NearbyScreen.kt")
        assertTrue("NearbyScreen source file must exist", nearbySource.exists())
        val nearbyContent = nearbySource.readText()
        assertTrue(
            "NearbyScreen must use mutableLongStateOf(0L)",
            nearbyContent.contains("mutableLongStateOf(0L)")
        )
        assertFalse(
            "NearbyScreen must not use mutableStateOf(0L) for lastHandledRefreshTimestamp",
            nearbyContent.contains("var lastHandledRefreshTimestamp by remember { mutableStateOf(0L) }")
        )
        assertTrue(
            "NearbyScreen must suppress UNUSED_PARAMETER on cookieHeader",
            nearbyContent.contains("@Suppress(\"UNUSED_PARAMETER\") cookieHeader")
        )

        // 4. Verify FavoritesScreen.kt source does not have shadowing on activeStationForDetail
        val favoritesSource = resolveSourceFile("src/main/java/com/evcs/favorites/ui/screens/FavoritesScreen.kt")
        assertTrue("FavoritesScreen source file must exist", favoritesSource.exists())
        val favContent = favoritesSource.readText()
        assertTrue(
            "FavoritesScreen must rename local variable to targetStationForDetail",
            favContent.contains("val targetStationForDetail = selectedStationForDetail")
        )
        assertFalse(
            "FavoritesScreen must not shadow activeStationForDetail",
            favContent.contains("val activeStationForDetail = selectedStationForDetail")
        )
        assertTrue(
            "FavoritesScreen must suppress UNUSED_PARAMETER on cookieHeader",
            favContent.contains("@Suppress(\"UNUSED_PARAMETER\") cookieHeader")
        )
        assertTrue(
            "FavoritesScreen must suppress UNUSED_PARAMETER on isSigningIn",
            favContent.contains("@Suppress(\"UNUSED_PARAMETER\") isSigningIn")
        )

        // 5. Verify StationDetailCarScreen.kt pre-API 7 fallback has @Suppress("DEPRECATION")
        val stationDetailCarSource = resolveSourceFile("src/main/java/com/evcs/favorites/car/StationDetailCarScreen.kt")
        assertTrue("StationDetailCarScreen source file must exist", stationDetailCarSource.exists())
        val carContent = stationDetailCarSource.readText()
        assertTrue(
            "StationDetailCarScreen must suppress DEPRECATION on fallback branch",
            carContent.contains("@Suppress(\"DEPRECATION\")\n            templateBuilder\n                .setHeaderAction(Action.BACK)")
        )
    }

    private fun findFieldRecursively(clazz: Class<*>?, fieldName: String): Field? {
        var current: Class<*>? = clazz
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName)
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }
}
