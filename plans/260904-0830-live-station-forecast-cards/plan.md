# Plan: Live Station Forecast on Nearby and Favorites Cards
Created: 2026-09-04 08:30:00 (GMT+7)
Updated: 2026-09-04 08:38:00 (GMT+7)
Status: 🟡 In Progress

## Overview
Enable real-time EV charging completion forecast badges on station cards across both "Quanh đây" (Nearby) and "Yêu thích" (Favorites) tabs for full stations (`available_ports == 0`).
The forecast is parsed from server-side rendered (SSR) static HTML of EVCS station detail pages, bypassing client-side JavaScript locks (`amd-locked`).
To optimize network and performance, forecasting targets strictly the Top 5 nearest full stations with a 3-minute in-memory cache, background retry with exponential backoff, request concurrency throttled via `Semaphore(3)`, and an Amber UI presentation adhering to [1.md](file:///home/skul9x/Desktop/Code/TramsacEV/1.md).

## User Decisions & Constraints
1. **Target Stations**: Top 5 nearest full stations (`totalPlugs > 0 && totalAvailablePlugs == 0`), sorted by actual distance to user coordinates, `take(5)` (applied uniformly to both Nearby and Favorites tabs).
2. **UI Format**:
   - Header badge: Amber `⏱️ Sắp trống` (replacing red `Hết cổng`) with smooth animated crossfade (`AnimatedContent`).
   - Forecast Capsule: Placed between Address row and Wattage chips row:
     - Single session / 1 power line: `⏱️ Dự kiến 2 xe sạc trụ 20kW sẽ xong trong 7-14 phút nữa`
     - Multi sessions / multi-power levels:
       Header: `⚡ DỰ KIẾN CỔNG SẮP TRỐNG:`
       Bullets:
       `• 20kW:  ~7-14 phút (2 xe)`
       `• 60kW:  ~13 phút (1 xe)`
       `• 250kW: ~8 phút (1 xe)`
     - Background: `Color(0x1AF59E0B)` (Amber 10%), Border: `BorderStroke(1.dp, Color(0x4DF59E0B))` rounded `10.dp`, Padding: `horizontal = 12.dp, vertical = 8.dp`.
     - Smooth entrance animation: `AnimatedVisibility(visible = station.forecast != null, enter = fadeIn() + expandVertically())`.
3. **Transition**: Seamless optimistic appearance (starts with red "Hết cổng", smoothly crossfades to amber "⏱️ Sắp trống" badge upon fetch completion).
4. **Caching & Refresh**: 3-minute (180s) TTL memory cache (`ForecastCache`); invalidated immediately on manual pull-to-refresh (`forceRefresh = true`).
5. **Resilience & Concurrency**:
   - Concurrency throttle: `Semaphore(3)` to cap simultaneous HTTP requests.
   - Background silent retry with exponential backoff (1s, 2s + jitter 0-300ms) on transient network failures.
   - Unrecoverable failures degrade silently to red "Hết cổng" with a 1-minute failure cooldown.
6. **Testing**: Exactly ONE comprehensive file-based test per phase. Stop after verifying each phase for user review.

## Phases

| Phase | Name | Status | Test File |
|-------|------|--------|-----------|
| 01 | Domain Models & Parser Implementation | ✅ Completed | `StationForecastParserTest.kt` |
| 02 | Repository Cache & Network Retry Layer | ⬜ Pending | `StationForecastRepositoryTest.kt` |
| 03 | ViewModel Pipeline (Nearby & Favorites) | ⬜ Pending | `StationForecastViewModelPipelineTest.kt` |
| 04 | UI StationCard Forecast Capsule & Polish | ⬜ Pending | `StationCardForecastBadgeTest.kt` |

## Quick Commands
- Execute Phase 1: `/code phase-01`
- Check progress: `/next`
