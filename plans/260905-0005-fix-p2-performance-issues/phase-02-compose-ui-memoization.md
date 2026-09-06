# Phase 02: Compose Memoization & UI Allocation Reduction
Status: ✅ Completed  
Dependencies: `phase-01-crypto-and-algorithm-hot-paths.md`  
Issue IDs: `PERF-UI-03`

## Objective
Prevent micro-allocations and redundant string formatting on every recomposition in Jetpack Compose:
1. Wrap `formatJourneyBadge(station.drivingMetrics, station.distanceKm)` inside `StationCard.kt` with `remember(station.drivingMetrics, station.distanceKm) { ... }`.
2. Memoize working time / parking description string formatting with `remember(station.isFreeParking, station.workingTimeDescription) { ... }`.

## Requirements
### Functional
- Station cards render identical badge text, icons, colors, traffic descriptions, and working time text.
- When `station.drivingMetrics` or `station.distanceKm` changes, the badge updates dynamically as expected.

### Non-Functional
- Recompositions where `station.drivingMetrics` and `distanceKm` have not changed bypass `formatJourneyBadge`, avoiding `String.format` and intermediate `JourneyBadgeInfo` allocations.

## Implementation Steps
1. [x] In `app/src/main/java/com/evcs/favorites/ui/components/StationCard.kt`:
   - Wrap `journeyBadgeInfo` computation:
     ```kotlin
     val journeyBadgeInfo = remember(station.drivingMetrics, station.distanceKm) {
         formatJourneyBadge(station.drivingMetrics, station.distanceKm)
     }
     ```
   - Wrap parking and working time text:
     ```kotlin
     val workingTimeText = remember(station.isFreeParking, station.workingTimeDescription) {
         if (station.isFreeParking) "Mở ${station.workingTimeDescription} • Miễn phí gửi xe"
         else "Mở ${station.workingTimeDescription} • Gửi xe có phí"
     }
     ```
   - Check and memoize any other inline formatting in `StationCard` (e.g., connector display counts or rating strings).
2. [x] Create single comprehensive test `app/src/test/java/com/evcs/favorites/ui/components/StationCardMemoizationPerformanceTest.kt`:
   - Verifies `formatJourneyBadge` produces correct `JourneyBadgeInfo` for Google, OSRM, and Haversine routing engines.
   - Tests edge cases (null metrics, 0 duration, heavy congestion ratio vs free-flow).
   - Verifies referential memoization stability and expected badge outputs.
3. [x] Run single test:
   ```bash
   ./gradlew testDebugUnitTest --tests com.evcs.favorites.ui.components.StationCardMemoizationPerformanceTest
   ```

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/components/StationCard.kt` - [MODIFY] Memoize badge and string allocations
- `app/src/test/java/com/evcs/favorites/ui/components/StationCardMemoizationPerformanceTest.kt` - [NEW] Single comprehensive verification test

## Test Criteria
- [x] Journey badges for all three tiers (Google traffic, OSRM road distance, Haversine baseline) calculate correctly.
- [x] Formatted values match required visual specs without regression.
- [x] Exactly one test file is executed and passes cleanly.

---
Next Phase: `phase-03-public-cache-storage-decoupling.md`
