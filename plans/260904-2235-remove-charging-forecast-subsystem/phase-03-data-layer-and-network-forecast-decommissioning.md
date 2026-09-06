# Phase 03: Data Layer and Network Forecast Decommissioning

Status: ✅ Completed  
Dependencies: Phase 02

## Objective
Decommission the forecast network endpoints, chargeToken handshakes, and in-memory forecast caching from `EvcsRepository.kt`, `EvcsApiClient.kt`, `ForecastCache.kt`, `DebugLoggingInterceptor.kt`, `AppDebugLogger.kt`, and `DebugLogViewerCard.kt`, retire obsolete forecast data tests, and adapt shared tests.

## Requirements

### Functional
- [x] Remove `enrichStationsWithForecast`, `fetchStationForecast`, `clearForecastCache`, and `invalidateForecastCache` from `EvcsRepository.kt`.
- [x] Remove `forecastCache` and `forecastSemaphore` constructor parameters/fields and singleFlight `forecast_${station.id}` key from `EvcsRepository.kt`.
- [x] In `EvcsApiClient.kt`, remove the 2-step charging forecast handshake (`fetchChargeTokenStep1`, `executeStep2`, and `fetchChargingForecast`).
- [x] Delete or decommission `ForecastCache.kt` to eliminate station forecast memory caches.
- [x] In `DebugLoggingInterceptor.kt`, remove `/charging` and `X-Partial: user` route mappings to `DebugLogTag.FORECAST`.
- [x] In `AppDebugLogger.kt` and `DebugLogViewerCard.kt`, clean up prominent forecast banner rendering and `parsedForecastSummary`.
- [x] Retire legacy forecast API & repository tests:
  - `ChargingForecastApiClientTest.kt`
  - `ChargeTokenCachingAndRateLimitAbortTest.kt`
  - `ChargingForecastRepositoryPipelineTest.kt`
  - `StationForecastRepositoryTest.kt`
  - `ForecastRateLimitAndCompoundParserTest.kt`
  - `CoroutineCancellationCacheSafetyTest.kt`
- [x] Adapt shared test files to remove forecast cases while preserving core tests:
  - `SingleFlightRequestDeduplicationTest.kt` (remove `testEvcsRepository_concurrentFetchStationForecast_deduplicatesToSingleNetworkExecution`)
  - `AppDebugLoggerPipelineTest.kt` (remove tests asserting forecast handshake, parser, and error logging)

### Non-Functional
- [x] Zero network requests directed to `${baseUrl}/charging` or `X-Partial: user`.
- [x] Reduced memory footprint by removing forecast ticker buffers, cache maps, and concurrency semaphores.
- [x] Zero compilation errors across test source set during Gradle build.

## Implementation Steps
1. In `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt`:
   - Remove `enrichStationsWithForecast` and `fetchStationForecast`.
   - Remove `clearForecastCache` and `invalidateForecastCache`.
   - Remove `forecastCache` and `forecastSemaphore` constructor arguments and class properties.
   - Remove `singleFlight` key execution for `forecast_${station.id}`.
2. In `app/src/main/java/com/evcs/favorites/data/api/EvcsApiClient.kt`:
   - Remove `fetchChargingForecast`, `fetchChargeTokenStep1`, and `executeStep2`.
3. In `app/src/main/java/com/evcs/favorites/data/cache/ForecastCache.kt`:
   - Delete `ForecastCache.kt`.
4. In `app/src/main/java/com/evcs/favorites/data/logging/DebugLoggingInterceptor.kt`:
   - Remove `/charging` routing to `DebugLogTag.FORECAST`.
5. In `app/src/main/java/com/evcs/favorites/data/logging/AppDebugLogger.kt` & `DebugLogViewerCard.kt`:
   - Remove forecast banner display from `DebugLogViewerCard.kt`.
6. Retire & Adapt Legacy Tests:
   - Delete `app/src/test/java/com/evcs/favorites/data/api/ChargingForecastApiClientTest.kt`.
   - Delete `app/src/test/java/com/evcs/favorites/data/api/ChargeTokenCachingAndRateLimitAbortTest.kt`.
   - Delete `app/src/test/java/com/evcs/favorites/data/repository/ChargingForecastRepositoryPipelineTest.kt`.
   - Delete `app/src/test/java/com/evcs/favorites/data/repository/StationForecastRepositoryTest.kt`.
   - Delete `app/src/test/java/com/evcs/favorites/data/repository/ForecastRateLimitAndCompoundParserTest.kt`.
   - Delete `app/src/test/java/com/evcs/favorites/data/repository/CoroutineCancellationCacheSafetyTest.kt`.
   - In `app/src/test/java/com/evcs/favorites/data/repository/SingleFlightRequestDeduplicationTest.kt`, remove `testEvcsRepository_concurrentFetchStationForecast_deduplicatesToSingleNetworkExecution()`.
   - In `app/src/test/java/com/evcs/favorites/data/logging/AppDebugLoggerPipelineTest.kt`, remove forecast diagnostic logging tests.

## Files to Modify
- `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt`
- `app/src/main/java/com/evcs/favorites/data/api/EvcsApiClient.kt`
- `app/src/main/java/com/evcs/favorites/data/logging/DebugLoggingInterceptor.kt`
- `app/src/main/java/com/evcs/favorites/data/logging/AppDebugLogger.kt`
- `app/src/main/java/com/evcs/favorites/ui/components/DebugLogViewerCard.kt`
- `app/src/test/java/com/evcs/favorites/data/repository/SingleFlightRequestDeduplicationTest.kt`
- `app/src/test/java/com/evcs/favorites/data/logging/AppDebugLoggerPipelineTest.kt`

## Files to Delete
- `app/src/main/java/com/evcs/favorites/data/cache/ForecastCache.kt`
- `app/src/test/java/com/evcs/favorites/data/api/ChargingForecastApiClientTest.kt`
- `app/src/test/java/com/evcs/favorites/data/api/ChargeTokenCachingAndRateLimitAbortTest.kt`
- `app/src/test/java/com/evcs/favorites/data/repository/ChargingForecastRepositoryPipelineTest.kt`
- `app/src/test/java/com/evcs/favorites/data/repository/StationForecastRepositoryTest.kt`
- `app/src/test/java/com/evcs/favorites/data/repository/ForecastRateLimitAndCompoundParserTest.kt`
- `app/src/test/java/com/evcs/favorites/data/repository/CoroutineCancellationCacheSafetyTest.kt`

## Files to Create (Test)
- `app/src/test/java/com/evcs/favorites/data/repository/ForecastDecommissionDataPipelineTest.kt`

## Test Criteria (Single Comprehensive Test)
- Exactly one test file: `app/src/test/java/com/evcs/favorites/data/repository/ForecastDecommissionDataPipelineTest.kt`
- Verifies:
  1. `EvcsRepository` loads stations without issuing `/charging` requests or acquiring charge tokens.
  2. Station data pipeline correctly maintains connectors, coordinates, and live status without forecast dependencies.
  3. No forecast caching or debug forecast logging events are emitted during normal repository operations.

## Verification Command
```bash
./gradlew testDebugUnitTest --tests com.evcs.favorites.data.repository.ForecastDecommissionDataPipelineTest
```

---
Next Phase: [Phase 04: Domain Cleanup and System Regression Verification](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260904-2235-remove-charging-forecast-subsystem/phase-04-domain-cleanup-and-system-regression-verification.md)
