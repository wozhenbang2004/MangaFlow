@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.mangaflow

import androidx.compose.ui.window.ComposeUIViewController
import dev.mangaflow.platform.PlatformContext
import platform.UIKit.UIViewController
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound

/** SwiftUI's sole content controller hosts the complete shared MangaFlow application. */
fun MainViewController(): UIViewController {
    val context = object : PlatformContext() {}
    initializeMangaFlow(context)
    return ComposeUIViewController {
        MangaFlowHost(context, onRequestNotifications = {
            UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(
                UNAuthorizationOptionAlert or UNAuthorizationOptionBadge or UNAuthorizationOptionSound,
            ) { _, _ -> }
        })
    }
}
