# Phase 03: Secure BYOK Preferences & Settings UI
Status: ✅ Completed
Dependencies: [Phase 02: Multi-Tier Coordinator & Fallback Arbitration](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260903-multi-tier-routing-and-byok/phase-02-multi-tier-coordinator-and-fallback-arbitration.md)

## Objective
Implement secure encrypted storage for the user's Google Cloud API Key and routing configuration (`RoutingPreferencesManager`), an active API key connection validator (`validateGoogleApiKey`), and an intuitive Jetpack Compose `RoutingSettingsModal` BottomSheet with a top bar action in `FavoritesScreen`.

## Requirements
### Functional
- **Secure Persistence (`RoutingPreferencesManager.kt`)**:
  - Storage backed by `SessionStorage` abstraction (using `EncryptedSharedPrefsStorage` in Android production, and `InMemorySessionStorage` in JVM tests).
  - Persists and exposes `RoutingSettings`:
    - `googleApiKey: String` (defaults to `""`)
    - `preferredEngine: RoutingEngineMode` (defaults to `AUTO`)
    - `autoFallbackEnabled: Boolean` (defaults to `true`)
    - `customOsrmServerUrl: String? = null` (optional custom OSRM endpoint for self-hosters)
  - Exposes settings via Kotlin `StateFlow<RoutingSettings>` with immediate updates upon save.
  - Provides `validateGoogleApiKey(apiKey: String): Result<Boolean>`: sends a 1-element probe request to verify key validity and returns actionable error messages:
    - HTTP 400: `"Khóa API Google không hợp lệ. Vui lòng kiểm tra lại ký tự khóa."`
    - HTTP 403 (Billing): `"Dự án Google Cloud chưa kích hoạt thanh toán (Billing)."`
    - HTTP 403 (Routes API): `"Chưa kích hoạt 'Routes API' trên dự án Google Cloud của bạn."`
    - HTTP 403 (Restrictions): `"Khóa API bị giới hạn ứng dụng hoặc IP. Vui lòng kiểm tra cài đặt hạn chế trên Google Cloud."`
    - HTTP 429: `"Vượt quá hạn ngạch yêu cầu của Google Cloud API."`
- **Compose Settings UI (`RoutingSettingsModal.kt`)**:
  - ModalBottomSheet containing:
    1. **Google Maps API Key**:
       - OutlinedTextField with password visual transformation and toggleable show/hide eye icon.
       - "Kiểm tra kết nối" (Test Connection) button with loading indicator, displaying `✅ Hợp lệ` on success or actionable error message on failure.
       - Helper tip card:
         `💡 Hướng dẫn tạo API Key trên Google Cloud Console:`
         `• API Restrictions: Chọn "Restrict key" và chỉ bật "Routes API".`
         `• Application Restrictions: Chọn "None" hoặc "Android apps" (Package: com.evcs.favorites).`
    2. **Routing Engine Selector (Radio Group)**:
       - `Tự động (Khuyên dùng)`: Sử dụng Google Maps nếu có API Key, tự động chuyển sang OSRM nếu lỗi/hết quota.
       - `Chỉ Google Maps`: Bắt buộc dữ liệu giao thông trực tiếp từ Google; không chuyển dự phòng.
       - `Chỉ OSRM (Miễn phí)`: Dùng lộ trình đường bộ mở 100% miễn phí; không tốn quota Google.
       - `Chỉ Đường chim bay`: Khoảng cách đường thẳng offline 0ms; tiết kiệm pin và dữ liệu tối đa.
    3. **Auto-Fallback Toggle (Switch)**:
       - `Tự động chuyển dự phòng (Auto-Fallback)`: Bật/tắt tự động chuyển tầng tiếp theo khi xảy ra sự cố mạng.
    4. **Action Buttons**:
       - "Lưu cài đặt" (Save) and "Đóng" (Dismiss).
- **FavoritesScreen Integration (`FavoritesScreen.kt`)**:
  - Add Settings gear icon (`⚙️` / `Icons.Default.Settings`) to the top action bar in `FavoritesScreen.kt` to open the modal.

### Non-Functional
- API key must never be logged in cleartext or transmitted to any third-party server other than Google's official endpoints.
- Seamless Dark Mode theme adherence using official project colors and typography.

## Implementation Steps
1. Create `RoutingPreferencesManager.kt` handling secure storage and connection validation probe.
2. Build Composable `RoutingSettingsModal.kt` with key input, test validation button, engine mode radio buttons, and auto-fallback switch.
3. Update `FavoritesScreen.kt` TopAppBar to include the settings gear button and state wire-up to show/dismiss the modal.
4. Implement `RoutingPreferencesManagerTest.kt` verifying persistence, default values, state updates, and connection validation with MockWebServer.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/routing/RoutingPreferencesManager.kt` - [New] Encrypted storage and key validator
- `app/src/main/java/com/evcs/favorites/ui/components/RoutingSettingsModal.kt` - [New] Compose Material 3 ModalBottomSheet for BYOK settings
- `app/src/main/java/com/evcs/favorites/ui/screens/FavoritesScreen.kt` - [Modify] TopAppBar settings gear action and modal binding
- `app/src/test/java/com/evcs/favorites/RoutingPreferencesManagerTest.kt` - [New] Comprehensive file-based verification test for Phase 03

## Test Criteria
- Verify default settings initialize with `preferredEngine = AUTO`, `autoFallbackEnabled = true`, and `googleApiKey = ""`.
- Verify saving and retrieving updated `RoutingSettings` correctly persists all three fields.
- Verify `validateGoogleApiKey` successfully returns `Result.success(true)` when Google API returns HTTP 200 with valid route elements.
- Verify `validateGoogleApiKey` returns `Result.failure` with clear error description when Google API returns HTTP 400 (API key not valid) or HTTP 403.
- Verify state updates propagate correctly to observers.

---
Next Phase: [Phase 04: ViewModel Hybrid Pipeline & ETA Sorting](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260903-multi-tier-routing-and-byok/phase-04-viewmodel-hybrid-filtering-and-eta-sorting.md)
