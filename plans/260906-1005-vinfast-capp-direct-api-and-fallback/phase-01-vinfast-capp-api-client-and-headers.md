# Phase 01: VinFast CAPP API Client & Headers

Status: ✅ Completed  
Dependencies: None  
Reference Sources:
- `output_vinfast/sources/vn/vinfast/chargingkit/datasources/remote/api/ChargingApiClient.java`
- `output_vinfast/sources/vn/vinfast/chargingkit/datasources/remote/models/RemoteChargingStationsStatus.java`
- `output_vinfast/sources/vn/vinfast/chargingkit/datasources/remote/models/request/RequestSearchChargingStation.java`
- `output_vinfast/sources/vn/vinfast/chargingkit/repositories/request/ChargingStationsStatusRequest.java`
- `output_vinfast/sources/com/vinfast/companion/corenetwork/interceptor/TokenInterceptor.java`

---

## 1. Objective

Establish the foundational networking layer to communicate directly with VinFast's Connected Car (CAPP) charging station endpoints:
- `POST /ccarcharging/api/v1/stations/search?page={page}&size={size}`
- `POST /ccarcharging/api/v1/stations/location-info`

This client injects official VinFast CAPP client identity headers, handles optional Bearer authorization (supporting guest mode), separates URL query parameters from JSON body per VinFast APK specifications, enforces a strict 5-second timeout for fast failover to Tier-2 (`evcs.vn`), and reuses the shared OkHttp connection pool via `AppOkHttpClientProvider`.

---

## 2. Requirements

### Functional Requirements
1. **Endpoint Contracts & Parameter Separation**:
   - `POST /ccarcharging/api/v1/stations/search?page={page}&size={size}`:
     - `page` (default 0) and `size` (default 50) **must be passed as URL query parameters** (matching `ChargingApiClient.m`).
     - JSON Request Body (`VinFastSearchRequest` matching `RequestSearchChargingStation.java`):
       - `latitude: Double?`
       - `longitude: Double?`
       - `province: String? = null`
       - `district: String? = null`
       - `wattageTypes: List<String>? = null`
       - `parkingFee: Boolean? = null`
       - `freeParking: Boolean? = null`
       - `excludeFavorite: Boolean? = null`
   - `POST /ccarcharging/api/v1/stations/location-info`:
     - JSON Request Body (`VinFastLocationInfoRequest` matching `ChargingStationsStatusRequest.java`):
       - `locationIds: List<String>`
2. **CAPP Header Generation (`VinFastHeaderInterceptor`)**:
   - Matches production `TokenInterceptor.java` in VinFast Android APK:
     - `X-APP-VERSION`: `2.25.7` (production baseline).
     - `X-SERVICE-NAME`: `CAPP`.
     - `X-Device-Platform`: `android`.
     - `X-Device-Family`: `Build.MODEL` (or device name fallback).
     - `X-Device-Identifier`: Persistent unique client UUID stored locally.
     - `X-Device-Locale`: `Locale.getDefault().toString()` (e.g. `vi_VN`).
     - `X-Device-OS-Version`: `"android " + Build.VERSION.RELEASE`.
     - `X-TIMESTAMP`: `System.currentTimeMillis().toString()`.
     - `X-Timezone`: `TimeZone.getDefault().id` (e.g. `Asia/Ho_Chi_Minh`).
     - `User-Agent`: `"android - " + deviceId + " - 2.25.7"`.
     - `Content-Type`: `application/json; charset=UTF-8`.
     - `Accept`: `application/json`.
     - `Authorization`: Optional `Bearer <token>` provider (if configured; omitted in guest mode).
3. **Data Transfer Objects (DTOs)**:
   - Defined using `kotlinx.serialization` mirroring VinFast's JSON schema:
     - `VinFastBaseResponse<T>` (`code: Int? = null`, `message: String? = null`, `data: T? = null`, `metadata: JsonElement? = null`)
     - `VinFastSearchRequest`
     - `VinFastLocationInfoRequest`
     - `VinFastStationStatusDto` (`locationId: String?`, `stationName: String?`, `stationAddress: String?`, `hereId: String?`, `latitude: Double?`, `longitude: Double?`, `numberOfAvailableEvse: Int?`, `totalEvse: Int?`, `connectors: List<VinFastConnectorDto>?`, `images: List<VinFastImageDto>?`, `isPublic: Boolean?`, `isFreeParking: Boolean?`, `isInWorkingTime: Boolean?`, `workingTimeDescription: String?`, `depotStatus: String?`)
     - `VinFastConnectorDto` (`type: Int?`, `status: String?`, `count: Int?`, `total: Int?`, `isLink: Boolean?`, `powerType: String?`)
     - `VinFastImageDto` (`url: String?`, `thumbnail: String?`)
4. **Resilient Error Handling (`VinFastApiException`)**:
   - Handles HTTP 401 Unauthorized (`code: 40300`, "Authenticate failed"), HTTP 403 Forbidden, HTTP 500/503, and network timeouts.
   - Encapsulates errors cleanly in `Result.failure(VinFastApiException)` without application crash, enabling instant degradation to Tier 2.

### Non-Functional & Performance Requirements
- Built via `AppOkHttpClientProvider.newSharedClientBuilder()` to share `ConnectionPool` and worker `Dispatcher` (satisfying `PERF-NET-01`).
- Fast-fail timeout: 5s connect / 5s read to guarantee immediate failover to Tier 2 without perceptible UI stutter.
- Shared `Json` configuration with `ignoreUnknownKeys = true`, `isLenient = true`, `encodeDefaults = false` (satisfying `PERF-CPU-02`).
- Clean package isolation under `com.evcs.favorites.data.network.vinfast`.

---

## 3. Implementation Steps

1. **Create DTOs & Exception**:
   - Path: `app/src/main/java/com/evcs/favorites/data/network/vinfast/VinFastStationDtos.kt`.
   - Implement `VinFastBaseResponse<T>`, `VinFastSearchRequest`, `VinFastLocationInfoRequest`, `VinFastStationStatusDto`, `VinFastConnectorDto`, `VinFastImageDto`, and `VinFastApiException`.
2. **Create Header Interceptor**:
   - Path: `app/src/main/java/com/evcs/favorites/data/network/vinfast/VinFastHeaderInterceptor.kt`.
   - Implement `okhttp3.Interceptor` injecting all 10 standard CAPP headers and persistent UUID.
3. **Implement VinFastCAppApiClient**:
   - Path: `app/src/main/java/com/evcs/favorites/data/network/vinfast/VinFastCAppApiClient.kt`.
   - Suspend methods:
     - `suspend fun searchStations(lat: Double, lon: Double, page: Int = 0, size: Int = 50): Result<List<VinFastStationStatusDto>>`
     - `suspend fun getLocationInfo(locationIds: List<String>): Result<List<VinFastStationStatusDto>>`

---

## 4. Files to Create / Modify

- **New Files**:
  - `app/src/main/java/com/evcs/favorites/data/network/vinfast/VinFastStationDtos.kt`
  - `app/src/main/java/com/evcs/favorites/data/network/vinfast/VinFastHeaderInterceptor.kt`
  - `app/src/main/java/com/evcs/favorites/data/network/vinfast/VinFastCAppApiClient.kt`
- **Test File**:
  - `app/src/test/java/com/evcs/favorites/VinFastCAppApiClientTest.kt`

---

## 5. File-Based Test Plan (`VinFastCAppApiClientTest.kt`)

Create `app/src/test/java/com/evcs/favorites/VinFastCAppApiClientTest.kt` using MockWebServer:
1. `testHeaderAndQueryParamInjection()`:
   - Enqueue a dummy 200 OK response: `{"code":200,"message":"Success","data":[]}`.
   - Execute `searchStations(lat = 21.0285, lon = 105.8542, page = 0, size = 50)`.
   - Inspect `RecordedRequest`:
     - Assert `request.path.contains("?page=0&size=50")`.
     - Assert `request.getHeader("X-APP-VERSION") == "2.25.7"`.
     - Assert `request.getHeader("X-SERVICE-NAME") == "CAPP"`.
     - Assert `request.getHeader("X-Device-Platform") == "android"`.
     - Assert `request.getHeader("X-Device-Identifier")` is not blank.
     - Assert body contains `"latitude":21.0285` and `"longitude":105.8542` and does NOT contain `page` or `size`.
2. `testSuccessfulStationParsing()`:
   - Mock realistic VinFast JSON payload matching `RemoteChargingStationsStatus`.
   - Execute `searchStations`.
   - Verify `Result.isSuccess == true`, station list size > 0, correct `locationId` and connector counts.
3. `testLocationInfoEndpoint()`:
   - Enqueue 200 OK for `/ccarcharging/api/v1/stations/location-info`.
   - Execute `getLocationInfo(listOf("C.HNO11417", "C.HNO11418"))`.
   - Inspect `RecordedRequest`: body contains `{"locationIds":["C.HNO11417","C.HNO11418"]}`.
   - Verify `Result.isSuccess == true`.
4. `testHttp401UnauthorizedReturnsFailure()`:
   - Mock HTTP 401 response: `{"code":40300,"message":"Authenticate failed","data":null}`.
   - Verify `Result.isFailure == true` and exception is `VinFastApiException`.
5. `testNetworkTimeoutFastFail()`:
   - Configure MockWebServer with delay exceeding client timeout (e.g. 7s delay with 5s timeout).
   - Verify timeout aborts cleanly within 5 seconds without blocking the calling thread.

---
Next Phase: [Phase 02: DTO Mapping & EV Car Port Filtering](./phase-02-dto-mapping-and-ev-car-port-filtering.md)
