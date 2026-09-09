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

### 2.3. Cơ chế Online 4G & Trạm Sạc Real-time:
- 100% Online qua mạng 4G: Không cần cache cơ sở dữ liệu trạm sạc offline nặng nề.
- Lộ trình: Gọi OSRM Routing Server (`https://map.evcs.vn/route/v1/driving/...`).
- Dữ liệu trạm sạc & Live Telemetry: Gọi HERE Maps EV API (`ev-v2.cc.api.here.com`) và EVCS Search API (`evcs.vn/search`) để lấy tọa độ, công suất và trạng thái trụ rảnh (Available plugs).

---

## 3. QUY TẮC THUẬT TOÁN ĐÃ THỐNG NHẤT (ALGORITHM RULES)

1. **Khắc phục lỗi chia đều - Áp dụng Greedy Forward Simulation:**
   - **Chặng 1:** Tính tầm vận hành an toàn từ thanh trượt % pin hiện tại ($SoC_{start}$):
     $$D_1 = Range_{safe} \times \frac{SoC_{start} - SoC_{buffer}}{100}$$
   - **Các chặng tiếp theo:** Sau khi sạc tại trạm dừng lên mức khuyến nghị 85% ($SoC_{charge\_target}$), tầm vận hành chặng sau chỉ tính từ 85% về 10% pin an toàn (tương đương ~75% $Range_{safe}$):
     $$D_k = Range_{safe} \times \frac{85 - SoC_{buffer}}{100}$$
2. **Chống Bẫy Cao Tốc & Đi đường vòng (Detour Penalty):**
   - Lọc trạm trong hành lang Corridor Buffer $\le 3\text{km} - 4\text{km}$ tính từ tim đường polyline.
   - OSRM Detour Penalty $> 3\text{km}$ hoặc lệch chiều có dải phân cách cứng $\to$ Tự động loại trừ.
3. **Cơ chế Popup thông báo khi không có trạm đạt Min Power:**
   - Khi quét trong cửa sổ pin an toàn không tìm thấy trạm nào đạt công suất tối thiểu do user cài đặt (ví dụ: yêu cầu $\ge 60\text{kW}$ nhưng vùng đó chỉ có 30kW):
     - Hiển thị Popup cảnh báo trên giao diện: *"Không tìm thấy trạm $\ge [MinPower]\text{kW}$ trong phạm vi pin an toàn. Trạm khả dụng gần nhất là [Tên trạm] ([Công suất]kW). Bạn có muốn nới lỏng bộ lọc để tiếp tục?"*.
     - User chọn **[Đồng ý hạ công suất]** để tiếp tục lập lộ trình, hoặc **[Tự chọn trạm khác]**.
4. **Cặp Trạm Chính + Trạm Dự Phòng (Primary & Backup Station):**
   - Tại mỗi điểm dừng nghỉ sạc, thuật toán chọn ra:
     - **Trạm chính (Primary Stop):** Trạm đạt điểm cao nhất (công suất tối ưu, ít detour, có súng trống).
     - **Trạm dự phòng (Backup Station):** Trạm thay thế tốt thứ hai nằm lân cận trạm chính.
   - Hiển thị trực tiếp trạm dự phòng trên Timeline để tài xế có thể 1-chạm đổi trạm ngay lập tức nếu tới nơi trạm chính bị mất điện hoặc kẹt hàng đợi.
5. **Cài đặt Tùy biến (Settings):**
   - Số km an toàn @ 100% pin (lưu vĩnh viễn).
   - Mức pin lúc xuất phát (Thanh trượt % pin hiện tại: 20% - 100%).
   - Ngưỡng pin an toàn khi đến trạm ($SoC_{buffer}$, mặc định 10% pin).
   - Mục tiêu sạc tại trạm dừng (Mặc định: Luôn sạc tới 85%).
   - Công suất tối thiểu mong muốn ($MinPower$: Mặc định 60kW DC).
6. **Chuyển tiếp chặng tự động:**
   - Dẫn đường đến Trạm 1 trước. Khi xe đến gần trạm ($< 300\text{m}$) hoặc tài xế bấm xác nhận đã sạc xong, app tự động chuyển mục tiêu sang chặng tiếp theo.

---

## 4. BỘ MOCKUP GIAO DIỆN CHUẨN DEV (UI/UX MOCKUPS)

### 4.1. Màn hình Tab "Lộ trình" (Phát triển theo chuẩn EVCS Screenshot nhưng nâng cấp Material 3)
![Mockup Màn hình Lộ trình Chuẩn EVCS](/home/skul9x/.gemini/antigravity-ide/brain/a7c5ef3e-0258-4bf3-8fcc-3e4074832a5f/ev_plus_route_tab_mockup_1788871837022.jpg)

### 4.2. Màn hình Cài đặt Dẫn đường & Sạc pin VinFast (Settings UI)
![Mockup Màn hình Cài đặt Dẫn đường & Sạc pin VinFast](/home/skul9x/.gemini/antigravity-ide/brain/a7c5ef3e-0258-4bf3-8fcc-3e4074832a5f/ev_routing_settings_mockup_1788870989534.jpg)

### 4.3. Màn hình Lập Lộ Trình & Bản Đồ Năng Lượng (Energy Corridor & Map UI)
![Mockup Màn hình Lộ trình Sạc Pin Thông Minh](/home/skul9x/.gemini/antigravity-ide/brain/a7c5ef3e-0258-4bf3-8fcc-3e4074832a5f/ev_smart_route_mockup_1788871013987.jpg)
