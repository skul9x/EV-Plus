# Phase 02: Station Detail Action Split: "Chỉ Đường" & "Focus" Buttons
Status: ✅ Completed
Dependencies: Phase 01

## Objective
Split the single "Dẫn đường & Theo dõi" action button in the station detail view/pane (`NativeStationDetailContent`) into two distinct, high-contrast, automotive-grade action buttons:
1. **"Chỉ Đường"**: Launches pure Google Maps turn-by-turn navigation directly without starting Focus Mode background service or overlay.
2. **"Focus"**: Retains the existing "Dẫn đường & Theo dõi" functionality (renamed to "Focus"), which starts Focus Mode real-time telemetry overlay with background tracking and Google Maps navigation.
3. **Automotive UI/UX Optimization**: Lay out both buttons side-by-side in a horizontal row with `height >= 56dp`, rounded corners, distinct color coding (Automotive Navigation Blue for pure navigation vs. Signature Emerald Green for Focus Mode), clear icons (`AppIcons.Navigation` and `AppIcons.Bolt`), and independent debounce protection.

## Requirements

### Functional
- [x] Update `NativeStationDetailSheetHelper`:
  - Define `const val LABEL_NAVIGATE_ONLY = "Chỉ Đường"`.
  - Define `const val LABEL_FOCUS = "Focus"`.
  - Preserve `LABEL_NAVIGATE_AND_TRACK`, `LABEL_NAVIGATE`, and `LABEL_FOCUS_MODE` to guarantee 100% backwards compatibility with all existing unit tests.
  - Expose helper or contract for building pure navigation intent vs. focus mode activation spec.
- [x] Update `NativeStationDetailContent`:
  - Split quick action row: Replace the single full-width button with a horizontal `Row` containing two buttons with `weight(1f)` and `spacedBy(8.dp)`.
  - Button 1 ("Chỉ Đường"):
    - Icon: `AppIcons.Navigation` (or `AppIcons.NearMe`).
    - Label: `NativeStationDetailSheetHelper.LABEL_NAVIGATE_ONLY` ("Chỉ Đường").
    - Container Color: Automotive Navigation Blue (`Color(0xFF1D4ED8)`) or Primary. Content Color: `Color.White`.
    - Action: Debounced invocation of `onNavigate(station)` with 1000ms threshold, completely omitting `onStartFocusMode`.
    - Dimensions: Height `56dp` (`AutomotiveDimens.CAR_BUTTON_HEIGHT`), shape `RoundedCornerShape(16.dp)`, text 15sp Bold with `TextOverflow.Ellipsis`.
  - Button 2 ("Focus"):
    - Icon: `AppIcons.Bolt` (⚡).
    - Label: `NativeStationDetailSheetHelper.LABEL_FOCUS` ("Focus").
    - Container Color: Signature Emerald Green (`EmeraldPrimary`, `Color(0xFF10B981)`). Content Color: `Color.White`.
    - Action: Debounced invocation of Focus Mode (`if (onStartFocusMode != null) onStartFocusMode(station) else onNavigate(station)`) with 1000ms threshold.
    - Dimensions: Height `56dp` (`AutomotiveDimens.CAR_BUTTON_HEIGHT`), shape `RoundedCornerShape(16.dp)`, text 15sp Bold with `TextOverflow.Ellipsis`.
  - Keep the secondary row below with `Yêu thích` and `Chia sẻ` intact (56dp height, equal width).
  - Works seamlessly in both Landscape (`NearbyLandscapeScreen`, `FavoritesLandscapeScreen`) and Portrait modal sheet (`NativeStationDetailSheet`).

### Non-Functional
- [x] Safety & Driver Ergonomics: Touch targets strictly >= 56dp per Android Automotive Human Interface Guidelines.
- [x] Anti-Spam Hardening: Distinct debounce handlers for each button (`navDebounce` and `focusDebounce`) to prevent intent storms or double launches during bumpy in-car driving.

## Implementation Steps
1. [x] Update `NativeStationDetailSheetHelper.kt`:
   - Add `LABEL_NAVIGATE_ONLY = "Chỉ Đường"` and `LABEL_FOCUS = "Focus"`.
   - Maintain all existing label constants for test backward compatibility.
2. [x] Update `NativeStationDetailContent` in `NativeStationDetailSheet.kt`:
   - Add `val navDebounce = remember { DebounceHelper(1000L) }`.
   - Create `onNavigateOnlyClick` invoking `onNavigate(station)`.
   - Rename existing `onCombinedNavClick` to `onFocusClick`.
   - Replace the single `Button` with a side-by-side `Row` rendering "Chỉ Đường" and "Focus" buttons.
3. [x] Create single comprehensive test `app/src/test/java/com/evcs/favorites/ui/StationDetailActionSplitTest.kt` verifying:
   - `LABEL_NAVIGATE_ONLY` and `LABEL_FOCUS` constants.
   - Backward compatibility of `LABEL_NAVIGATE_AND_TRACK`, `LABEL_NAVIGATE`, `LABEL_FOCUS_MODE`.
   - Pure navigation action dispatches Google Maps navigation intent without foreground service invocation.
   - Focus action activates focus mode and foreground service.
   - Layout allocation and 56dp minimum height enforcement.
   - Debounce isolation between pure navigation and focus clicks.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt` - Split action button into "Chỉ Đường" and "Focus", update helper
- `app/src/test/java/com/evcs/favorites/ui/StationDetailActionSplitTest.kt` - Single comprehensive test for Phase 02

## Test Criteria
- Single Test Class: `com.evcs.favorites.ui.StationDetailActionSplitTest`
- Execution Command: `./gradlew testDebugUnitTest --tests "com.evcs.favorites.ui.StationDetailActionSplitTest"`
- Verification: All test cases pass with 0 failures, 0 regressions.

