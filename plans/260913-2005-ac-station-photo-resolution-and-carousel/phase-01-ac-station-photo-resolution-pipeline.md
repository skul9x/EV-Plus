# Phase 01: AC Station Photo Resolution Pipeline

Status: ✅ Completed
Dependencies: None

## Objective
Implement on-demand station photo resolution for AC charging stations during station detail loading in `StationDetailCoordinator`, matching against the VinFast search API or EVCS HTML detail page, decoding CDN CloudFront URLs, and updating `StationDetailUiState`.

## Requirements
### Functional
- When a station with `images.isEmpty()` (e.g., candidate AC stations from HERE EV API) is selected for detail inspection, asynchronously resolve authentic station photos.
- Matching strategy:
  1. Match by `locationId` (or sanitized station slug) against cached or queried VinFast search results.
  2. Fallback to GPS coordinate proximity matching (<= 100 meters) against VinFast search results.
  3. Secondary fallback: Extract media tokens from EVCS HTML detail page (`https://evcs.vn/tram-sac-vinfast-{id}.html`).
- Decode all extracted media items using `VinFastCdnUrlDecoder.decodeList(...)` to direct `https://cpo-prod-s3.vinfastauto.com/...` URLs.
- Retain all returned photos in the exact server-provided order without kW tier filtering.
- Update `_stationDetailState` with the enriched station model (`station.copy(images = resolvedImages, image = resolvedImages.firstOrNull())`).

### Non-Functional & Resilience
- Performance: Photo resolution runs concurrently in background IO Dispatcher during Stage 1 / Stage 2 telemetry fetching without delaying instant bottom sheet opening (0ms).
- Graceful degradation: If network fails, times out, or no media is available, retain existing station state without throwing exceptions or blocking telemetry display.

## Implementation Steps
1. Add photo resolution logic in `EvcsStationNameResolver` or `EvcsApiClient` to fetch/extract `media` tokens by ID or coordinates, returning decoded CDN URLs.
2. Integrate photo resolution into `StationDetailCoordinator.loadStationDetails`:
   - When `station.images.isEmpty()`, invoke the photo resolver.
   - If non-empty photos are resolved, atomically update `_stationDetailState` with enriched `station`.
3. Ensure in-memory caching of resolved photos per `locationId` / coordinate to prevent duplicate network calls during repeated opens.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/focus/EvcsStationNameResolver.kt` - Add or expose photo resolution support.
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/StationDetailCoordinator.kt` - Integrate on-demand photo enrichment into station detail lifecycle.
- `app/src/test/java/com/evcs/favorites/data/AcStationPhotoResolutionPipelineTest.kt` - Comprehensive file-based unit test for Phase 01.

## Test Criteria
- Exactly one comprehensive test file: `app/src/test/java/com/evcs/favorites/data/AcStationPhotoResolutionPipelineTest.kt`.
- Tests verify:
  1. AC station with `images = emptyList()` successfully resolves and decodes photos when matching station exists.
  2. Resolved photos preserve original server ordering.
  3. `StationDetailCoordinator` emits state update with updated `station.images`.
  4. Network failure or empty server response degrades gracefully without crash or blocking telemetry.

---
Next Phase: [phase-02-ui-photo-loading-state-and-carousel-rendering.md](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260913-2005-ac-station-photo-resolution-and-carousel/phase-02-ui-photo-loading-state-and-carousel-rendering.md)
