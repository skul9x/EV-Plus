# Phase 02: Single-Line Smooth Marquee for Station Name & Address

Status: ✅ Completed
Dependencies: Phase 01

## Objective

Limit both the station name and detailed station address in `NativeStationDetailSheet.kt` to exactly 1 line (`maxLines = 1`, `softWrap = false`). Integrate Compose Foundation's `Modifier.basicMarquee()` with automotive-tuned timing (2000ms delay, ~35dp/s velocity, infinite iterations) so that overflowing text scrolls smoothly across the screen, preventing multi-line wrapping and preserving layout stability on car screens.

## Requirements

### Functional
1. **Station Name Single-Line Marquee**:
   - In `NativeStationDetailContent`, update `station.name` `Text` composable:
     - Set `maxLines = 1` (was 3).
     - Set `softWrap = false`.
     - Remove `overflow = TextOverflow.Ellipsis` to avoid truncating marquee content.
     - Add `Modifier.basicMarquee()` with:
       - `iterations = Int.MAX_VALUE`
       - `delayMillis = 2000` (initial pause allowing driver to read first segment)
       - `velocity = 35.dp` (smooth, distraction-free scrolling speed)
       - `spacing = MarqueeSpacing.fractionOfContainer(1f / 4f)`
2. **Station Address Single-Line Marquee**:
   - In `NativeStationDetailContent`, update `station.address` `Text` composable:
     - Set `maxLines = 1` (was 2).
     - Set `softWrap = false`.
     - Remove `overflow = TextOverflow.Ellipsis`.
     - Add `Modifier.basicMarquee()` with identical automotive configuration.
3. **Layout Stability**:
   - Ensure the station header `Column` maintains consistent height regardless of name/address length.
   - Stations with short names or short addresses remain static (Compose `basicMarquee` does not scroll when content fits container).

### Non-Functional
- Performance: 60fps smooth animation with zero frame drops on Android Automotive hardware.
- Driver safety: 2-second initial delay prevents flickering motion on screen load.

## Implementation Steps
1. [x] In `NativeStationDetailSheet.kt`, import `androidx.compose.foundation.basicMarquee`, `androidx.compose.foundation.MarqueeSpacing`, and `androidx.compose.foundation.MarqueeAnimationMode`.
2. [x] Define helper constants or extension in `NativeStationDetailSheetHelper` for marquee configuration (e.g. `MARQUEE_INITIAL_DELAY_MS = 2000`, `MARQUEE_VELOCITY_DP = 35`).
3. [x] Apply single-line marquee modifier to `station.name` and `station.address` in `NativeStationDetailSheet.kt`.
4. [x] Create single comprehensive test `StationDetailMarqueeTest.kt` under `app/src/test/java/com/evcs/favorites/ui/`.
5. [x] Execute single test:
   `./gradlew testDebugUnitTest --tests "com.evcs.favorites.ui.StationDetailMarqueeTest"`
6. [x] Stop execution for user review.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt` - Apply single-line marquee modifiers and parameters to station name and address.
- `app/src/test/java/com/evcs/favorites/ui/StationDetailMarqueeTest.kt` - [NEW] Single comprehensive test for Phase 02.

## Single Comprehensive Test
- File: `app/src/test/java/com/evcs/favorites/ui/StationDetailMarqueeTest.kt`
- Command: `./gradlew testDebugUnitTest --tests "com.evcs.favorites.ui.StationDetailMarqueeTest"`
- Verification criteria:
  - Source code contract verifies `basicMarquee` modifier presence on both `station.name` and `station.address`.
  - Verifies `maxLines = 1` and `softWrap = false` on both text fields.
  - Verifies marquee constants (iterations = Int.MAX_VALUE, delay >= 1500ms, velocity between 30dp-40dp).
  - Verifies that ellipsis truncation is eliminated in favor of marquee animation.

---
Phase Complete: Station Detail UI Streamlining & Marquee Plan Ready for Execution
