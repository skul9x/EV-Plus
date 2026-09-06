# Plan: Real-Time Charging Forecast Synchronization & Alignment
Created: 2026-09-04 10:55:00 (GMT+7)
Status: 🟡 Pending Review

## Overview
Resolve the discrepancy between Station Card forecasts on Native screens (showing stale SSR cache text e.g. "Dự kiến 2 xe sạc trụ 150kW sẽ xong trong 15-29 phút nữa") and the Station Detail Modal (showing live dynamic ticker e.g. "Dự kiến 1 xe sạc trụ 150kW sẽ xong trong...").

The root cause is that the native client executes a simple HTTP `GET` for the static SSR HTML page, whereas the web client executes a 2-step dynamic API handshake via `POST`:
1. `POST <station_url>` with header `X-Partial: user` to acquire a short-lived `chargeToken`.
2. `POST /charging` with header `x-t: <chargeToken>` and body `{"id":"<locationId>","t":"vinfast"}` to receive real-time JSON containing `ticker`, `busyKw`, and `partial`.

Additionally, the WebView in `StationDetailModal` suffers from a CSS layout bug on narrow mobile viewports where the green `[Xem thêm ↗]` button overlaps the minute display text (`... phút nữa`).

## Key Rules & Architectural Decisions
1. **Single Comprehensive Test Per Phase**:
   - Exactly ONE file-based test per phase.
   - Run ONLY that single test for verification before pausing for user review.
   - Do not create or run more than one test per phase.
2. **Two-Step Dynamic Handshake in EvcsApiClient**:
   - Implement `fetchChargingForecast(stationName: String, locationId: String, isVinFast: Boolean)` matching `web_charging.js`.
   - Step 1: `POST <canonical_station_url>` with `X-Partial: user` header -> extract `chargeToken`.
   - Step 2: `POST /charging` with `x-t: <chargeToken>` header and JSON body `{"id":"<id>","t":"vinfast"}` -> extract `ChargingForecastResponse`.
3. **Repository Pipeline & Cache Integration**:
   - Update `EvcsRepository.fetchStationForecast` to invoke `apiClient.fetchChargingForecast` instead of downloading raw 34KB static HTML.
   - Parse ticker HTML directly using `StationForecastParser`.
   - Retain existing `ForecastCache` (3-minute TTL), failure cooldown (1 minute), and `Semaphore(3)` throttling.
4. **WebView CSS Injection for Layout Correction**:
   - In `StationDetailModal.kt` (`onPageFinished`), inject responsive CSS adding `padding-right: 115px !important` to `.amd-hasmore .amd-item` so the `[Xem thêm ↗]` badge never obscures the forecast duration.

## Phases

| Phase | Name | Scope | Status | Test File |
|-------|------|-------|--------|-----------|
| 01 | API Client Charging Forecast Handshake | `EvcsApiClient` & Models | ⬜ Pending | `ChargingForecastApiClientTest.kt` |
| 02 | Repository Real-Time Forecast Pipeline | `EvcsRepository` & `StationForecastParser` | ⬜ Pending | `ChargingForecastRepositoryPipelineTest.kt` |
| 03 | WebView Modal Layout & UI Alignment | `StationDetailModal` CSS Injection | ⬜ Pending | `StationDetailModalForecastAlignmentTest.kt` |

## Quick Commands
- Verify Phase 01: `./gradlew testDebugUnitTest --tests com.evcs.favorites.data.api.ChargingForecastApiClientTest`
- Verify Phase 02: `./gradlew testDebugUnitTest --tests com.evcs.favorites.data.repository.ChargingForecastRepositoryPipelineTest`
- Verify Phase 03: `./gradlew testDebugUnitTest --tests com.evcs.favorites.ui.components.StationDetailModalForecastAlignmentTest`
