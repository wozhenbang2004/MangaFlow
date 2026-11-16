package dev.mangaflow.anime.sources.lanerc

import dev.mangaflow.json.JSONObject
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

internal actual fun <T> lanercBlocking(context: CoroutineContext, block: suspend () -> T): T =
    runBlocking(context.minusKey(ContinuationInterceptor)) { block() }

internal actual fun lanercPlatformCrypto(request: JSONObject): ByteArray =
    throw LanercCapabilityException("此来源需要当前 iOS 宿主尚未支持的加密或解压能力，请选择其他来源。")
