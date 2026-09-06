package com.evcs.favorites

import com.evcs.favorites.data.network.AppOkHttpClientProvider
import com.evcs.favorites.data.network.vinfast.VinFastApiException
import com.evcs.favorites.data.network.vinfast.VinFastCAppApiClient
import com.evcs.favorites.data.network.vinfast.VinFastDeviceIdProvider
import kotlinx.coroutines.test.runTest
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
 * File-based comprehensive test suite for Phase 01: VinFast CAPP API Client & Headers.
 * Verifies header generation, query parameter separation, DTO parsing, 401 failure handling,
 * and 5-second fast-fail timeout.
 */
class VinFastCAppApiClientTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var client: VinFastCAppApiClient

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
        AppOkHttpClientProvider.reset()
        VinFastDeviceIdProvider.setDeviceIdForTesting("test-device-uuid-1234")
        client = VinFastCAppApiClient(baseUrl = mockServer.url("").toString().removeSuffix("/"))
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
        AppOkHttpClientProvider.reset()
        VinFastDeviceIdProvider.setDeviceIdForTesting(null)
    }

    @Test
    fun testHeaderAndQueryParamInjection() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"code":200,"message":"Success","data":[]}""")
        )

        val result = client.searchStations(lat = 21.0285, lon = 105.8542, page = 0, size = 50)
        assertTrue(result.isSuccess)

        val request = mockServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(request)
        val path = request!!.path.orEmpty()
        assertTrue("Request path should contain page=0&size=50: $path", path.contains("?page=0&size=50"))
        assertEquals("2.25.7", request.getHeader("X-APP-VERSION"))
        assertEquals("CAPP", request.getHeader("X-SERVICE-NAME"))
        assertEquals("android", request.getHeader("X-Device-Platform"))
        val deviceIdHeader = request.getHeader("X-Device-Identifier")
        assertNotNull(deviceIdHeader)
        assertTrue(deviceIdHeader!!.isNotBlank())
        assertEquals("test-device-uuid-1234", deviceIdHeader)
        assertEquals("android - test-device-uuid-1234 - 2.25.7", request.getHeader("User-Agent"))
        assertTrue(request.getHeader("Content-Type")?.equals("application/json; charset=utf-8", ignoreCase = true) == true)
        assertEquals("application/json", request.getHeader("Accept"))

        val body = request.body.readUtf8()
        assertTrue("Body must contain latitude 21.0285: $body", body.contains("\"latitude\":21.0285"))
        assertTrue("Body must contain longitude 105.8542: $body", body.contains("\"longitude\":105.8542"))
        assertFalse("Body must not contain page query parameter: $body", body.contains("\"page\""))
        assertFalse("Body must not contain size query parameter: $body", body.contains("\"size\""))
    }

    @Test
    fun testSuccessfulStationParsing() = runTest {
        val realisticPayload = """
        {
            "code": 200,
            "message": "Success",
            "data": [
                {
                    "locationId": "C.HNO11417",
                    "stationName": "Trạm Sạc VinFast Vincom Long Biên",
                    "stationAddress": "Khu đô thị Vinhomes Riverside, Long Biên, Hà Nội",
                    "hereId": "HERE-12345",
                    "latitude": 21.0456,
                    "longitude": 105.9012,
                    "numberOfAvailableEvse": 6,
                    "totalEvse": 8,
                    "connectors": [
                        {
                            "type": 30000,
                            "status": "Available",
                            "count": 4,
                            "total": 4,
                            "isLink": false,
                            "powerType": "DC"
                        },
                        {
                            "type": 60000,
                            "status": "Available",
                            "count": 2,
                            "total": 4,
                            "isLink": false,
                            "powerType": "DC"
                        }
                    ],
                    "images": [
                        {
                            "url": "https://cpo-prod-s3.vinfastauto.com/station_1.jpg",
                            "thumbnail": "https://cpo-prod-s3.vinfastauto.com/station_1_thumb.jpg"
                        }
                    ],
                    "isPublic": true,
                    "isFreeParking": false,
                    "isInWorkingTime": true,
                    "workingTimeDescription": "24/7",
                    "depotStatus": "OPEN"
                }
            ]
        }
        """.trimIndent()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(realisticPayload)
        )

        val result = client.searchStations(lat = 21.0456, lon = 105.9012)
        assertTrue(result.isSuccess)

        val stations = result.getOrThrow()
        assertTrue("Stations list must not be empty", stations.isNotEmpty())
        val station = stations[0]
        assertEquals("C.HNO11417", station.locationId)
        assertEquals("Trạm Sạc VinFast Vincom Long Biên", station.stationName)
        assertEquals(6, station.numberOfAvailableEvse)
        assertEquals(8, station.totalEvse)
        assertEquals(2, station.connectors?.size)
        assertEquals(4, station.connectors?.get(0)?.count)
        assertEquals("DC", station.connectors?.get(0)?.powerType)
        assertEquals(1, station.images?.size)
        assertEquals("https://cpo-prod-s3.vinfastauto.com/station_1.jpg", station.images?.get(0)?.url)
    }

    @Test
    fun testLocationInfoEndpoint() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"code":200,"message":"Success","data":[]}""")
        )

        val ids = listOf("C.HNO11417", "C.HNO11418")
        val result = client.getLocationInfo(ids)
        assertTrue(result.isSuccess)

        val request = mockServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(request)
        val path = request!!.path.orEmpty()
        assertTrue("Request path must contain location-info: $path", path.contains("/ccarcharging/api/v1/stations/location-info"))
        val body = request.body.readUtf8()
        assertEquals("""{"locationIds":["C.HNO11417","C.HNO11418"]}""", body)
    }

    @Test
    fun testHttp401UnauthorizedReturnsFailure() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"code":40300,"message":"Authenticate failed","data":null}""")
        )

        val result = client.searchStations(lat = 21.0, lon = 105.0)
        assertTrue("Expected failure for 401 unauthorized", result.isFailure)

        val exception = result.exceptionOrNull()
        assertTrue("Exception must be VinFastApiException", exception is VinFastApiException)
        val apiEx = exception as VinFastApiException
        assertEquals(40300, apiEx.code)
        assertEquals("Authenticate failed", apiEx.message)
    }

    @Test
    fun testNetworkTimeoutFastFail() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setBody("""{"code":200,"message":"Success","data":[]}""")
                .setBodyDelay(7, TimeUnit.SECONDS)
        )

        val startTime = System.currentTimeMillis()
        val result = client.searchStations(lat = 21.0285, lon = 105.8542)
        val elapsed = System.currentTimeMillis() - startTime

        assertTrue("Expected failure due to timeout", result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue("Exception must be VinFastApiException, was: $exception", exception is VinFastApiException)
        assertTrue("Timeout must abort cleanly around 5s (before 7s finishes), took $elapsed ms", elapsed < 6800)
        assertTrue("Elapsed time must be at least 4500ms for 5s timeout, took $elapsed ms", elapsed >= 4500)
    }
}
