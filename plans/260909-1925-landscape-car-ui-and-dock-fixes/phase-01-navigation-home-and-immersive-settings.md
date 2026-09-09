# Phase 01: Navigation Rail System Home Action & In-App Immersive Settings Panel

Status: ✅ Completed  
Dependencies: None

## Objective

1. Add a dedicated **System Home** action anchored at the top of the compact automotive navigation rail (`AppNavigationRail`), allowing drivers on Android Box / Carlinkit to return directly to the vehicle's Android home launcher screen with a single touch.
2. Fix the Carlinkit dock bar pop-up bug when entering settings by replacing the windowed `ModalBottomSheet` in landscape mode with an **In-App Landscape Settings Panel** within the same Activity window tree with `BackHandler` support, ensuring system immersive fullscreen flags remain 100% active and the Android Box system dock bar never unhides.

## Requirements

### Functional
- [x] In `AppNavigationRailDefaults.ACTION_ORDER`, place `HOME` as the top-most action: `HOME` ➔ `NEARBY` ➔ `FAVORITES` ➔ `SETTINGS` ➔ `REFRESH`.
- [x] Anchor the `HOME` button at the top of the rail with distinct spacing (`Spacer(modifier = Modifier.weight(1f))` or fixed separation) above the centered functional group (`NEARBY`, `FAVORITES`, `SETTINGS`, `REFRESH`) to prevent accidental clicks while driving.
- [x] Render `HOME` action using `Icons.Default.Home`, styled consistently with rail icons (26dp icon size, 50dp touch target).
- [x] On click of `HOME`, dispatch an Android launcher intent with robust automotive flags and fallback:
  ```kotlin
  try {
      val homeIntent = Intent(Intent.ACTION_MAIN).apply {
          addCategory(Intent.CATEGORY_HOME)
          flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
      }
      context.startActivity(homeIntent)
  } catch (e: Exception) {
      (context as? Activity)?.moveTaskToBack(true)
  }
  ```
- [x] In landscape mode (`isLandscape == true`), render the Settings screen as an in-app overlay/surface panel layered directly in the root Compose tree (e.g. Scrim `Box` + centered/side `Surface` of ~560dp width) instead of launching a separate windowed `ModalBottomSheet`.
- [x] In portrait mode (`isLandscape == false`), retain standard Material 3 `ModalBottomSheet`.
- [x] Integrate `BackHandler(enabled = isSettingsOpen) { isSettingsOpen = false }` so steering-wheel or physical back keys close the in-app panel cleanly without exiting the app.

### Non-Functional
- [x] Keep `RAIL_WIDTH_DP = 58.dp` intact.
- [x] Guarantee zero additional `DialogWindow` or `PopupWindow` instantiation in landscape settings to maintain `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` immersion.

## Implementation Steps

1. **Update `AppNavigationRail.kt`:**
   - Add `NavigationRailAction.HOME("Trang chủ xe")` to `NavigationRailAction` enum.
   - Update `AppNavigationRailDefaults.ACTION_ORDER` to put `HOME` at index 0.
   - Add `onHomeClick: () -> Unit` callback parameter to `AppNavigationRail` and `AppNavigationRailHelper.handleRailAction`.
   - Update layout to top-anchor the Home button with clear separation from the centered action group.
2. **Update `MainActivity.kt`:**
   - Wire `onHomeClick` in `MainActivity` with `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_RESET_TASK_IF_NEEDED` and `moveTaskToBack(true)` fallback.
   - Add `BackHandler(enabled = showRoutingSettingsModal) { showRoutingSettingsModal = false }`.
   - Pass `isLandscape = effectiveIsLandscape` to `RoutingSettingsModal`.
3. **Update `RoutingSettingsModal.kt`:**
   - Branch presentation based on `isLandscape`:
     - When `isLandscape`: Render in-tree `Box(Modifier.fillMaxSize())` scrim + `Surface` modal card (width ~560dp, tonal elevation, rounded corners).
     - When `!isLandscape`: Render standard `ModalBottomSheet`.
4. **Implement Single Verification Test:**
   - Create `com.evcs.favorites.navigation.SystemHomeAndImmersiveSettingsTest` to verify:
     - `ACTION_ORDER` contains `HOME` at index 0.
     - `handleRailAction` correctly routes `HOME` to `onHomeClick`.
     - Intent builder helper correctly sets `FLAG_ACTIVITY_NEW_TASK` and `FLAG_ACTIVITY_RESET_TASK_IF_NEEDED`.
     - Settings presentation mode helper resolves to `IN_APP_PANEL` for landscape and `BOTTOM_SHEET` for portrait.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/navigation/AppNavigationRail.kt` (MODIFY)
- `app/src/main/java/com/evcs/favorites/MainActivity.kt` (MODIFY)
- `app/src/main/java/com/evcs/favorites/ui/components/RoutingSettingsModal.kt` (MODIFY)
- `app/src/test/java/com/evcs/favorites/navigation/SystemHomeAndImmersiveSettingsTest.kt` (NEW)

## Test Criteria (Single Test)
- Test Class: `com.evcs.favorites.navigation.SystemHomeAndImmersiveSettingsTest`
- Verification Command: `./gradlew testDebugUnitTest --tests "com.evcs.favorites.navigation.SystemHomeAndImmersiveSettingsTest"`
- Assertions:
  - `HOME` is present in `ACTION_ORDER` as the first item.
  - Rail action dispatches `onHomeClick` invocation.
  - Home intent spec matches expected flags.
  - In-app landscape settings presentation contract resolves correctly.

---
Next Phase: [phase-02-station-detail-compact-pills-and-scroll-reset.md](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260909-1925-landscape-car-ui-and-dock-fixes/phase-02-station-detail-compact-pills-and-scroll-reset.md)
