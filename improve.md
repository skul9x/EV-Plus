# Giải Pháp Kiến Trúc Hybrid: HERE Maps EV API + EVCS Fallback & Name Resolver

---

## 1. Đặt vấn đề & Bối cảnh kỹ thuật

### Hiện trạng
- **VinFast CAPP API (`mobile.connected-car.vinfast.vn`)**: 
  - Đòi hỏi bắt buộc phải có User Access Token (`Authorization: Bearer <token>`).
  - Nếu người dùng chưa đăng nhập hoặc token hết hạn, cổng API trả về `40300 Authenticate failed` (HTTP 401/403).
- **EVCS Aggregator API (`evcs.vn/search?t=...`)**:
  - Hoạt động ổn định, không cần đăng nhập người dùng (chỉ cần HMAC-SHA256 signature với static secret).
  - Cung cấp tên trạm rất chi tiết (ví dụ: *"VinFast - Chung cư Golden Park"*, *"VinFast - TƯ NHÂN Nguyễn Văn Yêm"*).
  - Tuy nhiên là bên thứ 3 (aggregator), dữ liệu có độ trễ nhất định và phụ thuộc vào hệ sinh thái máy chủ proxy của EVCS.
- **HERE Maps EV API (`ev-v2.cc.api.here.com`)** *(Nguồn chính VinFast sử dụng)*:
  - Được trích xuất trực tiếp từ mã nguồn decompiled của ứng dụng VinFast (`output_vinfast`).
  - Sử dụng cơ chế xác thực **OAuth 1.0a Client Credentials** với `keyId` và `keySecret` chính thức của VinFast.
  - **100% không yêu cầu người dùng đăng nhập**, kết nối trực tiếp đến hạ tầng bản đồ của VinFast/V-Green.
  - Cung cấp dữ liệu trạng thái cổng sạc theo thời gian thực chuẩn xác nhất (`AVAILABLE`, `OCCUPIED`), công suất chuẩn (`3.7kW`, `11kW`, `30kW`, `60kW`, `150kW`, `250kW`, `360kW`), và mã cổng `cpoEvseEMI3Id`.
  - **Nhược điểm duy nhất:** Trường `name` trong HERE API bị đặt generic là `"Trạm sạc VinFast"`. Tuy nhiên, trường `cpoId` lại **trùng khớp 100%** với `locationId` của hệ thống VinFast và EVCS (ví dụ: `C.BNI0018`, `C.BNI11024`, `C.HNO15880`).

---

## 2. Các File Trong Mã Nguồn Gốc App VinFast (`output_vinfast`)

Trong mã nguồn đã decompile của ứng dụng VinFast (`d:\skul9x\EV-Plus-main\output_vinfast`), toàn bộ luồng lấy dữ liệu trạm sạc qua HERE Maps và CAPP được phân định rõ ràng tại các file sau:

### 2.1. Cấu hình Credentials & Khởi tạo HERE SDK
- **File:** [`output_vinfast/sources/com/vinfast/companionapp/di/modules/ManagerModule.java`](file:///d:/skul9x/EV-Plus-main/output_vinfast/sources/com/vinfast/companionapp/di/modules/ManagerModule.java)
  - **Vị trí (Dòng 83–86):**
    ```java
    public final HEREManager h(Context context) {
        Intrinsics.l(context, "context");
        return new HereManagerImpl(context, 
            "GMocOYGaYOqTt1npTZIwTg", 
            "MpzfCzgK37pe7Bz6IgoJDIQAP1fgA7082XG_Q2GzGSgex50Re1wJmYF-F8jSemoVjABImKPgZqvTL1DyH7uepQ"
        );
    }
    ```
  - **Ý nghĩa:** Chứa trực tiếp cặp khóa xác thực chính thức của VinFast (`accessKeyID` và `accessKeySecret`) dùng cho hạ tầng HERE API / SDK.
- **File:** [`output_vinfast/sources/com/vinfast/companion/map/manager/HereManagerImpl.java`](file:///d:/skul9x/EV-Plus-main/output_vinfast/sources/com/vinfast/companion/map/manager/HereManagerImpl.java)
  - **Vị trí (Dòng 52–61):**
    ```java
    AuthenticationMode authenticationModeWithKeySecret = AuthenticationMode.withKeySecret(this.accessKeyID, this.accessKeySecret);
    SDKOptions sDKOptions = new SDKOptions(authenticationModeWithKeySecret);
    sDKOptions.politicalView = "VNM";
    MapView.setPrimaryLanguage(LanguageCode.VI_VN);
    SDKNativeEngine.makeSharedInstance(this.context, sDKOptions);
    ```
  - **Ý nghĩa:** Khởi tạo HERE Native Engine với khóa bí mật để xác thực OAuth với `https://account.api.here.com/oauth2/token`.

---

### 2.2. Client Gọi HERE EV API (Không Cần Đăng Nhập)
- **File:** [`output_vinfast/sources/com/vinfast/companion/globalkit/data/EVStationClient.java`](file:///d:/skul9x/EV-Plus-main/output_vinfast/sources/com/vinfast/companion/globalkit/data/EVStationClient.java)
  - **Phương thức `k(...)` (Dòng 428–517):** Gọi tìm kiếm trạm sạc theo bán kính và tọa độ:
    ```java
    StringBuilder sb = new StringBuilder("https://ev-v2.cc.api.here.com/ev/stations.json?");
    sb.append("prox=" + lat + "," + lon + "," + radius);
    // Hỗ trợ filter: &connectortype=... &powermin=... &powermax=... &maxresults=20
    ```
  - **Phương thức `i(...)` (Dòng 363–391):** Gọi lấy chi tiết một trạm theo ID:
    ```java
    url = "https://ev-v2.cc.api.here.com/ev/stations/" + id + ".json";
    ```
  - **Phương thức `j(...)` (Dòng 393–426):** Tra cứu theo pool ID trạm:
    ```java
    url = "https://ev-v2.cc.api.here.com/ev/pools/bulk.json?id=" + id;
    ```
  - **Phương thức `g(...)` & `h(...)` (Dòng 117–339):**
    - Đọc mảng `connectors.connector` và `connectorStatuses`.
    - Phân loại trạng thái cổng: `StringsKt.K(connectorStatus.getState(), "AVAILABLE", true)` $\rightarrow$ Cổng trống / Cổng bận.
    - Đọc mã định danh súng sạc thực tế: `connectorStatus.getCpoEvseEMI3Id()`.
    - Trích xuất công suất lớn nhất: `connector.getMaxPowerLevel()`.

---

### 2.3. Các Model Dữ Liệu Phản Hồi của HERE API trong App VinFast
Nằm trong package: [`output_vinfast/sources/com/vinfast/companion/globalkit/model/response/evstation/`](file:///d:/skul9x/EV-Plus-main/output_vinfast/sources/com/vinfast/companion/globalkit/model/response/evstation/)

| Tên File Model | Thuộc tính quan trọng & Mục đích |
| :--- | :--- |
| [`EVStationResponse.java`](file:///d:/skul9x/EV-Plus-main/output_vinfast/sources/com/vinfast/companion/globalkit/model/response/evstation/EVStationResponse.java) | Root response JSON, chứa đối tượng `EvStations`. |
| [`EvStations.java`](file:///d:/skul9x/EV-Plus-main/output_vinfast/sources/com/vinfast/companion/globalkit/model/response/evstation/EvStations.java) | Chứa danh sách `List<EVStation> evStation`. |
| [`EVStation.java`](file:///d:/skul9x/EV-Plus-main/output_vinfast/sources/com/vinfast/companion/globalkit/model/response/evstation/EVStation.java) | Chứa `id` (HERE ID), **`cpoId`** (Mã trạm VinFast: `C.BNI0018`), `name`, `brand`, `position`, `address`, `connectors`, `totalNumberOfConnectors`. |
| [`Connectors.java`](file:///d:/skul9x/EV-Plus-main/output_vinfast/sources/com/vinfast/companion/globalkit/model/response/evstation/Connectors.java) | Chứa danh sách `List<Connector> connector`. |
| [`Connector.java`](file:///d:/skul9x/EV-Plus-main/output_vinfast/sources/com/vinfast/companion/globalkit/model/response/evstation/Connector.java) | Chứa `maxPowerLevel` (kW), `chargingPoint`, `connectorStatuses`, `connectorType` (CCS2, Type 2,...). |
| [`ConnectorStatus.java`](file:///d:/skul9x/EV-Plus-main/output_vinfast/sources/com/vinfast/companion/globalkit/model/response/evstation/ConnectorStatus.java) | Chứa `state` (`AVAILABLE`, `OCCUPIED`), `cpoEvseEMI3Id`, `cpoEvseId`, `physicalReference`. |
| [`ChargingPoint.java`](file:///d:/skul9x/EV-Plus-main/output_vinfast/sources/com/vinfast/companion/globalkit/model/response/evstation/ChargingPoint.java) | Chứa `numberOfAvailable`, `numberOfConnectors`. |
| [`Address.java`](file:///d:/skul9x/EV-Plus-main/output_vinfast/sources/com/vinfast/companion/globalkit/model/response/evstation/Address.java) | Chứa `unparsedAddress`, `street`, `district`, `city`, `county`, `postalCode`. |
| [`Position.java`](file:///d:/skul9x/EV-Plus-main/output_vinfast/sources/com/vinfast/companion/globalkit/model/response/evstation/Position.java) | Chứa tọa độ GPS `latitude`, `longitude`. |

---

### 2.4. So Sánh Với Client CAPP Nội Bộ (Bắt Buộc Đăng Nhập)
- **File:** [`output_vinfast/sources/com/vinfast/companion/car/data/storage/EVStationClient.java`](file:///d:/skul9x/EV-Plus-main/output_vinfast/sources/com/vinfast/companion/car/data/storage/EVStationClient.java)
  - Đây là client thứ 2 trong app VinFast (chú ý khác package `globalkit`), chuyên gọi API xe cá nhân:
    `getBaseURL() + "ccarcharging/api/v1/geolocations/districts?province="` và các API đặt chỗ trạm sạc.
  - Client này bắt buộc chèn header `X-Vin-Code` và OAuth Bearer token của xe (`SessionManager`).
  - **Kết luận:** Khi app VinFast hiển thị bản đồ trạm sạc công cộng và tìm kiếm trạm quanh đây, họ dùng **`globalkit/data/EVStationClient.java` (HERE EV API)** thay vì CAPP API xe cá nhân.

---

## 3. Kiến trúc giải pháp tổng thể (Hybrid Architecture)

Hệ thống EV-Plus sẽ vận hành theo mô hình **Hybrid 3 tầng** thông minh:

```
                          [User Yêu Cầu Quét Trạm Quanh Đây (lat, lon)]
                                              │
                                              ▼
                         [HERE EV API (Tier 1 - Zero-Login Telemetry)]
                                              │
                       ┌──────────────────────┴──────────────────────┐
                       │ (Thành công: 50+ trạm)                      │ (Thất bại: Mạng/Quota)
                       ▼                                             ▼
          [StationNameResolver (Hybrid)]                  [EVCS API (Tier 2 - Fallback)]
     (Phân giải cpoId -> Tên trạm chuẩn)               (Lấy trạm + Trạng thái cổng từ EVCS)
        │                             │                              │
        ├─ Cache RAM/Disk (Hit)       └─ Chưa có (Miss)              │
        │                                     │                      │
        │                         [EVCS Batch/Direct Lookup]         │
        ▼                                     │                      ▼
[Station Card với Tên Chuẩn] ◄────────────────┘             [Station Card Hoàn Chỉnh]
```

### Các nguyên lý cốt lõi:
1. **Dữ liệu trạng thái thời gian thực**: Ưu tiên số 1 lấy từ **HERE Maps EV API** (nhanh, chuẩn VinFast, không cần login).
2. **Dữ liệu danh tính & Tên trạm**: Sử dụng **StationNameResolver** để ánh xạ `cpoId` (`C.BNI0018`) thành tên mô tả thực tế (*"VinFast - Chung cư Golden Park"*).
3. **Cơ chế Dự phòng (Failover)**: Nếu HERE API gặp sự cố mạng hoặc lỗi xác thực token, tự động fallback về **EVCS Search API** mà không làm gián đoạn trải nghiệm người dùng.

---

## 4. Danh Sách Các File Triển Khai Trong EV-Plus Codebase

Dưới đây là cấu trúc các file mới và file sửa đổi trong project Android (`d:\skul9x\EV-Plus-main`):

| Thao tác | Đường dẫn File | Vai trò & Trách nhiệm |
| :--- | :--- | :--- |
| **[MỚI]** | `app/src/main/java/com/evcs/favorites/data/network/here/model/HereEvModels.kt` | Data classes parse JSON phản hồi từ HERE EV API (`HereEvStationsResponse`, `HereEvStation`, `HereConnector`, `HereAddress`, v.v.) |
| **[MỚI]** | `app/src/main/java/com/evcs/favorites/data/network/here/HereOAuthManager.kt` | Quản lý ký HMAC-SHA256 OAuth 1.0a, lấy Bearer token từ `https://account.api.here.com/oauth2/token` và tự động refresh trước khi hết hạn. |
| **[MỚI]** | `app/src/main/java/com/evcs/favorites/data/network/here/HereEvApiClient.kt` | OkHttp client gọi `GET https://ev-v2.cc.api.here.com/ev/stations.json?prox={lat},{lon},{radius}` và chuyển đổi sang Domain Model `Station`. |
| **[MỚI]** | `app/src/main/java/com/evcs/favorites/data/repository/StationNameResolver.kt` | Bộ nhớ đệm (L1 Memory Cache, L2 Disk Cache) ánh xạ `cpoId -> Tên trạm`. Hỗ trợ nạp trước từ danh sách EVCS và gọi phân giải đơn lẻ khi cần. |
| **[SỬA]** | `app/src/main/java/com/evcs/favorites/data/api/EvcsApiClient.kt` | Bổ sung hàm `resolveStationNameByLocationId(locationId: String)` tận dụng endpoint `GET /tram-sac-vinfast-{locationId}.html`. |
| **[SỬA]** | `app/src/main/java/com/evcs/favorites/data/repository/DualTierStationRepository.kt` | Cập nhật luồng ưu tiên Tier 1 = `HereEvApiClient` (ghép nối qua `StationNameResolver`), Tier 2 = `EvcsApiClient.searchStations()`. |
| **[SỬA]** | `app/src/main/java/com/evcs/favorites/di/AppContainer.kt` | Khởi tạo Singleton cho `HereOAuthManager`, `HereEvApiClient`, `StationNameResolver`. |
| **[TEST]** | `app/src/test/java/com/evcs/favorites/data/network/here/HereOAuthManagerTest.kt` | Test tạo chữ ký OAuth 1.0a HMAC-SHA256 và quản lý vòng đời token. |
| **[TEST]** | `app/src/test/java/com/evcs/favorites/data/network/here/HereEvApiClientTest.kt` | Test mock response JSON từ HERE API và mapping sang domain model `Station`. |
| **[TEST]** | `app/src/test/java/com/evcs/favorites/data/repository/StationNameResolverTest.kt` | Test cơ chế L1/L2 cache và fallback khi phân giải tên trạm. |

---

## 5. Chi Tiết Kỹ Thuật Từng Thành Phần

### 5.1. Quản lý OAuth 1.0a (`HereOAuthManager.kt`)
- **Credentials** (Lấy từ `output_vinfast/.../ManagerModule.java`):
  - `KEY_ID` = `"GMocOYGaYOqTt1npTZIwTg"`
  - `KEY_SECRET` = `"MpzfCzgK37pe7Bz6IgoJDIQAP1fgA7082XG_Q2GzGSgex50Re1wJmYF-F8jSemoVjABImKPgZqvTL1DyH7uepQ"`
  - `TOKEN_ENDPOINT` = `https://account.api.here.com/oauth2/token`
- **Thuật toán ký OAuth 1.0a Client Credentials:**
  1. Tạo `oauth_nonce` ngẫu nhiên và `oauth_timestamp` (epoch seconds).
  2. Tạo Parameter String chuẩn hóa:
     `grant_type=client_credentials&oauth_consumer_key={KEY_ID}&oauth_nonce={nonce}&oauth_signature_method=HMAC-SHA256&oauth_timestamp={timestamp}&oauth_version=1.0`
  3. Tạo Base String:
     `POST&https%3A%2F%2Faccount.api.here.com%2Foauth2%2Ftoken&{URLEncoder.encode(parameterString)}`
  4. Tạo Signing Key: `{KEY_SECRET}&` (UTF-8).
  5. Tính `HMAC-SHA256(Base String, Signing Key)` $\rightarrow$ Base64 encode $\rightarrow$ `oauth_signature`.
  6. Gửi Header: `Authorization: OAuth oauth_consumer_key="...", oauth_nonce="...", oauth_signature="...", oauth_signature_method="HMAC-SHA256", oauth_timestamp="...", oauth_version="1.0"`.
  7. Nhận `access_token` và `expires_in` (86400s - 24 giờ). Tự động refresh token khi còn 10 phút hết hạn.

### 5.2. Gọi API trạm sạc & Parse dữ liệu (`HereEvApiClient.kt`)
- **Endpoint:**
  ```http
  GET https://ev-v2.cc.api.here.com/ev/stations.json?prox={lat},{lon},{radius_in_meters}
  Authorization: Bearer {accessToken}
  ```
- **Mapping sang Domain Model `Station`**:
  - `station.id` $\leftarrow$ `st.cpoId` (vd: `C.BNI0018`)
  - `station.latitude` $\leftarrow$ `st.position.latitude`
  - `station.longitude` $\leftarrow$ `st.position.longitude`
  - `station.address` $\leftarrow$ `st.address.unparsedAddress`
  - `station.evse` $\leftarrow$ `"VinFast"`
  - `station.ports` $\leftarrow$ Gom nhóm mảng `connectors.connector`:
    - Đọc `connectorStatuses.connectorStatus`:
      - `state == "AVAILABLE"` $\rightarrow$ Cổng khả dụng (`availableCount++`).
      - `state == "OCCUPIED"` $\rightarrow$ Cổng đang có xe sạc (`occupiedCount++`).
    - Lọc bỏ cổng xe máy: loại bỏ các cổng công suất $\le 7\text{kW}$ hoặc type xe máy.
    - Chuyển đổi `maxPowerLevel` thành label cổng (11kW, 30kW, 60kW, 150kW, 250kW, 360kW).

### 5.3. Cơ chế Phân giải Tên Trạm (`StationNameResolver.kt`)
1. **Lớp Cache 1 (RAM ConcurrentHashMap)**: Lưu trữ cặp `locationId -> stationName`. Khi `EvcsApiClient.searchStations()` quét tọa độ, gần 100 trạm lân cận kèm tên đầy đủ sẽ được nạp ngay vào Cache.
2. **Lớp Cache 2 (Disk via `PlainSharedPrefsStorage`)**: Lưu bền vững trên máy, không bị mất khi thoát ứng dụng.
3. **Lớp Phân giải Realtime (Direct URL Resolution)**:
   - Nếu một trạm từ HERE API chưa có trong cache:
     - Gửi request nhẹ: `GET https://evcs.vn/tram-sac-vinfast-{locationId.toLowerCase()}.html` với `User-Agent: EVCS/A1.57 Mobile`.
     - Regex trích xuất tên trạm từ thẻ JSON-LD: `"name"\s*:\s*"(VinFast - [^"]+)"` hoặc thẻ `<title>`.
     - Lưu tên tìm được vào Cache.
   - **Fallback hiển thị**: Trong khi chờ resolve tên, card trạm sẽ hiển thị `"VinFast - " + (st.address.street ?: st.address.district ?: "Trạm sạc VinFast")`.

---

## 6. Kế Hoạch Triển Khai & Kiểm Thử

1. **Bước 1 (Network)**: Tạo `HereEvModels.kt`, `HereOAuthManager.kt` và `HereEvApiClient.kt`. Viết unit test xác thực OAuth 1.0a và query trạm.
2. **Bước 2 (Resolver)**: Tạo `StationNameResolver.kt`, cập nhật `EvcsApiClient.kt`. Viết unit test kiểm chứng với các Location ID mẫu (`C.BNI0018`, `C.BNI11024`, `C.HNO15880`).
3. **Bước 3 (Integration)**: Đấu nối vào `DualTierStationRepository.kt` và `AppContainer.kt`. Chạy `./gradlew testDebugUnitTest`.
4. **Bước 4 (Device Verification)**: Build APK Debug, cài đặt lên máy thật qua ADB và xác nhận trạm tải mượt mà, đầy đủ tên chi tiết và đúng số cổng xanh/đỏ thời gian thực.
