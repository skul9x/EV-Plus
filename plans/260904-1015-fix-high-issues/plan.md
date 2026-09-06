# Plan: Fix 5 High Severity Logic Defects (P1)
Created: 2026-09-04 10:15:00 (GMT+7)
Status: 🟡 Pending Review

## Overview
Remediate the five High Severity issues identified in `report.md` (ANDROID-LOGIC-004 through ANDROID-LOGIC-008):
1. **ANDROID-LOGIC-004 (Station URL Duplicate Prefix & Partner Syntax)**: `StationUrlBuilder` prepends duplicate `"tram-sac-"` prefixes and uses partner `-c.ID.html` syntax for VinFast stations starting with `"Trạm sạc"`, causing 404 errors.
2. **ANDROID-LOGIC-005 (Two-Way Sync Enrichment Wiping)**: `FavoritesViewModel` resets domain stations directly from `repository.favoritesState`, wiping transient `drivingMetrics` and `forecast` data.
3. **ANDROID-LOGIC-006 (Cloud Sync Failure Inconsistency)**: `EvcsRepository.addFavoriteStation` and `removeFavoriteStation` lack state rollback when remote `apiClient.saveFavorites` fails, leaving persistent and in-memory caches out-of-sync with the backend.
4. **ANDROID-LOGIC-007 (Nearby Routing Settings Unresponsiveness)**: Changing routing preferences on `NearbyScreen` only notifies `FavoritesViewModel`, leaving `NearbyViewModel` unaware and its displayed routes stale.
5. **ANDROID-LOGIC-008 (Multiple Concurrent EncryptedSharedPreferences Instances)**: Independent `EncryptedSharedPrefsStorage` instances are constructed across four components for `"evcs_secure_session"`, leading to Keystore contention and cache desynchronization.

## Key Rules & Architectural Decisions
1. **Single Comprehensive Test Per Phase**:
   - Each phase defines exactly ONE file-based test.
   - Run ONLY that single test for verification before pausing for user review.
   - Do not create or run more than one test per phase.
2. **Deterministic URL Slug Canonicalization**:
   - Strip `"tram-sac-"` prefixes prior to checking for `"vinfast"` in `StationUrlBuilder.buildStationDetailUrl`.
   - Update `StationNameSanitizer.sanitize` to strip common Vietnamese prefixes (`"Trạm sạc"`, `"Trạm sạc xe điện"`, `"Trụ sạc"`).
3. **Favorites Two-Way Sync Metric Preservation**:
   - Merge incoming repository stations with existing UI stations, preserving transient `drivingMetrics` and `forecast` objects for retained station IDs.
   - Use atomic `_uiState.update { ... }` transformations.
4. **Transactional Cloud Sync State Rollback**:
   - In `EvcsRepository`, capture pre-operation state snapshots and roll back `_favoritesState`, `_favoriteIdsState`, and cached storage if remote network synchronization fails.
5. **Reactive Routing Settings in NearbyViewModel**:
   - Observe `RoutingPreferencesManager.settings` in `NearbyViewModel` and re-evaluate routing on displayed stations when settings change.
6. **Thread-Safe EncryptedSharedPreferences Singleton**:
   - Enforce a thread-safe singleton pattern with double-checked locking for `EncryptedSharedPrefsStorage` using `applicationContext`.

## Phases

| Phase | Name | Issue ID | Status | Test File |
|-------|------|----------|--------|-----------|
| 01 | Station URL Slug Canonicalization & Prefix Handling | ANDROID-LOGIC-004 | ⬜ Pending | `StationUrlCanonicalizationSafetyTest.kt` |
| 02 | Favorites Two-Way Sync Enrichment Preservation | ANDROID-LOGIC-005 | ⬜ Pending | `FavoritesTwoWaySyncEnrichmentPreservationTest.kt` |
| 03 | Cloud Sync Failure Rollback & Consistency Safety | ANDROID-LOGIC-006 | ⬜ Pending | `CloudSyncRollbackSafetyTest.kt` |
| 04 | Nearby Routing Settings Reactivity & Observation | ANDROID-LOGIC-007 | ⬜ Pending | `NearbyRoutingSettingsSyncTest.kt` |
| 05 | Thread-Safe EncryptedSharedPreferences Singleton | ANDROID-LOGIC-008 | ⬜ Pending | `EncryptedSharedPrefsSingletonTest.kt` |

## Quick Commands
- Verify Phase 01: `./gradlew testDebugUnitTest --tests com.evcs.favorites.util.StationUrlCanonicalizationSafetyTest`
- Verify Phase 02: `./gradlew testDebugUnitTest --tests com.evcs.favorites.ui.viewmodel.FavoritesTwoWaySyncEnrichmentPreservationTest`
- Verify Phase 03: `./gradlew testDebugUnitTest --tests com.evcs.favorites.data.repository.CloudSyncRollbackSafetyTest`
- Verify Phase 04: `./gradlew testDebugUnitTest --tests com.evcs.favorites.ui.viewmodel.NearbyRoutingSettingsSyncTest`
- Verify Phase 05: `./gradlew testDebugUnitTest --tests com.evcs.favorites.data.auth.EncryptedSharedPrefsSingletonTest`
