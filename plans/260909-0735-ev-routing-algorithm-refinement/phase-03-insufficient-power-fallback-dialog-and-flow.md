# Phase 03: Insufficient Power Fallback Modal Flow & Criteria Relaxation

Status: ✅ Completed
Dependencies: [Phase 02: Lookahead Corridor Routing Engine with Power Filtering & Fallback Detection](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260909-0735-ev-routing-algorithm-refinement/phase-02-greedy-forward-simulation-and-window-search.md)

## Objective
Implement an automotive-grade Material 3 interactive alert dialog in the Route Planning UI when the routing engine detects an insufficient power condition. Because the routing engine optimistically generates a complete drivable route using the best available fallback station, the dialog provides clear situational awareness without blocking navigation: it notifies the user why the fallback station was chosen and offers 1-tap actions to accept the plan, swap the station, or adjust parameters.

## Requirements
### Functional
- [x] Represent dialog state in `RouteUiState` (inside `RouteViewModel.kt`):
  ```kotlin
  data class InsufficientPowerDialogState(
      val isVisible: Boolean = false,
      val requiredPowerKw: Double = 60.0,
      val fallbackStation: Station? = null,
      val fallbackPowerKw: Double = 30.0,
      val stopIndex: Int = 1,
      val message: String = ""
  )
  ```
- [x] Trigger logic in `RouteViewModel.planRoute()`:
  - When `plan.insufficientPowerWarning != null`, update `uiState` with `insufficientPowerDialog` where `isVisible = true` populated with the warning's metadata.
- [x] Dialog Actions:
  - **[Chấp nhận & Dùng lộ trình này]**: Calls `RouteViewModel.onAcceptRelaxedPower()` which confirms the current route, dismisses the dialog, and retains the complete route plan on screen.
  - **[Đổi trạm khác]**: Calls `RouteViewModel.onOpenSwapFromDialog()` which dismisses the dialog and directly opens the Station Swap bottom sheet for `plan.stops[stopIndex - 1]`, giving the driver immediate manual control.
  - **[Đóng]**: Calls `RouteViewModel.onDismissInsufficientPowerDialog()` to dismiss the dialog while keeping the calculated route visible.
- [x] Direct criteria relaxation helper:
  - Add `onRelaxPowerThresholdAndRecalculate(newPowerKw: Double)` in `RouteViewModel` if the driver decides to adjust their global preference to the lower power level.

### Non-Functional
- [x] Material 3 Automotive styling:
  - High contrast dark theme (`DarkCardBackground`), amber accent (`StatusMaintaining`) indicating warning without false panic.
  - Large touch targets ($\ge 48\text{dp}$) suitable for in-vehicle and Android Auto touch displays.
- [x] Recomposition performance:
  - Dialog visibility changes must not trigger polyline re-drawing or list re-fetching.

## Implementation Steps
1. [x] Update `app/src/main/java/com/evcs/favorites/ui/screens/RouteViewModel.kt`:
   - Add `insufficientPowerDialog: InsufficientPowerDialogState = InsufficientPowerDialogState()` to `RouteUiState`.
   - In `planRoute()`, inspect `plan.insufficientPowerWarning` and update `insufficientPowerDialog`.
   - Add `fun onAcceptRelaxedPower()`.
   - Add `fun onOpenSwapFromDialog()`.
   - Add `fun onDismissInsufficientPowerDialog()`.
2. [x] Update `app/src/main/java/com/evcs/favorites/ui/screens/RouteScreen.kt`:
   - Build `InsufficientPowerAlertDialog` composable.
   - Render `InsufficientPowerAlertDialog` when `uiState.insufficientPowerDialog.isVisible` is true.
   - Hook up acceptance, swap, and dismissal handlers.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/screens/RouteViewModel.kt` - Add dialog state, trigger, and handlers.
- `app/src/main/java/com/evcs/favorites/ui/screens/RouteScreen.kt` - Render `InsufficientPowerAlertDialog`.
- `app/src/test/java/com/evcs/favorites/ui/screens/RouteInsufficientPowerDialogTest.kt` - Comprehensive unit/UI test for Phase 03.

## Test Criteria (Single Verification Test)
- **Test Class:** `com.evcs.favorites.ui.screens.RouteInsufficientPowerDialogTest`
- **Key Assertions:**
  1. When routing produces an `insufficientPowerWarning`, `RouteUiState.insufficientPowerDialog.isVisible` becomes `true`.
  2. Dialog state captures the exact required power, suggested fallback station metadata, and stop index.
  3. Calling `onAcceptRelaxedPower()` dismisses the dialog while retaining the complete route plan.
  4. Calling `onOpenSwapFromDialog()` dismisses the dialog and sets `selectedStopForSwap` to the affected stop.
  5. Calling `onDismissInsufficientPowerDialog()` hides the dialog without losing existing state.

---
Next Phase: [Phase 04: Primary & Backup Station Pairing and Timeline UI](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260909-0735-ev-routing-algorithm-refinement/phase-04-primary-and-backup-station-pairing-ui.md)
