# Handover Document

**Thời gian lưu**: 2026-09-04 15:00:00 (GMT+7)  
**Dự án**: EV+ (TramsacEV)  
**Trạng thái phiên làm việc**: Hoàn tất xuất sắc và đã cài đặt APK lên thiết bị thực qua MCP.

---

## 📌 Tổng hợp công việc vừa hoàn thành trong phiên

1. **Điều tra & gỡ lỗi từ file `debug-log.txt`**:
   - Xác định nguyên nhân lỗi Cloudflare **HTTP 429 (Mã lỗi 1015 - You are being rate limited)** do gửi bão request làm giàu dự báo trong thời gian ngắn.
   - Phát hiện điểm yếu của regex cũ trong `StationForecastParser` khi gặp câu phức ghép nhiều nhóm trụ (ví dụ: `3 xe sạc trụ 120kW sẽ xong trong 5-21 phút, 1 xe sạc trụ 60kW sẽ xong trong 1 phút nữa`).

2. **Cải tiến mã nguồn & Kiến trúc**:
   - [`StationForecastParser.kt`](file:///home/skul9x/Desktop/Code/TramsacEV/app/src/main/java/com/evcs/favorites/data/parser/StationForecastParser.kt):
     - Thêm `CLAUSE_REGEX` và `FULL_SENTENCE_REGEX` để bóc tách chính xác từng nhóm công suất trong câu phức.
     - Tự động sinh `ForecastSession` ảo khi không có JSON script, kích hoạt `isMultiSession = true` và nhóm công suất đầy đủ.
   - [`EvcsRepository.kt`](file:///home/skul9x/Desktop/Code/TramsacEV/app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt):
     - Đưa HTTP 429 & 1015 vào nhóm Non-transient (không retry ngắn 1s/2s).
     - Bổ sung **Circuit Breaker** `globalRateLimitedUntil` (ngắt mạch 60 giây). Khi bị 429, toàn bộ request nền tự động dừng gọi mạng để chờ IP hết hạn block.
     - Thêm cơ chế **Pacing Delay** (`delay(index * 150L)`) giữa các trạm khi batch enrich.
     - Chuyển log banner `amd-locked` sang mức `INFO`.

3. **Kiểm thử chuyên biệt & Triển khai**:
   - Xây dựng file test riêng: [`ForecastRateLimitAndCompoundParserTest.kt`](file:///home/skul9x/Desktop/Code/TramsacEV/app/src/test/java/com/evcs/favorites/data/repository/ForecastRateLimitAndCompoundParserTest.kt) -> **BUILD SUCCESSFUL in 2s** (100% pass).
   - Build file APK `app-debug.apk` (18.5MB), cài đặt thành công vào thiết bị thực `3B658D010BU00000` và tự động mở app qua MCP adb.

---

## 📁 Các tệp trọng tâm

- `app/src/main/java/com/evcs/favorites/data/parser/StationForecastParser.kt`: Bộ bóc tách dự báo sạc đa câu/đa trụ.
- `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt`: Repository với cơ chế Circuit Breaker & Pacing.
- `app/src/test/java/com/evcs/favorites/data/repository/ForecastRateLimitAndCompoundParserTest.kt`: Bộ kiểm thử chuyên biệt.
- `.brain/brain.json` & `.brain/session.json`: Bộ nhớ dự án.

---

*Để khôi phục ngữ cảnh làm việc cho phiên tiếp theo, chỉ cần gõ `/recap`.*
