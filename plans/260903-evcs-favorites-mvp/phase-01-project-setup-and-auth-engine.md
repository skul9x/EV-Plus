# Phase 01: Project Setup & Authentication Engine

Status: ✅ Completed  
Dependencies: None  

---

## 1. Objective
Bootstrap the Android project with Kotlin, Jetpack Compose, Material 3, and build the Authentication Engine to handle EVCS Email OTP authentication, session extraction, and persistent credential storage.

---

## 2. Requirements

### Functional
- Bootstrap Android Gradle build files (`build.gradle.kts`, `settings.gradle.kts`, `AndroidManifest.xml`).
- Implement `SessionManager`:
  - Manages persistent auth cookie `evcs` (1-year validity, `Max-Age=31536000`), session cookie `PHPSESSID`, CSRF token, and client UUID `evcs_did`.
  - Persists credentials securely using Jetpack DataStore / EncryptedSharedPreferences.
  - Generates Cookie header string for authenticated requests (`PHPSESSID=...; evcs=...; evcs_did=...`).
- Implement `AuthEngine`:
  - Step 1: `fetchCsrfToken()` -> `POST https://evcs.vn/reward.html` with headers (`X-Partial: reward`, browser Sec-Fetch headers) to extract CSRF token (`window.EVCS_REWARD.csrf`) and initial `PHPSESSID` cookie.
  - Step 2: `sendOtp(email)` -> `POST https://evcs.vn/reward.html` with body `{"action": "send_otp", "csrf": csrf, "email": email, "agree": true}` to request 6-digit OTP code.
  - Step 3: `verifyOtp(email, otp)` -> `POST https://evcs.vn/reward.html` with body `{"action": "verify_otp", "csrf": csrf, "email": email, "otp": otp}`.
  - Extracts `Set-Cookie: evcs=...` from verification response headers and saves to `SessionManager`.
  - Exposes `isLoggedIn: StateFlow<Boolean>` and `logout()` method (clears cookies).

### Non-Functional
- Zero leak of sensitive credentials.
- Fast startup time (< 300ms).
- Proper HTTP headers (`User-Agent: Mozilla/5.0 ... EVCS/A1.57 Mobile`, `Sec-Fetch-*`, `Referer`, `Origin`) to ensure zero Cloudflare blocking.

---

## 3. Implementation Steps
1. Create root Android project structure with Gradle wrapper, Compose dependencies, OkHttp 4.12+, and Kotlinx Serialization.
2. Implement `SessionManager` managing `evcs` (1-year auth), `PHPSESSID`, and device UUID.
3. Implement `AuthEngine` with 3-step OTP flow (`fetchCsrfToken`, `sendOtp`, `verifyOtp`) and session persistence.
4. Implement the single comprehensive test file: `src/test/java/com/evcs/favorites/AuthEngineTest.kt`.

---

## 4. Files to Create / Modify
- `build.gradle.kts`: Root and app-level Gradle build scripts with Compose & OkHttp dependencies.
- `app/src/main/AndroidManifest.xml`: Basic application declaration and internet permissions.
- `app/src/main/java/com/evcs/favorites/data/auth/SessionManager.kt`: Session cookies and device ID manager.
- `app/src/main/java/com/evcs/favorites/data/auth/AuthEngine.kt`: Authentication engine handling OTP flow and session state.
- `app/src/test/java/com/evcs/favorites/AuthEngineTest.kt`: Single verification test for this phase.

---

## 5. Single Verification Test
- **File:** `app/src/test/java/com/evcs/favorites/AuthEngineTest.kt`
- **Scope:** 
  - Validates `SessionManager` UUID generation and cookie header formatting (`PHPSESSID=...; evcs=...`).
  - Validates CSRF extraction from `reward.html` response.
  - Validates OTP request formatting and extraction of 1-year `evcs` cookie upon OTP verification.
  - Validates session persistence and `isLoggedIn` state transitions.

---
Next Phase: [phase-02-evcs-api-and-favorites-repository.md](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260903-evcs-favorites-mvp/phase-02-evcs-api-and-favorites-repository.md)
