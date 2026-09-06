# BRIEF: Focus Mode & Live Telemetry Navigation Tracker

**Ngày tạo:** 2026-09-06
**Tính năng:** Focus Mode (Theo dõi súng sạc DC thời gian thực khi di chuyển) & Auto-Scroll Refresh

---

## 1. VẤN ĐỀ CẦN GIẢI QUYẾT
- Tài xế xe điện VinFast trên đường di chuyển đến trạm sạc thường xuyên gặp tình trạng trạm bị xe khác cắm kín súng ngay trước khi đến nơi.
- Người lái xe không thể vừa điều khiển xe, vừa mở app EV-Plus để kiểm tra lại cổng sạc thủ công.

## 2. GIẢI PHÁP ĐỀ XUẤT
- **Focus Mode**: 1 chạm mở Google Maps dẫn đường, đồng thời khởi chạy **Floating Window (Cửa sổ nổi)** hiển thị số lượng súng sạc DC trống thời gian thực.
- **Dynamic Polling**: Tần suất cập nhật tự động tăng dần khi xe đến gần trạm (15s -> 10s -> 5s).
- **Âm thanh & Giọng nói cảnh báo (TTS)**: Phát cảnh báo khi trạm hết súng sạc DC.
- **1-Tap Auto Reroute**: Tự động tìm và gợi ý trạm DC tương đương công suất gần nhất để chuyển hướng 1 chạm.
- **Tab Quanh Đây Auto-Scroll**: Tự động cuộn lên vị trí đầu tiên (trạm gần nhất) khi làm mới dữ liệu thành công.

## 3. THÔNG SỐ KỸ THUẬT & KIẾN TRÚC
1. **Network**:
   - Tier 1: HERE Maps EV API (OAuth 1.0a Client Credentials VinFast).
   - Tier 2: EVCS Search API Fallback.
2. **Android Components**:
   - `FocusModeForegroundService`: Giữ kết nối polling liên tục không bị Android Doze kill.
   - `FocusModeFloatingViewManager`: Quản lý WindowManager `TYPE_APPLICATION_OVERLAY`.
   - `FocusModeTtsManager`: Xử lý âm thanh Text-To-Speech tiếng Việt.
   - `SYSTEM_ALERT_WINDOW` permission handler kèm fallback sang Notification.
3. **UI / Compose**:
   - Nút `Focus Mode` trong `NativeStationDetailSheet`.
   - `animateScrollToItem(0)` trong `NearbyScreen`.

## 4. QUY TẮC NGHIỆP VỤ (BUSINESS RULES)
- Chỉ track cổng sạc nhanh DC (>= 30kW), bỏ qua cổng AC.
- Nút `[X]` đóng hoàn toàn thủ công.
- Reroute ưu tiên trạm cùng phân khúc công suất còn súng DC trống và gần GPS hiện tại nhất.
