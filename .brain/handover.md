━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📋 HANDOVER DOCUMENT
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

📍 Đang làm: Tối ưu hóa hiệu năng luồng tìm trạm sạc AC (AC Charging Flow Performance Optimization)
🔢 Đến bước: Hoàn thành 100% cả 3 Phase, pass 100% test suites, cập nhật tài liệu và đã push lên branch `main` GitHub.

✅ ĐÃ XONG:
   1. **Phase 01: Low-Allocation Pre-Filter & Mapping Optimization**:
      * Lọc thô danh sách `HereEvStation` DTO trước khi khởi tạo domain models hay parse power ports, giảm thiểu phân bổ bộ nhớ (GC churn) trong chu kỳ quét 5s/10s.
      * Tối ưu quantization công suất và gộp cổng sạc trong `HereModelMapper`.
      * Verification: `AcStationPreFilterAndMappingOptimizationTest.kt` (100% PASS).

   2. **Phase 02: Streaming HTML Resolution & 60m TTL In-Memory Caching**:
      * Đọc regex title trực tiếp trên `ResponseBody.byteStream()` của OkHttp, loại bỏ việc nạp toàn bộ chuỗi HTML nhiều MB vào RAM.
      * Thêm bộ nhớ đệm trong RAM với TTL 60 phút (`CachedHtmlTitle`), ngăn ngừa gửi request cào HTML trùng lặp.
      * Verification: `AcStationStreamingNameResolutionAndCacheTest.kt` (100% PASS).

   3. **Phase 03: Progressive Staged UI Rendering & Coroutine Lifecycle Management**:
      * Tách `executeAcHybridPipeline` thành pipeline hiển thị 2 giai đoạn:
        - **Stage 1 (Instant Discovery Emit):** Trả về Top 10 trạm kèm tên địa chỉ fallback và số cổng khả dụng ngay khi HERE API phản hồi (~300ms), tắt ngay `isLoading`, kích hoạt `ScrollToTop` khi làm mới.
        - **Stage 2 (Asynchronous Background Enrichment):** Tự động truy xuất tên chuẩn hóa EVCS trong nền qua `acEnrichmentJob`, cập nhật mượt mà danh sách trạm mà không kích hoạt lại spinner toàn màn hình, sau đó tính toán driving route debounced.
      * Quản lý vòng đời coroutine chặt chẽ: Hủy sạch `acDiscoveryJob` và `acEnrichmentJob` khi chuyển đổi bộ lọc (AC ⇄ DC ⇄ NONE), GPS scan mới hoặc ViewModel teardown, ngăn rò rỉ dữ liệu cũ.
      * Verification: `NearbyAcProgressiveRenderingAndOptimizationTest.kt` (100% PASS).

   4. **In-Car Automotive GPS Acquisition Resilience & Multi-Tier Fallback**:
      * Nâng cấp `LocationService` từ `PRIORITY_BALANCED_POWER_ACCURACY` lên `PRIORITY_HIGH_ACCURACY` để bắt buộc kích hoạt chip thu GPS vệ tinh (GNSS) của xe thay vì phụ thuộc Wi-Fi di động.
      * Thiết lập chuỗi fallback 3 lớp: `getCurrentLocation` (fresh) ➔ `client.lastLocation` (cache) khi fresh trả về null ➔ `LocationManager` phần cứng (`GPS_PROVIDER`, `NETWORK_PROVIDER`, `PASSIVE_PROVIDER`) cho thiết bị không có GMS (AOSP / Carlinkit TBox).
      * Thêm `isLocationEnabled()` qua `LocationManagerCompat` và cơ chế resume an toàn đơn lần `AtomicBoolean`.
      * Verification: `LocationServiceGpsResilienceTest.kt` (100% PASS).

   5. **Đồng bộ mã nguồn & Tài liệu**:
      * Đã cập nhật `CHANGELOG.md` cho bản phát hành `[2026-09-16]`.
      * Đã đồng bộ kiến thức vào `.brain/brain.json` và `.brain/session.json`.

⏳ CÒN LẠI / ĐỀ XUẤT TIẾP THEO:
   - Chạy thử nghiệm thực tế trên màn hình ô tô Android (Carlinkit TBox) để kiểm tra độ nhạy bắt tín hiệu GPS vệ tinh khi xe di chuyển.
   - Commit & push các thay đổi GPS resilience lên GitHub `main`.

🔧 QUYẾT ĐỊNH QUAN TRỌNG:
   - Áp dụng hiển thị lũy tiến 2 giai đoạn (Progressive 2-stage UI rendering) cho luồng trạm AC: hiển thị tức thì trạm khả dụng thay vì bắt người dùng chờ 2-3 giây cào HTML.
   - Quản lý tách rời `acDiscoveryJob` và `acEnrichmentJob` với cơ chế huỷ sạch khi đổi filter để loại bỏ hoàn toàn tình trạng trôi state trạm AC sang chế độ DC.
   - Dùng stream reader để bóc tách thẻ `<title>` HTML trực tiếp từ luồng mạng, tiết kiệm dung lượng bộ nhớ.

📁 FILES QUAN TRỌNG:
   - `app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt`
   - `app/src/main/java/com/evcs/favorites/focus/EvcsStationNameResolver.kt`
   - `app/src/main/java/com/evcs/favorites/data/network/here/HereEvApiClient.kt`
   - `plans/260916-1925-ac-charging-flow-performance-optimization/plan.md`
   - `CHANGELOG.md`
   - `.brain/brain.json`
   - `.brain/session.json`

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📍 Đã lưu! Để tiếp tục: Gõ /recap
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
