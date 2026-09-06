# Phase 02: Cold Start Restoration & Cross-Session Lifecycle

Status: ✅ Completed  
Dependencies: [Phase 01: Storage Wiring & ViewModel Filter Synchronization](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260904-2320-persist-filter-state-across-sessions/phase-01-storage-wiring-and-viewmodel-filter-synchronization.md)  

## Objective

Validate and guarantee the end-to-end cold-start lifecycle across application process restarts. Ensure that upon cold launch, `NearbyViewModel` automatically restores the user's active filter mode, selected DC tier, sub-filter visibility, and custom configuration, and immediately filters newly scanned stations through the routing pipeline without requiring re-selection.

---

## Requirements

### Functional Requirements
- [x] **Cold Start AC Mode Restoration**:
  - Previous session saves `SmartFilterMode.AC`.
  - On app relaunch, a new `NearbyViewModel` loads `activeFilterMode = SmartFilterMode.AC`, `isDcSubFilterVisible = false`, `selectedDcTier = null`.
  - When stations are retrieved (`scanNearbyStations()` / `refresh()`), pipeline immediately filters for AC ports.
  - Dynamic pill displays: `"Tìm thấy X trạm có cổng AC khả dụng"`.
- [x] **Cold Start DC Tier Mode Restoration**:
  - Previous session saves `SmartFilterMode.DC` and `selectedDcTier` (e.g. `GE_60KW`).
  - On app relaunch, a new `NearbyViewModel` loads `activeFilterMode = SmartFilterMode.DC`, `selectedDcTier = DcWattageTier.GE_60KW`, and restores `isDcSubFilterVisible = true`.
  - Pipeline filters for DC ports matching `GE_60KW`.
  - Dynamic pill displays: `"Tìm thấy X trạm có cổng DC ≥ 60kW khả dụng"`.
- [x] **Cold Start Custom Filter Mode Restoration**:
  - Previous session saves `SmartFilterMode.CUSTOM` and a valid `CustomFilterConfig`.
  - On app relaunch, a new `NearbyViewModel` loads `activeFilterMode = SmartFilterMode.CUSTOM` and `savedCustomConfig = <config>`.
  - Pipeline filters using the custom criteria.
  - Dynamic pill displays: `"Tìm thấy X trạm theo bộ lọc tùy chỉnh"`.
- [x] **Cold Start Unfiltered (NONE) Mode**:
  - When no filter is active, pipeline retains all valid stations with available plugs.
  - Dynamic pill displays: `"Top 10 trạm sạc VinFast gần nhất còn cổng trống"`.
- [x] **Defensive Incomplete & Corrupted State Sanitization**:
  - If previous session has persisted `SmartFilterMode.DC` with `selectedDcTier == null` (e.g. user force-closed app while viewing sub-filter bar before choosing a tier), cold launch gracefully sanitizes to `SmartFilterMode.NONE` and `isDcSubFilterVisible = false`.
  - If previous session has persisted `SmartFilterMode.CUSTOM` with `savedCustomConfig == null` or corrupted JSON, cold launch gracefully sanitizes to `SmartFilterMode.NONE`.
  - If persisted preference strings or JSON payloads are corrupted or malformed, the system gracefully falls back to `SmartFilterMode.NONE` and `null` without crashing.
- [x] **Legacy Filter Suppression Across Cold Start**:
  - If legacy `selectedWattages` existed in preferences from older versions, cold launch with an active smart filter suppresses and purges legacy wattage preferences so old chips never take precedence.

### Non-Functional Requirements
- [x] Deterministic pipeline execution in coroutines test environments.
- [x] Zero UI freezes or state flickering during cold start.

---

## Implementation Steps

1. **Verify ViewModel Pipeline Execution on Restored State**:
   - Verify that `NearbyViewModel.scanNearbyStations()` and `refresh()` strictly execute `executeFilterAndRoutingPipeline()` using the restored `_uiState.value.activeFilterMode`, `_uiState.value.selectedDcTier`, and `_uiState.value.savedCustomConfig`.
   - Ensure `isDcSubFilterVisible` correctly reflects `sanitizedSmartMode == SmartFilterMode.DC && initialDcTier != null` on launch.
   - Confirm `NearbyScreen.kt` requires no UI changes as Compose reactivity via `collectAsStateWithLifecycle()` seamlessly binds all restored states.

2. **Create Single Comprehensive Test File**:
   - Create `app/src/test/java/com/evcs/favorites/ui/viewmodel/SmartFilterColdStartRestorationPipelineTest.kt` verifying:
     - End-to-end multi-session cold start simulation:
       1. Session 1: User scans stations, activates AC filter. Process terminates.
       2. Session 2: Fresh ViewModel instance shares the same storage. Cold-start state initializes with AC mode. User triggers scan -> only AC stations populate Top 10 with matching summary pill.
       3. Session 2: User switches to DC mode with `GE_60KW`. Process terminates.
       4. Session 3: Fresh ViewModel reloads DC mode, `GE_60KW` tier, and `isDcSubFilterVisible = true`. User scans -> Top 10 contains strictly DC >= 60kW stations.
       5. Session 3: User configures custom filter (e.g. `minKw = 150`). Process terminates.
       6. Session 4: Fresh ViewModel reloads Custom mode with exact configuration. User scans -> only matching stations returned.
       7. Session 4: User clears filter. Process terminates.
       8. Session 5: Fresh ViewModel reloads `SmartFilterMode.NONE`. User scans -> all stations returned.
       9. Session 6 (Defensive Incomplete DC): Storage has mode=DC but tier=null -> Fresh ViewModel sanitizes to `SmartFilterMode.NONE`, `isDcSubFilterVisible = false`.
       10. Session 7 (Defensive Corrupted Custom): Storage has mode=CUSTOM but corrupted JSON -> Fresh ViewModel sanitizes to `SmartFilterMode.NONE`.
       11. Session 8 (Legacy Purge): Storage contains legacy wattage chips + smart filter -> Fresh ViewModel initializes smart filter and flushes legacy wattage preferences.

---

## Files to Create / Modify

- `app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt` - [VERIFY/MODIFY] Verify pipeline cold-start alignment and sanitization.
- `app/src/main/java/com/evcs/favorites/ui/screens/NearbyScreen.kt` - [VERIFY-ONLY] Verify Compose state collection reactivity.
- `app/src/test/java/com/evcs/favorites/ui/viewmodel/SmartFilterColdStartRestorationPipelineTest.kt` - [NEW] Single comprehensive end-to-end cold-start lifecycle test.

---

## Test Verification

Run only this single test command after implementing Phase 02:
```bash
./gradlew testDebugUnitTest --tests com.evcs.favorites.ui.viewmodel.SmartFilterColdStartRestorationPipelineTest
```
