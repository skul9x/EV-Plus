# Phase 01: Adaptive System Bars Helper & Policy
Status: ✅ Completed
Dependencies: None

## Objective
Design and implement `AdaptiveSystemBarsHelper` with pure Kotlin contract methods to determine system bar behaviors, window flags, insets types (`systemBars()`), and edge-to-edge styling based on device configuration orientation (`ORIENTATION_LANDSCAPE` vs `ORIENTATION_PORTRAIT` / unspecified).

## Requirements
### Functional
- [x] Define `SystemBarsMode` enum (`IMMERSIVE_STICKY_LANDSCAPE`, `EDGE_TO_EDGE_PORTRAIT`).
- [x] Implement `resolveMode(configurationOrientation: Int): SystemBarsMode`:
  - `Configuration.ORIENTATION_LANDSCAPE` -> `SystemBarsMode.IMMERSIVE_STICKY_LANDSCAPE`.
  - `Configuration.ORIENTATION_PORTRAIT` or any other -> `SystemBarsMode.EDGE_TO_EDGE_PORTRAIT`.
- [x] Implement behavioral resolution methods:
  - `resolveSystemBarsBehavior(mode: SystemBarsMode): Int`: returns `WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` for landscape, `WindowInsetsControllerCompat.BEHAVIOR_DEFAULT` for portrait.
  - `shouldHideSystemBars(mode: SystemBarsMode): Boolean`: returns `true` for landscape, `false` for portrait.
- [x] Provide `applySystemBars(window: Window, configurationOrientation: Int)` helper method to orchestrate `WindowCompat.setDecorFitsSystemWindows(window, false)` and `WindowInsetsControllerCompat` operations cleanly.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/util/AdaptiveSystemBarsHelper.kt` - [Create] Pure helper class and policy implementation.
- `app/src/test/java/com/evcs/favorites/util/AdaptiveSystemBarsHelperTest.kt` - [Create] Exactly one single comprehensive verification test.

## Verification Criteria
- [x] Single verification test `com.evcs.favorites.util.AdaptiveSystemBarsHelperTest` passes.
- [x] No regression on existing tests.

---
Next Phase: [Phase 02: Activity Lifecycle & Edge-to-Edge Integration](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260913-2205-adaptive-orientation-system-bars/phase-02-activity-lifecycle-and-edge-to-edge-integration.md)
