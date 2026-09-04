# Phase 03: ViewModel Pipeline (Nearby & Favorites)
Status: ✅ Completed
Dependencies: Phase 02

## Objective
Orchestrate targeted background forecast enrichment in both `NearbyViewModel` and `FavoritesViewModel` for strictly the Top 5 nearest full stations (`totalPlugs > 0 && totalAvailablePlugs == 0`), ensuring progressive state updates that preserve existing driving metrics without interrupting user browsing, filtering, or location scanning.

## Requirements
### Functional
- [x] **Target Selection Strategy**:
  - In `NearbyViewModel`: Target stations from the visible `top10DisplayStations` list:
    - Filter: `station.totalPlugs > 0 && station.totalAvailablePlugs == 0`.
    - Sorted by driving distance (or Haversine baseline).
    - Limit: strictly take the first 5 full stations (`take(5)`).
  - In `FavoritesViewModel`: Target stations from the loaded favorites list:
    - Filter: `station.totalPlugs > 0 && station.totalAvailablePlugs == 0`.
    - Sorted by shortest ETA / distance to user.
    - Limit: strictly take the first 5 full stations (`take(5)`).
  - Stations with `totalAvailablePlugs > 0` or unverified `totalPlugs == 0` are excluded from forecast requests.
- [x] **Asynchronous Execution & Job Lifecycle**:
  - Store `forecastJob: Job?` in both ViewModels.
  - In `NearbyViewModel`: Trigger enrichment after nearby search & initial routing display completes.
    - Cancel `forecastJob` when `scanNearbyStations()`, `toggleWattageFilter()`, `clearWattageFilters()`, `refresh()`, or `onCleared()` is called.
  - In `FavoritesViewModel`: Trigger enrichment after favorites & telemetry are resolved.
    - Cancel `forecastJob` when `updateUserLocation()` (displacement > 200m), `refreshFavorites()`, or `onCleared()` is called.
- [x] **Progressive State Flow Update**:
  - As each station's forecast arrives, update the station instance progressively via `station.copy(forecast = forecast)`.
  - Crucial: Preserve all existing `drivingMetrics`, connectors, and sort orders during the update.
  - On pull-to-refresh / manual refresh: trigger with `forceRefresh = true` to invalidate cache.

### Non-Functional
- [x] Main-safe coroutines: network & parsing performed on `ioDispatcher`, state updates safely committed on `dispatcher`.
- [x] No UI jank, frozen list scrolling, or flickering badges.

## Implementation Steps
1. [x] Add `forecastJob` and `enrichTopFullStationsWithForecast` in `NearbyViewModel.kt`.
2. [x] Add `forecastJob` and `enrichTopFullStationsWithForecast` in `FavoritesViewModel.kt`.
3. [x] Connect manual refresh actions and filter toggles to cancel and re-trigger forecast enrichment.
4. [x] Create and run unit test `StationForecastViewModelPipelineTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt` - [MODIFY] Add forecast enrichment pipeline & lifecycle management
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/FavoritesViewModel.kt` - [MODIFY] Add forecast enrichment pipeline & lifecycle management
- `app/src/test/java/com/evcs/favorites/ui/viewmodel/StationForecastViewModelPipelineTest.kt` - [NEW] Single comprehensive pipeline unit test

## Test Criteria (Single File-Based Test)
Run single test:
`./gradlew testDebugUnitTest --tests com.evcs.favorites.ui.viewmodel.StationForecastViewModelPipelineTest`
- [x] Verifies Top 5 nearest selection among full stations from visible list.
- [x] Verifies stations with available plugs > 0 are excluded from forecast fetching.
- [x] Verifies state progressively updates station with its parsed forecast while preserving driving metrics.
- [x] Verifies cancel-on-rescan and cancel-on-filter-change behavior.
- [x] Verifies forceRefresh clears cache and updates forecasts.

---
Next Phase: [phase-04-ui-stationcard-forecast-capsule.md](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260904-0830-live-station-forecast-cards/phase-04-ui-stationcard-forecast-capsule.md)
