# Phase 04: Nearby Routing Settings Reactivity & Observation
Status: ✅ Completed
Dependencies: Phase 03

## Objective
Resolve ANDROID-LOGIC-007: Fix desynchronization between `RoutingPreferencesManager` and `NearbyViewModel` where routing settings changes made on the `NearbyScreen` (such as adding a Google Maps API Key or switching routing mode between OSRM, Google, and Auto) only notify `FavoritesViewModel`. Ensure `NearbyViewModel` observes routing settings changes and automatically re-evaluates driving metrics for its visible top 10 stations without requiring a manual GPS re-scan.

## Requirements
### Functional
- Update `NearbyViewModel`:
  - In `init`, launch a coroutine to collect `prefsManager.settings` changes:
    - Compare new settings with previous settings; when routing engine mode or API key changes:
    - If `_uiState.value.top10DisplayStations.isNotEmpty()` and user coordinates exist (`userLatitude != null && userLongitude != null`):
    - Trigger immediate re-calculation of driving metrics for the visible top 10 stations with the new settings.
  - Add public helper `updateRoutingSettings(settings: RoutingSettings): Job` that saves settings to `prefsManager` and recalculates routing.
  - Add public helper `validateGoogleApiKey(key: String): Result<Boolean>` delegating to `prefsManager.validateGoogleApiKey(key)`.
- Update `MainActivity.kt`:
  - In `NearbyScreen` composable invocation, wire `onSaveRoutingSettings` and `onValidateGoogleApiKey` directly to `nearbyViewModel`.

### Non-Functional
- Instantaneous Feedback: Switching routing engines in settings immediately refreshes traffic and ETA badges on the Nearby screen.
- Low Resource Usage: Only re-calculates routing for the existing top 10 stations; avoids triggering unnecessary GPS hardware locks.

## Implementation Steps
1. Add settings observation and re-routing logic in `app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt`.
2. Add `updateRoutingSettings` and `validateGoogleApiKey` methods to `NearbyViewModel.kt`.
3. Update routing settings callback in `app/src/main/java/com/evcs/favorites/MainActivity.kt` under `AppTab.NEARBY`.
4. Create single comprehensive test file `app/src/test/java/com/evcs/favorites/ui/viewmodel/NearbyRoutingSettingsSyncTest.kt`.
5. Run verification command:
   `./gradlew testDebugUnitTest --tests com.evcs.favorites.ui.viewmodel.NearbyRoutingSettingsSyncTest`

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt` - [MODIFY] Collect `prefsManager.settings` and add routing recalculation
- `app/src/main/java/com/evcs/favorites/MainActivity.kt` - [MODIFY] Connect `NearbyScreen` callbacks to `nearbyViewModel`
- `app/src/test/java/com/evcs/favorites/ui/viewmodel/NearbyRoutingSettingsSyncTest.kt` - [NEW] Single comprehensive test for Phase 04

## Test Criteria (Single File-Based Test)
- Run single test:
  `./gradlew testDebugUnitTest --tests com.evcs.favorites.ui.viewmodel.NearbyRoutingSettingsSyncTest`
- [x] Verifies that when `RoutingPreferencesManager` saves new routing settings, `NearbyViewModel` detects the emission.
- [x] Verifies that `NearbyViewModel` triggers routing re-evaluation for visible top 10 stations upon settings update.
- [x] Verifies that `NearbyViewModel.updateRoutingSettings` saves to `prefsManager` and updates routing metrics.
- [x] Verifies that no re-routing is dispatched if top 10 stations are empty or GPS location has not been acquired yet.

---
Next Phase: [Phase 05: Thread-Safe EncryptedSharedPreferences Singleton](phase-05-encrypted-shared-prefs-singleton.md)
