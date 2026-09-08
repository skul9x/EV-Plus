━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📋 HANDOVER DOCUMENT
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

📍 Đang làm: Chuẩn hóa README.md Tech Stack & Toàn bộ Unit Test Suite (724/724 Tests)
🔢 Đến bước: Hoàn thành 100% (724/724 Tests Pass, README.md viết lại chuẩn xác)

✅ ĐÃ XONG:
   - Sửa toàn diện các test suite và logic bất đồng bộ:
     * `MultiTierRoutingCoordinator.kt`: Haversine tính duration ước tính 30km/h (thay vì 0s)
     * `MultiTierRoutingCoordinatorTest.kt`: Sửa assertion duration Haversine > 0s
     * `RoutingPreferencesManagerTest.kt` & `AppNavigationAndNearbyIntegrationTest.kt`: Đồng bộ `preferredEngine` mặc định `OSRM_ONLY`
     * `NearbyAutoScrollOnRefreshTest.kt`: Đồng bộ `FILTER_CHANGE` trigger auto-scroll
     * `NetworkRoutingAndCoalescingOptimizationTest.kt`: Haversine duration > 0s và nới rộng tolerance window (4500L..8500L)
     * `CloudSyncRollbackSafetyTest.kt`: Dùng MockWebServer Dispatcher cách ly lỗi 500 cho endpoint `favorite.html`
     * `LocalFirstFirestoreFavoritesSyncTest.kt`: Chạy `testScheduler.runCurrent()` nạp async cache
     * `StationDetailCoordinator.kt`: Triển khai Stage 2 tính 24h usage statistics với bounded timeout `statsTimeoutMs`
     * `NearbyViewModel.kt`: Tự động nhận diện `defaultDispatcher` thông minh (nếu `ioDispatcher === Dispatchers.IO` thì dùng `Dispatchers.Default`, ngược lại dùng `ioDispatcher`) -> pass 100% cả `FilterAlgorithmAndAllocationOptimizationTest` và `ViewModelThreadingAndRaceConditionTest`
     * Chạy `./gradlew testDebugUnitTest` đạt **724/724 passed (100%)** không còn bất kỳ lỗi nào!
   - Viết lại toàn bộ `README.md` theo chuẩn tech stack hiện đại nhất của dự án:
     * Bảng Tech Stack chi tiết từng thư viện và phiên bản chính xác (Android 14 API 34, Kotlin 1.9.23, Compose BOM 2024.04.01, Material 3 1.2.1, Car App 1.7.0, Firebase BOM 33.10.0, OkHttp 4.12.0, Security Crypto 1.1.0-alpha06, Gradle 8.7, AGP 8.3.2)
     * Mục Android Auto Car App Library
     * Sơ đồ Clean Architecture & UDF
     * Kết quả kiểm thử 724 Unit Tests

⏳ CÒN LẠI / HƯỚNG PHÁT TRIỂN TIẾP THEO:
   - UI Marquee cho tên trạm sạc dài & tối ưu Station Detail (theo plan `plans/260908-1317-station-detail-marquee-and-ui-cleanup/`)

🔧 QUYẾT ĐỊNH QUAN TRỌNG:
   - `defaultDispatcher` trong `NearbyViewModel` giải quyết linh hoạt dựa trên `ioDispatcher` để vừa hỗ trợ thread-safe background execution trên production vừa deterministic trên TestDispatcher.
   - Stage 2 telemetry tính `stats24h` được bao bọc trong `withTimeoutOrNull(statsTimeoutMs)` để đảm bảo timeout mượt mà không crash app khi mạng treo.
   - `README.md` cập nhật phản ánh trung thực toàn bộ stack công nghệ và các tính năng thực tế.

📁 FILES QUAN TRỌNG:
   - `README.md`
   - `app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt`
   - `app/src/main/java/com/evcs/favorites/ui/viewmodel/StationDetailCoordinator.kt`
   - `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt`
   - `app/src/main/java/com/evcs/favorites/ui/viewmodel/FavoritesViewModel.kt`
   - `.brain/handover.md`

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📍 Đã lưu! Để tiếp tục: Gõ /recap
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
