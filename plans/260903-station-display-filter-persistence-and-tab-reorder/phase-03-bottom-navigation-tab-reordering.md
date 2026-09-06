# Phase 03: Bottom Navigation Tab Order Swapping (Nearby Left, Favorites Right)

Status: ✅ Completed
Dependencies: Phase 01, Phase 02

## Objective
Reorder the bottom navigation tabs so that **Quanh đây** (Nearby) is positioned on the left and **Yêu thích** (Favorites) is positioned on the right, making **Quanh đây** the primary default destination when opening the app.

## Requirements
### Functional
1. **AppTab Enum Reordering (`AppTab.kt`)**:
   - Reorder `AppTab` enum entries so that `NEARBY` is declared first and `FAVORITES` is declared second:
     ```kotlin
     enum class AppTab(
         val label: String,
         val selectedIcon: ImageVector,
         val unselectedIcon: ImageVector
     ) {
         NEARBY(
             label = "Quanh đây",
             selectedIcon = Icons.Filled.LocationOn,
             unselectedIcon = Icons.Outlined.LocationOn
         ),
         FAVORITES(
             label = "Yêu thích",
             selectedIcon = Icons.Filled.Favorite,
             unselectedIcon = Icons.Outlined.FavoriteBorder
         )
     }
     ```
2. **Bottom Navigation Bar Display (`AppNavigationBar.kt`)**:
   - Because `AppNavigationBar` iterates over `AppTab.entries`, placing `NEARBY` first automatically places **Quanh đây** on the leftmost slot of the Material 3 `NavigationBar` and **Yêu thích** on the right slot.
   - Maintain active pill indicators (`EmeraldContainerDark`) and styling.
3. **Default Start Destination in `MainActivity.kt`**:
   - In `FavoritesApp`, initialize `currentTab` to `AppTab.NEARBY`:
     ```kotlin
     var currentTab by rememberSaveable { mutableStateOf(AppTab.NEARBY) }
     ```
   - Verify that guest users (unauthenticated) directly land on the Nearby screen without login gatekeeping, allowing immediate discovery of charging stations.
   - When tapping a favorite action on Nearby as a guest, tapping "Đăng nhập" seamlessly routes to `AppTab.FAVORITES` (which displays `LoginScreen`).

### Non-Functional
- Smooth navigation state transitions with zero screen flickering.
- 100% JVM-testable.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/navigation/AppTab.kt` - [MODIFY] Place `NEARBY` first, `FAVORITES` second.
- `app/src/main/java/com/evcs/favorites/navigation/AppNavigationBar.kt` - [MODIFY] Ensure clean layout contract for swapped tab order.
- `app/src/main/java/com/evcs/favorites/MainActivity.kt` - [MODIFY] Set default start tab to `AppTab.NEARBY`.
- `app/src/test/java/com/evcs/favorites/AppNavigationAndNearbyIntegrationTest.kt` - [MODIFY] Align navigation tab assertions and documentation comments with the new default start tab (`AppTab.NEARBY`).
- `app/src/test/java/com/evcs/favorites/BottomNavigationTabReorderTest.kt` - [NEW] Comprehensive verification test.

## Test Criteria (Exactly One Test File)
- `BottomNavigationTabReorderTest.kt`:
  - Verify `AppTab.entries[0]` is strictly `AppTab.NEARBY` ("Quanh đây") with LocationOn icon.
  - Verify `AppTab.entries[1]` is strictly `AppTab.FAVORITES` ("Yêu thích") with Favorite icon.
  - Verify default start tab is `AppTab.NEARBY`.
  - Verify bidirectional tab selection state transitions (`NEARBY <-> FAVORITES`).
  - Verify guest navigation routing contract from Nearby to Favorites login.

---
Plan Complete.
