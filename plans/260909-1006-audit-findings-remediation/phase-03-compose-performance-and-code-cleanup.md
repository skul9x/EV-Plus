# Phase 03: Compose Performance & UI Code Cleanup

Status: ✅ Completed
Dependencies: Phase 02

## Objective

Remediate clinical findings FIND-06 and FIND-08 identified in the system audit:
1. Eliminate `Long` autoboxing on every refresh/polling update in [NearbyScreen.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/screens/NearbyScreen.kt) by switching from `mutableStateOf(0L)` to `mutableLongStateOf(0L)`.
2. Suppress/resolve deprecated Car App `PaneTemplate` builder calls (`setHeaderAction`, `setTitle`) in [StationDetailCarScreen.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/car/StationDetailCarScreen.kt) for Car App API Level < 7.
3. Clean up compiler warnings for unused parameters (`cookieHeader`, `isSigningIn`) and variable shadowing (`activeStationForDetail`) in [FavoritesScreen.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/screens/FavoritesScreen.kt) and [NearbyScreen.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/screens/NearbyScreen.kt).

## Requirements

### Functional
- In `NearbyScreen.kt`, replace `var lastHandledRefreshTimestamp by remember { mutableStateOf(0L) }` with `remember { mutableLongStateOf(0L) }`.
- In `StationDetailCarScreen.kt`, add `@Suppress("DEPRECATION")` to the pre-API 7 fallback branch for `setHeaderAction` and `setTitle`.
- In `FavoritesScreen.kt`, suppress or clean unused legacy parameters (`cookieHeader`, `isSigningIn`) and resolve variable shadowing on line 413 (`activeStationForDetail`).
- In `NearbyScreen.kt`, suppress or clean unused legacy parameter `cookieHeader`.

### Non-Functional
- Eliminate Android Lint `AutoboxingStateCreation` warning.
- Zero breaking changes to Composable signatures to maintain test caller compatibility.
- Ensure 100% clean compilation with reduced compiler warnings.

## Implementation Steps
1. In [NearbyScreen.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/screens/NearbyScreen.kt):
   - Import `androidx.compose.runtime.mutableLongStateOf`.
   - Update line 137:
     ```kotlin
     var lastHandledRefreshTimestamp by remember { mutableLongStateOf(0L) }
     ```
   - Mark `cookieHeader: String? = null` with `@Suppress("UNUSED_PARAMETER")`.
2. In [StationDetailCarScreen.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/car/StationDetailCarScreen.kt):
   - Add `@Suppress("DEPRECATION")` to the `else` block handling Car API < 7.
3. In [FavoritesScreen.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/screens/FavoritesScreen.kt):
   - Mark `cookieHeader: String? = null` and `isSigningIn: Boolean = false` with `@Suppress("UNUSED_PARAMETER")`.
   - Rename shadowed local variable `activeStationForDetail` on line 413 to `targetStationForDetail`.
4. Create single verification test:
   [app/src/test/java/com/evcs/favorites/ui/ComposeOptimizationAndCleanupTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/ui/ComposeOptimizationAndCleanupTest.kt)

## Files to Create/Modify
- [app/src/main/java/com/evcs/favorites/ui/screens/NearbyScreen.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/screens/NearbyScreen.kt) - [MODIFY] Use `mutableLongStateOf` & clean warnings
- [app/src/main/java/com/evcs/favorites/car/StationDetailCarScreen.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/car/StationDetailCarScreen.kt) - [MODIFY] Suppress deprecation on pre-API 7 fallback
- [app/src/main/java/com/evcs/favorites/ui/screens/FavoritesScreen.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/screens/FavoritesScreen.kt) - [MODIFY] Clean unused warnings and shadowing
- [app/src/test/java/com/evcs/favorites/ui/ComposeOptimizationAndCleanupTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/ui/ComposeOptimizationAndCleanupTest.kt) - [NEW] Single comprehensive verification test

## Test Criteria
- Verify `StationDetailCarScreen` builds templates cleanly across Car API Level 1 to 7+.
- Verify primitive Long state management functions correctly without runtime exceptions or autoboxing.
- Verify screen composables retain stable public APIs.

## Verification Execution
Run only the single test for this phase:
```bash
./gradlew testDebugUnitTest --tests "com.evcs.favorites.ui.ComposeOptimizationAndCleanupTest"
```

---
All Phases Completed.
