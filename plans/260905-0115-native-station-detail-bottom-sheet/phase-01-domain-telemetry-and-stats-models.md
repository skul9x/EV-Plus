# Phase 01: Domain Telemetry and 24h Stats Models
Status: 🟢 Completed  
Dependencies: None

## Objective
Define the domain models and parsers required for live station charging telemetry, rating metadata, available/total port counts per kW rating, forecast ticker sanitization (with locked prompt filtering), and 24h usage statistics calculation based on real EVCS curl captures.

## Requirements
### Functional
- [x] Define `StationAccessTokens` model (parsed from `POST /{slug}.html` with `X-Partial: user`):
  - `chargeToken: String`: Ephemeral token for `/charging` endpoint.
  - `apiToken: String`: Ephemeral token for Socket.io and `/update`.
  - `rating: StationRating?`: User review summary (`avg: Double`, `count: Int`, `mine: Int`).
  - `ratingCsrf: String?`: CSRF token for submitting star ratings.
  - `hasGo: Boolean`, `hasBiz: Boolean`: Package entitlements.
  - `historyToken7: String?`, `historyToken30: String?`: Long-range telemetry tokens.
- [x] Define `StationRating` model:
  - `avg: Double`: Average star rating (e.g. `5.0`).
  - `count: Int`: Total count of user ratings.
  - `mine: Int`: Current user's rating (or 0 if unrated).
- [x] Define `StationTelemetry` model (parsed from `POST /charging`):
  - `busyByKw: Map<Int, Int>`: Mapping of kW tier (e.g. 60, 120) to occupied car count.
  - `rawTicker: String?`: Raw HTML string returned by EVCS.
  - `cleanForecast: String?`: Sanitized human-readable text (e.g. `"Dự kiến 2 xe sạc trụ 120kW sẽ xong trong 1-7 phút, 1 xe sạc trụ 60kW sẽ xong trong 1 phút nữa"`).
  - `isLocked: Boolean`: `true` if ticker has `amd-locked` class or contains unlock prompt.
- [x] Define `StationPortStatus` model:
  - `kw: Int`: Rating tier (e.g. 30, 60, 120, 250).
  - `availablePorts: Int`: Count of vacant ports ready for charging (`max(0, totalPorts - busyCount)`).
  - `totalPorts: Int`: Total count of physical connectors installed.
  - `busyCount: Int`: Count of actively occupied connectors.
- [x] Define `Station24hStats` model:
  - `peakUsage: Int`: Maximum vehicles observed charging concurrently.
  - `avgUsage: Int`: Rounded average vehicle count (ceils to 1 if `0 < avg < 1`).
  - `peakHour: String`: Rush-hour interval in UTC+7 (e.g. `"17-18h"`, or `"-"` if none).
  - `fillRate: Int`: Percentage utilization relative to total station capacity (`0-100%`).
- [x] Implement `StationTelemetryParser`:
  - `parseAccessTokens(jsonString: String): Result<StationAccessTokens>`: Parses response from `POST /{slug}.html`.
  - `parseChargingResponse(jsonString: String): Result<StationTelemetry>`:
    - Parses `busyKw` map safely into `Map<Int, Int>`.
    - Detects `amd-locked` or promotional upsell strings (`"Xem dự báo cổng sạc trống của trạm"`) and guarantees `cleanForecast = null`.
    - If active forecast is present, strips all HTML tags (`<b>`, `<span>`, `<svg>`, `<button>`) while preserving Vietnamese diacritics, numbers, and punctuation.
  - `derivePortStatuses(connectors: String?, busyByKw: Map<Int, Int>): List<StationPortStatus>`:
    - Parses static port definitions from station model.
    - Matches kW against `busyByKw` to calculate `availablePorts = max(0, totalPorts - busyCount)`.

## Test Criteria (Single Comprehensive Test)
- **Test File**: `app/src/test/java/com/evcs/favorites/domain/StationTelemetryModelsAndParserTest.kt`
- **Verifications**:
  1. Correct parsing of `StationAccessTokens` from `curl_capture_20260905_005601` token response (including rating `avg=5.0, count=3`).
  2. Parsing of active charging payload from `curl_capture_20260905_005601`: `busyByKw = {60=4, 120=5}` and `cleanForecast = "Dự kiến 2 xe sạc trụ 120kW sẽ xong trong 1-7 phút, 1 xe sạc trụ 60kW sẽ xong trong 1 phút nữa"`.
  3. Parsing of locked charging payload from `curl_capture_20260905_005946`: `busyByKw = {}`, `isLocked = true`, and `cleanForecast = null`.
  4. Precise computation of `StationPortStatus` with `availablePorts = totalPorts - busyCount`.
  5. Immutability and data integrity of `Station24hStats`.

---
Next Phase: [phase-02-evcs-telemetry-repository-and-stats-calculator.md](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260905-0115-native-station-detail-bottom-sheet/phase-02-evcs-telemetry-repository-and-stats-calculator.md)

