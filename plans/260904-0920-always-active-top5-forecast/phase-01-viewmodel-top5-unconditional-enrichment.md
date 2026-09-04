# Phase 01: ViewModel Top 5 Unconditional Forecast Pipeline
Status: ⬜ Pending
Dependencies: None

## Objective
Update `NearbyViewModel.kt` and `FavoritesViewModel.kt` so that the background forecast enrichment pipeline unconditionally queries the Top 5 nearest stations, removing the restriction `totalAvailablePlugs == 0`. If a station in the top 5 has live forecast data returned by the repository, attach it; if no forecast is found, leave `forecast = null` so the UI skips rendering the capsule.

## Requirements
### Functional
- [ ] Update `NearbyViewModel.kt`:
  - In `enrichTopFullStationsWithForecast` (rename or update to `enrichTopStationsWithForecast`):
    - Select `top10DisplayStations.take(5)` directly, without filtering by `totalAvailablePlugs == 0`.
    - Retain concurrency throttle via `repository.enrichStationsWithForecast`.
    - As each station's forecast arrives, progressively update `top10DisplayStations` and `rawStations` in UI state.
    - If forecast is null from the repository, leave `forecast = null` (no synthetic or placeholder data).
- [ ] Update `FavoritesViewModel.kt`:
  - In `enrichTopFullStationsWithForecast` (rename or update to `enrichTopStationsWithForecast`):
    - Select `favoriteStations.take(5)` directly, without filtering by `totalAvailablePlugs == 0`.
    - Progressively update `favoriteStations` and `allFavorites` in UI state with the enriched forecast (or null).
- [ ] Ensure pull-to-refresh (`forceRefresh = true`) triggers fresh enrichment for the top 5 stations in both screens.

### Non-Functional
- [ ] Performance: Maximum 5 concurrent/throttled network requests via existing repository `Semaphore(3)`.
- [ ] Clean Degrade: If network fails or station has no active sessions, `station.forecast` remains null with zero UI flickering or crashes.

## Implementation Steps
1. [ ] Update `NearbyViewModel.kt` target station selection to `top10DisplayStations.take(5)`.
2. [ ] Update `FavoritesViewModel.kt` target station selection to `favoriteStations.take(5)`.
3. [ ] Create single test file `Top5UnconditionalForecastViewModelTest.kt`.
4. [ ] Run unit test to verify that top 5 stations in both ViewModels are queried regardless of port availability.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt` - [MODIFY] Unconditionally target top 5 nearest stations
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/FavoritesViewModel.kt` - [MODIFY] Unconditionally target top 5 favorite stations
- `app/src/test/java/com/evcs/favorites/ui/viewmodel/Top5UnconditionalForecastViewModelTest.kt` - [NEW] Single comprehensive test

## Test Criteria (Single File-Based Test)
Run single test:
`./gradlew testDebugUnitTest --tests com.evcs.favorites.ui.viewmodel.Top5UnconditionalForecastViewModelTest`
- [ ] Verifies `NearbyViewModel` queries Top 5 stations when stations have available ports (`totalAvailablePlugs > 0`).
- [ ] Verifies `NearbyViewModel` attaches forecast to stations when repository returns valid forecast data.
- [ ] Verifies `NearbyViewModel` leaves `forecast = null` when repository returns null (skips without dummy data).
- [ ] Verifies `FavoritesViewModel` queries Top 5 favorite stations unconditionally.
- [ ] Verifies station sort order, driving metrics, and favorite status remain preserved after forecast enrichment.

---
Next Phase: [Phase 02: StationCard UI & Badge Presentation for Live Top 5 Forecasts](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260904-0920-always-active-top5-forecast/phase-02-ui-stationcard-forecast-presentation.md)
