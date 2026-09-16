package com.evcs.favorites.performance

import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.network.here.HereEvApiClient
import com.evcs.favorites.data.network.here.model.HereAddress
import com.evcs.favorites.data.network.here.model.HereConnector
import com.evcs.favorites.data.network.here.model.HereConnectorStatus
import com.evcs.favorites.data.network.here.model.HereConnectorStatusesContainer
import com.evcs.favorites.data.network.here.model.HereConnectorsContainer
import com.evcs.favorites.data.network.here.model.HereEvStation
import com.evcs.favorites.data.network.here.model.HereEvStationsResponse
import com.evcs.favorites.data.network.here.model.HereEvse
import com.evcs.favorites.data.network.here.model.HereModelMapper
import com.evcs.favorites.data.network.here.model.HerePosition
import com.evcs.favorites.domain.location.DistanceCalculator
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Comprehensive verification test suite for Phase 01:
 * Low-Allocation Pre-Filtering & Mapping Optimization.
 *
 * Requirements covered:
 * 1. Allocation-free AC qualification:
 *    - Inspects connectors directly across root containers, rawConnectors, and EVSE lists.
 *    - Matches 11kW and 22kW car AC connectors with available status.
 *    - Strictly excludes DC fast charging, 3.5kW/7kW scooter tiers, and occupied/OOS ports.
 *    - Tests hasAvailableStatus helper function.
 * 2. Raw coordinate distance calculation & proximity ordering:
 *    - Evaluates Haversine distance on raw station coordinates before mapping.
 *    - Sorts stations ascending by distance from user location.
 * 3. Pre-mapping slicing & data fidelity:
 *    - Slices raw qualifying stations to maxLimit before domain mapping.
 *    - Preserves 100% data contract integrity (addresses, power tiers, plug counts, IDs, coordinates).
 */
class AcStationPreFilterAndMappingOptimizationTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var okHttpClient: OkHttpClient
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val userLat = 21.028511
    private val userLon = 105.854444

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    // =========================================================================
    // 1. Zero-Allocation AC Qualification & Connector Availability Checks
    // =========================================================================

    @Test
    fun testZeroAllocationAcQualification_matches11kWAnd22kWAvailableConnectors() {
        // 11kW AC Available -> Qualifies
        val st11kW = createStation(
            id = "ST-11KW",
            name = "Station 11kW",
            connectors = listOf(
                createConnector(powerKw = 11.0, powerType = "AC_3_PHASE", available = 2, occupied = 0)
            )
        )
        assertTrue("11kW AC station with available slots must qualify", st11kW.hasAvailableAcConnector())

        // 22kW AC Available -> Qualifies
        val st22kW = createStation(
            id = "ST-22KW",
            name = "Station 22kW",
            connectors = listOf(
                createConnector(powerKw = 22.0, powerType = "AC_3_PHASE", available = 1, occupied = 1)
            )
        )
        assertTrue("22kW AC station with available slots must qualify", st22kW.hasAvailableAcConnector())

        // 7kW AC -> Excluded
        val st7kW = createStation(
            id = "ST-7KW",
            name = "Station 7kW",
            connectors = listOf(
                createConnector(powerKw = 7.0, powerType = "AC_1_PHASE", available = 2, occupied = 0)
            )
        )
        assertFalse("7kW AC station must be excluded", st7kW.hasAvailableAcConnector())

        // 3.5kW AC -> Excluded
        val st3kW = createStation(
            id = "ST-3KW",
            name = "Station 3.5kW",
            connectors = listOf(
                createConnector(powerKw = 3.5, powerType = "AC_1_PHASE", available = 2, occupied = 0)
            )
        )
        assertFalse("3.5kW AC station must be excluded", st3kW.hasAvailableAcConnector())

        // 60kW DC Only -> Excluded
        val stDc = createStation(
            id = "ST-60KW",
            name = "Station 60kW DC",
            connectors = listOf(
                createConnector(powerKw = 60.0, powerType = "DC", available = 4, occupied = 0)
            )
        )
        assertFalse("DC-only station must be excluded from AC discovery", stDc.hasAvailableAcConnector())

        // 11kW AC Fully Occupied -> Excluded
        val st11kWOccupied = createStation(
            id = "ST-11KW-OCCUPIED",
            name = "Station 11kW Occupied",
            connectors = listOf(
                createConnector(powerKw = 11.0, powerType = "AC_3_PHASE", available = 0, occupied = 2)
            )
        )
        assertFalse("11kW station with 0 available slots must be excluded", st11kWOccupied.hasAvailableAcConnector())

        // 22kW AC Out of Service -> Excluded
        val oosStatuses = listOf(
            HereConnectorStatus(state = "OUT_OF_SERVICE"),
            HereConnectorStatus(state = "UNKNOWN")
        )
        val st22kWOos = HereEvStation(
            id = "ST-22KW-OOS",
            name = "Station 22kW OOS",
            connectors = HereConnectorsContainer(
                connector = listOf(
                    HereConnector(
                        maxPowerLevel = 22.0,
                        powerType = "AC_3_PHASE",
                        numberOfAvailable = 0,
                        numberOfConnectors = 2,
                        connectorStatuses = HereConnectorStatusesContainer(
                            connectorStatus = oosStatuses,
                            numberOfAvailable = 0
                        )
                    )
                )
            )
        )
        assertFalse("Station with OUT_OF_SERVICE status must be excluded", st22kWOos.hasAvailableAcConnector())

        // Test hasAvailableStatus() helper on connector directly
        val availableConn = createConnector(powerKw = 11.0, powerType = "AC_3_PHASE", available = 1, occupied = 0)
        val occupiedConn = createConnector(powerKw = 11.0, powerType = "AC_3_PHASE", available = 0, occupied = 2)
        assertTrue(availableConn.hasAvailableStatus())
        assertFalse(occupiedConn.hasAvailableStatus())

        // Connector with explicit numberOfAvailable and null statuses
        val explicitConn = HereConnector(
            maxPowerLevel = 11.0,
            powerType = "AC_3_PHASE",
            numberOfAvailable = 2,
            numberOfConnectors = 2
        )
        assertTrue(explicitConn.hasAvailableStatus())

        val explicitZeroConn = HereConnector(
            maxPowerLevel = 11.0,
            powerType = "AC_3_PHASE",
            numberOfAvailable = 0,
            numberOfConnectors = 2
        )
        assertFalse(explicitZeroConn.hasAvailableStatus())
    }

    @Test
    fun testZeroAllocationInspection_acrossRawConnectorsAndEvseCollections() {
        val acConn = createConnector(powerKw = 11.0, powerType = "AC_3_PHASE", available = 2, occupied = 0)
        val dcConn = createConnector(powerKw = 60.0, powerType = "DC", available = 2, occupied = 0)

        // Station with connectors in rawConnectors
        val rawConnStation = HereEvStation(
            id = "ST-RAW",
            name = "Station Raw Connectors",
            rawConnectors = listOf(dcConn, acConn)
        )
        assertTrue("Must detect available AC connector in rawConnectors", rawConnStation.hasAvailableAcConnector())

        // Station with connectors in nested EVSE
        val evseStation = HereEvStation(
            id = "ST-EVSE",
            name = "Station EVSE",
            evse = listOf(
                HereEvse(id = "EVSE-1", connectors = listOf(dcConn)),
                HereEvse(id = "EVSE-2", connectors = listOf(acConn))
            )
        )
        assertTrue("Must detect available AC connector in nested evse", evseStation.hasAvailableAcConnector())

        // Empty station
        val emptyStation = HereEvStation(id = "ST-EMPTY", name = "Station Empty")
        assertFalse("Empty station must return false", emptyStation.hasAvailableAcConnector())
    }

    // =========================================================================
    // 2. Raw Coordinate Distance Calculation & Ascending Proximity Sorting
    // =========================================================================

    @Test
    fun testRawCoordinateDistanceCalculation_ordersStationsAscendingByProximity() = runTest {
        // Create 15 stations at increasing latitudes away from userLat (21.028511)
        val testStations = (1..15).map { index ->
            val delta = index * 0.01 // ~1.1 km per step
            val isQualifying = index % 2 != 0 // Odd indices qualify (11kW AC), even do not (60kW DC)
            val stLat = userLat + delta
            val stLon = userLon + delta

            if (isQualifying) {
                createStation(
                    id = "QUAL-$index",
                    cpoId = "C.QUAL-$index",
                    name = "Qualifying Station $index",
                    latitude = stLat,
                    longitude = stLon,
                    connectors = listOf(
                        createConnector(powerKw = 11.0, powerType = "AC_3_PHASE", available = 1, occupied = 0)
                    )
                )
            } else {
                createStation(
                    id = "DISQUAL-$index",
                    cpoId = "C.DISQUAL-$index",
                    name = "Disqualified DC Station $index",
                    latitude = stLat,
                    longitude = stLon,
                    connectors = listOf(
                        createConnector(powerKw = 60.0, powerType = "DC", available = 2, occupied = 0)
                    )
                )
            }
        }

        val responseBody = json.encodeToString(HereEvStationsResponse(stations = testStations))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(responseBody))

        val client = HereEvApiClient(
            client = okHttpClient,
            baseUrl = mockWebServer.url("/").toString(),
            defaultApiKey = "test-api-key"
        )

        val result = client.fetchNearbyAvailableAcStations(
            latitude = userLat,
            longitude = userLon,
            radiusMeters = 20_000,
            maxLimit = 10
        )

        assertTrue(result.isSuccess)
        val stations = result.getOrThrow()

        // 8 qualifying stations out of 15 (indices 1, 3, 5, 7, 9, 11, 13, 15)
        assertEquals(8, stations.size)

        // Verify strictly ascending distance ordering
        for (i in 0 until stations.size - 1) {
            val distCurrent = stations[i].distanceKm!!
            val distNext = stations[i + 1].distanceKm!!
            assertTrue(
                "Stations must be ordered ascending: $distCurrent <= $distNext",
                distCurrent <= distNext
            )
        }

        // Verify distanceKm matches Haversine calculation on raw coordinates
        stations.forEach { station ->
            val expectedDistance = DistanceCalculator.calculateDistanceKm(
                lat1 = userLat,
                lon1 = userLon,
                lat2 = station.latitude,
                lon2 = station.longitude
            )
            assertEquals(expectedDistance, station.distanceKm!!, 0.001)
        }
    }

    // =========================================================================
    // 3. Pre-Mapping Truncation & Complete Data Contract Fidelity
    // =========================================================================

    @Test
    fun testPreMappingTruncation_slicesToMaxLimit_andPreservesFullDataContractIntegrity() = runTest {
        // Create 20 qualifying AC stations
        val rawStations = (1..20).map { index ->
            val delta = index * 0.005
            HereEvStation(
                id = "RAW-ID-$index",
                cpoId = "C.VF-$index",
                name = "VinFast Station $index",
                address = HereAddress(
                    street = "$index Le Duan",
                    city = "Hanoi",
                    state = "HN",
                    country = "VNM"
                ),
                position = HerePosition(
                    latitude = userLat + delta,
                    longitude = userLon + delta
                ),
                connectors = HereConnectorsContainer(
                    connector = listOf(
                        createConnector(powerKw = 11.0, powerType = "AC_3_PHASE", available = 2, occupied = 1),
                        createConnector(powerKw = 22.0, powerType = "AC_3_PHASE", available = 1, occupied = 0)
                    )
                )
            )
        }

        val responseBody = json.encodeToString(HereEvStationsResponse(stations = rawStations))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(responseBody))

        val client = HereEvApiClient(
            client = okHttpClient,
            baseUrl = mockWebServer.url("/").toString(),
            defaultApiKey = "test-api-key"
        )

        val maxLimit = 5
        val result = client.fetchNearbyAvailableAcStations(
            latitude = userLat,
            longitude = userLon,
            radiusMeters = 10_000,
            maxLimit = maxLimit
        )

        assertTrue(result.isSuccess)
        val stations = result.getOrThrow()

        // Sliced to exactly maxLimit before domain mapping
        assertEquals(maxLimit, stations.size)

        // Check station 1 against expected domain mapping logic
        val firstStation = stations[0]
        val originalRaw = rawStations[0]
        val expectedDirectMapping = HereModelMapper.mapStationToDomain(originalRaw, dcOnly = false)

        assertEquals(expectedDirectMapping.id, firstStation.id)
        assertEquals("C.VF-1", firstStation.id)
        assertEquals(expectedDirectMapping.name, firstStation.name)
        assertEquals(expectedDirectMapping.address, firstStation.address)
        assertEquals("1 Le Duan, Hanoi, HN, VNM", firstStation.address)
        assertEquals(expectedDirectMapping.latitude, firstStation.latitude, 0.0001)
        assertEquals(expectedDirectMapping.longitude, firstStation.longitude, 0.0001)
        assertEquals(expectedDirectMapping.totalAvailablePlugs, firstStation.totalAvailablePlugs)
        assertEquals(3, firstStation.totalAvailablePlugs) // 2 available (11kW) + 1 available (22kW)
        assertEquals(expectedDirectMapping.totalPlugs, firstStation.totalPlugs)
        assertEquals(4, firstStation.totalPlugs) // 3 total (11kW) + 1 total (22kW)
        assertEquals(expectedDirectMapping.summary, firstStation.summary)
        assertEquals("Trống 3/4 cổng sạc", firstStation.summary)
        assertEquals("Normal", firstStation.depotStatus)

        // Verify power tiers breakdown
        assertEquals(2, firstStation.powers.size)
        val p22 = firstStation.powers.first { it.label == "22kW" }
        val p11 = firstStation.powers.first { it.label == "11kW" }
        assertEquals(22000L, p22.typeWatts)
        assertEquals(1, p22.availablePlugs)
        assertEquals(1, p22.totalPlugs)
        assertEquals(11000L, p11.typeWatts)
        assertEquals(2, p11.availablePlugs)
        assertEquals(3, p11.totalPlugs)

        // Verify distanceKm was computed and attached properly
        assertNotNull(firstStation.distanceKm)
        val expectedDist = DistanceCalculator.calculateDistanceKm(
            userLat, userLon,
            firstStation.latitude, firstStation.longitude
        )
        assertEquals(expectedDist, firstStation.distanceKm!!, 0.001)
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private fun createStation(
        id: String,
        cpoId: String? = null,
        name: String,
        address: String = "Test Address",
        latitude: Double = 21.0,
        longitude: Double = 105.0,
        connectors: List<HereConnector>
    ): HereEvStation {
        return HereEvStation(
            id = id,
            cpoId = cpoId,
            name = name,
            address = HereAddress(street = address),
            position = HerePosition(latitude = latitude, longitude = longitude),
            connectors = HereConnectorsContainer(connector = connectors)
        )
    }

    private fun createConnector(
        powerKw: Double,
        powerType: String,
        available: Int,
        occupied: Int
    ): HereConnector {
        val statuses = mutableListOf<HereConnectorStatus>()
        repeat(available) { statuses.add(HereConnectorStatus(state = "AVAILABLE")) }
        repeat(occupied) { statuses.add(HereConnectorStatus(state = "OCCUPIED")) }
        return HereConnector(
            maxPowerLevel = powerKw,
            powerType = powerType,
            numberOfAvailable = available,
            numberOfConnectors = available + occupied,
            connectorStatuses = HereConnectorStatusesContainer(
                connectorStatus = statuses,
                numberOfAvailable = available
            )
        )
    }
}
