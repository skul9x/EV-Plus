# Phase 03: Wattage Filter Chips UI & Station Card Favorite Action
Status: ✅ Completed
Dependencies: Phase 02

## Objective
Design and implement responsive Jetpack Compose Material 3 UI components for power rating filtering (`WattageFilterChipsRow`), enhance the existing `StationCard` with backward-compatible interactive heart/favorite action support, and provide a polished `LoginRequiredDialog` for unauthenticated users.

## Requirements

### Functional
- **Wattage Filter Chips Row (`WattageFilterChipsRow.kt`)**:
  - Horizontal scrollable row (`LazyRow`) with content padding (16.dp horizontal) and 8.dp item spacing.
  - Displays Material 3 `FilterChip` for all 14 ratings in descending order: 360kW, 300kW, 250kW, 180kW, 150kW, 120kW, 80kW, 60kW, 40kW, 30kW, 22kW, 20kW, 11kW, 7kW, 3.5kW.
  - Features an action chip "Xóa bộ lọc" (Clear filters) visible whenever `selectedWattages.isNotEmpty()`.
  - Selected styling: EV Emerald container (`#10B981` / Material 3 PrimaryContainer), white checkmark icon (`Icons.Default.Check`), bold label typography.
- **Enhanced `StationCard.kt` with Heart Action (100% Backward Compatible)**:
  - Add optional parameters with sensible defaults to prevent breaking existing usages:
    ```kotlin
    fun StationCard(
        station: Station,
        onNavigateClick: (Station) -> Unit,
        onRemoveFavoriteClick: ((Station) -> Unit)? = null,
        onFavoriteClick: ((Station) -> Unit)? = null,
        isFavorite: Boolean = false,
        onStationClick: (Station) -> Unit = {},
        modifier: Modifier = Modifier
    )
    ```
  - Action button area logic:
    - If `onFavoriteClick != null`: Render an interactive Heart `IconButton` with a 48x48dp touch target:
      - If `isFavorite == true`: Tint with Coral Red (`0xFFEF4444`) displaying `Icons.Filled.Favorite`.
      - If `isFavorite == false`: Tint with Slate Gray (`0xFF94A3B8`) displaying `Icons.Outlined.FavoriteBorder`.
    - If `onRemoveFavoriteClick != null`: Render the existing Trash button (`Icons.Default.DeleteOutline`) for `FavoritesScreen`.
- **Login Required Dialog (`LoginRequiredDialog.kt`)**:
  - Material 3 `AlertDialog` triggered when an unauthenticated user taps the heart button.
  - Displays icon `Icons.Default.AccountCircle`, title "Đăng nhập để lưu yêu thích", and description: "Đăng nhập tài khoản EVCS để đồng bộ các trạm sạc yêu thích của bạn trên mọi thiết bị."
  - Action buttons: "Đăng nhập ngay" (triggers navigation to `LoginScreen`) and "Để sau" (dismisses dialog).

### Non-Functional
- Support dynamic dark/light themes adhering to Material 3 tokens.
- Ensure minimum 48x48dp touch target size for the heart icon button to comply with Android accessibility standards.

## Implementation Steps
1. Create `WattageFilterChipsRow.kt` in `com.evcs.favorites.ui.components`.
2. Update `StationCard.kt` in `com.evcs.favorites.ui.components` adding `onFavoriteClick` and `isFavorite` parameters while maintaining full compatibility with `FavoritesScreen`.
3. Create `LoginRequiredDialog.kt` in `com.evcs.favorites.ui.components`.
4. Create pure component logic helper `NearbyUiHelper.kt` for testability (e.g. formatting chip labels, determining clear button visibility, and resolving heart icon states).
5. Create comprehensive unit test `NearbyUiComponentsTest.kt` verifying chip selection state transitions, clear-all behaviors, and favorite icon resolution.

## Files to Create/Modify
- [NEW] `app/src/main/java/com/evcs/favorites/ui/components/WattageFilterChipsRow.kt` - Horizontal filter chips bar.
- [MODIFY] `app/src/main/java/com/evcs/favorites/ui/components/StationCard.kt` - Add favorite heart toggle button with backward compatibility.
- [NEW] `app/src/main/java/com/evcs/favorites/ui/components/LoginRequiredDialog.kt` - Dialog prompting unauthenticated users.
- [NEW] `app/src/main/java/com/evcs/favorites/ui/components/NearbyUiHelper.kt` - UI state formatting & chip helper.
- [NEW] `app/src/test/java/com/evcs/favorites/NearbyUiComponentsTest.kt` - Single comprehensive test for Phase 03.

## Test Criteria
- `NearbyUiComponentsTest.kt`:
  - Verify wattage chip label formatting and sorted order (360kW down to 3.5kW).
  - Verify multi-selection toggle logic (add/remove wattage options from active set).
  - Verify "Clear filters" action resets active set to empty.
  - Verify heart icon state helper resolves correct icon, color tint, and content description based on `isFavorite`.

---
Next Phase: [Phase 04: Bottom Navigation Bar, NearbyScreen & Two-Way State Sync](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260903-nearby-charging-stations-and-multi-tier-routing/phase-04-bottom-navigation-and-nearby-screen-integration.md)
