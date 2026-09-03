# Phase 04: Bottom Navigation Bar, NearbyScreen & Two-Way State Sync
Status: ✅ Completed
Dependencies: Phase 03

## Objective
Assemble the complete end-to-end user experience by building `NearbyScreen.kt`, implementing a Material 3 `NavigationBar` in `MainActivity.kt` with Favorites as the default start destination, integrating runtime location permission requests, and ensuring seamless two-way favorite synchronization between the Nearby and Favorites screens.

## Requirements

### Functional
- **Navigation Architecture (`AppTab.kt` & `AppNavigationBar.kt`)**:
  - `AppTab` enum:
    - `FAVORITES`: Label "Yêu thích", icon `Icons.Filled.Favorite` / `Icons.Outlined.FavoriteBorder`.
    - `NEARBY`: Label "Quanh đây", icon `Icons.Filled.LocationOn` / `Icons.Outlined.LocationOn`.
  - `AppNavigationBar`: Material 3 `NavigationBar` embedded in the root `Scaffold` `bottomBar` with active pill indicators and EV Emerald styling.
  - Default start tab is strictly **`AppTab.FAVORITES`**.
- **Nearby Screen UI (`NearbyScreen.kt`)**:
  - `TopAppBar`: Title "Trạm sạc quanh đây", Settings gear icon `⚙️` (opens `RoutingSettingsModal`), and Refresh icon `🔄` (visible when results exist).
  - **Initial State (`hasSearched == false`)**:
    - Centered hero layout with location emblem.
    - Large primary action button: **"Nhấn để tìm trạm quanh đây"** with high-elevation EV Emerald styling and location icon.
    - Integrated Android location permission launcher via `rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions())`.
  - **Loading States**:
    - Animated progress bar / pulsing spinner with phased status text:
      - "Đang xác định vị trí GPS..." $\rightarrow$ "Đang tải dữ liệu trạm sạc..." $\rightarrow$ "Đang tính toán lộ trình Top 10...".
  - **Result State (`hasSearched == true`)**:
    - Sticky top `WattageFilterChipsRow`.
    - Header info pill: "Top 10 trạm sạc VinFast gần nhất còn cổng trống".
    - `LazyColumn` of `StationCard` elements displaying:
      - Multi-tier driving distance and ETA pill (traffic color-coded with fallback).
      - Live available port counts per power tier.
      - Heart favorite button with instantaneous toggle.
      - 1-Tap navigation button opening Google Maps Turn-by-Turn via `MapNavigator`.
    - Tapping card body opens existing `StationDetailModal`.
  - Empty state when all stations are filtered out: "Không có trạm sạc nào phù hợp với bộ lọc công suất".
- **Two-Way Favorites Synchronization**:
  - Both `FavoritesViewModel` and `NearbyViewModel` share the singleton `EvcsRepository`.
  - When a station is favorited on `NearbyScreen`, `repository.addFavoriteStation` emits the new list via `favoritesState`, immediately updating the Favorites screen.
  - When a station is deleted on `FavoritesScreen`, `repository.removeFavoriteStation` emits the updated IDs via `favoriteIdsState`, immediately un-tinting the heart icon on `NearbyScreen`.
- **Login Navigation Integration**:
  - If `LoginRequiredDialog` action "Đăng nhập ngay" is clicked on `NearbyScreen`, navigate to `LoginScreen`.

### Non-Functional
- Screen state preservation when switching tabs via `rememberSaveable`.
- Graceful handling of denied location permissions with an explanatory snackbar and prompt to open system settings.

## Implementation Steps
1. Define `AppTab.kt` and `AppNavigationBar.kt` in `com.evcs.favorites.navigation`.
2. Implement `NearbyScreen.kt` in `com.evcs.favorites.ui.screens`.
3. Update `MainActivity.kt` to host `AppNavigationBar` inside a parent `Scaffold`, orchestrating `currentTab`, `FavoritesScreen`, and `NearbyScreen`.
4. Implement shared favorite synchronization observer between `FavoritesViewModel` and `NearbyViewModel`.
5. Create comprehensive integration test `AppNavigationAndNearbyIntegrationTest.kt` verifying:
   - Default tab is `AppTab.FAVORITES`.
   - Tab switching transitions between screens without crashing.
   - Favorite toggle on Nearby updates shared repository state and reflects in Favorites.
   - Settings modal can be opened and saved from Nearby screen.

## Files to Create/Modify
- [NEW] `app/src/main/java/com/evcs/favorites/navigation/AppTab.kt` - Navigation destinations enum.
- [NEW] `app/src/main/java/com/evcs/favorites/navigation/AppNavigationBar.kt` - Material 3 NavigationBar composable.
- [NEW] `app/src/main/java/com/evcs/favorites/ui/screens/NearbyScreen.kt` - Complete Nearby Charging Station screen.
- [MODIFY] `app/src/main/java/com/evcs/favorites/MainActivity.kt` - Integrate bottom navigation and screen switching.
- [NEW] `app/src/test/java/com/evcs/favorites/AppNavigationAndNearbyIntegrationTest.kt` - Single comprehensive test for Phase 04.

## Test Criteria
- `AppNavigationAndNearbyIntegrationTest.kt`:
  - Verify `AppTab.FAVORITES` is initial destination.
  - Verify tab selection changes active route correctly.
  - Verify two-way favorite synchronization: favoriting a station via repository updates both ViewModels.
  - Verify routing settings updates are shared across both screens.

---
Master Plan: [Master Plan Overview](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260903-nearby-charging-stations-and-multi-tier-routing/plan.md)
