# Plan: Google Maps API Key In-App User Guide & Settings UI/UX Optimization
Created: 2026-09-03
Status: 🟡 In Progress

## Overview
Provide an intuitive, comprehensive, step-by-step in-app user guide for acquiring and configuring a Google Maps / Routes API key in the app's settings (`RoutingSettingsModal`). Optimize the Settings UI/UX with direct Google Cloud Console deep links, one-tap clipboard paste, quick copy for package name, proactive troubleshooting for HTTP 400/403/429 errors, and clear guidance on the updated 2025/2026 per-SKU free tier (10,000 free requests/month for Routes API Essentials).

## Architecture & Design Decisions
- **Data & Content Architecture**: Decouple guide content, step descriptions, direct URLs, and troubleshooting guides into `ApiKeyGuideProvider.kt` with pure Kotlin models (`ApiKeyGuideStep`, `ApiKeyTroubleshootingItem`, `FreeTierInfo`).
- **Fail-Safe BYOK Restriction Guidance**: Clarify in Step 4 that personal BYOK keys should set Application Restrictions to "None" (since REST calls don't provide custom SHA-1 certs) and restrict key strictly to "Routes API" with $0 budget alerts, avoiding guaranteed HTTP 403 restriction errors.
- **Interactive Guide UI (Dialog/Sheet)**: Build `GoogleApiKeyGuideModal.kt` using Jetpack Compose as a high-z-index `Dialog` with a rounded surface container to avoid Compose M3 nested `ModalBottomSheet` scrim and gesture conflicts.
- **Settings UX & Production Wiring**:
  - Enhance `RoutingSettingsModal.kt` with a direct "Xem hướng dẫn chi tiết" action, one-tap clipboard paste button with automatic trimming, contextual Cloud Console deep links, and `RoutingSettingsHelper.kt` for 100% JVM unit testability.
  - Wire `RoutingPreferencesManager.create(context)` into `FavoritesViewModel.provideFactory` and connect `routingSettings`, `onSaveRoutingSettings`, and `onValidateGoogleApiKey` in `MainActivity.kt`.

## Phases

| Phase | Name | Status | Verification Test | Progress |
|-------|------|--------|-------------------|----------|
| 01 | Guide Data Models, Content & Troubleshooting Provider | ✅ Completed | `ApiKeyGuideProviderTest.kt` | 100% |
| 02 | Interactive Guide Dialog UI Component & Presenter | ⬜ Pending | `ApiKeyGuideUiStateTest.kt` | 0% |
| 03 | Settings Modal UX, MainActivity Wiring & Deep Links | ⬜ Pending | `RoutingSettingsModalUxTest.kt` | 0% |

## Strict Testing Protocol
- Each phase contains **exactly one comprehensive file-based test** to verify its core functionality.
- After implementing each phase, execute only that single test for verification (`./gradlew testDebugUnitTest --tests com.evcs.favorites.<TestName>`).
- Stop after each phase verification for user review.

## Quick Commands
- Start Phase 1: `/code phase-01`
- Next Step: `/next`

