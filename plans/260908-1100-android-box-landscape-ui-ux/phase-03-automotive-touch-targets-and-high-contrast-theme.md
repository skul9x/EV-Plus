# Phase 03: Automotive Touch Target Sizing (≥ 56dp) & High-Contrast Car Dark Mode
Status: ✅ Completed
Dependencies: Phase 02

## Objective
Enforce Google Automotive touch-target safety standards ($\ge 56dp$, cards $\ge 76dp$) across all clickable UI elements in car mode to prevent miss-clicks while driving over bumpy roads. Establish a high-contrast dark automotive theme (deep obsidian `#121216` background, vivid emerald green `#00E676` accents, electric cyan `#00E5FF`, and crisp white `#FFFFFF` text) that maximizes readability through windshield glare during the day, eliminates distracting cabin reflections at night, and satisfies WCAG AAA contrast ratios ($\ge 7:1$).

## Requirements

### Functional
1. **Automotive Dimension Tokens (`AutomotiveDimens`)**:
   - Define automotive layout constants in `app/src/main/java/com/evcs/favorites/ui/theme/AutomotiveDimens.kt`:
     - `MIN_CAR_TOUCH_TARGET = 56.dp` (Google Automotive minimum touch target)
     - `CAR_CARD_MIN_HEIGHT = 76.dp` (Automotive card height target)
     - `CAR_BUTTON_HEIGHT = 56.dp` (Primary automotive action button height)
     - `CAR_ICON_SIZE = 28.dp` (Clear iconography readable from 90cm)
     - `CAR_HERO_METRIC_TEXT_SIZE = 24.sp` (Available plug count Hero Metric)
     - `CAR_PADDING_SPACER = 12.dp`
     - `CAR_CHIP_HEIGHT = 48.dp` (Filter chips in automotive mode)

2. **High-Contrast Automotive Color Palette**:
   - In `Color.kt`, declare dedicated automotive color tokens:
     - `CarDarkBackground = Color(0xFF121216)` (Deep obsidian reducing cabin glare)
     - `CarDarkSurface = Color(0xFF1B1B22)` (Distinct card surface)
     - `CarDarkSurfaceVariant = Color(0xFF242430)`
     - `CarDarkOutline = Color(0xFF2E2E3E)`
     - `CarAccentGreen = Color(0xFF00E676)` (High-visibility neon emerald for available status & primary CTA, contrast ratio > 10:1 vs `#121216`, exceeding WCAG AAA)
     - `CarAccentCyan = Color(0xFF00E5FF)` (High-visibility electric cyan for navigation and tech indicators, contrast ratio > 11:1 vs `#121216`, exceeding WCAG AAA)
     - `CarStatusOffline = Color(0xFFFF3B30)` (High-visibility alert red for full/offline stations)
     - `CarStatusWarning = Color(0xFFFF9500)` (Vivid amber for maintaining stations)
     - `CarTextPrimary = Color(0xFFFFFFFF)` (Crisp pure white text)
   - Update `Theme.kt` to integrate high-contrast dark color scheme for automotive mode.

3. **Automotive Touch Target Enforcement in Components**:
   - **StationCard (`StationCard.kt`):**
     - Minimum clickable card height $\ge 76dp$.
     - When displayed in landscape / car mode, format Hero Metric with `24sp Bold` font size (e.g. `🟢 4/8 TRỐNG`), clearly visible to driver sitting 70-90cm from the screen.
     - Quick navigation button inside card sized $\ge 56dp$ in height.
   - **Action Buttons in Detail Pane / `NativeStationDetailSheet`:**
     - Primary CTA button **"⚡ DẪN ĐƯỜNG & THEO DÕI"**: Minimum height `56dp`, font `16sp Bold`, full touch target coverage. Executes 1-tap navigation (Google Maps + Focus Mode HUD).
     - Secondary buttons (Favorite, Share, Reload): Height $\ge 56dp$.
   - **SmartFilterBar Chips:**
     - Height $\ge 48dp$ in car mode with larger touch paddings.
   - **NavigationRail Items:**
     - Tab touch target $\ge 56dp \times 56dp$ with high contrast active indicator.

4. **Contrast & Glanceability Verification**:
   - Verify mathematical contrast ratio of primary text and status accents against `#121216` background meets or exceeds WCAG AAA standard ($\ge 7:1$) for safety-critical driving information.

### Non-Functional
- Pure JVM testability for dimension constraints and contrast ratio calculations.
- Responsive ripple touch feedback with visual responsiveness.

## Implementation Steps
1. Create `AutomotiveDimens.kt` in `app/src/main/java/com/evcs/favorites/ui/theme/`.
2. Add automotive high-contrast color tokens to `Color.kt` and update `Theme.kt`.
3. Update `StationCard.kt` to support car mode dimensions, `24sp Bold` hero metrics, and $\ge 56dp$ touch targets.
4. Update `NativeStationDetailSheet.kt` action buttons to enforce `56dp` height and combined **"⚡ DẪN ĐƯỜNG & THEO DÕI"** action.
5. Update `SmartFilterBar.kt` chip dimensions for landscape automotive touch target compliance.
6. Create single verification test: `AutomotiveTouchTargetAndThemeTest.kt` in `app/src/test/java/com/evcs/favorites/ui/`.
7. Run the single verification test:
   `./gradlew testDebugUnitTest --tests "com.evcs.favorites.ui.AutomotiveTouchTargetAndThemeTest"`
8. Stop execution and await user review.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/theme/AutomotiveDimens.kt` - [New] Automotive touch target dimensions, font sizes, and layout spacing tokens.
- `app/src/main/java/com/evcs/favorites/ui/theme/Color.kt` - [Modify] Add high-contrast automotive dark palette (`#121216`, `#00E676`, `#00E5FF`, etc.).
- `app/src/main/java/com/evcs/favorites/ui/theme/Theme.kt` - [Modify] Update DarkColorScheme to leverage automotive high-contrast colors.
- `app/src/main/java/com/evcs/favorites/ui/components/StationCard.kt` - [Modify] Integrate automotive touch targets, $\ge 56dp$ buttons, and 24sp Hero Metric.
- `app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt` - [Modify] Enforce 56dp button heights for primary automotive actions and 1-tap "⚡ DẪN ĐƯỜNG & THEO DÕI".
- `app/src/main/java/com/evcs/favorites/ui/components/SmartFilterBar.kt` - [Modify] Adjust filter chip touch targets for landscape automotive mode.
- `app/src/test/java/com/evcs/favorites/ui/AutomotiveTouchTargetAndThemeTest.kt` - [New] Exactly one comprehensive unit test for Phase 03.

## Test Criteria
- Single test: `com.evcs.favorites.ui.AutomotiveTouchTargetAndThemeTest`
  - Verifies all automotive touch target tokens meet or exceed $56dp$.
  - Verifies luminance and contrast ratio calculations for `#00E676`, `#00E5FF`, and `#FFFFFF` against `#121216` comply with WCAG AAA standards ($\ge 7:1$).
  - Verifies StationCard hero metric typography is configured to `24sp Bold` for car mode.
  - Verifies button height specifications across landscape and portrait modes.

---
Phase 2 Complete. Ready for Phase 3: Android Auto (Car App Library) Integration.
