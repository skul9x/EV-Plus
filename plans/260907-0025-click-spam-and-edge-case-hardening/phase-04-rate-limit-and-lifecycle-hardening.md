# Phase 04: Client Rate Limit & Lifecycle Edge Cases

Status: ⬜ Pending
Dependencies: [Phase 03: GPS Timeout, Scan Guard & Android 13+ Notification Permissions](file:///home/skul9x/Desktop/Test_Code/EV-Plus-main/plans/260907-0025-click-spam-and-edge-case-hardening/phase-03-gps-timeout-and-notification-permission.md)

## Objective
Enforce client-side rate limit validation prior to issuing network requests to prevent extending Cloudflare/EVCS IP blocks, optimize gesture-driven drag animations in the photo lightbox to eliminate GC allocation churn, guarantee Audio Focus ducking release in FocusMode TTS, and preserve modal dialog states across screen rotations.

## Requirements
### Functional
- [ ] Client-Side Rate Limit Enforcement: `EvcsApiClient` must check `isGlobalRateLimited()` at the start of `searchStations()`, `fetchFavorites()`, and `saveFavorites()`. If currently in cooldown, immediately fail with `RateLimitException` without opening an HTTP connection or making unnecessary network round-trips.
- [ ] `EvcsRepository.searchNearbyVinFast()` must similarly check `isGlobalRateLimited()` before triggering network operations.
- [ ] State Preservation across Rotations: Replace `remember` with `rememberSaveable` for modal dialog visibility (`showRoutingSettings`, `showLoginRequiredDialog`, `showPermissionRationale`) in `NearbyScreen` and `MainActivity`.
- [ ] Lightbox Gesture Performance: Eliminate per-frame coroutine allocation (`coroutineScope.launch`) inside `onDismissDrag` by directly updating animation state via a continuous drag-tracking pattern.
- [ ] TTS Audio Focus Safety: Enforce a fallback watchdog timeout in `FocusModeTtsManager` so if a 3rd-party TTS engine fails to fire `onDone` or `onError`, audio ducking focus is automatically abandoned within 6 seconds, preventing permanently ducked vehicle audio.

### Non-Functional
- [ ] Zero memory leaks and zero unnecessary coroutine allocations during gesture drag interactions.
- [ ] Deterministic simulation of rate-limit cooldown, gesture drag, and rotation lifecycle in unit tests.

## Implementation Steps
1. [ ] Add `if (isGlobalRateLimited()) throw RateLimitException(...)` check at the entrance of `EvcsApiClient.searchStations`, `fetchFavorites`, and `saveFavorites`.
2. [ ] Add pre-network rate-limit check in `EvcsRepository.searchNearbyVinFast`.
3. [ ] In `StationPhotoViewerModal.kt`, update `detectVerticalDragGestures` to mutate an offset state directly or use an interactive pointer drag listener without launching a new coroutine on every single motion delta.
4. [ ] In `NearbyScreen.kt` and `MainActivity.kt`, migrate transient dialog flags from `remember` to `rememberSaveable`.
5. [ ] In `FocusModeTtsManager.kt`, add a watchdog timeout job that calls `abandonDuckAudioFocus()` after 6 seconds of speech execution if not already completed.
6. [ ] Create single comprehensive test file:
   - `app/src/test/java/com/evcs/favorites/hardening/RateLimitAndLifecycleHardeningTest.kt`

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/api/EvcsApiClient.kt` [MODIFY] - Enforce pre-network rate limit check before request dispatch.
- `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt` [MODIFY] - Check rate limit before search.
- `app/src/main/java/com/evcs/favorites/ui/components/StationPhotoViewerModal.kt` [MODIFY] - Optimize drag gesture state mutation without per-frame coroutines.
- `app/src/main/java/com/evcs/favorites/ui/screens/NearbyScreen.kt` [MODIFY] - Use `rememberSaveable` for dialog states.
- `app/src/main/java/com/evcs/favorites/MainActivity.kt` [MODIFY] - Use `rememberSaveable` for permission rationale state.
- `app/src/main/java/com/evcs/favorites/focus/FocusModeTtsManager.kt` [MODIFY] - Add watchdog timeout for audio ducking release.
- `app/src/test/java/com/evcs/favorites/hardening/RateLimitAndLifecycleHardeningTest.kt` [NEW] - Comprehensive test file for Phase 04.

## Test Criteria
- [ ] Run `./gradlew testDebugUnitTest --tests "com.evcs.favorites.hardening.RateLimitAndLifecycleHardeningTest"`
- [ ] 100% tests pass.
- [ ] Strictly only this single test is executed for Phase 04 verification.

---
All Phases Complete!

