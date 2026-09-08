package com.evcs.favorites.car

import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import androidx.car.app.CarContext
import androidx.car.app.ScreenManager
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.testing.TestCarContext
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.focus.FocusModeForegroundService
import com.evcs.favorites.focus.FocusModeVoiceAlertPolicy
import com.evcs.favorites.focus.FocusServiceIntentSpec
import com.evcs.favorites.focus.FocusVoiceAlert
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Comprehensive verification test for Phase 04:
 * - Verifies complete AndroidManifest.xml automotive contract (services, intent-filters, POI category,
 *   MAP_TEMPLATES permission, automotive app metadata, and Android 15 permissions).
 * - Verifies automotive descriptor XML schema (`<uses name="template" />`).
 * - Verifies screen backstack enforcement (<= 12 screens quota per Google Automotive App Quality guidelines).
 * - Verifies end-to-end flow from session creation -> main screen -> detail screen -> navigation intent
 *   dispatch (`CarContext.ACTION_NAVIGATE`) -> simultaneous Focus Mode background start.
 * - Verifies mobile fallback navigation intent schema (`Intent.ACTION_VIEW`, `google.navigation:`).
 * - Verifies Desktop Head Unit (DHU) port (5277) and ADB port forwarding contracts.
 * - Verifies Voice TTS alert policies and Audio Ducking integration flags.
 */
class AndroidAutoContractVerificationTest {

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
            override fun getPackageName(): String = "com.evcs.favorites"
        }
        testCarContext = TestCarContext.createCarContext(fakeContext)
        CarNavigationDispatcher.resetTestLaunchers()
        CarFocusModeBridge.resetTestStarter()
    }

    @After
    fun tearDown() {
        ArchTaskExecutor.getInstance().setDelegate(null)
        CarNavigationDispatcher.resetTestLaunchers()
        CarFocusModeBridge.resetTestStarter()
    }

    private fun findProjectRoot(): File {
        var dir: File = File(".").canonicalFile
        while (!File(dir, "app").exists() && dir.parentFile != null) {
            dir = dir.parentFile!!
        }
        return dir
    }

    private fun createSampleStation(
        id: String = "station_metropolis",
        name: String = "5.4km » Trạm sạc Vincom Metropolis",
        address: String = "29 Liễu Giai, Ba Đình, Hà Nội",
        lat: Double = 21.0313,
        lon: Double = 105.8152,
        availablePlugs: Int = 4,
        totalPlugs: Int = 8,
        powers: List<PowerPort> = listOf(
            PowerPort(typeWatts = 250000L, label = "250kW", availablePlugs = 2, totalPlugs = 2),
            PowerPort(typeWatts = 60000L, label = "60kW", availablePlugs = 2, totalPlugs = 2)
        )
    ): Station {
        return Station(
            id = id,
            name = name,
            address = address,
            latitude = lat,
            longitude = lon,
            summary = "Trạm sạc nhanh VinFast",
            connectors = "250kW, 60kW",
            depotStatus = "Normal",
            powers = powers,
            totalAvailablePlugs = availablePlugs,
            totalPlugs = totalPlugs,
            distanceKm = 1.8,
            evse = "VinFast"
        )
    }

    // =========================================================================================
    // 1. Automotive Descriptor XML Contract
    // =========================================================================================

    @Test
    fun testAutomotiveDescriptorContract() {
        val root = findProjectRoot()
        val descFile = File(root, "app/src/main/res/xml/automotive_app_desc.xml")
        val result = AndroidAutoContractValidator.validateDescriptorFile(descFile)

        assertTrue(
            "automotive_app_desc.xml failed validation: ${result.errors.joinToString()}",
            result.isValid
        )
        assertTrue(result.errors.isEmpty())

        // Validate rejection of missing template use
        val invalidXmlMissingTemplate = "<automotiveApp><uses name=\"media\" /></automotiveApp>"
        val invalidResult1 = AndroidAutoContractValidator.validateDescriptorXml(invalidXmlMissingTemplate)
        assertFalse("Validator must reject descriptor missing <uses name=\"template\" />", invalidResult1.isValid)
        assertTrue(invalidResult1.errors.any { it.contains("template") })

        // Validate rejection of wrong root element
        val invalidXmlWrongRoot = "<myApp><uses name=\"template\" /></myApp>"
        val invalidResult2 = AndroidAutoContractValidator.validateDescriptorXml(invalidXmlWrongRoot)
        assertFalse("Validator must reject descriptor with invalid root tag", invalidResult2.isValid)
        assertTrue(invalidResult2.errors.any { it.contains("automotiveApp") })
    }

    // =========================================================================================
    // 2. Android Manifest Automotive Contract & Android 15 Permissions
    // =========================================================================================

    @Test
    fun testAndroidManifestAutomotiveContract() {
        val root = findProjectRoot()
        val manifestFile = File(root, "app/src/main/AndroidManifest.xml")
        val result = AndroidAutoContractValidator.validateManifestFile(manifestFile)

        assertTrue(
            "AndroidManifest.xml failed automotive validation: ${result.errors.joinToString()}",
            result.isValid
        )
        assertTrue("Expected 0 validation errors, got: ${result.errors}", result.errors.isEmpty())
    }

    @Test
    fun testAndroid15PermissionsCompliance() {
        val fullPermissions = listOf(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.FOREGROUND_SERVICE_LOCATION",
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC"
        )
        val validResult = AndroidAutoContractValidator.validateAndroid15Permissions(fullPermissions)
        assertTrue(validResult.isValid)
        assertTrue(validResult.errors.isEmpty())

        val missingNotification = listOf(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.FOREGROUND_SERVICE_LOCATION",
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC"
        )
        val invalidResult = AndroidAutoContractValidator.validateAndroid15Permissions(missingNotification)
        assertFalse(invalidResult.isValid)
        assertTrue(invalidResult.errors.any { it.contains("POST_NOTIFICATIONS") })
    }

    // =========================================================================================
    // 3. Desktop Head Unit (DHU) Protocol & Port Forwarding Contract
    // =========================================================================================

    @Test
    fun testDhuCommunicationProtocolAndPortForwardingContract() {
        // Verify standard DHU TCP port 5277
        val validPort = AndroidAutoContractValidator.validateDhuPort(5277)
        assertTrue("Port 5277 must be valid for DHU communication", validPort.isValid)

        val invalidPort = AndroidAutoContractValidator.validateDhuPort(8080)
        assertFalse("Non-DHU port must fail validation", invalidPort.isValid)
        assertTrue(invalidPort.errors.any { it.contains("5277") })

        // Verify ADB port forwarding command
        val validCommand = "adb forward tcp:5277 tcp:5277"
        val validCommandResult = AndroidAutoContractValidator.validateDhuPortForwardingCommand(validCommand)
        assertTrue(validCommandResult.isValid)

        val invalidCommand = "adb forward tcp:8080 tcp:8080"
        val invalidCommandResult = AndroidAutoContractValidator.validateDhuPortForwardingCommand(invalidCommand)
        assertFalse(invalidCommandResult.isValid)
    }

    // =========================================================================================
    // 4. Screen Navigation Backstack Safety & Quota Enforcement (<= 12 screens)
    // =========================================================================================

    @Test
    fun testScreenBackstackQuotaAndSafetyEnforcement() {
        // Quota limits check
        for (depth in 1..12) {
            assertTrue(
                "Backstack depth $depth should be compliant (<= 12)",
                AndroidAutoContractValidator.validateScreenBackstackDepth(depth).isValid
            )
        }

        val zeroDepth = AndroidAutoContractValidator.validateScreenBackstackDepth(0)
        assertFalse("Depth 0 must be invalid", zeroDepth.isValid)

        val exceededDepth = AndroidAutoContractValidator.validateScreenBackstackDepth(13)
        assertFalse("Depth 13 must exceed Car App Library quota", exceededDepth.isValid)
        assertTrue(exceededDepth.errors.any { it.contains("quota of 12 screens") })

        // Runtime screen manager stack verification
        val screenManager = testCarContext.getCarService(ScreenManager::class.java)
        assertNotNull("ScreenManager must be available in CarContext", screenManager)

        val mainScreen = MainCarScreen(testCarContext)
        screenManager.push(mainScreen)
        assertEquals("Initial stack size must be 1", 1, screenManager.stackSize)
        assertTrue(AndroidAutoContractValidator.validateScreenBackstackDepth(screenManager.stackSize).isValid)

        val sampleStation = createSampleStation()
        val detailScreen = StationDetailCarScreen(testCarContext, sampleStation)
        screenManager.push(detailScreen)
        assertEquals("Stack size after push must be 2", 2, screenManager.stackSize)
        assertTrue(AndroidAutoContractValidator.validateScreenBackstackDepth(screenManager.stackSize).isValid)
    }

    // =========================================================================================
    // 5. End-to-End Flow: Session -> MainCarScreen -> Detail -> Nav Intent -> Focus Mode
    // =========================================================================================

    @Test
    fun testEndToEndSessionToNavigationAndFocusModeHandoff() {
        // Step 1: Session creation
        val session = EvPlusCarSession()
        val rootScreen = session.onCreateScreen(Intent())
        assertTrue("Session must create MainCarScreen as root screen", rootScreen is MainCarScreen)

        // Step 2: MainCarScreen provides PlaceListMapTemplate
        val station = createSampleStation()
        val mainScreen = MainCarScreen(testCarContext, stationProvider = { listOf(station) })
        val template = mainScreen.onGetTemplate()
        assertTrue("MainCarScreen must return PlaceListMapTemplate", template is PlaceListMapTemplate)

        // Step 3: Detail Screen provides PaneTemplate with primary action "⚡ DẪN ĐƯỜNG & THEO DÕI"
        val detailScreen = StationDetailCarScreen(testCarContext, station)
        val detailTemplate = detailScreen.onGetTemplate()
        assertTrue("Detail screen must return PaneTemplate", detailTemplate is PaneTemplate)
        val pane = (detailTemplate as PaneTemplate).pane
        assertNotNull(pane)
        val actions = pane.actions
        assertTrue(actions.isNotEmpty())
        assertEquals("⚡ DẪN ĐƯỜNG & THEO DÕI", actions[0].title?.toString())

        // Step 4: Dispatch navigation and verify CarNavigationIntentSpec & CarFocusModeBridge
        var capturedCarNavSpec: CarNavigationIntentSpec? = null
        var capturedFocusSpec: FocusServiceIntentSpec? = null

        CarNavigationDispatcher.testCarAppSpecLauncher = { spec ->
            capturedCarNavSpec = spec
        }
        CarFocusModeBridge.testServiceSpecStarter = { spec ->
            capturedFocusSpec = spec
        }

        // Trigger navigation from StationDetailCarScreen
        detailScreen.dispatchNavigation(station)

        // Step 5: Verify Car Navigation Intent specification and contract compliance
        assertNotNull("Car navigation spec must be dispatched", capturedCarNavSpec)
        assertEquals(CarContext.ACTION_NAVIGATE, capturedCarNavSpec!!.action)
        val carUri = capturedCarNavSpec!!.uriString
        assertTrue("Car URI must start with geo:", carUri.startsWith("geo:0,0?q="))
        assertTrue("Car URI must contain latitude 21.0313", carUri.contains("21.0313"))
        assertTrue("Car URI must contain longitude 105.8152", carUri.contains("105.8152"))

        val navValidation = AndroidAutoContractValidator.validateCarNavigationIntent(
            capturedCarNavSpec!!.action,
            capturedCarNavSpec!!.uriString
        )
        assertTrue(
            "Car navigation intent must pass contract validation: ${navValidation.errors.joinToString()}",
            navValidation.isValid
        )

        // Step 6: Verify simultaneous mobile Focus Mode background synchronization
        assertNotNull("Focus Mode start intent must be dispatched simultaneously", capturedFocusSpec)
        assertEquals(FocusModeForegroundService.ACTION_START, capturedFocusSpec!!.action)
        assertNotNull(capturedFocusSpec!!.payloadJson)
        val payload = capturedFocusSpec!!.payloadJson!!
        assertTrue("Payload must contain station id", payload.contains("station_metropolis"))
        assertTrue("Payload must contain station latitude", payload.contains("21.0313"))
        assertTrue("Payload must contain station longitude", payload.contains("105.8152"))
    }

    // =========================================================================================
    // 6. Mobile Fallback Navigation Contract
    // =========================================================================================

    @Test
    fun testMobileFallbackNavigationContract() {
        val station = createSampleStation()
        val fallbackSpec = CarNavigationDispatcher.getFallbackNavigationIntentSpec(station)

        assertEquals(Intent.ACTION_VIEW, fallbackSpec.action)
        assertEquals("google.navigation:q=21.0313,105.8152&mode=d", fallbackSpec.uriString)
        assertFalse("Fallback spec is for mobile display, not car app host", fallbackSpec.isCarApp)

        val validation = AndroidAutoContractValidator.validateFallbackNavigationIntent(
            fallbackSpec.action,
            fallbackSpec.uriString
        )
        assertTrue(
            "Fallback navigation intent must pass contract validation: ${validation.errors.joinToString()}",
            validation.isValid
        )

        // Negative test: Car URI passed to fallback validator must fail
        val invalidFallback = AndroidAutoContractValidator.validateFallbackNavigationIntent(
            Intent.ACTION_VIEW,
            "geo:0,0?q=21.0313,105.8152"
        )
        assertFalse("Fallback validator must reject non-google.navigation URIs", invalidFallback.isValid)
    }

    // =========================================================================================
    // 7. Voice TTS Alert Policy & Audio Ducking Integration Contract
    // =========================================================================================

    @Test
    fun testVoiceTtsPolicyAndAudioDuckingContract() {
        // Verify audio ducking and mandatory voice alerts contract
        val alertTypes = listOf(
            FocusVoiceAlert.STATION_FULL.name,
            FocusVoiceAlert.ALTERNATIVE_FOUND.name,
            FocusVoiceAlert.PROXIMITY_REMINDER.name
        )
        val contractResult = AndroidAutoContractValidator.validateVoiceAndDuckingContract(
            duckingSupported = true,
            supportedAlertTypes = alertTypes
        )
        assertTrue(contractResult.isValid)
        assertTrue(contractResult.errors.isEmpty())

        // Verify failure when ducking is disabled
        val noDuckingResult = AndroidAutoContractValidator.validateVoiceAndDuckingContract(
            duckingSupported = false,
            supportedAlertTypes = alertTypes
        )
        assertFalse(noDuckingResult.isValid)
        assertTrue(noDuckingResult.errors.any { it.contains("Audio Ducking") })

        // Verify failure when mandatory alert type is omitted
        val missingAlertResult = AndroidAutoContractValidator.validateVoiceAndDuckingContract(
            duckingSupported = true,
            supportedAlertTypes = listOf(FocusVoiceAlert.STATION_FULL.name)
        )
        assertFalse(missingAlertResult.isValid)
        assertTrue(missingAlertResult.errors.any { it.contains("ALTERNATIVE_FOUND") })

        // Verify Voice Alert Policy transitions (station full & 2km proximity)
        var simulatedTime = 1000L
        val policy = FocusModeVoiceAlertPolicy(
            initialAvailableSlots = 4,
            initialDistanceKm = 5.0,
            clock = { simulatedTime }
        )

        // Transition 1: Target station drops from 4 available to 0 available -> triggers STATION_FULL
        val alertOnFull = policy.evaluate(currentAvailableSlots = 0)
        assertNotNull(alertOnFull)
        assertEquals(FocusVoiceAlert.STATION_FULL, alertOnFull)
        assertEquals(FocusModeVoiceAlertPolicy.ALERT_TEXT_STATION_FULL, alertOnFull?.text)

        // Advance past debounce window (25 seconds)
        simulatedTime += 25_000L

        // Transition 2: Proximity drops below 2.0 km -> triggers PROXIMITY_REMINDER
        val alertOnProximity = policy.evaluateProximity(distanceRemainingKm = 1.9)
        assertNotNull(alertOnProximity)
        assertEquals(FocusVoiceAlert.PROXIMITY_REMINDER, alertOnProximity)

        // Vietnamese alternative station text verification
        val recommendationText = FocusModeVoiceAlertPolicy.formatAlternativeFoundText(
            distanceKm = 1.5,
            availableSlots = 4
        )
        assertTrue("Text must announce distance", recommendationText.contains("1.5"))
        assertTrue("Text must announce available slots", recommendationText.contains("4"))
    }
}
