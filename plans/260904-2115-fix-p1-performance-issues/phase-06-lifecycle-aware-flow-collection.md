# Phase 06: Lifecycle-Aware Flow Collection & Battery Conservation

Status: ✅ Completed
Issue ID: PERF-ASYNC-01
Dependencies: Phase 05

## Objective
Prevent background battery drain, unwanted network fetches, and unnecessary CPU wakeups when the app is minimized or the screen is turned off by adding `lifecycle-runtime-compose` and replacing unscoped `collectAsState()` with lifecycle-aware `collectAsStateWithLifecycle()` across `MainActivity`, `NearbyScreen`, and `DebugLogViewerCard`.

---

## Requirements

### Functional
- [x] Add `androidx.lifecycle:lifecycle-runtime-compose:2.7.0` dependency to `app/build.gradle.kts`.
- [x] In `MainActivity.kt`, migrate all StateFlow subscriptions from `collectAsState()` to `collectAsStateWithLifecycle()`:
  - `viewModel.uiState.collectAsStateWithLifecycle()`
  - `viewModel.isLoggedIn.collectAsStateWithLifecycle()`
  - `viewModel.routingSettings.collectAsStateWithLifecycle()`
  - `viewModel.selectedStationForDetail.collectAsStateWithLifecycle()`
- [x] In `NearbyScreen.kt`, migrate:
  - `viewModel.uiState.collectAsStateWithLifecycle()`
- [x] In `DebugLogViewerCard.kt`, migrate:
  - `AppDebugLogger.logsFlow.collectAsStateWithLifecycle()`
  - Note: This connects directly with Phase 02. When the settings modal is closed or the app is minimized, `logsFlow` collection pauses, bringing active subscriptions to 0 and preventing any intermediate log array allocations.
- [x] Ensure that when the host Activity drops below `Lifecycle.State.STARTED` (e.g., app moved to background), Flow collection automatically pauses.

### Non-Functional
- [x] Battery conservation: Zero redundant UI state emissions, recompositions, or background pipeline triggers while in the background.
- [x] Lifecycle safety: Instant and seamless state resumption upon returning to foreground (`Lifecycle.State.RESUMED`).

---

## Implementation Steps
1. **Update `app/build.gradle.kts`**:
   - Add `implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")`.
2. **Update UI Composables**:
   - In `MainActivity.kt`: import and use `collectAsStateWithLifecycle()`.
   - In `NearbyScreen.kt`: import and use `collectAsStateWithLifecycle()`.
   - In `DebugLogViewerCard.kt`: import and use `collectAsStateWithLifecycle()`.
3. **Verify Build & Flow Lifecycle**:
   - Ensure clean compilation and lifecycle-aware behavior.

---

## Files to Modify/Create
- [MODIFY] `app/build.gradle.kts` - Add `lifecycle-runtime-compose` dependency.
- [MODIFY] `app/src/main/java/com/evcs/favorites/MainActivity.kt` - Use `collectAsStateWithLifecycle()`.
- [MODIFY] `app/src/main/java/com/evcs/favorites/ui/screens/NearbyScreen.kt` - Use `collectAsStateWithLifecycle()`.
- [MODIFY] `app/src/main/java/com/evcs/favorites/ui/components/DebugLogViewerCard.kt` - Use `collectAsStateWithLifecycle()`.
- [NEW] `app/src/test/java/com/evcs/favorites/LifecycleAwareFlowCollectionTest.kt` - Exactly one comprehensive test for Phase 06.

---

## Test Criteria (Single Comprehensive Test)
- **Test File**: `app/src/test/java/com/evcs/favorites/LifecycleAwareFlowCollectionTest.kt`
- **Core Verifications**:
  1. `collectAsStateWithLifecycle` resolves and compiles against `androidx.lifecycle.compose`.
  2. StateFlow emissions update UI state observers when lifecycle is at or above `Lifecycle.State.STARTED`.
  3. Flow subscription cancels/suspends when lifecycle drops to `Lifecycle.State.STOPPED` / `DESTROYED`.
  4. Flow subscription cleanly resumes when lifecycle transitions back to `Lifecycle.State.STARTED`.

---

## Verification Command
```bash
./gradlew testDebugUnitTest --tests com.evcs.favorites.LifecycleAwareFlowCollectionTest
```
