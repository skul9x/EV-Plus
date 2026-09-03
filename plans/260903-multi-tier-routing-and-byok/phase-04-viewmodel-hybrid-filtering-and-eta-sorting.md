# Phase 04: ViewModel Hybrid Pipeline & ETA Sorting
Status: ✅ Completed
Dependencies: [Phase 03: Secure BYOK Preferences & Settings UI](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260903-multi-tier-routing-and-byok/phase-03-secure-byok-preferences-and-settings-ui.md)

## Objective
Integrate `MultiTierRoutingCoordinator` and `RoutingPreferencesManager` into `FavoritesViewModel`, employing a 2-step hybrid query (Step 1: 0ms Haversine sort; Step 2: asynchronous candidate batch routing on IO dispatcher), an in-memory TTL/displacement cache, and smart sorting by shortest driving ETA.

## Requirements
### Functional
- **Domain Model Extension (`StationModels.kt`)**:
  - Add optional `drivingMetrics: DrivingMetrics? = null` property to `Station`.
  - Expose helper `effectiveDistanceKm`: returns driving distance in km (`drivingMetrics.distanceMeters / 1000.0`) if `drivingMetrics` is available; otherwise falls back to `distanceKm` (Haversine).
  - Expose helper `effectiveDurationSeconds`: returns driving duration in seconds if available; otherwise null.
- **2-Step Hybrid Pipeline (`FavoritesViewModel.kt`)**:
  - **Step 1 (Immediate 0ms)**: Upon loading favorite stations and obtaining GPS coordinates, compute Haversine distances immediately and display the preliminary list.
  - **Step 2 (Async Coordinator Routing)**: In background coroutine (`Dispatchers.IO`), extract candidate stations:
    - Candidate count rule: `val candidateCount = minOf(validStations.size, 10)`. If total stations $\le 10$, route all of them. If $> 10$, route top 10 nearest by Haversine.
    - Exclude any stations with missing coordinates `(lat == 0.0 && lon == 0.0)`.
    - If user location is unavailable (`userLat == null || userLon == null`), skip coordinator routing and retain Step 1 baseline.
    - Previous in-flight `routingJob?.cancel()` is executed on every fresh refresh or GPS shift to prevent stale race conditions.
  - Enrich candidate stations with returned `DrivingMetrics`.
- **In-Memory Routing Cache**:
  - Cache results per station ID with timestamp and origin coordinates.
  - Invalidate cache only if:
    1. Cache TTL expires (> 3 minutes / 180,000 ms), OR
    2. User GPS position shifts by more than 200 meters (via `DistanceCalculator.calculateDistanceMeters`), OR
    3. User updates `RoutingSettings` (API key or Engine Mode changed), OR
    4. User explicitly triggers pull-to-refresh.
- **Intelligent ETA Sorting**:
  - Sort stations list using dual comparator:
    ```kotlin
    stations.sortedWith(
        compareBy<Station> { it.drivingMetrics?.durationSeconds ?: Long.MAX_VALUE }
            .thenBy(nullsLast()) { it.distanceKm }
    )
    ```
    Placing stations with the quickest driving arrival time at the top, followed by stations ordered by Haversine distance.
- **Settings & Routing Actions**:
  - Expose `routingSettings: StateFlow<RoutingSettings>`.
  - Expose methods: `updateRoutingSettings(settings: RoutingSettings)`, `validateGoogleApiKey(key: String)`, and `refreshRouting()`.

### Non-Functional
- Smooth UI state updates without freezing the Main/UI thread or causing screen flicker.
- Comprehensive coroutine lifecycle management (`viewModelScope`).

## Implementation Steps
1. Add `drivingMetrics` property and duration/distance accessors to `Station` in `StationModels.kt`.
2. Update `FavoritesViewModel.kt` to inject or instantiate `RoutingPreferencesManager` and `MultiTierRoutingCoordinator`.
3. Implement 2-step hybrid dispatching, caching logic, and shortest-ETA comparator.
4. Implement `FavoritesRoutingViewModelTest.kt` using `TestScope` and mock coordinator to verify the full ViewModel pipeline.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/model/StationModels.kt` - [Modify] Add `drivingMetrics` field to `Station`
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/FavoritesViewModel.kt` - [Modify] Integrate 2-step hybrid routing, cache, and ETA sorting
- `app/src/test/java/com/evcs/favorites/FavoritesRoutingViewModelTest.kt` - [New] Comprehensive file-based verification test for Phase 04

## Test Criteria
- Verify initial station state renders immediate Haversine distance before async routing finishes.
- Verify async coordinator run enriches candidate stations with `drivingMetrics`.
- Verify stations are sorted by shortest driving duration (`durationSeconds` ascending) rather than purely straight-line distance.
- Verify cache hit: second call with identical location within 3 minutes avoids re-querying the coordinator.
- Verify cache invalidation: location displacement > 200m or setting update triggers a fresh routing request.

---
Next Phase: [Phase 05: Station Card ETA Pill & Navigation UI](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260903-multi-tier-routing-and-byok/phase-05-station-card-eta-pill-and-navigation-ui.md)
