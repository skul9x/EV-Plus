# Phase 02: Adaptive Master-Detail Architecture & Automotive NavigationRail
Status: ⬜ Pending
Dependencies: Phase 01

## Objective
Design and implement an adaptive Master-Detail layout for wide screens and landscape orientations (Android Box, 16:9 in-car head units, and ultrawide 21:9 displays). In landscape mode, replace the bottom `AppNavigationBar` (which occupies 80dp of limited 600-720px vertical car screen height) with a compact vertical `AppNavigationRail` (72dp) on the left edge. Split content into a 2-column interface: Left Column (35% - 40%, 320-480dp) for the station list with sticky search/filters, and Right Column (60% - 65%) for embedded station details featuring live charging port statuses (250kW, 150kW, 60kW, 30kW, 20kW) and the primary "⚡ DẪN ĐƯỜNG & THEO DÕI" action button. Auto-select the nearest station on wide displays so drivers are never presented with an empty detail pane.

## Requirements

### Functional
1. **Adaptive Screen & Dimension Detection (`AdaptiveLayoutHelper`)**:
   - Pure Kotlin helper `AdaptiveLayoutHelper`:
     - `isLandscapeMode(widthDp: Float, heightDp: Float): Boolean`: returns `true` when `widthDp > heightDp` and `widthDp >= 600dp`.
     - `calculateMasterDetailWidths(totalWidthDp: Float, navRailWidthDp: Float = 72f): MasterDetailWidthAllocation`:
       - `availableWidthDp = totalWidthDp - navRailWidthDp`
       - `masterWidthDp = (availableWidthDp * 0.38f).coerceIn(320f, 480f)`
       - `detailWidthDp = availableWidthDp - masterWidthDp`
       - Validates that Master pane receives 35% - 40% (clamped between 320dp and 480dp) and Detail pane receives 60% - 65% of available width.
     - Supports common automotive displays:
       - 7-inch Android Box: 1024x600 px (~682 - 1024 dp)
       - 9-inch / 10.1-inch Android Box: 1280x720 px (~853 dp)
       - 10.25-inch / 12.3-inch Ultrawide Head Units: 1920x720 px (~1280 dp)
       - Handheld phone in portrait: 1080x2400 px -> returns `isLandscapeMode = false`

2. **Automotive `AppNavigationRail` Component**:
   - Create `AppNavigationRail` using Material 3 `NavigationRail`:
     - Anchored vertically at the start (left edge).
     - Compact width (~72dp) preserving horizontal content width.
     - Large automotive touch targets ($\ge 56dp$) for navigation destinations (`Nearby`, `Favorites`).
     - Distinct active indicator badge with `EmeraldContainerDark` container and `EmeraldPrimary` icon/label styling.
     - Replaces bottom `AppNavigationBar` when in landscape mode, recovering ~80dp of vertical screen space.

3. **Adaptive App Scaffold in `MainActivity.kt`**:
   - In `FavoritesApp`, inspect screen dimensions using `BoxWithConstraints`:
     - **Portrait Mode (`!isLandscape`):** Bottom `AppNavigationBar` + single column view hierarchy + modal bottom sheet for station details.
     - **Landscape Mode (`isLandscape`):** Left `AppNavigationRail` + 2-column Master-Detail layout + inline embedded detail pane (modal bottom sheet is suppressed).

4. **Master-Detail Layout in `NearbyScreen.kt` & `FavoritesScreen.kt`**:
   - **Left Column (Master - 35% to 40%):**
     - Contains GPS refresh hero action, sticky wattage filters, search bar, and station cards list.
     - Active station card has highlighted border (`EmeraldPrimary`, 2dp) to indicate current selection.
     - Clicking a station card updates `selectedStation` immediately without opening a bottom sheet.
   - **Right Column (Detail - 60% to 65%):**
     - Embeds `NativeStationDetailContent` inside a dedicated `Surface` / `Card` container.
     - Displays full charging port breakdown (250kW, 150kW, 60kW, 30kW, 20kW), photo carousel, address, and live metrics.
     - Features prominent action button: **"⚡ DẪN ĐƯỜNG & THEO DÕI"** (triggers Google Maps navigation and starts Focus Mode Floating Window simultaneously).
     - **Auto-Selection Rule:** When station list finishes loading and `selectedStation` is null, automatically select the first station (the nearest station!) so the right column is immediately populated and driver can start navigation with 1 tap.
     - If station list is empty, shows clear empty/error state.

5. **State Preservation Across Screen Rotations**:
   - Ensure `selectedStation`, scroll positions (`LazyListState`), search queries, and filter selections survive orientation changes without reset.

### Non-Functional
- Pure JVM testability for layout calculations, master-detail proportion math, and state resolution.
- Smooth transitions and zero visual jumping when rotating device or resizing window.

## Implementation Steps
1. Create `AdaptiveLayoutHelper.kt` in `app/src/main/java/com/evcs/favorites/ui/layout/`.
2. Create `AppNavigationRail.kt` in `app/src/main/java/com/evcs/favorites/navigation/`.
3. Update `MainActivity.kt` (`FavoritesApp`) to incorporate `AppNavigationRail` and adaptive scaffolding in landscape.
4. Update `NearbyScreen.kt` and `FavoritesScreen.kt` to support 2-column Master-Detail layout when `isLandscape` is true.
5. Create single verification test: `AdaptiveMasterDetailLayoutTest.kt` in `app/src/test/java/com/evcs/favorites/ui/`.
6. Run the single verification test:
   `./gradlew testDebugUnitTest --tests "com.evcs.favorites.ui.AdaptiveMasterDetailLayoutTest"`
7. Stop execution and await user review.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/layout/AdaptiveLayoutHelper.kt` - [New] Pure logic for orientation detection, width proportion splitting, and boundary clamping.
- `app/src/main/java/com/evcs/favorites/navigation/AppNavigationRail.kt` - [New] Material 3 vertical NavigationRail component for automotive landscape mode.
- `app/src/main/java/com/evcs/favorites/MainActivity.kt` - [Modify] Integrate NavigationRail and adaptive scaffolding in FavoritesApp.
- `app/src/main/java/com/evcs/favorites/ui/screens/NearbyScreen.kt` - [Modify] Master-Detail 2-column layout in landscape with auto-selection of nearest station.
- `app/src/main/java/com/evcs/favorites/ui/screens/FavoritesScreen.kt` - [Modify] Master-Detail 2-column layout in landscape.
- `app/src/test/java/com/evcs/favorites/ui/AdaptiveMasterDetailLayoutTest.kt` - [New] Exactly one comprehensive unit test for Phase 02.

## Test Criteria
- Single test: `com.evcs.favorites.ui.AdaptiveMasterDetailLayoutTest`
  - Verifies landscape vs portrait classification across common automotive displays:
    - 1024x600 (7-inch Android Box): Landscape
    - 1280x720 (9-inch Android Box): Landscape
    - 1920x720 (12.3-inch Ultrawide Head Unit): Landscape
    - 1080x2400 (Handheld smartphone): Portrait
  - Verifies master-detail width split:
    - Master list width is strictly between 35% and 40% within clamp limits `[320dp, 480dp]`.
    - Detail pane receives the remaining width (60% to 65%).
  - Verifies auto-selection policy (first station selected on wide screens if null).
  - Verifies selected station state propagation and active card selection resolution.
  - Verifies NavigationRail tab selection event mapping and dimension constraints.

---
Next Phase: [Phase 03: Automotive Touch Target Sizing (≥ 56dp) & High-Contrast Car Dark Mode](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260908-1100-android-box-landscape-ui-ux/phase-03-automotive-touch-targets-and-high-contrast-theme.md)
