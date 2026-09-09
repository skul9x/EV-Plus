# 💡 BRIEF: Cải Thiện UI Màn Hình Ngang & Tối Ưu Android Box Carlinkit Tbox Ambient

**Ngày tạo:** 2026-09-09  
**Dự án:** EV-Plus (Android Automotive App)  
**Thiết bị mục tiêu:** Màn hình ngang ô tô, Android Box Carlinkit Tbox Ambient (Qualcomm QCM6225 8G/128GB, Android 13) và các màn hình xe hơi tương đương.

---

## 1. VẤN ĐỀ CẦN GIẢI QUYẾT
1. **Thanh Dock Bar / Quick Launch Bar của Carlinkit chiếm diện tích:** Mặc định Android Box hiển thị thanh điều hướng hệ thống (Dock Bar bên trái) chiếm mất một phần bề ngang màn hình, giảm không gian quan sát trạm sạc.
2. **Chiều cao màn hình ngang bị lãng phí:**
   - Thanh tiêu đề trên cùng (`TopAppBar`: "Trạm sạc quanh đây \n 10 trạm gần nhất") chiếm ~60dp chiều cao.
   - Dòng pill thông báo bộ lọc ("Tìm thấy 10 trạm có cổng DC <=30kW khả dụng") chiếm thêm ~35dp chiều cao.
   - Kết quả: Danh sách trạm sạc bên dưới bị ép nhỏ, chỉ thấy được khoảng 1.5 thẻ trạm, người lái phải cuộn liên tục.
3. **Mã nguồn bị trộn lẫn:** Giao diện portrait (màn hình đứng) và landscape (màn hình ngang) đang viết chung trong cùng file (`NearbyScreen.kt`, `FavoritesScreen.kt`), gây rối rắm với hàng loạt câu lệnh `if (!effectiveIsLandscape)`.
4. **Tiêu đề trạm sạc bị dài dòng và cắt chữ:** Hầu hết các trạm đều có tiền tố thừa `VinFast - `, `Vinfast - ` khiến tên địa điểm chính (như "TTTM Dabaco Mart Quế Võ") bị dài quá khổ và bị cắt thành dấu `...`.

---

## 2. GIẢI PHÁP ĐÃ THỐNG NHẤT

### 2.1. Chế độ Toàn Màn Hình Tự Động (Immersive Sticky Mode)
- Tự động kích hoạt mặc định trong `MainActivity` bằng `WindowCompat` và `WindowInsetsControllerCompat`:
  - `systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`
  - `controller.hide(WindowInsetsCompat.Type.systemBars())`
  - `WindowCompat.setDecorFitsSystemWindows(window, false)`
- **Trải nghiệm:** Thanh Dock của Carlinkit tự động trượt ẩn khi mở EV+. Người dùng có thể vuốt từ mép trái màn hình để tạm thời gọi thanh Dock/phím Home khi cần, thanh sẽ tự động ẩn lại sau vài giây.

### 2.2. Trục Dọc Điều Hướng Bên Trái (Landscape Navigation Rail 58dp)
- Thu hẹp bề rộng từ 72dp xuống **58dp** để nhường tối đa không gian cho bảng trạm sạc và bản đồ bên phải.
- Toàn bộ các nút được **căn giữa theo trục dọc** (`verticalArrangement = Arrangement.Center`).
- Thiết kế dạng **Icon to tối giản** (không kèm chữ nhỏ bên dưới để giữ rail gọn gàng, touch target đạt chuẩn ô tô an toàn).
- Thứ tự 4 nút từ trên xuống dưới:
  1. `Quanh đây` (Icon `NearMe` / `LocationOn` to 26dp - kích hoạt tab Quanh đây)
  2. `Yêu thích` (Icon `Favorite` to 26dp - kích hoạt tab Yêu thích)
  3. `Cài đặt` (Icon `Settings` bánh răng - mở popup cài đặt lộ trình, bộ lọc tùy chỉnh)
  4. `Làm mới` (Icon `Refresh` xoay - làm mới dữ liệu của tab hiện tại với hiệu ứng xoay khi loading)

### 2.3. Giải Phóng Hoàn Toàn Chiều Cao Hiển Thị (Bỏ TopAppBar & Filter Pill)
- **Bỏ thanh TopAppBar:** Áp dụng đồng bộ cho cả tab **Quanh đây** và **Yêu thích** ở chế độ ngang. Nút Setting và Refresh đã được tích hợp vào trục dọc bên trái nên không còn cần TopAppBar.
- **Bỏ dòng text pill:** Loại bỏ dòng `Tìm thấy 10 trạm có cổng DC <=30kW khả dụng`. Danh sách thẻ trạm được kéo lên sát thanh filter chips.
- Tăng diện tích hiển thị thẳng đứng lên thêm ~95dp, giúp nhìn thấy ngay 3-4 trạm sạc cùng lúc.

### 2.4. Tách Riêng Biệt Code Màn Hình Ngang (Dedicated Landscape Screen)
- Tách hẳn ra các composable chuyên biệt trong package `com.evcs.favorites.ui.screens.landscape`:
  - `NearbyLandscapeScreen.kt`: Bố cục Master-Detail riêng cho Quanh đây.
  - `FavoritesLandscapeScreen.kt`: Bố cục Master-Detail riêng cho Yêu thích.
- Không còn code `if-else` lồng ghép lung tung trong các file màn hình đứng.

### 2.5. Làm Sạch Tiêu Đề & Hiệu Ứng Chữ Chạy (Marquee)
- Nâng cấp `StationNameSanitizer`: Tự động cắt bỏ triệt để tiền tố `VinFast -`, `Vinfast -`, `VINFAST -`, `VinFast:`, `Trạm sạc VinFast -`, v.v.
  - Ví dụ: `"Vinfast - TTTM Dabaco Mart Quế Võ"` ➔ `"TTTM Dabaco Mart Quế Võ"`.
- Thêm `Modifier.basicMarquee()` cho tiêu đề trạm sạc (`StationCard`): Nếu tên trạm vẫn còn dài, chữ sẽ tự động cuộn ngang mượt mà, không bị cắt dấu `...`.

---

## 3. PHẠM VI ẢNH HƯỞNG & CÁC FILE LIÊN QUAN
1. `app/src/main/java/com/evcs/favorites/MainActivity.kt`:
   - Cấu hình Immersive Sticky Mode tự động ẩn Dock Bar.
   - Điều phối layout ngang và truyền callback cho thanh điều hướng dọc.
2. `app/src/main/java/com/evcs/favorites/navigation/AppNavigationRail.kt`:
   - Thu hẹp 58dp, căn giữa 4 icon to: Quanh đây, Yêu thích, Cài đặt, Làm mới.
3. `app/src/main/java/com/evcs/favorites/ui/screens/landscape/NearbyLandscapeScreen.kt` (NEW):
   - Màn hình ngang độc lập không có TopBar và Filter Pill.
4. `app/src/main/java/com/evcs/favorites/ui/screens/landscape/FavoritesLandscapeScreen.kt` (NEW):
   - Màn hình ngang độc lập cho tab Yêu thích không có TopBar.
5. `app/src/main/java/com/evcs/favorites/ui/components/StationCard.kt`:
   - Tích hợp `basicMarquee` trên tiêu đề trạm.
6. `app/src/main/java/com/evcs/favorites/util/StationNameSanitizer.kt`:
   - Bổ sung regex làm sạch tiền tố VinFast.
7. `app/src/test/java/com/evcs/favorites/util/StationNameSanitizerTest.kt` (NEW):
   - Unit test kiểm thử các trường hợp tên trạm.

---

## 4. BƯỚC TIẾP THEO
→ Chuyển sang workflow `/plan` để lên kế hoạch chi tiết hoặc tiến hành triển khai mã nguồn (`/code`).
