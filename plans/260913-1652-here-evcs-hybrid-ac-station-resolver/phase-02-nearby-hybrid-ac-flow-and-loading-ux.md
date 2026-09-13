# Phase 02: Nearby Hybrid AC Flow Integration & Loading UX
Status: 🟢 Completed
Dependencies: Phase 01

## Objective
Integrate the Phase 01 HERE AC discovery and EVCS HTML name resolution engine into `NearbyViewModel` and the Nearby UI screens (`NearbyScreen.kt`, `NearbyLandscapeScreen.kt`). When the user selects `SmartFilterMode.AC` (or `QuickChipOption.AC`), display a blocking circular loading spinner while querying HERE and resolving authentic names for the top 10 stations, then render the resolved `StationCard` items with highlighted AC chips. Ensure non-AC filter modes retain standard repository behavior without regression.

## Requirements
### Functional
- [x] In `NearbyViewModel.kt`, detect when the active filter mode is `SmartFilterMode.AC` or `QuickChipOption.AC` in `CustomFilterMode.QUICK_CHIP`.
- [x] When AC mode is active, trigger the hybrid pipeline via `HereEvApiClient` and `EvcsStationNameResolver` instead of standard EVCS repository search.
- [x] Emit `isLoading = true` in `NearbyUiState` immediately upon switching to or refreshing AC filter mode, showing a circular progress indicator (Option B).
- [x] Wait until all candidate stations in the top 10 have their names resolved (or timed out to fallback address), then emit the enriched `List<Station>` and set `isLoading = false`.
- [x] If no AC stations are found in the 10km radius, expand search to 20km before concluding empty state.
- [x] In `NearbyScreen.kt` and `NearbyLandscapeScreen.kt`, ensure the circular loading indicator is visibly displayed while `uiState.isLoading == true`, preventing stale or half-loaded cards from flashing.
- [x] Ensure non-AC modes (`SmartFilterMode.NONE`, `SmartFilterMode.DC`, and numeric custom ranges) bypass the HERE hybrid pipeline and continue using `EvcsRepository` with zero regression.
- [x] Maintain consistent interaction contracts: Clicking "Chỉ đường" uses accurate GPS coordinates from HERE, and "Xem chi tiết" opens canonical EVCS URL using `station.id`.

### Non-Functional
- [x] StateFlow Thread Safety: Ensure coroutine cancellation and UI state updates occur cleanly without race conditions when the user rapidly toggles between filter chips.
- [x] UI Fluidity: Prevent ANR or Compose recomposition thrashing by executing all network fetches on `Dispatchers.IO` and publishing a single immutable state update on `Dispatchers.Main`.

## Implementation Steps
1. [x] Update `NearbyViewModel.kt` to inject or access `HereEvApiClient` and `EvcsStationNameResolver`.
2. [x] Add dedicated coroutine handling in `NearbyViewModel` for AC hybrid discovery with loading state orchestration.
3. [x] Verify that `NearbyScreen.kt` and `NearbyLandscapeScreen.kt` properly honor `uiState.isLoading` during AC filter activation.
4. [x] Create the single comprehensive test file `app/src/test/java/com/evcs/favorites/ui/screens/NearbyHybridAcFlowAndLoadingUiStateTest.kt` verifying:
   - Switching to `SmartFilterMode.AC` triggers loading state `isLoading = true`.
   - Completion of hybrid pipeline emits top 10 stations with authentic names and sets `isLoading = false`.
   - Switching to `SmartFilterMode.DC` or `NONE` reverts to repository flow without calling HERE client.
   - Rapid filter switching cancels obsolete AC network jobs cleanly.
5. [x] Execute the single test via Gradle to confirm verification passes.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt` - [MODIFY] Orchestrate HERE AC query, HTML name resolution, and loading state.
- `app/src/main/java/com/evcs/favorites/ui/screens/NearbyScreen.kt` - [MODIFY] Ensure loading spinner renders cleanly during AC resolution.
- `app/src/main/java/com/evcs/favorites/ui/screens/landscape/NearbyLandscapeScreen.kt` - [MODIFY] Ensure landscape layout displays loading spinner during AC resolution.
- `app/src/test/java/com/evcs/favorites/ui/screens/NearbyHybridAcFlowAndLoadingUiStateTest.kt` - [NEW] Single comprehensive unit test suite for Phase 02.

## Test Criteria
- [x] `NearbyViewModel` emits `isLoading = true` when AC filter mode is selected.
- [x] Top 10 AC stations with genuine EVCS names are published to `uiState.stations` when resolution completes.
- [x] `isLoading` flips to `false` only after all names are resolved.
- [x] Non-AC modes (`NONE`, `DC`) do not invoke the HERE client.
- [x] Canceling/switching filter aborts pending AC requests.

---
Next Phase: None (Feature Complete)
