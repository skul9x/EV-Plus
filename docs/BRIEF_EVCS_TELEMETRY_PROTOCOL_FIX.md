# 💡 BRIEF: Cập nhật Toàn diện Giao thức Telemetry & Phục hồi Biểu đồ 24h EVCS (Gói Full)

**Ngày tạo:** 2026-11-24  
**Brainstorm cùng:** skul9x  
**Phạm vi lựa chọn:** 🌟 **Gói Full (Toàn diện)**  
**Nguồn phân tích:** `curl_capture_20261124_203142` & Nghiên cứu Best Practices RFC/Android

---

## 1. VẤN ĐỀ CẦN GIẢI QUYẾT
Phân tích 19 luồng HTTP capture mới nhất từ web/app EVCS cho thấy backend EVCS (`www2.evcs.vn`) đã chuyển đổi mạnh mẽ sang kiến trúc REST HTTP gọn nhẹ, đồng thời thay đổi cấu trúc payload telemetry khiến app Android hiện tại không đồng bộ được trạng thái thực tế:

1. **Sai lệch giao thức `POST https://www2.evcs.vn/update`**:
   - Hiện tại app gửi `application/json` + header `x-t: apiToken` + body `{"a":"id","b":count}`.
   - Server thực tế yêu cầu: `Content-Type: text/plain;charset=UTF-8`, **không** dùng header `x-t`. Token `t` phải nằm trong JSON body kèm integrity token `u` và breakdown xe đang sạc `p`:
     - Single update: `{"t":"<token>","a":"<id>","b":<count>,"p":{"60":2,"120":4},"u":"<uToken>"}`
     - Batch update: `{"t":"<token>","items":[{"a":"<id>","b":<count>,"p":{...},"u":"<uToken>"}, ...]}`
2. **Sai lệch trường JSON `POST /charging`**:
   - Server trả về `"chargingKw": {"60": 2, "120": 4}` kèm token `"u": "..."`.
   - Model `ChargingResponseRaw` trong app đang khai báo `busyKw`, khiến trường phân bổ công suất luôn rỗng (`emptyMap()`), app không hiển thị được số cổng đang bận của từng mức kW (60kW, 120kW...).
3. **Bỏ quên Cookie RFC 6265bis `__Host-tp`**:
   - Server trả về cookie `__Host-tp=...`, nhưng `SessionManager` chỉ trích xuất `evcs` và `PHPSESSID`.
4. **Biểu đồ lịch sử 24h (`GET /history`) đang bị stub rỗng**:
   - Do trước đây gỡ bỏ Socket.io, hàm `fetch24hHistory` bị đánh dấu `@Deprecated` và trả về `emptyList()`.
   - Thực tế EVCS cung cấp endpoint REST HTTP siêu nhẹ: `GET https://www2.evcs.vn/history?sid={id}&t={apiToken}` trả về mảng 2D `[[timestamp, count], ...]`. Trong khi đó, `StationDetailCoordinator` và `Station24hStatsCalculator` đã sẵn sàng 100% để hiển thị biểu đồ nếu có dữ liệu.

---

## 2. KẾT QUẢ RESEARCH ONLINE & BEST PRACTICES KỸ THUẬT

1. **Resilient JSON Parsing với `@JsonNames`**:
   - Chuẩn `kotlinx.serialization` khuyến nghị dùng `@OptIn(ExperimentalSerializationApi::class) @JsonNames("chargingKw", "busyKw")`.
   - Đảm bảo tương thích xuôi ngược (backward-compatible) giữa API mới và các fixture/mock test cũ mà không cần viết custom serializer.
2. **Chuẩn Cookie RFC 6265bis (`__Host-` Prefixed Cookies)**:
   - Theo RFC 6265bis, cookie có tiền tố `__Host-` chỉ được truyền qua HTTPS, path `/`, và không có domain.
   - Client Android cần trích xuất nguyên vẹn giá trị `__Host-tp`, lưu vào secure storage và gắn vào header `Cookie: ...; __Host-tp=...` cùng với `evcs`, `PHPSESSID` và `evcs_did`.
3. **HTTP `text/plain` an toàn & Non-Cancellable Sync**:
   - Dùng `payload.toRequestBody("text/plain; charset=utf-8".toMediaType())`.
   - Bọc request cập nhật telemetry trong context `withContext(NonCancellable + ioDispatcher)` để khi người dùng back/dismiss màn hình, request đồng bộ ngầm không bị ngắt quãng giữa chừng gây thiếu nhất quán dữ liệu phía server.
4. **Xử lý Mảng 2D Lịch Sử 24h**:
   - Parse `List<List<Long>>` từ JSON mảng 2D, kiểm tra biên `point.size >= 2`, mapping sang `List<Pair<Long, Int>>`.
   - Tận dụng thuật toán timezone UTC+7 và bucket giờ cao điểm đã có trong `Station24hStatsCalculator`.

---

## 3. PHẠM VI TRIỂN KHAI (GÓI FULL)

### 🚀 Module 1: Tầng Dữ liệu & Phân giải (Parser & Model)
- [ ] Cập nhật `StationTelemetryParser.kt`:
  - Thêm `@JsonNames("chargingKw", "busyKw")` vào `ChargingResponseRaw`.
  - Thêm trường `u: String? = null` vào `ChargingResponseRaw`.
  - Cập nhật `StationTelemetry` mang theo `uToken: String? = null`.
  - Thêm hàm parse mảng 2D cho endpoint `/history` -> `List<Pair<Long, Int>>`.

### 🚀 Module 2: Quản lý Phiên & Cookie (`SessionManager`)
- [ ] Cập nhật `SessionManager.kt`:
  - Khai báo `KEY_HOST_TP = "__Host-tp"` và regex nhận diện `__Host-tp=([^;]+)`.
  - Thuộc tính `hostTpCookie: String?` lưu vào `SessionStorage`.
  - Tự động bóc tách `__Host-tp` trong `saveFromSetCookieHeader`.
  - Ghép `__Host-tp=...` vào `getCookieHeader()`.

### 🚀 Module 3: Tầng Truyền Thông Mạng (`EvcsTelemetryDataSource`)
- [ ] Cập nhật `EvcsTelemetryDataSource.kt`:
  - **Sửa `sendTelemetryUpdate`**: Chuyển sang `Content-Type: text/plain`, payload `{"t": apiToken, "a": stationId, "b": totalBusy, "p": chargingKwMap, "u": uToken}`, bỏ header `x-t`.
  - **Thêm `sendBatchTelemetryUpdate`**: Hỗ trợ gửi batch update `{"t": apiToken, "items": [...]}` cho nhiều trạm.
  - **Thực thi `fetch24hHistory(stationId, apiToken)`**: Gọi `GET https://www2.evcs.vn/history?sid={id}&t={apiToken}`, decode mảng 2D và trả về `Result<List<Pair<Long, Int>>>`.

### 🚀 Module 4: Tầng Nghiệp Vụ (`EvcsTelemetryRepository`)
- [ ] Cập nhật `EvcsTelemetryRepository.kt`:
  - Gỡ bỏ `@Deprecated` và stub rỗng ở `fetch24hHistory`, gọi trực tiếp qua `dataSource.fetch24hHistory(stationId, apiToken)`.
  - Cập nhật `fetchStationTelemetrySnapshot` để kết hợp `uToken` và chuyển giao cho `sendTelemetryUpdate`.

### 🚀 Module 5: Tầng Điều Phối & Giao Diện (`StationDetailCoordinator` & UI)
- [ ] Kiểm tra kết nối Stage 2 trong `StationDetailCoordinator.kt`:
  - Đảm bảo khi mở chi tiết trạm, pipeline gọi `fetch24hHistory` thành công sẽ kích hoạt `calculate24hStats` và cập nhật `stats24h` trong `StationDetailUiState`.
  - Kiểm tra các thành phần UI hiển thị `stats24h` (thống kê tải đỉnh, tải trung bình, khung giờ cao điểm) trong `NativeStationDetailSheet`.

### 🧪 Module 6: Kiểm Thử & Xác Minh (Test Suite)
- [ ] Cập nhật và bổ sung unit tests:
  - `StationTelemetryParserTest`: Test parse cả `chargingKw` và `busyKw`, parse `uToken`, parse mảng 2D `history`.
  - `SessionManagerTest`: Test lưu và sinh cookie header có `__Host-tp`.
  - `EvcsTelemetryDataSourceTest` & `EvcsTelemetryRepositoryTest`: MockWebServer kiểm tra request payload `/update` (`text/plain`, JSON body chuẩn) và endpoint `/history`.
  - Chạy test hồi quy toàn bộ luồng ViewModel.

---

## 4. ĐÁNH GIÁ KỸ THUẬT & TÍNH KHẢ THI

- 🟢 **Khả thi:** 100% các thành phần đã có kiến trúc nền tảng (Coordinator, Calculator, State). Chỉ cần nối đúng luồng dữ liệu theo capture mới.
- 🟢 **Thời gian dự kiến:** Lập plan và implement từng module mạch lạc, test chặt chẽ.
- 🟢 **Hiệu năng:** Giảm thiểu request thừa, không phụ thuộc Socket.io, HTTP GET cho history có dung lượng rất nhỏ (~1-2KB).

---

## 5. BƯỚC TIẾP THEO
→ Chuyển sang workflow `/plan` để tạo kế hoạch triển khai chi tiết theo từng Phase kỹ thuật.
