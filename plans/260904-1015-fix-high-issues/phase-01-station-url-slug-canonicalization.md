# Phase 01: Station URL Slug Canonicalization & Prefix Handling
Status: ✅ Completed
Dependencies: None

## Objective
Resolve ANDROID-LOGIC-004: Fix URL construction defect in `StationUrlBuilder` where stations prefixed with "Trạm sạc" (e.g. "Trạm sạc VinFast Mega Mall Smart City") generate duplicate prefixes (`tram-sac-tram-sac-...`) and falsely branch to partner syntax (`-c.ID.html`), causing HTTP 404 errors on evcs.vn. Ensure canonical slugification and single-prefix URL generation for all VinFast and partner station variants.

## Requirements
### Functional
- Update `StationUrlBuilder.buildStationDetailUrl`:
  - Normalize generated slug by stripping leading `"tram-sac-"` prefixes:
    `val normalizedSlug = slug.removePrefix("tram-sac-")`
  - Properly detect VinFast stations whether the name begins directly with "VinFast", "Trạm sạc VinFast", "Trạm sạc xe điện VinFast", or "Trụ sạc VinFast":
    `if (normalizedSlug.startsWith("vinfast"))`
  - For VinFast stations: format canonical URL as:
    `"$baseUrl/tram-sac-$normalizedSlug-${cleanLocId.lowercase()}.html"`
  - For partner stations: format canonical URL without duplicate `"tram-sac-"`:
    `"$baseUrl/tram-sac-$normalizedSlug-c.$encodedId.html"`
- Update `StationNameSanitizer.sanitize`:
  - Strip common Vietnamese charging station prefixes (`"Trạm sạc"`, `"Trạm sạc xe điện"`, `"Trụ sạc"`) in addition to distance/guillemet prefixes.

### Non-Functional
- Backwards Compatibility: Existing URLs that already start with "VinFast" or contain distance prefixes continue to produce canonical URLs.
- Zero Network Latency: Pure string formatting utility with no remote requests.

## Implementation Steps
1. Update `StationNameSanitizer.kt` in `app/src/main/java/com/evcs/favorites/util/StationNameSanitizer.kt` to strip station prefixes.
2. Update `StationUrlBuilder.kt` in `app/src/main/java/com/evcs/favorites/util/StationUrlBuilder.kt` to normalize slugs and avoid duplicate `"tram-sac-"` prefixes.
3. Create single comprehensive test file `app/src/test/java/com/evcs/favorites/util/StationUrlCanonicalizationSafetyTest.kt`.
4. Run verification command:
   `./gradlew testDebugUnitTest --tests com.evcs.favorites.util.StationUrlCanonicalizationSafetyTest`

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/util/StationUrlBuilder.kt` - [MODIFY] Strip `"tram-sac-"` and fix slug scheme branching
- `app/src/main/java/com/evcs/favorites/util/StationNameSanitizer.kt` - [MODIFY] Strip Vietnamese station prefixes
- `app/src/test/java/com/evcs/favorites/util/StationUrlCanonicalizationSafetyTest.kt` - [NEW] Single comprehensive test for Phase 01

## Test Criteria (Single File-Based Test)
- Run single test:
  `./gradlew testDebugUnitTest --tests com.evcs.favorites.util.StationUrlCanonicalizationSafetyTest`
- [x] Verifies VinFast station named `"Trạm sạc VinFast Mega Mall Smart City"` generates canonical URL `https://evcs.vn/tram-sac-vinfast-mega-mall-smart-city-c.hn005.html` without duplicate `tram-sac-tram-sac-`.
- [x] Verifies VinFast station named `"Trạm sạc xe điện VinFast Times City"` generates canonical VinFast URL scheme ending in `-${id.lowercase()}.html`.
- [x] Verifies partner station named `"Trạm sạc EV One"` generates canonical partner URL `https://evcs.vn/tram-sac-ev-one-c.PARTNER01.html` without duplicate `tram-sac-tram-sac-`.
- [x] Verifies raw unsanitized distance prefixes (e.g. `"5.4km » Trạm sạc VinFast Ocean Park"`) generate canonical URLs.
- [x] Verifies empty/blank and special character inputs maintain graceful fallback behavior.

---
Next Phase: [Phase 02: Favorites Two-Way Sync Enrichment Preservation](phase-02-favorites-sync-metric-preservation.md)
