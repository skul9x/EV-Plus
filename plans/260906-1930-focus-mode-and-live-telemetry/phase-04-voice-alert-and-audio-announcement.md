# Phase 04: Voice Alert & Audio Announcement

Status: ✅ Completed
Dependencies: Phase 03

## Objective
Implement hands-free audio notifications using Android Text-to-Speech (TTS) in Vietnamese paired with gentle audio chimes, notifying drivers immediately when their targeted station becomes full (0 DC slots) or when a DC slot becomes newly available, eliminating the need to take eyes off the road.

## Requirements
### Functional
- [x] Initialize Android `TextToSpeech` with Locale `vi-VN` (fallback to default TTS engine if Vietnamese language pack requires installation).
- [x] Generate audio alert when target station transitions from having slots to 0 DC slots available:
  - Text: `"Cảnh báo: Trạm sạc vừa hết chỗ!"`
- [x] Generate audio alert when target station transitions from 0 to $\ge 1$ DC slot available:
  - Text: `"Trụ sạc vừa có súng trống!"`
- [x] Implement audio alert debouncing (minimum 20 seconds between identical voice alerts) to prevent notification fatigue during rapid data fluctuations.
- [x] Provide a mute/unmute audio preference in Focus Mode.

### Non-Functional
- [x] Audio focus management: Request transient audio ducking (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`) so navigation apps or music smoothly duck volume during speech and restore afterward.
- [x] Release all TTS resources upon service shutdown.

## Implementation Steps
1. Create `FocusModeVoiceAlertPolicy.kt` with pure business logic deciding when an alert should trigger based on consecutive telemetry snapshots.
2. Implement `FocusModeTtsManager.kt` handling TextToSpeech initialization, audio ducking, and announcement queuing.
3. Integrate `FocusModeVoiceAlertPolicy` with `FocusModeTelemetryEngine`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/focus/FocusModeVoiceAlertPolicy.kt` - [Pure transition & debouncing rules for announcements]
- `app/src/main/java/com/evcs/favorites/focus/FocusModeTtsManager.kt` - [TTS engine, Vietnamese locale setup, and audio ducking]

## Test Verification (Single Test per Phase)
- Exactly one comprehensive test file:
  - `app/src/test/java/com/evcs/favorites/focus/FocusModeVoiceAlertTest.kt`
- Test cases covered:
  - Validates alert triggered on transition from available > 0 to available == 0.
  - Validates alert triggered on transition from available == 0 to available > 0.
  - Validates debouncing suppresses repeated alerts within the threshold window.
  - Validates no alerts on steady-state polling with unchanged slot counts.

---
Next Phase: [phase-05-floating-window-ui-and-permission-fallback.md](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260906-1930-focus-mode-and-live-telemetry/phase-05-floating-window-ui-and-permission-fallback.md)
