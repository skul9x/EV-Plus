# Plan: Click-Spam Protection, Concurrency & Edge-Case Hardening
Created: 2026-09-07T00:25:00+07:00
Status: 🟡 In Progress

## Overview
Comprehensive system-wide hardening to eliminate user click-spam vulnerabilities, concurrent race conditions, missing runtime permissions, indefinite UI hangs, and unhandled lifecycle edge cases across EV Plus.

This plan addresses all identified vectors:
1. **Debounce & Throttling Engine:** Action debounce and in-flight locking for 1-Tap navigation (Google Maps), ⚡ Focus Mode foreground service activation, 1-Tap reroute on floating capsule, Google Sign-In, and double OTP verification submit.
2. **Thread-Safe Favorites & Rapid-Click Guard:** Coroutine `Mutex` in `FirestoreFavoritesRepository` to guarantee atomic state mutations without lost updates, in-flight station locking (`togglingStationIds`) in `NearbyViewModel` and `FavoritesViewModel`, and toast notification debouncing.
3. **Hardware & Permission Safeguards:** Strict 8-second timeout (`withTimeoutOrNull`) in `LocationService.getFreshLocation()` preventing indefinite UI freeze when GPS hardware is off, re-entrant scan protection in `NearbyViewModel.refresh()`, and runtime `POST_NOTIFICATIONS` permission request flow for Android 13+ (API 33+).
4. **Client-Side Protections & Lifecycle:** Pre-network Rate Limit (HTTP 429) cooldown checks in `EvcsApiClient` and `EvcsRepository`, gesture drag allocation optimization in Lightbox, Audio Focus ducking watchdog timeout in TTS, and `rememberSaveable` dialog state preservation across screen rotations.

## Tech Stack
- Platform: Android (Kotlin 1.9.23, JVM Target 17)
- UI: Jetpack Compose (Material 3 BOM 2024.04.01)
- Architecture: MVVM + Coroutines StateFlow / Mutex + Clean Architecture
- System Services: FusedLocationProviderClient, Foreground Service, WindowManager, TextToSpeech, Credential Manager

## Verification Strategy
- Exactly **one** comprehensive unit test file per phase to verify core functionality.
- No more than one test file created or executed per phase.
- Gradle test execution targeting the single phase test class: `./gradlew testDebugUnitTest --tests "<TestClass>"`.
- Stop after each phase for user review.

## Phases

| Phase | Name | Description | Verification Test File | Status | Progress |
|---|---|---|---|---|---|
| 01 | [Action Debounce & Throttling Engine](phase-01-action-debounce-and-throttling.md) | Reusable debounce helper, throttled 1-Tap navigation, debounced Focus Mode & reroute clicks, and OTP double-submit guard | `ActionDebounceAndThrottlingTest.kt` | ⬜ Pending | 0% |
| 02 | [Thread-Safe Favorites & Rapid-Click Guard](phase-02-thread-safe-favorites-and-rapid-click.md) | Mutex synchronization in repository, in-flight station lock, and toast notification spam suppression | `FavoriteConcurrencyAndThreadSafetyTest.kt` | ⬜ Pending | 0% |
| 03 | [GPS Timeout, Scan Guard & Android 13+ Permissions](phase-03-gps-timeout-and-notification-permission.md) | 8-second GPS acquisition timeout, active scan job guard on refresh, and runtime POST_NOTIFICATIONS flow | `GpsTimeoutAndNotificationPermissionTest.kt` | ⬜ Pending | 0% |
| 04 | [Client Rate Limit & Lifecycle Edge Cases](phase-04-rate-limit-and-lifecycle-hardening.md) | Pre-network 429 cooldown checks, gesture drag allocation optimization, TTS ducking watchdog, and rotation state preservation | `RateLimitAndLifecycleHardeningTest.kt` | ⬜ Pending | 0% |

## Quick Commands
- Start Phase 01: `/code phase-01`
- Review Plan: Inspect individual phase files in `plans/260907-0025-click-spam-and-edge-case-hardening/`
