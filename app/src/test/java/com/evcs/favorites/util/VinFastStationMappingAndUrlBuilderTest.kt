package com.evcs.favorites.util

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.EvsePowerRaw
import com.evcs.favorites.data.model.SearchRequest
import com.evcs.favorites.data.model.SearchStationRaw
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.toDomainStation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive verification test for Phase 01:
 * VinFast Station Domain Modeling, Search Payload & URL Builder Fix.
 *
 * Verifies:
 * 1. Domain mapping from SearchStationRaw to Station preserves evse and defaults to "VinFast".
 * 2. Real captured station canonical URL generation (curl_capture_20260905_134301).
 * 3. Deduplication of "vinfast-" in URL builder to prevent "tram-sac-vinfast-vinfast-...".
 * 4. Handling of stations whose name is only "VinFast".
 * 5. Partner station URL generation and "-c.C." suffix deduplication.
 * 6. SearchRequest serializes to clean {"latitude": ..., "longitude": ...} without wattageTypes.
 * 7. EvcsApiClient.fetchStationHtml supports evse parameter and Station model overload.
 */
class VinFastStationMappingAndUrlBuilderTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var sessionManager: SessionManager
    private lateinit var apiClient: EvcsApiClient

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        sessionManager = SessionManager(InMemorySessionStorage())
        apiClient = EvcsApiClient(
            sessionManager = sessionManager,
            client = OkHttpClient(),
            baseUrl = mockWebServer.url("").toString().removeSuffix("/")
        )
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun testSearchStationRaw_toDomainStation_mapsEvseAndDefaultsCorrectly() {
        // 1a. Real captured station raw with explicit evse = "VinFast"
        val rawWithEvse = SearchStationRaw(
            locationId = "C.BNI0031",
            stationName = "Cửa hàng xăng dầu Tiến Minh Cách Bi",
            stationAddress = "Quế Võ, Bắc Ninh",
            latitude = 21.1388332,
            longitude = 106.1808943,
            evse = "VinFast",
            evsePowers = listOf(
                EvsePowerRaw(type = 60000L, numberOfAvailableEvse = 1, totalEvse = 2)
            )
        )
        val domainStation = rawWithEvse.toDomainStation()
        assertEquals("VinFast", domainStation.evse)
        assertEquals("C.BNI0031", domainStation.id)
        assertEquals("Cửa hàng xăng dầu Tiến Minh Cách Bi", domainStation.name)

        // 1b. Fallback when raw.evse is null -> defaults to "VinFast"
        val rawWithoutEvse = SearchStationRaw(
            locationId = "C.HN001",
            stationName = "Trạm Test",
            evse = null
        )
        val defaultStation = rawWithoutEvse.toDomainStation()
        assertEquals("VinFast", defaultStation.evse)

        // 1c. Partner station raw with explicit evse
        val partnerRaw = SearchStationRaw(
            locationId = "C.EVO001",
            stationName = "Audi Hà Nội",
            evse = "EV ONE"
        )
        val partnerStation = partnerRaw.toDomainStation()
        assertEquals("EV ONE", partnerStation.evse)
    }

    @Test
    fun testRealCapturedStationUrl_cBNI0031_matchesLiveWebCapture() {
        // Captured from live curl: /tram-sac-vinfast-cua-hang-xang-dau-tien-minh-cach-bi-c.bni0031.html
        val station = Station(
            id = "C.BNI0031",
            name = "Cửa hàng xăng dầu Tiến Minh Cách Bi",
            address = "Quế Võ, Bắc Ninh",
            latitude = 21.1388332,
            longitude = 106.1808943,
            summary = "24/7",
            connectors = "60kW",
            depotStatus = "Normal",
            evse = "VinFast"
        )

        val urlFromStation = StationUrlBuilder.buildStationDetailUrl(station)
        val urlDirect = StationUrlBuilder.buildStationDetailUrl(
            name = "Cửa hàng xăng dầu Tiến Minh Cách Bi",
            locationId = "C.BNI0031",
            evse = "VinFast"
        )

        val expected = "https://evcs.vn/tram-sac-vinfast-cua-hang-xang-dau-tien-minh-cach-bi-c.bni0031.html"
        assertEquals(expected, urlFromStation)
        assertEquals(expected, urlDirect)
    }

    @Test
    fun testVinFastStation_nameContainingVinFast_doesNotDuplicatePrefix() {
        val station = Station(
            id = "C.HN005",
            name = "VinFast Mega Mall Smart City",
            address = "Nam Từ Liêm, Hà Nội",
            latitude = 20.998,
            longitude = 105.748,
            summary = "24/7",
            connectors = "250kW",
            depotStatus = "Normal",
            evse = "VinFast"
        )

        val url = StationUrlBuilder.buildStationDetailUrl(station)
        val expected = "https://evcs.vn/tram-sac-vinfast-mega-mall-smart-city-c.hn005.html"

        assertEquals(expected, url)
        assertFalse("Must not contain duplicate vinfast-vinfast", url.contains("vinfast-vinfast"))
    }

    @Test
    fun testVinFastStation_nameOnlyVinFast_generatesCleanUrl() {
        val station = Station(
            id = "C.BNI0012",
            name = "VinFast",
            address = "Bắc Ninh",
            latitude = 21.14,
            longitude = 106.16,
            summary = "24/7",
            connectors = "30kW",
            depotStatus = "Normal",
            evse = "VinFast"
        )

        val url = StationUrlBuilder.buildStationDetailUrl(station)
        val expected = "https://evcs.vn/tram-sac-vinfast-c.bni0012.html"

        assertEquals(expected, url)
        assertFalse("Must not contain duplicate vinfast-vinfast", url.contains("vinfast-vinfast"))
    }

    @Test
    fun testPartnerStation_withCPrefix_normalizesPartnerIdWithoutDoubleSuffix() {
        val partnerStation = Station(
            id = "C.EVO001",
            name = "Audi Hà Nội",
            address = "Hà Nội",
            latitude = 21.0,
            longitude = 105.8,
            summary = "24/7",
            connectors = "150kW",
            depotStatus = "Normal",
            evse = "EV ONE"
        )

        val url = StationUrlBuilder.buildStationDetailUrl(partnerStation)
        val expected = "https://evcs.vn/tram-sac-ev-one-audi-ha-noi-c.evo001.html"

        assertEquals(expected, url)
        assertFalse("Must not contain duplicate -c.C. or -c.c.", url.contains("-c.c.") || url.contains("-c.C."))
        assertTrue("Must end with canonical -c.evo001.html", url.endsWith("-c.evo001.html"))
    }

    @Test
    fun testSearchRequest_serializesCleanJsonWithoutWattageTypes() {
        val request = SearchRequest(latitude = 21.1388332, longitude = 106.1808943)
        val jsonString = EvcsApiClient.json.encodeToString(request)

        assertFalse("SearchRequest JSON must not contain wattageTypes key", jsonString.contains("wattageTypes"))
        assertTrue("SearchRequest JSON must contain latitude", jsonString.contains("\"latitude\":21.1388332"))
        assertTrue("SearchRequest JSON must contain longitude", jsonString.contains("\"longitude\":106.1808943"))
        assertEquals("{\"latitude\":21.1388332,\"longitude\":106.1808943}", jsonString)
    }

    @Test
    fun testEvcsApiClient_fetchStationHtml_supportsEvseAndStationOverload() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("<html><head><title>Station Detail</title></head><body>OK</body></html>")
        )

        val station = Station(
            id = "C.BNI0031",
            name = "Cửa hàng xăng dầu Tiến Minh Cách Bi",
            address = "Bắc Ninh",
            latitude = 21.1388332,
            longitude = 106.1808943,
            summary = "24/7",
            connectors = "60kW",
            depotStatus = "Normal",
            evse = "VinFast"
        )

        val result = apiClient.fetchStationHtml(station)
        assertTrue("fetchStationHtml must succeed", result.isSuccess)
        assertEquals("<html><head><title>Station Detail</title></head><body>OK</body></html>", result.getOrThrow())

        val recordedRequest = mockWebServer.takeRequest()
        assertEquals("GET", recordedRequest.method)
        assertEquals(
            "/tram-sac-vinfast-cua-hang-xang-dau-tien-minh-cach-bi-c.bni0031.html",
            recordedRequest.path
        )
    }
}
