# Handover Document

**Date:** 2026-09-08T07:45:00+07:00  
**Project:** EV-Plus (Android Jetpack Compose)  
**Status:** Completed & Deployed (Plan 260907-0025 Click-Spam & Hardening 100% Done, APK Built & Installed)

---

## 📍 Vừa Hoàn Thành

### Plan: Click-Spam Protection, Concurrency & Edge-Case Hardening (`plans/260907-0025-click-spam-and-edge-case-hardening/`)
1. **Phase 01 - Action Debounce & Throttling Engine:**
   - [DebounceHelper.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/util/DebounceHelper.kt): Throttling 1-Tap navigation (`MapNavigator`), debounced Focus Mode & reroute clicks, OTP auto-submit double-click protection, Google Sign-In button disabling.
   - [ActionDebounceAndThrottlingTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/hardening/ActionDebounceAndThrottlingTest.kt): 100% PASS.
2. **Phase 02 - Thread-Safe Favorites Synchronization & Rapid-Click Guard:**
   - [FirestoreFavoritesRepository.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/data/repository/FirestoreFavoritesRepository.kt): `Mutex` synchronization cho các thao tác thêm/xóa trạm yêu thích, flow `togglingStationIds` vô hiệu hóa nút trong lúc sync, tự động rollback khi cloud sync thất bại và chặn spam toast.
   - [FavoriteConcurrencyAndThreadSafetyTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/hardening/FavoriteConcurrencyAndThreadSafetyTest.kt): 100% PASS.
3. **Phase 03 - GPS Timeout, Scan Guard & Android 13+ Notification Permissions:**
   - [LocationService.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/domain/location/LocationService.kt): Timeout GPS 8 giây (`withTimeoutOrNull`), fallback báo lỗi tiếng Việt thân thiện khi GPS treo.
   - [NearbyViewModel.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt): Guard `scanJob` ngăn chặn spam nút refresh khi đang scan.
   - [FocusModeForegroundService.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/focus/FocusModeForegroundService.kt): Kiểm tra quyền `POST_NOTIFICATIONS` runtime trên Android 13+ (API 33+).
   - [GpsTimeoutAndNotificationPermissionTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/hardening/GpsTimeoutAndNotificationPermissionTest.kt): 100% PASS.
4. **Phase 04 - Client Rate Limit Cooldown & Lifecycle Hardening:**
   - [EvcsApiClient.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/data/api/EvcsApiClient.kt) & [EvcsRepository.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt): Kiểm tra client rate limit trước khi gửi network request, ném `RateLimitException` ngay lập tức nếu đang trong thời gian cooldown HTTP 429.
   - [StationPhotoViewerModal.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/components/StationPhotoViewerModal.kt): Cải tiến thao tác vuốt ảnh lightbox đổi trạng thái trực tiếp, loại bỏ cấp phát coroutine từng khung hình.
   - [FocusModeTtsManager.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/focus/FocusModeTtsManager.kt): Watchdog timeout 6s tự động nhả Audio Focus Ducking nếu engine TTS bên thứ 3 bị treo.
   - [NearbyScreen.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/screens/NearbyScreen.kt) & [MainActivity.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/MainActivity.kt): Dùng `rememberSaveable` bảo toàn trạng thái dialog qua xoay màn hình.
   - [RateLimitAndLifecycleHardeningTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/hardening/RateLimitAndLifecycleHardeningTest.kt): 100% PASS.

### Build & Deployment:
- Build debug APK hoàn tất: `app/build/outputs/apk/debug/app-debug.apk` (15.9 MB).
- Cài đặt và khởi chạy thành công lên thiết bị Android (`3B658D010BU00000`) qua MCP ADB.

---

## 🔧 Quyết Định Kỹ Thuật Quan Trọng
- **Client-Side Cooldown Check:** Tránh gửi thêm request khi đang bị rate-limit để không làm kéo dài thời gian chặn IP của Cloudflare/EVCS.
- **Audio Ducking Watchdog:** Đảm bảo âm thanh của xe/ứng dụng phát nhạc không bao giờ bị giảm âm lượng vĩnh viễn nếu engine TTS gặp lỗi.
- **Mutex Favorites Sync:** Đảm bảo tính nhất quán dữ liệu favorites giữa local cache và Firebase Firestore.
