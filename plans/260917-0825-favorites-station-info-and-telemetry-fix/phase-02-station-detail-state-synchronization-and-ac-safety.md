# Phase 02: Station Detail State Synchronization & AC Station Safety
Status: ✅ Completed
Dependencies: Phase 01

## Objective
Prevent HTTP 404 network crashes when inspecting AC or partner charging stations in the detail view, ensure proper AC provider/EVSE attribution, and establish two-way state synchronization between `FavoritesViewModel` and `StationDetailCoordinator` so that driving metrics and telemetry updates are never lost or stale.

## Requirements
### Functional
- [x] Fix hardcoded VinFast EVSE in `HereModelMapper.mapStationToDomain`:
  - Determine `evse` dynamically from `hereStation.cpoId` or station attributes rather than hardcoding `"VinFast"`.
  - Mark non-VinFast or pure AC stations accurately so that `StationUrlBuilder` does not generate invalid `tram-sac-vinfast-...` URLs for third-party AC stations.
- [x] Implement AC / Partner Station Safety in `StationDetailCoordinator.loadStationDetails`:
  - Detect when a station is a non-VinFast / pure AC station (e.g., `powers.all { it.typeWatts <= 22000 }` or `id.startsWith("here_")` or `evse != "VinFast"`).
  - For AC / third-party stations without EVCS web scraping endpoints, bypass token fetching (`telemetryRepository.fetchStationTokens`) and retain initial port statuses directly.
  - Gracefully handle HTTP 404 or missing web scraping endpoints without failing the entire detail sheet or leaving it in a perpetual error state.
- [x] Implement Two-Way Station Synchronization in `StationDetailCoordinator`:
  - Add `updateStation(updatedStation: Station)` method to `StationDetailCoordinator` to update `_stationDetailState.value.station` whenever updated routing metrics (`drivingMetrics`) or telemetry arrive.
  - In `FavoritesViewModel.calculateDrivingRoutes`, call `stationDetailCoordinator.updateStation(enrichedStation)` when the enriched station matches the currently open detail sheet.
  - In `FavoritesViewModel.updateStationWithTelemetry`, ensure the updated station is reflected in `stationDetailCoordinator` if currently displayed.
- [x] Propagate AC Filter State dynamically in `FavoritesScreen` and `FavoritesLandscapeScreen`:
  - Pass appropriate AC highlighting/filter state based on station characteristics or selected filters to `StationCard` and `NativeStationDetailSheet`.

## Files to Create/Modify
- [HereEvModels.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/data/network/here/model/HereEvModels.kt) - Remove hardcoded `"VinFast"` EVSE in `mapStationToDomain`.
- [StationDetailCoordinator.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/viewmodel/StationDetailCoordinator.kt) - Add `updateStation` method and bypass EVCS web scraping for non-VinFast/AC stations.
- [FavoritesViewModel.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/viewmodel/FavoritesViewModel.kt) - Synchronize route metrics and telemetry updates with `stationDetailCoordinator`.
- [FavoritesDetailStateAndAcSafetyTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/ui/FavoritesDetailStateAndAcSafetyTest.kt) - Single comprehensive test for Phase 02.

## Test Criteria (Single Verification Test)
- `FavoritesDetailStateAndAcSafetyTest.kt` verifying:
  1. `HereModelMapper` assigns correct EVSE provider for AC and third-party stations without hardcoding `"VinFast"`.
  2. `StationDetailCoordinator` safely handles AC and third-party stations without triggering failing web scraping requests or error toast states.
  3. `StationDetailCoordinator.updateStation` preserves port statuses while synchronizing updated `drivingMetrics` (ETA, distance).
  4. `FavoritesViewModel` propagates computed driving metrics and telemetry updates to `StationDetailCoordinator` when a detail sheet is open.

---
Next Phase: [phase-03-live-cluster-search-and-telemetry-re-enrichment.md](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260917-0825-favorites-station-info-and-telemetry-fix/phase-03-live-cluster-search-and-telemetry-re-enrichment.md)
