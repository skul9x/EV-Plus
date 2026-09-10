# Phase 03: Vietnamese TTS Alert Voice Scripting & Floating HUD Integration
Status: ✅ Completed
Dependencies: Phase 01, Phase 02

## Objective
Update the Vietnamese Text-to-Speech (TTS) announcement formatting in `FocusModeVoiceAlertPolicy` to match the exact approved wording: *"Trạm bạn muốn tới hiện tại đã hết cổng. Gợi ý đổi sang [Tên trạm B], cách [X] cây số, đi mất [Y] phút, còn [Z] cổng [P]kW"*. Integrate this with the Floating Capsule HUD (`FocusModeFloatingViewManager`) and `FocusModeForegroundService` so when a driver taps the reroute button, the engine resolves the best OSRM alternative station, navigates via Google Maps, and announces the updated voice guidance.

## Requirements
### Functional
- [x] Update `FocusModeVoiceAlertPolicy.formatAlternativeFoundText(...)` to produce the standardized string:
  > `"Trạm bạn muốn tới hiện tại đã hết cổng. Gợi ý đổi sang ${stationName}, cách ${distStr} cây số, đi mất ${durationMinutes} phút, còn ${slots} cổng ${powerKw}kW"`
- [x] Ensure `durationMinutes` is accurately calculated from `effectiveDurationSeconds` (e.g. `durationSeconds / 60` rounded to nearest minute; default to 1 min if < 60s).
- [x] When target DC availability drops to 0, Floating HUD displays the reroute action button.
- [x] When the user clicks the reroute button in the Floating HUD or UI:
  1. Trigger on-demand OSRM reroute calculation.
  2. Emit voice alert with the exact wording format.
  3. Launch turn-by-turn navigation (`google.navigation:q=lat,lon&mode=d`) to the selected OSRM alternative station.
  4. Update the active target station in `FocusModeTelemetryEngine`.
- [x] Support optional telemetry ticker enrichment from `POST /charging` (`busyKw` & forecast turnaround ticker) when inspecting station details.

### Non-Functional
- [x] Clean TTS utterance completion and release of transient audio ducking focus.
- [x] Click-spam debouncing (1,000ms cooldown) on the floating reroute action button.

## Implementation Steps
1. Update `FocusModeVoiceAlertPolicy`:
   - Refactor `formatAlternativeFoundText` to accept `stationName`, `distanceKm`, `durationMinutes`, `availableSlots`, `powerKw`.
   - Update `evaluateAlternativeStation` to supply these parameters from `AlternativeStationRecommendation`.
2. Update `FocusModeFloatingViewManager` & `FocusModeForegroundService`:
   - Wire user click on reroute button to trigger the conditional OSRM reroute flow in the engine.
   - Format button text with OSRM driving distance.
3. Provide single comprehensive integration test in `app/src/test/java/com/evcs/favorites/focus/FocusModeVoiceAndHudIntegrationTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/focus/FocusModeVoiceAlertPolicy.kt` - Update Vietnamese TTS phrasing.
- `app/src/main/java/com/evcs/favorites/focus/FocusModeFloatingViewManager.kt` - Enhance reroute button label and interaction.
- `app/src/main/java/com/evcs/favorites/focus/FocusModeForegroundService.kt` - Wire reroute action to OSRM execution.
- `app/src/test/java/com/evcs/favorites/focus/FocusModeVoiceAndHudIntegrationTest.kt` - [NEW] Single comprehensive unit test for Phase 03.

## Test Criteria
- Exactly one comprehensive test file: `FocusModeVoiceAndHudIntegrationTest.kt` verifying:
  1. Formatted TTS sentence exactly matches: `"Trạm bạn muốn tới hiện tại đã hết cổng. Gợi ý đổi sang [Tên trạm B], cách [X] cây số, đi mất [Y] phút, còn [Z] cổng [P]kW"`.
  2. Floating HUD formats reroute button correctly and handles 1-tap user invocation cleanly.
  3. Full flow from target saturation -> user tap -> OSRM resolution -> state update.

---
Next Phase: Completion & Review
