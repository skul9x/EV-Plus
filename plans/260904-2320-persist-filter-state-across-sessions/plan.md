# Plan: Persist Smart Filter State Across App Sessions and Cold Starts

Created: 2026-09-04 23:20:00 (GMT+7)  
Status: 🟡 Ready for Execution  

## Overview

In the current EV-Plus application, whenever a user exits the app and returns, all selected charging station filters (`AC`, `DC` power tiers, and `Custom` filter configurations) are lost and reset to unfiltered defaults.

Code inspection reveals that the underlying persistence layer ([SmartFilterPreferences](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/data/preferences/SmartFilterPreferences.kt)) and the cold-start state loading logic in [NearbyViewModel](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt) are already implemented. However, [MainActivity.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/MainActivity.kt) fails to instantiate and inject `SmartFilterPreferences` into `NearbyViewModel.provideFactory()`. Consequently, the ViewModel defaults to an ephemeral `InMemorySessionStorage()` in RAM, discarding all filter states on process termination.

This plan resolves the missing injection in `MainActivity`, synchronizes filter state lifecycle between `NearbyViewModel` and `SmartFilterPreferences`, cleans up legacy wattage chip storage collisions, and guarantees robust cold-start state restoration across app exit/relaunch cycles.

---

## Key Principles & Execution Rules

1. **Strict Single Comprehensive Test Rule Per Phase**:
   - Each phase contains **exactly one** comprehensive file-based test verifying the core functionality of that phase.
   - Do NOT create or run more than one test per phase.
   - After completing each phase, run only that single test for verification (`./gradlew testDebugUnitTest --tests com.evcs.favorites.<TestFile>`), then stop for user review.

2. **English Phase Documentation**:
   - All phase files are written in English in `.md` format.
   - Each phase defines crisp objectives, functional and non-functional requirements, detailed implementation steps, production files to create/modify, and a single test verification command.

3. **Clean Architecture, Defensive Sanitization & Data Safety**:
   - Keep storage access abstracted through `SmartFilterPreferences` backed by `EncryptedSharedPrefsStorage` (and `InMemorySessionStorage` for JVM unit tests).
   - Defensive sanitization: Gracefully sanitize incomplete states on cold start (e.g. `DC` without tier or `CUSTOM` without config resets to `SmartFilterMode.NONE` to avoid UI locking).
   - Purge legacy conflicts: Ensure activating or clearing any smart filter purges legacy `selectedWattages` and `filterPrefs` so old chip states never resurrect unexpectedly.

4. **No Regressions**:
   - Existing GPS scanning, Haversine/OSRM/Google road distance routing, station card display, and cloud favorites sync must remain fully operational.

---

## Phases Overview

| Phase | Name | Production Scope | Single Comprehensive Test File | Status |
|---|---|---|---|---|
| 01 | Storage Wiring & ViewModel Filter Synchronization | `MainActivity.kt`, `NearbyViewModel.kt` | `SmartFilterDependencyWiringAndSyncTest.kt` | ⬜ Pending |
| 02 | Cold Start Restoration & Cross-Session Pipeline | `NearbyViewModel.kt` (modify), `NearbyScreen.kt` (verify-only) | `SmartFilterColdStartRestorationPipelineTest.kt` | ⬜ Pending |

---

## Verification Commands

- **Phase 01**:
  ```bash
  ./gradlew testDebugUnitTest --tests com.evcs.favorites.ui.viewmodel.SmartFilterDependencyWiringAndSyncTest
  ```

- **Phase 02**:
  ```bash
  ./gradlew testDebugUnitTest --tests com.evcs.favorites.ui.viewmodel.SmartFilterColdStartRestorationPipelineTest
  ```
