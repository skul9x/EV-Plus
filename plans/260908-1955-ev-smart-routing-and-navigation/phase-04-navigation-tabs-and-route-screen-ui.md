# Phase 04: Three-Tab Navigation & Route Screen UI
Status: ✅ Completed
Dependencies: [Phase 03: Smart EV Corridor Route Planner Engine](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260908-1955-ev-smart-routing-and-navigation/phase-03-smart-ev-corridor-route-planner.md)

## Objective
Reorganize EV-Plus main navigation into a 3-tab architecture with `FAVORITES` anchored in the center and `ROUTE` placed beside it. Implement the complete `RouteScreen` Compose UI according to the approved visual mockups and user specification (EV range slider, Origin/Destination dropdowns with GPS 1-tap `[🎯]`, Vertical Stop Timeline, Live Plug Status Badges with busy ETA, Energy Corridor Bar, and Swap Station Bottom Sheet).

## Requirements
### Functional
- [x] **Tab Structure Update:**
  - Update `AppTab.kt`: Add `ROUTE("Lộ trình", ...)` with automotive route vector icon.
  - Set tab order strictly to:
    1. `NEARBY` ("Quanh đây")
    2. `FAVORITES` ("Yêu thích" - centered)
    3. `ROUTE` ("Lộ trình" - beside favorites)
  - Update `MainActivity.kt`, `AppNavigationBar.kt`, and `AppNavigationRail.kt` to support all 3 tabs in both portrait and car-landscape orientations.
- [x] **Vehicle Range & Battery Input Cards:**
  - Slider: 100 - 500 km (default 200 km) with prompt: "Khai báo số km thực tế xe đi được an toàn ở 100% pin".
  - Slider: Starting SoC (10% - 100%, default 100%).
- [x] **Origin & Destination Selectors:**
  - Origin: Province dropdown $\to$ District dropdown, plus `[🎯] Vị trí hiện tại` GPS button to immediately use device location.
  - Destination: Province dropdown $\to$ District dropdown.
  - Swap Origin $\leftrightarrow$ Destination icon button.
- [x] **Route Results & Timeline:**
  - "TÌM TRẠM SẠC" CTA with loading indicator.
  - Dead-Zone Alert Banner: Render high-visibility crimson warning card if any gap exceeds vehicle safe range.
  - Vertical Timeline of stops:
    - Origin point (time & battery SoC).
    - Charging stops: station name, distance from origin, charger power pills (e.g. `⚡ 180 kW`, `⚡ 60 kW`), live plug status (`🟢 Trống 3/4` or `🟠 Đang kín - Dự kiến rảnh sau 18p`), estimated charging duration, arrival SoC %, and "Đổi trạm khác" button.
    - Destination point (final distance, overall travel time, total charging stops & duration).
- [x] **Energy Corridor Bar:**
  - Visual linear bar illustrating battery depletion across legs and replenishment jumps at charging stops.
- [x] **Swap Station ("Đổi trạm khác") Bottom Sheet:**
  - Modal bottom sheet displaying candidate VinFast stations within the safe corridor for that leg.
  - Tapping an alternate station updates the route and recomputes the adjacent legs dynamically.

### Non-Functional
- [x] Touch targets $\ge 48$ dp ($\ge 56$ dp on car landscape).
- [x] Dark theme aesthetic matching existing EV-Plus Emerald palette (`#10B981`, `#0F172A`, `#1E293B`).

## Implementation Steps
1. [x] Update `app/src/main/java/com/evcs/favorites/navigation/AppTab.kt`, `AppNavigationBar.kt`, `AppNavigationRail.kt`, and `MainActivity.kt`.
2. [x] Implement `app/src/main/java/com/evcs/favorites/ui/screens/RouteViewModel.kt`.
3. [x] Implement `app/src/main/java/com/evcs/favorites/ui/screens/RouteScreen.kt` and subcomponents (`EnergyCorridorBar.kt`, `RouteStopTimelineCard.kt`, `SwapStationBottomSheet.kt`).
4. [x] Implement comprehensive test in `app/src/test/java/com/evcs/favorites/ui/screens/RouteTabNavigationAndUiStateTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/navigation/AppTab.kt` - 3-tab enumeration.
- `app/src/main/java/com/evcs/favorites/ui/layout/AppNavigationBar.kt` - Bottom bar layout.
- `app/src/main/java/com/evcs/favorites/ui/layout/AppNavigationRail.kt` - Landscape rail layout.
- `app/src/main/java/com/evcs/favorites/MainActivity.kt` - Tab switching logic.
- `app/src/main/java/com/evcs/favorites/ui/screens/RouteViewModel.kt` - UI state holder & action processor.
- `app/src/main/java/com/evcs/favorites/ui/screens/RouteScreen.kt` - Route screen composable.
- `app/src/main/java/com/evcs/favorites/ui/components/EnergyCorridorBar.kt` - Battery trajectory bar.
- `app/src/main/java/com/evcs/favorites/ui/components/SwapStationBottomSheet.kt` - Alternate station chooser.
- `app/src/test/java/com/evcs/favorites/ui/screens/RouteTabNavigationAndUiStateTest.kt` - Phase 4 verification test.

## Verification Test (Exactly One File-Based Test)
- Test Class: `com.evcs.favorites.ui.screens.RouteTabNavigationAndUiStateTest`
- Execution Command:
  ```bash
  JAVA_HOME=/home/skul9x/.jdks/jdk-17.0.20.1+1 ./gradlew testDebugUnitTest --tests "com.evcs.favorites.ui.screens.RouteTabNavigationAndUiStateTest"
  ```

---
Next Phase: [Phase 05: Settings Modal Integration & Multi-Stop Navigation Handoff](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260908-1955-ev-smart-routing-and-navigation/phase-05-settings-modal-and-navigation-handoff.md)
