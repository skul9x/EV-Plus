# Phase 01: Guide Data Models, Content & Troubleshooting Provider
Status: ✅ Completed
Dependencies: None

## Objective
Establish the core data architecture and rich content provider for the Google Maps API Key guide (`ApiKeyGuideProvider`), modeling the complete 5-step walkthrough, accurate 2025/2026 Google Maps Platform free tier usage thresholds (10,000 requests/month free on Routes API Essentials), direct Google Cloud Console URLs, clipboard presets, and a structured troubleshooting matrix for HTTP 400, 403, and 429 errors.

## Requirements
### Functional
- **Data Models (`ApiKeyGuideModels.kt`)**:
  - `ApiKeyGuideStep`: Step number, title, subtitle, detailed instructions (Markdown/annotated text), tips/callouts, action link URL, action button label, copyable value (e.g., package name `com.evcs.favorites` or console links).
  - `ApiKeyTroubleshootingItem`: Error code/type (`INVALID_KEY`, `BILLING_DISABLED`, `API_NOT_ENABLED`, `RESTRICTION_ERROR`, `QUOTA_EXCEEDED`, `NETWORK_ERROR`), summary title, cause explanation, actionable solution, remediation console link.
  - `FreeTierInfo`: Summary of monthly quota (10,000 free requests for Routes API Essentials under Google's 2025/2026 per-SKU pricing), explanation of $0 cost guarantee via budget limits ($0 alert), and safety recommendations.
- **Guide Content Provider (`ApiKeyGuideProvider.kt`)**:
  - `fun getGuideSteps(): List<ApiKeyGuideStep>` returning 5 structured steps:
    1. **Step 1: Project & Billing Setup**: Go to Google Cloud Console, create project "TramsacEV", link a billing account (required by Google to activate APIs; 10,000 free requests apply first), and set a $0 budget alert. Link: `https://console.cloud.google.com/billing`.
    2. **Step 2: Enable Routes API**: Navigate to APIs & Services > Library, search for "Routes API" (or open direct link), and click "Enable". Link: `https://console.cloud.google.com/apis/library/routes.googleapis.com`.
    3. **Step 3: Create API Key**: Go to APIs & Services > Credentials, click "Create Credentials" > "API key", copy the newly generated key string (`AIza...`). Link: `https://console.cloud.google.com/apis/credentials`.
    4. **Step 4: Configure Key Restrictions (Critical for BYOK)**:
       - **API Restrictions**: Set to "Restrict key" and select ONLY "Routes API". This guarantees the key cannot be misused for any other Google Cloud service.
       - **Application Restrictions**: Set to **"None" (Không giới hạn)** for personal BYOK. Explain clearly: REST API requests from client devices do not transmit custom SHA-1 signatures; selecting "Android apps" in Google Cloud will require a SHA-1 certificate fingerprint and cause HTTP 403 errors. Safety is fully ensured by restricting to "Routes API" + setting a $0 budget alert.
    5. **Step 5: Test & Save in TramsacEV**: Paste key into the app settings, tap "Kiểm tra kết nối" (Test Connection) to verify live with Google servers, then tap "Lưu cài đặt".
  - `fun getTroubleshootingItems(): List<ApiKeyTroubleshootingItem>`:
    - Provides specific recovery guidance mapped to HTTP 400 (Invalid key format), 403 (Billing disabled), 403 (Routes API not enabled), 403 (Application/IP restrictions), 429 (Quota exceeded), and Network/timeout errors.
  - `fun getTroubleshootingForError(errorMessage: String): ApiKeyTroubleshootingItem?`:
    - Matches exact error strings returned by `RoutingPreferencesManager.validateGoogleApiKey`:
      - `"Khóa API Google không hợp lệ"` -> `INVALID_KEY`
      - `"Dự án Google Cloud chưa kích hoạt thanh toán"` -> `BILLING_DISABLED`
      - `"Chưa kích hoạt 'Routes API'"` -> `API_NOT_ENABLED`
      - `"Khóa API bị giới hạn ứng dụng hoặc IP"` -> `RESTRICTION_ERROR`
      - `"Vượt quá hạn ngạch yêu cầu"` -> `QUOTA_EXCEEDED`
      - Network / generic errors -> `NETWORK_ERROR`
  - Exposes official constant URLs and Android package identifier (`com.evcs.favorites`).

### Non-Functional
- Pure Kotlin implementation independent of Android UI classes so it is 100% unit-testable on JVM.
- Clean separation of presentation text and business logic.

## Implementation Steps
1. Create `ApiKeyGuideModels.kt` defining `ApiKeyGuideStep`, `ApiKeyTroubleshootingItem`, and `FreeTierInfo`.
2. Implement `ApiKeyGuideProvider.kt` with the complete 5 steps, console URLs, free-tier details, and substring error mapping matching `RoutingPreferencesManager`.
3. Write `ApiKeyGuideProviderTest.kt` verifying step count, URL correctness, package identifier, free tier details, and exact error matching logic.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/routing/ApiKeyGuideModels.kt` - [New] Guide step, troubleshooting item, and free-tier data classes
- `app/src/main/java/com/evcs/favorites/data/routing/ApiKeyGuideProvider.kt` - [New] Content repository, steps generator, and error matcher
- `app/src/test/java/com/evcs/favorites/ApiKeyGuideProviderTest.kt` - [New] Single comprehensive file-based test for Phase 01

## Test Criteria (Single Comprehensive Test: `ApiKeyGuideProviderTest.kt`)
- Verify `getGuideSteps()` returns exactly 5 steps in sequential order with non-empty titles, instructions, and valid HTTPS URLs.
- Verify Step 4 clearly explains selecting "None" for application restriction and "Routes API" for API restriction, and includes package name `com.evcs.favorites`.
- Verify `getTroubleshootingItems()` covers all primary failure categories (HTTP 400, 403 billing, 403 API not enabled, 403 restrictions, 429 quota, network error).
- Verify `getTroubleshootingForError()` correctly resolves actionable remediation items and URLs for all validation error messages produced by `RoutingPreferencesManager`.
- Verify `getFreeTierInfo()` correctly specifies the 10,000 free monthly request threshold.

---
Next Phase: [Phase 02: Interactive Guide BottomSheet / Dialog UI Component](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260903-google-maps-api-key-guide-and-settings-ux/phase-02-interactive-guide-ui-component.md)
