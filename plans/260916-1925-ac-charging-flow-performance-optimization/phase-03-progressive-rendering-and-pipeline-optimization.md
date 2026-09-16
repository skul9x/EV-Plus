# Phase 03: Progressive Staged UI Rendering & Pipeline Optimization
Status: ✅ Completed
Dependencies: Phase 01, Phase 02

## Objective
Revamp `NearbyViewModel.executeAcHybridPipeline` to perform 2-stage progressive UI rendering: immediately emitting top 10 stations with fallback addresses and available plug counts as soon as HERE EV API results return (~300-500ms), turning off `isLoading`, and then asynchronously resolving canonical names in the background to eliminate blocking spinner latency.

## Requirements
### Functional
- [x] In `NearbyViewModel.executeAcHybridPipeline()`:
  - **Stage 1 (Instant Discovery Emit):**
    - Query HERE Maps EV API (10km, expanding to 20km if empty) with `nameResolver = null` (deferring HTML name resolution).
    - If stations are found, immediately update `_uiState`:
      - `top10DisplayStations = candidateStations` (with fallback address names `VinFast - $address`).
      - `isLoading = false`, `isSearching = false`, `isLocating = false`.
      - Reset `errorMessage = null`.
    - If user triggered refresh, dispatch `NearbyUiEvent.ScrollToTop`.
  - **Stage 2 (Asynchronous Enrichment):**
    - Concurrently resolve canonical station names via `nameResolver.resolveStationNamesBatch(candidateStations)`.
    - When resolved, smoothly update `_uiState`:
      - Update names of matching stations in `top10DisplayStations`.
    - Initiate debounced driving route calculation via `routingCoordinator.calculateRoutes`.
- [x] Safe Cancellation: If user rapidly toggles filters (e.g. AC -> DC -> AC), ensure ongoing AC discovery and enrichment jobs are cancelled cleanly without leaking stale states or race conditions.

### Non-Functional
- [x] Perceived Latency: User sees candidate stations and live port availability in under 500ms instead of waiting 2-3 seconds for batch HTML network scraping.
- [x] UI Smoothness: Name enrichment happens seamlessly without re-triggering full-screen loading spinners or scroll jump.

## Implementation Steps
1. [x] Update `NearbyViewModel.kt` to decouple HERE AC discovery from synchronous name resolution.
2. [x] Implement Stage 1 immediate UI emission and Stage 2 background name resolution in `executeAcHybridPipeline`.
3. [x] Ensure proper coroutine job tracking and cancellation during rapid filter switching.
4. [x] Create the single comprehensive test file:
   `app/src/test/java/com/evcs/favorites/ui/screens/NearbyAcProgressiveRenderingAndOptimizationTest.kt`
   Verifying:
   - UI immediately receives stations and sets `isLoading = false` before name resolution finishes.
   - Background name resolution subsequently enriches station names to authentic titles.
   - Filter switching cleanly aborts pending background resolution jobs.
5. [x] Run only this test to verify Phase 03:
   `./gradlew testDebugUnitTest --tests "com.evcs.favorites.ui.screens.NearbyAcProgressiveRenderingAndOptimizationTest"`

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt` - [MODIFY] Implement 2-stage progressive rendering and asynchronous name resolution in AC hybrid pipeline.
- `app/src/test/java/com/evcs/favorites/ui/screens/NearbyAcProgressiveRenderingAndOptimizationTest.kt` - [NEW] Single comprehensive unit test suite for Phase 03.

## Test Criteria
- [x] Immediate UI emission produces `isLoading = false` and populated `top10DisplayStations` within the first dispatch step.
- [x] Second update updates station names with canonical resolved values without resetting loading flags.
- [x] Switching away from AC mode aborts background enrichment without state leakage.

---
All Phases Complete.
