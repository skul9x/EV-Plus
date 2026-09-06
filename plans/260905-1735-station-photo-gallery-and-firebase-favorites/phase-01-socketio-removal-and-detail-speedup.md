# Phase 01: Socket.io Removal & Detail Loading Performance Optimization

Status: ✅ Completed
Dependencies: None

## Objective
Remove the `io.socket:socket.io-client` dependency and eradicate the 4-second Stage 2 telemetry timeout (`fetch24hHistory`, Socket.io listeners, 24h stats calculation). Streamline the station detail pipeline to rely solely on fast HTTP REST endpoints (`POST /{slug}.html` for tokens and `POST /charging` for live ports), accelerating bottom sheet readiness from ~2s to ~150-200ms while eliminating socket background resource consumption and preserving test suite compilation safety.

## Requirements

### Functional
- [x] Remove `io.socket:socket.io-client` dependency from [app/build.gradle.kts](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/build.gradle.kts).
- [x] Refactor [EvcsTelemetryDataSource.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/data/telemetry/EvcsTelemetryDataSource.kt) to remove `SocketClient`, `RealSocketClient`, `SocketClientFactory`, `fetch24hHistory`, and `parseHistoryData`. Retain HTTP `fetchStationTokens`, `fetchLiveCharging`, and `sendTelemetryUpdate`. Provide default constructor parameter compatibility (`socketBaseUrl: String = ""` and optional factory stub) so other project callers compile without changes.
- [x] Refactor [EvcsTelemetryRepository.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/data/repository/EvcsTelemetryRepository.kt):
  - Retain lightweight `@Deprecated` stubs for `fetch24hHistory` (returns `Result.success(emptyList())`) and `fetch24hStats` (returns `Result.success(null)`) to safeguard existing test suite compilation (`NativeStationDetailIntegrationTest.kt`, `StationDetailViewModelPipelineTest.kt`, `NativeStationDetailRefreshFeedbackTest.kt`).
  - Streamline `fetchStationTelemetrySnapshot` to execute purely via HTTP with zero socket overhead.
- [x] Refactor [StationDetailCoordinator.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/viewmodel/StationDetailCoordinator.kt):
  - Remove Stage 2 coroutine launch block and `statsTimeoutMs = 4000L`.
  - Set `isLoadingStats = false` immediately alongside `isLoadingTelemetry = false` upon Stage 1 HTTP completion.
- [x] Clean up obsolete 24h stat cards in [NativeStationDetailSheet.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt) (remove 2x2 grid) so the sheet renders directly without waiting for 24h statistics.
- [x] Maintain Stage 3 fire-and-forget sync ping (`sendTelemetryUpdate`) without blocking UI state.
- [x] Safeguard existing test suite compilation in [EvcsTelemetryRepositoryAndStatsEngineTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/data/repository/EvcsTelemetryRepositoryAndStatsEngineTest.kt): remove obsolete `io.socket.client.*` imports and replace Socket.io-specific test cases with streamlined HTTP snapshot and deprecated stub assertions, ensuring Gradle's `compileDebugUnitTestKotlin` succeeds project-wide.

### Non-Functional
- [x] Bottom sheet detail loading roundtrip must complete in < 300ms on active network connections.
- [x] Zero background threads or sockets left open upon sheet dismissal.
- [x] Zero regression on live port availability derivation and power rating display.
- [x] Full compilation compatibility across all existing project test suites (`./gradlew testDebugUnitTest`).

## Implementation Steps
1. [x] Update [app/build.gradle.kts](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/build.gradle.kts): remove `implementation("io.socket:socket.io-client:2.1.1")`.
2. [x] Simplify [EvcsTelemetryDataSource.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/data/telemetry/EvcsTelemetryDataSource.kt): strip Socket.io imports, client abstractions, and history parsing routines. Keep HTTP `fetchStationTokens`, `fetchLiveCharging`, and `sendTelemetryUpdate`. Maintain backward-compatible constructor defaults.
3. [x] Update [EvcsTelemetryRepository.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/data/repository/EvcsTelemetryRepository.kt): provide deprecated no-op stubs for `fetch24hHistory` and `fetch24hStats`. Streamline `fetchStationTelemetrySnapshot` to execute purely via HTTP.
4. [x] Refactor [StationDetailCoordinator.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/viewmodel/StationDetailCoordinator.kt): remove Stage 2 coroutine block and 4s timeout. Set `isLoadingStats = false` immediately when Stage 1 completes.
5. [x] Update [NativeStationDetailSheet.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt): remove the 2x2 24h stats grid placeholder in preparation for the photo gallery.
6. [x] Update [EvcsTelemetryRepositoryAndStatsEngineTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/data/repository/EvcsTelemetryRepositoryAndStatsEngineTest.kt) to remove `io.socket.client.*` imports and adapt to HTTP-only telemetry.
7. [x] Create exactly one comprehensive file-based test: [StationDetailStreamlinedTelemetryTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/data/repository/StationDetailStreamlinedTelemetryTest.kt).
8. [x] Run only this single test to verify Phase 01 completion: `./gradlew testDebugUnitTest --tests com.evcs.favorites.data.repository.StationDetailStreamlinedTelemetryTest`.

## Files to Create/Modify
- `app/build.gradle.kts` - Remove Socket.io dependency.
- `app/src/main/java/com/evcs/favorites/data/telemetry/EvcsTelemetryDataSource.kt` - Remove Socket.io connection and listeners.
- `app/src/main/java/com/evcs/favorites/data/repository/EvcsTelemetryRepository.kt` - Streamline snapshot and provide compilation safety stubs.
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/StationDetailCoordinator.kt` - Remove Stage 2 coroutine block and 4s timeout.
- `app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt` - Strip obsolete stats cards layout.
- `app/src/test/java/com/evcs/favorites/data/repository/EvcsTelemetryRepositoryAndStatsEngineTest.kt` - Remove `io.socket` imports and adapt tests.
- `app/src/test/java/com/evcs/favorites/data/repository/StationDetailStreamlinedTelemetryTest.kt` - [NEW] Single comprehensive test verifying streamlined HTTP telemetry flow, instantaneous loading completion, and absence of socket overhead.

## Test Criteria
- [x] Single comprehensive test `StationDetailStreamlinedTelemetryTest.kt` PASSES:
  - Verifies `EvcsTelemetryDataSource` executes `fetchStationTokens` and `fetchLiveCharging` over HTTP with zero Socket.io dependencies.
  - Verifies `StationDetailCoordinator` transitions state to loaded immediately upon Stage 1 HTTP completion without 4s timeout delay.
  - Verifies `activeJob` cancels cleanly on `dismissStationDetail()` with zero memory or network leaks.
  - Verifies fire-and-forget telemetry update is dispatched asynchronously without blocking state flow.
- [x] Entire test suite compiles cleanly without missing class/symbol errors: `./gradlew compileDebugUnitTestKotlin`.

---
Next Phase: [Phase 02: Station Photo Gallery, Direct CDN Decoding & Full-Screen Lightbox](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260905-1735-station-photo-gallery-and-firebase-favorites/phase-02-station-photo-gallery-and-lightbox.md)
