# Phase 02: Wattage Filter Persistence & Restoration Across App Restarts

Status: ✅ Completed
Dependencies: Phase 01

## Objective
Persist the user's selected wattage filter chips in the "Quanh đây" (Nearby) screen across application restarts using the established `SessionStorage` architecture, automatically restoring the filter state and re-applying it to newly scanned stations.

## Requirements
### Functional
1. **Filter Preferences Layer (`NearbyFilterPreferences`)**:
   - Create `NearbyFilterPreferences` in `com.evcs.favorites.data.preferences`.
   - Backed by `SessionStorage` interface (`EncryptedSharedPrefsStorage` on Android, `InMemorySessionStorage` for unit tests).
   - Storage Key: `nearby_selected_wattages`.
   - Provide companion factory:
     `fun create(context: Context): NearbyFilterPreferences = NearbyFilterPreferences(storage = EncryptedSharedPrefsStorage(context))` matching `RoutingPreferencesManager.create(context)`.
   - Provide methods:
     - `saveSelectedWattages(wattages: Set<WattageOption>)`: Encodes enum names as comma-separated string (e.g., `"KW_250,KW_180"`) or removes the key if empty.
     - `getSelectedWattages(): Set<WattageOption>`: Reads string, splits by comma, trims tokens, and safely parses into `Set<WattageOption>` (ignoring unknown or corrupted tokens).
     - `clear()`: Removes stored preference.
     - `hasPersistedFilters(): Boolean`: Returns true if persisted key exists.
2. **NearbyViewModel Integration**:
   - Inject `filterPreferences: NearbyFilterPreferences? = null` into `NearbyViewModel` constructor (defaulting to `NearbyFilterPreferences(InMemorySessionStorage())` if null). This ensures existing tests calling constructor directly without this argument continue to compile and pass.
   - In `NearbyViewModel` initialization:
     - Load initial filter state via `filterPreferences.getSelectedWattages()`.
     - Initialize `_uiState` with `selectedWattages = initialWattages`.
   - In `toggleWattageFilter(option: WattageOption)`:
     - After updating `_uiState.selectedWattages`, call `filterPreferences.saveSelectedWattages(newSelected)`.
   - In `clearWattageFilters()`:
     - Call `filterPreferences.clear()`.
   - In `scanNearbyStations()` and `refresh()`:
     - When raw search results return, automatically pass the currently active (restored) `_uiState.value.selectedWattages` into `executeFilterAndRoutingPipeline`, ensuring stations are filtered by the restored preferences without requiring user re-tap.
   - In `provideFactory`:
     - Add `filterPreferences: NearbyFilterPreferences? = null` parameter with default value.
3. **Application Wiring in `MainActivity.kt`**:
   - In `MainActivity.kt`, declare `private val nearbyFilterPreferences by lazy { NearbyFilterPreferences.create(applicationContext) }`.
   - Pass `filterPreferences = nearbyFilterPreferences` into `NearbyViewModel.provideFactory`.

### Non-Functional
- Persistent storage operations must be non-blocking and safe against corrupted preference strings.
- 100% JVM-testable with `InMemorySessionStorage`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/preferences/NearbyFilterPreferences.kt` - [NEW] Persistent store for wattage filter selection.
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt` - [MODIFY] Accept and wire `filterPreferences` in initial state, toggle, clear, and factory.
- `app/src/main/java/com/evcs/favorites/MainActivity.kt` - [MODIFY] Provide `NearbyFilterPreferences` in `NearbyViewModel.provideFactory`.
- `app/src/test/java/com/evcs/favorites/NearbyFilterPersistenceTest.kt` - [NEW] Comprehensive verification test.

## Test Criteria (Exactly One Test File)
- `NearbyFilterPersistenceTest.kt`:
  - Verify `NearbyFilterPreferences` encodes and decodes sets of `WattageOption` correctly, including empty set, single option, multiple options, and corrupted/unknown values.
  - Verify `NearbyViewModel` initializes its `selectedWattages` from `NearbyFilterPreferences`.
  - Verify toggling wattage filter persists new set into storage.
  - Verify clearing wattage filter removes it from storage.
  - Verify cross-session restoration: creating a second `NearbyViewModel` with the same storage immediately reflects the persisted wattage selection and automatically applies it to newly scanned stations.

---
Next Phase: [Phase 03: Bottom Navigation Tab Order Swapping](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260903-station-display-filter-persistence-and-tab-reorder/phase-03-bottom-navigation-tab-reordering.md)
