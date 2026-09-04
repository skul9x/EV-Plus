# Plan: Native Jetpack Compose Station Detail Bottom Sheet

Created: 2026-09-05 01:15:00 (GMT+7)  
Status: 🟢 Completed  
Target: Replace legacy WebView station detail modal with a 100% native Jetpack Compose Bottom Sheet delivering instant rendering (<50ms), zero gesture lag, real-time port telemetry, and 24h usage statistics with zero text clipping.

---

## 1. Overview & Architecture

### Problem
Previously, tapping a station in the app opened `StationDetailModal`, which loaded `https://evcs.vn/{station-slug}.html` in an embedded `WebView`. This introduced severe drawbacks:
1. **P0 Memory Leak (PERF-MEM-02)**: WebView engine allocated 15–45MB native RAM per open without timely release, causing OOM crashes.
2. **Slow rendering & heavy data**: Downloaded full HTML, web fonts, tracking scripts, and third-party web banners (300KB+ transfer).
3. **Gesture conflicts**: Web scrolling constantly conflicted with the bottom sheet drag handle.
4. **Layout brittleness & text clipping**: Web elements suffered from viewport mismatch and CSS text clipping on narrow mobile screens.

### Live Network Protocol Specification (Verified via cURL Captures)
Analysis of `curl_capture_20260905_005601` and `curl_capture_20260905_005946` reveals the exact 3-step EVCS communication pipeline:

1. **Step 1 - Station Access & Token Handshake**:
   - `POST https://evcs.vn/{station-slug}.html`
   - Headers: `X-Partial: user`, `User-Agent`, `Cookie: PHPSESSID=...`
   - Returns JSON:
     - `chargeToken`: Ephemeral auth token for `/charging` endpoint.
     - `apiToken`: Ephemeral auth token for Socket.io and `/update`.
     - `rating`: `{ avg: Double, count: Int, mine: Int }` (Native rating data!).
     - `hasGo: Boolean`, `hasBiz: Boolean`, `historyToken7`, `historyToken30`.
2. **Step 2 - Live Charging Telemetry**:
   - `POST https://evcs.vn/charging`
   - Headers: `Content-Type: application/json`, `x-t: chargeToken`
   - Body: `{"id": stationId, "t": "vinfast"}`
   - Returns JSON:
     - `busyKw: Map<String, Int>`: Occupied vehicle count per kW rating (e.g. `{"60": 4, "120": 5}`).
     - `ticker: String`: HTML forecast ticker. When active: `"Dự kiến 2 xe sạc trụ 120kW sẽ xong trong 1-7 phút..."`. When locked (`amd-locked`): promotional upsell prompt which must be filtered out (`cleanForecast = null`).
3. **Step 3 - 24h Usage Statistics (Socket.io via www2.evcs.vn)**:
   - Connects to `https://www2.evcs.vn/` with `auth: {"t": apiToken}`.
   - Emits `subscribe` with `stationId`, then `history` with `{stationId, hours: 24, token: "", detail: false}`.
   - Listens to `history_data`: Returns array of `[timestamp, vehicleCount]` data points.
   - Disconnects immediately after receiving data (4s timeout safeguard).
   - Reuses shared `OkHttpClient` pool for WebSocket/HTTP (`PERF-NET-01`).
4. **Step 4 (Optional) - Telemetry Ping Sync**:
   - `POST https://www2.evcs.vn/update` with `x-t: apiToken` and body `{"a": stationId, "b": totalBusyCars}`. Returns 204 No Content.

### Production Calculation Formulas (Verified from detail.26082102.js)
- **CAO ĐIỂM (Peak Usage)**: `points.maxOfOrNull { it.second } ?: 0`.
- **TRUNG BÌNH (Average Usage)**: Arithmetic mean `rawAvg = points.map { it.second }.average()`. If `rawAvg in (0.0..1.0)` then `1` else `rawAvg.roundToInt()`.
- **GIỜ CAO ĐIỂM (Peak Rush Hour)**: Grouped by UTC+7 Vietnam local hours (`timestamp + 25,200,000ms`). Best hour `h` (0..23) has highest peak vehicle count, tied by highest hourly average. Formatted as `"$h-${(h + 1) % 24}h"`.
- **TỈ LỆ LẤP ĐẦY (Fill Rate)**: `if (totalPorts > 0) min(100, (rawAvg / totalPorts * 100).roundToInt()) else 0` formatted as `"$fillRate%"`.

---

## 2. Phases & Progress

| Phase | Name | Status | Comprehensive Test |
|---|---|---|---|
| [Phase 01](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260905-0115-native-station-detail-bottom-sheet/phase-01-domain-telemetry-and-stats-models.md) | Domain Telemetry and 24h Stats Models | 🟢 Completed | `StationTelemetryModelsAndParserTest.kt` |
| [Phase 02](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260905-0115-native-station-detail-bottom-sheet/phase-02-evcs-telemetry-repository-and-stats-calculator.md) | Telemetry Repository & 24h Stats Engine | 🟢 Completed | `EvcsTelemetryRepositoryAndStatsEngineTest.kt` |
| [Phase 03](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260905-0115-native-station-detail-bottom-sheet/phase-03-viewmodel-on-demand-telemetry-pipeline.md) | ViewModel On-Demand Telemetry Pipeline | 🟢 Completed | `StationDetailViewModelPipelineTest.kt` |
| [Phase 04](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260905-0115-native-station-detail-bottom-sheet/phase-04-native-compose-bottom-sheet-ui.md) | Native Compose Bottom Sheet UI | 🟢 Completed | `NativeStationDetailSheetUiTest.kt` |
| [Phase 05](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260905-0115-native-station-detail-bottom-sheet/phase-05-screen-integration-and-webview-decoupling.md) | Screen Integration & Legacy Cleanup | 🟢 Completed | `NativeStationDetailIntegrationTest.kt` |

---

## 3. Strict Execution Principles
1. **Single Comprehensive Test Per Phase**: Exactly ONE file-based test per phase. Do not create or run more than one test per phase.
2. **Deterministic Verification**: After completing each phase, run only that single test. Then stop for user review.
3. **No Text Clipping**: Zero hardcoded clipping boxes. All containers wrap and scroll naturally.
4. **Resilient Fallback**: Telemetry and socket calls must never crash or freeze the UI. If stats are slow or unreachable, display dashes (`-`) gracefully.
