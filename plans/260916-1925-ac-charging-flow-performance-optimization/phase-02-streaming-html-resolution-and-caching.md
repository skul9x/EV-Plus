# Phase 02: Streaming HTML Resolution, TTL Caching & Timeout Guard
Status: ✅ Completed
Dependencies: Phase 01

## Objective
Accelerate the canonical station name resolution engine in `EvcsStationNameResolver.kt` by streaming HTML response lines to terminate early upon discovering the `<title>` tag or `</head>` boundary, introducing a thread-safe in-memory TTL Cache (1-hour lifespan) to eliminate redundant network traffic during repeat scans, and tightening timeout guard to 1.8s.

## Requirements
### Functional
- [x] Refactor `EvcsStationNameResolver.resolveStationNameFromHtml(locationId: String, fallbackAddress: String = "", bypassCache: Boolean = false)`:
  - Check in-memory HTML station name TTL cache first (returns immediately on cache hit unless `bypassCache == true`).
  - Read HTTP response body line-by-line using a streaming `BufferedReader`.
  - Terminate reading immediately as soon as a valid `<title>` tag is matched or when encountering `</head>` / `<body>`, without buffering full HTML body content.
  - Extract and sanitize canonical title using existing prefix/suffix sanitizers.
  - Store resolved title into in-memory TTL cache with an expiration window of 60 minutes.
  - Fallback cleanly to `"VinFast - ${fallbackAddress}"` on network error, HTTP non-200, or timeout.
- [x] Tighten network execution timeout from 5.0s to 1.8s (`withTimeoutOrNull(1800L)`).
- [x] Maintain backward-compatibility for existing tests by supporting a cache clear/bypass mechanism.

### Non-Functional
- [x] Network Bandwidth: Eliminate full-page HTML downloads, cutting data transfer per station request by up to 90%.
- [x] Latency: Response time for repeated station name resolutions reduced from >1000ms to 0ms (instant in-memory hit).
- [x] Memory: Eliminate large string allocations for full-page HTML bodies.

## Implementation Steps
1. [x] Implement thread-safe `ConcurrentHashMap` with timestamp tracking for canonical HTML names in `EvcsStationNameResolver.kt`.
2. [x] Implement streaming line parser with early break on `</head>` / `<title>` in `resolveStationNameFromHtml`.
3. [x] Apply 1.8s timeout guard and fallback formatting.
4. [x] Create the single comprehensive test file:
   `app/src/test/java/com/evcs/favorites/focus/AcStationStreamingNameResolutionAndCacheTest.kt`
   Verifying:
   - Streaming reader extracts `<title>` without consuming the rest of the body.
   - Cache hit returns cached title instantly on subsequent calls without dispatching HTTP requests.
   - Timeout guard (1.8s) trips gracefully to fallback address format.
   - Bypassing or invalidating cache forces fresh network retrieval when requested.
5. [x] Run only this test to verify Phase 02:
   `./gradlew testDebugUnitTest --tests "com.evcs.favorites.focus.AcStationStreamingNameResolutionAndCacheTest"`

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/focus/EvcsStationNameResolver.kt` - [MODIFY] Add streaming HTML reader, 1.8s timeout guard, and in-memory TTL cache.
- `app/src/test/java/com/evcs/favorites/focus/AcStationStreamingNameResolutionAndCacheTest.kt` - [NEW] Single comprehensive unit test suite for Phase 02.

## Test Criteria
- [x] Streaming parser stops reading after title tag or head closing tag.
- [x] Second lookup for same station ID returns cached value with 0 new HTTP requests.
- [x] Unresponsive endpoints gracefully resolve to `"VinFast - $fallbackAddress"` within timeout limit.
- [x] Extracted names are cleaned and sanitized identically to previous implementation.

---
Next Phase: [phase-03-progressive-rendering-and-pipeline-optimization.md](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260916-1925-ac-charging-flow-performance-optimization/phase-03-progressive-rendering-and-pipeline-optimization.md)
