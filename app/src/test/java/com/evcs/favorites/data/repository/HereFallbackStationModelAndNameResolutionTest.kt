package com.evcs.favorites.data.repository

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.cache.BoundedLruMap
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.network.here.HereEvApiClient
import com.evcs.favorites.data.network.here.model.HereAddress
import com.evcs.favorites.data.network.here.model.HereConnector
import com.evcs.favorites.data.network.here.model.HereConnectorStatus
import com.evcs.favorites.data.network.here.model.HereConnectorStatusesContainer
import com.evcs.favorites.data.network.here.model.HereConnectorsContainer
import com.evcs.favorites.data.network.here.model.HereEvStation
import com.evcs.favorites.data.network.here.model.HereModelMapper
import com.evcs.favorites.data.network.here.model.HerePosition
import com.evcs.favorites.domain.filter.NearbyStationFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Single Comprehensive Verification Test Suite for Phase 02:
 * Station Normalization, Coordinate Caching & Name Enrichment.
 *
 * Requirements covered:
 * 1. Safe Name Enrichment:
 *    - Generic names ("Trạm sạc VinFast", "VinFast", "Trụ sạc", "") with valid address are enriched to "VinFast - <Address>".
 *    - Blank/empty address guard: Never produces dangling dashes ("VinFast - "). Falls back to rawName or "Trạm sạc VinFast".
 *    - Authentic non-generic station names (e.g. "VinFast Landmark 81") are preserved verbatim.
 * 2. EVSE Operator Attribution & Downstream Filter Pass-Through:
 *    - Assigns evse = "VinFast" for:
 *      * cpoId starting with "c." (e.g. "c.bni0311", "C.HNI1234")
 *      * cpoId containing "vinfast"
 *      * rawName or resolvedName containing "vinfast"
 *    - Strictly passes downstream NearbyStationFilter.isVinFastStation(station) == true.
 *    - Correctly attributes non-VinFast stations:
 *      * Pure AC stations -> "AC" (filter returns false)
 *      * Explicit 3rd-party CPOs -> e.g. "EVN" (filter returns false)
 *      * Non-VinFast fast chargers -> "Partner" (filter returns false)
 * 3. Symmetric Dual-Key Coordinate Caching & Resolver:
 *    - Fallback stations cache coordinates under canonical key and alias ("c.<id>" <-> "<id>").
 *    - EvcsRepository.getStationCoordinates resolves both prefixed and un-prefixed keys case-insensitively.
 *    - Falls back to coordinateResolver when key is not cached.
 *    - Returns null cleanly for blank/empty locationId.
 * 4. Power Port & Connector Aggregation:
 *    - Accurate aggregation by normalized wattage tiers (30kW, 60kW, 150kW, 250kW, etc.).
 *    - DC vs AC filtering (dcOnly = true vs false).
 *    - Available vs total plugs computation.
 *    - depotStatus assignment ("Normal" vs "Occupied" when available == 0).
 * 5. End-to-End Fallback Integration:
 *    - Verifies repository fallback executes HERE mapping, populates coordinateCache with dual keys,
 *      and yields domain stations compatible with VinFast filters and favorites synchronization.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HereFallbackStationModelAndNameResolutionTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var coordinateCache: MutableMap<String, Pair<Double, Double>>
    private var mockResolverCalledWith: String? = null
    private val mockCoordinateResolver: (String) -> Pair<Double, Double>? = { locationId ->
        mockResolverCalledWith = locationId
        if (locationId == "resolved_loc_123") Pair(10.1234, 106.5678) else null
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        coordinateCache = BoundedLruMap(maxCapacity = 500)
        mockResolverCalledWith = null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // =========================================================================
    // 1. Station Name Enrichment & Blank Address Safety Guards
    // =========================================================================

    @Test
    fun testNameEnrichment_genericNameWithAddress_enrichesWithAddress() {
        val hereStation = createHereStation(
            id = "here_001",
            cpoId = "c.bni0311",
            name = "Trạm sạc VinFast",
            address = HereAddress(
                street = "Đường Trần Hưng Đạo",
                city = "Phố Mới",
                state = "Quế Võ"
            )
        )

        val station = HereModelMapper.mapStationToDomain(hereStation)

        assertEquals("VinFast - Đường Trần Hưng Đạo, Phố Mới, Quế Võ", station.name)
        assertEquals("Đường Trần Hưng Đạo, Phố Mới, Quế Võ", station.address)
        assertTrue(NearbyStationFilter.isVinFastStation(station))
    }

    @Test
    fun testNameEnrichment_genericVariants_enrichedConsistently() {
        val variants = listOf(
            "tram sac vinfast",
            "trạm sạc",
            "tram sac",
            "trụ sạc",
            "tru sac",
            "vinfast",
            "trạm sạc xe điện",
            "tram sac xe dien vinfast"
        )

        for (variant in variants) {
            val station = HereModelMapper.mapStationToDomain(
                createHereStation(
                    id = "here_var",
                    cpoId = "c.var123",
                    name = variant,
                    address = HereAddress(street = "Số 1 Đại Cồ Việt", city = "Hà Nội")
                )
            )
            assertEquals(
                "Variant '$variant' should be address enriched",
                "VinFast - Số 1 Đại Cồ Việt, Hà Nội",
                station.name
            )
        }
    }

    @Test
    fun testNameEnrichment_blankOrNullAddress_neverProducesDanglingDash() {
        // Case A: Generic name with null address
        val stationNullAddress = HereModelMapper.mapStationToDomain(
            createHereStation(
                id = "here_null_addr",
                cpoId = "c.bni0001",
                name = "Trạm sạc VinFast",
                address = null
            )
        )
        assertFalse("Must never produce trailing dash", stationNullAddress.name.endsWith("- "))
        assertEquals("Trạm sạc VinFast", stationNullAddress.name)

        // Case B: Generic name with empty address fields
        val stationEmptyAddress = HereModelMapper.mapStationToDomain(
            createHereStation(
                id = "here_empty_addr",
                cpoId = "c.bni0002",
                name = "VinFast",
                address = HereAddress(street = "   ", city = "", state = null)
            )
        )
        assertFalse("Must never produce trailing dash", stationEmptyAddress.name.endsWith("- "))
        assertEquals("VinFast", stationEmptyAddress.name)

        // Case C: Blank raw name with blank address
        val stationBlankBoth = HereModelMapper.mapStationToDomain(
            createHereStation(
                id = "here_blank_both",
                cpoId = "c.bni0003",
                name = "   ",
                address = null
            )
        )
        assertFalse("Must never produce trailing dash", stationBlankBoth.name.endsWith("- "))
        assertEquals("Trạm sạc VinFast", stationBlankBoth.name)
    }

    @Test
    fun testNameEnrichment_authenticCustomName_preservedVerbatim() {
        val authenticNames = listOf(
            "VinFast Landmark 81",
            "VinFast Mega Mall Thảo Điền",
            "Trạm sạc VinFast Ocean Park 2 - Zone A",
            "Vincom Plaza Bắc Giang",
            "Trạm Dừng Nghỉ Cao Tốc Long Thành"
        )

        for (name in authenticNames) {
            val station = HereModelMapper.mapStationToDomain(
                createHereStation(
                    id = "here_auth",
                    cpoId = "c.auth",
                    name = name,
                    address = HereAddress(street = "123 Đường ABC", city = "TP HCM")
                )
            )
            assertEquals("Authentic station name must be preserved verbatim", name, station.name)
        }
    }

    // =========================================================================
    // 2. EVSE Attribution & NearbyStationFilter Compatibility
    // =========================================================================

    @Test
    fun testEvseAttribution_cPrefixCpoId_attributedToVinFast() {
        // In Vietnam, CPO IDs starting with "c." are VinFast charging stations
        val hereStation = createHereStation(
            id = "here_cpo_c",
            cpoId = "c.bni0311",
            name = "Trạm sạc Công cộng", // Generic non-VinFast name
            address = HereAddress(street = "Quế Võ", city = "Bắc Ninh")
        )

        val station = HereModelMapper.mapStationToDomain(hereStation)

        assertEquals("VinFast", station.evse)
        assertTrue("Must pass NearbyStationFilter.isVinFastStation", NearbyStationFilter.isVinFastStation(station))
    }

    @Test
    fun testEvseAttribution_cpoIdContainsVinFast_attributedToVinFast() {
        val hereStation = createHereStation(
            id = "here_cpo_vf",
            cpoId = "VinFast-Auto-VN",
            name = "Trạm Dừng Chân",
            address = HereAddress(street = "Cao Tốc", city = "Đồng Nai")
        )

        val station = HereModelMapper.mapStationToDomain(hereStation)

        assertEquals("VinFast", station.evse)
        assertTrue("Must pass NearbyStationFilter.isVinFastStation", NearbyStationFilter.isVinFastStation(station))
    }

    @Test
    fun testEvseAttribution_enrichedNameContainsVinFast_attributedToVinFast() {
        // Station has null cpoId and generic name, but enriched name becomes "VinFast - <Address>"
        val hereStation = createHereStation(
            id = "here_no_cpo",
            cpoId = null,
            name = "Trạm sạc",
            address = HereAddress(street = "123 Nguyễn Trãi", city = "Hà Nội")
        )

        val station = HereModelMapper.mapStationToDomain(hereStation)

        assertEquals("VinFast - 123 Nguyễn Trãi, Hà Nội", station.name)
        assertEquals("VinFast", station.evse)
        assertTrue("Must pass NearbyStationFilter.isVinFastStation", NearbyStationFilter.isVinFastStation(station))
    }

    @Test
    fun testEvseAttribution_nonVinFastStations_correctlyCategorized() {
        // 1. Pure AC station (< 22kW) from third party
        val pureAcStation = createHereStation(
            id = "here_ac_thirdparty",
            cpoId = null,
            name = "Evolt Charging Hub",
            address = HereAddress(street = "Bạch Đằng", city = "Đà Nẵng"),
            connectors = listOf(
                createHereConnector(powerKw = 22.0, powerType = "AC_3_PHASE", available = 2, total = 2)
            )
        )
        val domainAc = HereModelMapper.mapStationToDomain(pureAcStation, dcOnly = false)
        assertEquals("AC", domainAc.evse)
        assertFalse("Pure AC station must NOT pass isVinFastStation", NearbyStationFilter.isVinFastStation(domainAc))

        // 2. Explicit third-party CPO (e.g. EVN) with DC fast charger
        val evnStation = createHereStation(
            id = "here_evn",
            cpoId = "EVN",
            name = "EVN Fast Charger",
            address = HereAddress(street = "Trần Phú", city = "Hà Nội"),
            connectors = listOf(
                createHereConnector(powerKw = 60.0, powerType = "DC", available = 1, total = 2)
            )
        )
        val domainEvn = HereModelMapper.mapStationToDomain(evnStation, dcOnly = true)
        assertEquals("EVN", domainEvn.evse)
        assertFalse("EVN station must NOT pass isVinFastStation", NearbyStationFilter.isVinFastStation(domainEvn))

        // 3. Third-party partner DC station without VinFast keyword
        val partnerStation = createHereStation(
            id = "here_partner",
            cpoId = null,
            name = "SolarEV Supercharger",
            address = HereAddress(street = "Phạm Văn Đồng", city = "TP HCM"),
            connectors = listOf(
                createHereConnector(powerKw = 150.0, powerType = "DC", available = 2, total = 4)
            )
        )
        val domainPartner = HereModelMapper.mapStationToDomain(partnerStation, dcOnly = true)
        assertEquals("Partner", domainPartner.evse)
        assertFalse("Partner station must NOT pass isVinFastStation", NearbyStationFilter.isVinFastStation(domainPartner))
    }

    // =========================================================================
    // 3. Symmetric Dual-Key Coordinate Caching & EvcsRepository Resolver
    // =========================================================================

    @Test
    fun testGetStationCoordinates_dualKeyResolution_prefixedAndUnprefixed() {
        val sessionStorage = InMemorySessionStorage()
        val sessionManager = SessionManager(sessionStorage)
        val fakeApiClient = FakeEvcsApiClient(sessionManager)
        val repository = EvcsRepository(
            apiClient = fakeApiClient,
            coordinateCache = coordinateCache,
            coordinateResolver = mockCoordinateResolver,
            ioDispatcher = testDispatcher
        )

        // Seed cache with prefixed key "c.bni0311"
        val expectedCoords = Pair(21.1833, 106.0667)
        coordinateCache["c.bni0311"] = expectedCoords

        // 1. Direct match with exact key
        assertEquals(expectedCoords, repository.getStationCoordinates("c.bni0311"))

        // 2. Case-insensitive prefixed lookup
        assertEquals(expectedCoords, repository.getStationCoordinates("C.BNI0311"))

        // 3. Un-prefixed lookup resolving prefixed cache entry
        assertEquals(expectedCoords, repository.getStationCoordinates("bni0311"))
        assertEquals(expectedCoords, repository.getStationCoordinates("BNI0311"))

        // Seed cache with un-prefixed key "hni0123"
        val hniCoords = Pair(21.0285, 105.8542)
        coordinateCache["hni0123"] = hniCoords

        // 4. Prefixed lookup resolving un-prefixed cache entry
        assertEquals(hniCoords, repository.getStationCoordinates("c.hni0123"))
        assertEquals(hniCoords, repository.getStationCoordinates("C.HNI0123"))
        assertEquals(hniCoords, repository.getStationCoordinates("hni0123"))
    }

    @Test
    fun testGetStationCoordinates_fallbackToCoordinateResolver() {
        val sessionStorage = InMemorySessionStorage()
        val sessionManager = SessionManager(sessionStorage)
        val fakeApiClient = FakeEvcsApiClient(sessionManager)
        val repository = EvcsRepository(
            apiClient = fakeApiClient,
            coordinateCache = coordinateCache,
            coordinateResolver = mockCoordinateResolver,
            ioDispatcher = testDispatcher
        )

        // Key not in cache -> falls back to coordinateResolver
        val result = repository.getStationCoordinates("resolved_loc_123")
        assertNotNull(result)
        assertEquals(Pair(10.1234, 106.5678), result)
        assertEquals("resolved_loc_123", mockResolverCalledWith)

        // Key not in cache and not in resolver -> returns null
        val notFound = repository.getStationCoordinates("unknown_loc_999")
        assertNull(notFound)
    }

    @Test
    fun testGetStationCoordinates_blankOrEmptyInput_returnsNullWithoutQueryingResolver() {
        val sessionStorage = InMemorySessionStorage()
        val sessionManager = SessionManager(sessionStorage)
        val fakeApiClient = FakeEvcsApiClient(sessionManager)
        val repository = EvcsRepository(
            apiClient = fakeApiClient,
            coordinateCache = coordinateCache,
            coordinateResolver = mockCoordinateResolver,
            ioDispatcher = testDispatcher
        )

        assertNull(repository.getStationCoordinates(""))
        assertNull(repository.getStationCoordinates("   "))
        assertNull(mockResolverCalledWith)
    }

    // =========================================================================
    // 4. Power Port Aggregation & Depot Status
    // =========================================================================

    @Test
    fun testPowerPortAggregation_dcOnly_aggregatesFastChargersOnly() {
        val hereStation = createHereStation(
            id = "vf_power_mix",
            cpoId = "c.power",
            name = "VinFast Charging Hub",
            connectors = listOf(
                createHereConnector(powerKw = 60.0, powerType = "DC", available = 2, total = 2),
                createHereConnector(powerKw = 150.0, powerType = "DC", available = 1, total = 2),
                createHereConnector(powerKw = 22.0, powerType = "AC_3_PHASE", available = 2, total = 2),
                createHereConnector(powerKw = 7.0, powerType = "AC_1_PHASE", available = 1, total = 1)
            )
        )

        // dcOnly = true -> Only 60kW and 150kW DC
        val dcDomain = HereModelMapper.mapStationToDomain(hereStation, dcOnly = true)
        assertEquals(2, dcDomain.powers.size)
        assertTrue("Contains 150kW port", dcDomain.powers.any { it.label == "150kW" })
        assertTrue("Contains 60kW port", dcDomain.powers.any { it.label == "60kW" })
        assertFalse("Must NOT contain 22kW AC port", dcDomain.powers.any { it.label == "22kW" })
        assertFalse("Must NOT contain 7kW scooter port", dcDomain.powers.any { it.label == "7kW" })
        assertEquals(3, dcDomain.totalAvailablePlugs) // 2 (60kW) + 1 (150kW)
        assertEquals(4, dcDomain.totalPlugs) // 2 + 2
        assertEquals("Trống 3/4 cổng sạc DC", dcDomain.summary)
        assertEquals("Normal", dcDomain.depotStatus)

        // dcOnly = false -> Car AC (22kW) included, 7kW scooter excluded
        val fullDomain = HereModelMapper.mapStationToDomain(hereStation, dcOnly = false)
        assertEquals(3, fullDomain.powers.size)
        assertTrue("Contains 22kW AC port", fullDomain.powers.any { it.label == "22kW" })
        assertFalse("Must NOT contain 7kW scooter port", fullDomain.powers.any { it.label == "7kW" })
        assertEquals(5, fullDomain.totalAvailablePlugs) // 3 DC + 2 AC
        assertEquals(6, fullDomain.totalPlugs) // 4 DC + 2 AC
        assertEquals("Trống 5/6 cổng sạc", fullDomain.summary)
    }

    @Test
    fun testPowerPortAggregation_allOccupied_setsOccupiedDepotStatus() {
        val hereStation = createHereStation(
            id = "vf_occupied",
            cpoId = "c.occ",
            name = "VinFast Station Full",
            connectors = listOf(
                createHereConnector(powerKw = 60.0, powerType = "DC", available = 0, total = 4)
            )
        )

        val domain = HereModelMapper.mapStationToDomain(hereStation, dcOnly = true)
        assertEquals(0, domain.totalAvailablePlugs)
        assertEquals(4, domain.totalPlugs)
        assertEquals("Occupied", domain.depotStatus)
        assertEquals("Trống 0/4 cổng sạc DC", domain.summary)
    }

    @Test
    fun testPowerPortAggregation_noConnectors_setsGracefulSummary() {
        val hereStation = createHereStation(
            id = "vf_no_conn",
            cpoId = "c.noconn",
            name = "VinFast Empty Station",
            connectors = emptyList()
        )

        val domain = HereModelMapper.mapStationToDomain(hereStation, dcOnly = true)
        assertEquals(0, domain.totalAvailablePlugs)
        assertEquals(0, domain.totalPlugs)
        assertEquals("Normal", domain.depotStatus)
        assertEquals("Không có súng sạc DC", domain.summary)
    }

    // =========================================================================
    // 5. End-to-End Fallback Integration & Symmetric Cache Verification
    // =========================================================================

    @Test
    fun testEndToEndFallback_enrichesStationNames_setsVinFastEvse_andCachesDualKeys() = runTest {
        val sessionStorage = InMemorySessionStorage()
        val sessionManager = SessionManager(sessionStorage)
        val fakeApiClient = FakeEvcsApiClient(sessionManager)
        // Make EVCS fail with 403 Forbidden to trigger HERE fallback
        fakeApiClient.searchStationsResult = Result.failure(IOException("HTTP 403 Forbidden: Cloudflare WAF Challenge"))

        val fallbackHereStation = createHereStation(
            id = "here_fb_01",
            cpoId = "c.bni0311",
            name = "Trạm sạc VinFast",
            address = HereAddress(
                street = "Đường Lý Thái Tổ",
                city = "Võ Cường",
                state = "Bắc Ninh"
            ),
            latitude = 21.1788,
            longitude = 106.0592,
            connectors = listOf(
                createHereConnector(powerKw = 60.0, powerType = "DC", available = 2, total = 4)
            )
        )
        val fakeHereClient = FakeHereEvApiClient(
            mockHereStations = listOf(fallbackHereStation)
        )

        val repository = EvcsRepository(
            apiClient = fakeApiClient,
            coordinateCache = coordinateCache,
            hereEvApiClient = fakeHereClient,
            ioDispatcher = testDispatcher
        )

        val result = repository.searchNearbyVinFast(lat = 21.0285, lon = 105.8542)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue("Fallback search should succeed", result.isSuccess)
        val stations = result.getOrThrow()
        assertEquals(1, stations.size)

        val station = stations[0]
        // 1. Station ID parity
        assertEquals("c.bni0311", station.id)

        // 2. Name enrichment parity
        assertEquals("VinFast - Đường Lý Thái Tổ, Võ Cường, Bắc Ninh", station.name)

        // 3. Filter compatibility parity
        assertEquals("VinFast", station.evse)
        assertTrue("Must pass NearbyStationFilter.isVinFastStation", NearbyStationFilter.isVinFastStation(station))

        // 4. Coordinates cached symmetrically in coordinateCache
        val expectedCoords = Pair(21.1788, 106.0592)
        assertEquals(expectedCoords, repository.getStationCoordinates("c.bni0311"))
        assertEquals(expectedCoords, repository.getStationCoordinates("bni0311"))
        assertEquals(expectedCoords, repository.getStationCoordinates("C.BNI0311"))

        // 5. Road distance attached
        assertNotNull(station.distanceKm)
        assertTrue("Distance should be positive", station.distanceKm!! > 0.0)
    }

    // =========================================================================
    // Fakes & Helper Methods
    // =========================================================================

    class FakeEvcsApiClient(sessionManager: SessionManager) : EvcsApiClient(sessionManager = sessionManager) {
        var searchStationsResult: Result<List<com.evcs.favorites.data.model.SearchStationRaw>> = Result.success(emptyList())

        override suspend fun searchStations(
            latitude: Double,
            longitude: Double,
            token: String,
            wattageTypes: List<String>?
        ): Result<List<com.evcs.favorites.data.model.SearchStationRaw>> {
            return searchStationsResult
        }

        override suspend fun searchStations(
            latitude: Double,
            longitude: Double,
            token: String
        ): Result<List<com.evcs.favorites.data.model.SearchStationRaw>> {
            return searchStationsResult
        }

        override suspend fun searchStations(
            latitude: Double,
            longitude: Double,
            wattageTypes: List<String>?
        ): Result<List<com.evcs.favorites.data.model.SearchStationRaw>> {
            return searchStationsResult
        }
    }

    class FakeHereEvApiClient(
        private val mockHereStations: List<HereEvStation> = emptyList()
    ) : HereEvApiClient() {
        override suspend fun fetchNearbyStations(
            latitude: Double,
            longitude: Double,
            radiusMeters: Int,
            dcOnly: Boolean,
            maxResults: Int
        ): Result<List<Station>> {
            val mapped = mockHereStations.map { HereModelMapper.mapStationToDomain(it, dcOnly = dcOnly) }
            return Result.success(mapped)
        }
    }

    private fun createHereStation(
        id: String,
        cpoId: String? = null,
        name: String,
        address: HereAddress? = null,
        latitude: Double = 21.0,
        longitude: Double = 105.0,
        connectors: List<HereConnector> = emptyList()
    ): HereEvStation {
        return HereEvStation(
            id = id,
            cpoId = cpoId,
            name = name,
            address = address,
            position = HerePosition(latitude = latitude, longitude = longitude),
            connectors = HereConnectorsContainer(connector = connectors)
        )
    }

    private fun createHereConnector(
        powerKw: Double,
        powerType: String,
        available: Int,
        total: Int
    ): HereConnector {
        val occupied = (total - available).coerceAtLeast(0)
        val statuses = mutableListOf<HereConnectorStatus>()
        repeat(available) { statuses.add(HereConnectorStatus(state = "AVAILABLE")) }
        repeat(occupied) { statuses.add(HereConnectorStatus(state = "OCCUPIED")) }
        return HereConnector(
            maxPowerLevel = powerKw,
            powerType = powerType,
            numberOfAvailable = available,
            numberOfConnectors = total,
            connectorStatuses = HereConnectorStatusesContainer(
                connectorStatus = statuses,
                numberOfAvailable = available
            )
        )
    }
}
