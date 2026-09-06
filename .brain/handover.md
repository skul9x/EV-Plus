━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📋 HANDOVER DOCUMENT
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

📍 Đang làm: Click-Spam Protection, Concurrency & Edge-Case Hardening
🔢 Đến bước: Đã hoàn thiện & chuẩn hóa Plan (4 phases), sẵn sàng thực thi Phase 01

✅ ĐÃ XONG:
   - Feature trước: Focus Mode 20kW DC, Auto-Scroll Filter Fixes & AC Sheet (3/3 phases verified 100% PASS) ✓
   - Rà soát toàn diện: 8 lỗ hổng spam click và 7 edge cases trong toàn bộ codebase EV Plus ✓
   - Master Plan & 4 Phase Files chuẩn hóa: plans/260907-0025-click-spam-and-edge-case-hardening/ ✓
   - Quy chuẩn kiểm thử: Mỗi phase chỉ có đúng 1 file test duy nhất, chạy 1 lần và dừng chờ user review ✓

⏳ CÒN LẠI:
   - Phase 01: Action Debounce & Throttling Engine (ActionDebounceAndThrottlingTest.kt)
   - Phase 02: Thread-Safe Favorites Synchronization & Rapid-Click Guard (FavoriteConcurrencyAndThreadSafetyTest.kt)
   - Phase 03: GPS Timeout, Scan Guard & Android 13+ Notification Permissions (GpsTimeoutAndNotificationPermissionTest.kt)
   - Phase 04: Client Rate Limit & Lifecycle Edge Cases (RateLimitAndLifecycleHardeningTest.kt)

🔧 QUYẾT ĐỊNH QUAN TRỌNG:
   - Phòng thủ đa tầng: Kết hợp vô hiệu hóa nút bấm theo State (isNavigating, isVerifyingOtp, togglingStationIds) với DebounceHelper (1000ms cooldown) và Mutex synchronization.
   - GPS Timeout 8 giây: Sử dụng withTimeoutOrNull(8000L) kèm cancellation cleanup cho FusedLocationProviderClient để chống treo UI khi tắt GPS.
   - Android 13+ Runtime Permission: Tích hợp rememberLauncherForActivityResult cho Manifest.permission.POST_NOTIFICATIONS.
   - Pre-network 429 Check: Kiểm tra isGlobalRateLimited() trước khi mở socket HTTP để tránh kéo dài lệnh chặn IP từ Cloudflare/EVCS.

⚠️ LƯU Ý CHO SESSION SAU:
   - Lệnh bắt đầu Phase 01: Gõ `/code` hoặc triển khai theo `phase-01-action-debounce-and-throttling.md`.
   - Mỗi phase chỉ chạy đúng 1 file test `./gradlew testDebugUnitTest --tests "<TestClass>"`, sau đó dừng chờ review.

📁 FILES QUAN TRỌNG:
   - plans/260907-0025-click-spam-and-edge-case-hardening/plan.md (Master plan)
   - plans/260907-0025-click-spam-and-edge-case-hardening/phase-01-action-debounce-and-throttling.md (Phase kế tiếp)
   - .brain/brain.json (Tri thức tĩnh hệ thống)
   - .brain/session.json (Trạng thái phiên làm việc)
   - CHANGELOG.md (Nhật ký thay đổi)

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📍 Đã lưu! Để tiếp tục: Gõ /recap hoặc /code
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
