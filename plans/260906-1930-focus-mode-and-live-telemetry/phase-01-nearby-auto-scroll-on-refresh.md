# Phase 01: Nearby Auto-Scroll on Refresh

Status: ✅ Completed
Dependencies: None

## Objective
Enable automatic and smooth scrolling of the Nearby stations list to the top (`index = 0`) whenever a user taps the Refresh button and new updated stations are received, ensuring the closest charging station is immediately in the driver's viewport.

## Requirements
### Functional
- [x] Track manual refresh events triggered by the user in `NearbyViewModel` / `NearbyScreen`.
- [x] When new stations load successfully following an explicit refresh action, automatically scroll `LazyListState` to `index = 0` via `animateScrollToItem(0)`.
- [x] Ensure that background updates or passive location updates do NOT unexpectedly hijack or disturb the user's manual scroll position while browsing down the list.

### Non-Functional
- [x] Animation must be smooth and non-blocking (executed inside a Compose `CoroutineScope`).
- [x] No race conditions with recomposition or empty list transitions.

## Implementation Steps
1. Add an event or state trigger `lastRefreshTimestamp` or `ScrollToTopEffect` in `NearbyViewModel` or `NearbyUiHelper`.
2. In `NearbyScreen.kt`, observe the refresh completion event and call `lazyListState.animateScrollToItem(0)` safely when new data is populated.
3. Guard the auto-scroll so it only fires when initiated by a user refresh gesture/button, preserving scroll position during regular list browsing.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/screens/NearbyScreen.kt` - [Observe refresh completion and trigger smooth scroll to index 0]
- `app/src/main/java/com/evcs/favorites/ui/components/NearbyUiHelper.kt` - [Add scroll event detection and state resolution]

## Test Verification (Single Test per Phase)
- Exactly one comprehensive test file:
  - `app/src/test/java/com/evcs/favorites/ui/NearbyAutoScrollOnRefreshTest.kt`
- Test cases covered:
  - Verify that user-initiated refresh completion produces a `ScrollToTop` action.
  - Verify that regular passive polling or pagination does not emit spurious scroll-to-top triggers.
  - Verify state preservation across non-empty list reloads.

---
Next Phase: [phase-02-hybrid-tier-1-here-ev-api-and-oauth.md](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260906-1930-focus-mode-and-live-telemetry/phase-02-hybrid-tier-1-here-ev-api-and-oauth.md)
