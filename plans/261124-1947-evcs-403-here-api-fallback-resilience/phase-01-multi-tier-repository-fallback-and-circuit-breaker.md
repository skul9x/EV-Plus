# Phase 01: Multi-Tier Repository Fallback & Circuit Breaker Engine
Status: ✅ Completed
Dependencies: None

## Objective
Equip `EvcsRepository.searchNearbyVinFast()` with an enterprise-grade multi-tier fallback architecture and atomic circuit breaker state machine. When `EvcsApiClient.searchStations()` encounters recoverable infrastructure failures (specifically `HTTP 403 Forbidden` from Cloudflare WAF, `HTTP 429 Too Many Requests`, `5xx Server Errors`, or network timeouts), `EvcsRepository` will seamlessly degrade and query `HereEvApiClient.fetchNearbyStations()` without surfacing failures to callers. Additionally, implement an atomic cooldown circuit breaker that bypasses EVCS upon detecting a 403 WAF challenge to preserve sub-second discovery latency, ensures graceful Half-Open probing and recovery, respects Coroutine cancellation safety, and handles existing global rate limits without premature short-circuiting.

## Scope
- Modify `EvcsRepository` constructor to accept an optional `val hereEvApiClient: HereEvApiClient? = null` as the **last parameter** with default `= null`, preserving 100% binary and source compatibility for all 30+ existing call sites and unit tests.
- Update `EvcsRepository.searchNearbyVinFast` entry guard: when `isGlobalRateLimited()` is active, if `hereEvApiClient != null`, route through `singleFlight` directly to fallback instead of short-circuiting with `Result.failure(RateLimitException)`.
- Implement `isRecoverableSearchError(throwable: Throwable): Boolean` to detect 403, 429, 5xx, and socket/network transport exceptions, while strictly rethrowing `CancellationException` to preserve structured coroutine concurrency.
- Implement atomic circuit breaker state machine (`evcsWafBlockedUntil: AtomicLong`) with:
  - **Closed**: Requests route to EVCS.
  - **Open**: Active cooldown (default 10 minutes / 600,000ms `WAF_COOLDOWN_MS`) bypasses EVCS and routes directly to HERE API.
  - **Half-Open**: Upon cooldown expiration, the next request probes EVCS. If successful, resets circuit breaker (`evcsWafBlockedUntil.set(0L)`). If 403 recurs, re-trips cooldown.
- Update `searchNearbyVinFastInternal(lat, lon)` using named arguments: delegate to `hereEvApiClient.fetchNearbyStations(latitude = lat, longitude = lon, radiusMeters = 10_000, dcOnly = false, maxResults = 50)` on fallback.
- Attach Haversine distance (`distanceKm`) to fallback stations via `DistanceCalculator.calculateDistanceKm(lat, lon, st.latitude, st.longitude)` to maintain full parity with normal EVCS domain station models.
- Cache coordinates of resulting fallback stations into `coordinateCache` with dual-key aliases.
- Inject `hereEvApiClient` into `EvcsRepository` in `AppContainer.kt`.
- Author exactly one comprehensive file-based test: `EvcsRepositoryFallbackResilienceTest.kt`.

## Requirements
### Functional
- [x] Constructor Compatibility: Add `val hereEvApiClient: HereEvApiClient? = null` as the last parameter of `EvcsRepository` constructor with default `= null`.
- [x] Global Rate Limit Bypass: When `isGlobalRateLimited()` is true (e.g. from prior 429), `searchNearbyVinFast` must bypass EVCS and route directly to HERE API fallback if `hereEvApiClient != null`, rather than returning a premature `RateLimitException`.
- [x] WAF & Error Discrimination: Identify `HTTP 403` / `RateLimitException` / `IOException` / `SocketTimeoutException` / `5xx` as recoverable fallback triggers. Non-recoverable client validation errors must not trigger unnecessary fallback.
- [x] Cancellation Safety: `CancellationException` must be rethrown immediately and never treated as a network failure.
- [x] Circuit Breaker State Transitions:
  - When EVCS returns HTTP 403 (Cloudflare WAF challenge), set `evcsWafBlockedUntil = System.currentTimeMillis() + WAF_COOLDOWN_MS` (10 minutes / 600,000ms).
  - While cooldown is active, requests bypass EVCS entirely and query HERE API directly to avoid 5-second connection stalls.
  - Upon cooldown expiration (Half-Open), the next call probes EVCS. If EVCS succeeds, reset `evcsWafBlockedUntil.set(0L)`.
- [x] Domain Distance Parity: Compute and assign `distanceKm` for fallback stations via `DistanceCalculator.calculateDistanceKm(lat, lon, st.latitude, st.longitude)` so downstream consumers receive consistent domain models.
- [x] Coordinate Cache Population: Fallback stations have their coordinates cached into `coordinateCache` under both canonical and un-prefixed keys.
- [x] Graceful Degradation: If both EVCS and HERE API fail, propagate the underlying failure cleanly via `Result.failure`.
- [x] Dependency Injection: Ensure `DefaultAppContainer` passes `hereEvApiClient = hereEvApiClient` when instantiating `evcsRepository`.

### Non-Functional
- [x] Latency: Fallback during active circuit breaker cooldown must execute in under 500ms without blocking the Android Main thread (`withContext(ioDispatcher)`).
- [x] Thread Safety: `evcsWafBlockedUntil` must use thread-safe atomic primitives (`AtomicLong`) to safely guard concurrent coroutines.
- [x] Structured Concurrency: Preserves `singleFlight` request deduplication on identical coordinates.

## How to Implement
1. **Extend `EvcsRepository` Constructor:**
   In `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt`, add `val hereEvApiClient: HereEvApiClient? = null` as the last constructor parameter:
   ```kotlin
   open class EvcsRepository(
       private val apiClient: EvcsApiClient,
       // ... existing parameters ...
       val firestoreFavoritesRepository: FirestoreFavoritesRepository? = null,
       coroutineScope: CoroutineScope? = null,
       val hereEvApiClient: HereEvApiClient? = null
   ) {
   ```
2. **Add Circuit Breaker State & Constants:**
   ```kotlin
   companion object {
       // ... existing constants ...
       const val WAF_COOLDOWN_MS = 10 * 60 * 1000L // 10 minutes
   }

   val evcsWafBlockedUntil: AtomicLong = AtomicLong(0L)

   fun isCircuitBreakerOpen(): Boolean {
       val blockedUntil = evcsWafBlockedUntil.get()
       return blockedUntil > 0L && System.currentTimeMillis() < blockedUntil
   }

   private fun isRecoverableSearchError(e: Throwable): Boolean {
       if (e is CancellationException) return false
       val message = e.message.orEmpty().lowercase()
       return e is RateLimitException ||
              e is java.io.IOException ||
              message.contains("403") ||
              message.contains("forbidden") ||
              message.contains("challenge") ||
              message.contains("timeout") ||
              message.contains("429") ||
              message.contains("500") ||
              message.contains("502") ||
              message.contains("503") ||
              message.contains("504")
   }

   private fun isWafBlockedError(e: Throwable): Boolean {
       val message = e.message.orEmpty().lowercase()
       return message.contains("403") || message.contains("forbidden") || message.contains("challenge")
   }
   ```
3. **Refactor `searchNearbyVinFast` & `searchNearbyVinFastInternal`:**
   In `searchNearbyVinFast(lat, lon)`:
   ```kotlin
   if (isGlobalRateLimited()) {
       if (hereEvApiClient != null) {
           // Graceful degradation: bypass rate-limited EVCS and route to HERE fallback via singleFlight
           return singleFlight.execute("search_fallback_${"%.4f".format(Locale.US, lat)}_${"%.4f".format(Locale.US, lon)}") {
               executeHereFallback(lat, lon)
           }
       }
       val repoBlocked = globalRateLimitedUntil.get()
       val apiBlocked = apiClient.globalRateLimitedUntil.get()
       val blockedUntil = maxOf(repoBlocked, apiBlocked)
       val remainingSeconds = ((blockedUntil - System.currentTimeMillis() + 999L) / 1000L).coerceAtLeast(1L)
       return Result.failure(
           RateLimitException(
               retryAfterSeconds = remainingSeconds,
               message = "Search nearby VinFast rate limited: Cooldown active for ${remainingSeconds}s"
           )
       )
   }
   ```
   In `searchNearbyVinFastInternal(lat, lon)`:
   ```kotlin
   // 1. If circuit breaker is active or rate limited, immediately route to fallback
   if (hereEvApiClient != null && (isCircuitBreakerOpen() || isGlobalRateLimited())) {
       return@withContext executeHereFallback(lat, lon)
   }

   // 2. Attempt primary EVCS search
   val searchResult = apiClient.searchStations(latitude = lat, longitude = lon)
   if (searchResult.isFailure) {
       val error = searchResult.exceptionOrNull() ?: IOException("Failed to search nearby VinFast stations")
       if (error is CancellationException) throw error

       if (hereEvApiClient != null && isRecoverableSearchError(error)) {
           if (isWafBlockedError(error)) {
               evcsWafBlockedUntil.set(System.currentTimeMillis() + WAF_COOLDOWN_MS)
           }
           return@withContext executeHereFallback(lat, lon)
       }
       return@withContext Result.failure(error)
   }

   // 3. On success, reset circuit breaker if it was previously set (Half-Open probe success)
   if (evcsWafBlockedUntil.get() > 0L) {
       evcsWafBlockedUntil.set(0L)
   }

   // Process EVCS results as normal...
   ```
4. **Implement `executeHereFallback` Helper:**
   ```kotlin
   private suspend fun executeHereFallback(lat: Double, lon: Double): Result<List<Station>> {
       val client = hereEvApiClient ?: return Result.failure(IOException("HERE EV API client unavailable"))
       val fallbackResult = client.fetchNearbyStations(
           latitude = lat,
           longitude = lon,
           radiusMeters = 10_000,
           dcOnly = false,
           maxResults = 50
       )
       if (fallbackResult.isFailure) {
           return fallbackResult
       }

       val stations = fallbackResult.getOrThrow()
       // Attach distance and cache coordinates
       val enrichedStations = stations.map { st ->
           val cleanKey = st.id.trim().lowercase()
           if (cleanKey.isNotEmpty() && (st.latitude != 0.0 || st.longitude != 0.0)) {
               val coords = Pair(st.latitude, st.longitude)
               coordinateCache[cleanKey] = coords
               val unPrefixed = cleanKey.removePrefix("c.")
               if (unPrefixed != cleanKey) {
                   coordinateCache[unPrefixed] = coords
               } else {
                   coordinateCache["c.$cleanKey"] = coords
               }
           }
           if (st.distanceKm == null && st.latitude != 0.0 && st.longitude != 0.0) {
               st.copy(distanceKm = DistanceCalculator.calculateDistanceKm(lat, lon, st.latitude, st.longitude))
           } else {
               st
           }
       }
       saveCachedCoordinates()
       return Result.success(enrichedStations)
   }
   ```
5. **Wire Injections:**
   In `DefaultAppContainer.kt`, inject `hereEvApiClient = hereEvApiClient` when constructing `evcsRepository`.
6. **Create the Single Comprehensive Test File:**
   Author `app/src/test/java/com/evcs/favorites/data/repository/EvcsRepositoryFallbackResilienceTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt` - [MODIFY] Add `hereEvApiClient` (last param), atomic circuit breaker, rate-limit fallback bypass, distance calculation, coordinate caching, and error discrimination.
- `app/src/main/java/com/evcs/favorites/di/AppContainer.kt` - [MODIFY] Wire `hereEvApiClient = hereEvApiClient` into `EvcsRepository`.
- `app/src/test/java/com/evcs/favorites/data/repository/EvcsRepositoryFallbackResilienceTest.kt` - [NEW] Single comprehensive test suite validating EVCS 403 WAF fallback, circuit breaker cooldown state, Half-Open recovery, rate limit bypass, and multi-tier recovery.

## Acceptance Criteria
- [x] When EVCS API succeeds, results from EVCS are returned and HERE API is not invoked.
- [x] When EVCS API returns `HTTP 403 Forbidden` / Cloudflare WAF, `searchNearbyVinFast` automatically falls back to `hereEvApiClient` and returns domain stations with computed `distanceKm`.
- [x] When 403 is received, `evcsWafBlockedUntil` is updated to `now + 10 minutes`.
- [x] Subsequent calls within the 10-minute window bypass EVCS entirely and query HERE API directly.
- [x] When circuit breaker expires, the next call probes EVCS; if successful, circuit breaker resets to 0.
- [x] When `isGlobalRateLimited()` is active, `searchNearbyVinFast` bypasses EVCS and returns HERE fallback results if `hereEvApiClient != null`.
- [x] When both EVCS and HERE API fail, a clear `Result.failure` is propagated without crashing.
- [x] Structured concurrency is preserved: `CancellationException` is immediately rethrown.
- [x] The single comprehensive test file passes 100%.

## Exactly One Comprehensive File-Based Test
`app/src/test/java/com/evcs/favorites/data/repository/EvcsRepositoryFallbackResilienceTest.kt`
- Verifies normal EVCS success (no fallback triggered).
- Verifies EVCS 403 WAF triggers fallback to `HereEvApiClient` and populates domain stations with `distanceKm`.
- Verifies circuit breaker cooldown skips EVCS on immediate subsequent calls.
- Verifies Half-Open recovery when circuit breaker cooldown expires and EVCS probe succeeds.
- Verifies rate limit bypass when `isGlobalRateLimited()` is active.
- Verifies clean failure propagation when both sources fail.
- Verifies `CancellationException` propagation.

---
Next Phase: [phase-02-station-normalization-and-name-enrichment.md](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/261124-1947-evcs-403-here-api-fallback-resilience/phase-02-station-normalization-and-name-enrichment.md)
