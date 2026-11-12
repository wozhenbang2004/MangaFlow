package dev.mangaflow

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dev.mangaflow.update.UpdateManager
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class MangaFlowApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .components {
                    add(OkHttpNetworkFetcherFactory(callFactory = {
                        OkHttpClient.Builder()
                            .connectTimeout(20, TimeUnit.SECONDS)
                            .readTimeout(40, TimeUnit.SECONDS)
                            .build()
                    }))
                }
                .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.18).build() }
                .diskCache {
                    DiskCache.Builder()
                        .directory(File(context.cacheDir, "image-cache"))
                        .maxSizeBytes(256L * 1024 * 1024)
                        .build()
                }
                .crossfade(160)
                .build()
        }
        UpdateManager.get(this).start()
    }
}
