# Handover Document - EV+ (Trạm Sạc EV)

**Ngày cập nhật:** 2026-09-04 08:32:00 (GMT+7)  
**Thiết bị kiểm thử:** OnePlus 13R (CPH2691 - ADB ID: `3B658D010BU00000`)  
**Mã nguồn GitHub:** `https://github.com/skul9x/EV-Plus.git` (Branch: `main`)  
**Trạng thái kế hoạch:** Đã tạo toàn bộ plan chi tiết gồm 4 phase tại `plans/260904-0830-live-station-forecast-cards/` cho tính năng đưa dự báo xe sạc sắp xong ra card trạm ngoài màn hình chính.

---

## 📍 Đang làm:
- **Chuẩn bị triển khai Phase 01**: Domain Models & Parser Implementation.
  - File đặc tả: [phase-01-domain-models-and-parser-implementation.md](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260904-0830-live-station-forecast-cards/phase-01-domain-models-and-parser-implementation.md)
  - Unit test mục tiêu: `StationForecastParserTest.kt`

---

## ✅ ĐÃ XONG:
1. **Brainstorming & Quyết định UX/Kỹ thuật**:
   - Chỉ quét dự báo cho **Top 5 trạm kín gần nhất (`available_ports == 0`)** trên cả 2 tab Quanh đây và Yêu thích.
   - Hiển thị badge Amber `⏱️ Sắp trống` kết hợp Capsule hiển thị đầy đủ chi tiết các xe/trụ sắp xong.
   - Cache In-Memory với TTL 3 phút (xóa ngay khi pull-to-refresh).
   - Retry ngầm Exponential Backoff tối đa 2 lần, fail an toàn không làm phiền người dùng.
2. **Kế hoạch chi tiết theo chuẩn AWF (4 Phase files bằng tiếng Anh)**:
   - [plan.md](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260904-0830-live-station-forecast-cards/plan.md) (Tổng quan)
   - [phase-01-domain-models-and-parser-implementation.md](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260904-0830-live-station-forecast-cards/phase-01-domain-models-and-parser-implementation.md) (Domain Models & HTML Parser)
   - [phase-02-repository-cache-and-network-retry.md](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260904-0830-live-station-forecast-cards/phase-02-repository-cache-and-network-retry.md) (Cache & Exponential Backoff Retry)
   - [phase-03-viewmodel-pipeline.md](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260904-0830-live-station-forecast-cards/phase-03-viewmodel-pipeline.md) (Nearby & Favorites ViewModel Orchestration)
   - [phase-04-ui-stationcard-forecast-capsule.md](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260904-0830-live-station-forecast-cards/phase-04-ui-stationcard-forecast-capsule.md) (StationCard Amber UI & Capsule)
3. **Quy tắc kiểm thử nghiêm ngặt**:
   - Mỗi phase chỉ có đúng 1 file test toàn diện.
   - Sau khi hoàn thành mỗi phase, chỉ chạy đúng 1 test đó để verify rồi dừng chờ review.

---

## ⏳ CÒN LẠI / GỢI Ý BƯỚC TIẾP THEO:
- [ ] Thực hiện Phase 01: Tạo model `StationForecast`, parser `StationForecastParser`, chạy test `StationForecastParserTest.kt`.
- [ ] Sau khi anh review Phase 01 -> tiếp tục Phase 02, 03, 04.

---

## 📁 FILES QUAN TRỌNG:
- [plans/260904-0830-live-station-forecast-cards/plan.md](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260904-0830-live-station-forecast-cards/plan.md)
- [plans/260904-0830-live-station-forecast-cards/phase-01-domain-models-and-parser-implementation.md](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260904-0830-live-station-forecast-cards/phase-01-domain-models-and-parser-implementation.md)
- [get.md](file:///home/skul9x/Desktop/Code/TramsacEV/get.md) (Đặc tả giải thuật SSR Regex & Data model)
- [.brain/session.json](file:///home/skul9x/Desktop/Code/TramsacEV/.brain/session.json) (Trạng thái phiên)
- [.brain/handover.md](file:///home/skul9x/Desktop/Code/TramsacEV/.brain/handover.md) (Bản giao ban ngữ cảnh)

---

## 📍 Để tiếp tục: Gõ `/recap` hoặc `/code phase-01`
