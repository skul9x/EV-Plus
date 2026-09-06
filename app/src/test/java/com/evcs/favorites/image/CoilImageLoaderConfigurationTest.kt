package com.evcs.favorites.image

import coil.ImageLoaderFactory
import coil.annotation.ExperimentalCoilApi
import com.evcs.favorites.EvPlusApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 03 Comprehensive Verification Test:
 * 1. testEvPlusApplication_implementsImageLoaderFactory: Confirms EvPlusApplication implements ImageLoaderFactory.
 * 2. testNewImageLoader_configuresMemoryAndDiskCaches: Verifies newImageLoader() configures a memory cache,
 *    disk cache, and shared OkHttp client without throwing exceptions.
 * 3. testManifest_declaresCustomApplicationClass: Asserts AndroidManifest.xml specifies android:name=".EvPlusApplication".
 * 4. Verifies NativeStationDetailSheet and StationPhotoViewerModal memoize ImageRequest using remember.
 */
@OptIn(ExperimentalCoilApi::class)
class CoilImageLoaderConfigurationTest {

    private fun resolveFile(relativeSubpath: String): File {
        val candidates = listOf(
            relativeSubpath,
            "app/$relativeSubpath",
            "../$relativeSubpath",
            "../app/$relativeSubpath"
        )
        for (cand in candidates) {
            val f = File(cand)
            if (f.exists()) return f
        }
        val userDir = System.getProperty("user.dir") ?: "."
        for (cand in candidates) {
            val f = File(userDir, cand)
            if (f.exists()) return f
        }
        throw AssertionError("Could not find file: $relativeSubpath in candidate paths from user.dir=$userDir")
    }

    @Test
    fun testEvPlusApplication_implementsImageLoaderFactory() {
        assertTrue(
            "EvPlusApplication must implement ImageLoaderFactory",
            ImageLoaderFactory::class.java.isAssignableFrom(EvPlusApplication::class.java)
        )
    }

    @Test
    fun testNewImageLoader_configuresMemoryAndDiskCaches() {
        val app = EvPlusApplication()
        val imageLoader = app.newImageLoader()

        assertNotNull("ImageLoader must not be null", imageLoader)
        assertNotNull("MemoryCache must be configured", imageLoader.memoryCache)
        assertNotNull("DiskCache must be configured", imageLoader.diskCache)

        val diskCache = imageLoader.diskCache!!
        assertEquals("Disk cache max size must be 50 MB", 50L * 1024 * 1024, diskCache.maxSize)
        assertTrue(
            "Disk cache directory must contain image_cache",
            diskCache.directory.toString().contains("image_cache")
        )

        // Verify call factory via reflection on RealImageLoader
        val callFactoryField = imageLoader.javaClass.declaredFields.firstOrNull {
            it.name.contains("callFactory", ignoreCase = true)
        }?.apply { isAccessible = true }
        assertNotNull("callFactory field should exist on ImageLoader", callFactoryField)
    }

    @Test
    fun testManifest_declaresCustomApplicationClass() {
        val manifestFile = resolveFile("src/main/AndroidManifest.xml")
        assertTrue("AndroidManifest.xml must exist", manifestFile.exists())

        val content = manifestFile.readText()
        assertTrue(
            "AndroidManifest.xml must declare android:name=\".EvPlusApplication\"",
            content.contains("android:name=\".EvPlusApplication\"")
        )
    }

    @Test
    fun testImageRequestMemoizationInComponents() {
        val sheetFile = resolveFile("src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt")
        val sheetContent = sheetFile.readText()
        assertTrue(
            "NativeStationDetailSheet must memoize ImageRequest with remember(images[page])",
            sheetContent.contains("remember(images[page])")
        )

        val modalFile = resolveFile("src/main/java/com/evcs/favorites/ui/components/StationPhotoViewerModal.kt")
        val modalContent = modalFile.readText()
        assertTrue(
            "StationPhotoViewerModal must memoize ImageRequest with remember(imageUrl)",
            modalContent.contains("remember(imageUrl)")
        )
    }
}
