# Phase 04: Domain Cleanup and System Regression Verification

Status: ✅ Completed  
Dependencies: Phase 03

## Objective
Clean up remaining domain models, delete `StationForecastParser.kt`, remove obsolete domain data structures (`ChargingForecastRequest`, `ChargingForecastResponse`, `Station.forecast`), adapt shared tests (`StaticRegexHotPathParsingPerformanceTest.kt`, `R8ProGuardConfigurationVerificationTest.kt`, `ComposeStabilityAndHotPathRecompositionTest.kt`), and execute full project build verification.

## Requirements

### Functional
- [x] Remove `StationForecast.kt` domain file containing `StationForecast`, `ForecastPowerGroup`, and `ForecastSession`.
- [x] Remove `StationForecastParser.kt` and its regex constants from data layer.
- [x] In `StationModels.kt`, remove `forecast` property from `Station` class, and remove obsolete DTOs (`ChargingForecastRequest`, `ChargingForecastResponse`, and unused user partial fields).
- [x] In `DebugLogModels.kt`, remove `parsedForecastSummary` from `DebugLogEntry` and prune `DebugLogTag.FORECAST`.
- [x] Retire obsolete parser test:
  - Delete `app/src/test/java/com/evcs/favorites/data/parser/StationForecastParserTest.kt`.
- [x] Adapt shared performance, R8, and Compose stability test files:
  - `StaticRegexHotPathParsingPerformanceTest.kt` (remove forecast parser HTML stripping and concurrency tests).
  - `R8ProGuardConfigurationVerificationTest.kt` (remove `StationForecast`, `ForecastPowerGroup`, `ForecastSession` serialization tests and serializer assertions).
  - `ComposeStabilityAndHotPathRecompositionTest.kt` (remove `StationForecast` immutability and equality tests).
- [x] Review `proguard-rules.pro` to ensure no stale rules remain.
- [x] Verify full project compilation without warnings or errors: `./gradlew compileDebugKotlin compileDebugUnitTestKotlin`.

### Non-Functional
- [x] Shrink APK size by eliminating dead forecast parsing code and regex patterns.
- [x] Clean architecture code integrity with zero broken imports or orphan references.
- [x] 100% green test suite across entire project.

## Implementation Steps
1. In `app/src/main/java/com/evcs/favorites/data/model/StationModels.kt`:
   - Remove `val forecast: StationForecast? = null` from `Station`.
   - Remove `ChargingForecastRequest` and `ChargingForecastResponse`.
2. Delete domain and parser files:
   - Delete `app/src/main/java/com/evcs/favorites/domain/model/StationForecast.kt`.
   - Delete `app/src/main/java/com/evcs/favorites/data/parser/StationForecastParser.kt`.
3. In `app/src/main/java/com/evcs/favorites/data/logging/DebugLogModels.kt`:
   - Remove `parsedForecastSummary` from `DebugLogEntry`.
   - Remove `FORECAST` from `DebugLogTag`.
4. Adapt shared tests and delete parser test:
   - Delete `app/src/test/java/com/evcs/favorites/data/parser/StationForecastParserTest.kt`.
   - In `app/src/test/java/com/evcs/favorites/StaticRegexHotPathParsingPerformanceTest.kt`, remove forecast stripping test.
   - In `app/src/test/java/com/evcs/favorites/R8ProGuardConfigurationVerificationTest.kt`, remove forecast model serialization assertions.
   - In `app/src/test/java/com/evcs/favorites/ComposeStabilityAndHotPathRecompositionTest.kt`, remove forecast model tests.
5. Run full project compilation check:
   - `./gradlew compileDebugKotlin compileDebugUnitTestKotlin`

## Files to Modify
- `app/src/main/java/com/evcs/favorites/data/model/StationModels.kt`
- `app/src/main/java/com/evcs/favorites/data/logging/DebugLogModels.kt`
- `app/src/test/java/com/evcs/favorites/StaticRegexHotPathParsingPerformanceTest.kt`
- `app/src/test/java/com/evcs/favorites/R8ProGuardConfigurationVerificationTest.kt`
- `app/src/test/java/com/evcs/favorites/ComposeStabilityAndHotPathRecompositionTest.kt`
- `app/proguard-rules.pro`

## Files to Delete
- `app/src/main/java/com/evcs/favorites/domain/model/StationForecast.kt`
- `app/src/main/java/com/evcs/favorites/data/parser/StationForecastParser.kt`
- `app/src/test/java/com/evcs/favorites/data/parser/StationForecastParserTest.kt`

## Files to Create (Test)
- `app/src/test/java/com/evcs/favorites/ForecastRemovalCleanArchitectureRegressionTest.kt`

## Test Criteria (Single Comprehensive Test)
- Exactly one test file: `app/src/test/java/com/evcs/favorites/ForecastRemovalCleanArchitectureRegressionTest.kt`
- Verifies:
  1. Complete station lifecycle and serialization operate cleanly without forecast dependencies.
  2. No active coroutines or background jobs exist for forecast polling.
  3. Clean architecture boundary verification: domain and data models compile and function with zero forecast coupling.

## Verification Command
```bash
./gradlew testDebugUnitTest --tests com.evcs.favorites.ForecastRemovalCleanArchitectureRegressionTest
./gradlew compileDebugKotlin compileDebugUnitTestKotlin
```
