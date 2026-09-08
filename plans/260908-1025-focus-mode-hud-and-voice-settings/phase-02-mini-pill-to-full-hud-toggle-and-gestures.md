# Phase 02: Mini Pill ⇄ Full HUD 1-Tap Toggle & Touch Interaction
Status: ✅ Completed
Dependencies: Phase 01

## Objective
Implement a seamless 1-tap transformation between an ultra-compact "Mini Pill" HUD (~80x38dp) and the expanded "Full HUD", along with robust gesture discrimination using Android's `scaledTouchSlop` and touch duration thresholds. This eliminates map obstruction while driving and ensures that child buttons (Close and Reroute) can be clicked without accidentally triggering mode toggles or window drags.

## Requirements

### Functional
1. **Display Mode Definitions (`FocusModeDisplayMode`)**:
   - `MINI_PILL`: Ultra-compact capsule (~80dp x 38dp, 20dp corner radius, `#E61E1E24` translucent background). Displays only status dot and available slot count (`🟢 4` or `🔴 0`) with `18sp Bold` font. Designed to never block turn-by-turn navigation arrows on Google Maps / Vietmap.
   - `FULL_HUD`: Expanded automotive dashboard displaying Station Name (auto-sizing), Hero Metric card (`24sp Bold`), fast charging kW chips, 1-tap Reroute CTA, and Close button `✕`.
2. **1-Tap Toggle Mechanism**:
   - Tapping anywhere on the Mini Pill immediately expands it to Full HUD with smooth layout update.
   - Tapping on an empty/background area of the Full HUD collapses it back to Mini Pill.
   - Interactive child controls (Close button `✕` and Reroute CTA) handle their own click actions and MUST NOT trigger mode toggle.
3. **Robust Gesture Discrimination (Eliminating Hardcoded Pixels)**:
   - Use `ViewConfiguration.get(context).scaledTouchSlop` (with fallback floor of `12dp`) instead of arbitrary hardcoded pixel constants.
   - Drag initiation condition: Euclidean movement delta $\sqrt{\Delta x^2 + \Delta y^2} > touchSlop$.
   - Tap initiation condition: Movement delta $\le touchSlop$ and touch duration $< 350ms$.
4. **Child Button Click Protection**:
   - When user taps on Close button `✕` or Reroute CTA, the touch event is handled cleanly:
     - Close button triggers `onDismiss()`.
     - Reroute button triggers `triggerReroute()`.
     - Neither action triggers a mode toggle or unintended drag.
5. **Adaptive Edge Snapping on Mode Switch**:
   - Helper function in `FocusModeViewLayoutHelper`:
     `calculateAdjustedXOnModeChange(currentX: Int, oldWidth: Int, newWidth: Int, screenWidth: Int, margin: Int = DEFAULT_MARGIN): Int`
   - If the view is snapped to the right half of the display:
     `newX = maxOf(margin, screenWidth - newWidth - margin)`
   - If snapped to the left half:
     `newX = margin`
   - This guarantees that collapsing to Mini Pill keeps it neatly pinned against the screen edge (instead of floating in the middle), and expanding to Full HUD never pushes the view off the right edge of the screen.
6. **Dual-Container Layout Architecture**:
   - Root layout contains both `miniPillContainer` and `fullHudContainer`.
   - Mode switching toggles visibility (`View.VISIBLE` vs `View.GONE`) and updates `WindowManager.LayoutParams.width` and `x` in a single `windowManager.updateViewLayout` call with zero window flickering and zero recreation leaks.

### Non-Functional
- Pure JVM unit testability for gesture discrimination math, display mode state transitions, and edge snap recalculations.
- Smooth 60fps WindowManager layout adjustments.

## Implementation Steps
1. **Extend `FocusModeViewLayoutHelper.kt`**:
   - Add `enum class FocusModeDisplayMode { MINI_PILL, FULL_HUD }`.
   - Add `calculateMiniPillWidth(density: Float): Int` (~80dp).
   - Add `calculateAdjustedXOnModeChange(currentX: Int, oldWidth: Int, newWidth: Int, screenWidth: Int, margin: Int): Int`.
   - Add `formatMiniPillState(state: FocusModeState): Pair<String, FocusBadgeColor>`.
2. **Refactor `FocusModeFloatingViewManager.kt`**:
   - Add `currentDisplayMode: FocusModeDisplayMode` (initial default: `FocusModeDisplayMode.FULL_HUD`).
   - Build dual container view tree: `miniPillContainer` and `fullHudContainer`.
   - Integrate `ViewConfiguration.get(context).scaledTouchSlop` and touch timestamp tracking.
   - Implement `toggleDisplayMode()`:
     - Calculate new width (`miniPillWidth` vs `overlayWidth`).
     - Adjust X coordinate using `calculateAdjustedXOnModeChange`.
     - Toggle container visibilities.
     - Call `windowManager.updateViewLayout`.
   - Protect child click listeners from triggering container tap.
3. **Create single verification test `FocusModeDisplayModeToggleTest.kt`**.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/focus/FocusModeViewLayoutHelper.kt` - [Modify] Add `FocusModeDisplayMode`, mini pill dimensions, and mode change snap adjustment math.
- `app/src/main/java/com/evcs/favorites/focus/FocusModeFloatingViewManager.kt` - [Modify] Implement dual-container rendering, `scaledTouchSlop` gesture discrimination, child click isolation, and mode toggle.
- `app/src/test/java/com/evcs/favorites/focus/FocusModeDisplayModeToggleTest.kt` - [New] Comprehensive JVM unit test for Phase 02.

## Test Criteria
- Single test: `com.evcs.favorites.focus.FocusModeDisplayModeToggleTest`
  - Verifies toggle from `MINI_PILL` to `FULL_HUD` and vice versa.
  - Verifies `formatMiniPillState` outputs clean status dot and available slots (`🟢 4`, `🔴 0`, `⚠️ !`).
  - Verifies gesture discrimination math: movements below `scaledTouchSlop` within 350ms classify as clicks, whereas movements exceeding `scaledTouchSlop` classify as drags.
  - Verifies adaptive edge snapping adjustment:
    - Snapped right in Full HUD (width 400px, x=664 on 1080px screen) collapsing to Mini Pill (width 160px) correctly recalculates x to `1080 - 160 - 16 = 904px`.
    - Snapped right in Mini Pill (width 160px, x=904) expanding to Full HUD (width 400px) correctly recalculates x to `1080 - 400 - 16 = 664px`.
    - Snapped left keeps x at margin (16px) across both modes.

---
Next Phase: [Phase 03: Voice Alert Settings Persistence & Compose Toggle](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260908-1025-focus-mode-hud-and-voice-settings/phase-03-voice-alert-settings-and-tts-integration.md)
