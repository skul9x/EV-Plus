# Phase 03: Firebase Auth & Google Sign-In Integration

Status: 🟩 Completed
Dependencies: Phase 02

## Objective
Integrate Firebase Authentication and modern AndroidX Credential Manager with Google ID into EV-Plus. Establish a seamless Local-First identity layer supporting frictionless anonymous guest access by default, and 1-tap Google Sign-In with automatic credential exchange and collision handling. Provide a reactive `StateFlow<AuthState>` to drive cloud synchronization and user profile presentation, with clean interfaces and a `FakeAuthService` for deterministic JVM unit testing.

## Requirements

### Functional
- [x] Configure Google Services Gradle plugin:
  - Root [build.gradle.kts](file:///home/skul9x/Desktop/Code/EV-Plus-main/build.gradle.kts): register `id("com.google.gms.google-services") version "4.4.2" apply false`.
  - [app/build.gradle.kts](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/build.gradle.kts): apply `id("com.google.gms.google-services")`.
- [x] Add modern Firebase and Credential Manager dependencies to [app/build.gradle.kts](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/build.gradle.kts):
  - `implementation(platform("com.google.firebase:firebase-bom:33.10.0"))`
  - `implementation("com.google.firebase:firebase-auth")` (Note: modern non-KTX artifact, as `-ktx` is deprecated in BoM 32.5+ and removed in BoM 34+).
  - `implementation("androidx.credentials:credentials:1.3.0")`
  - `implementation("androidx.credentials:credentials-play-services-auth:1.3.0")`
  - `implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")`
- [x] Implement domain models in [AuthModels.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/domain/model/AuthModels.kt):
  - `AuthUser`: `uid: String`, `email: String?`, `displayName: String?`, `photoUrl: String?`, `isAnonymous: Boolean`.
  - `AuthState`: `Idle`, `Loading`, `Authenticated(user: AuthUser)`, `Unauthenticated`, `Error(message: String)`.
- [x] Implement `AuthService` interface and `FirebaseAuthManager`:
  - `signInAnonymously()`: Instant local guest identity without prompting the user.
  - `buildGoogleIdOption(serverClientId: String)`: Configure `GetGoogleIdOption.Builder()` with Web Client ID from [app/google-services.json](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/google-services.json) (`49442747133-giv98l3ik6b124kdr6o9t257sntgp8c1.apps.googleusercontent.com`), `setFilterByAuthorizedAccounts(false)`, and `setAutoSelectEnabled(false)`.
  - `signInWithGoogle(activityContext: Context)`:
    - Invoke `credentialManager.getCredential(activityContext, request)` strictly with an `Activity` context to allow Android's account chooser UI to render.
    - Validate `credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL`.
    - Extract `GoogleIdTokenCredential.createFrom(credential.data).idToken`.
    - Exchange with `GoogleAuthProvider.getCredential(idToken, null)`.
  - `signInWithGoogleCredential(authCredential: AuthCredential)`:
    - If current user is anonymous: attempt `linkWithCredential(credential)` to preserve existing UID and local favorites.
    - If `FirebaseAuthUserCollisionException` occurs (Google account already exists in Firebase): gracefully fall back to `signInWithCredential(credential)`.
  - `signOut(activityContext: Context)`: Clear Credential Manager state via `credentialManager.clearCredentialState(ClearCredentialStateRequest())`, invoke `auth.signOut()`, and reset to `AuthState.Unauthenticated`.
  - Expose `val authState: StateFlow<AuthState>` and `val currentUser: AuthUser?`.
- [x] Provide testable abstractions (`AuthService` and `FakeAuthService`) ensuring tests execute deterministically on standard JVM without mocking Play Services stubs.

### Non-Functional
- [x] Seamless transition: Local data is preserved when upgrading from Anonymous to Google Sign-In.
- [x] Zero blocking calls on the UI thread during token verification or credential handshakes.
- [x] Resilient offline handling: App functions 100% normally when offline using cached user state.
- [x] Strict context safety: Credential Manager invocation receives `Activity` context, avoiding `IllegalArgumentException`.

## Implementation Steps
1. [x] Update root [build.gradle.kts](file:///home/skul9x/Desktop/Code/EV-Plus-main/build.gradle.kts): add `id("com.google.gms.google-services") version "4.4.2" apply false`.
2. [x] Update [app/build.gradle.kts](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/build.gradle.kts):
   - Apply `id("com.google.gms.google-services")`.
   - Add `implementation(platform("com.google.firebase:firebase-bom:33.10.0"))`.
   - Add `implementation("com.google.firebase:firebase-auth")`.
   - Add `implementation("androidx.credentials:credentials:1.3.0")`.
   - Add `implementation("androidx.credentials:credentials-play-services-auth:1.3.0")`.
   - Add `implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")`.
3. [x] Create [AuthModels.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/domain/model/AuthModels.kt) with `AuthUser` and `AuthState`.
4. [x] Create [AuthService.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/data/auth/AuthService.kt) interface and [FirebaseAuthManager.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/data/auth/FirebaseAuthManager.kt) implementing anonymous auth, `GetGoogleIdOption` builder, credential exchange, account linking, collision fallback, and state flow.
5. [x] Provide [FakeAuthService.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/data/auth/FakeAuthService.kt) for JVM testing and preview environments.
6. [x] Create exactly one comprehensive file-based test: [FirebaseAuthAndCredentialManagerTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/data/auth/FirebaseAuthAndCredentialManagerTest.kt).
7. [x] Run only this single test to verify Phase 03 completion: `./gradlew testDebugUnitTest --tests com.evcs.favorites.data.auth.FirebaseAuthAndCredentialManagerTest`.

## Files to Create/Modify
- `build.gradle.kts` - Register Google Services plugin.
- `app/build.gradle.kts` - Apply plugin and add Firebase Auth + Credential Manager dependencies.
- `app/src/main/java/com/evcs/favorites/domain/model/AuthModels.kt` - [NEW] AuthUser and AuthState models.
- `app/src/main/java/com/evcs/favorites/data/auth/AuthService.kt` - [NEW] Domain auth service interface.
- `app/src/main/java/com/evcs/favorites/data/auth/FirebaseAuthManager.kt` - [NEW] Firebase Auth and Credential Manager implementation.
- `app/src/main/java/com/evcs/favorites/data/auth/FakeAuthService.kt` - [NEW] Lightweight test implementation of AuthService for JVM tests and previews.
- `app/src/test/java/com/evcs/favorites/data/auth/FirebaseAuthAndCredentialManagerTest.kt` - [NEW] Single comprehensive test verifying anonymous login, `GetGoogleIdOption` construction, Google credential extraction, account upgrade/linking, user collision fallback, and sign-out lifecycle.

## Test Criteria
- [x] Single comprehensive test `FirebaseAuthAndCredentialManagerTest.kt` PASSES:
  - Verifies anonymous authentication initialization assigns unique local guest ID.
  - Verifies `buildGoogleIdOption` correctly sets Web Client ID `49442747133-giv98l3ik6b124kdr6o9t257sntgp8c1.apps.googleusercontent.com` and auto-select settings.
  - Verifies Google ID credential response parsing extracts valid idToken and email.
  - Verifies credential exchange upgrades anonymous user to Google authenticated user without ID loss.
  - Verifies collision fallback properly switches to the existing Google account when linking collides.
  - Verifies `AuthUser` model maps display name, email, and photo URL properly.
  - Verifies sign-out resets state flow to Unauthenticated cleanly.

---
Next Phase: [Phase 04: Local-First Firestore Favorites Sync & Profile UI Integration](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260905-1735-station-photo-gallery-and-firebase-favorites/phase-04-firestore-favorites-sync-and-localfirst.md)

