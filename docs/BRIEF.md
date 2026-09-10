# 💡 BRIEF: Tinh chỉnh Giao diện Cài đặt và Thương hiệu EV+

**Ngày tạo:** 2026-09-10  
**Tác giả:** Antigravity Brainstorm Partner & Nguyễn Duy Trường  
**Trạng thái:** Brainstorming Completed -> Sẵn sàng triển khai  

---

## 1. VẤN ĐỀ CẦN GIẢI QUYẾT
1. **Bộ lọc công suất trong Cài đặt có phần Chọn nhanh dư thừa:**
   - Mục "Chọn nhanh loại cổng / công suất" (các chip AC, DC <= 30kW, DC 30-60kW...) làm rối mắt và không cần thiết trong màn hình Cài đặt, vì tính năng lọc nhanh đã có sẵn ở màn hình bản đồ/trạm sạc bên ngoài.
   - Người dùng cần một giao diện tập trung vào việc tùy chỉnh dải công suất mong muốn (Min/Max) với các nút chạm `+/- 10kW` tối ưu cho xe hơi.

2. **Tên ứng dụng chưa đồng bộ và thừa thãi:**
   - Tên ứng dụng chính thức là **EV+** (theo `strings.xml`), nhưng trong card Thông tin ứng dụng đang hiển thị là "EV+ Station Navigator". Cần chuẩn hóa thành **EV+**.

3. **Vị trí và định dạng bản quyền chưa hợp lý:**
   - Dòng chữ bản quyền đặt dưới nút "Đặt lại mặc định" ở sidebar bên trái gây chật chội và không đúng vị trí tự nhiên.
   - Trong card Thông tin ứng dụng, hàng tác giả ghi "Nguyễn Duy Trường Copyright 2026" lặp lại thông tin bản quyền và tên tác giả.

4. **Khu vực hiển thị bản quyền chính thức:**
   - Cần một dòng chữ bản quyền `© 2026 Nguyễn Duy Trường` thanh lịch, đặt ở khoảng không gian nền đen bên phải (bên dưới card Thông tin ứng dụng), không nằm trong nền tím than của card.

---

## 2. GIẢI PHÁP ĐỀ XUẤT

### 2.1. Tinh gọn thẻ "Bộ lọc công suất" (`CustomFilterSettingsCard`)
- **Loại bỏ phần Chọn nhanh:**
  - Xóa bỏ khối "Chọn nhanh loại cổng / công suất" và hàng chip bộ lọc trong màn hình Cài đặt.
- **Tập trung vào dải công suất tùy chỉnh:**
  - Giữ lại duy nhất cụm Stepper +/- 10kW (`AutomotiveStepperBox`) cho Công suất Min và Công suất Max.
  - Cập nhật dòng phụ mô tả: *"Tùy chỉnh khoảng công suất kW mong muốn"*.

### 2.2. Chuẩn hóa tên ứng dụng thành "EV+" (`AboutAppCard`)
- Đổi hằng số `AboutAppInfo.APP_NAME = "EV+"`.
- Tiêu đề trong card Thông tin ứng dụng hiển thị ngắn gọn, sắc nét: **EV+**.

### 2.3. Tối ưu Sidebar bên trái & Card Tác giả
- **Sidebar trái:** Bỏ hoàn toàn dòng text bản quyền phía dưới nút "Đặt lại mặc định".
- **Card Thông tin ứng dụng:**
  - Dòng tiêu đề mục Tác giả (icon Người): **Nguyễn Duy Trường**
  - Dòng phụ: **Tác giả** (loại bỏ từ "Copyright 2026" và việc lặp lại họ tên).

### 2.4. Đặt dòng Bản quyền trên nền đen bên phải
- Trong mục "Thông tin ứng dụng" (`SettingsCategory.ABOUT`), đặt dòng chữ:
  ```
  © 2026 Nguyễn Duy Trường
  ```
  ở phía dưới `AboutAppCard`, căn giữa, hiển thị trên nền đen (`MaterialTheme.colorScheme.background`) của màn hình bên phải, tách biệt khỏi nền tím than (`DarkCardBackground`) của card.

---

## 3. DANH SÁCH TÍNH NĂNG & THAY ĐỔI

### 🚀 Triển khai ngay:
- [ ] **`CustomFilterSettingsCard.kt`**:
  - Thêm điều khiển ẩn/hiện Quick Chips (ẩn ở màn hình Cài đặt).
  - Cập nhật phụ đề thành *"Tùy chỉnh khoảng công suất kW mong muốn"*.
- [ ] **`AboutAppCard.kt`**:
  - Cập nhật `APP_NAME = "EV+"`.
  - Cập nhật hàng tác giả: Tiêu đề "Nguyễn Duy Trường", phụ đề "Tác giả".
  - Cập nhật `COPYRIGHT = "© 2026 Nguyễn Duy Trường"`.
- [ ] **`SettingsScreen.kt`**:
  - Gỡ bỏ footer bản quyền ở cột sidebar bên trái (dưới nút Đặt lại mặc định).
  - Thêm dòng text bản quyền `© 2026 Nguyễn Duy Trường` trên nền đen dưới `AboutAppCard`.
- [ ] **Unit Tests**:
  - Cập nhật `CommercialBrandingAndCopyrightContractTest` và `SettingsScreenComponentsAndAutoSaveTest` khớp 100% với cấu trúc mới.

---

## 4. ƯỚC TÍNH SƠ BỘ & RỦI RO
- **Độ phức tạp:** Đơn giản - Tinh chỉnh trực tiếp Composable UI và constant strings.
- **Rủi ro:** Cần cập nhật đúng các unit test contract để Gradle build luôn xanh 100%.

---

## 5. BƯỚC TIẾP THEO
→ Khi người dùng sẵn sàng, gõ `/code` hoặc `/plan` để triển khai mã nguồn theo Brief này.
