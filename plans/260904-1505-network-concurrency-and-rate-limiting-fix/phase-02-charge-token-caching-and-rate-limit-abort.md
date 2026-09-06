# Phase 02: Charge Token Session Caching, Forecast Pacing & Instant 429 Abort
Status: ✅ Completed
Dependencies: Phase 01

## Objective
Reduce forecast network calls by 50% by caching `chargeToken` across stations, smooth burst request rates to avoid triggering Cloudflare rules, and immediately abort the entire forecast batch upon encountering HTTP 429 (Error 1015).

## Requirements
### Functional
- **Session-Level Charge Token Caching in `EvcsApiClient`**:
  - `chargeToken` format contains Unix epoch expiry in seconds as prefix (e.g. `1788509251.none.PjUEpiHh...` gives ~10 minutes validity).
  - Add `cachedChargeToken: String?` and `cachedChargeTokenExpiryMs: Long` to `EvcsApiClient`.
  - Calculate expiry by extracting numeric epoch seconds prefix:
    `val expiryMs = (token.substringBefore('.').toLongOrNull()?.times(1000L) ?: (System.currentTimeMillis() + 300_000L)) - 30_000L` (with a 30s safety buffer).
  - When `fetchChargingForecast` is invoked:
    - If `cachedChargeToken` is non-null and `System.currentTimeMillis() < cachedChargeTokenExpiryMs`, bypass Step 1 (station detail HTML fetch) and proceed directly to Step 2 (`POST /charging`) using the cached token.
    - If no valid token exists, perform Step 1, parse and cache the token, then proceed to Step 2.
    - If Step 2 returns 401/403 or invalid token error: clear token cache, re-fetch a fresh token via Step 1, update cache, and retry Step 2 once.
- **Structured Rate Limiting & `Retry-After` Parser**:
  - Create `RateLimitException(val retryAfterSeconds: Long, val isCloudflare1015: Boolean = true, message: String): IOException(message)`.
  - Create `RetryAfterParser` utility:
    - Parses integer seconds from `Retry-After` HTTP header (e.g. `"60"`).
    - Parses RFC 7231 / RFC 1123 HTTP-date from `Retry-After` header and calculates delta seconds to current time.
    - Inspects Cloudflare Error 1015 JSON response body for `"retry_after"\s*:\s*(\d+)`.
    - Defaults to 60 seconds if header is missing or unparseable.
  - When `fetchChargingForecast`, `searchStations`, or `fetchFavorites` encounters HTTP 429:
    - Parse `Retry-After` and throw `RateLimitException`.
- **Forecast Request Pacing & Concurrency Control**:
  - Increase inter-station stagger delay in `enrichStationsWithForecast` from 150ms to 250ms.
  - Decrease `forecastSemaphore` concurrency limit from 3 to 2 to prevent burst spikes.
- **Instant Batch Abort on HTTP 429 (Error 1015)**:
  - In `EvcsRepository`:
    - On receiving `RateLimitException` (or HTTP 429):
      - Set `globalRateLimitedUntil = System.currentTimeMillis() + (e.retryAfterSeconds * 1000L)`.
      - Log WARN with exact cooldown duration to `AppDebugLogger`.
    - In `enrichStationsWithForecast`:
      - When any station coroutine detects 429 / `RateLimitException`:
        - Instantly cancel the active batch scope (`coroutineContext.cancel()`), immediately terminating all waiting delays and in-flight station forecast tasks.
        - Catch cancellation safely and return the list of stations (enriched so far or fallback) without UI crash or error banner.
    - Pre-flight check: In `fetchStationForecast` and before Step 1 / Step 2 in `EvcsApiClient`, check `isGlobalRateLimited()`. If active, immediately return `Result.success(null)` without any network calls.

### Non-Functional
- Graceful degradation: never crash UI; all aborted and rate-limited queries return clean `null` forecasts.
- Thread-safety: Atomic state for rate-limit cooldown and mutex/synchronization for token cache renewal.
- Logging: Log accurate cooldown duration and batch abort events to `AppDebugLogger`.

## Implementation Steps
1. Create `RateLimitException.kt` under `com.evcs.favorites.data.api`.
2. Create `RetryAfterParser.kt` under `com.evcs.favorites.util` supporting seconds, HTTP-date, and Cloudflare JSON.
3. Update `EvcsApiClient.kt`:
   - Add token cache with epoch-based TTL extraction and auto-renewal on 401/403.
   - Throw `RateLimitException` when encountering HTTP 429 with parsed cooldown.
4. Update `EvcsRepository.kt`:
   - Tune `forecastSemaphore` to 2 and pacing delay to 250ms.
   - Update `globalRateLimitedUntil` from `RateLimitException.retryAfterSeconds`.
   - Implement instant batch cancellation in `enrichStationsWithForecast` on 429.
5. Create and run the single comprehensive test `ChargeTokenCachingAndRateLimitAbortTest.kt`.

## Files to Create/Modify
- [NEW] `app/src/main/java/com/evcs/favorites/data/api/RateLimitException.kt` - Structured HTTP 429 exception with cooldown duration.
- [NEW] `app/src/main/java/com/evcs/favorites/util/RetryAfterParser.kt` - Header and body parser for Retry-After.
- [MODIFY] `app/src/main/java/com/evcs/favorites/data/api/EvcsApiClient.kt` - Charge token session cache and 429 rate limit detection.
- [MODIFY] `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt` - Dynamic rate limit cooldown, pacing tuning, and instant batch abort.
- [NEW] `app/src/test/java/com/evcs/favorites/data/api/ChargeTokenCachingAndRateLimitAbortTest.kt` - Single comprehensive test for Phase 02.

## Test Criteria
- [x] Consecutive `fetchChargingForecast` calls reuse cached `chargeToken`, making 0 calls to station detail HTML pages for subsequent stations.
- [x] If `/charging` returns 401/403 or token expires, token cache is cleared and a fresh token is acquired via Step 1.
- [x] `RetryAfterParser` correctly parses seconds, HTTP-date formats, and Cloudflare JSON bodies.
- [x] When a station receives HTTP 429, `globalRateLimitedUntil` is dynamically set from `RateLimitException.retryAfterSeconds`.
- [x] In `enrichStationsWithForecast`, receiving HTTP 429 cancels the active batch immediately, resulting in 0 further network requests for remaining stations.

## Verification Command
```bash
./gradlew test --tests "com.evcs.favorites.data.api.ChargeTokenCachingAndRateLimitAbortTest"
```

---
Next Phase: [Phase 03: Station Deduplication & Coordinate Aggregation for OSRM Routing](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260904-1505-network-concurrency-and-rate-limiting-fix/phase-03-osrm-coordinate-deduplication.md)
