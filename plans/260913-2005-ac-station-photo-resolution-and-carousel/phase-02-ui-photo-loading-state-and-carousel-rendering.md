# Phase 02: UI Photo Loading State & Carousel Rendering

Status: ✅ Completed
Dependencies: Phase 01

## Objective
Ensure unified UI photo rendering, smooth loading placeholder states, and identical interactive carousel / lightbox behaviors for AC stations across both Portrait (`NativeStationDetailSheet`) and Landscape (`NativeStationDetailContent`) layouts.

## Requirements
### Functional
- Position: AC station photos must be displayed in the exact same location as DC stations: inside `StationPhotoCarousel` at the bottom of `NativeStationDetailContent`.
- Loading State: While photos are resolving or being fetched, render a stable neutral placeholder framing (MaterialTheme surfaceVariant) so the sheet layout remains stable without abrupt height jumps.
- Carousel Rendering: Once `station.images` is populated, render photos in 16:9 aspect ratio with rounded corners (16.dp), horizontal paging via `HorizontalPager`, dot indicators (for 2..5 photos), and pill page badge (`x/y`).
- Full-Screen Lightbox: Tapping any image in the carousel launches `StationPhotoViewerModal` with high-quality full-screen display, ARGB_8888 color, pinch-to-zoom (1x..4x), and drag/pan navigation.
- Layout Synchronization: Maintain identical behavior in Portrait bottom sheet (`NativeStationDetailSheet`) and Landscape automotive right pane (`FavoritesLandscapeScreen`, `NearbyLandscapeScreen`).

### Non-Functional
- Performance: Coil RGB_565 decoding enabled in Carousel to protect JVM heap on automotive head units (Android Box / TBox) while preserving full-resolution rendering in lightbox.
- Glanceability: Clean typography and high contrast badges for automotive viewing.

## Implementation Steps
1. Enhance `NativeStationDetailSheetHelper` to define photo loading / placeholder specifications for resolving stations.
2. Update `NativeStationDetailContent` to display a smooth placeholder frame when photo resolution is in progress for stations with empty initial images.
3. Verify `StationPhotoCarousel` and `StationPhotoViewerModal` integration renders seamlessly when `station.images` transitions from empty to populated.
4. Ensure landscape screens (`NearbyLandscapeScreen` and `FavoritesLandscapeScreen`) reflect photo updates reactively from `stationDetailState`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt` - Add photo placeholder spec and verify carousel integration.
- `app/src/test/java/com/evcs/favorites/ui/components/AcStationPhotoCarouselUiTest.kt` - Comprehensive file-based unit test for Phase 02.

## Test Criteria
- Exactly one comprehensive test file: `app/src/test/java/com/evcs/favorites/ui/components/AcStationPhotoCarouselUiTest.kt`.
- Tests verify:
  1. Helper rules correctly determine carousel visibility and placeholder states for AC stations with empty vs populated photos.
  2. Indicator string formatting (`NativeStationDetailSheetHelper.formatCarouselIndicator`) produces exact index and count badges.
  3. Lightbox modal activation contracts match identically between DC and AC stations.
  4. Photo layout specifications remain stable across portrait and landscape configurations.

---
Done with all phases.
