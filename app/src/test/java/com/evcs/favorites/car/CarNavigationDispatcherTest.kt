package com.evcs.favorites.car

import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import androidx.car.app.CarContext
import androidx.car.app.model.Action
import androidx.car.app.model.Row
import androidx.car.app.testing.TestCarContext
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.preferences.FocusModePreferences
import com.evcs.favorites.focus.AlternativeStationRecommendation
import com.evcs.favorites.focus.FocusModeForegroundService
import com.evcs.favorites.focus.FocusModeVoiceAlertPolicy
import com.evcs.favorites.focus.FocusVoiceAlert
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Single comprehensive verification test for Phase 03:
 * - Verifies generated in-car navigation intent uses [CarContext.ACTION_NAVIGATE] and geo: URI format with station label query.
 * - Verifies fallback navigation intent generates valid google.navigation: URI with mode=d.
 * - Verifies CarFocusModeBridge serializes Station model and produces correct ACTION_START / ACTION_REROUTE intents.
 * - Verifies Voice TTS alert trigger conditions across all 3 automotive scenarios (0 vacant ports, alternative station, 2km proximity)
 *   and preference muting.
 * - Verifies error recovery when in-car navigation host throws exception or is unavailable, seamlessly falling back to mobile.
 * - Verifies screen action integration on StationDetailCarScreen and MainCarScreen.
 */
class CarNavigationDispatcherTest {

    private lateinit var testCarContext: CarContext
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

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
    }

    @After
    fun tearDown() {
        CarNavigationDispatcher.resetTestLaunchers()
        CarFocusModeBridge.resetTestStarter()
        ArchTaskExecutor.getInstance().setDelegate(null)
    }

    private fun createSampleStation(
        id: String = "station_vinfast_01",
        name: String = "VinFast Landmark 81",
        address: String = "720A Điện Biên Phủ, P.22, Bình Thạnh, TP.HCM",
        latitude: Double = 10.7950,
        longitude: Double = 106.7210,
        availablePlugs: Int = 4,
        totalPlugs: Int = 8,
        powers: List<PowerPort> = listOf(
            PowerPort(typeWatts = 250000L, label = "250kW", availablePlugs = 2, totalPlugs = 4),
            PowerPort(typeWatts = 60000L, label = "60kW", availablePlugs = 2, totalPlugs = 4)
        ),
        summary: String = "Trạm sạc nhanh VinFast",
        connectors: String = "250kW, 60kW",
        depotStatus: String = "Normal",
        distanceKm: Double? = 1.8,
        evse: String = "VinFast"
    ): Station {
        return Station(
            id = id,
            name = name,
            address = address,
            latitude = latitude,
            longitude = longitude,
            summary = summary,
            connectors = connectors,
            depotStatus = depotStatus,
            powers = powers,
            totalAvailablePlugs = availablePlugs,
            totalPlugs = totalPlugs,
            distanceKm = distanceKm,
            evse = evse
        )
    }

    @Test
    fun testInCarNavigationIntent_usesCarContextActionNavigateAndGeoUri() {
        val station = createSampleStation(
            name = "VinFast Trạm Sạc Q.1",
            latitude = 10.7769,
            longitude = 106.7009
        )

        val spec = CarNavigationDispatcher.getCarNavigationIntentSpec(station)

        // 1. Must use official Android Auto cross-app navigation action
        assertEquals("androidx.car.app.action.NAVIGATE", spec.action)
        assertEquals(CarContext.ACTION_NAVIGATE, spec.action)
        assertTrue("Car spec must be flagged as in-car app", spec.isCarApp)

        // 2. Must format geo: URI with 0,0 and query coordinates + encoded label
        val uriStr = spec.uriString
        assertTrue("In-car URI must start with geo:0,0?q=", uriStr.startsWith("geo:0,0?q="))
        assertTrue("In-car URI must contain coordinates", uriStr.contains("10.7769,106.7009"))

        // Extract label from query and decode to ensure Vietnamese accents are preserved
        val labelStart = uriStr.indexOf("(")
        val labelEnd = uriStr.lastIndexOf(")")
        assertTrue("In-car URI must include parentheses for label query", labelStart != -1 && labelEnd > labelStart)
        val encodedLabel = uriStr.substring(labelStart + 1, labelEnd)
        val decodedLabel = URLDecoder.decode(encodedLabel, StandardCharsets.UTF_8.name())
        assertEquals("VinFast Trạm Sạc Q.1", decodedLabel)

        // 3. Verify intent conversion
        val intent = spec.toIntent()
        assertNotNull("Intent object must be successfully created", intent)

        // 4. Test station with blank name falls back to raw coordinates without empty parentheses
        val blankNameStation = station.copy(name = "   ")
        val blankSpec = CarNavigationDispatcher.getCarNavigationIntentSpec(blankNameStation)
        assertEquals("geo:0,0?q=10.7769,106.7009", blankSpec.uriString)
    }

    @Test
    fun testFallbackNavigationIntent_generatesValidGoogleNavigationUri() {
        val station = createSampleStation(
            latitude = 10.7950,
            longitude = 106.7210
        )

        val fallbackSpec = CarNavigationDispatcher.getFallbackNavigationIntentSpec(station)

        // 1. Must use standard ACTION_VIEW for mobile navigation
        assertEquals("android.intent.action.VIEW", fallbackSpec.action)
        assertEquals(Intent.ACTION_VIEW, fallbackSpec.action)
        assertFalse("Fallback spec must be flagged as mobile/external", fallbackSpec.isCarApp)

        // 2. Must generate exact google.navigation URI format with mode=d (driving)
        val expectedUri = "google.navigation:q=10.795,106.721&mode=d"
        assertEquals(expectedUri, fallbackSpec.uriString)

        // 3. Verify intent conversion
        val intent = fallbackSpec.toIntent()
        assertNotNull("Fallback intent object must be successfully created", intent)
    }

    @Test
    fun testCarFocusModeBridge_serializesStationModelAndProducesCorrectIntents() {
        val station = createSampleStation(
            id = "vinfast_binh_thanh_01",
            name = "VinFast Landmark 81 Sạc Nhanh",
            latitude = 10.7950,
            longitude = 106.7210
        )

        // 1. Startup Intent Spec Verification
        val startSpec = CarFocusModeBridge.getStartIntentSpec(station)
        assertEquals(FocusModeForegroundService.ACTION_START, startSpec.action)
        assertEquals(FocusModeForegroundService::class.java, startSpec.targetClass)
        assertNotNull("Payload JSON must not be null", startSpec.payloadJson)

        val deserializedStation = json.decodeFromString(Station.serializer(), startSpec.payloadJson!!)
        assertEquals(station.id, deserializedStation.id)
        assertEquals(station.name, deserializedStation.name)
        assertEquals(station.latitude, deserializedStation.latitude, 0.0001)
        assertEquals(station.longitude, deserializedStation.longitude, 0.0001)

        // 2. Reroute Intent Spec Verification
        val altStation = createSampleStation(
            id = "vinfast_alt_02",
            name = "VinFast Thảo Điền",
            latitude = 10.8032,
            longitude = 106.7350
        )
        val rerouteSpec = CarFocusModeBridge.getRerouteIntentSpec(altStation)
        assertEquals(FocusModeForegroundService.ACTION_REROUTE, rerouteSpec.action)
        assertEquals(FocusModeForegroundService::class.java, rerouteSpec.targetClass)
        assertNotNull("Payload JSON must not be null", rerouteSpec.payloadJson)

        val deserializedAlt = json.decodeFromString(Station.serializer(), rerouteSpec.payloadJson!!)
        assertEquals(altStation.id, deserializedAlt.id)
        assertEquals(altStation.name, deserializedAlt.name)

        // 3. Intent creation helpers verification
        val startIntent = CarFocusModeBridge.createStartIntent(testCarContext, station)
        assertNotNull("Start intent must be created", startIntent)

        val rerouteIntent = CarFocusModeBridge.createRerouteIntent(testCarContext, altStation)
        assertNotNull("Reroute intent must be created", rerouteIntent)
    }

    @Test
    fun testVoiceTtsAlertTriggerConditions_allThreeAutomotiveScenarios() {
        var simulatedTime = 1_000_000L
        val policy = CarFocusModeBridge.createVoiceAlertPolicy(
            context = null,
            initialAvailableSlots = 3,
            initialDistanceKm = 5.0,
            debounceWindowMs = 20_000L,
            clock = { simulatedTime }
        )

        // -----------------------------------------------------------------------------------------
        // Scenario 1: Destination station becomes completely full (transitions to 0 vacant ports)
        // -----------------------------------------------------------------------------------------
        val fullAlert = CarFocusModeBridge.evaluateStationFullAlert(
            policy = policy,
            previousSlots = 3,
            currentSlots = 0,
            timestamp = simulatedTime
        )
        assertNotNull("Must trigger voice alert when station becomes full", fullAlert)
        assertEquals(FocusVoiceAlert.STATION_FULL, fullAlert)
        assertEquals(FocusModeVoiceAlertPolicy.ALERT_TEXT_STATION_FULL, fullAlert?.text)
        assertEquals("Cảnh báo: Trạm sạc vừa hết chỗ!", fullAlert?.text)

        // Steady state (remains 0) does not re-trigger
        val steadyFullAlert = CarFocusModeBridge.evaluateStationFullAlert(
            policy = policy,
            previousSlots = 0,
            currentSlots = 0,
            timestamp = simulatedTime + 1000L
        )
        assertNull("Steady state (0 to 0) must not trigger alert", steadyFullAlert)

        // -----------------------------------------------------------------------------------------
        // Scenario 2: System discovers alternative station with vacant ports while active is full
        // -----------------------------------------------------------------------------------------
        simulatedTime += 25_000L // Exceed debounce window
        val altRecommendation = AlternativeStationRecommendation(
            station = createSampleStation(name = "VinFast Cantavil", availablePlugs = 4),
            distanceKm = 1.5,
            matchingPowerWatts = 60000L,
            availableDcSlots = 4,
            totalDcSlots = 6
        )
        val altAlert = CarFocusModeBridge.evaluateAlternativeFoundAlert(
            policy = policy,
            targetSlots = 0,
            recommendation = altRecommendation,
            timestamp = simulatedTime
        )
        assertNotNull("Must trigger alternative station voice announcement", altAlert)
        assertEquals(FocusVoiceAlert.ALTERNATIVE_FOUND, altAlert)
        assertTrue(altAlert?.text?.contains("Trạm hiện tại đã hết trụ") == true)
        assertTrue(altAlert?.text?.contains("1.5 km còn 4 trụ trống") == true)

        // -----------------------------------------------------------------------------------------
        // Scenario 3: Vehicle approaches within 2km of destination station
        // -----------------------------------------------------------------------------------------
        simulatedTime += 25_000L
        // Outside 2km (e.g. 2.8km) -> no proximity alert yet
        val farProximity = CarFocusModeBridge.evaluateProximityAlert(
            policy = policy,
            distanceKm = 2.8,
            timestamp = simulatedTime
        )
        assertNull("Distance > 2km must not trigger arrival alert", farProximity)

        // Approaches to 1.8km (crosses 2.0km boundary) -> triggers arrival voice prompt
        simulatedTime += 10_000L
        val nearProximity = CarFocusModeBridge.evaluateProximityAlert(
            policy = policy,
            distanceKm = 1.8,
            timestamp = simulatedTime
        )
        assertNotNull("Distance <= 2km boundary crossing must trigger arrival prompt", nearProximity)
        assertEquals(FocusVoiceAlert.PROXIMITY_REMINDER, nearProximity)
        assertTrue(nearProximity?.text?.contains("Sắp đến trạm sạc") == true)
        assertTrue(nearProximity?.text?.contains("1.8 km") == true)

        // Staying inside 2km (e.g. 1.2km) does not repeat the arrival reminder
        simulatedTime += 25_000L
        val insideProximity = CarFocusModeBridge.evaluateProximityAlert(
            policy = policy,
            distanceKm = 1.2,
            timestamp = simulatedTime
        )
        assertNull("Subsequent updates inside 2km threshold must not re-trigger proximity reminder", insideProximity)

        // -----------------------------------------------------------------------------------------
        // Mute / Voice Alert Disabled Preference Verification
        // -----------------------------------------------------------------------------------------
        policy.isMuted = true
        simulatedTime += 50_000L

        val mutedFullAlert = CarFocusModeBridge.evaluateStationFullAlert(
            policy = policy,
            previousSlots = 2,
            currentSlots = 0,
            timestamp = simulatedTime
        )
        assertNull("When voice alerts are muted, station full alert must return null", mutedFullAlert)

        val mutedAltAlert = CarFocusModeBridge.evaluateAlternativeFoundAlert(
            policy = policy,
            targetSlots = 0,
            recommendation = altRecommendation,
            timestamp = simulatedTime
        )
        assertNull("When voice alerts are muted, alternative found alert must return null", mutedAltAlert)

        val mutedProximity = CarFocusModeBridge.evaluateProximityAlert(
            policy = policy,
            distanceKm = 1.5,
            timestamp = simulatedTime
        )
        assertNull("When voice alerts are muted, proximity reminder must return null", mutedProximity)
    }

    @Test
    fun testNavigationHostDispatch_successAndRerouteFlow() {
        val station = createSampleStation()
        val altStation = createSampleStation(
            id = "station_alt",
            name = "VinFast Quận 2",
            latitude = 10.8000,
            longitude = 106.7300
        )

        var launchedInCarSpec: CarNavigationIntentSpec? = null
        var launchedServiceSpec: com.evcs.favorites.focus.FocusServiceIntentSpec? = null

        CarNavigationDispatcher.testCarAppSpecLauncher = { launchedInCarSpec = it }
        CarFocusModeBridge.testServiceSpecStarter = { launchedServiceSpec = it }

        // 1. Initial Navigation Dispatch
        val startResult = CarNavigationDispatcher.startNavigation(testCarContext, station)
        assertTrue("In-car navigation dispatch must return true when successful", startResult)
        assertNotNull("In-car intent spec must be launched", launchedInCarSpec)
        assertEquals(CarContext.ACTION_NAVIGATE, launchedInCarSpec?.action)
        assertTrue(launchedInCarSpec?.uriString?.startsWith("geo:0,0?q=") == true)
        assertTrue(launchedInCarSpec?.isCarApp == true)

        assertNotNull("FocusModeForegroundService must be simultaneously started", launchedServiceSpec)
        assertEquals(FocusModeForegroundService.ACTION_START, launchedServiceSpec?.action)
        assertNotNull(launchedServiceSpec?.payloadJson)

        // 2. Reroute Dispatch
        var reroutedInCarSpec: CarNavigationIntentSpec? = null
        var reroutedServiceSpec: com.evcs.favorites.focus.FocusServiceIntentSpec? = null
        CarNavigationDispatcher.testCarAppSpecLauncher = { reroutedInCarSpec = it }
        CarFocusModeBridge.testServiceSpecStarter = { reroutedServiceSpec = it }

        val rerouteResult = CarNavigationDispatcher.rerouteNavigation(testCarContext, altStation)
        assertTrue("Reroute navigation dispatch must return true when successful", rerouteResult)
        assertNotNull("In-car reroute intent spec must be launched", reroutedInCarSpec)
        assertEquals(CarContext.ACTION_NAVIGATE, reroutedInCarSpec?.action)
        assertTrue(reroutedInCarSpec?.uriString?.contains("10.8,106.73") == true)
        assertTrue(reroutedInCarSpec?.isCarApp == true)

        assertNotNull("FocusModeForegroundService must receive reroute intent", reroutedServiceSpec)
        assertEquals(FocusModeForegroundService.ACTION_REROUTE, reroutedServiceSpec?.action)
        assertNotNull(reroutedServiceSpec?.payloadJson)
    }

    @Test
    fun testNavigationHostUnavailable_fallbackRecovery() {
        val station = createSampleStation()

        var fallbackSpec: CarNavigationIntentSpec? = null
        var serviceSpec: com.evcs.favorites.focus.FocusServiceIntentSpec? = null

        // In-car host throws exception (e.g. HostException / not in projected mode)
        CarNavigationDispatcher.testCarAppSpecLauncher = {
            throw IllegalStateException("Android Auto projection host is currently unavailable")
        }
        CarNavigationDispatcher.testActivitySpecLauncher = { fallbackSpec = it }
        CarFocusModeBridge.testServiceSpecStarter = { serviceSpec = it }

        val startResult = CarNavigationDispatcher.startNavigation(testCarContext, station)

        // 1. In-car result is false indicating fallback was triggered
        assertFalse("Must return false when in-car host fails and fallback is triggered", startResult)

        // 2. Mobile fallback activity intent spec was successfully dispatched
        assertNotNull("Mobile fallback intent spec must be launched", fallbackSpec)
        assertEquals(Intent.ACTION_VIEW, fallbackSpec?.action)
        val expectedFallbackUri = "google.navigation:q=${station.latitude},${station.longitude}&mode=d"
        assertEquals(expectedFallbackUri, fallbackSpec?.uriString)
        assertFalse("Fallback must be flagged as non-car/mobile", fallbackSpec?.isCarApp == true)

        // 3. Mobile telemetry service is STILL started despite in-car host failure
        assertNotNull("Focus mode service must still start to ensure mobile telemetry and voice alerts work", serviceSpec)
        assertEquals(FocusModeForegroundService.ACTION_START, serviceSpec?.action)
    }

    @Test
    fun testCarScreensActionIntegration_stationDetailAndMainCarScreen() {
        val station = createSampleStation()

        // 1. StationDetailCarScreen primary action delegates to CarNavigationDispatcher
        var navigatedStation: Station? = null
        val detailScreen = StationDetailCarScreen(
            carContext = testCarContext,
            station = station,
            onNavigateAction = { navigatedStation = it }
        )
        detailScreen.dispatchNavigation(station)
        assertEquals(station.id, navigatedStation?.id)

        // 2. MainCarScreen station rows include direct navigation action and trigger navigation
        var directNavigatedStation: Station? = null
        val mainScreen = MainCarScreen(
            carContext = testCarContext,
            stationProvider = { listOf(station) },
            onNavigateAction = { directNavigatedStation = it }
        )

        val itemList = mainScreen.buildItemList()
        assertEquals(1, itemList.items.size)
        val firstRow = itemList.items[0] as Row

        // Row has action for 1-tap direct navigation
        assertEquals("Row must have direct navigation action", 1, firstRow.actions.size)
        val action = firstRow.actions[0]
        assertEquals(CarPaneSpec.ACTION_NAVIGATE_AND_MONITOR, action.title?.toString())

        // Trigger direct navigation
        mainScreen.navigateToStation(station)
        assertEquals(station.id, directNavigatedStation?.id)
    }
}
