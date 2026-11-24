# Phase 03: ViewModel Orchestration, Realtime GPS Refresh & Regression Verification
Status: ✅ Completed
Dependencies: [Phase 02: Station Normalization, Coordinate Caching & Name Enrichment](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/261124-1947-evcs-403-here-api-fallback-resilience/phase-02-station-normalization-and-name-enrichment.md)

## Objective
Verify and solidify end-to-end integration across the presentation and domain layers when the application operates in fallback mode. Ensure that `NearbyViewModel` handles fallback stations transparently across all user triggers—manual pull-to-refresh (`scanNearbyStations()`), automotive realtime GPS movement (`onLocationUpdated()`), filter mode switching (DC wattage tiers and quick chips), and cloud favorites synchronization—with zero error UI flickers and 100% regression safety.

In strict adherence to Google Android Architecture best practices, the Data layer (`EvcsRepository`) owns fallback orchestration as the Single Source of Truth. The ViewModel remains decoupled from remote data source origins, consuming `repository.searchNearbyVinFast` without duplicating fallback logic or introducing cascading network retries.

## Scope
- Validate `NearbyViewModel` execution paths when `repository.searchNearbyVinFast()` transparently recovers via the secondary HERE API fallback under EVCS 403 WAF or 429 conditions.
- Confirm `NearbyUiState` lifecycle: `isSearching` transitions from true to false, `errorMessage` remains `null`, `hasSearched` is set to `true`, and `rawStations` contains qualifying domain models.
- Validate that `NearbyStationFilter` correctly filters fallback stations by DC wattage tiers (30kW, 60kW, 120kW, 150kW, 250kW) and prunes motorbike-only stations (`hasCarCompatiblePorts()`).
- Verify that `MultiTierRoutingCoordinator` dispatches road distance matrix calculations for the Top 10 nearest fallback stations.
- Verify two-way favorites toggle and Firestore synchronization on stations discovered via the fallback pipeline.
- Verify clean error state propagation to `NearbyUiState.errorMessage` when both primary and secondary data sources fail.
- Author exactly one comprehensive file-based test: `NearbyViewModelHereFallbackIntegrationTest.kt`.

## Requirements
### Functional
- [x] Seamless Discovery in Fallback Mode: When EVCS API is blocked (HTTP 403 / Cloudflare WAF / 429), calling `viewModel.scanNearbyStations()` must emit top nearest stations to `uiState.value.top10DisplayStations` without surfacing any error toast or message.
- [x] Realtime Automotive GPS Updates: When `viewModel.onLocationUpdated(newLat, newLon)` is triggered while EVCS is in cooldown, the ViewModel must update `userLatitude`/`userLongitude`, trigger discovery, and update the station list via repository fallback.
- [x] Smart Filter & Wattage Chip Compatibility: Selecting a DC tier (e.g. `DcWattageTier.TIER_60KW` or `DcWattageTier.TIER_120KW`) or quick chips must correctly filter fallback stations based on their aggregated power port ratings.
- [x] Road Distance Matrix Routing: The Top 10 fallback stations must receive driving metrics (km distance, ETA) via `MultiTierRoutingCoordinator` without crashes.
- [x] Favorites Integration: Users must be able to toggle favorite status on fallback stations, persisting to local and Firestore favorites matching canonical IDs.
- [x] Complete Failure Graceful Degradation: If both EVCS and HERE API fail, `NearbyViewModel` must cleanly set `errorMessage` on `NearbyUiState`, clear `isSearching`/`isLoading`, and not crash.

### Non-Functional
- [x] Clean Architecture Compliance: `NearbyViewModel` remains decoupled from remote telemetry providers; it does not implement duplicate network fallback or cascading retry calls.
- [x] UI State Stability: No UI stutter, loading spinner lockup, or temporary "Lỗi tải trạm sạc quanh đây" flash when transitioning to the fallback data source.
- [x] Cancellation Safety: Rapid GPS movements or filter switching while a fallback query is in flight must cancel cleanly without coroutine leaks or state corruption.

## How to Implement
1. **Verify ViewModel Orchestration with Multi-Tier Repository:**
   Ensure `NearbyViewModel` consumes `repository.searchNearbyVinFast(lat, lon)` cleanly. Because `EvcsRepository` transparently handles the fallback, `NearbyViewModel` requires no duplicate network calls or remote source branching.
2. **Harden Error Boundary & State Synchronization:**
   In `NearbyViewModel.kt`, verify that:
   - When `searchResult.isSuccess`: `rawStations` updates, `isSearching = false`, `hasSearched = true`, and `executeFilterAndRoutingPipeline` is invoked.
   - When `searchResult.isFailure`: `isSearching = false`, `errorMessage = searchResult.exceptionOrNull()?.message ?: "Lỗi tải trạm sạc quanh đây"`.
3. **Verify Filter & Route Dispatch:**
   Ensure `executeFilterAndRoutingPipeline` processes the fallback stations, applying `NearbyStationFilter.filterStations` / `filterSmartStations` and extracting Top 10 candidates.
4. **Create the Single Comprehensive Integration Test File:**
   Author `app/src/test/java/com/evcs/favorites/ui/viewmodel/NearbyViewModelHereFallbackIntegrationTest.kt` verifying:
   - Cold-start scan with EVCS returning 403 WAF -> Repository degrades to HERE -> ViewModel displays Top 10 stations without error.
   - Subsequent GPS updates during cooldown -> ViewModel updates stations and driving routes.
   - DC wattage tier filtering on fallback stations -> Filters out non-matching tiers.
   - Favorites toggle on fallback stations -> Synchronizes state.
   - Both EVCS and HERE failing -> ViewModel reflects error cleanly in `uiState.errorMessage`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt` - [VERIFY/HARDEN] Ensure robust state transitions and error boundaries during repository fallback execution.
- `app/src/test/java/com/evcs/favorites/ui/viewmodel/NearbyViewModelHereFallbackIntegrationTest.kt` - [NEW] Single comprehensive integration test verifying ViewModel discovery, filter switching, GPS updates, and favorites synchronization under EVCS 403 conditions.

## Dependencies
- `com.evcs.favorites.ui.viewmodel.NearbyViewModel`
- `com.evcs.favorites.domain.filter.NearbyStationFilter`
- `com.evcs.favorites.data.repository.EvcsRepository`
- `com.evcs.favorites.data.network.here.HereEvApiClient`

## Constraints
- Must not alter the public API contract of `NearbyViewModel`.
- Must not break existing test suites (`NearbyViewModelSmartFilterTest.kt`, `NearbyRealtimeGpsRefreshTest.kt`, `NearbyPreFilterAndInitialScanTest.kt`).
- Must not perform network or disk operations on the Main dispatcher.

## Acceptance Criteria
- [x] ViewModel discovers stations and renders Top 10 list when EVCS returns 403 WAF error.
- [x] No error message is displayed on `NearbyUiState` when fallback succeeds.
- [x] Switching between DC tiers correctly filters fallback stations.
- [x] Realtime GPS update successfully refreshes the fallback station list.
- [x] Driving metrics are calculated for Top 10 fallback stations.
- [x] Dual failure (EVCS + HERE) sets `errorMessage` and stops loading without crash.
- [x] The single comprehensive test file passes 100%.

## Exactly One Comprehensive File-Based Test
`app/src/test/java/com/evcs/favorites/ui/viewmodel/NearbyViewModelHereFallbackIntegrationTest.kt`
- Simulates EVCS 403 WAF error during initial scan, verifying fallback stations populate `top10DisplayStations` with zero error UI.
- Simulates GPS location change and verifies fresh discovery via fallback.
- Verifies DC tier filtering (e.g. 60kW, 120kW, 250kW) on fallback stations.
- Verifies favorites toggle on fallback station.
- Verifies driving metrics calculation dispatch.
- Verifies clean error handling only when both EVCS and HERE API fail.

---
Next Phase: Complete (Ready for Implementation)
