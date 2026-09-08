# PHÂN TÍCH CHI TIẾT THUẬT TOÁN VÀ CƠ CHẾ:
## "LẬP LỘ TRÌNH SẠC PIN ĐƯỜNG DÀI & LƯU TRẠM SẠC THƯỜNG DÙNG"

Dự án **Trạm EV (`com.aydada.estations.driver`)** là ứng dụng di động dành cho tài xế xe điện tại Việt Nam, xây dựng trên kiến trúc lai (**React Native + Native Android Auto / Car App Library + SQLite Offline Cache + Firebase / Cloud API**). 

Tài liệu này phân tích chi tiết thuật toán, cấu trúc dữ liệu, luồng xử lý và mã nguồn thực tế của hai tính năng cốt lõi:
1. **Lập lộ trình sạc pin khi đi đường dài (Long-distance Route Planning & Charging Corridor)**
2. **Lưu các trạm thường dùng & Cảnh báo vùng địa lý (Favorite / Bookmark Management & Geofencing)**

---

## PHẦN I: THUẬT TOÁN "LẬP LỘ TRÌNH SẠC PIN KHI ĐI ĐƯỜNG DÀI"

### 1. Kiến trúc luồng xử lý lộ trình (Route Planning Pipeline)

```
[Người dùng nhập Điểm đi / Điểm đến / Điểm dừng]
                   │
                   ▼
       [Trip API V3 / Routing Engine]
                   │
                   ├─► Trả về Routes, Polylines, Khoảng cách, Thời gian
                   ▼
    [Polyline Decoder & Segment Analyzer]
                   │
                   ▼
[Thuật toán Corridor Search: filterStationsAlongRoute]
  - Lọc trạm sạc trong bán kính hành lang tuyến đường
  - Lọc theo loại cổng (AC/DC), công suất (kW), giá, trạng thái
                   │
                   ▼
 [Chọn trạm dừng sạc (Custom Stops) & Recalculate]
  - Tái tính toán lộ trình tối ưu qua các trạm sạc đã chọn
                   │
                   ├─► Lưu hành trình cá nhân (Save Trip V3 API)
                   ├─► Chia sẻ cộng đồng (SharedRouteService)
                   ▼
[Đồng bộ CarPlatformBridge ──► Native Android Auto / CarPlay]
  - RouteScreen hiển thị danh sách trạm trên màn hình ô tô
  - StationStatusFetcher tự động poll trạng thái súng sạc real-time
```

---

### 2. Thuật toán lọc trạm sạc dọc hành lang tuyến đường (`filterStationsAlongRoute`)

Khi có lộ trình giữa điểm xuất phát (Origin) và điểm đến (Destination), ứng dụng không quét toàn bộ bản đồ mà áp dụng thuật toán tìm kiếm theo **hành lang tuyến đường (Corridor Buffer Algorithm)**:

#### Bước 2.1: Giải mã Polyline
Tuyến đường từ Routing Engine trả về chuỗi nén Polyline được giải mã thành tập hợp các điểm tọa độ liên tiếp:
$$\mathcal{P} = \{ P_1, P_2, P_3, \dots, P_n \} \quad \text{với } P_i = (\text{lat}_i, \text{lng}_i)$$

#### Bước 2.2: Tính khoảng cách trắc địa bằng công thức Haversine
Để tính khoảng cách giữa 2 điểm bất kỳ trên bề mặt Trái Đất:
$$d(A, B) = 2R \cdot \arcsin \left( \sqrt{\sin^2\left(\frac{\Delta \varphi}{2}\right) + \cos(\varphi_A)\cos(\varphi_B)\sin^2\left(\frac{\Delta \lambda}{2}\right)} \right)$$
*Trong đó:*
* $R = 6371 \text{ km}$ (bán kính Trái Đất)
* $\Delta \varphi = \varphi_B - \varphi_A$ (chênh lệch vĩ độ tính theo radian)
* $\Delta \lambda = \lambda_B - \lambda_A$ (chênh lệch kinh độ tính theo radian)

#### Bước 2.3: Lọc điểm theo khoảng cách vuông góc tối thiểu tới đoạn thẳng (Cross-Track Distance)
Với mỗi trạm sạc $S$ trong database, hệ thống tính khoảng cách tối thiểu từ $S$ tới các đoạn thẳng $P_i P_{i+1}$ trên Polyline:
$$d(S, \mathcal{P}) = \min_{1 \le i < n} \text{dist\_to\_segment}(S, P_i, P_{i+1})$$

Nếu $d(S, \mathcal{P}) \le D_{\text{buffer}}$ (bán kính lệch đường cho phép, thường từ $1 \text{ km} - 5 \text{ km}$ tùy cấu hình), trạm sạc được đưa vào danh sách ứng viên (Candidate Stations along Route).

---

### 3. Bộ lọc tương thích thông số kỹ thuật xe điện

Các trạm nằm trong hành lang tiếp tục được lọc qua các tiêu chí kỹ thuật:

```
                          [Tất cả trạm trong hành lang]
                                       │
                ┌──────────────────────┴──────────────────────┐
                ▼                                             ▼
       [Mạng lưới VinFast]                           [Mạng lưới Ngoài VinFast]
  (V-Green / VinFast Stations)                  (Trạm sạc độc lập / Đối tác thứ ba)
                │                                             │
                └──────────────────────┬──────────────────────┘
                                       ▼
                       [Lọc theo Chuẩn Cổng Sạc]
           - Type 2 (AC)
           - CCS2 / CCS1 (DC Fast)
           - GB/T (DC cho xe nhập khẩu)
           - NACS / Tesla Supercharger / HPWC
           - CHAdeMO
                                       ▼
                       [Lọc theo Cấp Công Suất]
           - AC (Chậm / Tiêu chuẩn)
           - DC 20 kW – 60 kW
           - DC 60 kW – 120 kW
           - DC 120 kW – 180 kW
           - DC > 180 kW (Siêu nhanh)
                                       ▼
                       [Lọc theo Mức Giá & Tình Trạng]
           - Khoảng giá sạc (< 6.000đ, 6.000–7.000đ, > 7.000đ/kWh)
           - Bỏ qua trạm đang bảo trì (`isMaintenance == false`)
```

---

### 4. Thuật toán tái tính toán lộ trình động khi chọn trạm sạc (Dynamic Recalculation)

Khi người dùng chọn thêm một hoặc nhiều trạm sạc vào hành trình:
1. **Chèn Waypoint:** Trạm sạc được chèn vào mảng các điểm dừng `customStops = [W_1, W_2, ...]`.
2. **Kích hoạt Recalculation:** `[RouteMapScreen] ===== RECALCULATING ROUTE =====`
3. **Phân đoạn màu sắc (Colored Segments):** Đoạn đường được chia nhỏ thành các chặng (Legs: Origin $\rightarrow W_1 \rightarrow W_2 \rightarrow \dots \rightarrow$ Destination), mã hóa màu sắc hiển thị trên bản đồ để tài xế phân biệt từng chặng pin.
4. **Cập nhật danh sách trạm theo từng chặng:** Tự động load lại các trạm sạc dự phòng quanh từng trạm dừng chính.

---

### 5. Cơ chế đồng bộ lộ trình xuống màn hình ô tô (Android Auto / CarPlay)

1. Khi người dùng thiết lập hoặc lưu lộ trình trên điện thoại, React Native gọi qua Native Bridge:
   ```java
   CarPlatformBridgeModule.syncRouteStations(ReadableArray stationIds, String routeName)
   ```
2. `StationDataStore` trên Native Android tiếp nhận:
   ```java
   public final void setRouteStations(List<String> ids, String str) {
       this.routeStationIds = ids;
       this.activeRouteName = str;
   }
   ```
3. Màn hình ô tô (`RouteScreen`) tự động nhận diện lộ trình đang kích hoạt qua `getHasActiveRoute()`:
   - Query toàn bộ thông tin trạm sạc trên lộ trình từ SQLite offline (`loadStationsFromSQLite`).
   - Tính toán lại khoảng cách từ vị trí GPS xe hiện tại tới từng trạm sạc trên tuyến đường.
   - Giới hạn số lượng hiển thị phù hợp với quy chuẩn an toàn lái xe của Google (`HostConstraintsKt.listLimit`).
   - Bắt đầu chu kỳ Polling tự động (`StationStatusFetcher.startPolling`) để tài xế biết trước trụ nào đang trống trước khi lái xe đến.

---

## PHẦN II: THUẬT TOÁN "LƯU TRẠM THƯỜNG DÙNG & HÀNG RÀO ĐỊA LÝ"

### 1. Kiến trúc lưu trữ đa tầng (Multi-tier Bookmark Storage)

Dự án áp dụng cơ chế lưu trữ 4 tầng để tối ưu tốc độ và hỗ trợ hoạt động hoàn toàn offline:

```
┌─────────────────────────────────────────────────────────────┐
│ 1. Cloud Layer (Firebase DataConnect / AuthApiService)      │
│    - Lưu Bookmark theo User ID, đồng bộ đa thiết bị         │
└──────────────────────────────┬──────────────────────────────┘
                               │ Sync on Login / Update
┌──────────────────────────────▼──────────────────────────────┐
│ 2. React Native JS Layer (State & AsyncStorage)             │
│    - Quản lý tương tác UI (nút bookmark trên App)           │
└──────────────────────────────┬──────────────────────────────┘
                               │ Native Bridge: syncBookmarks(ids)
┌──────────────────────────────▼──────────────────────────────┐
│ 3. Native SharedPreferences (carplay_prefs)                 │
│    - Lưu Set<String> (carplay_bookmark_ids_set)             │
│    - Tự động Migrate từ dạng chuỗi cũ (carplay_bookmark_ids)│
└──────────────────────────────┬──────────────────────────────┘
                               │ Query Details by IDs
┌──────────────────────────────▼──────────────────────────────┐
│ 4. Local SQLite Cache (estations_cache_v3.db)               │
│    - Bảng stations: id, name, lat, lng, connectors, price...│
└─────────────────────────────────────────────────────────────┘
```

---

### 2. Thuật toán sắp xếp trạm yêu thích theo khoảng cách (`getBookmarkedStations`)

Khi tài xế mở danh sách trạm yêu thích (trên điện thoại hoặc màn hình Android Auto):
1. Đọc danh sách ID từ `BookmarkStore`:
   ```kotlin
   val bookmarkIds = BookmarkStore.Companion.getInstance(context).getBookmarkIds()
   ```
2. Query dữ liệu chi tiết của toàn bộ trạm trong danh sách từ SQLite bằng mệnh đề `IN (...)`:
   ```sql
   SELECT id, name, address, lat, lng, provider_logo, provider_app, flags, connectors, simple_price, station_type
   FROM stations
   WHERE id IN (?, ?, ...) AND station_type IN ('vinfast', 'nonvinfast')
   ```
3. Tính khoảng cách từ vị trí hiện tại $(lat_{curr}, lng_{curr})$ đến từng trạm bằng hàm hệ thống Android `Location.distanceBetween`:
   ```kotlin
   station.distanceKm = calculateDistance(currentLat, currentLng, station.latitude, station.longitude)
   ```
4. Sắp xếp danh sách trạm tăng dần theo khoảng cách (`sortedBy { it.distanceKm }`), đưa trạm gần tài xế nhất lên đầu.

---

### 3. Thuật toán Hàng rào địa lý cảnh báo khi đến gần trạm (`GeofenceManager`)

Để nhắc nhở tài xế sạc pin khi đến gần trạm quen thuộc mà không làm hao pin điện thoại:

```
[Vị trí GPS xe thay đổi / Danh sách Bookmark thay đổi]
                   │
                   ▼
[Lấy Top 15 Trạm Yêu Thích Gần Nhất (MAX_GEOFENCES = 15)]
                   │
                   ▼
[Thiết lập Hàng rào bán kính 500m (GEOFENCE_RADIUS = 500.0f)]
  - Transition: GEOFENCE_TRANSITION_ENTER (1)
  - Expiration: NEVER (-1)
  - Request ID: "station_" + stationId
                   │
                   ▼
   [Đăng ký với Google LocationServices GeofencingClient]
                   │
                   ▼
    (Khi xe tiến vào vùng 500m của trạm sạc)
                   │
                   ▼
       [GeofenceBroadcastReceiver kích hoạt]
                   │
                   ├─► Trích xuất stationId từ Request ID
                   ├─► Lấy thông tin trạm sạc từ Cache
                   ├─► Bắn Push Notification cục bộ (Channel: "nearby_stations")
                   └─► Click mở thẳng màn hình chi tiết trạm sạc (SplashActivity -> StationDetail)
```

#### Chi tiết thông số Geofence trong mã nguồn:
* `MAX_GEOFENCES = 15`: Giới hạn tối đa 15 trạm gần xe nhất để tuân thủ ngưỡng tối ưu phần cứng của Google Play Services.
* `GEOFENCE_RADIUS = 500.0f`: Bán kính 500 mét quanh tọa độ trạm sạc.
* `GEOFENCE_EXPIRATION = -1`: Hàng rào không hết hạn cho đến khi có đợt cập nhật vị trí mới.
* `PendingIntent` cờ `FLAG_MUTABLE | FLAG_UPDATE_CURRENT` tương thích Android 12+.

---

### 4. Thuật toán Polling trạng thái trực tiếp của trạm (`StationStatusFetcher`)

Khi mở màn hình trạm yêu thích hoặc lộ trình, hệ thống không chỉ hiện dữ liệu tĩnh mà kích hoạt cơ chế Polling song song:
1. `StationStatusFetcher.Companion.getInstance().startPolling(stationIds)`
2. Định kỳ gửi request kiểm tra số lượng súng sạc đang rảnh (`available`), đang sạc (`busy`), hoặc hỏng hóc/bảo trì (`maintenance`).
3. Render trạng thái trực quan: `%d/%d khả dụng` trên cả UI điện thoại và màn hình Car App xe hơi.

---

## TỔNG KẾT BẢNG ÁNH XẠ CODE THỰC TẾ TRONG PROJECT

| Chức năng | File nguồn Java/Kotlin | File / Module JS |
|---|---|---|
| **Cầu nối React Native & Native** | `carplay/bridge/CarPlatformBridgeModule.java` | `index.android.bundle` (`CarPlatformBridge`) |
| **Quản lý & Query dữ liệu trạm** | `carplay/data/StationDataStore.java` | `index.android.bundle` (`stations_cache_v3.db`) |
| **Lưu & Migrate trạm yêu thích** | `carplay/data/BookmarkStore.java` | `index.android.bundle` (`AuthApiService.syncBookmarks`) |
| **Quản lý Hàng rào địa lý** | `carplay/GeofenceManager.java` | `index.android.bundle` (`refreshGeofencesIfPermitted`) |
| **Bắt sự kiện vào vùng trạm sạc** | `carplay/GeofenceBroadcastReceiver.java` | Android System Broadcast |
| **Màn hình Lộ trình sạc trên xe** | `carplay/screens/RouteScreen.java` | `index.android.bundle` (`RouteMapScreen`, `RouteStopsModal`) |
| **Màn hình Trạm yêu thích trên xe** | `carplay/screens/FavoritesScreen.java` | `index.android.bundle` (`FavoritesScreen`) |
| **Polling trạng thái súng sạc** | `carplay/data/StationStatusFetcher.java` | `index.android.bundle` (`StationStatusFetcher`) |
| **Dịch vụ chia sẻ lộ trình** | — | `index.android.bundle` (`SharedRouteService`, `RouteService`) |
