package com.evcs.favorites.data.preferences

import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.routing.EvRoutingSettings
import com.evcs.favorites.data.routing.RoutingEngineMode
import com.evcs.favorites.data.routing.RoutingPreferencesManager
import com.evcs.favorites.data.routing.RoutingSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive verification test for Phase 02: EV Routing Settings & Vehicle Profile Configuration.
 *
 * Verifies:
 * 1. Default EV settings schema and values.
 * 2. Mathematical helper formulas: usable range, effective range before reserve, and duration buffer.
 * 3. Boundary clamping and sanitization against invalid/out-of-range inputs.
 * 4. JSON serialization and deserialization compatibility.
 * 5. Persistence and cold-load recovery via [RoutingPreferencesManager] with [InMemorySessionStorage].
 * 6. Reactive [StateFlow] updates via settings and convenience methods.
 * 7. Backward compatibility and resilience against legacy or corrupted storage states.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EvRoutingSettingsPreferencesTest {

    private lateinit var storage: InMemorySessionStorage
    private lateinit var manager: RoutingPreferencesManager
    private val testDispatcher = UnconfinedTestDispatcher()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Before
    fun setUp() {
        storage = InMemorySessionStorage()
        manager = RoutingPreferencesManager(
            storage = storage,
            ioDispatcher = testDispatcher
        )
    }

    @Test
    fun testDefaultSettings_matchesRequiredSpecifications() {
        val defaultEv = EvRoutingSettings()

        assertEquals(200, defaultEv.vehicleSafeRangeKm)
        assertEquals(100, defaultEv.startBatteryPercent)
        assertEquals(10, defaultEv.arrivalBufferSocPercent)
        assertEquals(85, defaultEv.targetChargingSocPercent)
        assertTrue(defaultEv.safetyDurationBufferEnabled)
        assertEquals(0.25f, defaultEv.safetyDurationBufferRatio, 0.0001f)

        val routingSettings = manager.settings.value
        assertEquals(defaultEv, routingSettings.evSettings)
        assertEquals(defaultEv, manager.evSettings.value)
        assertEquals(defaultEv, manager.evRoutingSettings.value)
    }

    @Test
    fun testRangeAndDurationFormulas_computeAccurately() {
        val settings = EvRoutingSettings(
            vehicleSafeRangeKm = 300,
            startBatteryPercent = 90,
            arrivalBufferSocPercent = 15,
            targetChargingSocPercent = 80,
            safetyDurationBufferEnabled = true,
            safetyDurationBufferRatio = 0.20f
        )

        // Usable Range: vehicleSafeRangeKm * (socPercent / 100.0)
        assertEquals(270.0, settings.calculateUsableRangeKm(90), 0.001)
        assertEquals(300.0, settings.calculateUsableRangeKm(100), 0.001)
        assertEquals(150.0, settings.calculateUsableRangeKm(50), 0.001)
        assertEquals(0.0, settings.calculateUsableRangeKm(0), 0.001)
        // Default param uses startBatteryPercent
        assertEquals(270.0, settings.calculateUsableRangeKm(), 0.001)

        // Effective Range Before Reserve: vehicleSafeRangeKm * ((socPercent - arrivalBufferSocPercent).coerceAtLeast(0) / 100.0)
        // (90 - 15) = 75% -> 300 * 0.75 = 225.0 km
        assertEquals(225.0, settings.calculateEffectiveRangeBeforeReserveKm(90), 0.001)
        // (15 - 15) = 0% -> 0.0 km
        assertEquals(0.0, settings.calculateEffectiveRangeBeforeReserveKm(15), 0.001)
        // (10 - 15) -> clamped to 0 -> 0.0 km
        assertEquals(0.0, settings.calculateEffectiveRangeBeforeReserveKm(10), 0.001)
        // Default param
        assertEquals(225.0, settings.calculateEffectiveRangeBeforeReserveKm(), 0.001)

        // Safety Duration Buffer: if (enabled) (rawMinutes * (1f + ratio)).roundToInt() else rawMinutes
        // 60 minutes * 1.20 = 72 minutes
        assertEquals(72, settings.applyDurationBuffer(60))
        // 45 minutes * 1.20 = 54 minutes
        assertEquals(54, settings.applyDurationBuffer(45))

        // When buffer is disabled
        val disabledBufferSettings = settings.copy(safetyDurationBufferEnabled = false)
        assertEquals(60, disabledBufferSettings.applyDurationBuffer(60))
        assertEquals(45, disabledBufferSettings.applyDurationBuffer(45))
    }

    @Test
    fun testBoundaryClampingAndSanitization_enforcesLimits() {
        // Underflow inputs
        val underflow = EvRoutingSettings(
            vehicleSafeRangeKm = 50,
            startBatteryPercent = 5,
            arrivalBufferSocPercent = 2,
            targetChargingSocPercent = 50,
            safetyDurationBufferEnabled = true,
            safetyDurationBufferRatio = -0.5f
        ).sanitized()

        assertEquals(EvRoutingSettings.MIN_VEHICLE_SAFE_RANGE_KM, underflow.vehicleSafeRangeKm)
        assertEquals(EvRoutingSettings.MIN_START_BATTERY_PERCENT, underflow.startBatteryPercent)
        assertEquals(EvRoutingSettings.MIN_ARRIVAL_BUFFER_SOC_PERCENT, underflow.arrivalBufferSocPercent)
        assertEquals(EvRoutingSettings.MIN_TARGET_CHARGING_SOC_PERCENT, underflow.targetChargingSocPercent)
        assertEquals(EvRoutingSettings.MIN_SAFETY_DURATION_BUFFER_RATIO, underflow.safetyDurationBufferRatio, 0.0001f)

        // Overflow inputs
        val overflow = EvRoutingSettings(
            vehicleSafeRangeKm = 999,
            startBatteryPercent = 150,
            arrivalBufferSocPercent = 50,
            targetChargingSocPercent = 120,
            safetyDurationBufferEnabled = true,
            safetyDurationBufferRatio = 3.0f
        ).sanitize()

        assertEquals(EvRoutingSettings.MAX_VEHICLE_SAFE_RANGE_KM, overflow.vehicleSafeRangeKm)
        assertEquals(EvRoutingSettings.MAX_START_BATTERY_PERCENT, overflow.startBatteryPercent)
        assertEquals(EvRoutingSettings.MAX_ARRIVAL_BUFFER_SOC_PERCENT, overflow.arrivalBufferSocPercent)
        assertEquals(EvRoutingSettings.MAX_TARGET_CHARGING_SOC_PERCENT, overflow.targetChargingSocPercent)
        assertEquals(EvRoutingSettings.MAX_SAFETY_DURATION_BUFFER_RATIO, overflow.safetyDurationBufferRatio, 0.0001f)

        // Factory sanitized creation
        val factorySanitized = EvRoutingSettings.createSanitized(
            vehicleSafeRangeKm = 80,
            startBatteryPercent = 120
        )
        assertEquals(100, factorySanitized.vehicleSafeRangeKm)
        assertEquals(100, factorySanitized.startBatteryPercent)
    }

    @Test
    fun testJsonSerialization_roundTripPreservesValues() {
        val original = EvRoutingSettings(
            vehicleSafeRangeKm = 350,
            startBatteryPercent = 85,
            arrivalBufferSocPercent = 12,
            targetChargingSocPercent = 90,
            safetyDurationBufferEnabled = true,
            safetyDurationBufferRatio = 0.30f
        )

        val jsonString = json.encodeToString(original)
        val deserialized = json.decodeFromString<EvRoutingSettings>(jsonString)

        assertEquals(original, deserialized)

        // Also test full RoutingSettings container round-trip
        val fullSettings = RoutingSettings(
            googleApiKey = "AIzaSyTestKey",
            preferredEngine = RoutingEngineMode.GOOGLE_ONLY,
            autoFallbackEnabled = false,
            customOsrmServerUrl = "https://osrm.example.com",
            evSettings = original
        )
        val fullJson = json.encodeToString(fullSettings)
        val deserializedFull = json.decodeFromString<RoutingSettings>(fullJson)
        assertEquals(fullSettings, deserializedFull)
    }

    @Test
    fun testPersistenceAndColdLoadRecovery_acrossManagerInstances() {
        val customEv = EvRoutingSettings(
            vehicleSafeRangeKm = 420,
            startBatteryPercent = 95,
            arrivalBufferSocPercent = 15,
            targetChargingSocPercent = 80,
            safetyDurationBufferEnabled = false,
            safetyDurationBufferRatio = 0.15f
        )

        val newSettings = RoutingSettings(
            googleApiKey = "AIzaSyEvPersistenceKey",
            preferredEngine = RoutingEngineMode.OSRM_ONLY,
            autoFallbackEnabled = true,
            evSettings = customEv
        )

        manager.saveSettings(newSettings)

        // Verify storage keys
        assertEquals("420", storage.getString(RoutingPreferencesManager.KEY_EV_SAFE_RANGE_KM))
        assertEquals("95", storage.getString(RoutingPreferencesManager.KEY_EV_START_BATTERY_PERCENT))
        assertEquals("15", storage.getString(RoutingPreferencesManager.KEY_EV_ARRIVAL_BUFFER_SOC_PERCENT))
        assertEquals("80", storage.getString(RoutingPreferencesManager.KEY_EV_TARGET_CHARGING_SOC_PERCENT))
        assertEquals("false", storage.getString(RoutingPreferencesManager.KEY_EV_SAFETY_DURATION_BUFFER_ENABLED))
        assertEquals("0.15", storage.getString(RoutingPreferencesManager.KEY_EV_SAFETY_DURATION_BUFFER_RATIO))
        assertNotNull(storage.getString(RoutingPreferencesManager.KEY_EV_ROUTING_SETTINGS_JSON))

        // Create cold new instance reading from the same storage
        val coldManager = RoutingPreferencesManager(
            storage = storage,
            ioDispatcher = testDispatcher
        )
        val loaded = coldManager.loadSettings()

        assertEquals(customEv, loaded.evSettings)
        assertEquals(customEv, coldManager.evSettings.value)
        assertEquals("AIzaSyEvPersistenceKey", loaded.googleApiKey)
    }

    @Test
    fun testReactiveStateFlowAndConvenienceUpdaters_propagateInstantly() = runTest(testDispatcher) {
        // Test updateVehicleSafeRangeKm
        manager.updateVehicleSafeRangeKm(450)
        assertEquals(450, manager.evSettings.first().vehicleSafeRangeKm)
        assertEquals(450, manager.settings.first().evSettings.vehicleSafeRangeKm)

        // Test clamping on updateVehicleSafeRangeKm (e.g. 600 -> 500)
        manager.updateVehicleSafeRangeKm(600)
        assertEquals(500, manager.evSettings.first().vehicleSafeRangeKm)

        // Test updateStartBatteryPercent
        manager.updateStartBatteryPercent(75)
        assertEquals(75, manager.evSettings.first().startBatteryPercent)
        // Test clamping (5 -> 10)
        manager.updateStartBatteryPercent(5)
        assertEquals(10, manager.evSettings.first().startBatteryPercent)

        // Test updateArrivalBufferSocPercent
        manager.updateArrivalBufferSocPercent(20)
        assertEquals(20, manager.evSettings.first().arrivalBufferSocPercent)
        // Test clamping (30 -> 25)
        manager.updateArrivalBufferSocPercent(30)
        assertEquals(25, manager.evSettings.first().arrivalBufferSocPercent)

        // Test updateTargetChargingSocPercent
        manager.updateTargetChargingSocPercent(80)
        assertEquals(80, manager.evSettings.first().targetChargingSocPercent)
        // Test clamping (100 -> 95)
        manager.updateTargetChargingSocPercent(100)
        assertEquals(95, manager.evSettings.first().targetChargingSocPercent)

        // Test updateSafetyDurationBuffer
        manager.updateSafetyDurationBuffer(enabled = false, ratio = 0.10f)
        assertFalse(manager.evSettings.first().safetyDurationBufferEnabled)
        assertEquals(0.10f, manager.evSettings.first().safetyDurationBufferRatio, 0.0001f)

        // Test updateSafetyDurationBufferEnabled
        manager.updateSafetyDurationBufferEnabled(true)
        assertTrue(manager.evSettings.first().safetyDurationBufferEnabled)

        // Test updateSafetyDurationBufferRatio
        manager.updateSafetyDurationBufferRatio(0.35f)
        assertEquals(0.35f, manager.evSettings.first().safetyDurationBufferRatio, 0.0001f)

        // Test resetEvRoutingSettings
        manager.resetEvRoutingSettings()
        assertEquals(EvRoutingSettings(), manager.evSettings.first())
    }

    @Test
    fun testBackwardCompatibilityAndResilience_againstCorruptedStorage() {
        // Populate storage with legacy keys only (pre-Phase 02 schema)
        storage.clear()
        storage.putString(RoutingPreferencesManager.KEY_GOOGLE_API_KEY, "AIzaSyLegacyKey")
        storage.putString(RoutingPreferencesManager.KEY_PREFERRED_ENGINE, "OSRM_ONLY")
        storage.putString(RoutingPreferencesManager.KEY_AUTO_FALLBACK, "true")

        val legacyLoaded = manager.loadSettings()
        assertEquals("AIzaSyLegacyKey", legacyLoaded.googleApiKey)
        assertEquals(EvRoutingSettings(), legacyLoaded.evSettings)

        // Inject corrupt non-numeric data into EV storage keys
        storage.putString(RoutingPreferencesManager.KEY_EV_SAFE_RANGE_KM, "not-a-number")
        storage.putString(RoutingPreferencesManager.KEY_EV_START_BATTERY_PERCENT, "invalid")
        storage.putString(RoutingPreferencesManager.KEY_EV_ROUTING_SETTINGS_JSON, "{corrupted_json: true}")

        val resilientLoaded = manager.loadSettings()
        // Should safely fallback to default sanitized values
        assertEquals(EvRoutingSettings.DEFAULT_VEHICLE_SAFE_RANGE_KM, resilientLoaded.evSettings.vehicleSafeRangeKm)
        assertEquals(EvRoutingSettings.DEFAULT_START_BATTERY_PERCENT, resilientLoaded.evSettings.startBatteryPercent)
    }
}
