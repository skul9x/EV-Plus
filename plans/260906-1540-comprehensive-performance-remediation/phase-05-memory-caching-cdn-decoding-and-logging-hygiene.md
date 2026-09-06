# Phase 05: Memory Caching, CDN Decoding & Logging Hygiene (PERF-007, PERF-010, PERF-011)
Status: 🟩 Completed
Dependencies: Phase 04

## Objective
Optimize memory consumption and CPU churn by adding an in-memory thread-safe LRU cache (500 entries) in `VinFastCdnUrlDecoder`, gating `DebugLoggingInterceptor` and `AppDebugLogger` buffer population behind `BuildConfig.DEBUG`, and tuning Coil's memory cache down from 25% JVM heap to 15% with optimized bitmap formats.

## Requirements
### Functional
- [x] `VinFastCdnUrlDecoder` maintains an internal bounded LRU cache (500 capacity) for decoded token -> CDN URL mappings. Repeated calls with identical raw URLs/tokens return cached results in $O(1)$ without repeating the 3-step Base64 and URL decoding sequence.
- [x] `DebugLoggingInterceptor` defaults to enabled only in debug builds (`BuildConfig.DEBUG`), skipping request/response body peeking (`peekBody`) and snippet extraction in release builds.
- [x] `AppDebugLogger` does not retain entries or launch coroutine emit jobs when logging is disabled or in release mode unless explicitly enabled for testing.
- [x] `EvPlusApplication.newImageLoader` configures Coil's `MemoryCache` to 15% of JVM heap (`maxSizePercent(0.15)`) instead of 25%, preventing low-memory kills on mid-tier devices.

### Non-Functional
- [x] RAM Optimization: Free up 10% of total JVM heap and eliminate up to 5MB of persistent log entry retention in production builds.
- [x] CPU Optimization: Reduce string and byte array allocations in image lists by caching decoded VinFast CDN links.

## Implementation Steps
1. [x] Update `VinFastCdnUrlDecoder.kt`:
   - Add a private `BoundedLruMap<String, String>(500)` or thread-safe `LinkedHashMap` cache.
   - Check cache before performing Base64 decoding; store successful decode results into cache.
   - Provide cache inspection / clearing helper for unit testing.
2. [x] Update `DebugLoggingInterceptor.kt` and `AppDebugLogger.kt`:
   - Check `BuildConfig.DEBUG` (or inject an `enabled: Boolean = BuildConfig.DEBUG` flag) to bypass interceptor logic in production.
   - Guard `AppDebugLogger.log(entry)` so release builds bypass memory buffering unless explicitly enabled.
3. [x] Update `EvPlusApplication.kt`:
   - Change `.maxSizePercent(0.25)` to `.maxSizePercent(0.15)` in Coil `MemoryCache.Builder`.
4. [x] Create verification test `MemoryCacheCdnAndLoggingHygieneTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/util/VinFastCdnUrlDecoder.kt` - In-memory LRU cache for decoded CDN links.
- `app/src/main/java/com/evcs/favorites/data/logging/DebugLoggingInterceptor.kt` - Gate logging on `BuildConfig.DEBUG`.
- `app/src/main/java/com/evcs/favorites/data/logging/AppDebugLogger.kt` - Gate buffer retention on debug flag.
- `app/src/main/java/com/evcs/favorites/EvPlusApplication.kt` - Tune Coil memory cache to 15% heap.
- `app/src/test/java/com/evcs/favorites/performance/MemoryCacheCdnAndLoggingHygieneTest.kt` - Dedicated verification test.

## Test Criteria (Single Comprehensive Test)
- `MemoryCacheCdnAndLoggingHygieneTest.kt` must verify:
  1. CDN cache hit: First call to `VinFastCdnUrlDecoder.decode` decodes the token; second call returns the exact cached string with zero additional decoding passes.
  2. Cache eviction: Exceeding the 500-item capacity evicts the least-recently-used item without memory leaks.
  3. Release logging bypass: When `enabled = false` (simulating release build), `DebugLoggingInterceptor` immediately passes through requests without allocating `responseSnippet` or modifying `AppDebugLogger`.
  4. Coil memory config: `EvPlusApplication` sets Coil memory cache percentage to 0.15.

---
Next Phase: [phase-06-baseline-profile-and-macrobenchmark-architecture.md](file:///d:/skul9x/EV-Plus-main/plans/260906-1540-comprehensive-performance-remediation/phase-06-baseline-profile-and-macrobenchmark-architecture.md)
