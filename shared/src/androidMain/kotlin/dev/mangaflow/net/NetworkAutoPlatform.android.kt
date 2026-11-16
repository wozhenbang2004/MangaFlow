package dev.mangaflow.net

import android.os.Build
import android.os.Process

internal actual fun networkAutoRuntimeAbi(): String? = Build.SUPPORTED_ABIS.firstOrNull {
    it in if (Process.is64Bit()) setOf("arm64-v8a", "x86_64") else setOf("armeabi-v7a", "x86")
}
