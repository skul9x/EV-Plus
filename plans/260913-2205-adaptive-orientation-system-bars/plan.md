# Plan: Adaptive Orientation System Bars (Edge-to-Edge Portrait & Immersive Landscape)
Created: 2026-09-13 22:05
Status: 🟡 In Progress

## Overview
Re-architect Android system bars handling to dynamically adapt based on screen orientation. In Portrait mode, enforce standard modern Edge-to-Edge (Android 14/15 transparent style with visible Status Bar and Navigation Bar, eliminating the need to swipe the screen edge like consumer apps Facebook and TikTok). In Landscape mode, strictly preserve Fullscreen Immersive Sticky Mode (`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` + `hide(systemBars())`) for automotive Carlinkit / Android Box environments.

## Architecture & Design
- **`AdaptiveSystemBarsHelper`**: Encapsulates orientation resolution, system bars behavior flags, and window inset application logic.
- **`MainActivity` Lifecycle**: Enforces adaptive system bars during `onCreate`, `onWindowFocusChanged`, `onConfigurationChanged`, and reactive orientation preference updates.
- **`Theme.kt` System Bars Appearance**: Provides transparent edge-to-edge bar background and reactive light/dark status bar and navigation bar icon contrast adaptation.

## Phases

| Phase | Name | Status | Verification Test |
|-------|------|--------|-------------------|
| 01 | Adaptive System Bars Helper & Policy | ⬜ Pending | `com.evcs.favorites.util.AdaptiveSystemBarsHelperTest` |
| 02 | Activity Lifecycle & Edge-to-Edge Integration | ⬜ Pending | `com.evcs.favorites.ui.AdaptiveSystemBarsIntegrationTest` |

## Execution Guidelines
- Implement Phase 01, run only `com.evcs.favorites.util.AdaptiveSystemBarsHelperTest`, and stop for user review.
- Implement Phase 02, run only `com.evcs.favorites.ui.AdaptiveSystemBarsIntegrationTest`, and stop for user review.
