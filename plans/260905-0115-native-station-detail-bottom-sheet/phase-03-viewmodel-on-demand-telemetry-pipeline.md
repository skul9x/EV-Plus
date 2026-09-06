# Phase 03: ViewModel On-Demand Telemetry Pipeline
Status: ✅ Completed  
Dependencies: Phase 02

## Objective
Wire the on-demand telemetry pipeline into the application ViewModels (`FavoritesViewModel` and `NearbyViewModel`) so that selecting a station triggers background fetch for telemetry, user rating, and 24h stats with instant sheet opening, progressive data enrichment, and clean lifecycle cancellation upon dismissal.

## Requirements
### Functional
- [x] Define `StationDetailUiState` data class:
  - `station: Station? = null`
  - `isLoadingTelemetry: Boolean = false`
  - `isLoadingStats: Boolean = false`
  - `isRefreshing: Boolean = false`
  - `rating: StationRating? = null`: User star rating and count from EVCS token handshake.
  - `telemetry: StationTelemetry? = null`
  - `cleanForecast: String? = null`: Sanitized live forecast ticker string (null if locked or unavailable).
  - `portStatuses: List<StationPortStatus> = emptyList()`: Real-time available/total ports per kW tier.
  - `stats24h: Station24hStats? = null`: Computed 24h metrics (peak, average, rush hour, fill rate).
  - `error: String? = null`
- [x] Update `FavoritesViewModel` & `NearbyViewModel`:
  - Expose `stationDetailState: StateFlow<StationDetailUiState>`.
  - `selectStationForDetail(station: Station)`:
    - **Instant Opening (0ms)**: Sets `station` immediately, populates `portStatuses` from static `station.evsePowers` / `powerPorts`, sets `isLoadingTelemetry = true`, `isLoadingStats = true`.
    - **Stage 1 (Live Charging & Rating, ~200ms)**:
      - Calls `fetchStationTokens(station)` -> updates `rating`.
      - Calls `fetchLiveCharging(station.id, chargeToken)` -> derives updated live `portStatuses` and `cleanForecast`.
      - Sets `isLoadingTelemetry = false`.
    - **Stage 2 (24h Usage Stats, ~500-1200ms)**:
      - Calls `fetch24hHistory(station.id, apiToken)` with 4s timeout.
      - Calculates `Station24hStats` and updates `stats24h`.
      - Sets `isLoadingStats = false`.
    - **Stage 3 (Telemetry Ping)**: Dispatches `sendTelemetryUpdate` fire-and-forget.
  - `refreshStationDetail()`:
    - Re-fetches Stage 1 & Stage 2 with `isRefreshing = true`.
  - `dismissStationDetail()`:
    - Cancels active coroutine jobs and tears down any active socket immediately.
    - Resets `stationDetailState` to initial empty state.
- [x] Zero background overhead: No telemetry polling or background jobs run when the sheet is closed.

## Test Criteria (Single Comprehensive Test)
- **Test File**: `app/src/test/java/com/evcs/favorites/ui/viewmodel/StationDetailViewModelPipelineTest.kt`
- **Verifications**:
  1. `selectStationForDetail` emits initial state synchronously with the selected station and initial ports (zero delay).
  2. Live port availability, clean forecast, and rating are enriched during Stage 1.
  3. 24h usage statistics are enriched during Stage 2 while gracefully handling timeouts.
  4. `dismissStationDetail` cancels active background jobs and clears selection cleanly.
  5. Manual refresh updates `isRefreshing` state correctly and repopulates stats.

---
Next Phase: [phase-04-native-compose-bottom-sheet-ui.md](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260905-0115-native-station-detail-bottom-sheet/phase-04-native-compose-bottom-sheet-ui.md)

