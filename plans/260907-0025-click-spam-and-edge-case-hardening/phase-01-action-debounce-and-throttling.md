# Phase 01: Action Debounce & Throttling Engine

Status: ⬜ Pending
Dependencies: None

## Objective
Introduce robust, reusable debounce and action-throttling mechanics across all primary interactive user entry points to prevent multi-intent spawning, duplicate foreground service activations, repeated navigation launches, and concurrent OTP submissions.

## Requirements
### Functional
- [ ] Create generic `DebounceHelper` supporting time-based debouncing (1000ms cooldown) and in-flight action locking with injectable clock for deterministic testing.
- [ ] Throttle "Chỉ đường" (Navigation 1-Tap) clicks in `MapNavigator`, `StationCard`, and `NativeStationDetailSheet` so repeated rapid clicks (> 1 click per 1000ms) only dispatch a single `Intent` to Google Maps.
- [ ] Debounce "⚡ Focus Mode" button clicks in `NativeStationDetailSheet` to prevent launching duplicate foreground service start intents and duplicate navigations.
- [ ] Debounce 1-Tap "Reroute" button clicks in `FocusModeFloatingViewManager` to prevent dispatching duplicate reroute actions to Google Maps.
- [ ] Prevent race condition between auto-submit on the 6th OTP digit and manual click on "Xác thực OTP" button in `LoginScreen` and `FavoritesViewModel.verifyOtp`.
- [ ] Disable Google Sign-In button in `FavoritesProfileHeader` during active authentication to prevent concurrent `credentialManager.getCredential()` invocations.

### Non-Functional
- [ ] Reusable, decoupled `DebounceHelper` with zero frame-rate or UI rendering overhead.
- [ ] Thread-safe state tracking for concurrent coroutines and UI interaction threads.

## Implementation Steps
1. [ ] Create `app/src/main/java/com/evcs/favorites/util/DebounceHelper.kt`:
   - Implement `DebounceHelper` class with configurable `intervalMs: Long = 1000L` and injectable `clock: () -> Long = { SystemClock.elapsedRealtime() }`.
   - Provide `runIfAllowed(action: () -> Unit): Boolean` to execute actions atomically only if the cooldown has elapsed.
   - Provide `withInFlightLock(block: suspend () -> T): T?` to ignore duplicate invocations while an asynchronous action is in progress.
2. [ ] Modify `app/src/main/java/com/evcs/favorites/navigation/MapNavigator.kt`:
   - Integrate `DebounceHelper` into `MapNavigator.navigate()` to drop rapid repeated intent dispatches within 1000ms.
3. [ ] Modify `app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt`:
   - Add debouncing to `onNavClick` and `onFocusClick` handlers before intent dispatch.
4. [ ] Modify `app/src/main/java/com/evcs/favorites/focus/FocusModeFloatingViewManager.kt`:
   - Debounce `rerouteBtn.setOnClickListener` to prevent multiple rapid reroutes.
5. [ ] Modify `app/src/main/java/com/evcs/favorites/ui/screens/LoginScreen.kt` & `FavoritesViewModel.kt`:
   - Add `isVerifyingOtp` guard in `FavoritesViewModel.verifyOtp` to discard duplicate concurrent calls.
   - Disable OTP button in `LoginScreen` when `uiState is FavoritesUiState.VerifyingOtp` and ignore auto-submit if already verifying.
6. [ ] Modify `app/src/main/java/com/evcs/favorites/ui/components/FavoritesProfileHeader.kt`:
   - Add `isSigningIn: Boolean` flag to disable Google Sign-In button during in-flight authentication.
7. [ ] Create single comprehensive test file:
   - `app/src/test/java/com/evcs/favorites/hardening/ActionDebounceAndThrottlingTest.kt`

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/util/DebounceHelper.kt` [NEW] - Generic action debouncer and in-flight lock helper.
- `app/src/main/java/com/evcs/favorites/navigation/MapNavigator.kt` [MODIFY] - Add throttling to navigation intent launches.
- `app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt` [MODIFY] - Debounce navigation and Focus Mode activation clicks.
- `app/src/main/java/com/evcs/favorites/focus/FocusModeFloatingViewManager.kt` [MODIFY] - Debounce 1-Tap reroute button click.
- `app/src/main/java/com/evcs/favorites/ui/screens/LoginScreen.kt` [MODIFY] - Debounce OTP submit and disable button during verification.
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/FavoritesViewModel.kt` [MODIFY] - Guard `verifyOtp` against concurrent invocations.
- `app/src/main/java/com/evcs/favorites/ui/components/FavoritesProfileHeader.kt` [MODIFY] - Disable Google Sign-In button during in-flight auth.
- `app/src/test/java/com/evcs/favorites/hardening/ActionDebounceAndThrottlingTest.kt` [NEW] - Comprehensive test file for Phase 01.

## Test Criteria
- [ ] Run `./gradlew testDebugUnitTest --tests "com.evcs.favorites.hardening.ActionDebounceAndThrottlingTest"`
- [ ] 100% tests pass.
- [ ] Strictly only this single test is executed for Phase 01 verification.

---
Next Phase: [Phase 02: Thread-Safe Favorites Synchronization & Rapid-Click Guard](file:///home/skul9x/Desktop/Test_Code/EV-Plus-main/plans/260907-0025-click-spam-and-edge-case-hardening/phase-02-thread-safe-favorites-and-rapid-click.md)
