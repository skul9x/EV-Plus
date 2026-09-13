# 💡 BRIEF: Hybrid HERE EV API & EVCS Name Resolver cho Bộ Lọc Trạm AC (Top 10)

**Ngày tạo:** 2026-09-13  
**Tác giả:** Antigravity Brainstorm Partner & User  
**Trạng thái:** Brainstorming Completed -> Sẵn sàng lên kế hoạch (`/plan`)  

---

## 1. VẤN ĐỀ CẦN GIẢI QUYẾT

1. **Hạn chế của EVCS Search API (`POST /search`):**
   - API tìm kiếm mặc định của `evcs.vn` ưu tiên các trạm sạc nhanh DC công cộng lớn (30kW - 250kW) và giới hạn kết quả tối đa ~85 trạm.
   - Hầu hết các trụ sạc AC 11kW nhượng quyền tư nhân / quy mô hộ gia đình bị **lược bỏ hoặc ẩn mất** (ví dụ thực tế: trạm *"TƯ NHÂN Nguyễn Văn Đức"* cách điểm tìm kiếm chỉ 340m hoàn toàn không xuất hiện trong kết quả search của EVCS).

2. **Hạn chế của HERE EV API (`ev-v2.cc.api.here.com`):**
   - Nhận trực tiếp CPO Data Feed từ VinFast / V-Green nên có độ phủ 100% đến từng trụ sạc 11kW ở nhà dân (tìm thấy hơn 64 trụ trong bán kính 10km).
   - Tuy nhiên, tên trạm trả về từ HERE bị generic thành *"Trạm sạc VinFast"* và chỉ kèm mã trạm (`cpoId` như `C.BNI11197`).

3. **Mục tiêu:**
   - Kết hợp ưu điểm của cả hai nguồn: **Dùng HERE EV API để phát hiện mọi trụ AC 11kW/22kW gần nhất** + **Dùng EVCS để giải mã mã trạm thành tên trạm chính thức**, hiển thị Top 10 trạm AC khả dụng gần nhất lên màn hình.

---

## 2. QUY TẮC NGHIỆP VỤ & CÁC QUYẾT ĐỊNH ĐÃ CHỐT

Theo kết quả thảo luận và thực nghiệm trực tiếp:

1. **Lọc khả dụng nghiêm ngặt (100% Available):**
   - Loại bỏ các trạm hết chỗ, bận hoặc lỗi (`OCCUPIED`, `OTHER`, `OUT_OF_SERVICE`) **ngay từ bước đọc dữ liệu của HERE Maps**.
   - Đảm bảo danh sách Top 10 trạm lấy ra luôn là 10 trạm **đang có sẵn cổng AC cắm sạc được ngay**.

2. **Chính sách Cache:**
   - **Không lưu cache:** Không lưu cặp `Mã trạm -> Tên trạm` vào Database/Preferences nội bộ, luôn thực hiện resolve theo lượt gọi.

3. **Trải nghiệm hiển thị Loading (UX Flow):**
   - **Áp dụng Phương án B:** Hiển thị vòng quay loading xoay tròn, đợi coroutines hoàn tất việc giải mã đầy đủ tên cho Top 10 trạm từ EVCS rồi mới hiển thị danh sách trạm lên giao diện.

4. **Cơ chế Fallback khi lỗi mạng hoặc không có tên trên EVCS:**
   - Nếu link EVCS trả về lỗi hoặc không trích xuất được thẻ title:
   - Dùng tên định dạng: `"VinFast - [Địa chỉ từ HERE]"` (Ví dụ: *"VinFast - 29 Bình Than 1, Phường Đại Phúc"*).

5. **Phạm vi công suất:**
   - Ưu tiên chuẩn AC ô tô: **11kW** và **22kW**.

---

## 3. KIẾN TRÚC KỸ THUẬT & LUỒNG XỬ LÝ (PIPELINE)

```
[Người dùng chọn Lọc AC]
        │
        ▼
[HERE EV API: fetchNearbyStations]
  • Bán kính 10km quanh toạ độ GPS
  • Lọc: maxPowerLevel in (11kW, 22kW)
  • Lọc: numberOfAvailable > 0 && state == 'AVAILABLE'
  • Sắp xếp theo khoảng cách Haversine (gần -> xa)
  • Cắt lấy Top 10 trạm
        │
        ▼
[EVCS Name Resolver: Song song 10 coroutines]
  • URL: https://evcs.vn/tram-sac-vinfast-${locationId.lowercase()}.html
  • User-Agent: Chuẩn mobile app (đã test 100% không bị chặn)
  • Trích xuất tên từ <title> hoặc <meta name="title">
  • Fallback: "VinFast - " + HERE address nếu gặp lỗi
        │
        ▼
[Station Domain Model Mapping]
  • Gán tên chính thức đã resolve
  • Tọa độ GPS & số cổng trống từ HERE
  • Highlight chip 11kW (Emerald Primary)
        │
        ▼
[Hiển thị Top 10 StationCard trên NearbyScreen]
```

---

## 4. KẾT QUẢ TEST THỰC NGHIỆM ĐÃ XÁC THỰC

- Đã chạy test tự động trên Top 10 trạm gần tọa độ `21.1667007, 106.0706426`:
  * **10/10 trạm thành công (HTTP 200)**.
  * **Không bị dính Rate Limit (HTTP 429)** nhờ Cloudflare cache tại edge.
  * Tìm ra chính xác trạm **"TƯ NHÂN Nguyễn Văn Đức"** (`C.BNI11197`) tại 29 Bình Than 1, cách 340m.

---

## 5. CÁC FILE DỰ KIẾN TÁC ĐỘNG

1. **`HereEvApiClient.kt`**:
   - Thêm phương thức tìm kiếm chuyên biệt cho AC: `fetchNearbyAvailableAcStations(lat, lon, radiusMeters, limit = 10)`.
2. **`EvcsStationNameResolver.kt` / `EvcsApiClient.kt`**:
   - Thêm hàm `resolveStationNameFromHtml(locationId: String): String?` sử dụng parser title/meta.
3. **`NearbyViewModel.kt`**:
   - Khi `SmartFilterMode.AC` được chọn, kích hoạt luồng Hybrid tải từ HERE + resolve tên EVCS, phát tín hiệu loading trong lúc đợi kết quả.
4. **`NearbyScreen.kt` & `NearbyLandscapeScreen.kt`**:
   - Đảm bảo hiển thị Loading Indicator chuẩn xác theo Phương án B trước khi render danh sách trạm.

---

## 6. BƯỚC TIẾP THEO
→ Chạy lệnh `/plan` để tạo tài liệu thiết kế kỹ thuật chi tiết và tiến hành code.
