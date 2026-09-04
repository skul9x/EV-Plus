# Phase 01: Default OSRM Routing & BYOK Decoupling
Status: ✅ Completed
Dependencies: None

## Objective
Establish OSRM (Open Source Routing Machine) as the standard, default routing engine across the app with automatic, seamless fallback to Haversine (straight-line distance) when OSRM is unreachable or errors. Decouple routing from Google BYOK requirements so that no Google API key or user setup is required.

## Requirements
### Functional
- [x] Set `RoutingSettings` default `preferredEngine` to `RoutingEngineMode.OSRM_ONLY` and `autoFallbackEnabled` to `true`.
- [x] Ensure `RoutingPreferencesManager.loadSettings()` defaults unconfigured or missing engine settings to `RoutingEngineMode.OSRM_ONLY` with `autoFallbackEnabled = true`.
- [x] Ensure `MultiTierRoutingCoordinator` seamlessly executes OSRM routing and automatically falls back to Haversine straight-line calculation on network failures, non-200 responses, or timeouts when auto-fallback is enabled.
- [x] Verify that station distance and travel calculations in both `NearbyViewModel` and `FavoritesViewModel` function immediately without an API key.

### Non-Functional
- [x] Fast offline resilience: Haversine fallback executes in 0ms if network connectivity drops.
- [x] Backward compatibility: preserve existing custom OSRM server URL configuration if present.
- [x] Safe fallback guarantee: when OSRM encounters HTTP 4xx/5xx or timeout, fallback is invoked transparently without throwing exceptions to caller ViewModels.

## Implementation Steps
1. [x] Update `RoutingSettings` in `app/src/main/java/com/evcs/favorites/data/routing/RoutingModels.kt` to default `preferredEngine = RoutingEngineMode.OSRM_ONLY` and `autoFallbackEnabled = true`.
2. [x] Update `RoutingPreferencesManager` in `app/src/main/java/com/evcs/favorites/data/routing/RoutingPreferencesManager.kt` to load `OSRM_ONLY` as default when no user preference is stored or when migrating unconfigured preferences.
3. [x] Verify `MultiTierRoutingCoordinator` in `app/src/main/java/com/evcs/favorites/data/routing/MultiTierRoutingCoordinator.kt` to guarantee robust OSRM execution and clean fallback cascade to Haversine.
4. [x] Create single comprehensive test `app/src/test/java/com/evcs/favorites/data/routing/OsrmHaversineDefaultRoutingTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/routing/RoutingModels.kt` - Default engine and fallback configuration
- `app/src/main/java/com/evcs/favorites/data/routing/RoutingPreferencesManager.kt` - Default settings loading and migration
- `app/src/main/java/com/evcs/favorites/data/routing/MultiTierRoutingCoordinator.kt` - Fallback arbitration
- `app/src/test/java/com/evcs/favorites/data/routing/OsrmHaversineDefaultRoutingTest.kt` - Single comprehensive verification test

## Test Criteria (Single Test File)
- **Test File**: `app/src/test/java/com/evcs/favorites/data/routing/OsrmHaversineDefaultRoutingTest.kt`
- [x] Verify default `RoutingSettings` initializes with `preferredEngine == OSRM_ONLY` and `autoFallbackEnabled == true`.
- [x] Verify `RoutingPreferencesManager.loadSettings()` provides OSRM default on empty storage.
- [x] Verify successful OSRM matrix calculation returns driving metrics without any Google API key.
- [x] Verify OSRM HTTP 500, socket timeout, or offline connectivity automatically triggers Haversine fallback with valid distance calculations.
- [x] Verify invalid coordinates or empty destinations return safe empty maps without crashing.

---
Next Phase: `phase-02-debug-log-subsystem-and-forecast-capture.md`
