# Plan: Favorites Tab Station Information Display and Real-Time Telemetry Fix
Created: 2026-09-17T08:25:00+07:00
Status: 🟡 In Progress

## Overview
Resolves critical telemetry and display degradation bugs in the Favorites tab:
1. Prevents port count truncation ("1/1" and "0/1 Hết cổng") by storing and restoring full-fidelity domain metadata (`powers`, `totalPlugs`, `totalAvailablePlugs`, `depotStatus`, `isFreeParking`, `workingTimeDescription`, `evse`) in Firestore and local offline cache.
2. Fixes connector string parsing in `EvcsRepository.parseConnectorsToPowers` using multiplier regexes so that port counts are properly captured.
3. Fixes fallback clamping in `StationTelemetryParser.derivePortStatuses` so unverified total plugs fall back to connector definitions instead of forcing 1.
4. Prevents HTTP 404 crashes for saved AC charging stations (from HERE API) in `StationDetailCoordinator` by bypassing `evcs.vn` web scraping for non-VinFast stations and dynamically toggling `isAcFilterActive`.
5. Synchronizes live telemetry and routing driving metrics between `FavoritesViewModel` and `StationDetailCoordinator` so that detail sheets and landscape panes always show real-time metrics without stale state lag.
6. Re-enables lightweight cluster search re-enrichment when favorite stations have missing or unverified telemetry.

## Phases

| Phase | Name | Status | Single Verification Test | Progress |
|-------|------|--------|--------------------------|----------|
| 01 | Full-Fidelity Favorites Model Persistence & Connector Parsing | ✅ Completed | `FavoritesPersistenceAndConnectorParsingTest.kt` | 100% |
| 02 | Station Detail State Synchronization & AC Station Safety | ⬜ Pending | `FavoritesDetailStateAndAcSafetyTest.kt` | 0% |
| 03 | Live Cluster Search & Background Telemetry Re-Enrichment | ⬜ Pending | `FavoritesClusterSearchAndTelemetryEnrichmentTest.kt` | 0% |

## Verification Strategy
- Exactly one comprehensive file-based test per phase.
- Only run that single test upon phase completion.
- Stop for user review after each phase.
