# Phase 04: Compose Dropdown & Zero-Allocation UI Optimizations
Status: ✅ Completed
Dependencies: Phase 03

## Objective
Optimize Jetpack Compose rendering performance and eliminate unnecessary allocations in the Route screen, Province/District selection dropdowns, and Energy Corridor Canvas.

## Requirements
### Functional
- [x] In `VietnamLocationsRepository.kt`:
  - Cache the sorted 63-province list using a thread-safe `lazy` delegate so `Collator.getInstance(Locale("vi", "VN"))` is executed once rather than on every invocation.
- [x] In `RouteViewModel.kt` & `RouteUiState`:
  - Add pre-computed `originDistrictNames: List<String>` and `destinationDistrictNames: List<String>` to `RouteUiState`.
  - Update `RouteScreen.kt` to consume these pre-computed lists directly, eliminating repeated `.map { it.name }` list allocations on every recomposition.
- [x] In `RouteScreen.kt`:
  - Optimize `DropdownSelector` to render smoothly without frame drops when opening 63 province options on automotive and mobile screens.
- [x] In `EnergyCorridorBar.kt`:
  - Avoid creating short-lived `Triple` allocations and recreate `Path` only when `energyProfile` or size dimensions change, eliminating Garbage Collector pressure during drawing and scrolling.

### Non-Functional
- [x] Zero object allocations during the Canvas Draw phase in `EnergyCorridorBar`.
- [x] Cold start and repeated dropdown open response time < 16ms (60-120fps smooth animation).

## Implementation Steps
1. In `app/src/main/java/com/evcs/favorites/data/locations/VietnamLocationsRepository.kt`:
   - Memoize sorted provinces list in `cachedProvinces by lazy`.
2. In `app/src/main/java/com/evcs/favorites/ui/screens/RouteViewModel.kt`:
   - Include `originDistrictNames` and `destinationDistrictNames` directly in `RouteUiState`.
3. In `app/src/main/java/com/evcs/favorites/ui/screens/RouteScreen.kt`:
   - Pass pre-computed district name lists to `DropdownSelector`.
4. In `app/src/main/java/com/evcs/favorites/ui/components/EnergyCorridorBar.kt`:
   - Optimize drawing scope and points mapping to prevent per-frame object churn.
5. Create single comprehensive verification test in `app/src/test/java/com/evcs/favorites/ui/screens/RouteUiPerformanceOptimizationTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/locations/VietnamLocationsRepository.kt` - Cache sorted provinces.
- `app/src/main/java/com/evcs/favorites/ui/screens/RouteViewModel.kt` - Pre-compute district names in state.
- `app/src/main/java/com/evcs/favorites/ui/screens/RouteScreen.kt` - Zero-allocation dropdown consumption.
- `app/src/main/java/com/evcs/favorites/ui/components/EnergyCorridorBar.kt` - Zero-allocation Canvas draw optimization.
- `app/src/test/java/com/evcs/favorites/ui/screens/RouteUiPerformanceOptimizationTest.kt` - Comprehensive single verification test.

## Verification Test
- **Test Class**: `com.evcs.favorites.ui.screens.RouteUiPerformanceOptimizationTest`
- **Command**: `JAVA_HOME=/home/skul9x/.jdks/jdk-17.0.20.1+1 ./gradlew testDebugUnitTest --tests "com.evcs.favorites.ui.screens.RouteUiPerformanceOptimizationTest"`
- **Criteria**:
  - Asserts `VietnamLocationsRepository.getAllProvinces()` returns cached instance across calls.
  - Verifies `RouteUiState` maintains synchronized district name collections with zero inline mapping overhead.
  - Verifies `EnergyCorridorBar` waypoint geometry handling remains functionally consistent across varying route profiles.
