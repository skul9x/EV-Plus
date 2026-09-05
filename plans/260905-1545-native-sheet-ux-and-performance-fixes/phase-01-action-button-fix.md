# Phase 01: Primary Action Button Truncation Fix & Action Bar Layout Alignment

Status: ✅ Completed
Dependencies: None

## Objective
Eliminate text truncation on the primary navigation call-to-action button ("Chỉ đường"), which currently truncates to `"▲ ."` due to space contention from secondary buttons ("Yêu thích" and "Chia sẻ"). Rebalance row weights, padding, and layout parameters so that the primary CTA is prominently displayed with full label visibility across all device widths.

## Requirements

### Functional
- [x] Ensure "Chỉ đường" (Primary Navigation Button) has dedicated proportional weight (`weight(1.3f)` or `weight(1.2f, fill = true)`) guaranteeing its full text label never ellipses or truncates on mobile screens down to 360dp width.
- [x] Adjust secondary action buttons ("Yêu thích" / "Đã lưu" and "Chia sẻ") to fit neatly beside the primary button with compact horizontal padding (e.g. `horizontal = 10.dp`) and clean iconography.
- [x] Maintain 1-tap navigation intent firing to Google Maps via `NativeStationDetailSheetHelper.launchNavigation` or caller callback.
- [x] Maintain favorite state toggle handling with instant visual feedback (Emerald container when saved).
- [x] Maintain Android share sheet launcher triggering formatted station location text.

### Non-Functional
- [x] Material 3 styling compliance with `EmeraldPrimary` background on primary pill and `surfaceVariant` on tonal secondary buttons.
- [x] Zero horizontal overflow or layout jitter when transitioning between "Yêu thích" and "Đã lưu" text states.

## Implementation Steps
1. [x] Review [NativeStationDetailSheet.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt) Quick Action Row composable layout parameters.
2. [x] Update the `Modifier.weight` and internal content padding of the `Button` for "Chỉ đường" to prioritize primary action visibility without clipping.
3. [x] Optimize the padding and width distribution of `FilledTonalButton` for "Yêu thích" and "Chia sẻ" so all 3 actions fit comfortably in a single row without pushing text out of bounds.
4. [x] Verify helper pure formatting and intent specifications in `NativeStationDetailSheetHelper`.
5. [x] Create exactly one comprehensive file-based test: `app/src/test/java/com/evcs/favorites/ui/components/NativeStationDetailActionBarLayoutTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt` - Modify Quick Action Row buttons layout and padding.
- `app/src/test/java/com/evcs/favorites/ui/components/NativeStationDetailActionBarLayoutTest.kt` - [NEW] Single comprehensive test verifying layout constraints, action labels, intent payloads, and favorite toggle states.

## Test Criteria
- [x] Single comprehensive test `NativeStationDetailActionBarLayoutTest.kt` PASSES with 0 failures:
  - Verifies primary CTA action label and navigation intent spec formatting.
  - Verifies favorite button labels ("Yêu thích" vs "Đã lưu") and color spec resolutions.
  - Verifies share intent text formatting containing station name, address, and Google Maps link.
  - Verifies button row layout specification guarantees sufficient allocation for primary navigation.

---
Next Phase: [Phase 02: Infinite Shimmer Gating & Recomposition Performance Optimization](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260905-1545-native-sheet-ux-and-performance-fixes/phase-02-performance-shimmer-gating.md)
