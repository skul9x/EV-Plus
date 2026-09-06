# Phase 05: Station Card ETA Pill & Navigation UI
Status: ✅ Completed
Dependencies: [Phase 04: ViewModel Hybrid Pipeline & ETA Sorting](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260903-multi-tier-routing-and-byok/phase-04-viewmodel-hybrid-filtering-and-eta-sorting.md)

## Objective
Enhance `StationCard.kt` to present a rich, informative journey pill displaying driving duration (ETA), distance, and traffic condition color-coding according to the active tier, and ensure 1-tap turn-by-turn navigation intent (`google.navigation:q=lat,lng`).

## Requirements
### Functional
- **Rich Journey Badge (`StationCard.kt`)**:
  - Replace the simple straight-line distance chip with an adaptive journey badge:
    - **Tier 1 (Google Routes with Live Traffic)**:
      - Text format: `🚗 {minutes} phút • {distanceKm} km • {Traffic Label}`
      - Traffic conditions & color coding:
        - 🟢 `Thông thoáng` (`#10B981`): Delay ratio $R < 1.15$
        - 🟡 `Kẹt xe vừa` (`#F59E0B`): Delay ratio $1.15 \le R < 1.35$
        - 🔴 `Ùn tắc` (`#EF4444`): Delay ratio $R \ge 1.35$
    - **Tier 2 (OSRM Table Service)**:
      - Text format: `🚗 {minutes} phút • {distanceKm} km • Đường bộ`
      - Neutral cyan/blue styling (`#06B6D4` or `#3B82F6`) indicating accurate road network calculation without live congestion.
    - **Tier 3 (Haversine Baseline)**:
      - Text format: `⚡ {distanceKm} km • Đường thẳng`
      - Subtle neutral styling indicating straight-line fallback distance.
  - **Responsive Layout Protection**:
    - Convert sub-header in `StationCard.kt` from single `Row` to `FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp))`.
    - Guarantees the rich journey badge and the working time/parking pill wrap cleanly on narrow screens (e.g. 360dp) without text truncation or edge clipping.
- **Turn-by-Turn Navigation Integration**:
  - Tapping the navigation button on the station card delegates to existing `MapNavigator.navigate(context, latitude, longitude, stationName)`:
    - Primary intent: `google.navigation:q={latitude},{longitude}&mode=d` (Google Maps package `com.google.android.apps.maps`)
    - Fallback 1: generic geo intent `geo:{latitude},{longitude}?q={latitude},{longitude}(Name)`
    - Fallback 2: web browser `https://www.google.com/maps/search/?api=1&query={lat},{lon}`

### Non-Functional
- Compact and responsive chip layout that prevents text truncation on narrow mobile screens.
- Adherence to Material Design 3 and EVCS Dark Mode theme tokens.

## Implementation Steps
1. Refactor sub-header in `StationCard.kt` to use `FlowRow` and replace `DistanceBadge` with rich journey badge evaluating `station.drivingMetrics`.
2. Implement helper function `formatJourneyBadge(metrics: DrivingMetrics?, distanceKm: Double?): JourneyBadgeInfo` for clean presentation logic and testability.
3. Verify station navigation action integrates seamlessly with existing `MapNavigator`.
4. Implement `StationCardRoutingTest.kt` verifying badge formatting for all tiers, traffic color assignment rules, and navigation intent URI construction.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/components/StationCard.kt` - [Modify] Implement FlowRow layout, rich ETA journey badge, and traffic coloring
- `app/src/test/java/com/evcs/favorites/StationCardRoutingTest.kt` - [New] Comprehensive file-based verification test for Phase 05

## Test Criteria
- Verify Tier 1 formatting generates `🚗 X phút • Y km • [Thông thoáng/Kẹt xe vừa/Ùn tắc]`.
- Verify traffic delay ratio mapping:
  - Ratio $< 1.15 \rightarrow$ Green (`#10B981`) `Thông thoáng`
  - $1.15 \le R < 1.35 \rightarrow$ Amber (`#F59E0B`) `Kẹt xe vừa`
  - Ratio $\ge 1.35 \rightarrow$ Red (`#EF4444`) `Ùn tắc`
- Verify Tier 2 formatting generates `🚗 X phút • Y km • Đường bộ` with cyan/blue accent.
- Verify Tier 3 formatting generates `⚡ Y km • Đường thẳng`.
- Verify navigation URI generator outputs valid `google.navigation:q=lat,lng&mode=d` format matching destination station coordinates via `MapNavigator`.
