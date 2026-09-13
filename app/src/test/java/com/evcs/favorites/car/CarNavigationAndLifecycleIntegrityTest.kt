package com.evcs.favorites.car

import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import androidx.car.app.CarContext
import androidx.car.app.testing.TestCarContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.focus.FocusModeForegroundService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Phase 04 Comprehensive Single Verification Test:
 * - Verifies navigation Geo URI properly escapes/percent-encodes nested parentheses in station names.
 * - Verifies (0.0, 0.0) invalid coordinates are safely rejected by dispatcher and contract validator.
 * - Verifies high-power AC ports (e.g. 43kW AC) are correctly grouped under AC breakdown rather than DC.
 * - Verifies EvPlusCarSession lifecycle observer halts FocusModeForegroundService upon ON_DESTROY.
 */
class CarNavigationAndLifecycleIntegrityTest {

    private lateinit var testCarContext: CarContext

    @Before
    fun setUp() {
        ArchTaskExecutor.getInstance().setDelegate(object : TaskExecutor() {
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
        CarNavigationDispatcher.resetTestLaunchers()
        CarFocusModeBridge.resetTestStarter()
        EvPlusCarSession.resetTestStopper()
    }

    @After
    fun tearDown() {
        CarNavigationDispatcher.resetTestLaunchers()
        CarFocusModeBridge.resetTestStarter()
        EvPlusCarSession.resetTestStopper()
        ArchTaskExecutor.getInstance().setDelegate(null)
    }

    private fun createStation(
        name: String,
        lat: Double = 21.0313,
        lon: Double = 105.8152,
        powers: List<PowerPort> = emptyList()
    ): Station {
        return Station(
            id = "station_test_id",
            name = name,
            address = "Hanoi, Vietnam",
            latitude = lat,
            longitude = lon,
            summary = "Trạm sạc",
            connectors = "",
            depotStatus = "Normal",
            powers = powers,
            totalAvailablePlugs = 2,
            totalPlugs = 4,
            distanceKm = 1.5,
            evse = "VinFast"
        )
    }

    @Test
    fun testGeoUriParenthesesSanitizationAndContractCompliance() {
        // 1. Station name containing parentheses
        val stationWithParens = createStation("VinFast Landmark 81 (Trụ Sạc Nhanh Q.Bình Thạnh)")
        val navSpec = CarNavigationDispatcher.getCarNavigationIntentSpec(stationWithParens)

        val uri = navSpec.uriString
        assertTrue("URI must start with geo:0,0?q=", uri.startsWith("geo:0,0?q=21.0313,105.8152("))
        assertTrue("URI must end with )", uri.endsWith(")"))

        // Extract inside of outermost parentheses
        val firstParen = uri.indexOf("(")
        val lastParen = uri.lastIndexOf(")")
        val innerLabel = uri.substring(firstParen + 1, lastParen)

        // Ensure inner label does NOT contain raw '(' or ')'
        assertFalse("Inner label must not contain raw '('", innerLabel.contains("("))
        assertFalse("Inner label must not contain raw ')'", innerLabel.contains(")"))
        assertTrue("Inner label must contain percent-encoded '%28'", innerLabel.contains("%28"))
        assertTrue("Inner label must contain percent-encoded '%29'", innerLabel.contains("%29"))

        // Decoded label must restore original text
        val decoded = URLDecoder.decode(innerLabel, StandardCharsets.UTF_8.name())
        assertEquals("VinFast Landmark 81 (Trụ Sạc Nhanh Q.Bình Thạnh)", decoded)

        // 2. Validate compliance against AndroidAutoContractValidator
        val contractResult = AndroidAutoContractValidator.validateCarNavigationIntent(navSpec.action, uri)
        assertTrue(
            "Sanitized URI must pass contract validation: ${contractResult.errors.joinToString()}",
            contractResult.isValid
        )

        // 3. Negative validation: Raw unescaped nested parentheses must be rejected by contract validator
        val unescapedUri = "geo:0,0?q=21.0313,105.8152(VinFast (Hanoi Center))"
        val negativeResult = AndroidAutoContractValidator.validateCarNavigationIntent(CarContext.ACTION_NAVIGATE, unescapedUri)
        assertFalse("Unescaped nested parentheses in geo URI must fail validation", negativeResult.isValid)
        assertTrue(negativeResult.errors.any { it.contains("pattern") })
    }

    @Test
    fun testZeroCoordinatesSafelyRejected() {
        val zeroStation = createStation("Invalid Coordinate Station", lat = 0.0, lon = 0.0)

        var carAppDispatched = false
        var activityDispatched = false
        var focusModeDispatched = false

        CarNavigationDispatcher.testCarAppSpecLauncher = { carAppDispatched = true }
        CarNavigationDispatcher.testActivitySpecLauncher = { activityDispatched = true }
        CarFocusModeBridge.testServiceSpecStarter = { focusModeDispatched = true }

        // 1. startNavigation must reject (0.0, 0.0) and return false
        val startResult = CarNavigationDispatcher.startNavigation(testCarContext, zeroStation)
        assertFalse("startNavigation must return false for (0.0, 0.0) coordinates", startResult)
        assertFalse("Car app navigation must not be launched", carAppDispatched)
        assertFalse("Activity fallback must not be launched", activityDispatched)
        assertFalse("Focus mode service must not be started", focusModeDispatched)

        // 2. rerouteNavigation must also reject (0.0, 0.0) and return false
        val rerouteResult = CarNavigationDispatcher.rerouteNavigation(testCarContext, zeroStation)
        assertFalse("rerouteNavigation must return false for (0.0, 0.0) coordinates", rerouteResult)
        assertFalse("Car app navigation must not be launched on reroute", carAppDispatched)
        assertFalse("Focus mode service must not be rerouted", focusModeDispatched)

        // 3. Contract validator must also reject (0.0, 0.0) navigation URIs
        val zeroUri = CarNavigationDispatcher.buildCarNavigationUriString(0.0, 0.0, "Zero Station")
        val contractResult = AndroidAutoContractValidator.validateCarNavigationIntent(CarContext.ACTION_NAVIGATE, zeroUri)
        assertFalse("Contract validator must reject (0.0, 0.0) destination coordinates", contractResult.isValid)
        assertTrue(contractResult.errors.any { it.contains("(0.0, 0.0)") })
    }

    @Test
    fun testHighPowerAcPortsCorrectlyGroupedUnderAcBreakdown() {
        // High-power 43kW AC port (43,000W > 30,000W DC threshold)
        val ac43Port = PowerPort(typeWatts = 43000L, label = "43kW AC", availablePlugs = 1, totalPlugs = 2)
        val dc60Port = PowerPort(typeWatts = 60000L, label = "60kW DC", availablePlugs = 2, totalPlugs = 4)
        val ac11Port = PowerPort(typeWatts = 11000L, label = "11kW", availablePlugs = 2, totalPlugs = 2)
        val dc20Port = PowerPort(typeWatts = 20000L, label = "20kW DC", availablePlugs = 1, totalPlugs = 1)

        // 1. Direct port classification
        assertFalse("43kW port with explicit 'AC' label must be classified as AC", CarStationFormatter.isDcPort(ac43Port))
        assertTrue("60kW DC port must be classified as DC", CarStationFormatter.isDcPort(dc60Port))
        assertFalse("11kW standard port must be classified as AC", CarStationFormatter.isDcPort(ac11Port))
        assertTrue("20kW port with 'DC' label must be classified as DC", CarStationFormatter.isDcPort(dc20Port))

        // 2. Station breakdown formatting
        val station = createStation(
            name = "Trạm Hỗn Hợp AC/DC",
            powers = listOf(ac43Port, dc60Port, ac11Port, dc20Port)
        )

        val acBreakdown = CarStationFormatter.formatAcBreakdown(station)
        val dcBreakdown = CarStationFormatter.formatDcBreakdown(station)

        assertTrue("AC breakdown must include 43kW AC", acBreakdown.contains("43kW AC"))
        assertTrue("AC breakdown must include 11kW", acBreakdown.contains("11kW"))
        assertFalse("AC breakdown must not include DC ports", acBreakdown.contains("60kW DC"))

        assertTrue("DC breakdown must include 60kW DC", dcBreakdown.contains("60kW DC"))
        assertTrue("DC breakdown must include 20kW DC", dcBreakdown.contains("20kW DC"))
        assertFalse("DC breakdown must NOT include 43kW AC", dcBreakdown.contains("43kW AC"))

        // 3. Pane spec row verification
        val paneSpec = CarStationFormatter.buildPaneSpec(station)
        assertEquals(4, paneSpec.rows.size)
        val rowDc = paneSpec.rows[2]
        val rowAc = paneSpec.rows[3]

        assertEquals("Sạc nhanh DC", rowDc.title)
        assertFalse(rowDc.subtitle?.contains("43kW AC") == true)
        assertTrue(rowDc.subtitle?.contains("60kW DC") == true)

        assertEquals("Sạc chuẩn AC", rowAc.title)
        assertTrue(rowAc.subtitle?.contains("43kW AC") == true)
    }

    @Test
    fun testSessionOnDestroyTerminatesFocusModeForegroundService() {
        val session = EvPlusCarSession()

        var capturedSpec: com.evcs.favorites.focus.FocusServiceIntentSpec? = null
        var stopperCalled = false
        EvPlusCarSession.testServiceSpecStopper = { spec ->
            capturedSpec = spec
        }
        EvPlusCarSession.testServiceStopper = {
            stopperCalled = true
        }

        // Transition session lifecycle to ON_CREATE then ON_DESTROY
        val registry = session.lifecycle as? LifecycleRegistry
        assertNotNull("Session lifecycle must be a LifecycleRegistry", registry)
        registry?.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry?.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

        assertNotNull("Stop intent specification must be dispatched on session ON_DESTROY", capturedSpec)
        assertEquals(
            "Dispatched action must be ACTION_STOP",
            FocusModeForegroundService.ACTION_STOP,
            capturedSpec?.action
        )
        assertEquals(
            "Target class must be FocusModeForegroundService",
            FocusModeForegroundService::class.java,
            capturedSpec?.targetClass
        )
        assertTrue("testServiceStopper must be invoked", stopperCalled)
    }
}
