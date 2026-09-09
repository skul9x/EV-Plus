package com.evcs.favorites.data.repository

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.SearchStationRaw
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.domain.filter.NearbyStationFilter
import com.evcs.favorites.domain.model.CustomFilterConfig
import com.evcs.favorites.domain.model.CustomFilterMode
import com.evcs.favorites.domain.model.DcWattageTier
import com.evcs.favorites.domain.model.QuickChipOption
import com.evcs.favorites.domain.model.SmartFilterMode
import com.evcs.favorites.domain.model.WattageOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive verification unit test for Phase 02:
 * Strict VinFast Search Filtering and Defense-in-Depth.
 *
 * Requirements verified:
 * 1. searchNearbyVinFast prunes 3rd-party stations (Ford, Đại lý Ford, Esky, Rabbit EVC, BitCharge, EBOOST, etc.).
 * 2. Case variations and whitespace ("VinFast", "vinfast", "VINFAST", "  VinFast  ") are matched and retained.
 * 3. Stations with null, empty, or non-VinFast evse are pruned from searchNearbyVinFast results.
 * 4. Coordinate cache is populated ONLY with coordinates of retained VinFast stations.
 * 5. Favorites flow (getFavorites) preserves user-saved partner/3rd-party stations.
 * 6. NearbyStationFilter.isVinFastStation accurately validates VinFast stations with whitespace/casing resilience.
 * 7. NearbyStationFilter.filterStations and filterSmartStations enforce VinFast validation as defense-in-depth across modes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NearbyVinFastFilteringTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockServer: MockWebServer
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var apiClient: EvcsApiClient
    private lateinit var repository: EvcsRepository
    private val coordinateCache = mutableMapOf<String, Pair<Double, Double>>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockServer = MockWebServer()
        mockServer.start()

        sessionStorage = InMemorySessionStorage()
        sessionManager = SessionManager(sessionStorage).apply {
            phpSessionId = "test_php_session"
            authCookie = "test_auth_cookie"
            csrfToken = "test_csrf_token"
        }

        apiClient = EvcsApiClient(
            sessionManager = sessionManager,
            baseUrl = mockServer.url("").toString().removeSuffix("/")
        )

        coordinateCache.clear()
        repository = EvcsRepository(
            apiClient = apiClient,
            coordinateCache = coordinateCache,
            cacheStorage = sessionStorage,
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
        Dispatchers.resetMain()
    }

    private fun createDomainStation(
        id: String,
        name: String,
        evse: String = "VinFast",
        depotStatus: String = "Normal",
        availablePlugs: Int = 2,
        totalPlugs: Int = 4,
        wattage: Long = 60_000L
    ): Station {
        return Station(
            id = id,
            name = name,
            address = "$name Address",
            latitude = 21.0285,
            longitude = 105.8542,
            summary = "24/7",
            connectors = "${wattage / 1000}kW",
            depotStatus = depotStatus,
            powers = listOf(
                PowerPort(
                    typeWatts = wattage,
                    label = "${wattage / 1000}kW",
                    availablePlugs = availablePlugs,
                    totalPlugs = totalPlugs,
                    displayString = "${wattage / 1000}kW: $availablePlugs/$totalPlugs"
                )
            ),
            totalAvailablePlugs = availablePlugs,
            totalPlugs = totalPlugs,
            evse = evse
        )
    }

    // ---------------------------------------------------------------------
    // 1. REPOSITORY searchNearbyVinFast STRICT FILTERING TESTS
    // ---------------------------------------------------------------------

    @Test
    fun testSearchNearbyVinFast_retainsOnlyVinFastAndPrunesPartnerNetworks() = runTest(testDispatcher) {
        val mixedJson = """
        {
          "code": 200000,
          "data": [
            {
              "locationId": "VF_01",
              "stationName": "VinFast Thao Dien",
              "stationAddress": "Thao Dien, Q2, HCMC",
              "latitude": 10.8031,
              "longitude": 106.7324,
              "evse": "VinFast",
              "depotStatus": "Normal",
              "evsePowers": [{"type": 60000, "numberOfAvailableEvse": 2, "totalEvse": 4}]
            },
            {
              "locationId": "VF_02_LOWER",
              "stationName": "VinFast Landmark 81",
              "stationAddress": "Binh Thanh, HCMC",
              "latitude": 10.7950,
              "longitude": 106.7218,
              "evse": "vinfast",
              "depotStatus": "Normal",
              "evsePowers": [{"type": 150000, "numberOfAvailableEvse": 1, "totalEvse": 2}]
            },
            {
              "locationId": "VF_03_UPPER",
              "stationName": "VinFast Ocean Park",
              "stationAddress": "Gia Lam, Hanoi",
              "latitude": 20.9902,
              "longitude": 105.9456,
              "evse": "VINFAST",
              "depotStatus": "Normal",
              "evsePowers": [{"type": 250000, "numberOfAvailableEvse": 3, "totalEvse": 4}]
            },
            {
              "locationId": "VF_04_SPACES",
              "stationName": "VinFast Smart City",
              "stationAddress": "Nam Tu Liem, Hanoi",
              "latitude": 21.0022,
              "longitude": 105.7428,
              "evse": "  VinFast  ",
              "depotStatus": "Normal",
              "evsePowers": [{"type": 60000, "numberOfAvailableEvse": 1, "totalEvse": 2}]
            },
            {
              "locationId": "FORD_01",
              "stationName": "Ford Long Bien",
              "stationAddress": "Long Bien, Hanoi",
              "latitude": 21.0450,
              "longitude": 105.8890,
              "evse": "Ford",
              "depotStatus": "Normal",
              "evsePowers": [{"type": 60000, "numberOfAvailableEvse": 2, "totalEvse": 2}]
            },
            {
              "locationId": "FORD_DEALER",
              "stationName": "Đại lý Ford Pho Quang",
              "stationAddress": "Tan Binh, HCMC",
              "latitude": 10.8055,
              "longitude": 106.6690,
              "evse": "Đại lý Ford",
              "depotStatus": "Normal",
              "evsePowers": [{"type": 30000, "numberOfAvailableEvse": 1, "totalEvse": 1}]
            },
            {
              "locationId": "ESKY_01",
              "stationName": "Esky Charging Hub",
              "stationAddress": "District 7, HCMC",
              "latitude": 10.7320,
              "longitude": 106.7110,
              "evse": "Esky",
              "depotStatus": "Normal",
              "evsePowers": [{"type": 60000, "numberOfAvailableEvse": 2, "totalEvse": 2}]
            },
            {
              "locationId": "RABBIT_01",
              "stationName": "Rabbit EVC Station",
              "stationAddress": "Cau Giay, Hanoi",
              "latitude": 21.0330,
              "longitude": 105.7920,
              "evse": "Rabbit EVC",
              "depotStatus": "Normal",
              "evsePowers": [{"type": 60000, "numberOfAvailableEvse": 1, "totalEvse": 2}]
            },
            {
              "locationId": "BITCHARGE_01",
              "stationName": "BitCharge Center",
              "stationAddress": "Ba Dinh, Hanoi",
              "latitude": 21.0350,
              "longitude": 105.8320,
              "evse": "BitCharge",
              "depotStatus": "Normal",
              "evsePowers": [{"type": 11000, "numberOfAvailableEvse": 1, "totalEvse": 2}]
            },
            {
              "locationId": "EBOOST_01",
              "stationName": "EBOOST Condo Point",
              "stationAddress": "Thu Duc, HCMC",
              "latitude": 10.8200,
              "longitude": 106.7700,
              "evse": "EBOOST",
              "depotStatus": "Normal",
              "evsePowers": [{"type": 7000, "numberOfAvailableEvse": 1, "totalEvse": 1}]
            },
            {
              "locationId": "NULL_EVSE",
              "stationName": "Unknown Provider Station",
              "stationAddress": "Somewhere",
              "latitude": 21.0100,
              "longitude": 105.8100,
              "evse": null,
              "depotStatus": "Normal",
              "evsePowers": [{"type": 60000, "numberOfAvailableEvse": 1, "totalEvse": 1}]
            },
            {
              "locationId": "EMPTY_EVSE",
              "stationName": "Empty String Provider Station",
              "stationAddress": "Somewhere",
              "latitude": 21.0150,
              "longitude": 105.8150,
              "evse": "",
              "depotStatus": "Normal",
              "evsePowers": [{"type": 60000, "numberOfAvailableEvse": 1, "totalEvse": 1}]
            },
            {
              "locationId": "WHITESPACE_EVSE",
              "stationName": "Whitespace Only Provider Station",
              "stationAddress": "Somewhere",
              "latitude": 21.0160,
              "longitude": 105.8160,
              "evse": "   ",
              "depotStatus": "Normal",
              "evsePowers": [{"type": 60000, "numberOfAvailableEvse": 1, "totalEvse": 1}]
            }
          ]
        }
        """.trimIndent()

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(mixedJson))

        val result = repository.searchNearbyVinFast(lat = 10.8, lon = 106.7)
        assertTrue("searchNearbyVinFast should succeed", result.isSuccess)

        val stations = result.getOrThrow()

        // Exactly 4 VinFast stations must be returned
        assertEquals(4, stations.size)

        val returnedIds = stations.map { it.id }.toSet()
        val expectedIds = setOf("VF_01", "VF_02_LOWER", "VF_03_UPPER", "VF_04_SPACES")
        assertEquals(expectedIds, returnedIds)

        // Ensure 3rd-party and invalid evse are entirely pruned
        val excludedIds = setOf(
            "FORD_01", "FORD_DEALER", "ESKY_01", "RABBIT_01",
            "BITCHARGE_01", "EBOOST_01", "NULL_EVSE", "EMPTY_EVSE", "WHITESPACE_EVSE"
        )
        for (excluded in excludedIds) {
            assertFalse("Station $excluded must be excluded from searchNearbyVinFast", returnedIds.contains(excluded))
        }

        // Verify coordinateCache was updated ONLY for the VinFast stations
        for (id in expectedIds) {
            val key = id.lowercase()
            assertTrue("coordinateCache must contain VinFast key $key", coordinateCache.containsKey(key))
        }
        for (id in excludedIds) {
            val key = id.lowercase()
            assertFalse("coordinateCache must NOT contain excluded station key $key", coordinateCache.containsKey(key))
        }
    }

    // ---------------------------------------------------------------------
    // 2. FAVORITES PRESERVATION TESTS
    // ---------------------------------------------------------------------

    @Test
    fun testGetFavorites_preservesSavedPartnerStations() = runTest(testDispatcher) {
        // User has saved both a VinFast station and partner stations (Ford, Esky) to favorites
        val favoritesJson = """
        {
          "sync": true,
          "csrf": "csrf_fav_test",
          "server": [
            {
              "locationId": "VF_FAV_01",
              "name": "VinFast - TTTM Vincom Metropolis",
              "address": "29 Lieu Giai, Hanoi",
              "connectors": "60kW, 250kW"
            },
            {
              "locationId": "FORD_FAV_01",
              "name": "Ford Dealer - Long Bien",
              "address": "Long Bien, Hanoi",
              "connectors": "60kW"
            },
            {
              "locationId": "ESKY_FAV_01",
              "name": "Esky Charging Hub - Q7",
              "address": "Q7, HCMC",
              "connectors": "11kW"
            }
          ]
        }
        """.trimIndent()

        // Cluster search response returns enriched data for these stations
        val searchJson = """
        {
          "code": 200000,
          "data": [
            {
              "locationId": "VF_FAV_01",
              "stationName": "VinFast - TTTM Vincom Metropolis",
              "stationAddress": "29 Lieu Giai, Hanoi",
              "latitude": 21.0315,
              "longitude": 105.8150,
              "evse": "VinFast",
              "depotStatus": "Normal",
              "evsePowers": [{"type": 60000, "numberOfAvailableEvse": 2, "totalEvse": 4}]
            },
            {
              "locationId": "FORD_FAV_01",
              "stationName": "Ford Dealer - Long Bien",
              "stationAddress": "Long Bien, Hanoi",
              "latitude": 21.0450,
              "longitude": 105.8890,
              "evse": "Ford",
              "depotStatus": "Normal",
              "evsePowers": [{"type": 60000, "numberOfAvailableEvse": 1, "totalEvse": 2}]
            },
            {
              "locationId": "ESKY_FAV_01",
              "stationName": "Esky Charging Hub - Q7",
              "stationAddress": "Q7, HCMC",
              "latitude": 10.7320,
              "longitude": 106.7110,
              "evse": "Esky",
              "depotStatus": "Normal",
              "evsePowers": [{"type": 11000, "numberOfAvailableEvse": 1, "totalEvse": 2}]
            }
          ]
        }
        """.trimIndent()

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(favoritesJson))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(searchJson))

        val favResult = repository.getFavorites(userLat = 21.03, userLon = 105.82)
        assertTrue("getFavorites should succeed", favResult.isSuccess)

        val favorites = favResult.getOrThrow()

        // Crucial requirement: All 3 favorites must remain intact!
        assertEquals("All saved favorites must be preserved regardless of provider", 3, favorites.size)

        val vfFav = favorites.find { it.id == "VF_FAV_01" }
        assertNotNull(vfFav)
        assertEquals("VinFast", vfFav!!.evse)

        val fordFav = favorites.find { it.id == "FORD_FAV_01" }
        assertNotNull(fordFav)
        assertEquals("Ford", fordFav!!.evse)

        val eskyFav = favorites.find { it.id == "ESKY_FAV_01" }
        assertNotNull(eskyFav)
        assertEquals("Esky", eskyFav!!.evse)

        // Verify repository state flows reflect all 3 favorites
        assertEquals(3, repository.favoritesState.value.size)
        assertEquals(setOf("VF_FAV_01", "FORD_FAV_01", "ESKY_FAV_01"), repository.favoriteIdsState.value)
    }

    // ---------------------------------------------------------------------
    // 3. NearbyStationFilter.isVinFastStation HELPER TESTS
    // ---------------------------------------------------------------------

    @Test
    fun testIsVinFastStation_accuracyAndWhitespaceTolerance() {
        val standardVf = createDomainStation("ST_1", "VF", evse = "VinFast")
        assertTrue(NearbyStationFilter.isVinFastStation(standardVf))

        val lowerVf = createDomainStation("ST_2", "VF", evse = "vinfast")
        assertTrue(NearbyStationFilter.isVinFastStation(lowerVf))

        val upperVf = createDomainStation("ST_3", "VF", evse = "VINFAST")
        assertTrue(NearbyStationFilter.isVinFastStation(upperVf))

        val mixedCaseVf = createDomainStation("ST_4", "VF", evse = "vInFaSt")
        assertTrue(NearbyStationFilter.isVinFastStation(mixedCaseVf))

        val spacesVf = createDomainStation("ST_5", "VF", evse = "   VinFast   ")
        assertTrue(NearbyStationFilter.isVinFastStation(spacesVf))

        val tabNewlineVf = createDomainStation("ST_6", "VF", evse = "\tVinFast\n")
        assertTrue(NearbyStationFilter.isVinFastStation(tabNewlineVf))

        // Non-VinFast stations
        val fordStation = createDomainStation("ST_7", "Ford", evse = "Ford")
        assertFalse(NearbyStationFilter.isVinFastStation(fordStation))

        val dealerStation = createDomainStation("ST_8", "Ford Dealer", evse = "Đại lý Ford")
        assertFalse(NearbyStationFilter.isVinFastStation(dealerStation))

        val eskyStation = createDomainStation("ST_9", "Esky", evse = "Esky")
        assertFalse(NearbyStationFilter.isVinFastStation(eskyStation))

        val rabbitStation = createDomainStation("ST_10", "Rabbit", evse = "Rabbit EVC")
        assertFalse(NearbyStationFilter.isVinFastStation(rabbitStation))

        val bitChargeStation = createDomainStation("ST_11", "BitCharge", evse = "BitCharge")
        assertFalse(NearbyStationFilter.isVinFastStation(bitChargeStation))

        val eboostStation = createDomainStation("ST_12", "EBOOST", evse = "EBOOST")
        assertFalse(NearbyStationFilter.isVinFastStation(eboostStation))

        val emptyStation = createDomainStation("ST_13", "Empty", evse = "")
        assertFalse(NearbyStationFilter.isVinFastStation(emptyStation))

        val whitespaceStation = createDomainStation("ST_14", "Whitespace", evse = "    ")
        assertFalse(NearbyStationFilter.isVinFastStation(whitespaceStation))
    }

    // ---------------------------------------------------------------------
    // 4. DEFENSE-IN-DEPTH: NearbyStationFilter.filterStations & filterSmartStations
    // ---------------------------------------------------------------------

    @Test
    fun testNearbyStationFilter_defenseInDepthPrunesNonVinFastAcrossAllModes() {
        val validVf1 = createDomainStation("VF_1", "VinFast 1", evse = "VinFast", wattage = 60_000L)
        val validVf2 = createDomainStation("VF_2", "VinFast 2", evse = "  vinfast  ", wattage = 150_000L)
        val nonVfFord = createDomainStation("FORD_1", "Ford Station", evse = "Ford", wattage = 60_000L)
        val nonVfEsky = createDomainStation("ESKY_1", "Esky Station", evse = "Esky", wattage = 11_000L)
        val nonVfEmpty = createDomainStation("EMPTY_1", "Empty Evse", evse = "", wattage = 60_000L)

        val candidateList = listOf(validVf1, validVf2, nonVfFord, nonVfEsky, nonVfEmpty)

        // 4a. filterStations with empty selectedWattages
        val basicFiltered = NearbyStationFilter.filterStations(candidateList, emptySet())
        assertEquals(2, basicFiltered.size)
        assertEquals(setOf("VF_1", "VF_2"), basicFiltered.map { it.id }.toSet())

        // 4b. filterStations with specific selectedWattages
        val wattageFiltered = NearbyStationFilter.filterStations(candidateList, setOf(WattageOption.KW_60))
        assertEquals(1, wattageFiltered.size)
        assertEquals("VF_1", wattageFiltered[0].id)

        // 4c. filterSmartStations with SmartFilterMode.NONE
        val smartNone = NearbyStationFilter.filterSmartStations(candidateList, SmartFilterMode.NONE)
        assertEquals(2, smartNone.size)
        assertEquals(setOf("VF_1", "VF_2"), smartNone.map { it.id }.toSet())

        // 4d. filterSmartStations with SmartFilterMode.AC
        val acVf = createDomainStation("VF_AC", "VinFast AC", evse = "VINFAST", wattage = 22_000L)
        val acCandidateList = candidateList + acVf
        val smartAc = NearbyStationFilter.filterSmartStations(acCandidateList, SmartFilterMode.AC)
        assertEquals(1, smartAc.size)
        assertEquals("VF_AC", smartAc[0].id)

        // 4e. filterSmartStations with SmartFilterMode.DC
        val smartDc = NearbyStationFilter.filterSmartStations(candidateList, SmartFilterMode.DC, dcTier = DcWattageTier.GE_120KW)
        assertEquals(1, smartDc.size)
        assertEquals("VF_2", smartDc[0].id)

        // 4f. filterSmartStations with SmartFilterMode.CUSTOM (Quick chip)
        val customConfigQuickChip = CustomFilterConfig(mode = CustomFilterMode.QUICK_CHIP, quickChip = QuickChipOption.ALL)
        val smartCustom = NearbyStationFilter.filterSmartStations(candidateList, SmartFilterMode.CUSTOM, customConfig = customConfigQuickChip)
        assertEquals(2, smartCustom.size)
        assertEquals(setOf("VF_1", "VF_2"), smartCustom.map { it.id }.toSet())

        // 4g. filterSmartStations with SmartFilterMode.CUSTOM (Custom Range)
        val customConfigRange = CustomFilterConfig(mode = CustomFilterMode.CUSTOM_RANGE, minKw = 50, maxKw = 160)
        val smartRange = NearbyStationFilter.filterSmartStations(candidateList, SmartFilterMode.CUSTOM, customConfig = customConfigRange)
        assertEquals(2, smartRange.size)
        assertEquals(setOf("VF_1", "VF_2"), smartRange.map { it.id }.toSet())
    }
}
