# Plan: Landscape Settings UX, Automotive Stepper, Visual Color Fix & Commercial Branding

Created: 2026-09-10 15:05
Status: 🟡 In Progress
Branch: main

## Overview
Address four critical issues in the commercial Settings experience on automotive landscape screens and Android Box devices:
1. **Landscape Scrolling**: Enable smooth vertical scrolling on the detail canvas (and sidebar safety scroll) so settings cards are never cut off.
2. **Visual Color Remediation**: Eliminate green-on-green visual clutter in the "Hiển thị & Xe" (Startup Orientation) card by switching to neutral dark card containers, high-contrast white text, and refined emerald accents.
3. **Automotive Power Stepper**: Remove virtual keyboard numeric text inputs (`OutlinedTextField`) in landscape mode; replace with dedicated large-touch `[-] [ XX kW ] [+]` automotive steppers.
4. **Commercial Branding & Copyright**: Lock commercial version string to `"1.0"` in `AboutAppInfo` and anchor a persistent `Copyright 2026` footer at the bottom of the settings screen.

## Technical Rules & Constraints
- Exactly **one comprehensive file-based test** per phase.
- Do not create or run more than one test per phase.
- All phase files written in English.
- Stop for user review after each phase completion.

## Phases

| Phase | Name | Description | Status | Verification Test |
|---|---|---|---|---|
| 01 | Landscape Scrolling & Visual Hierarchy | Add vertical scrolling to landscape canvas and redesign `StartupOrientationCard` & `FocusModeVoiceAlertCard` color palette | ✅ Complete | `com.evcs.favorites.ui.screens.LandscapeScrollAndVisualContractTest` |
| 02 | Automotive Power Filter Steppers | Remove keyboard text entry in landscape mode; implement touch-friendly +/- 10kW steppers and display boxes | ⬜ Pending | `com.evcs.favorites.ui.screens.LandscapeCustomFilterStepperContractTest` |
| 03 | Commercial Branding & Copyright Footer | Update version to "1.0", add persistent 2026 copyright footer to screen bottom, update About card | ⬜ Pending | `com.evcs.favorites.ui.screens.CommercialBrandingAndCopyrightContractTest` |

## Quick Commands
- Run Phase 01 Test: `./gradlew testDebugUnitTest --tests "com.evcs.favorites.ui.screens.LandscapeScrollAndVisualContractTest"`
- Run Phase 02 Test: `./gradlew testDebugUnitTest --tests "com.evcs.favorites.ui.screens.LandscapeCustomFilterStepperContractTest"`
- Run Phase 03 Test: `./gradlew testDebugUnitTest --tests "com.evcs.favorites.ui.screens.CommercialBrandingAndCopyrightContractTest"`
