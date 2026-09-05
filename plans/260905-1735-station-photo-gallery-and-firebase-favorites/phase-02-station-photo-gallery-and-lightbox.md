# Phase 02: Station Photo Gallery, Direct CDN Decoding & Full-Screen Lightbox

Status: ✅ Completed
Dependencies: Phase 01

## Objective
Enable drivers to preview real-world charging station photos directly within the Native Station Detail Bottom Sheet. Extract direct CloudFront S3 CDN URLs (`cpo-prod-s3.vinfastauto.com`) via a dedicated multi-pass base64 decoder implemented with JVM-compatible `java.util.Base64` and URI regex query extraction to completely bypass Cloudflare 403 challenge blocks without unmocked Android dependencies. Display a responsive 16:9 carousel with page indicator pills that smoothly collapses to zero height when no images exist, and build an interactive full-screen Lightbox dialog modal supporting pinch-to-zoom (1x-4x), double-tap zoom (1x <-> 2.5x), boundary-clamped pan, horizontal paging, and swipe-to-dismiss isolated from bottom sheet gesture conflicts.

## Requirements

### Functional
- [x] Add `io.coil-kt:coil-compose:2.6.0` dependency to [app/build.gradle.kts](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/build.gradle.kts).
- [x] Implement `VinFastCdnUrlDecoder` using `java.util.Base64` (standard Java 8 & Android minSdk 26):
  - Input formats: Full URL `https://evcs.vn/media?file=...`, relative `media?file=...`, direct CDN URL, or raw base64 token.
  - JVM-safe parameter extraction: Extract the `file` token using standard string matching or `Regex("""[?&]file=([^&#]+)""")` instead of `android.net.Uri.parse` (which is unmocked in local JVM JUnit tests).
  - Multi-pass decoding pipeline:
    1. Extract raw token.
    2. Pass 1: Decode base64 to intermediate base64 string using `Base64.getDecoder().decode()`.
    3. Pass 2: Decode intermediate base64 string to percent-encoded URL string.
    4. Pass 3: URL unescape (`java.net.URLDecoder.decode(..., "UTF-8")`) to resolve direct CloudFront S3 CDN URL (`https://cpo-prod-s3.vinfastauto.com/charging-station-car/depot/images/...`).
    *(Crucial: Never run URLDecoder before Base64 decoding, as it converts `+` to spaces, corrupting Base64 tokens).*
  - Graceful fallback: If input is already a direct CloudFront S3 URL, return it directly. If corrupted, empty, or non-base64, return `null` safely without throwing exceptions.
- [x] Update [StationModels.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/data/model/StationModels.kt) and [EvcsRepository.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt):
  - Add `images: List<String> = emptyList()` and `addedAt: Long = 0L` to `Station`.
  - In `EvcsRepository.kt` (`toDomainStation` and `mergeToDomainStation`), map `SearchStationRaw.media` through `VinFastCdnUrlDecoder.decodeList()`.
  - Ensure legacy `image: String?` property continues to resolve to `images.firstOrNull() ?: image` for complete backward compatibility with `StationCard` and other existing UI components.
- [x] Build Photo Carousel in [NativeStationDetailSheet.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt):
  - 16:9 aspect ratio (`Modifier.fillMaxWidth().aspectRatio(16f / 9f)`), Material 3 rounded corners (`16.dp`).
  - `HorizontalPager` with indicator pill badge (`"${pagerState.currentPage + 1} / ${images.size}"`) and dot indicators.
  - Coil `AsyncImage` with `crossfade(true)`, disk cache, and memory cache pooling.
  - Tapping an image triggers full-screen Lightbox starting at the clicked index.
  - **Auto-collapse rule:** If `station.images.isEmpty()`, render nothing (zero height, no margin/padding, no empty placeholder) so that title and live port chips render immediately below the handle.
- [x] Create [StationPhotoViewerModal.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/components/StationPhotoViewerModal.kt):
  - Use `androidx.compose.ui.window.Dialog` with `DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)` so the viewer opens in a dedicated window on top of `ModalBottomSheet`, eliminating touch gesture interception with bottom sheet drag.
  - `BackHandler(onBack = onDismiss)` for seamless system back navigation.
  - 95% translucent dark backdrop (`Color(0xF20B0F17)`).
  - Gesture arbitration & state machine:
    - **Pinch-to-zoom:** Smooth scale modification between `1.0f` and `4.0f`.
    - **Double-tap to zoom:** Toggles between `1.0f` and `2.5f` with animated transition.
    - **Pan / Drag:** Active when `scale > 1.05f`. `HorizontalPager(userScrollEnabled = false)`. Clamp pan offset to boundaries `((scale - 1f) * size / 2)`.
    - **Horizontal Paging:** Active strictly when `scale <= 1.05f`. `HorizontalPager(userScrollEnabled = true)`.
    - **Swipe-to-Dismiss:** When `scale <= 1.05f`, vertical downward drag translates the viewer with progressive alpha fade; releasing past 100.dp or with downward velocity triggers `onDismiss()`.
  - Top app bar: photo index pill counter (`"2 / 3"`) and Close `"✕"` icon button.

### Non-Functional
- [x] Fast, zero-overhead image rendering with Coil memory cache and disk pooling.
- [x] Absolute isolation: Gestures within the Lightbox dialog must never trigger `ModalBottomSheet` dismiss or page drag.
- [x] Strict null-safety: zero crashes on malformed media strings or connection drops.
- [x] 100% JVM unit testability without unmocked Android framework exceptions.

## Implementation Steps
1. [x] Add `implementation("io.coil-kt:coil-compose:2.6.0")` to [app/build.gradle.kts](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/build.gradle.kts).
2. [x] Create [VinFastCdnUrlDecoder.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/util/VinFastCdnUrlDecoder.kt):
   - Implement `decode(raw: String?): String?` using `java.util.Base64` and URI regex extraction.
   - Implement `decodeList(rawList: List<String>?): List<String>`.
3. [x] Extend `Station` data class in [StationModels.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/data/model/StationModels.kt) with `images: List<String> = emptyList()` and `addedAt: Long = 0L`.
4. [x] Update mapping logic in [EvcsRepository.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt) (`toDomainStation` and `mergeToDomainStation`) to decode `media` items into `images`.
5. [x] Build [StationPhotoViewerModal.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/components/StationPhotoViewerModal.kt) inside a full-screen `Dialog` encapsulating zoomable/pannable gesture logic.
6. [x] Integrate photo carousel and modal state in [NativeStationDetailSheet.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt) with auto-collapse when empty.
7. [x] Create exactly one comprehensive file-based test: [StationPhotoGalleryAndDecoderTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/ui/components/StationPhotoGalleryAndDecoderTest.kt).
8. [x] Run only this single test to verify Phase 02 completion: `./gradlew testDebugUnitTest --tests com.evcs.favorites.ui.components.StationPhotoGalleryAndDecoderTest`.

## Files to Create/Modify
- `app/build.gradle.kts` - Add Coil Compose dependency.
- `app/src/main/java/com/evcs/favorites/util/VinFastCdnUrlDecoder.kt` - [NEW] Multi-pass base64 VinFast CloudFront S3 CDN URL decoder using `java.util.Base64`.
- `app/src/main/java/com/evcs/favorites/data/model/StationModels.kt` - Add `images` and `addedAt` fields to `Station`.
- `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt` - Map search media items through decoder.
- `app/src/main/java/com/evcs/favorites/ui/components/StationPhotoViewerModal.kt` - [NEW] Lightbox full-screen gesture modal wrapped in `Dialog`.
- `app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt` - Add 16:9 carousel and Lightbox trigger with zero-height collapse when empty.
- `app/src/test/java/com/evcs/favorites/ui/components/StationPhotoGalleryAndDecoderTest.kt` - [NEW] Single comprehensive test verifying double-base64 URL extraction, fallback handling, image list parsing, carousel item count/indicators, and Lightbox gesture transformation states on JVM.

## Test Criteria
- [x] Single comprehensive test `StationPhotoGalleryAndDecoderTest.kt` PASSES:
  - Verifies multi-pass base64 decode correctly extracts `cpo-prod-s3.vinfastauto.com` direct URL from actual VinFast sample tokens.
  - Verifies corrupted, non-base64, empty, or direct HTTP inputs are handled safely without exceptions or crashes.
  - Verifies parameter extraction runs on standard JVM without unmocked Android `Uri` failures.
  - Verifies `Station` model properly stores decoded image URLs and `addedAt`, maintaining backward compatibility with `image`.
  - Verifies carousel visibility logic (collapsed to zero height when empty, visible when non-empty with correct indicator text).
  - Verifies Lightbox zoom/pan state calculations (clamp scale between 1.0f and 4.0f, double-tap toggling, boundary clamping, and dismiss threshold).

---
Next Phase: [Phase 03: Firebase Auth & Google Sign-In Integration](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260905-1735-station-photo-gallery-and-firebase-favorites/phase-03-firebase-auth-and-google-signin.md)

