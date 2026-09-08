# Phase 01: Dynamic Responsive Sizing & Zero Text Clipping Layout
Status: ✅ Completed
Dependencies: None

## Objective
Implement dynamic screen-proportional sizing, automatic orientation handling, and zero-text-clipping typography for the Focus Mode floating window overlay. This guarantees readability at a 90cm driving distance on car screens/Android Box (16:9, 21:9 ultrawide) without compromising the handheld smartphone experience, while eliminating Vietnamese diacritic clipping and text truncation.

## Requirements

### Functional
1. **Dynamic Width Calculation from DisplayMetrics**:
   - Helper function in `FocusModeViewLayoutHelper`:
     `calculateOverlayWidth(screenWidthPx: Int, isLandscape: Boolean, density: Float): Int`
   - **Landscape (Car / Android Box):** Width = 30% to 34% (nominal `32%`) of screen width, clamped between `minWidth = 280dp` and `maxWidth = 440dp`.
   - **Portrait (Phone):** Width = 80% to 85% (nominal `82%`) of screen width, clamped between `minWidth = 280dp` and `maxWidth = 380dp`.
   - **Height:** Dynamic `WRAP_CONTENT` with `minHeight = 64dp`, never hardcoded to fixed pixels.
2. **WindowManager.LayoutParams Integration**:
   - `params.width` must be set to `calculateOverlayWidth(...)` for Full HUD so the overlay container strictly matches the calculated percentage rather than shrinking or overflowing unpredictably.
3. **Orientation & Screen Geometry Change Handling**:
   - `FocusModeForegroundService` implements `onConfigurationChanged(newConfig: Configuration)` and delegates to `FocusModeFloatingViewManager.onConfigurationChanged(newConfig)`.
   - On orientation change:
     - Re-query display metrics and orientation.
     - Recalculate overlay width via `calculateOverlayWidth`.
     - Re-clamp (x, y) coordinates via `FocusModeViewLayoutHelper.clampPosition` to prevent the overlay from escaping off-screen.
     - Call `windowManager.updateViewLayout(floatingRootView, params)`.
4. **Zero Text Clipping Policy (Anti-Truncation)**:
   - **Station Name Uniform Auto-Sizing:**
     - Use `AppCompatTextView` with `layout_width = 0dp` and `layout_weight = 1f` inside the header row (so the auto-size engine has bounded width constraints).
     - Apply `TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(tv, 13, 16, 1, TypedValue.COMPLEX_UNIT_SP)` with `maxLines = 1` and `ellipsize = TextUtils.TruncateAt.END`.
   - **Vietnamese Diacritic Safety (Zero Clipping):**
     - Ensure vertical breathing room: `paddingTop = dp(4)`, `paddingBottom = dp(4)` on `stationNameView` with `includeFontPadding = true` so tall uppercase vowels with accents (`Â, Ê, Ô, Ơ, Ư, Ỹ`) and low underdots (`Ạ, Ệ, Ộ, Ự`) are never clipped.
5. **Hero Metric Layout Separation**:
   - Isolate available slot counts into a dedicated high-contrast Hero Badge container (separate from the station title string).
   - Display: Indicator dot (`🟢` / `🔴` / `⚠️`) + Slot Count (e.g. `4/8 TRỐNG` or `HẾT TRỤ`), styled with `22sp - 24sp Bold` font for instant glanceability from 90cm.
   - Display power breakdown chips (e.g. `[250kW: 2] [60kW: 2]` or `detailedDcTiersText`) in a subordinate details row.
6. **Automotive Touch Target Dimensions**:
   - Close button `✕`: Touch target sized to $\ge 48dp \times 48dp$ (with centered glyph and adequate padding).
   - 1-Tap Reroute CTA button: Height sized to $\ge 48dp$ (and $56dp$ on landscape/car screens) with high visual contrast (`#1565C0` / `#00E676`).

### Non-Functional
- Pure JVM testability for layout calculations and clamping algorithms without requiring an Android emulator.
- Zero UI thread blocking during layout dimension calculations.
- Backward compatibility across API levels 26 to 34.

## Implementation Steps
1. **Extend `FocusModeViewLayoutHelper.kt`**:
   - Add `calculateOverlayWidth(screenWidthPx: Int, isLandscape: Boolean, density: Float): Int`.
   - Add dimension constants: `MIN_LANDSCAPE_WIDTH_DP = 280`, `MAX_LANDSCAPE_WIDTH_DP = 440`, `MIN_PORTRAIT_WIDTH_DP = 280`, `MAX_PORTRAIT_WIDTH_DP = 380`, `MIN_TOUCH_TARGET_DP = 48`.
   - Add `calculateHeroBadgeDimensions(density: Float): Pair<Int, Int>`.
2. **Refactor `FocusModeFloatingViewManager.kt`**:
   - Replace raw `TextView` with `AppCompatTextView` for station title and configure auto-sizing.
   - Restructure `buildCapsuleView()`:
     - Header row: Station Name (bounded weight 1f, auto-sizing 13-16sp) + Distance badge + Close button `✕` ($\ge 48dp$).
     - Hero Metric Card: Standalone container with prominent `24sp Bold` available slots count.
     - Details row: Charging tier chips / `detailedTiersView`.
     - Action row: 1-Tap Reroute CTA ($\ge 48dp$ touch target).
   - Set `params.width = calculateOverlayWidth(...)` on window attachment.
   - Add `onConfigurationChanged(newConfig: Configuration)` method to recalculate width and re-clamp coordinates upon screen rotation.
3. **Update `FocusModeForegroundService.kt`**:
   - Override `onConfigurationChanged(newConfig: Configuration)` and forward to `floatingViewManager?.onConfigurationChanged(newConfig)`.
4. **Create single verification test `FocusModeDynamicLayoutScalingTest.kt`**.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/focus/FocusModeViewLayoutHelper.kt` - [Modify] Add responsive scaling math, clamping thresholds, and layout helper methods.
- `app/src/main/java/com/evcs/favorites/focus/FocusModeFloatingViewManager.kt` - [Modify] Restructure view hierarchy with Hero Metric container, `AppCompatTextView` auto-sizing, $\ge 48dp$ touch targets, and orientation handling.
- `app/src/main/java/com/evcs/favorites/focus/FocusModeForegroundService.kt` - [Modify] Forward `onConfigurationChanged` events to floating view manager.
- `app/src/test/java/com/evcs/favorites/focus/FocusModeDynamicLayoutScalingTest.kt` - [New] Comprehensive JVM unit test for Phase 01.

## Test Criteria
- Single test: `com.evcs.favorites.focus.FocusModeDynamicLayoutScalingTest`
  - Verifies landscape width scales at 32% and clamps between `280dp` and `440dp` across car resolutions:
    - 1024x600 (7-inch Box): 328px (within bounds)
    - 1280x720 (9-inch Box): 410px (within bounds)
    - 1920x720 (12.3-inch Ultrawide): Clamps at 440dp max
    - 2560x1440 (2K Dashboard): Clamps at 440dp max
  - Verifies portrait width scales at 82% and clamps between `280dp` and `380dp` across phone resolutions:
    - 720x1280 (HD phone): Clamps within [280dp, 380dp]
    - 1080x2400 (FHD+ phone): Clamps at 380dp max
    - 1440x3120 (QHD+ phone): Clamps at 380dp max
  - Verifies boundary clamping maintains window visibility across orientation rotation swaps (e.g. 1080x2400 <-> 2400x1080).
  - Verifies Hero Metric typography specs (24sp Bold) and Vietnamese vertical padding safety ($\ge 4dp$).
  - Verifies automotive touch targets meet or exceed 48dp.

---
Next Phase: [Phase 02: Mini Pill ⇄ Full HUD 1-Tap Toggle & Touch Interaction](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260908-1025-focus-mode-hud-and-voice-settings/phase-02-mini-pill-to-full-hud-toggle-and-gestures.md)
