# Phase 02: Top 10 Multi-Tier Routing Pipeline & Nearby ViewModel
Status: ✅ Completed
Dependencies: Phase 01

## Objective
Implement the `NearbyViewModel` and `NearbyUiState` coordinating manual GPS acquisition via `LocationService.getFreshLocation()`, raw station retrieval via `EvcsRepository`, instant client-side wattage and availability filtering, Top 10 Haversine sorting, targeted multi-tier driving metrics dispatch ($\le 10$ destinations to `MultiTierRoutingCoordinator`), and authentication-guarded cloud favorites toggling.

## Requirements

### Functional
- **UI State Modeling (`NearbyUiState.kt`)**:
  - `hasSearched: Boolean = false`: False before the user presses "Nhấn để tìm trạm quanh đây".
  - `isLocating: Boolean = false`: True during `LocationService.getFreshLocation()` execution.
  - `isSearching: Boolean = false`: True while fetching raw stations from `EvcsRepository.searchNearbyVinFast`.
  - `isRoutingLoading: Boolean = false`: True while `MultiTierRoutingCoordinator` computes Top 10 routes.
  - `userLatitude: Double? = null`, `userLongitude: Double? = null`.
  - `selectedWattages: Set<WattageOption> = emptySet()`: User-selected power filter chips.
  - `rawStations: List<Station> = emptyList()`: Complete raw list fetched from server.
  - `top10DisplayStations: List<Station> = emptyList()`: Filtered, sorted, and routed Top 10 stations.
  - `routingMetrics: Map<String, DrivingMetrics> = emptyMap()`: Station ID -> `DrivingMetrics`.
  - `favoriteStationIds: Set<String> = emptySet()`: Location IDs marked as favorite (reactive from repository).
  - `errorMessage: String? = null`: User-visible error notification.
- **One-Shot Event Channel (`NearbyUiEvent.kt`)**:
  - `ShowLoginRequired(stationName: String)`: Emitted when an unauthenticated user attempts to tap the heart icon.
  - `ShowToast(message: String)`: User feedback on favorite toggle.
  - `RequestLocationPermission`: Emitted if location permission is not yet granted.
- **ViewModel Orchestration (`NearbyViewModel.kt`)**:
  - `scanNearbyStations()`:
    - Verifies `locationService.hasLocationPermission()`. If false, emits `RequestLocationPermission` and returns.
    - Sets `isLocating = true`. Calls `locationService.getFreshLocation()`.
    - Once coordinates `(lat, lon)` are acquired, sets `isLocating = false, isSearching = true`.
    - Calls `repository.searchNearbyVinFast(lat, lon)`.
    - Stores raw stations, sets `hasSearched = true, isSearching = false`.
    - Dispatches filtering pipeline:
      1. `filtered = NearbyStationFilter.filterStations(rawStations, selectedWattages)`
      2. `top10 = NearbyStationFilter.extractTopNearest(lat, lon, filtered, limit = 10)`
      3. Sets `top10DisplayStations = top10, isRoutingLoading = true`.
      4. Maps top10 to `List<RoutingDestination>`. Size is strictly $\le 10$.
      5. Calls `routingCoordinator.calculateRoutes(lat, lon, destinations, prefsManager.settings.value)`.
      6. Merges `drivingMetrics` into `top10DisplayStations` and updates `routingMetrics` map. Sets `isRoutingLoading = false`.
  - `toggleWattageFilter(option: WattageOption)`:
    - Adds or removes `option` from `selectedWattages`.
    - Re-executes the filtering pipeline for the new Top 10:
      - Filters `rawStations` $\rightarrow$ extracts Top 10 $\rightarrow$ dispatches fresh route matrix request for strictly these 10 stations.
  - `toggleFavorite(station: Station)`:
    - Checks `sessionManager.hasAuthCookie()`.
    - If `false`: Emits `NearbyUiEvent.ShowLoginRequired(station.name)`.
    - If `true`: Checks whether `station.id` is currently in `favoriteStationIds`. If present, calls `repository.removeFavoriteStation(station.id)`; otherwise calls `repository.addFavoriteStation(station)`.
  - `refresh()`: Re-scans using existing GPS coordinates if valid; otherwise re-requests fresh location.

### Non-Functional
- Strictly enforce `destinations.size <= 10` for every `MultiTierRoutingCoordinator.calculateRoutes` call.
- Background location tracking is disabled; no GPS polling occurs when idle or during driving.

## Implementation Steps
1. Create `NearbyUiState.kt` and `NearbyUiEvent.kt` in `com.evcs.favorites.ui.state`.
2. Add `searchNearbyVinFast(lat: Double, lon: Double): Result<List<Station>>` in `EvcsRepository.kt`.
3. Implement `NearbyViewModel.kt` in `com.evcs.favorites.ui.viewmodel` with `StateFlow<NearbyUiState>`.
4. Observe `repository.favoriteIdsState` to automatically keep `favoriteStationIds` in sync.
5. Create comprehensive unit test `NearbyRoutingViewModelTest.kt` verifying:
   - Initial state and scan state progression.
   - Matrix routing receives strictly $\le 10$ destinations.
   - Filter toggle recomputes Top 10 and initiates fresh routing.
   - Unauthenticated favorite click emits `ShowLoginRequired`.
   - Authenticated favorite click invokes repository toggle and updates favorites.

## Files to Create/Modify
- [NEW] `app/src/main/java/com/evcs/favorites/ui/state/NearbyUiState.kt` - UI state and events.
- [NEW] `app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt` - ViewModel coordinating scan, filter, and Top 10 routing.
- [MODIFY] `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt` - Add `searchNearbyVinFast`.
- [NEW] `app/src/test/java/com/evcs/favorites/NearbyRoutingViewModelTest.kt` - Single comprehensive test for Phase 02.

## Test Criteria
- `NearbyRoutingViewModelTest.kt`:
  - Verify scanning state transitions (`isLocating` -> `isSearching` -> `isRoutingLoading` -> idle).
  - Verify only Top 10 destinations are sent to `MultiTierRoutingCoordinator`.
  - Verify wattage filter toggle recomputes Top 10 and initiates fresh routing computation.
  - Verify unauthenticated user clicking favorite emits `ShowLoginRequired` event.
  - Verify authenticated user clicking favorite calls `repository.addFavoriteStation` and updates state.

---
Next Phase: [Phase 03: Wattage Filter Chips UI & Station Card Favorite Action](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260903-nearby-charging-stations-and-multi-tier-routing/phase-03-wattage-filter-chips-and-card-favorite-action.md)
