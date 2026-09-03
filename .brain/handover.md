# Handover Document - TramsacEV (EVCS Favorites & Nearby Stations)

**Ngày cập nhật:** 2026-09-03 19:46:00 (GMT+7)  
**Thiết bị kiểm thử:** OnePlus 13R (CPH2691 - ADB ID: `3B658D010BU00000`)  
**Trạng thái kế hoạch:** Plan `260903-station-display-filter-persistence-and-tab-reorder` hoàn thành **100% (3/3 phases)**

---

## 📍 Đang làm:
- Kế hoạch hiện tại: **Station Display Refinements, Filter Persistence & Bottom Navigation Tab Reordering** đã hoàn thành trọn vẹn.
- APK mới nhất (`app-debug.apk`) đã build và cài đặt thành công lên điện thoại thật.
- Đã xác thực giao diện qua ảnh chụp màn hình adb và giải đáp cơ chế định tuyến khoảng cách đường thẳng (Haversine).

---

## ✅ ĐÃ XONG:
1. **Phase 01: Chuẩn hóa tên trạm & hiển thị đa dòng**
   - Tạo `StationNameSanitizer` loại bỏ tiền tố khoảng cách (`5.4km » `, `9.1km » `, v.v.).
   - Tích hợp vào `EvcsRepository` và `StationUrlBuilder`.
   - Bọc dòng tối đa 3 dòng (`maxLines = 3`) và căn `Alignment.Top` cho badge trạng thái trong `StationCard` & `StationDetailModal`.
   - Test: `StationNameDisplayAndSanitizationTest.kt` (PASS).
2. **Phase 02: Lưu trữ bộ lọc công suất qua các lần mở app**
   - Tạo `NearbyFilterPreferences` backed bởi `SessionStorage` (`EncryptedSharedPrefsStorage`).
   - Tích hợp vào `NearbyViewModel` (khởi tạo, lưu khi toggle/clear, tự động áp dụng khi scan trạm).
   - Inject factory tại `MainActivity.kt`.
   - Test: `NearbyFilterPersistenceTest.kt` (PASS).
3. **Phase 03: Đổi thứ tự tab điều hướng dưới & màn hình mở đầu**
   - Đổi thứ tự `AppTab`: `NEARBY` ("Quanh đây") vị trí 0 (trái), `FAVORITES` ("Yêu thích") vị trí 1 (phải).
   - Đặt `AppTab.NEARBY` làm điểm đến khởi đầu mặc định khi mở app (`MainActivity.kt`).
   - Hỗ trợ unauthenticated guest khám phá trạm sạc quanh đây ngay lập tức mà không bị chặn đăng nhập.
   - Test: `BottomNavigationTabReorderTest.kt` (PASS).
4. **Build, Deploy & Kiểm thử thực tế**:
   - Build `assembleDebug` ra `app-debug.apk` (18 MB).
   - Cài đặt qua ADB MCP tool vào OnePlus 13R.
   - Chụp màn hình UI thực tế, kiểm tra hiển thị chip lọc 20kW, khoảng cách đường thẳng, và thứ tự tab.

---

## 🔧 QUYẾT ĐỊNH QUAN TRỌNG:
- **Ưu tiên màn hình Quanh đây làm default**: Giúp tài xế xe điện mở app là thấy ngay trạm sạc gần nhất kèm số lượng cổng trống thay vì phải đăng nhập trước.
- **Lưu trữ filter qua SessionStorage**: Đảm bảo dùng chung abstraction bảo mật `SessionStorage` có sẵn, dễ dàng test trên JVM với `InMemorySessionStorage`.
- **Cơ chế định tuyến 3 tầng**: Google Routes API v2 (Tier 1) -> OSRM Table Service (Tier 2) -> Haversine Baseline (Tier 3). Khi chưa có Google API Key hoặc OSRM chưa trả kết quả, app hiển thị ngay khoảng cách đường thẳng offline trong 0ms.

---

## 📁 CÁC FILE QUAN TRỌNG:
- `plans/260903-station-display-filter-persistence-and-tab-reorder/plan.md` (Kế hoạch tổng thể)
- `app/src/main/java/com/evcs/favorites/data/preferences/NearbyFilterPreferences.kt` (Lưu bộ lọc)
- `app/src/main/java/com/evcs/favorites/navigation/AppTab.kt` (Thứ tự tab)
- `app/src/main/java/com/evcs/favorites/MainActivity.kt` (Khởi tạo Compose UI & default tab)
- `app/src/main/java/com/evcs/favorites/util/StationNameSanitizer.kt` (Làm sạch tên trạm)
- `.brain/brain.json` (Tri thức tĩnh của dự án)
- `.brain/session.json` (Trạng thái phiên làm việc)
- `CHANGELOG.md` (Nhật ký thay đổi)

---

## 📍 Để tiếp tục: Gõ `/recap` trong phiên làm việc mới!
