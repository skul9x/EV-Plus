# Phase 02: ViewModel and Background Job Decommissioning

Status: ✅ Completed  
Dependencies: Phase 01

## Objective
Completely eliminate all background charging forecast coroutine jobs, triggers, and state mutations from `FavoritesViewModel.kt` and `NearbyViewModel.kt`, retire obsolete ViewModel forecast unit tests, and adapt `FavoritesTwoWaySyncEnrichmentPreservationTest.kt`.

## Requirements

### Functional
- [x] Remove `forecastJob` tracking job from `NearbyViewModel.kt` and `FavoritesViewModel.kt`.
- [x] Remove `enrichTopStationsWithForecast` and `enrichTopFullStationsWithForecast` methods from both ViewModels.
- [x] Remove `forceRefreshForecast` parameters and flags across station loading and filter pipelines (`triggerFilterPipeline`, `loadNearbyStations`, manual refresh).
- [x] Ensure that filter updates, distance calculations, and station ranking operate purely on station coordinates and live connectors without launching any background forecast network tasks.
- [x] Remove forecast job cancellations (`forecastJob?.cancel()`) from ViewModel `onCleared()`, filter toggles, search, and refresh callbacks.
- [x] Retire obsolete ViewModel forecast unit tests that access `forecastJob`:
  - `StationForecastViewModelPipelineTest.kt`
  - `Top5UnconditionalForecastViewModelTest.kt`
- [x] Adapt `FavoritesTwoWaySyncEnrichmentPreservationTest.kt` to remove forecast-retention assertions while keeping core two-way sync tests intact.

### Non-Functional
- [x] Significant battery and data savings: Zero background HTTP requests scheduled during station list browsing.
- [x] Zero unnecessary recompositions caused by asynchronous arrival of forecast payloads.
- [x] Zero compilation errors across test source set during Gradle build.

## Implementation Steps
1. In `app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt`:
   - Remove `var forecastJob: Job? = null`.
   - Remove `enrichTopStationsWithForecast` and `enrichTopFullStationsWithForecast`.
   - Remove all calls to `enrichTopStationsWithForecast` in `triggerFilterPipeline` and `loadNearbyStations`.
   - Remove `forecastJob?.cancel()` in `onCleared()` and filter triggers.
   - Clean up state copying: avoid replacing station items with `st.copy(forecast = ...)`.
2. In `app/src/main/java/com/evcs/favorites/ui/viewmodel/FavoritesViewModel.kt`:
   - Remove `var forecastJob: Job? = null`.
   - Remove `enrichTopStationsWithForecast` and `enrichTopFullStationsWithForecast`.
   - Remove calls to `enrichTopStationsWithForecast` in `refreshStations`, sync pipelines, and filter updates.
   - Remove `forecastJob?.cancel()` in `onCleared()`.
   - Clean up state updates to omit `forecast` overrides.
3. Retire & Adapt Legacy Tests:
   - Delete `app/src/test/java/com/evcs/favorites/ui/viewmodel/StationForecastViewModelPipelineTest.kt`.
   - Delete `app/src/test/java/com/evcs/favorites/ui/viewmodel/Top5UnconditionalForecastViewModelTest.kt`.
   - In `app/src/test/java/com/evcs/favorites/ui/viewmodel/FavoritesTwoWaySyncEnrichmentPreservationTest.kt`, remove `testFavoritesTwoWaySync_existingStationsRetainForecastDataDuringRepoEmissions()`.

## Files to Modify
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt`
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/FavoritesViewModel.kt`
- `app/src/test/java/com/evcs/favorites/ui/viewmodel/FavoritesTwoWaySyncEnrichmentPreservationTest.kt`

## Files to Delete (Legacy Tests)
- `app/src/test/java/com/evcs/favorites/ui/viewmodel/StationForecastViewModelPipelineTest.kt`
- `app/src/test/java/com/evcs/favorites/ui/viewmodel/Top5UnconditionalForecastViewModelTest.kt`

## Files to Create (Test)
- `app/src/test/java/com/evcs/favorites/ui/viewmodel/ForecastDecommissionViewModelPipelineTest.kt`

## Test Criteria (Single Comprehensive Test)
- Exactly one test file: `app/src/test/java/com/evcs/favorites/ui/viewmodel/ForecastDecommissionViewModelPipelineTest.kt`
- Verifies:
  1. `FavoritesViewModel` loads favorite stations and updates live status without spawning any forecast background tasks.
  2. `NearbyViewModel` executes GPS/road sorting and distance filtering without spawning any forecast background tasks.
  3. Station state lists in both ViewModels remain stable and responsive during refresh and search queries with zero forecast mutations.

## Verification Command
```bash
./gradlew testDebugUnitTest --tests com.evcs.favorites.ui.viewmodel.ForecastDecommissionViewModelPipelineTest
```

---
Next Phase: [Phase 03: Data Layer and Network Forecast Decommissioning](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260904-2235-remove-charging-forecast-subsystem/phase-03-data-layer-and-network-forecast-decommissioning.md)
