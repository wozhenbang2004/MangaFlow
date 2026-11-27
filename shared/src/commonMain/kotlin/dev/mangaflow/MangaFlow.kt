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
import dev.mangaflow.experience.initializeExperienceImages
import dev.mangaflow.background.ApplicationOperations
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.net.RelayLinkController
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.ui.AppLaunchReveal
import dev.mangaflow.ui.MangaAdaptiveLayout
import dev.mangaflow.ui.MangaFlowApp
import dev.mangaflow.ui.MangaFlowTheme
import dev.mangaflow.ui.ScreenFitProvider
import dev.mangaflow.ui.glass.HazeProviders
import dev.mangaflow.ui.glass.MangaHazeRuntime
import dev.mangaflow.ui.motion.MangaMotionProvider
import dev.mangaflow.update.UpdateManager

/** Every native entry initializes the same actual application services, then mounts the same UI. */
fun initializeMangaFlow(context: PlatformContext) {
    val relay = RelayLinkController.get(context)
    DownloadRoutingStore.get(context)
    relay.start(ApplicationOperations.get(context).scope)
    MangaHazeRuntime.initialize(context)
    initializeExperienceImages(context)
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
            model.services.setAnimeForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) model.services.resumePending()
                if (event == Lifecycle.Event.ON_START) model.services.setAnimeForeground(true)
                if (event == Lifecycle.Event.ON_STOP) {
                    model.flushPendingStorage()
                    model.services.setAnimeForeground(false)
                }
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        MangaMotionProvider {
            MangaFlowTheme {
                HazeProviders {
                    MangaAdaptiveLayout {
                        ScreenFitProvider {
                            AppLaunchReveal(play = animateLaunch) {
                                MangaFlowApp(openUpdatesRequest, openDownloadsRequest, onRequestNotifications, model)
                            }
                        }
                    }
                }
            }
        }
    }
}
