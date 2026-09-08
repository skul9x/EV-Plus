# Plan: Phase 1 - Automotive HUD Floating Window & Voice TTS Settings

Created: 2026-09-08 10:25
Updated: 2026-09-08 10:30
Status: ✅ Completed

## Overview

Upgrade the EV-Plus Focus Mode floating overlay and audio telemetry system to resolve driver glanceability, text clipping, and safety issues on both handheld smartphones and car screens / Android Boxes:
1. **Dynamic Responsive Sizing & Zero Text Clipping:** Eliminate fixed 200dp width and tiny 10-13sp fonts. Compute overlay bounds dynamically based on screen orientation (30-34% clamped [280dp, 440dp] for landscape/car screens, 80-85% clamped [280dp, 380dp] for portrait/phone screens). Enforce `AppCompatTextView` uniform auto-sizing (13sp-16sp) inside width-constrained containers, isolate available DC slots into a prominent 24sp Bold Hero Metric card, safeguard Vietnamese diacritics against vertical clipping, provide $\ge 48dp - 56dp$ touch targets, and handle orientation change events dynamically.
2. **Mini Pill ⇄ Full HUD 1-Tap Toggle:** Support seamless transformation between an ultra-compact "Mini Pill" (~80x38dp, `🟢 4`) that never obstructs navigation maps and an expanded "Full HUD" detailing station stats, charging tier chips, and 1-tap rerouting. Implement robust gesture discrimination using `ViewConfiguration.scaledTouchSlop` + duration threshold to prevent accidental drags during driving, resolve child click conflicts, and maintain edge snap alignment when view width changes.
3. **Voice TTS Alert Persistence, Settings UI & Spoken Alerts:** Persist user voice announcement preferences using `PlainSharedPrefsStorage` (`KEY_VOICE_ALERT_ENABLED`, default `true`), expose a high-contrast Material 3 toggle switch in `RoutingSettingsModal`, synchronize live audio ducking state with `FocusModeForegroundService`, and enrich `FocusModeVoiceAlertPolicy` to handle the 3 key automotive scenarios specified in `1.md` (0 slots full, alternative station found, and 2km proximity reminder).

## Tech Stack
- Platform: Android 8.0+ (API 26-34)
- Language: Kotlin 1.9.23 (JVM 17)
- UI: Android WindowManager System Alert Overlay (`TYPE_APPLICATION_OVERLAY`) + Jetpack Compose Material 3
- Storage: `PlainSharedPrefsStorage` (Thread-safe, zero cold-start delay)
- Telemetry & Audio: `FocusModeTelemetryEngine` + `FocusModeTtsManager` (Audio Ducking via `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`)
- Testing: JUnit4 + Kotlinx Coroutines Test (100% fast JVM unit testing)

## Phases

| Phase | Name | Status | Single Verification Test |
|---|---|---|---|
| 01 | Dynamic Responsive Sizing, Anti-Clipping & Orientation Handling | ✅ Completed | `com.evcs.favorites.focus.FocusModeDynamicLayoutScalingTest` |
| 02 | Mini Pill ⇄ Full HUD 1-Tap Toggle & Touch Interaction | ✅ Completed | `com.evcs.favorites.focus.FocusModeDisplayModeToggleTest` |
| 03 | Voice Alert Settings Persistence, Compose UI & Alert Triggers | ✅ Completed | `com.evcs.favorites.focus.FocusModeVoiceAlertSettingsTest` |

## Verification Strategy
- Exactly one comprehensive file-based test per phase.
- Run only that single test after completing each phase via `./gradlew testDebugUnitTest --tests "<TestClass>"`.
- Fast execution on pure JVM without emulator or hardware dependencies.
- Stop after each phase for user review and validation.
