# Phase 01: Storage Wiring & ViewModel Filter Synchronization

Status: ✅ Completed  
Dependencies: None  

## Objective

Connect production `SmartFilterPreferences` into `MainActivity`'s dependency injection graph, wire it to `NearbyViewModel.provideFactory()`, and ensure bidirectional state synchronization and clean reset handling between `SmartFilterPreferences` and legacy `NearbyFilterPreferences`.

---

## Background & Problem Statement

1. `NearbyViewModel` already accepts `smartFilterPreferences: SmartFilterPreferences?` in its constructor and companion factory. However:
1. `MainActivity.kt` declares only `nearbyFilterPreferences` and never passes `smartFilterPreferences` to `provideFactory`.
2. As a result, `NearbyViewModel` falls back to `SmartFilterPreferences(storage = InMemorySessionStorage())`, writing all filter updates exclusively to RAM.
3. When `clearSmartFilter()` is called, it resets `SmartFilterPreferences` but omits clearing `filterPrefs` (`NearbyFilterPreferences`), risking latent conflicts if legacy wattage chips had been persisted.
4. If a prior session left incomplete state (e.g. `DC` mode entered without selecting a tier, or corrupted `CUSTOM` config), loading this directly causes UI anomalies: the top-level DC button renders active (green) without a cancel [✕] button and without the sub-filter open.
5. If legacy wattage preferences remain in storage, toggling a smart filter off (returning to `NONE`) causes `selectedWattages.isNotEmpty()` to suddenly reactivate old wattage chips even though no chips are visible in the modern UI.

---

## Requirements

### Functional Requirements
- [x] In `MainActivity.kt`:
  - Declare `private val smartFilterPreferences by lazy { SmartFilterPreferences.create(applicationContext) }`.
  - Pass `smartFilterPreferences = smartFilterPreferences` into `NearbyViewModel.provideFactory(...)`.
- [x] In `NearbyViewModel.kt`:
  - **Defensive Cold-Start Sanitization**:
    - During initialization, sanitize incomplete or corrupted smart filter states:
      - If `initialSmartMode == SmartFilterMode.DC && initialDcTier == null`, sanitize `activeFilterMode` to `SmartFilterMode.NONE` and `isDcSubFilterVisible = false`.
      - If `initialSmartMode == SmartFilterMode.CUSTOM && initialCustomConfig == null`, sanitize `activeFilterMode` to `SmartFilterMode.NONE`.
  - **Legacy Filter Purge on Activation**:
    - When activating smart filters (`toggleAcFilter`, `selectDcTier`, `saveAndApplyCustomFilter`), atomically reset `_uiState.selectedWattages` to `emptySet()` and purge `filterPrefs.clear()`.
  - **Comprehensive Smart Filter Clear**:
    - In `clearSmartFilter()`:
      - Atomically reset `activeFilterMode = SmartFilterMode.NONE`, `selectedDcTier = null`, `isDcSubFilterVisible = false`, and `selectedWattages = emptySet()`.
      - Persist `smartFilterPrefs.saveActiveFilterMode(SmartFilterMode.NONE)`.
      - Persist `smartFilterPrefs.saveSelectedDcTier(null)`.
      - Purge legacy preferences via `filterPrefs.clear()`.
  - **Initialization Precedence**:
    - If sanitized smart mode != `SmartFilterMode.NONE`, ensure `_uiState` initializes with `selectedWattages = emptySet()` and calls `filterPrefs.clear()`.
- [x] In `SmartFilterPreferences.kt`:
  - Verify null-safety and defensive error-handling when loading preferences from storage.

### Non-Functional Requirements
- [x] Clean Architecture: ViewModel depends on `SmartFilterPreferences` abstraction, not raw Android Context or filesystem.
- [x] Thread Safety: Preference writes remain non-blocking on the UI thread.
- [x] Testability: Fully verifiable using JVM unit tests with `InMemorySessionStorage`.

---

## Implementation Steps

1. **Modify `MainActivity.kt`**:
   - Add `import com.evcs.favorites.data.preferences.SmartFilterPreferences`.
   - Add lazy initialization: `private val smartFilterPreferences by lazy { SmartFilterPreferences.create(applicationContext) }`.
   - Pass `smartFilterPreferences = smartFilterPreferences` in `NearbyViewModel.provideFactory`.

2. **Modify `NearbyViewModel.kt`**:
   - Apply defensive cold-start sanitization for `initialSmartMode` (DC without tier -> NONE; CUSTOM without config -> NONE).
   - In `clearSmartFilter()`, call `filterPrefs.clear()` and reset `_uiState.selectedWattages = emptySet()`.
   - In `toggleAcFilter()`, `selectDcTier()`, and `saveAndApplyCustomFilter()`, clear `filterPrefs.clear()` and ensure `selectedWattages = emptySet()`.
   - In the initial `_uiState` construction, ensure `selectedWattages` is `emptySet()` whenever sanitized smart mode != `SmartFilterMode.NONE`.

3. **Create Single Comprehensive Test File**:
   - Create `app/src/test/java/com/evcs/favorites/ui/viewmodel/SmartFilterDependencyWiringAndSyncTest.kt` verifying:
     - `provideFactory` correctly assigns and uses the supplied `SmartFilterPreferences`.
     - Mode changes (`AC`, `DC`, `CUSTOM`) persist directly to the underlying `SessionStorage`.
     - Defensive sanitization: persisted `DC` without tier or `CUSTOM` without config resets cleanly to `SmartFilterMode.NONE`.
     - `clearSmartFilter()` clears active smart mode, selected DC tier, and legacy wattage preferences simultaneously.
     - Legacy purge: activating smart filters purges legacy `selectedWattages` and calls `filterPrefs.clear()`.
     - Precedence rule: active smart filter takes priority and suppresses legacy wattage chip overrides.

---

## Files to Create / Modify

- `app/src/main/java/com/evcs/favorites/MainActivity.kt` - [MODIFY] Instantiate and inject `SmartFilterPreferences`.
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt` - [MODIFY] Synchronize filter reset and enforce precedence.
- `app/src/test/java/com/evcs/favorites/ui/viewmodel/SmartFilterDependencyWiringAndSyncTest.kt` - [NEW] Single comprehensive test file.

---

## Test Verification

Run only this single test command after implementing Phase 01:
```bash
./gradlew testDebugUnitTest --tests com.evcs.favorites.ui.viewmodel.SmartFilterDependencyWiringAndSyncTest
```

---

Next Phase: [Phase 02: Cold Start Restoration & Cross-Session Lifecycle](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260904-2320-persist-filter-state-across-sessions/phase-02-cold-start-restoration-and-cross-session-lifecycle.md)
