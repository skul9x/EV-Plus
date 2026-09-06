# Handover Document

**Date:** 2026-09-07T01:06:00+07:00  
**Project:** EV-Plus (Android Jetpack Compose)  
**Status:** In Progress (Plan 260907-0055 Completed, Plan 260907-0025 Ready)

---

## 📍 Vừa Hoàn Thành

### Plan: Focus Mode evcs.vn Station Name & 1-Tap Direct Turn-by-Turn Navigation (`plans/260907-0055-focus-mode-name-and-navigation-fix/`)
1. **Phase 01 - 1-Tap Direct Turn-by-Turn Navigation:**
   - [NativeStationDetailSheet.kt](file:///home/skul9x/Desktop/Test_Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt): Kích hoạt `⚡ Focus Mode` gửi trực tiếp Intent dẫn đường lái xe rẽ từng chặng (`google.navigation:q=lat,lon&mode=d`) vào Google Maps, không dừng ở màn hình xem trước pin trạm.
   - [FocusModeDirectNavigationTest.kt](file:///home/skul9x/Desktop/Test_Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/focus/FocusModeDirectNavigationTest.kt): Verified 100% PASS.
2. **Phase 02 - evcs.vn Station Name Resolution & Preservation:**
   - [EvcsStationNameResolver.kt](file:///home/skul9x/Desktop/Test_Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/focus/EvcsStationNameResolver.kt): Resolver tra cứu & cache tên trạm chuẩn từ `evcs.vn` bằng Location ID (ví dụ `c.bni0012` -> `VinFast TTTM Dabaco Mart Quế Võ`) hoặc toạ độ GPS (<= 100m).
   - [FocusModeTelemetryEngine.kt](file:///home/skul9x/Desktop/Test_Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/focus/FocusModeTelemetryEngine.kt): Bảo toàn tên chuẩn trong vòng lặp polling 5s/10s/15s, ngăn Here EV API ghi đè tên chung `"Trạm sạc VinFast"`.
   - Làm giàu tên ứng viên trạm đổi tự động (Reroute Recommendation).
   - [FocusModeEvcsStationNameTest.kt](file:///home/skul9x/Desktop/Test_Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/focus/FocusModeEvcsStationNameTest.kt): Verified 100% PASS.

---

## ⏳ Kế Hoạch Tiếp Theo (Pending Plan)

### Plan: Click-Spam Protection, Concurrency & Edge-Case Hardening (`plans/260907-0025-click-spam-and-edge-case-hardening/`)
1. **Phase 01:** Action Debounce & Throttling Engine (`ActionDebounceAndThrottlingTest.kt`)
2. **Phase 02:** Thread-Safe Favorites Synchronization & Rapid-Click Guard (`FavoriteConcurrencyAndThreadSafetyTest.kt`)
3. **Phase 03:** GPS Timeout, Scan Guard & Android 13+ Notification Permissions (`GpsTimeoutAndNotificationPermissionTest.kt`)
4. **Phase 04:** Client Rate Limit & Lifecycle Edge Cases (`RateLimitAndLifecycleHardeningTest.kt`)

---

## 🔧 Quyết Định Kỹ Thuật Quan Trọng
- **Dual Matching:** Khớp trạm theo Location ID và khoảng cách GPS <= 100m để giải quyết triệt để trường hợp Here API trả về ID khác hệ thống `evcs.vn`.
- **In-Memory Concurrency Caching:** `ConcurrentHashMap` + `CopyOnWriteArrayList` giúp tra cứu 0ms, không tốn thêm network request khi polling định kỳ.
- **Single Test File Strategy:** Mỗi phase chỉ tạo đúng 1 file test duy nhất và chỉ chạy test đó để verify.
