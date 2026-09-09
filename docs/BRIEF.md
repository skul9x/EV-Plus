# 💡 BRIEF: Nâng Cấp UX Landscape & Khắc Phục Lỗi Màn Hình Android Box Ô Tô

**Ngày tạo:** 2026-09-09  
**Dự án:** EV-Plus (Android Automotive & Carlinkit TBox)  
**Tác giả & Brainstorm:** User & Antigravity  

---

## 1. BỐI CẢNH & VẤN ĐỀ CẦN GIẢI QUYẾT

Dựa trên ảnh chụp thực tế từ màn hình ô tô chạy Android Box Carlinkit TBox Ambient, phát hiện các điểm nghẽn UX và lỗi giao diện chế độ ngang (Landscape Mode):

1. **Tab Yêu thích không có dữ liệu trụ sạc realtime:**
   - Dữ liệu tab Yêu thích được lấy từ cache/Firestore/endpoint tĩnh, không có số trụ, số cổng trống và công suất thực tế như tab Quanh đây.
2. **Chi tiết trạm không tự cuộn lên đỉnh (Top) khi đổi trạm:**
   - Khi chọn trạm khác ở danh sách bên trái, cột chi tiết bên phải giữ nguyên vị trí scroll của trạm cũ, làm trôi thông tin quan trọng trên đầu.
3. **Thiếu nút Home hệ thống trên thanh điều hướng bên trái:**
   - Người lái xe cần thao tác 1 chạm để quay về màn hình chính Launcher của Android Box/xe hơi một cách nhanh chóng, thay vì phải vuốt mép tìm phím Home của xe.
4. **Lỗi thanh Dock Bar của Android Box bị bung ra khi mở Cài đặt (Bug nghiêm trọng):**
   - Bình thường (tab Quanh đây), app chạy Immersive Fullscreen, thanh dock của xe bị ẩn hoàn toàn.
   - Khi bấm nút **Cài đặt** (Gear icon), component `ModalBottomSheet` của Material 3 mở ra một `DialogWindow` mới không có cờ Immersive. Hệ điều hành Android Box phát hiện cửa sổ thường nên lập tức bung thanh Dock Bar hệ thống (chứa giờ 18:09, Wifi, các icon xe) đè lên góc trái màn hình.
5. **Thừa thông tin số sao đánh giá:**
   - Cột chi tiết trạm sạc hiển thị badge sao đánh giá (`★ 4.8 (25)`) không cần thiết, làm rối không gian của màn hình xe.
6. **Hiển thị trụ sạc trống dài dòng & xếp chồng dọc:**
   - Text cũ dạng `"30kW: Trống 2/4 cổng"` quá dài, làm các badge bị xếp dọc chiếm nhiều diện tích, bắt người dùng phải cuộn dọc nhiều khi lái xe.
   - Trạng thái hết chỗ chưa đủ nổi bật để cảnh báo tài xế.
7. **Thiếu thông tin phân bổ công suất trạm ở danh sách "Quanh đây":**
   - Thẻ trạm ở danh sách Quanh đây chỉ có tên + khoảng cách/ETA, thiếu thông tin bao quát xem trạm có bao nhiêu trụ 120kW, 60kW, 30kW để người dùng quyết định ghé vào.

---

## 2. GIẢI PHÁP ĐÃ THỐNG NHẤT

### 2.1. Tab Yêu thích: Tự động cập nhật Realtime khi mở Tab / Làm mới (Mục 1)
- **Cơ chế:** Khi người dùng chuyển sang tab Yêu thích hoặc nhấn nút "Làm mới" (Refresh) trên thanh điều hướng, app sẽ tự động fetch telemetry ngầm cho các trạm yêu thích.
- Cập nhật số trụ, số cổng khả dụng và công suất mới nhất lên từng thẻ trạm trong danh sách.
- Không áp dụng polling ngầm định kỳ (để tiết kiệm tài nguyên mạng và pin xe).

### 2.2. Tự động Reset Scroll về đỉnh khi chuyển trạm (Mục 3)
- Trong `NativeStationDetailContent`: Bổ sung `LaunchedEffect(station.id) { scrollState.scrollTo(0) }`.
- Mỗi khi chọn trạm mới từ danh sách Master, cột Detail lập tức cuộn mượt về đầu trang (`offset = 0`).

### 2.3. Bổ sung nút Home Hệ Thống trên Navigation Rail (Mục 4)
- Thêm nút icon **Home** (`Icons.Default.Home`) tại vị trí **trên cùng** của thanh Navigation Rail (phía trên icon Quanh đây).
- **Hành động:** Gửi `Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME); flags = Intent.FLAG_ACTIVITY_NEW_TASK }`.
- Đưa người dùng về thẳng màn hình Launcher của Android Box xe hơi ngay lập tức.

### 2.4. Khắc phục triệt để lỗi bung Dock Bar xe khi mở Cài đặt (Mục 5)
- **Giải pháp gốc rễ:** Thay thế `ModalBottomSheet` (vốn tạo `DialogWindow` riêng) bằng **In-App Landscape Overlay/Drawer Panel** nội bộ, vẽ trực tiếp trong cây Compose của cùng `MainActivity Window`.
- Giữ nguyên 100% chế độ Immersive Sticky Fullscreen, loại bỏ hoàn toàn khả năng Android Box bung thanh dock bar của xe.

### 2.5. Gỡ bỏ số sao đánh giá trong chi tiết trạm (Mục 6)
- Xóa bỏ hoàn toàn khối `ratingText` / badge sao đánh giá trong `NativeStationDetailContent`.
- Giúp giao diện thông thoáng, tập trung vào công suất và cổng sạc khả dụng.

### 2.6. Tinh gọn hiển thị cổng sạc & Chống cuộn dọc (Mục 7)
- **Format mới:** `${kw}kW  ${avail}/${total}` (Ví dụ: `30kW  2/4`).
- **Màu sắc trạng thái:**
  - Còn chỗ (`avail > 0`): Màu xanh lá (Emerald / StatusAvailable).
  - **Hết chỗ (`avail == 0`): Màu đỏ cảnh báo (StatusOffline / Red)** giống trạng thái Bảo trì (`30kW  0/4` hoặc `30kW  Hết chỗ`).
  - Bảo trì: Màu đỏ (`30kW  Bảo trì`).
- **Bố cục:** Xếp ngang bằng `FlowRow` với các pill bo góc nhỏ gọn, loại bỏ tình trạng xếp dọc dài dòng.

### 2.7. Dòng thông tin phân bổ trụ sạc trạm ở tab "Quanh đây" (Mục 9)
- Thêm 1 dòng phụ tinh gọn ngay dưới tên trạm trên thẻ trạm Quanh đây (`StationCard`):
  - Định dạng: `120kW x 6 | 60kW x 10 | 30kW x 20`.
  - Text **công suất** (`120kW`, `60kW`): Màu trung tính (trắng / xám sáng).
  - Text **số lượng trụ** (`x 6`, `x 10`): Màu nổi bật (xanh lá / cyan).
  - Tích hợp `basicMarquee` tự động trượt ngang khi chuỗi dài vượt quá chiều rộng thẻ.

---

## 3. DANH SÁCH TÍNH NĂNG & THAY ĐỔI (MVP SCOPE)

| STT | Hạng mục | Vị trí thay đổi | Chi tiết thực hiện |
|---|---|---|---|
| 1 | **Realtime Favorites** | `FavoritesViewModel.kt`, `FavoritesLandscapeScreen.kt` | Fetch telemetry ngầm khi vào tab hoặc bấm Refresh, cập nhật trực tiếp `powers` và `totalAvailablePlugs`. |
| 2 | **Scroll Reset** | `NativeStationDetailSheet.kt` | `LaunchedEffect(station.id)` reset `scrollState` về 0 khi ID trạm thay đổi. |
| 3 | **Nút Home Rail** | `AppNavigationRail.kt`, `MainActivity.kt` | Thêm action `HOME` ở đỉnh thanh rail, trigger Home Intent ra Launcher xe. |
| 4 | **Fix Lộ Dock Bar** | `RoutingSettingsModal.kt`, `MainActivity.kt` | Chuyển Modal thành In-App Landscape Dialog/Drawer trong cùng Compose Window. |
| 5 | **Bỏ Star Rating** | `NativeStationDetailSheet.kt` | Gỡ bỏ badge sao đánh giá trong giao diện chi tiết. |
| 6 | **Rút gọn Pill sạc** | `NativeStationDetailSheet.kt` | Đổi format `${kw}kW  ${avail}/${total}`, hết chỗ chuyển màu đỏ, xếp ngang bằng FlowRow. |
| 7 | **Dòng trụ sạc Quanh đây** | `StationCard.kt`, `StationCardHelper.kt` | Gom nhóm `station.powers`, render dòng `120kW x 6 \| 60kW x 10` kèm marquee & highlight màu. |

---

## 4. BƯỚC TIẾP THEO
→ Khi người dùng sẵn sàng, gõ `/plan` để tạo kế hoạch triển khai chi tiết từng file và tiến hành viết mã nguồn (`/code`).
