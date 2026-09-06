# Phase 02: Compose Stability & Dead Code Elimination (PERF-004, PERF-014)
Status: ✅ Completed
Dependencies: Phase 01

## Objective
Restore Compose smart recomposition skipping across all primary screens by marking UI state holders as `@Immutable`, and eliminate dead code (`StatCard`, `resolveStatsGrid` if uncalled from active views) in `NativeStationDetailSheet.kt` to clean up the composable tree and reduce compiled DEX footprint while preserving all test invariants.

## Requirements
### Functional
- [x] `NearbyUiState` is annotated with `@androidx.compose.runtime.Immutable`, allowing Compose compiler to treat collection properties (`Set<WattageOption>`, `List<Station>`, `Map<String, DrivingMetrics>`) as stable for recomposition skipping.
- [x] `FavoritesUiState.Success` is annotated with `@androidx.compose.runtime.Immutable`, allowing Compose runtime to skip recomposing the favorites list when irrelevant state events occur.
- [x] Safely eliminate uncalled `StatCard` composable in `NativeStationDetailSheet.kt` without breaking public APIs or active layout components (`LiveForecastCapsule`, `DetailActionBar`, `StationPhotoCarousel`, `StationPhotoLightboxModal`).
- [x] Verify or adapt existing tests (including `NativeStationDetailPerformanceOptimizationTest.kt`) to ensure 100% test pass rate across all existing UI and ViewModel suites.

### Non-Functional
- [x] Compose Performance: Eliminate unskippable recompositions across `NearbyScreen`, `FavoritesScreen`, and child items during filter and routing updates.
- [x] Code Cleanliness: Zero unused dead composables or redundant calculation code.

## Implementation Steps
1. [x] Update `NearbyUiState.kt`:
   - Add `@Immutable` annotation to `NearbyUiState` class.
2. [x] Update `FavoritesUiState.kt`:
   - Add `@Immutable` annotation to `FavoritesUiState.Success` data class.
3. [x] Update `NativeStationDetailSheet.kt`:
   - Remove unused `StatCard` composable.
   - Retain/clean `NativeStationDetailSheetHelper` methods used in layout / tests as appropriate.
4. [x] Create verification test `ComposeStabilityAndDeadCodeTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/state/NearbyUiState.kt` - Add `@Immutable`.
- `app/src/main/java/com/evcs/favorites/ui/state/FavoritesUiState.kt` - Add `@Immutable` to `FavoritesUiState.Success`.
- `app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt` - Remove dead `StatCard` composable.
- `app/src/test/java/com/evcs/favorites/performance/ComposeStabilityAndDeadCodeTest.kt` - Dedicated verification test.

## Test Criteria (Single Comprehensive Test)
- `ComposeStabilityAndDeadCodeTest.kt` must verify:
  1. Reflection check: `NearbyUiState` has `@androidx.compose.runtime.Immutable` annotation present.
  2. Reflection check: `FavoritesUiState.Success` has `@androidx.compose.runtime.Immutable` annotation present.
  3. `NativeStationDetailSheet` compiles cleanly and unused `StatCard` composable is absent.
  4. State equality and immutability invariants hold across copies of `NearbyUiState` and `FavoritesUiState.Success`.

---
Next Phase: [phase-03-filter-algorithm-and-allocation-optimization.md](file:///d:/skul9x/EV-Plus-main/plans/260906-1540-comprehensive-performance-remediation/phase-03-filter-algorithm-and-allocation-optimization.md)

