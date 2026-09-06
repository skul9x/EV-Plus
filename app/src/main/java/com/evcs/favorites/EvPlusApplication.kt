package com.evcs.favorites

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.evcs.favorites.data.network.AppOkHttpClientProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class EvPlusApplication : Application(), ImageLoaderFactory {
 
    override fun onCreate() {
        super.onCreate()
        CoroutineScope(Dispatchers.IO).launch {
            AppOkHttpClientProvider.installDiskCache(cacheDir.resolve("http_cache"))
        }
    }

    override fun getCacheDir(): File {
        return runCatching { super.getCacheDir() }.getOrNull()
            ?: File(System.getProperty("java.io.tmpdir", "/tmp"), "evplus_cache").apply { mkdirs() }
    }

    companion object {
        const val COIL_MEMORY_CACHE_PERCENT = 0.15
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(COIL_MEMORY_CACHE_PERCENT) // 15% of available JVM heap
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
