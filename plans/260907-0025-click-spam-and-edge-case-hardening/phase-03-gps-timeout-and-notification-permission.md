# Phase 03: GPS Timeout, Scan Guard & Android 13+ Notification Permissions

Status: ⬜ Pending
Dependencies: [Phase 02: Thread-Safe Favorites Synchronization & Rapid-Click Guard](file:///home/skul9x/Desktop/Test_Code/EV-Plus-main/plans/260907-0025-click-spam-and-edge-case-hardening/phase-02-thread-safe-favorites-and-rapid-click.md)

## Objective
Prevent indefinite UI hanging when device GPS is disabled or unresponsive, guard the Nearby refresh action against re-entrant scan invocations, and implement the runtime `POST_NOTIFICATIONS` permission flow for Android 13+ (API 33+) so Focus Mode notifications are never dropped.

## Requirements
### Functional
- [ ] Bounded GPS acquisition: `LocationService.getFreshLocation()` must enforce a strict 8-second timeout (`withTimeoutOrNull(8000L)`). If location hardware is disabled or provider times out, return null cleanly instead of freezing the caller.
- [ ] Guard `NearbyViewModel.refresh()` against concurrent active `scanJob` executions when the user taps reload repeatedly.
- [ ] Provide clear error feedback when GPS is disabled or unavailable: *"Không thể lấy vị trí hiện tại. Vui lòng kiểm tra GPS và thử lại."*
- [ ] Check and request `Manifest.permission.POST_NOTIFICATIONS` runtime permission on Android 13+ (Build.VERSION.SDK_INT >= 33) when activating Focus Mode, especially for Notification Fallback mode.
- [ ] Safely verify notification permission in `FocusModeForegroundService` before dispatching notifications.

### Non-Functional
- [ ] Configurable timeout injection in `LocationService` for deterministic test verification.
- [ ] Seamless backward compatibility from Android 8.0 (API 26) through Android 14 (API 34).

## Implementation Steps
1. [ ] Modify `app/src/main/java/com/evcs/favorites/domain/location/LocationService.kt`:
   - Add parameter `timeoutMs: Long = 8_000L` to `getFreshLocation()`.
   - Wrap the `suspendCancellableCoroutine` block with `withTimeoutOrNull(timeoutMs)`.
   - Return null if timed out or providers unavailable.
2. [ ] Modify `app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt`:
   - In `refresh(triggerType)`: check `if (scanJob?.isActive == true && (_uiState.value.isLocating || _uiState.value.isSearching)) return scanJob!`.
   - Handle GPS acquisition failure cleanly by resetting loading flags and showing friendly error.
3. [ ] Modify `app/src/main/java/com/evcs/favorites/ui/components/FocusModePermissionDialog.kt`:
   - Update descriptions to clarify notification permissions on Android 13+.
4. [ ] Modify `app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt`:
   - Integrate `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())` for `Manifest.permission.POST_NOTIFICATIONS` on API 33+.
   - Trigger notification permission request when user selects notification fallback if permission is not yet granted.
5. [ ] Modify `app/src/main/java/com/evcs/favorites/focus/FocusModeForegroundService.kt`:
   - Add notification permission check helper before calling `notificationManager?.notify(...)`.
6. [ ] Create single comprehensive test file:
   - `app/src/test/java/com/evcs/favorites/hardening/GpsTimeoutAndNotificationPermissionTest.kt`

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/domain/location/LocationService.kt` [MODIFY] - Add timeout wrapper to `getFreshLocation`.
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt` [MODIFY] - Guard `refresh()` against active `scanJob`.
- `app/src/main/java/com/evcs/favorites/ui/components/FocusModePermissionDialog.kt` [MODIFY] - Support notification permission request on API 33+.
- `app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt` [MODIFY] - Add notification permission launcher integration before fallback mode.
- `app/src/main/java/com/evcs/favorites/focus/FocusModeForegroundService.kt` [MODIFY] - Safe notification dispatch with permission check.
- `app/src/test/java/com/evcs/favorites/hardening/GpsTimeoutAndNotificationPermissionTest.kt` [NEW] - Comprehensive test file for Phase 03.

## Test Criteria
- [ ] Run `./gradlew testDebugUnitTest --tests "com.evcs.favorites.hardening.GpsTimeoutAndNotificationPermissionTest"`
- [ ] 100% tests pass.
- [ ] Strictly only this single test is executed for Phase 03 verification.

---
Next Phase: [Phase 04: Client Rate Limit & Lifecycle Edge Cases](file:///home/skul9x/Desktop/Test_Code/EV-Plus-main/plans/260907-0025-click-spam-and-edge-case-hardening/phase-04-rate-limit-and-lifecycle-hardening.md)
