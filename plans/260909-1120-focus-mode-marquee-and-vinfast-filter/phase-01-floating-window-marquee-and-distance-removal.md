# Phase 01: Floating Window Header Marquee & Distance Removal

Status: ✅ Completed
Dependencies: None

## Objective

Streamline the Focus Mode Floating Window overlay header row by removing the redundant distance badge (`distanceBadgeView`) and converting the station name TextView (`stationNameView`) to a single-line continuous marquee. When a station name exceeds the available header width, it scrolls horizontally across the screen infinitely, ensuring that long station names (especially with Vietnamese diacritics) are fully readable without auto-sizing font degradation or ellipsis cut-off.

## Requirements

### Functional

1. **Remove Distance Badge from Header**:
   - Completely remove `distanceBadgeView` and its sub-layout from the header row in `FocusModeFloatingViewManager.kt`.
   - Remove `distanceBadgeView` references in `buildCapsuleView()`, `updateView()`, and `removeOverlay()`.
   - Retain `FloatingViewState.distanceText` as a nullable property to preserve data-contract backward compatibility for notifications/bridges if needed, but do not render it in the floating window header.
   - Allocate the freed horizontal space to `stationNameView` (`LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)`).

2. **Single-Line Smooth Marquee for Station Name**:
   - In `FocusModeFloatingViewManager.kt`, configure `stationNameView` (`AppCompatTextView`):
     - `isSingleLine = true` (or `maxLines = 1`)
     - `ellipsize = TextUtils.TruncateAt.MARQUEE`
     - `marqueeRepeatLimit = -1` (infinite looping marquee)
     - `isSelected = true` (essential requirement in Android View system to activate marquee animation without requiring keyboard focus in an alert window)
     - `isHorizontalFadingEdgeEnabled = true` with fading edge length (~10dp) so text transitions smoothly near the edge.
     - Set fixed standard font size: `textSize = 15f`, `Typeface.BOLD`, `Color.WHITE`.
     - Retain adequate vertical padding (`VIETNAMESE_VERTICAL_PADDING_DP = 4`) and `includeFontPadding = true` to prevent clipping of Vietnamese tone marks (`ể`, `ệ`, `ỗ`, `ũ`).
   - In `updateView(state)`:
     - Set `stationNameView?.text = viewState.stationName`.
     - Explicitly re-assert `stationNameView?.isSelected = true` to ensure marquee restarts/continues scrolling upon state refresh.

3. **Layout Stability & Close Button**:
   - Header row contains exactly two interactive elements: `stationNameView` (left, weight 1f) and `closeButtonView` (right, fixed min touch target $\ge 48\text{dp}$).
   - Short station names that fit within the header container remain static without unnecessary motion.

### Non-Functional

- Automotive glanceability: High contrast 15sp Bold white text.
- Memory safe: Zero references leaked upon `removeOverlay()`.

## Implementation Steps

1. [x] In `FocusModeFloatingViewManager.kt`:
   - Remove `distanceBadgeView` property and its getter `testDistanceBadgeView`.
   - In `buildCapsuleView()`, do not create or add `distanceTv` to `headerRow`.
   - In `stationNameView`, remove `TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration`, configure `ellipsize = TextUtils.TruncateAt.MARQUEE`, `marqueeRepeatLimit = -1`, `isSingleLine = true`, `isSelected = true`, and `isHorizontalFadingEdgeEnabled = true`.
   - In `updateView()`, remove `distanceBadgeView` visibility/text handling, and ensure `stationNameView?.isSelected = true`.
   - In `removeOverlay()`, clean up `stationNameView = null`.
2. [x] Update any existing tests in `FocusModeFloatingWindowAndFallbackTest.kt` that asserted `testDistanceBadgeView` to align with the streamlined header layout.
3. [x] Create single comprehensive unit test `FocusModeFloatingHeaderMarqueeTest.kt` under `app/src/test/java/com/evcs/favorites/focus/`.
4. [x] Run only the single Phase 01 test:
   `./gradlew testDebugUnitTest --tests "com.evcs.favorites.focus.FocusModeFloatingHeaderMarqueeTest"`
5. [x] Stop execution for user review.

## Files to Create/Modify

- `app/src/main/java/com/evcs/favorites/focus/FocusModeFloatingViewManager.kt` - Remove distanceBadgeView, configure stationNameView marquee.
- `app/src/test/java/com/evcs/favorites/focus/FocusModeFloatingWindowAndFallbackTest.kt` - Adapt existing tests to removed distance view.
- `app/src/test/java/com/evcs/favorites/focus/FocusModeFloatingHeaderMarqueeTest.kt` - [NEW] Comprehensive test for Phase 01.

## Single Comprehensive Test

- **File**: `app/src/test/java/com/evcs/favorites/focus/FocusModeFloatingHeaderMarqueeTest.kt`
- **Command**: `./gradlew testDebugUnitTest --tests "com.evcs.favorites.focus.FocusModeFloatingHeaderMarqueeTest"`
- **Verification Criteria**:
  - Verifies `stationNameView.ellipsize == TextUtils.TruncateAt.MARQUEE`.
  - Verifies `stationNameView.marqueeRepeatLimit == -1`.
  - Verifies `stationNameView.isSelected == true` initially and remains `isSelected == true` after `updateView(state)`.
  - Verifies `distanceBadgeView` is completely absent from the header container.
  - Verifies header row contains only `stationNameView` and `closeButtonView`.
