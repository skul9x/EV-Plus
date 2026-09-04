# Handover Document - Milestone: Native Station Detail Bottom Sheet

📍 **Dự án**: EV+ (Android Jetpack Compose)  
🔢 **Trạng thái**: Hoàn tất 100% Milestone Native Station Detail Bottom Sheet (5/5 Phases)  
📅 **Cập nhật**: 2026-09-05 02:40:00 (GMT+7)  

---

## ✅ ĐÃ XONG TOÀN DIỆN (5/5 PHASES)

1. **Phase 01: Domain Telemetry and 24h Stats Models**
   - Tạo các domain models: `StationTelemetry`, `StationPortStatus`, `StationRating`, `Station24hStats`, `StationAccessTokens`.
   - Bóc tách phân phối cổng bận theo công suất kW (`busyKw`) và làm sạch ticker dự báo HTML (`cleanForecast`).
   - Test: `StationTelemetryModelsAndParserTest.kt` (100% PASS).

2. **Phase 02: EvcsTelemetryRepository & 24h Stats Engine**
   - Tạo `EvcsTelemetryRepository` và `EvcsTelemetryDataSource` thực hiện 3-step handshake:
     1. Post `{station-slug}.html` lấy ephemeral tokens (`chargeToken`, `apiToken`) và community rating (`avg`, `count`).
     2. Post `/charging` lấy real-time busy count per kW và forecast ticker.
     3. Socket.io WebSocket tới `www2.evcs.vn` lấy `history_data` 24h với timeout 4s.
   - Xây dựng `Station24hStatsCalculator` tính toán chuẩn công thức: Cao điểm (Peak), Trung bình (Mean), Giờ cao điểm (Rush Hour UTC+7), Tỉ lệ lấp đầy (Fill Rate).
   - Test: `EvcsTelemetryRepositoryAndStatsEngineTest.kt` (100% PASS).

3. **Phase 03: ViewModel On-Demand Telemetry Pipeline**
   - Xây dựng `StationDetailCoordinator` điều phối pipeline 2 giai đoạn:
     - Mở tức thì (0ms) với static power ports.
     - Stage 1: Tokens + Rating + Live Ports.
     - Stage 2: 24h Stats.
   - Tích hợp vào `FavoritesViewModel` và `NearbyViewModel`.
   - Tự động hủy toàn bộ coroutine jobs khi đóng bottom sheet.
   - Test: `StationDetailViewModelPipelineTest.kt` (100% PASS).

4. **Phase 04: Native Compose Bottom Sheet UI**
   - Xây dựng `NativeStationDetailSheet.kt` 100% Jetpack Compose (Material 3):
     - Header trạm (Tên tối đa 3 dòng, địa chỉ, rating cộng đồng, cự ly/ETA lái xe).
     - Row hành động nhanh: Nút "Chỉ đường" (mở Google Maps navigation), "Yêu thích", "Chia sẻ".
     - Badge danh sách cổng sạc theo từng kW với chấm màu trạng thái.
     - Capsule dự báo sạc sạch (ẩn khi locked hoặc null).
     - Lưới 2x2 hiển thị 4 chỉ số thống kê 24h kèm hiệu ứng shimmer loading.
     - Không cố định chiều cao, hỗ trợ cuộn tự nhiên chống text clipping.
   - Test: `NativeStationDetailSheetUiTest.kt` (100% PASS).

5. **Phase 05: Screen Integration & Legacy WebView Decoupling**
   - Tích hợp vào `MainActivity.kt`, `FavoritesScreen.kt`, `NearbyScreen.kt`.
   - Xóa bỏ vĩnh viễn `StationDetailModal.kt` và các file CSS WebView.
   - Giải quyết triệt để lỗi rò rỉ bộ nhớ `PERF-MEM-02` (15-45MB RAM/lần xem).
   - Cập nhật các legacy unit tests và xây dựng test tích hợp toàn diện: `NativeStationDetailIntegrationTest.kt` (100% PASS).
   - Build và cài đặt APK thành công lên thiết bị thật.

---

## 🔧 QUYẾT ĐỊNH QUAN TRỌNG

1. **Khử bỏ hoàn toàn WebView**:
   - WebView nhúng gây giật lag, tốn 300KB+ data web, xung đột cử chỉ vuốt và rò rỉ RAM nghiêm trọng. Thay bằng 100% Native Compose giúp mở sheet dưới 50ms và tiêu thụ RAM cực thấp.
2. **Quản lý vòng đời qua Coordinator**:
   - Mọi kết nối mạng và Socket.io 24h history được gắn với lifecycle của bottom sheet và tự động hủy bỏ ngay khi người dùng đóng modal.
3. **Tính toán chuẩn UTC+7**:
   - Phân tích giờ cao điểm dựa trên timezone Việt Nam `(timestamp + 25,200,000ms)` đồng bộ tuyệt đối với logic của EVCS gốc.

---

## 📁 FILES QUAN TRỌNG
- `plans/260905-0115-native-station-detail-bottom-sheet/plan.md`: Master Plan toàn bộ milestone.
- `app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt`: Giao diện Native Bottom Sheet.
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/StationDetailCoordinator.kt`: Bộ điều phối lifecycle on-demand.
- `app/src/main/java/com/evcs/favorites/data/repository/EvcsTelemetryRepository.kt`: Repository xử lý API tokens và Socket.io history.
- `.brain/brain.json` & `.brain/session.json`: Bộ nhớ ngữ cảnh vĩnh cửu.
