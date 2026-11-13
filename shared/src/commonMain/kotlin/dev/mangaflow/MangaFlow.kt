package dev.mangaflow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.storage.StorageFile
import dev.mangaflow.platform.storage.cacheDirectory
import dev.mangaflow.ui.AppLaunchReveal
import dev.mangaflow.ui.MangaAdaptiveLayout
import dev.mangaflow.ui.MangaFlowApp
import dev.mangaflow.ui.MangaFlowTheme
import dev.mangaflow.ui.glass.HazeProviders
import dev.mangaflow.ui.glass.MangaHazeRuntime
import dev.mangaflow.ui.motion.MangaMotionProvider
import dev.mangaflow.update.UpdateManager

/** Every native entry initializes the same actual application services, then mounts the same UI. */
fun initializeMangaFlow(context: PlatformContext) {
    MangaHazeRuntime.initialize(context)
    SingletonImageLoader.setSafe { imageContext ->
        ImageLoader.Builder(imageContext)
            .components { add(KtorNetworkFetcherFactory()) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(imageContext, .18).build() }
            .diskCache {
                DiskCache.Builder().directory(StorageFile(cacheDirectory(context), "image-cache").nativePath)
                    .maxSizeBytes(256L * 1024 * 1024).build()
            }
            .crossfade(160)
            .build()
    }
    UpdateManager.get(context).start()
}

@Composable
fun MangaFlowHost(
    context: PlatformContext,
    openUpdatesRequest: Int = 0,
    openDownloadsRequest: Int = 0,
    onRequestNotifications: () -> Unit = {},
    animateLaunch: Boolean = true,
    onModelReady: (MangaViewModel) -> Unit = {},
) {
    CompositionLocalProvider(LocalMangaContext provides context) {
        val providedOwner = LocalViewModelStoreOwner.current
        val owner = providedOwner ?: remember {
            object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        }
        DisposableEffect(owner) {
            onDispose { if (providedOwner == null) owner.viewModelStore.clear() }
        }
        val model: MangaViewModel = viewModel(viewModelStoreOwner = owner) { MangaViewModel(context) }
        SideEffect { onModelReady(model) }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle, model) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) model.downloads.resumePendingDownloads()
                if (event == Lifecycle.Event.ON_STOP) model.flushPendingStorage()
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        MangaFlowTheme {
            MangaMotionProvider {
                HazeProviders {
                    MangaAdaptiveLayout {
                        AppLaunchReveal(play = animateLaunch) {
                            MangaFlowApp(openUpdatesRequest, openDownloadsRequest, onRequestNotifications, model)
                        }
                    }
                }
            }
        }
    }
}
