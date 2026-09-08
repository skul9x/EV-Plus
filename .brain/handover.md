# Handover Document: EV Smart Route Planning & Corridor Navigation System

Created: 2026-09-08 19:59
Status: 🟡 Planned - Ready for Execution

## Feature Overview
Implement a comprehensive EV Smart Routing & Charging Stop Planner exclusively for VinFast electric vehicles in EV-Plus:
1. **Offline Locations Dataset:** Bundle Vietnam's 63 provinces and ~700 districts from `https://www3.evcs.vn/l.json` (~26 KB) into `app/src/main/assets/vietnam_locations.json`.
2. **Bottom Navigation Architecture:** 3 Tabs: `NEARBY` | `FAVORITES` (Centered) | `ROUTE` (Beside Favorites).
3. **Smart Routing Engine:**
   - Vehicle safe range slider at 100% SoC (100 - 500 km, default 200 km) and starting SoC.
   - Highway dual-carriageway anti-trap: reject stations on the opposite side of divided expressways via OSRM detour penalty ($> 3$ km).
   - DC power hierarchy: DC $\ge 60$ kW $\to$ DC 30 kW $\to$ exclude AC 11 kW.
   - Charging duration buffer (+25% duration toggle in Settings).
   - Arrival safety reserve (default 10%) & target charging SoC (default 85%).
   - Busy station badge (0 live plugs) with ETA to next vacancy and alternate station swap.
   - Energy Corridor Bar: visual battery depletion trajectory across legs.
4. **Navigation Handoff:**
   - Target Stop 1 first in `FocusModeForegroundService`.
   - Auto-advance to Stop 2 / Destination upon reaching within 300m of the charging stop.

---

## Phases & Single Verification Tests

| Phase | Specification | Status | Verification Test |
|---|---|---|---|
| **01** | [phase-01-offline-locations-dataset-and-repository.md](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260908-1955-ev-smart-routing-and-navigation/phase-01-offline-locations-dataset-and-repository.md) | ⬜ Pending | `com.evcs.favorites.data.locations.VietnamLocationsRepositoryTest` |
| **02** | [phase-02-ev-routing-settings-and-preferences.md](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260908-1955-ev-smart-routing-and-navigation/phase-02-ev-routing-settings-and-preferences.md) | ⬜ Pending | `com.evcs.favorites.data.preferences.EvRoutingSettingsPreferencesTest` |
| **03** | [phase-03-smart-ev-corridor-route-planner.md](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260908-1955-ev-smart-routing-and-navigation/phase-03-smart-ev-corridor-route-planner.md) | ⬜ Pending | `com.evcs.favorites.data.routing.EvSmartRoutePlannerTest` |
| **04** | [phase-04-navigation-tabs-and-route-screen-ui.md](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260908-1955-ev-smart-routing-and-navigation/phase-04-navigation-tabs-and-route-screen-ui.md) | ⬜ Pending | `com.evcs.favorites.ui.screens.RouteTabNavigationAndUiStateTest` |
| **05** | [phase-05-settings-modal-and-navigation-handoff.md](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260908-1955-ev-smart-routing-and-navigation/phase-05-settings-modal-and-navigation-handoff.md) | ⬜ Pending | `com.evcs.favorites.ui.EvRoutingSettingsAndHandoffIntegrationTest` |

---

## Important Build Note
To build and run tests without Java `jlink` errors on this machine, always use:
```bash
JAVA_HOME=/home/skul9x/.jdks/jdk-17.0.20.1+1 ./gradlew testDebugUnitTest --tests "<TestClass>"
```

---

## Quick Resume Command
To start Phase 01:
```text
/code phase-01
```
Or to restore full context in a new session:
```text
/recap
```
