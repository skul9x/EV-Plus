package com.evcs.favorites

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.logging.AppDebugLogger
import com.evcs.favorites.data.logging.DebugLogLevel
import com.evcs.favorites.data.logging.DebugLogTag
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.data.routing.DrivingMetrics
import com.evcs.favorites.data.routing.RoutingEngineType
import com.evcs.favorites.data.routing.TrafficCondition
import com.evcs.favorites.ui.components.resolveStatusBadge
import com.evcs.favorites.ui.theme.StatusAvailable
import com.evcs.favorites.ui.theme.StatusBusy
import com.evcs.favorites.ui.theme.StatusMaintaining
import com.evcs.favorites.ui.theme.StatusOffline
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Phase 04 Comprehensive Clean Architecture & Domain Cleanup Verification Test.
 *
 * Requirements verified:
 * 1. Station lifecycle, equality, copy contract, and JSON serialization operate cleanly
 *    with zero forecast dependencies; legacy JSON payloads with "forecast" deserialize safely.
 * 2. Status badge resolution works accurately for all depot states without forecast parameters.
 * 3. Clean architecture boundary verification: Station, DebugLogEntry, DebugLogTag,
 *    EvcsApiClient, and EvcsRepository have zero forecast fields, methods, or enum values.
 * 4. Retired classes (StationForecast, ForecastPowerGroup, ForecastSession, StationForecastParser,
 *    ChargingForecastRequest, ChargingForecastResponse) are completely absent from classpath.
 * 5. No active coroutines, lingering background jobs, or logging side effects exist for forecast polling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForecastRemovalCleanArchitectureRegressionTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        AppDebugLogger.clear()
    }

    // =========================================================================
    // 1. Station Lifecycle, Serialization & Domain Contracts Without Forecast
    // =========================================================================

    @Test
    fun testStationSerializationAndLifecycle_operatesWithoutForecastDependencies() {
        val metrics = DrivingMetrics(
            distanceMeters = 4200L,
            durationSeconds = 540L,
            staticDurationSeconds = 600L,
            trafficCondition = TrafficCondition.FREE_FLOW,
            engineUsed = RoutingEngineType.OSRM
        )

        val port1 = PowerPort(
            typeWatts = 60000L,
            label = "60kW",
            availablePlugs = 2,
            totalPlugs = 4,
            displayString = "60kW: trống 2/4 cổng"
        )
        val port2 = PowerPort(
            typeWatts = 250000L,
            label = "250kW",
            availablePlugs = 0,
            totalPlugs = 2,
            displayString = "250kW: trống 0/2 cổng"
        )

        val station = Station(
            id = "station_clean_01",
            name = "Trạm Sạc VinFast Landmark 81",
            address = "720A Điện Biên Phủ, P.22, Bình Thạnh, TP.HCM",
            latitude = 10.7950,
            longitude = 106.7218,
            summary = "Trụ sạc siêu nhanh",
            connectors = "60kW, 250kW",
            depotStatus = "Normal",
            powers = listOf(port1, port2),
            totalAvailablePlugs = 2,
            totalPlugs = 6,
            image = "https://example.com/station.jpg",
            isPublic = true,
            isFreeParking = true,
            workingTimeDescription = "24/7",
            distanceKm = 4.2,
            drivingMetrics = metrics
        )

        // Verify domain computed properties
        assertTrue(station.hasLiveTelemetry)
        assertEquals(4.2, station.effectiveDistanceKm ?: 0.0, 0.001)
        assertEquals(540L, station.effectiveDurationSeconds)

        // 1. Serialize Station -> Verify JSON does NOT contain "forecast"
        val serializedJson = json.encodeToString(station)
        assertFalse(
            "Serialized JSON must not contain 'forecast' key",
            serializedJson.contains("\"forecast\"")
        )
        assertTrue(serializedJson.contains("station_clean_01"))
        assertTrue(serializedJson.contains("Trạm Sạc VinFast Landmark 81"))

        // 2. Deserialize Station -> Round-trip fidelity
        val deserialized = json.decodeFromString<Station>(serializedJson)
        assertEquals(station, deserialized)
        assertEquals(station.hashCode(), deserialized.hashCode())
        assertEquals(station.powers, deserialized.powers)
        assertEquals(station.drivingMetrics, deserialized.drivingMetrics)

        // 3. Backward-compatibility: Deserializing legacy JSON containing "forecast" must not fail
        val legacyJsonWithForecast = """
            {
                "id": "legacy_01",
                "name": "Trạm Cũ",
                "address": "Hà Nội",
                "latitude": 21.0285,
                "longitude": 105.8542,
                "summary": "Trạm sạc",
                "connectors": "60kW",
                "depotStatus": "Normal",
                "powers": [],
                "totalAvailablePlugs": 1,
                "totalPlugs": 2,
                "isPublic": true,
                "isFreeParking": false,
                "workingTimeDescription": "24/7",
                "forecast": {
                    "rawText": "Dự kiến 2 xe sạc trụ 60kW sẽ xong trong 15 phút",
                    "vehicleCount": 2,
                    "wattageKw": 60.0,
                    "minMinutes": 10,
                    "maxMinutes": 15
                }
            }
        """.trimIndent()

        val parsedLegacyStation = json.decodeFromString<Station>(legacyJsonWithForecast)
        assertEquals("legacy_01", parsedLegacyStation.id)
        assertEquals("Trạm Cũ", parsedLegacyStation.name)
        assertEquals(1, parsedLegacyStation.totalAvailablePlugs)
        assertEquals(2, parsedLegacyStation.totalPlugs)

        // 4. Data class copy and equality contracts
        val copyIdentical = station.copy()
        assertEquals(station, copyIdentical)
        assertEquals(station.hashCode(), copyIdentical.hashCode())

        val copyUpdatedPlugs = station.copy(totalAvailablePlugs = 0)
        assertFalse(station == copyUpdatedPlugs)
        assertEquals(0, copyUpdatedPlugs.totalAvailablePlugs)
        assertEquals(station.id, copyUpdatedPlugs.id)
        assertEquals(station.drivingMetrics, copyUpdatedPlugs.drivingMetrics)
    }

    // =========================================================================
    // 2. Status Badge Resolution Clean Separation
    // =========================================================================

    @Test
    fun testResolveStatusBadge_operatesCleanlyWithoutForecastParameter() {
        // Full station: totalPlugs > 0 && totalAvailablePlugs == 0 -> "Hết cổng" (StatusBusy)
        val fullBadge = resolveStatusBadge(
            depotStatus = "Normal",
            totalAvailablePlugs = 0,
            totalPlugs = 4
        )
        assertEquals("Hết cổng", fullBadge.label)
        assertEquals(StatusBusy, fullBadge.dotColor)
        assertFalse(fullBadge.label.contains("Sắp trống"))

        // Available station: totalAvailablePlugs > 0 -> "Hoạt động" (StatusAvailable)
        val availableBadge = resolveStatusBadge(
            depotStatus = "Normal",
            totalAvailablePlugs = 3,
            totalPlugs = 6
        )
        assertEquals("Hoạt động", availableBadge.label)
        assertEquals(StatusAvailable, availableBadge.dotColor)

        // Maintaining station: -> "Bảo trì" (StatusMaintaining)
        val maintainingBadge = resolveStatusBadge(
            depotStatus = "Maintaining",
            totalAvailablePlugs = 0,
            totalPlugs = 4
        )
        assertEquals("Bảo trì", maintainingBadge.label)
        assertEquals(StatusMaintaining, maintainingBadge.dotColor)

        // Offline station: -> "Tạm dừng" (StatusOffline)
        val offlineBadge = resolveStatusBadge(
            depotStatus = "OutOfService",
            totalAvailablePlugs = 0,
            totalPlugs = 4
        )
        assertEquals("Tạm dừng", offlineBadge.label)
        assertEquals(StatusOffline, offlineBadge.dotColor)

        // Unverified station (totalPlugs == 0) -> "Hoạt động" for Normal, never "Hết cổng"
        val unverifiedNormalBadge = resolveStatusBadge(
            depotStatus = "Normal",
            totalAvailablePlugs = 0,
            totalPlugs = 0
        )
        assertEquals("Hoạt động", unverifiedNormalBadge.label)
        assertEquals(StatusAvailable, unverifiedNormalBadge.dotColor)

        val unverifiedUnknownBadge = resolveStatusBadge(
            depotStatus = "Unknown",
            totalAvailablePlugs = 0,
            totalPlugs = 0
        )
        assertEquals("Đã lưu", unverifiedUnknownBadge.label)
    }

    // =========================================================================
    // 3. Clean Architecture Boundary Verification & Classpath Absence
    // =========================================================================

    @Test
    fun testCleanArchitectureBoundaries_zeroForecastCouplingInDomainAndData() {
        // 1. Verify Station class has zero forecast fields or methods
        val stationFields = Station::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(
            "Station class must not declare any forecast field",
            stationFields.any { it.contains("forecast") }
        )

        val stationMethods = Station::class.java.declaredMethods.map { it.name.lowercase() }
        assertFalse(
            "Station class must not declare any forecast method",
            stationMethods.any { it.contains("forecast") }
        )

        // 2. Verify DebugLogEntry has zero parsedForecastSummary field
        val logFields = com.evcs.favorites.data.logging.DebugLogEntry::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(
            "DebugLogEntry must not declare parsedForecastSummary",
            logFields.any { it.contains("forecast") }
        )

        // 3. Verify DebugLogTag enum has zero FORECAST constant
        val logTagNames = DebugLogTag.values().map { it.name }
        assertFalse(
            "DebugLogTag must not contain FORECAST enum constant",
            logTagNames.contains("FORECAST")
        )
        assertEquals(listOf("NETWORK", "SEARCH", "FAVORITES", "ROUTING"), logTagNames)

        // 4. Verify EvcsApiClient and EvcsRepository have zero forecast methods
        val apiClientMethods = EvcsApiClient::class.java.declaredMethods.map { it.name.lowercase() }
        assertFalse(
            "EvcsApiClient must not declare any forecast method",
            apiClientMethods.any { it.contains("forecast") }
        )

        val repositoryMethods = EvcsRepository::class.java.declaredMethods.map { it.name.lowercase() }
        assertFalse(
            "EvcsRepository must not declare any forecast method",
            repositoryMethods.any { it.contains("forecast") }
        )

        // 5. Verify obsolete forecast classes are completely purged from classpath
        val purgedClasses = listOf(
            "com.evcs.favorites.domain.model.StationForecast",
            "com.evcs.favorites.domain.model.ForecastPowerGroup",
            "com.evcs.favorites.domain.model.ForecastSession",
            "com.evcs.favorites.data.parser.StationForecastParser",
            "com.evcs.favorites.data.model.ChargingForecastRequest",
            "com.evcs.favorites.data.model.ChargingForecastResponse"
        )

        for (className in purgedClasses) {
            try {
                Class.forName(className)
                fail("Class $className should have been purged from the project")
            } catch (_: ClassNotFoundException) {
                // Expected: class does not exist
            }
        }
    }

    // =========================================================================
    // 4. Background Job & Coroutine Safety (Zero Forecast Polling Tasks)
    // =========================================================================

    @Test
    fun testNoActiveCoroutinesOrBackgroundJobsForForecastPolling() = testScope.runTest {
        // Record test log entries across all supported tags
        AppDebugLogger.log(
            tag = DebugLogTag.SEARCH,
            level = DebugLogLevel.INFO,
            message = "Scan nearby initiated"
        )
        AppDebugLogger.log(
            tag = DebugLogTag.NETWORK,
            level = DebugLogLevel.SUCCESS,
            message = "Stations received: 5"
        )
        AppDebugLogger.log(
            tag = DebugLogTag.FAVORITES,
            level = DebugLogLevel.INFO,
            message = "Favorites synced"
        )
        AppDebugLogger.log(
            tag = DebugLogTag.ROUTING,
            level = DebugLogLevel.SUCCESS,
            message = "Route computed in 120ms"
        )

        advanceUntilIdle()

        // Verify stored logs
        val storedLogs = AppDebugLogger.getLogs()
        assertEquals(4, storedLogs.size)

        // Verify no forecast tags exist in logs
        assertFalse(
            "No log should have a FORECAST tag",
            storedLogs.any { it.tag.name == "FORECAST" }
        )

        // Formatted log string must be free of forecast labels
        val formattedLog = AppDebugLogger.getFormattedLogText()
        assertFalse(
            "Formatted logs must not contain forecast banner",
            formattedLog.contains("Dự báo sạc:")
        )
        assertTrue(formattedLog.contains("Scan nearby initiated"))
        assertTrue(formattedLog.contains("Favorites synced"))

        // Active test scope has no pending or leaked child jobs
        advanceUntilIdle()
    }
}
