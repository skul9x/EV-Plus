# Phase 01: Remove Redundant "Cổng sạc" Label & Compact Hero Section

Status: ✅ Completed
Dependencies: None

## Objective

Eliminate the redundant `"Cổng sạc"` text header in the charging ports section of `NativeStationDetailSheet.kt`. Reclaim ~32dp of vertical screen estate so that port pills and the primary navigation action button (`⚡ DẪN ĐƯỜNG & THEO DÕI`) are positioned higher, directly accessible to drivers on landscape in-car screens without vertical scrolling.

## Requirements

### Functional
1. In `NativeStationDetailSheet.kt` (`NativeStationDetailContent`), remove the static `Text(text = "Cổng sạc", ...)` header inside the Charging Ports Section.
2. Maintain `PortStatusPill` grid layout (`FlowRow` with `Arrangement.spacedBy(8.dp)`) directly below the station header and community rating badge.
3. Preserve empty state feedback: if `uiState.portStatuses` is empty, keep `"Đang cập nhật danh sách cổng sạc..."`.
4. Ensure spacing between the station header / rating and the port pills remains visually balanced (8dp vertical spacing).

### Non-Functional
- Automotive Glanceability: Drivers can see available port counts and power ratings immediately upon selecting a station without scrolling.
- Zero regressions in existing bottom sheet contracts and lifecycle handling.

## Implementation Steps
1. [x] Inspect `NativeStationDetailSheet.kt` section 3 (Charging Ports Section).
2. [x] Remove the `Text(text = "Cổng sạc", ...)` composable call.
3. [x] Verify spacing of the enclosing `Column` / `FlowRow` to ensure compact alignment.
4. [x] Create single comprehensive test `StationDetailPortHeroStreamlineTest.kt` under `app/src/test/java/com/evcs/favorites/ui/`.
5. [x] Execute single test:
   `./gradlew testDebugUnitTest --tests "com.evcs.favorites.ui.StationDetailPortHeroStreamlineTest"`
6. [x] Stop execution for user review.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt` - Remove redundant "Cổng sạc" text and compact port hero section.
- `app/src/test/java/com/evcs/favorites/ui/StationDetailPortHeroStreamlineTest.kt` - [NEW] Single comprehensive test for Phase 01.

## Single Comprehensive Test
- File: `app/src/test/java/com/evcs/favorites/ui/StationDetailPortHeroStreamlineTest.kt`
- Command: `./gradlew testDebugUnitTest --tests "com.evcs.favorites.ui.StationDetailPortHeroStreamlineTest"`
- Verification criteria:
  - Source code contract verifies removal of static `"Cổng sạc"` title string from `NativeStationDetailSheet.kt`.
  - Port status list / FlowRow rendering contract remains intact.
  - "Đang cập nhật danh sách cổng sạc..." empty fallback text is retained.

---
Next Phase: `phase-02-single-line-marquee-station-name-and-address.md`
