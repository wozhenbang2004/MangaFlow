package dev.mangaflow

import android.app.Application
import android.content.Context

class MangaFlowApplication : Application() {
    private var initialized = false

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if (!isDiagnosticsProcess()) LaunchFailure.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        if (!isDiagnosticsProcess() && LaunchFailure.recoveryReport == null) initializeServices()
    }

    internal fun initializeServices() {
        if (initialized) return
        initializeMangaFlow(this)
        initialized = true
    }
}
