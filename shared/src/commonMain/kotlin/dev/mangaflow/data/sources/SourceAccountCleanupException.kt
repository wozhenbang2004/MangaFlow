package dev.mangaflow.data.sources

/** The local account change committed; only retiring-client cleanup failed. No secrets in UI text. */
internal class SourceAccountCleanupException(signedIn: Boolean, cause: Exception) : Exception(
    if (signedIn) "账号已登录，但旧会话收尾异常；请重启应用完成清理。"
    else "账号已退出，但旧会话收尾异常；请重启应用完成清理。",
    cause,
)
