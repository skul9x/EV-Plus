# 💡 BRIEF: TÍNH NĂNG DẪN ĐƯỜNG THÔNG MINH THEO DUNG LƯỢNG PIN & GỢI Ý ĐIỂM DỪNG SẠC (EV SMART ROUTING & CHARGING STOP PLANNER)

**Ngày cập nhật:** 2026-09-08  
**Dự án:** EV-Plus (Dành riêng cho hệ sinh thái xe điện VinFast & V-Green)  
**Phiên bản kế hoạch:** v1.2.0  

---

## 1. MỤC TIÊU & CẤU TRÚC ĐIỀU HƯỚNG MỚI (NAVIGATION & ARCHITECTURE)

### 1.1. Cấu trúc 3 Tab Bottom Navigation (Thanh điều hướng dưới đáy)
- **Tab 1:** 📍 **Quanh đây (NEARBY)** - Tìm kiếm và lọc trạm sạc xung quanh vị trí GPS.
- **Tab 2:** 🌟 **Yêu thích (FAVORITES)** - Nằm ở **CHÍNH GIỮA** (Center Tab), quản lý các trạm đã lưu.
- **Tab 3:** 🛣️ **Lộ trình (ROUTE)** - Nằm bên cạnh tab Yêu thích: Màn hình lập kế hoạch sạc pin đường dài và dẫn đường Turn-by-Turn thông minh.

### 1.2. Màn hình Lập Lộ Trình (Tab "Lộ trình" chuẩn theo EVCS nhưng tối ưu Material 3)
- **Phạm vi hoạt động của EV (Slider):**
  - Kéo chọn số km an toàn (từ $100\text{km} - 500\text{km}$, mặc định $200\text{km}$).
  - Liên động với công thức đã thống nhất: $Range = Range_{safe} \times \frac{SoC_{start}}{100}$.
- **Điểm xuất phát (A):**
  - Dropdown **Tỉnh/Thành phố** $\to$ Dropdown **Quận/Huyện** (chứa danh mục 63 tỉnh/thành của Việt Nam).
  - Nút bấm nhanh icon mục tiêu GPS: **[🎯]** để lấy ngay vị trí hiện tại của xe.
- **Điểm kết thúc (B):**
  - Dropdown **Tỉnh/Thành phố** $\to$ Dropdown **Quận/Huyện**.
- **Nút hành động chính:**
  - Nút bấm lớn màu xanh ngọc: **[Tìm trạm sạc]**.
- **Danh sách kết quả trạm sạc (Chặng dừng):**
  - Đánh số thứ tự 1, 2, 3...
  - Tên trạm & Khoảng cách từ điểm xuất phát A (`Cách điểm đi X km`).
  - Danh sách cổng sạc theo từng công suất (`60kW (22 cổng)`, `20kW (10 cổng)`...).
  - Thời gian sạc ước tính: *"Sạc 20p lên 85%"*.
  - Huy hiệu trạng thái súng rảnh trực tiếp (Live plugs).
  - Nút hành động cho từng trạm: **[Đổi trạm khác]** và nút tổng **[Bắt đầu Dẫn đường]**.

---

## 2. DỮ LIỆU BẮT GÓI THỰC TẾ & CƠ CHẾ NATIVE ENGINE

### 2.1. Cấu trúc Payload Route của EVCS (`POST https://evcs.vn/route`):
```json
{
  "start_lat": 21.0333,
  "start_lon": 105.8141,
  "end_lat": 11.5667,
  "end_lon": 105.9667,
  "max_distance": "200",
  "station_type": "vinfast"
}
```

### 2.2. Chuẩn Deeplink (`evcs://route`):
```
evcs://route?pl={polyline}&stations=[{"name":"...","latitude":...,"longitude":...,"power":"..."},...]
```

### 2.3. Giải pháp Native Offline Resilience của EV-Plus:
- Nhúng sẵn database 63 Tỉnh/Thành & Quận/Huyện kèm tọa độ trung tâm trong asset offline.
- Tự động chạy thuật toán bám đường, quét hành lang Corridor Buffer và OSRM Detour Penalty trên thiết bị, không bị lỗi Cloudflare 403 Forbidden và chạy mượt 100% offline kể cả khi mất sóng 4G.

---

## 3. QUY TẮC THUẬT TOÁN ĐÃ THỐNG NHẤT (ALGORITHM RULES)

1. **Chống Bẫy Cao Tốc:** OSRM Detour Penalty $> 3\text{km}$ $\to$ Tự động loại trừ trạm ngược chiều cao tốc có dải phân cách cứng.
2. **Ưu tiên Công Suất Trụ Sạc:**
   - Ưu tiên 1: Trụ DC $\ge 60\text{kW}$ (hoặc 150kW - 250kW).
   - Ưu tiên 2: Trụ DC 30kW nếu không có trụ công suất cao hơn.
   - Loại trừ: Trạm chỉ có AC 11kW ở các chặng dừng giữa đường.
3. **Cài đặt Tùy biến (Settings):**
   - Số km an toàn @ 100% pin (lưu vĩnh viễn).
   - Ngưỡng pin an toàn khi đến trạm (Mặc định: 10% pin).
   - Mục tiêu sạc tại trạm dừng (Mặc định: Luôn sạc tới 85%).
   - Cộng 25% thời gian sạc an toàn (Toggle On/Off, mặc định Bật).
4. **Chuyển tiếp chặng tự động:**
   - Dẫn đường đến Trạm 1 trước. Khi xe đến gần trạm ($< 300\text{m}$) hoặc tài xế bấm xác nhận đã sạc xong, app tự động chuyển mục tiêu sang chặng tiếp theo.

---

## 4. BỘ MOCKUP GIAO DIỆN CHUẨN DEV (UI/UX MOCKUPS)

### 4.1. Màn hình Tab "Lộ trình" (Phát triển theo chuẩn EVCS Screenshot nhưng nâng cấp Material 3)
![Mockup Màn hình Lộ trình Chuẩn EVCS](/home/skul9x/.gemini/antigravity-ide/brain/a7c5ef3e-0258-4bf3-8fcc-3e4074832a5f/ev_plus_route_tab_mockup_1788871837022.jpg)

### 4.2. Màn hình Cài đặt Dẫn đường & Sạc pin VinFast (Settings UI)
![Mockup Màn hình Cài đặt Dẫn đường & Sạc pin VinFast](/home/skul9x/.gemini/antigravity-ide/brain/a7c5ef3e-0258-4bf3-8fcc-3e4074832a5f/ev_routing_settings_mockup_1788870989534.jpg)

### 4.3. Màn hình Lập Lộ Trình & Bản Đồ Năng Lượng (Energy Corridor & Map UI)
![Mockup Màn hình Lộ trình Sạc Pin Thông Minh](/home/skul9x/.gemini/antigravity-ide/brain/a7c5ef3e-0258-4bf3-8fcc-3e4074832a5f/ev_smart_route_mockup_1788871013987.jpg)
