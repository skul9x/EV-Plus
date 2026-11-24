package com.evcs.favorites.data.repository

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.api.RateLimitException
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.EvsePowerRaw
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.SearchStationRaw
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.network.here.HereEvApiClient
import com.evcs.favorites.domain.location.DistanceCalculator
import kotlinx.coroutines.CancellationException
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Single Comprehensive Verification Test Suite for Phase 01:
 * Multi-Tier Repository Fallback & Atomic Circuit Breaker Engine.
 *
 * Verifies:
 * 1. Constructor 100% binary and source compatibility (optional hereEvApiClient default = null).
 * 2. Normal EVCS success: HERE API fallback is never invoked.
 * 3. EVCS HTTP 403 Forbidden / Cloudflare WAF: triggers seamless fallback to HereEvApiClient.
 * 4. Domain distance parity: Haversine distance attached to fallback stations.
 * 5. Coordinate cache enrichment with dual-key aliases for fallback stations.
 * 6. Circuit breaker Open state: subsequent calls bypass EVCS and query HERE API directly.
 * 7. Half-Open probe success: cooldown expiration probes EVCS and resets circuit breaker upon success.
 * 8. Half-Open probe failure: repeated 403 re-trips circuit breaker cooldown.
 * 9. Rate limit bypass: when isGlobalRateLimited() is active and hereEvApiClient != null,
 *    routes through singleFlight to HERE fallback rather than returning RateLimitException.
 * 10. Rate limit short-circuit: when hereEvApiClient == null, returns RateLimitException.
 * 11. Dual-source failure: clean Result.failure propagation without crashes.
 * 12. Structured concurrency: CancellationException is strictly rethrown.
 * 13. Recoverable error discrimination: 403, 429, 5xx, timeout trigger fallback; non-recoverable errors do not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EvcsRepositoryFallbackResilienceTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeEvcsApiClient: FakeEvcsApiClient
    private lateinit var fakeHereEvApiClient: FakeHereEvApiClient
    private lateinit var coordinateCache: MutableMap<String, Pair<Double, Double>>
    private lateinit var repository: EvcsRepository

    private val userLat = 21.0285
    private val userLon = 105.8542

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val sessionStorage = InMemorySessionStorage()
        val sessionManager = SessionManager(sessionStorage)
        fakeEvcsApiClient = FakeEvcsApiClient(sessionManager)
        fakeHereEvApiClient = FakeHereEvApiClient()
        coordinateCache = mutableMapOf()

        repository = EvcsRepository(
            apiClient = fakeEvcsApiClient,
            coordinateCache = coordinateCache,
            ioDispatcher = testDispatcher,
            hereEvApiClient = fakeHereEvApiClient
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---------------------------------------------------------------------------------------------
    // FAKES
    // ---------------------------------------------------------------------------------------------

    class FakeEvcsApiClient(sessionManager: SessionManager) : EvcsApiClient(sessionManager = sessionManager) {
        var searchCallCount = 0
        var searchResultProvider: (Double, Double) -> Result<List<SearchStationRaw>> = { _, _ ->
            Result.success(emptyList())
        }

        override suspend fun searchStations(
            latitude: Double,
            longitude: Double,
            token: String,
            wattageTypes: List<String>?
        ): Result<List<SearchStationRaw>> {
            searchCallCount++
            return searchResultProvider(latitude, longitude)
        }

        override suspend fun searchStations(
            latitude: Double,
            longitude: Double,
            token: String
        ): Result<List<SearchStationRaw>> {
            return searchStations(latitude, longitude, token, null)
        }

        override suspend fun searchStations(
            latitude: Double,
            longitude: Double,
            wattageTypes: List<String>?
        ): Result<List<SearchStationRaw>> {
            return searchStations(latitude, longitude, DEFAULT_SEARCH_TOKEN, wattageTypes)
        }
    }

    class FakeHereEvApiClient : HereEvApiClient() {
        var fetchCallCount = 0
        var lastLatitude: Double? = null
        var lastLongitude: Double? = null
        var lastRadiusMeters: Int? = null
        var lastDcOnly: Boolean? = null
        var lastMaxResults: Int? = null

        var fallbackResultProvider: (Double, Double) -> Result<List<Station>> = { _, _ ->
            Result.success(emptyList())
        }

        override suspend fun fetchNearbyStations(
            latitude: Double,
            longitude: Double,
            radiusMeters: Int,
            dcOnly: Boolean,
            maxResults: Int
        ): Result<List<Station>> {
            fetchCallCount++
            lastLatitude = latitude
            lastLongitude = longitude
            lastRadiusMeters = radiusMeters
            lastDcOnly = dcOnly
            lastMaxResults = maxResults
            return fallbackResultProvider(latitude, longitude)
        }
    }

    private fun createEvcsStationRaw(
        id: String,
        name: String,
        lat: Double,
        lon: Double,
        evse: String = "VinFast"
    ): SearchStationRaw {
        return SearchStationRaw(
            id = id,
            locationId = id,
            stationName = name,
            stationAddress = "Test Address $name",
            latitude = lat,
            longitude = lon,
            evse = evse,
            depotStatus = "Normal",
            workingTimeDescription = "24/7",
            isPublic = true,
            isFreeParking = true,
            distance = 1.5,
            evsePowers = listOf(
                EvsePowerRaw(
                    type = 60000L,
                    numberOfAvailableEvse = 2,
                    totalEvse = 4,
                    powerType = "DC"
                )
            )
        )
    }

    private fun createHereStation(
        id: String,
        name: String,
        lat: Double,
        lon: Double,
        distanceKm: Double? = null
    ): Station {
        return Station(
            id = id,
            name = name,
            address = "HERE Address $name",
            latitude = lat,
            longitude = lon,
            summary = "24/7",
            connectors = "60kW: trống 1/2 cổng",
            depotStatus = "Normal",
            powers = listOf(
                PowerPort(
                    typeWatts = 60000L,
                    label = "60kW",
                    availablePlugs = 1,
                    totalPlugs = 2,
                    displayString = "60kW: trống 1/2 cổng"
                )
            ),
            totalAvailablePlugs = 1,
            totalPlugs = 2,
            distanceKm = distanceKm,
            evse = "VinFast"
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 1. CONSTRUCTOR COMPATIBILITY
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testConstructorCompatibility_DefaultHereClientIsNull() {
        val legacyRepo = EvcsRepository(
            apiClient = fakeEvcsApiClient,
            coordinateCache = coordinateCache,
            ioDispatcher = testDispatcher
        )
        assertNull("hereEvApiClient should default to null preserving 100% backward compatibility", legacyRepo.hereEvApiClient)
        assertFalse("Circuit breaker should start Closed", legacyRepo.isCircuitBreakerOpen())
        assertEquals(0L, legacyRepo.evcsWafBlockedUntil.get())
    }

    // ---------------------------------------------------------------------------------------------
    // 2. NORMAL EVCS SUCCESS
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testNormalEvcsSuccess_NoFallbackTriggered() = runTest {
        val expectedStation = createEvcsStationRaw("C.HN001", "VinFast BigC", 21.03, 105.85)
        fakeEvcsApiClient.searchResultProvider = { _, _ ->
            Result.success(listOf(expectedStation))
        }

        val result = repository.searchNearbyVinFast(userLat, userLon)

        assertTrue(result.isSuccess)
        val stations = result.getOrThrow()
        assertEquals(1, stations.size)
        assertEquals("C.HN001", stations[0].id)
        assertEquals("BigC", stations[0].name)
        assertEquals(1, fakeEvcsApiClient.searchCallCount)
        assertEquals(0, fakeHereEvApiClient.fetchCallCount)
        assertFalse(repository.isCircuitBreakerOpen())
        assertEquals(0L, repository.evcsWafBlockedUntil.get())
    }

    // ---------------------------------------------------------------------------------------------
    // 3. WAF 403 FORBIDDEN FALLBACK & DATA PARITY
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testEvcs403WafForbidden_TriggersHereFallback_AndPopulatesDistanceAndCoordinates() = runTest {
        fakeEvcsApiClient.searchResultProvider = { _, _ ->
            Result.failure(IOException("HTTP 403 Forbidden - Cloudflare Turnstile Challenge"))
        }

        val hereStationLat = 21.0500
        val hereStationLon = 105.8200
        val fallbackStation = createHereStation("c.here_vf_01", "VinFast Tay Ho", hereStationLat, hereStationLon, distanceKm = null)
        fakeHereEvApiClient.fallbackResultProvider = { _, _ ->
            Result.success(listOf(fallbackStation))
        }

        val result = repository.searchNearbyVinFast(userLat, userLon)

        assertTrue("Result should succeed via HERE fallback", result.isSuccess)
        val stations = result.getOrThrow()
        assertEquals(1, stations.size)

        val station = stations[0]
        assertEquals("c.here_vf_01", station.id)
        assertEquals("VinFast Tay Ho", station.name)

        // Parity verification: distanceKm calculated via DistanceCalculator
        val expectedDistance = DistanceCalculator.calculateDistanceKm(userLat, userLon, hereStationLat, hereStationLon)
        assertNotNull("distanceKm must be enriched", station.distanceKm)
        assertEquals(expectedDistance, station.distanceKm!!, 0.001)

        // Coordinate cache verification: dual-key aliases
        assertEquals(Pair(hereStationLat, hereStationLon), coordinateCache["c.here_vf_01"])
        assertEquals(Pair(hereStationLat, hereStationLon), coordinateCache["here_vf_01"])

        // Circuit breaker verification: tripped for 10 minutes
        assertTrue("Circuit breaker must be open", repository.isCircuitBreakerOpen())
        val blockedUntil = repository.evcsWafBlockedUntil.get()
        assertTrue(blockedUntil > System.currentTimeMillis() + 9 * 60 * 1000L)
        assertEquals(1, fakeEvcsApiClient.searchCallCount)
        assertEquals(1, fakeHereEvApiClient.fetchCallCount)
    }

    // ---------------------------------------------------------------------------------------------
    // 4. CIRCUIT BREAKER OPEN (COOLDOWN BYPASS)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testCircuitBreakerCooldown_SkipsEvcsOnSubsequentCalls() = runTest {
        // Manually trip circuit breaker into Open state (cooldown active for 10 minutes)
        repository.evcsWafBlockedUntil.set(System.currentTimeMillis() + EvcsRepository.WAF_COOLDOWN_MS)
        assertTrue(repository.isCircuitBreakerOpen())

        fakeHereEvApiClient.fallbackResultProvider = { _, _ ->
            Result.success(listOf(createHereStation("c.here_02", "VinFast Ba Dinh", 21.03, 105.83)))
        }

        val result = repository.searchNearbyVinFast(userLat, userLon)

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().size)

        // EVCS must have been completely bypassed!
        assertEquals("EVCS API must be bypassed while circuit breaker is Open", 0, fakeEvcsApiClient.searchCallCount)
        assertEquals(1, fakeHereEvApiClient.fetchCallCount)
    }

    // ---------------------------------------------------------------------------------------------
    // 5. HALF-OPEN PROBE RECOVERY
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testHalfOpenRecovery_CooldownExpired_EvcsProbeSucceeds_CircuitBreakerResets() = runTest {
        // Set cooldown timestamp to 1 second in the past (Half-Open state)
        repository.evcsWafBlockedUntil.set(System.currentTimeMillis() - 1000L)
        assertFalse("Cooldown has expired, so isCircuitBreakerOpen() is false", repository.isCircuitBreakerOpen())
        assertTrue("evcsWafBlockedUntil is still > 0 indicating pending probe", repository.evcsWafBlockedUntil.get() > 0L)

        // EVCS recovered and responds with success
        fakeEvcsApiClient.searchResultProvider = { _, _ ->
            Result.success(listOf(createEvcsStationRaw("C.HN002", "VinFast Recovered", 21.03, 105.85)))
        }

        val result = repository.searchNearbyVinFast(userLat, userLon)

        assertTrue(result.isSuccess)
        assertEquals("Recovered", result.getOrThrow()[0].name)
        assertEquals(1, fakeEvcsApiClient.searchCallCount)
        assertEquals(0, fakeHereEvApiClient.fetchCallCount)

        // Circuit breaker must be fully reset to 0L upon probe success
        assertEquals(0L, repository.evcsWafBlockedUntil.get())
        assertFalse(repository.isCircuitBreakerOpen())
    }

    @Test
    fun testHalfOpenProbeFailure_Repeated403_ReTripsCircuitBreaker() = runTest {
        // Expired cooldown (Half-Open probe)
        repository.evcsWafBlockedUntil.set(System.currentTimeMillis() - 1000L)

        // Probe fails with 403 again
        fakeEvcsApiClient.searchResultProvider = { _, _ ->
            Result.failure(IOException("HTTP 403 Forbidden - WAF Challenge persists"))
        }
        fakeHereEvApiClient.fallbackResultProvider = { _, _ ->
            Result.success(listOf(createHereStation("c.here_fallback", "HERE Station", 21.02, 105.84)))
        }

        val result = repository.searchNearbyVinFast(userLat, userLon)

        assertTrue(result.isSuccess)
        assertEquals(1, fakeEvcsApiClient.searchCallCount)
        assertEquals(1, fakeHereEvApiClient.fetchCallCount)

        // Must re-trip circuit breaker into future
        assertTrue(repository.isCircuitBreakerOpen())
        assertTrue(repository.evcsWafBlockedUntil.get() > System.currentTimeMillis() + 9 * 60 * 1000L)
    }

    // ---------------------------------------------------------------------------------------------
    // 6. GLOBAL RATE LIMIT BYPASS
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testRateLimitBypass_WhenGlobalRateLimitedActive_RoutesToHereFallback() = runTest {
        // Activate global rate limit
        repository.globalRateLimitedUntil.set(System.currentTimeMillis() + 60_000L)
        assertTrue(repository.isGlobalRateLimited())

        fakeHereEvApiClient.fallbackResultProvider = { _, _ ->
            Result.success(listOf(createHereStation("c.here_ratelimit_bypass", "VinFast RateLimit Bypass", 21.04, 105.86)))
        }

        val result = repository.searchNearbyVinFast(userLat, userLon)

        assertTrue("Should degrade gracefully to HERE fallback during global rate limit", result.isSuccess)
        val stations = result.getOrThrow()
        assertEquals(1, stations.size)
        assertEquals("VinFast RateLimit Bypass", stations[0].name)

        assertEquals("EVCS API must be bypassed completely during global rate limit", 0, fakeEvcsApiClient.searchCallCount)
        assertEquals(1, fakeHereEvApiClient.fetchCallCount)
    }

    @Test
    fun testRateLimitWithoutFallbackClient_ReturnsRateLimitException() = runTest {
        val noFallbackRepo = EvcsRepository(
            apiClient = fakeEvcsApiClient,
            coordinateCache = coordinateCache,
            ioDispatcher = testDispatcher,
            hereEvApiClient = null
        )
        noFallbackRepo.globalRateLimitedUntil.set(System.currentTimeMillis() + 60_000L)
        assertTrue(noFallbackRepo.isGlobalRateLimited())

        val result = noFallbackRepo.searchNearbyVinFast(userLat, userLon)

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue("Exception must be RateLimitException", exception is RateLimitException)
        assertEquals(0, fakeEvcsApiClient.searchCallCount)
    }

    // ---------------------------------------------------------------------------------------------
    // 7. MULTI-TIER FAILURE PROPAGATION
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testBothSourcesFail_PropagatesUnderlyingErrorCleanly() = runTest {
        fakeEvcsApiClient.searchResultProvider = { _, _ ->
            Result.failure(IOException("HTTP 403 Forbidden"))
        }
        fakeHereEvApiClient.fallbackResultProvider = { _, _ ->
            Result.failure(IOException("HERE API 503 Service Unavailable"))
        }

        val result = repository.searchNearbyVinFast(userLat, userLon)

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error is IOException)
        assertTrue(error!!.message!!.contains("HERE API 503"))
        assertEquals(1, fakeEvcsApiClient.searchCallCount)
        assertEquals(1, fakeHereEvApiClient.fetchCallCount)
    }

    // ---------------------------------------------------------------------------------------------
    // 8. STRUCTURED CONCURRENCY & CANCELLATION SAFETY
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testCancellationException_RethrownImmediatelyWithoutFallback() = runTest {
        fakeEvcsApiClient.searchResultProvider = { _, _ ->
            throw CancellationException("Coroutine scope cancelled by user")
        }

        try {
            repository.searchNearbyVinFast(userLat, userLon)
            fail("Expected CancellationException to be thrown directly")
        } catch (e: Throwable) {
            assertTrue("Must rethrow CancellationException", e is CancellationException)
            assertEquals("Coroutine scope cancelled by user", e.message)
        }

        // HERE API must not have been invoked
        assertEquals(0, fakeHereEvApiClient.fetchCallCount)
        assertFalse(repository.isCircuitBreakerOpen())
    }

    // ---------------------------------------------------------------------------------------------
    // 9. RECOVERABLE ERROR DISCRIMINATION
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testRecoverableErrorDiscrimination_TriggersFallbackOnRecoverableErrors() = runTest {
        val recoverableErrors = listOf(
            RateLimitException(retryAfterSeconds = 30, message = "HTTP 429 Too Many Requests"),
            IOException("Failed to connect to evcs.vn"),
            SocketTimeoutException("Read timed out"),
            IOException("HTTP 500 Internal Server Error"),
            IOException("HTTP 502 Bad Gateway"),
            IOException("HTTP 503 Service Unavailable"),
            IOException("HTTP 504 Gateway Timeout")
        )

        for ((index, error) in recoverableErrors.withIndex()) {
            fakeEvcsApiClient.searchCallCount = 0
            fakeHereEvApiClient.fetchCallCount = 0
            repository.evcsWafBlockedUntil.set(0L)

            fakeEvcsApiClient.searchResultProvider = { _, _ -> Result.failure(error) }
            fakeHereEvApiClient.fallbackResultProvider = { _, _ ->
                Result.success(listOf(createHereStation("c.rec_$index", "Station $index", 21.0, 105.8)))
            }

            val result = repository.searchNearbyVinFast(userLat, userLon)
            assertTrue("Should recover on error: ${error.javaClass.simpleName} - ${error.message}", result.isSuccess)
            assertEquals(1, fakeEvcsApiClient.searchCallCount)
            assertEquals(1, fakeHereEvApiClient.fetchCallCount)
        }
    }

    @Test
    fun testNonRecoverableError_DoesNotTriggerWafCooldown() = runTest {
        fakeEvcsApiClient.searchResultProvider = { _, _ ->
            Result.failure(IllegalArgumentException("Invalid parameter: latitude out of range"))
        }

        val result = repository.searchNearbyVinFast(userLat, userLon)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, fakeHereEvApiClient.fetchCallCount)
        assertFalse(repository.isCircuitBreakerOpen())
        assertEquals(0L, repository.evcsWafBlockedUntil.get())
    }
}
