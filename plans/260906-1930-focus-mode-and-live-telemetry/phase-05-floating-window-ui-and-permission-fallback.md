# Phase 05: Floating Window UI & Permission Fallback

Status: ⬜ Pending
Dependencies: Phase 03, Phase 04

## Objective
Build the draggable Android System Alert Overlay (Floating Bubble/Capsule) that floats above Google Maps navigation, displaying live DC slot counts, offline warnings, 1-tap reroute buttons, and a manual `[X]` dismiss button, while providing a seamless fallback to a persistent Foreground Service Notification if overlay permission is denied.

## Requirements
### Functional
- [ ] Permission check for `Settings.canDrawOverlays(context)`.
- [ ] Implement `FocusModeFloatingViewManager`:
  - When overlay permission is granted, instantiate a compact floating capsule using `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`.
  - Support smooth touch dragging and snap-to-edge behavior.
  - Normal state: Display station name, available DC slots / total slots (`🟢 2/8 Trống (150kW)`), and a close `[X]` button.
  - Full state (0 slots): Highlight red badge (`🔴 HẾT CHỖ!`) and reveal 1-tap reroute button (`[🔄 Đổi trạm: {Tên} (+{km}km)]`). Tapping reroute immediately updates the target station and relaunches Google Maps navigation intent.
  - Offline state: Display warning badge (`⚠️ Mất kết nối - Dữ liệu lúc HH:mm`).
  - Dismiss: Only closes when user taps `[X]`.
- [ ] Implement Notification Fallback:
  - When overlay permission is denied or revoked, present an ongoing Foreground Notification with equivalent text, action buttons ("Đổi trạm", "Tắt"), and maintain TTS alerts.

### Non-Functional
- [ ] Zero crash on rapid orientation changes or app minimization.
- [ ] Safe removal from `WindowManager` on service destroy to prevent Android window leaks.

## Implementation Steps
1. Create `FocusModeViewLayoutHelper.kt` defining layout parameters, touch listener math, and state formatting.
2. Implement `FocusModeFloatingViewManager.kt` handling WindowManager add/update/remove cycles.
3. Implement `FocusModeNotificationHelper.kt` to build ongoing notifications for the fallback mode.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/focus/FocusModeViewLayoutHelper.kt` - [Touch gesture calculation & view state presentation]
- `app/src/main/java/com/evcs/favorites/focus/FocusModeFloatingViewManager.kt` - [WindowManager overlay controller]
- `app/src/main/java/com/evcs/favorites/focus/FocusModeNotificationHelper.kt` - [Foreground notification fallback builder]

## Test Verification (Single Test per Phase)
- Exactly one comprehensive test file:
  - `app/src/test/java/com/evcs/favorites/focus/FocusModeFloatingWindowAndFallbackTest.kt`
- Test cases covered:
  - Validates formatted text and color tokens across Normal, Full (Reroute available), and Offline states.
  - Validates permission branch routing: chooses overlay mode when `canDrawOverlays=true` and notification mode when `false`.
  - Validates touch drag boundary clamping math to prevent dragging outside screen bounds.
  - Validates manual close `[X]` trigger properly sends termination intent.

---
Next Phase: [phase-06-sheet-entry-point-and-e2e-integration.md](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260906-1930-focus-mode-and-live-telemetry/phase-06-sheet-entry-point-and-e2e-integration.md)
