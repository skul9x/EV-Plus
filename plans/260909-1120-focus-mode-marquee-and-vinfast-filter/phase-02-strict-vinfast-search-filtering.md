# Phase 02: Strict VinFast Search Filtering

Status: ✅ Completed
Dependencies: Phase 01

## Objective

Ensure that on-demand nearby GPS searches strictly filter and return only genuine VinFast charging stations (`evse == "VinFast"`), eliminating 3rd-party partner networks (such as Ford, Đại lý Ford, Esky, Rabbit EVC, BitCharge, EBOOST) from the "Quanh đây" (Nearby) screen and Android Auto dashboard, while rigorously preserving user-saved stations in the "Yêu thích" (Favorites) tab regardless of provider.

## Requirements

### Functional

1. **Repository-Level Filtering in `searchNearbyVinFast`**:
   - In `EvcsRepository.kt` (`searchNearbyVinFastInternal`):
     - After querying `apiClient.searchStations(lat, lon)`, inspect the raw station list.
     - Filter stations using case-insensitive trimmed matching:
       ```kotlin
       val vinFastStations = rawStations.filter { raw ->
           raw.evse?.trim()?.equals("VinFast", ignoreCase = true) == true
       }
       ```
     - Update `coordinateCache` only with coordinates of the filtered VinFast stations.
     - Transform only the filtered VinFast stations into domain `Station` models.
2. **Domain-Level Guard in `NearbyStationFilter`**:
   - As a defense-in-depth safeguard against any edge case where non-VinFast stations might enter the filtering pipeline, ensure `NearbyStationFilter.filterStations()` and `NearbyStationFilter.filterSmartStations()` enforce `station.evse.trim().equals("VinFast", ignoreCase = true)` when filtering nearby stations.
3. **Preserve User Favorites**:
   - In `EvcsRepository.fetchAndMergeFavorites()` and `EvcsRepository.loadFavorites()`, do NOT enforce this filter. If a user explicitly saved a partner station to Favorites, it must remain fully visible and operational in the Favorites tab.
4. **Summary Feedback Pill Consistency**:
   - The UI count text in `NearbyUiState.filterSummaryPillText` ("Tìm thấy X trạm có cổng AC khả dụng", "Top 10 trạm sạc VinFast gần nhất còn cổng trống") automatically reflects only valid VinFast stations.

### Non-Functional

- Zero additional network overhead: Filters in-memory on raw response with $O(N)$ complexity.
- Resilient to whitespace and casing in the upstream `evse` payload (e.g. `"VinFast"`, `"vinfast"`, `" VinFast "`).

## Implementation Steps

1. [x] In `EvcsRepository.kt`:
   - In `searchNearbyVinFastInternal(lat, lon)`, filter `rawStations` to retain only items where `raw.evse?.trim()?.equals("VinFast", ignoreCase = true) == true`.
   - Ensure `fetchAndMergeFavorites` retains all user-saved favorites untouched.
2. [x] In `NearbyStationFilter.kt`:
   - Add provider verification helper `fun isVinFastStation(station: Station): Boolean = station.evse.trim().equals("VinFast", ignoreCase = true)`.
   - Enforce `isVinFastStation` in `filterStations` and `filterSmartStations`.
3. [x] Create single comprehensive unit test `NearbyVinFastFilteringTest.kt` under `app/src/test/java/com/evcs/favorites/data/repository/`.
4. [x] Run only the single Phase 02 test:
   `./gradlew testDebugUnitTest --tests "com.evcs.favorites.data.repository.NearbyVinFastFilteringTest"`
5. [x] Stop execution for user review.

## Files to Create/Modify

- `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt` - Enforce VinFast filtering on raw search results.
- `app/src/main/java/com/evcs/favorites/domain/filter/NearbyStationFilter.kt` - Add defense-in-depth VinFast check.
- `app/src/test/java/com/evcs/favorites/data/repository/NearbyVinFastFilteringTest.kt` - [NEW] Comprehensive test for Phase 02.

## Single Comprehensive Test

- **File**: `app/src/test/java/com/evcs/favorites/data/repository/NearbyVinFastFilteringTest.kt`
- **Command**: `./gradlew testDebugUnitTest --tests "com.evcs.favorites.data.repository.NearbyVinFastFilteringTest"`
- **Verification Criteria**:
  - Validates that a realistic mixed payload (VinFast, Ford, Đại lý Ford, Esky, Rabbit EVC) prunes all 3rd-party stations from `searchNearbyVinFast`.
  - Validates that case variations (`"vinfast"`, `"VinFast"`, `"VINFAST"`, with spaces) are properly matched and preserved.
  - Validates that non-VinFast stations (`"Ford"`, `"Esky"`, `"Rabbit EVC"`, null, empty) are removed from nearby search.
  - Validates that `fetchAndMergeFavorites` preserves saved partner stations in the Favorites repository.
