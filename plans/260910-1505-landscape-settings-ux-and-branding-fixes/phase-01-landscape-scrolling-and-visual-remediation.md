# Phase 01: Landscape Scrolling & Visual Hierarchy

Status: ✅ Complete
Dependencies: None

## Objective
Fix the unscrollable landscape detail canvas in `SettingsScreenLandscape` so cards are never clipped on automotive / Android Box screens, and redesign `StartupOrientationCard` and `FocusModeVoiceAlertCard` to resolve the confusing green-on-green visual clutter reported by the user.

## Requirements

### Functional
- Add `Modifier.verticalScroll(rememberScrollState())` to the Right Detail Content Canvas in `SettingsScreenLandscape` with generous bottom padding (`24.dp`) to ensure full visibility and reachability of all settings controls.
- Add safe vertical scrolling to the left master sidebar navigation column to prevent overflow on low-vertical-resolution car displays (e.g., 480p/600p).
- Replace `EmeraldContainerDark` card background in `StartupOrientationCard` and `FocusModeVoiceAlertCard` with neutral automotive dark background `DarkCardBackground` (`#1E293B`) or `MaterialTheme.colorScheme.surface`.
- Ensure option titles use high-contrast text (`MaterialTheme.colorScheme.onSurface` / `Color.White`) instead of neon green text, matching automotive readability standards.
- Tone down unselected option borders and Android Box badges: use subtle `outlineVariant` borders and neutral pill backgrounds, reserving `EmeraldPrimary` strictly as an accent color (radio button and active item border).

### Non-Functional
- Maintain minimum 56dp automotive touch targets.
- Comply with dark theme automotive contrast ratios (>= 4.5:1).

## Implementation Steps
1. In `app/src/main/java/com/evcs/favorites/ui/screens/SettingsScreen.kt`:
   - In `SettingsScreenLandscape`, wrap the detail content canvas in a scrollable `Box` or `Column` with `.verticalScroll(rememberScrollState())` and safe bottom padding.
   - Ensure the left master sidebar supports safe scrolling or flexible arrangement.
2. In `app/src/main/java/com/evcs/favorites/ui/components/SettingsComponents.kt`:
   - In `StartupOrientationCard`, change container color from `EmeraldContainerDark` to `DarkCardBackground`.
   - Update selected option styling: high-contrast text (`onSurface`), neutral container background, and a clean accent border.
   - Update the "Khuyên dùng cho Android Box ô tô" badge: replace heavy green borders and green container background with clean, distraction-free automotive badge styling.
   - In `FocusModeVoiceAlertCard`, change container color to `DarkCardBackground` for visual consistency across all 4 settings cards.
3. Create exactly one comprehensive file-based verification test:
   `app/src/test/java/com/evcs/favorites/ui/screens/LandscapeScrollAndVisualContractTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/screens/SettingsScreen.kt` - [MODIFY] Add vertical scrolling to landscape canvas.
- `app/src/main/java/com/evcs/favorites/ui/components/SettingsComponents.kt` - [MODIFY] Redesign card background, typography, and badge colors.
- `app/src/test/java/com/evcs/favorites/ui/screens/LandscapeScrollAndVisualContractTest.kt` - [NEW] Phase 01 contract test.

## Test Criteria
- Verify `SettingsScreenLandscape` right detail container contains `verticalScroll`.
- Verify `StartupOrientationCard` uses `DarkCardBackground` rather than `EmeraldContainerDark` as its card container color.
- Verify option title text is not hardcoded to `EmeraldPrimary`.
- Verify all unit tests pass with `./gradlew testDebugUnitTest --tests "com.evcs.favorites.ui.screens.LandscapeScrollAndVisualContractTest"`.

---
Next Phase: [Phase 02: Automotive Power Filter Steppers](phase-02-automotive-power-filter-stepper.md)
