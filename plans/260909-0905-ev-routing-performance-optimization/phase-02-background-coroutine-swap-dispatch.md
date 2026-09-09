# Phase 02: Background Coroutine Dispatch for Alternate Station Swapping

Status: ✅ Completed
Dependencies: Phase 01

## Objective
Offload the CPU-intensive route recalculation logic invoked during station swapping (`swapStation` and `swapStopWithBackup`) from `Dispatchers.Main` to a dedicated background coroutine (`Dispatchers.Default` / `Dispatchers.IO`). This eliminates 150ms–500ms main-thread freezes and UI drops when drivers tap "Đổi sang trạm này" or pick a station from the alternate selection sheet.

## Requirements
### Functional
- Refactor `swapStation(stopIndex: Int, alternateStation: Station)` and `swapStopWithBackup(stopIndex: Int)` in `RouteViewModel.kt` to run asynchronous recalculation via `viewModelScope.launch(defaultDispatcher)`.
- Immediately dismiss the bottom sheet or update modal UI state (`selectedStopForSwap = null`) so the interface remains responsive.
- Safely update `_uiState` on completion with the new recalculated `routePlan`.
- Maintain cancellation safety: if a new route planning operation or another station swap is initiated while one is in progress, any superseded background recalculation must be cleanly cancelled or ignored.

### Non-Functional
- Smooth 60fps interaction: 0ms main thread blocking during route recalculation.
- Thread safety: State mutations happen atomically on the main thread / via `StateFlow.update`.

## Implementation Steps
1. In `app/src/main/java/com/evcs/favorites/ui/screens/RouteViewModel.kt`:
   - Inject or define `defaultDispatcher: CoroutineDispatcher = Dispatchers.Default` in `RouteViewModel` constructor to facilitate unit testing and background computational dispatch.
   - Maintain a reference to any active swap job `private var swapStationJob: Job? = null`.
   - Update `swapStation(stopIndex: Int, alternateStation: Station)`:
     - Cancel any previous swap job if active.
     - Dismiss swap sheet: `_uiState.update { it.copy(selectedStopForSwap = null) }`.
     - Launch `swapStationJob = viewModelScope.launch(defaultDispatcher)`:
       - Perform `evSmartRoutePlanner.recalculateWithAlternateStop(...)`.
       - Atomically update `_uiState.update { it.copy(routePlan = updatedPlan) }` on completion.
   - Ensure `swapStopWithBackup(stopIndex: Int)` properly leverages the asynchronous `swapStation` flow.
2. Verify that unit tests using `StandardTestDispatcher` / `TestScope` correctly advance time and assert state transitions.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/screens/RouteViewModel.kt` - Move station swapping to background coroutine.
- `app/src/test/java/com/evcs/favorites/ui/screens/RouteViewModelStationSwapAsyncTest.kt` - Comprehensive single verification test for Phase 02.

## Test Criteria (Single Verification Test)
- **Test Class:** `com.evcs.favorites.ui.screens.RouteViewModelStationSwapAsyncTest`
- **Execution Command:** `./gradlew testDebugUnitTest --tests "com.evcs.favorites.ui.screens.RouteViewModelStationSwapAsyncTest"`
- **Key Assertions:**
  1. `swapStation` immediately clears `selectedStopForSwap` and executes recalculation in a background coroutine without blocking the caller.
  2. Upon background recalculation completion, `_uiState.value.routePlan` reflects the updated stops, arrival SoCs, and battery trajectory.
  3. `swapStopWithBackup` seamlessly swaps primary and backup stations asynchronously.
  4. Rapid successive swap requests cancel or supersede earlier calculations without race conditions or corrupting the final route plan.

---
Next Phase: [Phase 03: Pre-compiled Static Regexes & Power Extraction Deduplication](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260909-0905-ev-routing-performance-optimization/phase-03-precompiled-regex-power-deduplication.md)
