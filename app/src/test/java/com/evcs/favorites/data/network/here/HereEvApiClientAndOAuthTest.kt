package com.evcs.favorites.data.network.here

import com.evcs.favorites.data.network.here.model.HereConnector
import com.evcs.favorites.data.network.here.model.HereConnectorStatus
import com.evcs.favorites.data.network.here.model.HereConnectorsContainer
import com.evcs.favorites.data.network.here.model.HereEvStation
import com.evcs.favorites.data.network.here.model.HereModelMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
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
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Comprehensive verification test for Phase 02:
 * Hybrid Tier 1 HERE EV API & OAuth 1.0a Client Credentials.
 *
 * Requirements covered:
 * 1. Validates OAuth 1.0a HMAC-SHA256 signature generation against known test vectors.
 * 2. Verifies token reuse when fresh and automatic renewal when near expiry (< 10 minutes).
 * 3. Verifies thread-safe token acquisition under concurrent load without duplicate requests.
 * 4. Validates mock JSON parsing of multi-gun VinFast stations with accurate AVAILABLE/OCCUPIED
 *    slot counts, strict DC wattage categorization (>= 30kW), and AC exclusion.
 */
class HereEvApiClientAndOAuthTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var okHttpClient: OkHttpClient

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    // =========================================================================
    // 1. OAuth 1.0a HMAC-SHA256 Signing & Normalization Tests
    // =========================================================================

    @Test
    fun percentEncode_conformsStrictlyToRfc3986() {
        // Unreserved characters must not be encoded: [a-zA-Z0-9-._~]
        val unreserved = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~"
        assertEquals(unreserved, HereOAuthManager.percentEncode(unreserved))

        // Spaces must be %20 (not +)
        assertEquals("Hello%20World", HereOAuthManager.percentEncode("Hello World"))

        // Special characters
        assertEquals("%2A", HereOAuthManager.percentEncode("*"))
        assertEquals("%2B", HereOAuthManager.percentEncode("+"))
        assertEquals("%2F", HereOAuthManager.percentEncode("/"))
        assertEquals("%26", HereOAuthManager.percentEncode("&"))
        assertEquals("%3D", HereOAuthManager.percentEncode("="))
        assertEquals("%40", HereOAuthManager.percentEncode("@"))
    }

    @Test
    fun normalizeParameters_sortsLexicographicallyByNameThenValue() {
        val params = mapOf(
            "oauth_version" to "1.0",
            "oauth_consumer_key" to "key_123",
            "grant_type" to "client_credentials",
            "oauth_signature_method" to "HMAC-SHA256",
            "oauth_timestamp" to "1700000000",
            "oauth_nonce" to "nonce_abc"
        )

        val normalized = HereOAuthManager.normalizeParameters(params)

        val expected = "grant_type=client_credentials" +
            "&oauth_consumer_key=key_123" +
            "&oauth_nonce=nonce_abc" +
            "&oauth_signature_method=HMAC-SHA256" +
            "&oauth_timestamp=1700000000" +
            "&oauth_version=1.0"

        assertEquals(expected, normalized)
    }

    @Test
    fun buildSignatureBaseString_formatsCorrectlyAccordingToRfc5849() {
        val method = "POST"
        val url = "https://account.api.here.com/oauth2/token"
        val normalizedParams = "grant_type=client_credentials&oauth_consumer_key=key"

        val baseString = HereOAuthManager.buildSignatureBaseString(method, url, normalizedParams)

        val expected = "POST&https%3A%2F%2Faccount.api.here.com%2Foauth2%2Ftoken" +
            "&grant_type%3Dclient_credentials%26oauth_consumer_key%3Dkey"

        assertEquals(expected, baseString)
    }

    @Test
    fun computeHmacSha256_matchesKnownCryptographicTestVector() {
        // Standard test inputs
        val consumerSecret = "here_consumer_secret_xyz"
        val tokenSecret = "" // Client credentials (2-legged) has empty token secret
        val signingKey = "${consumerSecret}&${tokenSecret}"

        val baseString = "POST&https%3A%2F%2Faccount.api.here.com%2Foauth2%2Ftoken" +
            "&grant_type%3Dclient_credentials" +
            "%26oauth_consumer_key%3Dtest_consumer_key" +
            "%26oauth_nonce%3D987654321" +
            "%26oauth_signature_method%3DHMAC-SHA256" +
            "%26oauth_timestamp%3D1700000000" +
            "%26oauth_version%3D1.0"

        // Independent JVM reference computation
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(signingKey.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val expectedSignature = Base64.getEncoder().encodeToString(
            mac.doFinal(baseString.toByteArray(StandardCharsets.UTF_8))
        )

        val computedSignature = HereOAuthManager.computeHmacSha256(
            baseString = baseString,
            consumerSecret = consumerSecret,
            tokenSecret = tokenSecret
        )

        assertEquals(expectedSignature, computedSignature)
        assertTrue("Signature must be a non-empty base64 string", computedSignature.isNotBlank())
    }

    @Test
    fun buildAuthorizationHeader_producesValidOAuth1Header() {
        val header = HereOAuthManager.buildAuthorizationHeader(
            consumerKey = "my_key",
            nonce = "my_nonce",
            signature = "sig+123/abc=",
            timestamp = "1700000000"
        )

        assertTrue(header.startsWith("OAuth "))
        assertTrue(header.contains("oauth_consumer_key=\"my_key\""))
        assertTrue(header.contains("oauth_nonce=\"my_nonce\""))
        assertTrue(header.contains("oauth_signature_method=\"HMAC-SHA256\""))
        assertTrue(header.contains("oauth_timestamp=\"1700000000\""))
        assertTrue(header.contains("oauth_version=\"1.0\""))
        // Signature value should be percent-encoded inside quotes
        assertTrue(header.contains("oauth_signature=\"sig%2B123%2Fabc%3D\""))
    }

    // =========================================================================
    // 2. Token Lifecycle, Caching & Concurrent Acquisition Tests
    // =========================================================================

    @Test
    fun getAccessToken_reusesCachedTokenWhenFreshAndRefreshesWhenNearExpiry() = runTest {
        var simulatedEpochMs = 1_000_000_000L // initial time

        val tokenJson1 = """
            {
                "access_token": "token_session_001",
                "token_type": "bearer",
                "expires_in": 3600
            }
        """.trimIndent()

        val tokenJson2 = """
            {
                "access_token": "token_session_002_refreshed",
                "token_type": "bearer",
                "expires_in": 3600
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(tokenJson1))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(tokenJson2))

        val tokenUrl = mockWebServer.url("/oauth2/token").toString()

        val oauthManager = HereOAuthManager(
            accessKeyId = "test_key",
            accessKeySecret = "test_secret",
            tokenUrl = tokenUrl,
            client = okHttpClient,
            timeProvider = { simulatedEpochMs }
        )

        // 1. Initial acquisition at t = 0
        val firstToken = oauthManager.getAccessToken().getOrThrow()
        assertEquals("token_session_001", firstToken)
        assertEquals(1, oauthManager.networkRequestCount)

        // 2. Advance time by 30 minutes (1800s). Remaining validity = 1800s (> 10 minutes).
        // Must reuse cached token without any new network call.
        simulatedEpochMs += (1800 * 1000L)
        val reusedToken = oauthManager.getAccessToken().getOrThrow()
        assertEquals("token_session_001", reusedToken)
        assertEquals(1, oauthManager.networkRequestCount) // No network call made

        // 3. Advance time further to 51 minutes total elapsed (3060s).
        // Remaining validity = 3600 - 3060 = 540s (9 minutes).
        // Less than 10 minutes buffer remaining -> MUST trigger proactive refresh.
        simulatedEpochMs += (1260 * 1000L)
        val refreshedToken = oauthManager.getAccessToken().getOrThrow()
        assertEquals("token_session_002_refreshed", refreshedToken)
        assertEquals(2, oauthManager.networkRequestCount) // Second network call triggered

        // Verify request payload was correct
        val recordedRequest = mockWebServer.takeRequest()
        assertEquals("/oauth2/token", recordedRequest.path)
        assertEquals("POST", recordedRequest.method)
        assertTrue(recordedRequest.headers["Authorization"]?.startsWith("OAuth ") == true)
        assertEquals("grant_type=client_credentials", recordedRequest.body.readUtf8())
    }

    @Test
    fun getAccessToken_handlesConcurrentRequestsThreadSafely() = runTest {
        val tokenJson = """
            {
                "access_token": "token_thread_safe_abc",
                "token_type": "bearer",
                "expires_in": 7200
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(tokenJson))

        val oauthManager = HereOAuthManager(
            accessKeyId = "key_concurrent",
            accessKeySecret = "secret_concurrent",
            tokenUrl = mockWebServer.url("/oauth2/token").toString(),
            client = okHttpClient
        )

        // Launch 10 concurrent coroutines attempting to obtain the token simultaneously
        val deferredTokens = (1..10).map {
            async(Dispatchers.IO) {
                oauthManager.getAccessToken().getOrThrow()
            }
        }

        val results = deferredTokens.awaitAll()

        // All 10 callers must receive the exact same valid token
        results.forEach { token ->
            assertEquals("token_thread_safe_abc", token)
        }

        // Exactly one network call should have occurred
        assertEquals(1, oauthManager.networkRequestCount)
    }

    // =========================================================================
    // 3. HERE EV Stations API Client & Multi-Gun VinFast Parsing Tests
    // =========================================================================

    @Test
    fun fetchNearbyStations_parsesMultiGunVinFastStationsWithDcOnlyFilter() = runTest {
        // Mock token response for OAuthManager
        val tokenJson = """
            {
                "access_token": "bearer_here_ev_test",
                "token_type": "bearer",
                "expires_in": 86400
            }
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(tokenJson))

        // Mock stations JSON response with Landmark 81 and Sala stations
        val stationsJson = """
            {
                "evStations": {
                    "total": 2,
                    "evStation": [
                        {
                            "id": "VF-HCM-001",
                            "name": "VinFast - Landmark 81 B1 Parking",
                            "address": {
                                "street": "720A Dien Bien Phu",
                                "city": "Ho Chi Minh City",
                                "country": "Vietnam"
                            },
                            "position": {
                                "latitude": 10.7951,
                                "longitude": 106.7218
                            },
                            "connectors": {
                                "connector": [
                                    {
                                        "id": "conn_250kw",
                                        "maxPowerLevel": 250.0,
                                        "powerType": "DC",
                                        "connectorStatuses": {
                                            "connectorStatus": [
                                                {
                                                    "cpoEvseId": "EVSE_250_A",
                                                    "cpoEvseEMI3Id": "VN*VNF*E001*250A",
                                                    "state": "AVAILABLE",
                                                    "physicalReference": "Trụ 250kW - Súng 1"
                                                },
                                                {
                                                    "cpoEvseId": "EVSE_250_B",
                                                    "cpoEvseEMI3Id": "VN*VNF*E001*250B",
                                                    "state": "OCCUPIED",
                                                    "physicalReference": "Trụ 250kW - Súng 2"
                                                }
                                            ]
                                        }
                                    },
                                    {
                                        "id": "conn_60kw",
                                        "maxPowerLevel": 60.0,
                                        "powerType": "DC",
                                        "connectorStatuses": {
                                            "connectorStatus": [
                                                {
                                                    "cpoEvseId": "EVSE_60_1",
                                                    "cpoEvseEMI3Id": "VN*VNF*E001*60_1",
                                                    "state": "AVAILABLE",
                                                    "physicalReference": "Trụ 60kW - Súng 1"
                                                },
                                                {
                                                    "cpoEvseId": "EVSE_60_2",
                                                    "cpoEvseEMI3Id": "VN*VNF*E001*60_2",
                                                    "state": "AVAILABLE",
                                                    "physicalReference": "Trụ 60kW - Súng 2"
                                                },
                                                {
                                                    "cpoEvseId": "EVSE_60_3",
                                                    "cpoEvseEMI3Id": "VN*VNF*E001*60_3",
                                                    "state": "OCCUPIED",
                                                    "physicalReference": "Trụ 60kW - Súng 3"
                                                },
                                                {
                                                    "cpoEvseId": "EVSE_60_4",
                                                    "cpoEvseEMI3Id": "VN*VNF*E001*60_4",
                                                    "state": "OCCUPIED",
                                                    "physicalReference": "Trụ 60kW - Súng 4"
                                                }
                                            ]
                                        }
                                    },
                                    {
                                        "id": "conn_11kw_ac",
                                        "maxPowerLevel": 11.0,
                                        "powerType": "AC_3_PHASE",
                                        "connectorStatuses": {
                                            "connectorStatus": [
                                                {
                                                    "cpoEvseId": "EVSE_AC_1",
                                                    "cpoEvseEMI3Id": "VN*VNF*E001*AC1",
                                                    "state": "AVAILABLE",
                                                    "physicalReference": "Trụ AC 11kW"
                                                }
                                            ]
                                        }
                                    }
                                ]
                            }
                        },
                        {
                            "id": "VF-HCM-002",
                            "name": "VinFast - Sala Sarimi (Full DC)",
                            "address": {
                                "street": "10 Mai Chi Tho",
                                "city": "Ho Chi Minh City",
                                "country": "Vietnam"
                            },
                            "position": {
                                "latitude": 10.7712,
                                "longitude": 106.7189
                            },
                            "connectors": {
                                "connector": [
                                    {
                                        "id": "conn_150kw_full",
                                        "maxPowerLevel": 150000.0,
                                        "powerType": "DC",
                                        "connectorStatuses": {
                                            "connectorStatus": [
                                                {
                                                    "cpoEvseId": "EVSE_150_A",
                                                    "cpoEvseEMI3Id": "VN*VNF*E002*150A",
                                                    "state": "OCCUPIED"
                                                },
                                                {
                                                    "cpoEvseId": "EVSE_150_B",
                                                    "cpoEvseEMI3Id": "VN*VNF*E002*150B",
                                                    "state": "OCCUPIED"
                                                }
                                            ]
                                        }
                                    }
                                ]
                            }
                        }
                    ]
                }
            }
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(stationsJson))

        val oauthManager = HereOAuthManager(
            accessKeyId = "client_ev",
            accessKeySecret = "secret_ev",
            tokenUrl = mockWebServer.url("/oauth2/token").toString(),
            client = okHttpClient
        )

        val apiClient = HereEvApiClient(
            oauthManager = oauthManager,
            client = okHttpClient,
            baseUrl = mockWebServer.url("/ev").toString()
        )

        // Query with DC-only filter enabled (default)
        val stationsResult = apiClient.fetchNearbyStations(
            latitude = 10.7951,
            longitude = 106.7218,
            radiusMeters = 3000,
            dcOnly = true
        )

        assertTrue(stationsResult.isSuccess)
        val stations = stationsResult.getOrThrow()
        assertEquals(2, stations.size)

        // 1. Verify Station 1 (Landmark 81)
        val st1 = stations[0]
        assertEquals("VF-HCM-001", st1.id)
        assertEquals("VinFast - Landmark 81 B1 Parking", st1.name)
        assertEquals("720A Dien Bien Phu, Ho Chi Minh City, Vietnam", st1.address)
        assertEquals(10.7951, st1.latitude, 0.0001)
        assertEquals(106.7218, st1.longitude, 0.0001)

        // DC-only filter must strictly exclude 11kW AC connector
        assertEquals(2, st1.powers.size)

        // Powers are sorted descending by wattage (250kW, then 60kW)
        val power250 = st1.powers[0]
        assertEquals(250000L, power250.typeWatts)
        assertEquals("250kW", power250.label)
        assertEquals(1, power250.availablePlugs) // 1 available, 1 occupied
        assertEquals(2, power250.totalPlugs)
        assertEquals("250kW: trống 1/2 cổng", power250.displayString)

        val power60 = st1.powers[1]
        assertEquals(60000L, power60.typeWatts)
        assertEquals("60kW", power60.label)
        assertEquals(2, power60.availablePlugs) // 2 available, 2 occupied
        assertEquals(4, power60.totalPlugs)
        assertEquals("60kW: trống 2/4 cổng", power60.displayString)

        // Aggregated station metrics (DC only)
        assertEquals(3, st1.totalAvailablePlugs) // 1 (from 250kW) + 2 (from 60kW)
        assertEquals(6, st1.totalPlugs) // 2 (250kW) + 4 (60kW)
        assertEquals("Normal", st1.depotStatus)
        assertTrue(st1.summary.contains("Trống 3/6 cổng sạc DC"))

        // 2. Verify Station 2 (Sala Sarimi - Fully Occupied 150kW)
        val st2 = stations[1]
        assertEquals("VF-HCM-002", st2.id)
        assertEquals(1, st2.powers.size)

        val power150 = st2.powers[0]
        assertEquals(150000L, power150.typeWatts)
        assertEquals("150kW", power150.label)
        assertEquals(0, power150.availablePlugs) // 0 available
        assertEquals(2, power150.totalPlugs) // 2 total occupied

        assertEquals(0, st2.totalAvailablePlugs)
        assertEquals(2, st2.totalPlugs)
        assertEquals("Occupied", st2.depotStatus) // Marked Occupied since all DC slots are taken

        // 3. Verify outgoing HTTP request from ApiClient transmitted correct parameters & headers
        // Request 1 was token; Request 2 was stations
        mockWebServer.takeRequest() // token request
        val stationsRequest = mockWebServer.takeRequest()
        assertEquals("GET", stationsRequest.method)
        val path = stationsRequest.path.orEmpty()
        assertTrue("Path should point to /ev/stations.json", path.startsWith("/ev/stations.json"))
        assertTrue("Path should contain prox parameter", path.contains("prox=10.7951") && path.contains("106.7218") && path.contains("3000"))
        assertTrue("Path should contain maxresults parameter", path.contains("maxresults=50"))
        assertEquals("Bearer bearer_here_ev_test", stationsRequest.headers["Authorization"])
    }

    @Test
    fun fetchStationById_findsTargetStationAccurately() = runTest {
        val singleStationJson = """
            {
                "stations": [
                    {
                        "id": "ST-TARGET-360",
                        "name": "VinFast Supercharger - Da Nang",
                        "position": { "latitude": 16.0544, "longitude": 108.2022 },
                        "connectors": {
                            "connector": [
                                {
                                    "maxPowerLevel": 360.0,
                                    "powerType": "DC",
                                    "connectorStatuses": {
                                        "connectorStatus": [
                                            { "cpoEvseEMI3Id": "VN*VNF*DN*360A", "state": "AVAILABLE" },
                                            { "cpoEvseEMI3Id": "VN*VNF*DN*360B", "state": "OCCUPIED" }
                                        ]
                                    }
                                },
                                {
                                    "maxPowerLevel": 30.0,
                                    "powerType": "DC",
                                    "connectorStatuses": {
                                        "connectorStatus": [
                                            { "cpoEvseEMI3Id": "VN*VNF*DN*30A", "state": "AVAILABLE" },
                                            { "cpoEvseEMI3Id": "VN*VNF*DN*30B", "state": "AVAILABLE" }
                                        ]
                                    }
                                }
                            ]
                        }
                    }
                ]
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(singleStationJson))

        val apiClient = HereEvApiClient(
            client = okHttpClient,
            baseUrl = mockWebServer.url("/ev").toString(),
            defaultApiKey = "test_api_key"
        )

        val stationResult = apiClient.fetchStationById(
            stationId = "ST-TARGET-360",
            latitude = 16.0544,
            longitude = 108.2022,
            dcOnly = true
        )

        assertTrue(stationResult.isSuccess)
        val station = stationResult.getOrThrow()
        assertNotNull(station)
        assertEquals("ST-TARGET-360", station?.id)
        assertEquals("VinFast Supercharger - Da Nang", station?.name)
        assertEquals(2, station?.powers?.size)

        val power360 = station?.powers?.find { it.typeWatts == 360000L }
        assertNotNull(power360)
        assertEquals(1, power360?.availablePlugs)
        assertEquals(2, power360?.totalPlugs)

        val power30 = station?.powers?.find { it.typeWatts == 30000L }
        assertNotNull(power30)
        assertEquals(2, power30?.availablePlugs)
        assertEquals(2, power30?.totalPlugs)

        assertEquals(3, station?.totalAvailablePlugs)
        assertEquals(4, station?.totalPlugs)
    }

    @Test
    fun hereModelMapper_excludesAcAndLowPowerConnectorsWhenDcOnly() {
        val mixedConnectors = listOf(
            HereConnector(maxPowerLevel = 360.0, powerType = "DC"),
            HereConnector(maxPowerLevel = 250.0, powerType = "DC"),
            HereConnector(maxPowerLevel = 150.0, powerType = "DC"),
            HereConnector(maxPowerLevel = 60.0, powerType = "DC"),
            HereConnector(maxPowerLevel = 30.0, powerType = "DC"),
            HereConnector(maxPowerLevel = 11.0, powerType = "AC_3_PHASE"), // 11kW AC
            HereConnector(maxPowerLevel = 7.4, powerType = "AC_1_PHASE"),  // 7.4kW AC
            HereConnector(maxPowerLevel = 3.3, powerType = "AC")           // Motorcycle port
        )

        val dcOnlyAggs = HereModelMapper.aggregatePowerTiers(mixedConnectors, dcOnly = true)
        val powerWattsList = dcOnlyAggs.map { it.powerWatts }

        assertEquals(5, dcOnlyAggs.size)
        assertTrue(powerWattsList.contains(360000L))
        assertTrue(powerWattsList.contains(250000L))
        assertTrue(powerWattsList.contains(150000L))
        assertTrue(powerWattsList.contains(60000L))
        assertTrue(powerWattsList.contains(30000L))
        assertFalse("11kW AC must not be included", powerWattsList.contains(11000L))
        assertFalse("7.4kW AC must not be included", powerWattsList.contains(7000L))
        assertFalse("3.3kW AC must not be included", powerWattsList.contains(3000L))
    }
}
