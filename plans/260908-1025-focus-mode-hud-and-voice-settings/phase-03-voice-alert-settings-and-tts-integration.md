# Phase 03: Voice Alert Settings Persistence, Compose UI & Alert Triggers
Status: ✅ Completed
Dependencies: Phase 01, Phase 02

## Objective
Persist the user's preference for Focus Mode Voice Announcements (`voice_alert_enabled`), embed a high-contrast Material 3 toggle switch in `RoutingSettingsModal`, synchronize the preference instantaneously with `FocusModeTtsManager` (Audio Ducking) and `FocusModeForegroundService`, and enrich the voice alert policy to fulfill all 3 automotive voice scenarios specified in `1.md`.

## Requirements

### Functional
1. **Persistent Preferences Storage (`FocusModePreferences`)**:
   - Location: `data/preferences/FocusModePreferences.kt`.
   - Backing store: `SessionStorage` abstraction (`PlainSharedPrefsStorage` for production, `InMemorySessionStorage` for JVM unit tests).
   - Preferences filename: `evcs_focus_mode_prefs`.
   - Key: `KEY_VOICE_ALERT_ENABLED = "focus_voice_alert_enabled"` (Boolean, default: `true`).
   - Methods:
     - `isVoiceAlertEnabled(): Boolean`
     - `setVoiceAlertEnabled(enabled: Boolean)`
     - `val voiceAlertEnabledFlow: StateFlow<Boolean>` for reactive Compose UI updates.
2. **Settings UI Integration (`RoutingSettingsModal.kt`)**:
   - Add `FocusModeVoiceAlertCard` section into `RoutingSettingsModal`:
     - Material 3 container styled to match `EmeraldContainerDark` / `EmeraldPrimary`.
     - Icon: `Icons.Default.VolumeUp` / `Icons.Default.VolumeOff`.
     - Title: **"Cảnh báo bằng giọng nói (Voice Announcements)"**.
     - Subtitle: *"Tự động giảm âm lượng nhạc xe (Audio Ducking) và thông báo bằng tiếng Việt khi trạm sạc hết chỗ, gợi ý trạm mới hoặc cách trạm 2km."*
     - Switch: Reactive toggle bound to `FocusModePreferences.voiceAlertEnabledFlow`.
3. **Real-Time Service & Audio Ducking Synchronization**:
   - On `FocusModeForegroundService` startup:
     - Query `FocusModePreferences.isVoiceAlertEnabled()`.
     - Initialize `ttsManager.isMuted = !isVoiceAlertEnabled()` and `engine.setMuted(!isVoiceAlertEnabled())`.
   - On preference toggle in Settings UI:
     - Call `FocusModeForegroundService.setAudioMuted(context, !enabled)` so active navigation updates instantaneously without requiring a service restart.
   - When muted:
     - `FocusModeTtsManager.isMuted = true` cancels any active speech and immediately releases audio focus ducking (`abandonDuckAudioFocus()`).
     - `FocusModeVoiceAlertPolicy.isMuted = true` suppresses alert evaluations to avoid queueing stale speech.
4. **Fulfillment of the 3 Automotive Voice Scenarios (from `1.md` Section 4)**:
   - Enrich `FocusVoiceAlert` / `FocusModeVoiceAlertPolicy`:
     1. **Station Full (0 slots):** *"Cảnh báo: Trạm sạc vừa hết chỗ!"* (Already implemented).
     2. **Alternative Station Found:** When target station is full and an alternative candidate is resolved:
        *"Trạm hiện tại đã hết trụ. Đã tìm thấy trạm thay thế cách [X] km còn [Y] trụ trống."*
     3. **2km Proximity Reminder:** When `distanceRemainingKm` drops below `2.0km` for the first time on the journey:
        *"Sắp đến trạm sạc, còn [X] km."*
   - Each alert type respects independent 20-second debouncing to prevent audio fatigue.

### Non-Functional
- Non-blocking I/O on UI thread during preference reads and writes.
- Deterministic JVM unit testability with fake storage and mocked clocks.
- Zero audio leaks: `abandonDuckAudioFocus()` invoked immediately whenever muted or stopped.

## Implementation Steps
1. **Create `FocusModePreferences.kt`**:
   - Implement storage, read, write, and `StateFlow` observation using `SessionStorage`.
2. **Enrich `FocusModeVoiceAlertPolicy.kt`**:
   - Add support for alternative station found announcements and 2km proximity reminder.
   - Update `FocusModeTelemetryEngine` to trigger these alerts when conditions are met.
3. **Update `FocusModeForegroundService.kt`**:
   - Read `FocusModePreferences` during `initializeAndStartEngine(...)`.
   - Set `ttsManager.isMuted` and `engine.setMuted(...)` based on persisted preference.
4. **Update `RoutingSettingsModal.kt`**:
   - Add `FocusModeVoiceAlertCard` with switch and description.
   - Wire switch change to update `FocusModePreferences` and dispatch `FocusModeForegroundService.setAudioMuted`.
5. **Create single verification test `FocusModeVoiceAlertSettingsTest.kt`**.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/preferences/FocusModePreferences.kt` - [New] Preferences manager for Focus Mode settings.
- `app/src/main/java/com/evcs/favorites/focus/FocusModeVoiceAlertPolicy.kt` - [Modify] Add alternative station found and 2km proximity alerts with debouncing.
- `app/src/main/java/com/evcs/favorites/focus/FocusModeTelemetryEngine.kt` - [Modify] Wire enriched alert triggers into polling evaluation.
- `app/src/main/java/com/evcs/favorites/focus/FocusModeForegroundService.kt` - [Modify] Sync mute state with `FocusModePreferences` on launch.
- `app/src/main/java/com/evcs/favorites/ui/components/RoutingSettingsModal.kt` - [Modify] Add Voice Alert switch card.
- `app/src/test/java/com/evcs/favorites/focus/FocusModeVoiceAlertSettingsTest.kt` - [New] Comprehensive JVM unit test for Phase 03.

## Test Criteria
- Single test: `com.evcs.favorites.focus.FocusModeVoiceAlertSettingsTest`
  - Verifies default setting value is `true` (voice announcements enabled).
  - Verifies preference writing and reading across app restarts using `InMemorySessionStorage`.
  - Verifies `voiceAlertEnabledFlow` emits new values on toggle.
  - Verifies `FocusModeTtsManager.isMuted` and `FocusModeVoiceAlertPolicy.isMuted` synchronize with preference.
  - Verifies voice alert generation for:
    - Station transitions from $>0$ to $0$ slots ("Cảnh báo: Trạm sạc vừa hết chỗ!").
    - Alternative station found ("Trạm hiện tại đã hết trụ. Đã tìm thấy trạm thay thế...").
    - 2km proximity boundary crossing ("Sắp đến trạm sạc, còn...").
  - Verifies that when muted (`voice_alert_enabled = false`), all speech triggers and audio ducking requests are completely suppressed.

---
Next Phase: Completed Phase 1 Roadmap! Ready for Phase 2 (Landscape Android Box UI).
