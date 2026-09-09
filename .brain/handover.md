━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📋 HANDOVER DOCUMENT
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

📍 Đang làm: Tối ưu hóa toàn diện giao diện Landscape cho Carlinkit TBox Android Box
🔢 Đến bước: Hoàn thành 100% cả 3 Phase & Đã push lên GitHub main (`commit 2e45142`)

✅ ĐÃ XONG:
   1. **Phase 01: StationNameSanitizer & Marquee Titles**:
      * `StationNameSanitizer.kt`: Loại bỏ tiền tố thừa như *"Trạm sạc VinFast"*, *"Trạm sạc Ô tô điện VinFast"*, *"EV Charger..."* giúp hiển thị ngay tên địa danh thực tế.
      * `StationCard.kt`: Thêm Marquee horizontal scroll cho tiêu đề trạm sạc dài khi ở Car Mode.
      * Unit tests: `StationNameSanitizerTest.kt` (pass 100%).

   2. **Phase 02: Fullscreen Immersive Mode & Compact Navigation Rail (58dp)**:
      * `MainActivity.kt`: Kích hoạt `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` ẩn hoàn toàn system bars (Status bar & Navigation bar) trên màn hình Android Box. Tự động phục hồi khi `onWindowFocusChanged(true)`.
      * `AppNavigationRail.kt`: Thu gọn bề rộng rail từ 72dp -> 58dp, touch target 50dp, icon 26dp. Thứ tự automotive glanceability: Quanh đây -> Yêu thích -> Cài đặt -> Làm mới (animated rotation).
      * Unit tests: `LandscapeNavigationRailTest.kt` (pass 100%).

   3. **Phase 03: Dedicated Landscape UI Screens for Nearby & Favorites**:
      * `NearbyLandscapeScreen.kt`: Bỏ hoàn toàn `TopAppBar` và filter summary pill, thu hồi ~95dp chiều dọc hiển thị 3-4 StationCard cùng lúc bên cạnh detail pane.
      * `FavoritesLandscapeScreen.kt`: Bỏ `TopAppBar`, giao diện 2 cột với cloud sync status và full-height detail pane.
      * `LandscapeScreenContracts.kt`: Định nghĩa các hằng số hợp đồng kiến trúc (0 nested `if (!isLandscape)`).
      * Unit tests: `LandscapeDedicatedScreensContractTest.kt` (pass 100%).

   4. **Kiểm tra .gitignore & Git Push**:
      * Fix lỗi pattern `lan*/` chặn nhầm package `ui/screens/landscape/` -> đổi thành `/lan*/`.
      * Commit và push an toàn lên `origin/main` (fast-forward, không force push).

⏳ CÒN LẠI / HƯỚNG PHÁT TRIỂN TIẾP THEO:
   - Theo dõi thực tế trên thiết bị Carlinkit TBox / Android Box khi chạy xe thực tế.
   - Bổ sung tùy chỉnh kích thước font chữ hoặc mật độ hiển thị theo kích thước màn hình xe (7 inch, 9 inch, 12.3 inch) nếu người dùng có nhu cầu thêm.

🔧 QUYẾT ĐỊNH QUAN TRỌNG:
   - Tách riêng biệt composable màn hình ngang (`NearbyLandscapeScreen`, `FavoritesLandscapeScreen`) thay vì nhồi nhét `if (isLandscape)` trong màn hình dọc.
   - Loại bỏ TopAppBar trong Landscape vì các hành động (Settings, Refresh, Tabs) đã được Navigation Rail đảm nhiệm.
   - Giữ nguyên toàn bộ logic nghiệp vụ (UDF, Local-First, Mutex Coroutine, Auto-Selection).

📁 FILES QUAN TRỌNG:
   - `app/src/main/java/com/evcs/favorites/ui/screens/landscape/NearbyLandscapeScreen.kt`
   - `app/src/main/java/com/evcs/favorites/ui/screens/landscape/FavoritesLandscapeScreen.kt`
   - `app/src/main/java/com/evcs/favorites/ui/screens/landscape/LandscapeScreenContracts.kt`
   - `app/src/main/java/com/evcs/favorites/navigation/AppNavigationRail.kt`
   - `app/src/main/java/com/evcs/favorites/util/StationNameSanitizer.kt`
   - `app/src/main/java/com/evcs/favorites/MainActivity.kt`
   - `.brain/brain.json`
   - `.brain/session.json`
   - `.brain/handover.md`

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📍 Đã lưu! Để tiếp tục: Gõ /recap
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
