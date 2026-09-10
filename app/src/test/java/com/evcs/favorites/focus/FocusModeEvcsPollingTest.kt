package com.evcs.favorites.focus

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.EvsePowerRaw
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.SearchStationRaw
import com.evcs.favorites.data.model.Station
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject

/**
 * Single comprehensive test file verifying Phase 01: Focus Mode EVCS Fixed 10s Telemetry Polling.
 *
 * Core criteria verified:
 * 1. Polling interval is constant at 10,000ms regardless of distance to station.
 * 2. Telemetry is parsed correctly from EVCS API format (SearchStationRaw -> Station domain model).
 * 3. When an EVCS API request times out or throws IOException, the existing available DC slots count
 *    and previous telemetry state are retained without zeroing out or corrupting the snapshot.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FocusModeEvcsPollingTest {

    private val fixedTimeZone = TimeZone.getTimeZone("GMT+7")
    private val fixedLocale = Locale.US
    private val sessionManager = SessionManager(InMemorySessionStorage())

    private fun createInitialStation(
        id: String = "loc_target_001",
        name: String = "VinFast Vincom Dong Khoi",
        lat: Double = 10.7769,
        lon: Double = 106.7009
    ): Station {
        val initialPowers = listOf(
            PowerPort(typeWatts = 250_000L, label = "250kW", availablePlugs = 2, totalPlugs = 4),
            PowerPort(typeWatts = 60_000L, label = "60kW", availablePlugs = 1, totalPlugs = 2)
        )
        return Station(
            id = id,
            name = name,
            address = "72 Le Thanh Ton, District 1, HCM",
            latitude = lat,
            longitude = lon,
            summary = "Trống 3/6 cổng sạc DC",
            connectors = "250kW, 60kW",
            depotStatus = "Normal",
            powers = initialPowers,
            totalAvailablePlugs = 3,
            totalPlugs = 6,
            distanceKm = 5.0
        )
    }

    private fun createMockSearchStation(
        locationId: String = "loc_target_001",
        stationName: String = "VinFast Vincom Dong Khoi",
        lat: Double = 10.7769,
        lon: Double = 106.7009,
        dc250Available: Int = 2,
        dc60Available: Int = 1,
        ac11Available: Int = 2,
        totalCharging: Int? = 3,
        chargingKw: Double? = 250.0
    ): SearchStationRaw {
        return SearchStationRaw(
            locationId = locationId,
            id = locationId,
            stationName = stationName,
            stationAddress = "72 Le Thanh Ton, District 1, HCM",
            latitude = lat,
            longitude = lon,
            depotStatus = "Normal",
            evsePowers = listOf(
                EvsePowerRaw(type = 250_000L, numberOfAvailableEvse = dc250Available, totalEvse = 4, totalCharging = 2, chargingKw = chargingKw?.let { JsonPrimitive(it) }),
                EvsePowerRaw(type = 60_000L, numberOfAvailableEvse = dc60Available, totalEvse = 2, totalCharging = 1, chargingKw = chargingKw?.let { JsonPrimitive(it) }),
                EvsePowerRaw(type = 11_000L, numberOfAvailableEvse = ac11Available, totalEvse = 2) // AC port
            ),
            totalCharging = totalCharging,
            chargingKw = chargingKw?.let { JsonPrimitive(it) }
        )
    }

    // =========================================================================
    // 1. Constant Polling Interval Verification (10,000ms) & Wattage Filter
    // =========================================================================

    @Test
    fun testPollingInterval_isConstantFixed10000ms_regardlessOfDistance() = runTest {
        // Constant contract verification
        assertEquals(10_000L, FocusModeTelemetryEngine.INTERVAL_FIXED_MS)
        assertEquals(listOf("FAST", "SUPER_FAST"), FocusModeTelemetryEngine.DEFAULT_WATTAGE_TYPES)

        val station = createInitialStation()
        var pollCounter = 0
        var capturedWattageTypes: List<String>? = null

        val fakeClient = object : EvcsApiClient(sessionManager = sessionManager) {
            override suspend fun searchStations(
                latitude: Double,
                longitude: Double,
                token: String,
                wattageTypes: List<String>?
            ): Result<List<SearchStationRaw>> {
                pollCounter++
                capturedWattageTypes = wattageTypes
                return Result.success(listOf(createMockSearchStation()))
            }
        }

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val engine = FocusModeTelemetryEngine(
            initialStation = station,
            evcsApiClient = fakeClient,
            defaultDispatcher = testDispatcher,
            coroutineScope = testScope,
            timeZone = fixedTimeZone,
            locale = fixedLocale
        )

        assertEquals(10_000L, engine.pollingIntervalMs)

        // Start polling coroutine
        engine.start()
        assertTrue(engine.isRunning)

        // First poll happens immediately on coroutine launch
        testScheduler.runCurrent()
        assertEquals(1, pollCounter)
        assertEquals(listOf("FAST", "SUPER_FAST"), capturedWattageTypes)

        // After 9,999ms, the interval has not elapsed yet -> still 1 poll
        testScheduler.advanceTimeBy(9_999L)
        testScheduler.runCurrent()
        assertEquals(1, pollCounter)

        // At exactly 10,000ms -> second poll triggered
        testScheduler.advanceTimeBy(1L)
        testScheduler.runCurrent()
        assertEquals(2, pollCounter)

        // At 20,000ms -> third poll triggered
        testScheduler.advanceTimeBy(10_000L)
        testScheduler.runCurrent()
        assertEquals(3, pollCounter)

        // Change distance remaining to near distance (e.g. 0.8km)
        engine.updateDriverLocation(station.latitude + 0.005, station.longitude)
        testScheduler.advanceTimeBy(10_000L)
        testScheduler.runCurrent()
        assertEquals(4, pollCounter)

        // Stop polling cleanly
        engine.stop()
        assertFalse(engine.isRunning)

        // Advancing time further produces no more polls
        testScheduler.advanceTimeBy(30_000L)
        testScheduler.runCurrent()
        assertEquals(4, pollCounter)
    }

    // =========================================================================
    // 2. EVCS Search API Format Parsing & Mapping Verification
    // =========================================================================

    @Test
    fun testTelemetryParsing_parsesEvcsSearchFormatCorrectly() = runTest {
        val rawJson = """
            {
                "locationId": "loc_target_001",
                "id": "loc_target_001",
                "stationName": "VinFast Vincom Dong Khoi",
                "stationAddress": "72 Le Thanh Ton, District 1, HCM",
                "latitude": 10.7769,
                "longitude": 106.7009,
                "depotStatus": "Normal",
                "totalCharging": 3,
                "chargingKw": 250.0,
                "evsePowers": [
                    {
                        "type": 250000,
                        "numberOfAvailableEvse": 3,
                        "totalEvse": 4,
                        "totalCharging": 1,
                        "chargingKw": 250.0
                    },
                    {
                        "type": 60000,
                        "numberOfAvailableEvse": 2,
                        "totalEvse": 2,
                        "totalCharging": 0,
                        "chargingKw": 60.0
                    },
                    {
                        "type": 11000,
                        "numberOfAvailableEvse": 2,
                        "totalEvse": 2
                    }
                ]
            }
        """.trimIndent()

        // 2a. Direct JSON deserialization verification (evsePowers, totalCharging, chargingKw)
        val deserializedStation = EvcsApiClient.json.decodeFromString<SearchStationRaw>(rawJson)
        assertEquals("loc_target_001", deserializedStation.locationId)
        assertEquals(3, deserializedStation.totalCharging)
        assertEquals(250.0, deserializedStation.chargingKwDouble)
        assertEquals(3, deserializedStation.evsePowers.size)
        assertEquals(250000L, deserializedStation.evsePowers[0].type)
        assertEquals(1, deserializedStation.evsePowers[0].totalCharging)
        assertEquals(250.0, deserializedStation.evsePowers[0].chargingKwDouble)

        // 2a-2. Real EVCS API breakdown map deserialization (e.g. {"60":3, "150":2})
        val realApiJson = """
            {
                "locationId": "C.BNI0018",
                "stationName": "Chung cu Golden Park",
                "latitude": 21.170145,
                "longitude": 106.10141,
                "totalCharging": 5,
                "chargingKw": {
                    "60": 3,
                    "150": 2
                }
            }
        """.trimIndent()
        val realStation = EvcsApiClient.json.decodeFromString<SearchStationRaw>(realApiJson)
        assertEquals("C.BNI0018", realStation.locationId)
        assertEquals(5, realStation.totalCharging)
        assertEquals(mapOf("60" to 3, "150" to 2), realStation.chargingKwBreakdown)

        val station = createInitialStation()
        val fakeClient = object : EvcsApiClient(sessionManager = sessionManager) {
            override suspend fun searchStations(
                latitude: Double,
                longitude: Double,
                token: String,
                wattageTypes: List<String>?
            ): Result<List<SearchStationRaw>> {
                return Result.success(listOf(deserializedStation))
            }
        }

        val engine = FocusModeTelemetryEngine(
            initialStation = station,
            evcsApiClient = fakeClient,
            timeZone = fixedTimeZone,
            locale = fixedLocale
        )

        val state = engine.pollOnce()

        // Verify domain station mapping
        assertEquals("loc_target_001", state.targetStation.id)
        assertEquals("Vincom Dong Khoi", state.targetStation.name)
        assertEquals(FocusConnectionStatus.CONNECTED, state.connectionStatus)
        assertTrue(state.isConnected)
        assertFalse(state.isOffline)
        assertNull(state.offlineMessage)

        // DC slots: 3 (from 250kW) + 2 (from 60kW) = 5. AC 11kW (2 plugs) strictly excluded!
        assertEquals(5, state.availableDcSlots)
        assertEquals(6, state.totalDcSlots)
        assertEquals(250_000L, state.maxDcPowerWatts)
        assertEquals(250, state.maxDcPowerKw)
        assertEquals("🟢 5/6 Trống (250kW)", state.statusBadgeText)
    }

    // =========================================================================
    // 3. Network Jitter, Timeout & Retention Verification
    // =========================================================================

    @Test
    fun testNetworkTimeout_retainsPreviousValidTelemetrySnapshotWithoutZeroingOut() = runTest {
        var clockTime = 1700000000000L // 17:13 GMT+7
        val station = createInitialStation()

        var shouldFail = false
        var failureException: Throwable = IOException("Connection timed out: evcs.vn search API unreachable")
        var returnedAvailableDc250 = 2

        val fakeClient = object : EvcsApiClient(sessionManager = sessionManager) {
            override suspend fun searchStations(
                latitude: Double,
                longitude: Double,
                token: String,
                wattageTypes: List<String>?
            ): Result<List<SearchStationRaw>> {
                return if (shouldFail) {
                    Result.failure(failureException)
                } else {
                    Result.success(
                        listOf(
                            createMockSearchStation(
                                locationId = "loc_target_001",
                                dc250Available = returnedAvailableDc250,
                                dc60Available = 1
                            )
                        )
                    )
                }
            }
        }

        val engine = FocusModeTelemetryEngine(
            initialStation = station,
            evcsApiClient = fakeClient,
            clock = { clockTime },
            timeZone = fixedTimeZone,
            locale = fixedLocale
        )

        // Step 1: Successful initial poll
        val validState = engine.pollOnce()
        assertEquals(FocusConnectionStatus.CONNECTED, validState.connectionStatus)
        assertEquals(3, validState.availableDcSlots) // 2 (250kW) + 1 (60kW)
        assertEquals(6, validState.totalDcSlots)
        assertNull(validState.offlineMessage)

        // Step 2: Network timeout / IOException occurs
        shouldFail = true
        clockTime += 10_000L // +10s later

        val offlineState = engine.pollOnce()

        // Critical verification: Connection marked OFFLINE with timestamp
        assertEquals(FocusConnectionStatus.OFFLINE, offlineState.connectionStatus)
        assertTrue(offlineState.isOffline)
        assertNotNull(offlineState.offlineMessage)

        // Crucial requirement: Available DC slots and total DC slots MUST NOT reset to 0
        assertEquals(3, offlineState.availableDcSlots)
        assertEquals(6, offlineState.totalDcSlots)
        assertEquals(3, offlineState.targetStation.powers.size)
        assertEquals("loc_target_001", offlineState.targetStation.id)

        // Step 3: Network restores with updated data (1 available plug remaining on 250kW)
        shouldFail = false
        returnedAvailableDc250 = 1
        clockTime += 10_000L // +10s later

        val recoveredState = engine.pollOnce()

        assertEquals(FocusConnectionStatus.CONNECTED, recoveredState.connectionStatus)
        assertFalse(recoveredState.isOffline)
        assertNull(recoveredState.offlineMessage)
        assertEquals(2, recoveredState.availableDcSlots) // 1 (250kW) + 1 (60kW)
        assertEquals(6, recoveredState.totalDcSlots)
    }

    @Test
    fun testEvcsTelemetry_matchesStationByLocationIdInMultiStationSearchResult() = runTest {
        val targetId = "loc_target_target"
        val otherId1 = "loc_other_001"
        val otherId2 = "loc_other_002"

        val targetStation = createInitialStation(id = targetId, name = "Trạm Target")

        val searchResults = listOf(
            createMockSearchStation(locationId = otherId1, stationName = "Trạm Phụ 1", dc250Available = 0, dc60Available = 0),
            createMockSearchStation(locationId = targetId, stationName = "Trạm Target Thật", dc250Available = 4, dc60Available = 2),
            createMockSearchStation(locationId = otherId2, stationName = "Trạm Phụ 2", dc250Available = 1, dc60Available = 1)
        )

        val fakeClient = object : EvcsApiClient(sessionManager = sessionManager) {
            override suspend fun searchStations(
                latitude: Double,
                longitude: Double,
                token: String,
                wattageTypes: List<String>?
            ): Result<List<SearchStationRaw>> {
                return Result.success(searchResults)
            }
        }

        val engine = FocusModeTelemetryEngine(
            initialStation = targetStation,
            evcsApiClient = fakeClient,
            timeZone = fixedTimeZone,
            locale = fixedLocale
        )

        val state = engine.pollOnce()

        // Verify correct matching by locationId
        assertEquals(targetId, state.targetStation.id)
        assertEquals(6, state.availableDcSlots) // 4 (250kW) + 2 (60kW)
        assertEquals(6, state.totalDcSlots)
    }
}
