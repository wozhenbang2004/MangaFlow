package dev.mangaflow.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Explicit system callback endpoint. Never launches UI from the background. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updates = UpdateManager.get(context)
        try {
            updates.receiveInstallationStatus(intent)
        } catch (failure: Exception) {
            updates.installationLaunchFailed(intent.getIntExtra("session_id", -1),
                intent.getStringExtra("install_token").orEmpty(), failure.message ?: "未能接收系统安装结果")
        }
    }
}
