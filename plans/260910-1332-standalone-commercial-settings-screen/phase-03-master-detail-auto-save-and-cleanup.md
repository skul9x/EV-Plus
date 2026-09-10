# Phase 03: Adaptive Layouts (Landscape Master-Detail & Portrait Fullscreen) & Final Cleanup
Status: 🟩 Completed
Dependencies: Phase 02

## Objective
Assemble the complete `SettingsScreen` supporting both Portrait fullscreen (single column with TopAppBar reset action and IME auto-scroll) and Landscape automotive layout (Tesla / Android Auto inspired Master-Detail sidebar and detail panel), integrate it into `MainActivity`'s tab container, and finalize legacy test alignment.

## Requirements
### Functional
- [x] Build `SettingsScreen` supporting adaptive layout branching via `effectiveIsLandscape`.
- [x] In Portrait:
  - Render `Scaffold` with TopAppBar displaying title `"Cài đặt"` and a top-right Reset icon button.
  - Render smooth single-column vertical scroll containing all setting groups in logical sequence: Display, Voice Guidance, Power Filter, About & Copyright.
  - Implement window insets handling ensuring virtual keyboard (IME) does not cover custom kW inputs.
- [x] In Landscape (Automotive 16:9 / 21:9):
  - Render Master-Detail split layout.
  - Left Sidebar (260dp width): 4 category selector tiles:
    1. 🖥️ *Hiển thị & Xe* (`DISPLAY`)
    2. 🔊 *Giọng nói & Lái xe* (`VOICE`)
    3. ⚡ *Bộ lọc công suất* (`FILTER`)
    4. ℹ️ *Thông tin ứng dụng* (`ABOUT`)
  - Anchored Reset Defaults button in sidebar footer.
  - Right Detail Content Canvas: displays selected category with large automotive touch targets ($\ge 56\text{dp}$), completely eliminating vertical scrolling.
- [x] Wire `SettingsScreen` into `MainActivity` inside `when (currentTab)` under `AppTab.SETTINGS`.
- [x] Ensure 100% full-canvas rendering: no dialogs, popups, or underlying dimmed station lists behind `SettingsScreen`.
- [x] Align legacy tests (`LandscapeNavigationRailTest.kt`, `SystemHomeAndImmersiveSettingsTest.kt`, `SettingsModalRedesignTest.kt`) with upgraded `AppTab.SETTINGS` contract.

### Non-Functional
- [x] Render 100% within root Compose hierarchy to preserve system fullscreen immersive mode on Carlinkit / Android Box devices.
- [x] Zero jank or frame drops during category transitions in landscape mode.

## Implementation Steps
1. [x] Create `SettingsScreen.kt`:
   - Implement `SettingsScreenPortrait` with `Scaffold`, TopAppBar, Reset icon, and single-column scroll container.
   - Implement `SettingsScreenLandscape` with 260dp Master Sidebar and Right Detail Canvas.
   - Route based on `effectiveIsLandscape`.
2. [x] Wire `SettingsScreen` into `MainActivity.kt`:
   - In `when (currentTab)`: add `AppTab.SETTINGS -> SettingsScreen(...)`.
   - Connect `OrientationPreferences`, `FocusModePreferences`, and `RoutingPreferencesManager`.
3. [x] Align existing navigation rail and settings modal test suites.
4. [x] Create single comprehensive test file in `app/src/test/java/com/evcs/favorites/ui/screens/SettingsScreenAdaptiveLayoutTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/screens/SettingsScreen.kt` - [NEW] Dedicated standalone adaptive settings screen.
- `app/src/main/java/com/evcs/favorites/MainActivity.kt` - Wire SettingsScreen into main tab container.
- `app/src/test/java/com/evcs/favorites/ui/screens/SettingsScreenAdaptiveLayoutTest.kt` - [NEW] Single comprehensive test for Phase 03.

## Test Criteria
- Exactly one comprehensive test file: `SettingsScreenAdaptiveLayoutTest.kt` verifying:
  1. Master-Detail category switching in landscape mode (active category state and detail panel mapping).
  2. Portrait layout composition (TopAppBar, single-column scroll container, IME window insets).
  3. Full-screen rendering without modal/overlay background underlays (`fillMaxSize`, no dim scrim/dialog).
  4. Integration verification: `MainActivity` tab routing to `SettingsScreen` on `AppTab.SETTINGS`.

---
Next Phase: None (Plan Complete)
