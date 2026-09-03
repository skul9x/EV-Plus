# Handover Document - EV+ (Trạm Sạc EV)

**Ngày cập nhật:** 2026-09-03 20:10:00 (GMT+7)  
**Thiết bị kiểm thử:** OnePlus 13R (CPH2691 - ADB ID: `3B658D010BU00000`)  
**Mã nguồn GitHub:** `https://github.com/skul9x/EV-Plus.git` (Branch: `main`)  
**Trạng thái ứng dụng:** Hoàn thiện 100% tính năng MVP + Nearby Stations + Multi-Tier Routing + Adaptive Launcher + Tên app "EV+"

---

## 📍 Đang làm:
- Dự án đã hoàn tất toàn bộ các tính năng đặt ra:
  1. Tra cứu trạm sạc quanh đây (Nearby) kèm số cổng trống thời gian thực.
  2. Định tuyến 3 tầng (Google Routes v2 Live Traffic / OSRM Table / Haversine offline).
  3. Bộ lọc công suất sạc ghi nhớ liên phiên (Filter Persistence qua SessionStorage).
  4. Quản lý trạm sạc yêu thích & đồng bộ tài khoản EVCS hai chiều.
  5. Đổi tên ứng dụng ngắn gọn thành **"EV+"** và tạo bộ icon thích ứng (Adaptive Launcher Icon) chuẩn Android.
  6. Dọn dẹp thư mục gốc, chuyển dữ liệu app gốc vào `original_app_data/`.
  7. Khởi tạo Git repo và force push thành công lên GitHub.
  8. Cài đặt APK `app-debug.apk` và kiểm thử trực tiếp trên điện thoại thật OnePlus 13R.

---

## ✅ ĐÃ XONG:
1. **Adaptive Launcher Icon (API 26+)**:
   - `ic_launcher_background.xml`: Nền đen Obsidian viền hào quang phát quang xanh Emerald & Cyan.
   - `ic_launcher_foreground.xml`: Tia sét năng lượng cao pure white kết hợp huy hiệu dấu cộng `+` phát sáng màu cyan.
   - `mipmap-anydpi-v26/ic_launcher.xml` & `ic_launcher_round.xml`: Tương thích mọi launcher Android (tròn, vuông, squircle).
2. **Rút gọn tên ứng dụng thành "EV+"**:
   - `strings.xml`: `<string name="app_name">EV+</string>`.
   - `AndroidManifest.xml`: Trỏ `android:label="@string/app_name"` và `android:icon="@mipmap/ic_launcher"`.
   - `MainActivity.kt`: Cập nhật dialog xin quyền vị trí cho "EV+".
3. **Tái cấu trúc thư mục sạch sẽ**:
   - Tạo thư mục `original_app_data/` lưu trữ file .xapk, source code decompile JADX, apktool smali, web templates HTML/JS và script test reverse-engineering.
   - Tạo `original_app_data/README.md`.
4. **Git & GitHub Deployment**:
   - Tạo file `.gitignore` chuẩn Android Native loại trừ build cache và file nhị phân nặng.
   - Initialized Git, commit 140 files và force push lên repository `https://github.com/skul9x/EV-Plus.git`.
5. **Biên dịch & Test thiết bị thật**:
   - Chạy toàn bộ test suites (`./gradlew test` ➔ 100% Pass).
   - Biên dịch `assembleDebug` và cài đặt lên OnePlus 13R qua ADB MCP.

---

## ⏳ CÒN LẠI / GỢI Ý BƯỚC TIẾP THEO:
- [ ] Tích hợp thông báo đẩy (Push Notifications) khi trạm sạc yêu thích có cổng sạc vừa được giải phóng rảnh.
- [ ] Tích hợp widget màn hình chính (Android AppWidget) hiển thị nhanh 3 trạm sạc gần nhất.
- [ ] Lọc trạm theo hãng xe tương thích hoặc nhà vận hành trạm sạc đối tác.

---

## 🔧 QUYẾT ĐỊNH QUAN TRỌNG:
- **Tên app "EV+"**: Ngắn gọn, hiện đại, dễ nhận diện và không bị cắt ngắn (ellipsis) trên launcher điện thoại.
- **Adaptive Icon chuẩn Vector**: Không dùng ảnh bitmap cố định, hoàn toàn sắc nét ở mọi mật độ điểm ảnh (hdpi, xhdpi, xxhdpi, xxxhdpi) và tương thích mọi hình dạng mask launcher.
- **Tách biệt dữ liệu reverse engineering**: Gom toàn bộ file nhị phân nặng và assets gốc vào `original_app_data/` để repository GitHub nhẹ, sạch và đúng chuẩn MAD.

---

## 📁 FILES QUAN TRỌNG:
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` (Adaptive icon)
- `app/src/main/res/drawable/ic_launcher_background.xml` (Icon background)
- `app/src/main/res/drawable/ic_launcher_foreground.xml` (Icon foreground)
- `app/src/main/res/values/strings.xml` (App name "EV+")
- `app/src/main/AndroidManifest.xml` (Manifest khai báo icon & tên)
- `README.md` (Tài liệu tổng quan dự án)
- `.gitignore` (Quy tắc lọc Git)
- `.brain/brain.json` (Tri thức tĩnh của dự án)
- `.brain/session.json` (Trạng thái phiên làm việc)

---

## 📍 Để tiếp tục: Gõ `/recap` trong phiên làm việc mới!
