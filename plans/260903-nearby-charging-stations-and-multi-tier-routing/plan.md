# Plan: Nearby Charging Stations with Multi-Tier Routing & Wattage Filter

Created: 2026-09-03
Status: ✅ Completed

## Overview
Implement a native "Nearby Charging Stations" screen replicating the original EVCS app's core GPS-based discovery while significantly elevating it with the modern Android MAD stack (Jetpack Compose Material 3, MVVM, Coroutines, StateFlow). The feature allows EV drivers to locate nearby VinFast passenger EV charging stations on-demand via GPS, apply multi-power wattage filters (from 3.5kW up to 360kW) with strict availability filtering, compute real-time driving distance and ETA traffic metrics for strictly the **Top 10 nearest stations** via `MultiTierRoutingCoordinator` (Google Routes v2 / OSRM Table Service), toggle cloud favorites directly from search results with instant two-way synchronization, and seamlessly switch between Favorites and Nearby screens via a Material 3 `NavigationBar`.

## Key Architectural & Design Decisions
1. **Targeted Data Scope (VinFast Passenger EVs Only)**: Exclusively focus on VinFast passenger EV charging infrastructure, skipping 3rd-party networks and 2-wheeler battery swapping stations for optimal responsiveness, reduced payload, and zero clutter.
2. **Two-Stage Filtering & Strict Top 10 Routing Policy**:
   - *Stage 1 (Client-Side Wattage & Port Availability Filtering)*: Raw stations fetched via signed `POST /search?t=...` are filtered by user-selected wattage chips (`OR` condition). Strict availability is enforced: matching connectors must have `numberOfAvailableEvse > 0` and station `depotStatus` must not be `Maintaining` or `OutOfService`. Stations with zero available ports are completely excluded. If no wattage chips are selected, all stations with `totalAvailablePlugs > 0` are accepted.
   - *Stage 2 (Top 10 Multi-Tier Matrix Routing)*: Compute instant great-circle Haversine distances for all valid stations, sort ascending, and extract strictly the **Top 10 nearest stations**. Only these 10 stations are dispatched to `MultiTierRoutingCoordinator` for road driving distance and live traffic ETA. Whenever the user alters wattage filters, a fresh Top 10 is extracted and re-routed.
3. **Manual GPS Trigger & Zero Auto-Polling**: GPS scanning is initiated exclusively on-demand when the user taps the primary "Nhấn để tìm trạm quanh đây" button. Vehicle displacement (> 200m) does not trigger automatic background re-scans, preserving battery and cellular data quota.
4. **Reactive Two-Way Cloud Favorites Sync**: Each station card features an interactive heart icon. If authenticated (`SessionManager.hasAuthCookie() == true`), tapping toggles the favorite state and synchronizes with EVCS cloud via `POST /favorite.html` (`action: "save"`). `EvcsRepository` exposes a reactive `favoritesState: StateFlow<List<Station>>`, ensuring changes in Nearby immediately reflect in Favorites and vice-versa. If unauthenticated, a Material 3 `AlertDialog` prompts the user to log in.
5. **Material 3 Bottom Navigation**: The root `Scaffold` hosts an M3 `NavigationBar` with 🌟 **Yêu thích** (Favorites - Default start destination) and 📍 **Quanh đây** (Nearby).

## Phases

| Phase | Name | Status | Verification Test | Progress |
|-------|------|--------|-------------------|----------|
| 01 | Domain Models, Wattage Filter Engine & Cloud Favorites Sync API | ✅ Completed | `NearbyFilteringAndFavoriteSyncTest.kt` | 100% |
| 02 | Top 10 Multi-Tier Routing Pipeline & Nearby ViewModel | ✅ Completed | `NearbyRoutingViewModelTest.kt` | 100% |
| 03 | Wattage Filter Chips UI & Station Card Favorite Action | ✅ Completed | `NearbyUiComponentsTest.kt` | 100% |
| 04 | Bottom Navigation Bar, NearbyScreen & Two-Way State Sync | ✅ Completed | `AppNavigationAndNearbyIntegrationTest.kt` | 100% |

## Strict Testing Protocol
- Each phase contains **exactly one comprehensive file-based test** to verify its core functionality.
- After implementing each phase, execute only that single test for verification (`./gradlew testDebugUnitTest --tests com.evcs.favorites.<TestName>`).
- Stop after each phase verification for user review.

## Quick Commands
- Start Phase 1: `/code phase-01`
- Next Step: `/next`
