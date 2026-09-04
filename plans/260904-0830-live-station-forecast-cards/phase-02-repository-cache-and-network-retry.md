# Phase 02: Repository Cache & Network Retry Layer
Status: ✅ Completed
Dependencies: Phase 01

## Objective
Implement resilient network fetching and caching for station HTML detail pages in `EvcsApiClient` and `EvcsRepository`, featuring in-memory TTL caching (3 minutes), concurrent request throttling via `Semaphore(3)`, and silent background retry with exponential backoff and failure cooldown on transient failures.

## Requirements
### Functional
- [x] In `EvcsApiClient.kt`:
  - Implement `fetchStationHtml(stationName: String, locationId: String): Result<String>` using canonical URL from `StationUrlBuilder.buildStationDetailUrl(stationName, locationId, baseUrl)` with mobile browser headers (`USER_AGENT_BROWSER`, `Referer`, `Origin`, and optional session cookies) to prevent Cloudflare bot blocks.
- [x] In `ForecastCache.kt`:
  - Thread-safe `ConcurrentHashMap<String, ForecastCacheEntry>` with 3-minute (180,000 ms) TTL.
  - Temporary failure cooldown tracker (`ConcurrentHashMap<String, Long>`) with 1-minute (60,000 ms) cooldown.
  - Invalidation helpers: `invalidate(stationId: String)` and `clearAll()`.
- [x] In `EvcsRepository.kt`:
  - Implement `fetchStationForecast(station: Station, forceRefresh: Boolean = false): Result<StationForecast?>`:
    - If `forceRefresh`: invalidate cache and cooldown for station.
    - If cached and valid (< 180s): return cached forecast immediately.
    - If in failure cooldown (< 60s): return `Result.success(null)` immediately.
    - Execute `apiClient.fetchStationHtml(station.name, station.id)`.
    - On transient failure (`IOException` / timeout / HTTP 5xx):
      - Attempt 1 retry after 1000ms (+ jitter 0-300ms).
      - Attempt 2 retry after 2000ms (+ jitter 0-300ms).
    - If retries exhaust: record 1-minute cooldown, return `Result.success(null)` silently (never throw exception to UI).
    - On successful HTML retrieval: parse forecast via `StationForecastParser.parseForecastFromHtml(html)`, store in `ForecastCache`, return `Result.success(forecast)`.
  - Implement batch enrichment helper:
    - Enrich a list of full stations with concurrency throttled by `Semaphore(3)`.
    - Invalidate cache when manual refresh is requested.

### Non-Functional
- [x] Thread safety: synchronized or concurrent data structures (`ConcurrentHashMap`).
- [x] Silent failure: zero exceptions propagated to UI, safe degradation to red "Hết cổng".
- [x] Concurrency safety: strict `Semaphore(3)` permit release in `try-finally`.

## Implementation Steps
1. [x] Add `fetchStationHtml` in `EvcsApiClient.kt`.
2. [x] Create `com.evcs.favorites.data.cache.ForecastCache.kt` with TTL (3m) and failure cooldown (1m).
3. [x] Add `fetchStationForecast` and batch enrichment helper in `EvcsRepository.kt`.
4. [x] Create and run unit test `StationForecastRepositoryTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/api/EvcsApiClient.kt` - [MODIFY] Add `fetchStationHtml` method
- `app/src/main/java/com/evcs/favorites/data/cache/ForecastCache.kt` - [NEW] Thread-safe in-memory cache with TTL and cooldown
- `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt` - [MODIFY] Add forecast fetch, retry & batch enrichment
- `app/src/test/java/com/evcs/favorites/data/repository/StationForecastRepositoryTest.kt` - [NEW] Single comprehensive repository unit test

## Test Criteria (Single File-Based Test)
Run single test:
`./gradlew testDebugUnitTest --tests com.evcs.favorites.data.repository.StationForecastRepositoryTest`
- [x] Cache hit: second request within 180s does not trigger network call.
- [x] Cache invalidation: forceRefresh clears cache and triggers fresh network call.
- [x] Retry mechanism: simulates transient network failure on attempt 1, verifies successful retry on attempt 2.
- [x] Silent failure & cooldown: verifies unrecoverable network failure returns null without exception and respects 60s cooldown.
- [x] Concurrency limit: verifies batch fetching never exceeds 3 concurrent calls simultaneously.

---
Next Phase: [phase-03-viewmodel-pipeline.md](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260904-0830-live-station-forecast-cards/phase-03-viewmodel-pipeline.md)
