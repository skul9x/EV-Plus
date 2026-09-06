# Phase 03: Coil ImageLoader & Cache Management (PERF-01)

Status: ✅ Completed  
Dependencies: Phase 02

## Objective
Establish an application-wide singleton `ImageLoader` via `ImageLoaderFactory` to bound memory and disk caches, link Coil to the app's shared OkHttpClient connection pool without double disk caching, and eliminate repetitive `ImageRequest` re-allocation in [NativeStationDetailSheet.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt) and [StationPhotoViewerModal.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/components/StationPhotoViewerModal.kt) to prevent OutOfMemory crashes on high-res VinFast CDN images (up to 12MP).

## Requirements

### Functional
- [x] Create custom [EvPlusApplication.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/EvPlusApplication.kt) extending `Application()` and implementing `ImageLoaderFactory`:
  ```kotlin
  package com.evcs.favorites

  import android.app.Application
  import coil.ImageLoader
  import coil.ImageLoaderFactory
  import coil.disk.DiskCache
  import coil.memory.MemoryCache
  import com.evcs.favorites.data.network.AppOkHttpClientProvider

  class EvPlusApplication : Application(), ImageLoaderFactory {

      override fun newImageLoader(): ImageLoader {
          return ImageLoader.Builder(this)
              .memoryCache {
                  MemoryCache.Builder(this)
                      .maxSizePercent(0.25) // 25% of available JVM heap
                      .build()
              }
              .diskCache {
                  DiskCache.Builder()
                      .directory(cacheDir.resolve("image_cache"))
                      .maxSizeBytes(50L * 1024 * 1024) // 50 MB
                      .build()
              }
              // Share OkHttp connection pool and thread dispatcher, but strip OkHttp's disk cache
              // to prevent double-caching (Coil 2.x manages disk storage via its own DiskCache)
              .callFactory {
                  AppOkHttpClientProvider.getSharedClient()
                      .newBuilder()
                      .cache(null)
                      .build()
              }
              .respectCacheHeaders(false) // Cache images regardless of missing/short CDN cache headers
              .crossfade(true)
              .build()
      }
  }
  ```
- [x] Register `EvPlusApplication` in [AndroidManifest.xml](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/AndroidManifest.xml) by adding `android:name=".EvPlusApplication"` to `<application>`.
- [x] In [NativeStationDetailSheet.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt#L1200-L1211):
  - Memoize `ImageRequest` with `remember(images[page])`:
    ```kotlin
    val context = LocalContext.current
    val imageRequest = remember(images[page]) {
        ImageRequest.Builder(context)
            .data(images[page])
            .crossfade(true)
            .build()
    }
    AsyncImage(
        model = imageRequest,
        contentDescription = "Station photo ${page + 1}",
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().clickable { onImageClick(page) }
    )
    ```
- [x] In [StationPhotoViewerModal.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/components/StationPhotoViewerModal.kt#L337-L352):
  - Memoize `ImageRequest` with `remember(imageUrl)`:
    ```kotlin
    val context = LocalContext.current
    val imageRequest = remember(imageUrl) {
        ImageRequest.Builder(context)
            .data(imageUrl)
            .crossfade(true)
            .build()
    }
    AsyncImage(
        model = imageRequest,
        contentDescription = "Station photo",
        contentScale = ContentScale.Fit,
        ...
    )
    ```

### Non-Functional
- [x] Zero OutOfMemory (OOM) crashes when browsing high-resolution photo carousels on memory-constrained devices.
- [x] Zero duplicate disk cache writes: Coil uses `image_cache` and OkHttp uses `http_cache`.
- [x] Instant offline image retrieval for previously loaded photos without network roundtrips.

## Implementation Steps
1. [x] Create [EvPlusApplication.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/EvPlusApplication.kt):
   - Implement `ImageLoaderFactory.newImageLoader()`.
   - Setup memory cache (25%), disk cache (50MB), `.respectCacheHeaders(false)`, and lazy `.callFactory` stripping OkHttp cache.
2. [x] Update [AndroidManifest.xml](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/AndroidManifest.xml):
   - Add `android:name=".EvPlusApplication"` to the `<application>` tag.
3. [x] Update [NativeStationDetailSheet.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt):
   - Wrap `ImageRequest.Builder` creation in `remember(images[page])`.
4. [x] Update [StationPhotoViewerModal.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/components/StationPhotoViewerModal.kt):
   - Wrap `ImageRequest.Builder` creation in `remember(imageUrl)`.
5. [x] Create the single comprehensive test file:
   - [CoilImageLoaderConfigurationTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/image/CoilImageLoaderConfigurationTest.kt)
6. [x] Run the verification command to confirm all tests pass.

## Files to Create/Modify
- `[NEW]` [EvPlusApplication.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/EvPlusApplication.kt) - Custom Application class implementing ImageLoaderFactory.
- `[MODIFY]` [AndroidManifest.xml](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/AndroidManifest.xml) - Register EvPlusApplication.
- `[MODIFY]` [NativeStationDetailSheet.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt) - Memoize ImageRequest with remember.
- `[MODIFY]` [StationPhotoViewerModal.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/components/StationPhotoViewerModal.kt) - Memoize ImageRequest with remember.
- `[NEW]` [CoilImageLoaderConfigurationTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/image/CoilImageLoaderConfigurationTest.kt) - Single comprehensive test for Phase 03.

## Test Criteria (Single Comprehensive Test File)
- **Target Test File**: [CoilImageLoaderConfigurationTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/image/CoilImageLoaderConfigurationTest.kt)
- **Verification Command**: `./gradlew testDebugUnitTest --tests "com.evcs.favorites.image.CoilImageLoaderConfigurationTest"`
- Test cases included within the single file:
  - `testEvPlusApplication_implementsImageLoaderFactory`: Confirms `EvPlusApplication` implements `ImageLoaderFactory`.
  - `testNewImageLoader_configuresMemoryAndDiskCaches`: Verifies `newImageLoader()` configures a memory cache, disk cache, and shared OkHttp client without throwing exceptions.
  - `testManifest_declaresCustomApplicationClass`: Asserts `AndroidManifest.xml` specifies `android:name=".EvPlusApplication"`.

---
Next Phase: [Phase 04: Regex Pre-compilation & CPU Optimization](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260905-1935-performance-remediation/phase-04-regex-precompilation-and-cpu-optimization.md)
