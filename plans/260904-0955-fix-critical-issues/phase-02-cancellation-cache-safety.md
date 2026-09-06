# Phase 02: Cancellation Propagation & Cache Cooldown Safety
Status: 🟩 Completed
Dependencies: Phase 01

## Objective
Resolve ANDROID-LOGIC-002: Ensure `CancellationException` in coroutines is never caught and converted into a remote network failure. Specifically prevent `EvcsRepository.fetchStationForecast` and routing clients from recording a 60-second cooldown on `ForecastCache` when user operations (pull-to-refresh, tab switching, location update) cancel active jobs.

## Requirements
### Functional
- Update `EvcsRepository.kt`:
  - In `fetchStationForecast`: Explicitly check `if (e is CancellationException) throw e` before calling `forecastCache.recordFailure(station.id)` and returning `Result.success(null)`.
  - In `enrichStationsWithForecast`: Ensure coroutine cancellation re-throws properly out of `coroutineScope`.
- Update `MultiTierRoutingCoordinator.kt`:
  - In `calculateRoutes`: In `catch (e: Throwable)`, rethrow if `e is CancellationException`.
- Update `GoogleRoutesClient.kt` and `OsrmRoutingClient.kt`:
  - Ensure all catch blocks re-throw `CancellationException`.

### Non-Functional
- Concurrency Integrity: Adhere to Kotlin Coroutines structured concurrency rules.
- Fast Refresh: Users can pull to refresh repeatedly without being locked out by failure cooldowns.

## Implementation Steps
1. Update catch blocks in `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt` to re-throw `CancellationException`.
2. Update catch blocks in `app/src/main/java/com/evcs/favorites/data/routing/MultiTierRoutingCoordinator.kt`.
3. Update catch blocks in `app/src/main/java/com/evcs/favorites/data/routing/GoogleRoutesClient.kt` and `OsrmRoutingClient.kt`.
4. Create single test file `app/src/test/java/com/evcs/favorites/data/repository/CoroutineCancellationCacheSafetyTest.kt`.
5. Run verification command:
   `./gradlew testDebugUnitTest --tests com.evcs.favorites.data.repository.CoroutineCancellationCacheSafetyTest`

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt` - [MODIFY] Re-throw `CancellationException` before `recordFailure`
- `app/src/main/java/com/evcs/favorites/data/routing/MultiTierRoutingCoordinator.kt` - [MODIFY] Re-throw `CancellationException` in `calculateRoutes`
- `app/src/main/java/com/evcs/favorites/data/routing/GoogleRoutesClient.kt` - [MODIFY] Re-throw `CancellationException`
- `app/src/main/java/com/evcs/favorites/data/routing/OsrmRoutingClient.kt` - [MODIFY] Re-throw `CancellationException`
- `app/src/test/java/com/evcs/favorites/data/repository/CoroutineCancellationCacheSafetyTest.kt` - [NEW] Single comprehensive test for Phase 02

## Test Criteria (Single File-Based Test)
- Run single test:
  `./gradlew testDebugUnitTest --tests com.evcs.favorites.data.repository.CoroutineCancellationCacheSafetyTest`
- [x] Verifies cancelling a `fetchStationForecast` coroutine throws `CancellationException`.
- [x] Verifies that cancelled coroutines do NOT trigger `forecastCache.recordFailure(station.id)`.
- [x] Verifies that `forecastCache.isInCooldown(station.id)` is `false` after job cancellation.
- [x] Verifies that cancelling `enrichStationsWithForecast` propagates cancellation cleanly and subsequent requests are not blocked.

---
Next Phase: [Phase 03: OkHttp Response Deterministic Cleanup & Socket Leak Prevention](phase-03-okhttp-response-leak-prevention.md)
