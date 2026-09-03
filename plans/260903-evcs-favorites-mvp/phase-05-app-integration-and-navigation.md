# Phase 05: Navigation & End-to-End Integration

Status: ✅ Completed  
Dependencies: Phase 04  

---

## 1. Objective
Integrate 1-Tap Google Maps routing intents, connect the Compose navigation graph in `MainActivity`, provide offline caching via local database/file storage, and finalize the complete end-to-end user experience.

---

## 2. Requirements

### Functional
- `MapNavigator`:
  - Builds standard geo URI intents: `geo:latitude,longitude?q=latitude,longitude(StationName)`.
  - Also generates Google Maps navigation intent: `google.navigation:q=latitude,longitude&mode=d`.
  - Gracefully falls back to browser/generic map viewer if Google Maps is not installed.
- `MainActivity`:
  - Entry point hosting `FavoritesApp` composable inside `ComponentActivity.setContent`.
  - Requests fine/coarse location permissions with clean user rationale.
- Offline Cache:
  - Caches the latest fetched favorites list in local storage so users can immediately view their stations even without an active internet connection.

### Non-Functional
- Zero crashes on unexpected Intent resolution.
- Instant, seamless app startup.

---

## 3. Implementation Steps
1. Implement `MapNavigator` helper utility with intent formatting and fallback logic.
2. Implement offline caching mechanism in `FavoritesRepository` (storing JSON snapshot).
3. Wire up `MainActivity` with runtime location permission launcher and Compose theme.
4. Implement the single comprehensive test file: `src/test/java/com/evcs/favorites/MapIntentIntegrationTest.kt`.

---

## 4. Files to Create / Modify
- `app/src/main/java/com/evcs/favorites/navigation/MapNavigator.kt`: Google Maps & Geo intent dispatcher.
- `app/src/main/java/com/evcs/favorites/MainActivity.kt`: Main Android activity and permission handling.
- `app/src/test/java/com/evcs/favorites/MapIntentIntegrationTest.kt`: Single verification test for this phase.

---

## 5. Single Verification Test
- **File:** `app/src/test/java/com/evcs/favorites/MapIntentIntegrationTest.kt`
- **Scope:**
  - Validates `MapNavigator` URI string generation for coordinates and encoded station names.
  - Validates intent action types (`ACTION_VIEW`) and fallback package handling.
  - Validates offline cache persistence and reload without network connectivity.

---
Plan Complete. Ready for review and execution.
