# Phase 01: Station Name Sanitization & Multiline Full-Text Display

Status: ✅ Completed
Dependencies: None

## Objective
Eliminate estimated distance prefixes (such as `5.4km » `, `9.1km » `, `500m » `) from station names returned by the raw search API and eliminate text clipping by allowing flexible multiline station name wrapping (up to 3 lines) in `StationCard` and `StationDetailModal`.

## Requirements
### Functional
1. **Station Name Distance Prefix Stripping (`StationNameSanitizer`)**:
   - Create utility `StationNameSanitizer` in `com.evcs.favorites.util` or domain layer.
   - Detect and remove leading distance patterns such as:
     - Distance followed by guillemet: `^[\d.,]+\s*(?:km|m)\s*»\s*` (e.g., `"5.4km » VinFast - CHXD Petrolimex" -> "VinFast - CHXD Petrolimex"`).
     - Any text before guillemet if present: `^[^»\n\r]*»\s*`.
     - Distance followed by separator/space: `^[\d.,]+\s*(?:km|m)\s*(?:[-:>]\s*|\s+)` (e.g., `"5.4km - VinFast..." -> "VinFast..."`, `"500m VinFast..." -> "VinFast..."`).
     - Normal names without distance prefix (e.g., `"VinFast - Royal City"`) must remain intact without modification.
     - Null or blank names return empty string safely.
2. **Repository, Cloud Sync & URL Builder Sanitization**:
   - In `EvcsRepository.kt`:
     - In `SearchStationRaw.toDomainStation()`: sanitize `stationName` before assigning to `Station.name`.
     - In `mergeToDomainStation()`: sanitize `search.stationName?.ifBlank { fav.name } ?: fav.name` AND fallback `fav.name`.
     - In `Station.toFavoriteStationRaw()`: sanitize `name` before building `FavoriteStationRaw` payload to permanently prevent unsanitized prefixes from syncing back to EVCS cloud.
   - In `StationUrlBuilder.kt`:
     - In `buildStationDetailUrl(name, locationId, baseUrl)`: sanitize `name` via `StationNameSanitizer.sanitize(name)` before calculating `slug = slugify(...)`, ensuring canonical URL slugs for VinFast stations correctly start with `vinfast` and never break into partner URL schemes (`tram-sac-5-4km-...`).
3. **Multiline Text Wrapping in UI (`StationCard.kt` & `StationDetailModal.kt`)**:
   - In `StationCard.kt`:
     - Change station title `Row`: set `verticalAlignment = Alignment.Top` so that `StatusBadge` stays cleanly top-aligned with the first line of the title rather than awkwardly vertically centered against a 2-3 line title.
     - Change station title `Text`: set `maxLines = 3`, `overflow = TextOverflow.Ellipsis`, and ensure it fills available space properly (`modifier = Modifier.weight(1f, fill = false)`), allowing long station names (e.g., `"VinFast - Hộ kinh doanh Trịnh Thị Duyên - Gia Bình"`) to wrap across 2 to 3 lines without clipping.
   - In `StationDetailModal.kt`:
     - Change station title `Text`: set `maxLines = 3`, `overflow = TextOverflow.Ellipsis`, so full station title displays when the bottom sheet is opened.

### Non-Functional
- 100% JVM-testable without Android framework dependencies.
- Zero performance regression in mapping station lists.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/util/StationNameSanitizer.kt` - [NEW] Pure Kotlin sanitizer for distance prefixes and separators.
- `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt` - [MODIFY] Apply `StationNameSanitizer.sanitize(...)` in `toDomainStation`, `mergeToDomainStation`, and `toFavoriteStationRaw`.
- `app/src/main/java/com/evcs/favorites/util/StationUrlBuilder.kt` - [MODIFY] Sanitize name in `buildStationDetailUrl` before slug generation.
- `app/src/main/java/com/evcs/favorites/ui/components/StationCard.kt` - [MODIFY] Set `verticalAlignment = Alignment.Top`, `maxLines = 3` for station name.
- `app/src/main/java/com/evcs/favorites/ui/components/StationDetailModal.kt` - [MODIFY] Set `maxLines = 3` for station name.
- `app/src/test/java/com/evcs/favorites/StationNameDisplayAndSanitizationTest.kt` - [NEW] Comprehensive verification test.

## Test Criteria (Exactly One Test File)
- `StationNameDisplayAndSanitizationTest.kt`:
  - Verify regex sanitization across various distance prefix formats (`"5.4km » ..."`, `"9.1km » ..."`, `"500m » ..."`, `"~5.4km » ..."`, `"12,5km - ..."`, `"5.4 km : ..."`, `"500m VinFast..."`).
  - Verify station names without distance prefix (e.g., `"VinFast - Royal City"`, `"Km 12 Quốc lộ 1A"`) are strictly preserved.
  - Verify `SearchStationRaw.toDomainStation()` sanitizes station name.
  - Verify `mergeToDomainStation()` sanitizes station name in both search-matched and fallback paths.
  - Verify `Station.toFavoriteStationRaw()` persists sanitized station name for cloud sync.
  - Verify `StationUrlBuilder` creates canonical VinFast slug even if given raw name with distance prefix.
  - Verify `StationCard` and `StationDetailModal` API compatibility and multiline contract.

---
Next Phase: [Phase 02: Wattage Filter Persistence & Restoration](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260903-station-display-filter-persistence-and-tab-reorder/phase-02-wattage-filter-persistence.md)
