package dev.mangaflow

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import java.text.DateFormat
import java.util.Date

/** A launcher shortcut opens only the system Sharesheet, without Compose, Haze or app services. */
class CrashReportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val report = buildReport()
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "漫流错误日志")
                putExtra(Intent.EXTRA_TEXT, report)
            }
            startActivity(Intent.createChooser(send, "复制或分享错误日志"))
        }
        finish()
    }

    private fun buildReport(): String = buildString {
        appendLine("MangaFlow ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}, ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine()
        appendLine("应用异常记录")
        appendLine(LaunchFailure.readSavedReport(this@CrashReportActivity)
            ?: "尚无已保存的 Java 异常；以下系统退出记录可能仍有线索。")
        appendLine()
        appendLine("系统退出记录（时间及退出原因，不代表已确认根因）")
        if (Build.VERSION.SDK_INT >= 30) {
            val exits = runCatching {
                getSystemService(ActivityManager::class.java)
                    ?.getHistoricalProcessExitReasons(packageName, 0, 12)
                    ?.filter { it.processName == packageName }
                    ?.take(6).orEmpty()
            }.getOrElse {
                appendLine("无法读取系统记录：${it.javaClass.simpleName}")
                emptyList()
            }
            if (exits.isEmpty()) appendLine("系统未提供主进程退出记录。")
            exits.forEach { exit ->
                val reason = when (exit.reason) {
                    ApplicationExitInfo.REASON_CRASH -> "Java 崩溃"
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native 崩溃"
                    ApplicationExitInfo.REASON_ANR -> "应用无响应"
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "系统内存不足"
                    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "进程初始化失败"
                    else -> "退出原因 ${exit.reason}"
                }
                appendLine("${DateFormat.getDateTimeInstance().format(Date(exit.timestamp))}: $reason, status=${exit.status}")
                appendLine(LaunchFailure.redact(exit.description.orEmpty().take(2048)))
            }
        } else appendLine("当前 Android 版本不提供系统退出记录。")
    }
}

internal fun Context.isDiagnosticsProcess(): Boolean {
    val name = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName()
        else getSystemService(ActivityManager::class.java)?.runningAppProcesses
            ?.firstOrNull { it.pid == Process.myPid() }?.processName
    return name == "$packageName:diagnostics"
}
