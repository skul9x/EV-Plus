# Plan: Completely Remove Charging Forecast Subsystem (Option 1)

Created: 2026-09-04 22:35:00 (GMT+7)  
Updated: 2026-09-04 22:45:00 (GMT+7) - Enhanced with Atomic Compilation & Test Suite Safety  
Status: 🟡 Ready for Execution

## Overview
Completely remove the charging time forecast subsystem ("Dự kiến 2 xe sạc trụ 120kW sẽ xong trong 13-17 phút") across all application layers in EV-Plus: Native UI, WebView modal, ViewModels, Repository, Network API, Caching, Domain models, and Logging.

Eliminating this feature streamlines the Station Card UI, removes confusing estimation text, eliminates multiple background HTTP requests per station (`POST /charging` and `chargeToken` handshakes), conserves device battery and mobile data, and simplifies the codebase.

---

## Key Principles & Execution Rules

1. **Strict Single Comprehensive Test Rule Per Phase**:
   - Each phase contains **exactly one** comprehensive file-based unit test to verify core functionality.
   - Do NOT create or run more than one new test per phase.
   - Run only that phase's test for verification (`./gradlew testDebugUnitTest --tests com.evcs.favorites.<TestFile>`), avoiding running unrelated test files that may stall or hang.

2. **Atomic Test Compilation Safety (Per-Phase Legacy Test Maintenance)**:
   - In Android Gradle, `./gradlew testDebugUnitTest --tests ...` always triggers `:app:compileDebugUnitTestKotlin` across all test files in `app/src/test/java`.
   - Modifying production classes in a phase without updating/retiring legacy tests that reference the modified symbols breaks test compilation immediately.
   - Therefore, each phase explicitly retires or adapts legacy test files within its scope so that `:app:compileDebugUnitTestKotlin` remains 100% green at every phase milestone.

3. **Deterministic English Phase Documentation**:
   - All phase files are written in English in `.md` format.
   - Every phase defines crisp objectives, functional and non-functional requirements, detailed implementation steps, production files to modify/delete, legacy tests to retire/adapt, and verification commands.

4. **Clean Architecture Separation (Outside-In Decoupling)**:
   - Follow clean architecture: decouple the presentation layer first (Phase 01), then presentation ViewModels (Phase 02), followed by data/network layers (Phase 03), and finally domain cleanup, shared test adaptation, and regression verification (Phase 04).

5. **No Regressions**:
   - Station cards, navigation, distance sorting, connector display, manual refresh, favorite two-way sync, and WebView modal details must remain 100% operational.

---

## Phases Overview

| Phase | Name | Production Scope | Test Maintenance | Single Phase Test File | Status |
|---|---|---|---|---|---|
| 01 | Presentation & StationCard Decoupling | `StationCard.kt`, `StationDetailModal.kt` | Retire: `StationCardForecastBadgeTest.kt`, `StationCardTop5ForecastTest.kt`, `StationDetailModalForecastAlignmentTest.kt` | `StationCardForecastRemovalTest.kt` | ✅ Completed |
| 02 | ViewModel & Background Job Decommissioning | `NearbyViewModel.kt`, `FavoritesViewModel.kt` | Retire: `StationForecastViewModelPipelineTest.kt`, `Top5UnconditionalForecastViewModelTest.kt`<br>Adapt: `FavoritesTwoWaySyncEnrichmentPreservationTest.kt` | `ForecastDecommissionViewModelPipelineTest.kt` | ✅ Completed |
| 03 | Data Layer & Network Forecast Decommissioning | `EvcsRepository.kt`, `EvcsApiClient.kt`, `ForecastCache.kt`, `DebugLoggingInterceptor.kt`, `AppDebugLogger.kt`, `DebugLogViewerCard.kt` | Retire: `ChargingForecastApiClientTest.kt`, `ChargeTokenCachingAndRateLimitAbortTest.kt`, `ChargingForecastRepositoryPipelineTest.kt`, `StationForecastRepositoryTest.kt`, `ForecastRateLimitAndCompoundParserTest.kt`, `CoroutineCancellationCacheSafetyTest.kt`<br>Adapt: `SingleFlightRequestDeduplicationTest.kt`, `AppDebugLoggerPipelineTest.kt` | `ForecastDecommissionDataPipelineTest.kt` | ✅ Completed |
| 04 | Domain Cleanup & System Regression Verification | `StationForecast.kt`, `StationForecastParser.kt`, `StationModels.kt`, `DebugLogModels.kt`, `proguard-rules.pro` | Retire: `StationForecastParserTest.kt`<br>Adapt: `StaticRegexHotPathParsingPerformanceTest.kt`, `R8ProGuardConfigurationVerificationTest.kt`, `ComposeStabilityAndHotPathRecompositionTest.kt` | `ForecastRemovalCleanArchitectureRegressionTest.kt` | ✅ Completed |

---

## Verification Commands
- **Phase 01**:
  ```bash
  ./gradlew testDebugUnitTest --tests com.evcs.favorites.ui.components.StationCardForecastRemovalTest
  ```
- **Phase 02**:
  ```bash
  ./gradlew testDebugUnitTest --tests com.evcs.favorites.ui.viewmodel.ForecastDecommissionViewModelPipelineTest
  ```
- **Phase 03**:
  ```bash
  ./gradlew testDebugUnitTest --tests com.evcs.favorites.data.repository.ForecastDecommissionDataPipelineTest
  ```
- **Phase 04**:
  ```bash
  ./gradlew testDebugUnitTest --tests com.evcs.favorites.ForecastRemovalCleanArchitectureRegressionTest
  ./gradlew compileDebugKotlin compileDebugUnitTestKotlin
  ```
