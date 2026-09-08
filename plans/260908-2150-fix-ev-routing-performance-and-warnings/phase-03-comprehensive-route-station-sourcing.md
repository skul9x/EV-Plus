# Phase 03: Comprehensive Route Station Sourcing
Status: ✅ Completed
Dependencies: Phase 02

## Objective
Prevent false "Dead Zone" alerts on highway corridors by ensuring `RouteViewModel` sources candidate charging stations from the comprehensive repository station cache rather than strictly from the user's personal favorites.

## Requirements
### Functional
- [x] In `EvcsRepository.kt`:
  - Implement `getAllKnownStations(): List<Station>` which aggregates:
    1. In-memory favorite stations (`favoritesState.value` or persistent cache).
    2. Cached cluster search stations and resolved stations.
    3. Deduplicates stations cleanly by unique station ID.
- [x] In `RouteViewModel.kt`:
  - Update default candidate station fallback:
    `customCandidateStations ?: candidateStationsProvider?.invoke() ?: evcsRepository.getAllKnownStations()`.
  - If `getAllKnownStations()` is empty, gracefully fall back to `evcsRepository.favoritesState.value`.
- [x] In `MainActivity.kt`:
  - Wire `candidateStationsProvider` in `routeViewModel` factory to provide `repository.getAllKnownStations()`.

### Non-Functional
- [x] 0ms network latency for offline candidate station retrieval.
- [x] Reliable corridor planning along national highways without requiring manual favoriting of intermediate stations.

## Implementation Steps
1. In `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt`:
   - Add `getAllKnownStations(): List<Station>` merging cached favorites and cached cluster stations.
2. In `app/src/main/java/com/evcs/favorites/ui/screens/RouteViewModel.kt`:
   - Update `planRoute()` candidate station resolution logic to query `getAllKnownStations()`.
3. In `app/src/main/java/com/evcs/favorites/MainActivity.kt`:
   - Pass `candidateStationsProvider = { repository.getAllKnownStations() }` in `RouteViewModel.provideFactory`.
4. Create single comprehensive verification test in `app/src/test/java/com/evcs/favorites/ui/screens/RouteStationSourcingIntegrationTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt` - Expose comprehensive cached stations.
- `app/src/main/java/com/evcs/favorites/ui/screens/RouteViewModel.kt` - Default candidate sourcing to all known stations.
- `app/src/main/java/com/evcs/favorites/MainActivity.kt` - Wire candidate stations provider.
- `app/src/test/java/com/evcs/favorites/ui/screens/RouteStationSourcingIntegrationTest.kt` - Comprehensive single verification test.

## Verification Test
- **Test Class**: `com.evcs.favorites.ui.screens.RouteStationSourcingIntegrationTest`
- **Command**: `JAVA_HOME=/home/skul9x/.jdks/jdk-17.0.20.1+1 ./gradlew testDebugUnitTest --tests "com.evcs.favorites.ui.screens.RouteStationSourcingIntegrationTest"`
- **Criteria**:
  - Asserts route planner successfully discovers highway stations from `getAllKnownStations()` even when personal `favoritesState` is empty or sparse.
  - Verifies no false dead-zone warnings when sufficient stations exist in the comprehensive cache.
  - Verifies graceful fallback when no stations are available.
