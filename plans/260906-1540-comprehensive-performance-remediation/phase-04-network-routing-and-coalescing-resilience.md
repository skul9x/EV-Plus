# Phase 04: Network Routing & Coalescing Resilience (PERF-002, PERF-005, PERF-013)
Status: ✅ Completed
Dependencies: Phase 03

## Objective
Eliminate sequential N+1 HTML coordinate scraping waterfalls in `EvcsRepository.getFavoritesInternal`, prevent 25–30s routing timeout cascades in `MultiTierRoutingCoordinator`, and stabilize `SingleFlight` request coalescing against micro GPS coordinate jitter.

## Requirements
### Functional
- [x] In `EvcsRepository.getFavoritesInternal`: When unknown station coordinates must be resolved, eliminate sequential `for` loop HTTP calls. Resolve missing coordinates concurrently using bounded parallelism (`coroutineScope` with `async`), skipping already cached coordinates.
- [x] In `MultiTierRoutingCoordinator`:
  - Enforce strict bounded timeouts: Tier 1 (Google Routes) timeout <= 3.5 seconds; Tier 2 (OSRM) timeout <= 3.5 seconds.
  - Implement an overall arbitration timeout wrapper (`withTimeoutOrNull(5000L)`) in `executeAuto` so worst-case network stalls immediately fall back to Tier 3 (Haversine baseline) within 5 seconds instead of 30 seconds.
- [x] In `EvcsRepository`: Round latitude and longitude coordinates to 4 decimal places (~11 meters) when generating cache keys for `SingleFlight` in `searchNearbyVinFast` and `getFavorites`, ensuring repeated scans within a tight GPS radius coalesce into a single HTTP request.

### Non-Functional
- [x] Network Latency: Reduce worst-case routing fallback latency from 30s to < 5s.
- [x] Bandwidth: Eliminate redundant parallel/sequential HTML scraping requests.
- [x] Robustness: Seamless fallback to Haversine routing under flaky 3G/4G connectivity.

## Implementation Steps
1. [x] Update `EvcsRepository.kt`:
   - Refactor `getFavoritesInternal` coordinate resolution: Filter unknown stations, launch concurrent `async` resolution with a concurrency limiter (semaphore of 4 or `async` batching), and save to cache.
   - Round coordinates to 4 decimal places in `SingleFlight` key: `"search_${"%.4f".format(Locale.US, lat)}_${"%.4f".format(Locale.US, lon)}"`.
   - Apply coordinate rounding similarly to `favorites_${"%.4f".format(...)}` flight key.
2. [x] Update `MultiTierRoutingCoordinator.kt`:
   - Wrap `executeAuto` with `withTimeoutOrNull(5000L)`. If null (timeout exceeded), fall back to `computeHaversine(originLat, originLng, destinations)`.
   - Pass explicit timeout constraints to `googleClient` and `osrmClient` (or configure call timeouts).
3. [x] Update `GoogleRoutesClient.kt` and `OsrmRoutingClient.kt`:
   - Ensure network calls respect coroutine cancellation and timeouts.
4. [x] Create verification test `NetworkRoutingAndCoalescingOptimizationTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt` - Concurrent coordinate resolution & SingleFlight key rounding.
- `app/src/main/java/com/evcs/favorites/data/routing/MultiTierRoutingCoordinator.kt` - Timeout bounds & 5s overall fallback wrapper.
- `app/src/main/java/com/evcs/favorites/data/routing/GoogleRoutesClient.kt` - Timeout and cancellation handling.
- `app/src/main/java/com/evcs/favorites/data/routing/OsrmRoutingClient.kt` - Timeout and cancellation handling.
- `app/src/test/java/com/evcs/favorites/performance/NetworkRoutingAndCoalescingOptimizationTest.kt` - Dedicated verification test.

## Test Criteria (Single Comprehensive Test)
- `NetworkRoutingAndCoalescingOptimizationTest.kt` must verify:
  1. Concurrent resolution: Resolving 5 unknown station coordinates completes concurrently rather than sequentially in series.
  2. GPS jitter coalescing: Two `searchNearbyVinFast` calls with micro GPS differences (e.g. `21.0285114` vs `21.0285189`) generate the identical `SingleFlight` key and coalesce into one execution.
  3. Timeout fallback: When both Google and OSRM endpoints hang or simulate 10s latency, `MultiTierRoutingCoordinator.executeAuto` aborts after 5s and returns valid Tier 3 Haversine metrics.

---
Next Phase: [phase-05-memory-caching-cdn-decoding-and-logging-hygiene.md](file:///d:/skul9x/EV-Plus-main/plans/260906-1540-comprehensive-performance-remediation/phase-05-memory-caching-cdn-decoding-and-logging-hygiene.md)
