# 📋 HANDOVER DOCUMENT

📍 **Đang làm**: VinFast Station Fixes, Real-time GPS Refresh & Pre-Filter Selection UX  
🔢 **Đến bước**: Toàn bộ 3/3 Phase đã hoàn thành 100%, Build APK & Cài đặt thành công lên thiết bị thật

---

### ✅ ĐÃ XONG:
- **Phase 01**: VinFast Station Domain Modeling & Canonical Detail URL Builder Fix (`VinFastStationMappingAndUrlBuilderTest.kt` ✓ PASS).
- **Phase 02**: Real-time GPS Refresh & Location Error Handling (`NearbyRealtimeGpsRefreshTest.kt` ✓ PASS).
- **Phase 03**: Pre-Filter Selection on Initial Screen, Persistence & Immediate Execution (`NearbyPreFilterAndInitialScanTest.kt` ✓ PASS).
- **Build & Deploy**:
  - Build file APK debug: `app/build/outputs/apk/debug/app-debug.apk` ✓.
  - Tự động gỡ bản cũ xung đột signature và cài đặt bản mới lên OnePlus 13R (`3B658D010BU00000`) qua ADB MCP ✓.
  - Tự động khởi chạy ứng dụng `com.evcs.favorites` trên máy ✓.

---

### ⏳ CÒN LẠI / GỢI Ý TIẾP THEO:
- Trải nghiệm thực tế app trên đường phố:
  - Kiểm tra độ nhạy của nút refresh GPS khi đang di chuyển.
  - Kiểm tra tính năng chọn trước bộ lọc công suất AC/DC trên màn hình Hero trước khi bấm tìm trạm.

---

### 🔧 QUYẾT ĐỊNH QUAN TRỌNG:
1. **Canonical URL Builder**: Chuẩn hóa pattern `/tram-sac-vinfast-${slug}-${locationId.toLowerCase()}.html`, lọc bỏ triệt để tiền tố trùng lặp `vinfast-` và mã đối tác trùng `-c.C.`.
2. **Search API Request**: Chỉ gửi `{"latitude": ..., "longitude": ...}` (bỏ `wattageTypes`) để backend trả về toàn bộ trạm, ủy quyền bộ lọc client-side xử lý chính xác theo thời gian thực.
3. **Realtime GPS Refresh**: Không tái sử dụng tọa độ cũ trong memory; luôn lấy GPS tươi mới, nếu mất sóng GPS sẽ báo lỗi rõ ràng để tài xế thử lại.
4. **Pre-Filter Initial Screen UX**: Đưa `SmartFilterBar` lên màn hình ban đầu, khôi phục từ `SmartFilterPreferences` và lưu ngay khi đổi chế độ; tự động áp dụng ngay sau khi quét GPS.

---

### 📁 FILES QUAN TRỌNG:
- `app/src/main/java/com/evcs/favorites/util/StationUrlBuilder.kt` (Xây dựng canonical URL chuẩn)
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt` (GPS refresh & SmartFilter pipeline)
- `app/src/main/java/com/evcs/favorites/ui/screens/NearbyScreen.kt` (Hero screen UI với SmartFilterBar)
- `plans/260905-1400-vinfast-station-fixes-prefilter-and-gps-refresh/plan.md` (Plan chi tiết)
- `CHANGELOG.md` (Lịch sử thay đổi)
- `.brain/brain.json` & `.brain/session.json` (Bộ nhớ AWF)
