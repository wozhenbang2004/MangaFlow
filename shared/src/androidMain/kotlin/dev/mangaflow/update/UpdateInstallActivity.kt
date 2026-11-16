package dev.mangaflow.update

import android.app.Activity
import android.app.ActivityOptions
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Foreground bridge for this app's verified self-update, never a silent installer. */
class UpdateInstallActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var retryButton: Button
    private lateinit var continueButton: Button
    private var observation: Job? = null
    private var blockedConfirmation: String? = null
    private val updates get() = UpdateManager.get(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val spacing = (24 * resources.displayMetrics.density).toInt()
        statusView = TextView(this).apply { textSize = 16f; setTextIsSelectable(true) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(spacing, spacing, spacing, spacing)
            addView(statusView)
            retryButton = Button(this@UpdateInstallActivity).apply {
                text = "取消此请求后重试"
                setOnClickListener {
                    AlertDialog.Builder(this@UpdateInstallActivity)
                        .setMessage("仅取消此次系统安装请求，保留已下载 APK、已装应用和阅读数据。之后可返回软件更新重新安装。")
                        .setNegativeButton("保留请求", null)
                        .setPositiveButton("取消此请求") { _, _ ->
                            val id = intent.getIntExtra("session_id", -1)
                            val token = intent.getStringExtra("install_token").orEmpty()
                            try {
                                statusView.text = updates.cancelInstallRequest(id, token)
                                    ?: "此安装请求已结束，请返回软件更新"
                                isEnabled = false
                            } catch (failure: Exception) {
                                statusView.text = "未能取消安装请求：${failure.message.orEmpty().take(300)}"
                            }
                        }.show()
                }
            }
            addView(retryButton)
            continueButton = Button(this@UpdateInstallActivity).apply {
                text = "继续上次安装确认"
                visibility = if (intent.getBooleanExtra("recover_only", false)) View.VISIBLE else View.GONE
                setOnClickListener {
                    intent.putExtra("recover_only", false)
                    visibility = View.GONE
                    handle(intent)
                    updates.installationEvents.value += 1
                }
            }
            addView(continueButton)
            addView(Button(this@UpdateInstallActivity).apply {
                text = "返回软件更新"
                setOnClickListener {
                    packageManager.getLaunchIntentForPackage(packageName)?.let {
                        startActivity(it.putExtra("open_updates", true)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    }
                    finish()
                }
            })
        }
        setContentView(ScrollView(this).apply { addView(content) })
        // Rotation must not replay an already consumed confirmation intent.
        if (savedInstanceState != null) {
            statusView.text = savedInstanceState.getString("status") ?: "等待系统安装结果；可返回软件更新查看"
            retryButton.isEnabled = savedInstanceState.getBoolean("can_cancel", true)
        } else handle(intent)
    }

    @Suppress("DEPRECATION")
    override fun onResume() {
        super.onResume()
        blockedConfirmation = null
        observation = CoroutineScope(Dispatchers.Main).launch {
            updates.installationEvents.collect {
                val id = intent.getIntExtra("session_id", -1)
                val token = intent.getStringExtra("install_token").orEmpty()
                updates.installationDisplayMessage(id, token)?.let { statusView.text = it }
                retryButton.isEnabled = !updates.installRequestTerminal(id, token)
                continueButton.isEnabled = retryButton.isEnabled
                if (intent.getBooleanExtra("recover_only", false) && retryButton.isEnabled) {
                    statusView.text = "上次安装请求仍未结束。取消它后可安装新版本，也可以明确选择继续上次确认。"
                    return@collect
                }
                val confirmation = updates.pendingInstallConfirmation(id, token)
                if (confirmation == null && retryButton.isEnabled) {
                    statusView.append("\n若确认窗口已关闭或无法恢复，请取消此请求后重试。")
                }
                // No background launch: this observer exists only while this activity is resumed.
                confirmation?.takeIf { blockedConfirmation != "$id:$token" }?.let { pending ->
                    try {
                        val options = ActivityOptions.makeBasic().apply {
                            if (Build.VERSION.SDK_INT >= 36) setPendingIntentBackgroundActivityStartMode(
                                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE)
                            else if (Build.VERSION.SDK_INT >= 34) setPendingIntentBackgroundActivityStartMode(
                                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                        }
                        pending.send(this@UpdateInstallActivity, 0, null, null, null, null, options.toBundle())
                        updates.releaseInstallConfirmation(id, token)
                    } catch (_: PendingIntent.CanceledException) {
                        updates.releaseInstallConfirmation(id, token)
                        statusView.append("\n确认入口已失效，可取消此请求后重试。")
                    }
                    catch (failure: Exception) {
                        blockedConfirmation = "$id:$token"
                        updates.installationLaunchFailed(id, token, failure.message ?: "无法继续系统确认")
                    }
                }
            }
        }
    }

    override fun onPause() {
        observation?.cancel()
        observation = null
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("status", statusView.text.toString())
        outState.putBoolean("can_cancel", retryButton.isEnabled)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    @Suppress("DEPRECATION")
    private fun handle(request: Intent) {
        continueButton.visibility = if (request.getBooleanExtra("recover_only", false)) View.VISIBLE else View.GONE
        val id = request.getIntExtra("session_id", -1)
        val token = request.getStringExtra("install_token").orEmpty()
        if (!updates.ownsInstallSession(id, token)) {
            statusView.text = "此安装会话已结束或失效，请返回软件更新查看结果"
            return
        }
        try {
            retryButton.isEnabled = !updates.installRequestTerminal(id, token)
            continueButton.isEnabled = retryButton.isEnabled
            updates.installedUpdateMessage(id, token)?.let {
                statusView.text = it
                retryButton.isEnabled = false
                return
            }
            if (request.getBooleanExtra("recover_only", false)) {
                statusView.text = "请先处理上次安装请求"
                return
            }
            if (request.action == CONFIRM_ACTION) {
                updates.releaseInstallConfirmation(id, token)
                if (updates.installRequestTerminal(id, token)) {
                    statusView.text = updates.installationDisplayMessage(id, token)
                    retryButton.isEnabled = false
                    return
                }
                val confirmation = if (Build.VERSION.SDK_INT >= 33)
                    request.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else request.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                requireNotNull(confirmation) { "系统未返回安装确认界面" }
                startActivity(confirmation)
                return
            }
            val installer = packageManager.packageInstaller
            val info = installer.getSessionInfo(id)
            requireNotNull(info) { "系统安装会话已不存在" }
            if (info.isSealed) {
                statusView.text = "安装请求已交给系统，结果未知。若确认窗口已关闭，可取消此请求后重试"
                return
            }
            val callback = Intent(this, UpdateInstallReceiver::class.java).apply {
                action = "dev.mangaflow.update.INSTALL_RESULT"
                data = Uri.parse("mangaflow-install://result/$id/$token")
                putExtra("session_id", id)
                putExtra("install_token", token)
            }
            // Explicit receiver only persists the result; it never starts an activity.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val result = PendingIntent.getBroadcast(this, id, callback, flags)
            statusView.text = "等待系统安装确认；不会自动确认或删除原有数据"
            installer.openSession(id).use { it.commit(result.intentSender) }
        } catch (failure: Exception) {
            statusView.text = updates.installationLaunchFailed(id, token, failure.message ?: "无法打开系统安装器")
                ?: "未能继续安装，请返回软件更新"
        }
    }

    internal companion object {
        private const val CONFIRM_ACTION = "dev.mangaflow.update.CONFIRM_INSTALL"
        fun confirmationIntent(context: Context, id: Int, token: String): Intent =
            Intent(context, UpdateInstallActivity::class.java).apply {
                action = CONFIRM_ACTION
                data = Uri.parse("mangaflow-install://confirm/$id/$token")
                putExtra("session_id", id)
                putExtra("install_token", token)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
    }
}
