# Phase 02: Compose Recomposition & Scroll Optimization (PERF-03 & PERF-08)

Status: ✅ Completed  
Dependencies: Phase 01

## Objective
Prevent redundant recompositions of [StationCard.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/components/StationCard.kt) items during list scrolling, filter changes, and status updates by declaring `contentType = { "station_card" }` in `LazyColumn`, memoizing parent UI interaction callbacks with `remember`, and ensuring badge derivations avoid per-frame CPU recalculation.

## Requirements

### Functional
- [x] In [NearbyScreen.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/screens/NearbyScreen.kt#L637-L649):
  - Add `contentType = { "station_card" }` inside `items(...)` in `NearbyResultContent`:
    ```kotlin
    items(
        items = uiState.top10DisplayStations,
        key = { it.id },
        contentType = { "station_card" }
    ) { station ->
        StationCard(
            station = station,
            onNavigateClick = onNavigateClick,
            onFavoriteClick = onFavoriteClick,
            isFavorite = uiState.favoriteStationIds.contains(station.id),
            onStationClick = onStationClick
        )
    }
    ```
  - In `NearbyScreenContent`, memoize action lambdas passed to `NearbyResultContent` using `remember(context, viewModel)`:
    - `onNavigateClick = remember(context) { { station: Station -> MapNavigator.navigate(context, station.latitude, station.longitude, station.name) } }`
    - `onFavoriteClick = remember(viewModel) { { station: Station -> viewModel.toggleFavorite(station) } }`
    - `onStationClick = remember(viewModel) { { station: Station -> viewModel.selectStationForDetail(station) } }`
    - `onClearFilters = remember(viewModel) { { viewModel.clearSmartFilter() } }`
    - Auxiliary filter callbacks (`onCustomFilterClick`, `onDcFilterClick`, `onAcFilterClick`, `onSelectDcTier`, `onBackFromDc`).
- [x] In [FavoritesScreen.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/screens/FavoritesScreen.kt#L320-L330):
  - Add `contentType = { "station_card" }` inside `items(...)` in `FavoritesListContent`:
    ```kotlin
    items(
        items = stations,
        key = { it.id },
        contentType = { "station_card" }
    ) { station -> ... }
    ```
  - Memoize item click, navigation, and remove callbacks passed from `FavoritesScreenContent`.
- [x] In [StationCard.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/components/StationCard.kt):
  - Verify stability of parameters: [Station](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/data/model/StationModels.kt#L169) and [DrivingMetrics](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/data/routing/DrivingMetrics.kt#L74) already have `@Immutable`.
  - Ensure all sub-computations (`formatJourneyBadge`, working time text, power connector chips) remain properly wrapped with `remember(...)` so that Compose skips recomposition when model properties are unchanged (addressing PERF-08).

### Non-Functional
- [x] Smooth 60/120fps scrolling in `LazyColumn` without frame drops or jank caused by lambda re-allocations.
- [x] Compose runtime reuses existing composition slots across view recycling without disposing and re-allocating nodes.

## Implementation Steps
1. [x] Update [NearbyScreen.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/screens/NearbyScreen.kt):
   - Add `contentType = { "station_card" }` in `NearbyResultContent` `LazyColumn.items`.
   - Wrap action handler lambdas in `remember(context, viewModel)`.
2. [x] Update [FavoritesScreen.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/screens/FavoritesScreen.kt):
   - Add `contentType = { "station_card" }` in `FavoritesListContent` `LazyColumn.items`.
   - Wrap action handler lambdas in `remember(context, viewModel)`.
3. [x] Review [StationCard.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/components/StationCard.kt):
   - Verify badge resolution and formatting are fully memoized and skippable.
4. [x] Create the single comprehensive test file:
   - [StationCardRecompositionAndStabilityTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/ui/components/StationCardRecompositionAndStabilityTest.kt)
5. [x] Run the verification command to confirm all tests pass.

## Files to Create/Modify
- `[MODIFY]` [NearbyScreen.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/screens/NearbyScreen.kt) - Add `contentType` and memoize action lambdas with `remember`.
- `[MODIFY]` [FavoritesScreen.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/screens/FavoritesScreen.kt) - Add `contentType` and memoize action lambdas with `remember`.
- `[MODIFY]` [StationCard.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/components/StationCard.kt) - Ensure badge derivations are memoized and skippable.
- `[NEW]` [StationCardRecompositionAndStabilityTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/ui/components/StationCardRecompositionAndStabilityTest.kt) - Single comprehensive test for Phase 02.

## Test Criteria (Single Comprehensive Test File)
- **Target Test File**: [StationCardRecompositionAndStabilityTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/ui/components/StationCardRecompositionAndStabilityTest.kt)
- **Verification Command**: `./gradlew testDebugUnitTest --tests "com.evcs.favorites.ui.components.StationCardRecompositionAndStabilityTest"`
- Test cases included within the single file:
  - `testStationModel_hasImmutableAnnotation_enablingCompilerSkip`: Validates that `Station`, `PowerPort`, and `DrivingMetrics` have `@Immutable` so Compose compiler can mark them stable.
  - `testJourneyBadgeInfo_equalityAndCacheStability`: Confirms identical driving metrics produce structurally equal badge objects without memory churn.
  - `testStatusBadgeResolution_producesStableInstances`: Confirms badge appearance resolution is deterministic and idempotent.

---
Next Phase: [Phase 03: Coil ImageLoader & Cache Management](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260905-1935-performance-remediation/phase-03-coil-image-loader-and-cache-management.md)
