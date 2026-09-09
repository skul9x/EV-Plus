# Phase 01: Minimum Power Criteria & Starting SoC Settings Integration

Status: ✅ Completed
Dependencies: None

## Objective
Extend `EvRoutingSettings`, persistent preferences, `RouteUiState` (inside `RouteViewModel.kt`), and `VehicleConfigurationCard` in `RouteScreen.kt` to support user-configurable minimum charger power (`minChargerPowerKw`). Ensure that changes to starting battery ($SoC_{start}$), vehicle safe range, and minimum power threshold immediately update preferences and invalidate stale route plans to prevent misleading drivers.

## Requirements
### Functional
- [x] Add `minChargerPowerKw: Double` (default `60.0` kW) to `EvRoutingSettings` with validation bounds `[20.0, 250.0]` in `sanitized()`.
- [x] Provide predefined power presets matching Vietnamese charging infrastructure:
  - `30.0` kW: Standard DC (suitable for VinFast VF3 / urban chargers)
  - `60.0` kW: Fast DC (Default recommended for highway cruising)
  - `150.0` kW: Ultra-Fast DC (High-power highway corridor hubs)
  - `250.0` kW: Super-Fast DC (Peak express hubs)
- [x] Update `RoutingPreferencesManager` in `com.evcs.favorites.data.routing` to persist `minChargerPowerKw` with reactive `StateFlow`.
- [x] Update `RouteUiState` (in `RouteViewModel.kt`) to expose `minChargerPowerKw`.
- [x] Add `onMinPowerChanged(powerKw: Double)` to `RouteViewModel`.
- [x] Ensure changing `startBatteryPercent`, `safeRangeKm`, or `minChargerPowerKw` clears stale `routePlan` (`routePlan = null`) or initiates recalculation so the UI never displays an outdated route.
- [x] Add a high-contrast automotive chip selector in `VehicleConfigurationCard` (`RouteScreen.kt`) allowing drivers to switch between minimum power presets with 1 tap.

### Non-Functional
- [x] Thread safety: State mutations run on appropriate coroutine dispatchers (IO for persistence, Main for UI state updates).
- [x] Immutability: Maintain `@Immutable` data structures across UI states.
- [x] Zero disk thrashing: Debounce slider persistence writes while keeping UI values immediately responsive.

## Implementation Steps
1. [x] Update `app/src/main/java/com/evcs/favorites/data/routing/EvRoutingSettings.kt`:
   - Add `minChargerPowerKw: Double = DEFAULT_MIN_CHARGER_POWER_KW` (60.0 kW).
   - Add constants: `DEFAULT_MIN_CHARGER_POWER_KW = 60.0`, `MIN_CHARGER_POWER_KW = 20.0`, `MAX_CHARGER_POWER_KW = 250.0`.
   - Add preset constants: `PRESET_POWER_STANDARD = 30.0`, `PRESET_POWER_FAST = 60.0`, `PRESET_POWER_ULTRA = 150.0`, `PRESET_POWER_SUPER = 250.0`.
   - Update `sanitized()` to clamp `minChargerPowerKw`.
2. [x] Update `app/src/main/java/com/evcs/favorites/data/routing/RoutingPreferencesManager.kt`:
   - Add DataStore/Preference key `ev_min_charger_power_kw`.
   - Expose `updateMinChargerPowerKw(powerKw: Double)`.
   - Update `updateEvRoutingSettings` to include `minChargerPowerKw`.
3. [x] Update `app/src/main/java/com/evcs/favorites/ui/screens/RouteViewModel.kt`:
   - Add `minChargerPowerKw: Double = 60.0` to `RouteUiState`.
   - Sync `minChargerPowerKw` in `observeRoutingPreferences()`.
   - Add `fun onMinPowerChanged(powerKw: Double)` which updates state, persists preference, and invalidates stale `routePlan`.
   - Invalidate `routePlan` on `onSafeRangeChanged` and `onStartBatteryPercentChanged` when values differ significantly.
4. [x] Update `app/src/main/java/com/evcs/favorites/ui/screens/RouteScreen.kt`:
   - Add Power Selection Chips to `VehicleConfigurationCard` for `≥ 30 kW`, `≥ 60 kW (Chuẩn)`, `≥ 150 kW`, `≥ 250 kW`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/routing/EvRoutingSettings.kt` - Add `minChargerPowerKw` field, presets, and bounds.
- `app/src/main/java/com/evcs/favorites/data/routing/RoutingPreferencesManager.kt` - Persist minimum power preference.
- `app/src/main/java/com/evcs/favorites/ui/screens/RouteViewModel.kt` - Expose `minChargerPowerKw` in `RouteUiState` and add update handlers.
- `app/src/main/java/com/evcs/favorites/ui/screens/RouteScreen.kt` - Render power preset chips in `VehicleConfigurationCard`.
- `app/src/test/java/com/evcs/favorites/data/routing/EvRoutingMinPowerSettingsTest.kt` - Comprehensive unit test for Phase 01.

## Test Criteria (Single Verification Test)
- **Test Class:** `com.evcs.favorites.data.routing.EvRoutingMinPowerSettingsTest`
- **Key Assertions:**
  1. Default `EvRoutingSettings` initializes with `minChargerPowerKw = 60.0`.
  2. Out-of-bound power values (< 20.0 kW or > 250.0 kW) clamp safely during `sanitized()`.
  3. `RoutingPreferencesManager` correctly reads, writes, and emits updated `minChargerPowerKw`.
  4. `RouteViewModel` updates `RouteUiState.minChargerPowerKw` reactively when preferences change.
  5. Invalidation of `routePlan` occurs when starting battery or minimum power is changed.

---
Next Phase: [Phase 02: Lookahead Corridor Routing Engine with Power Filtering & Fallback Detection](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260909-0735-ev-routing-algorithm-refinement/phase-02-greedy-forward-simulation-and-window-search.md)
