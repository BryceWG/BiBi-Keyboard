/**
 * AI 后处理（LLM）的调用前预热：录音开始时提前建立可复用的 TCP/TLS 连接，
 * 并把首次请求模式探测挪进用户说话的时间窗口。
 *
 * 归属模块：asr
 */
package com.brycewg.asrkb.asr

import android.os.SystemClock
import android.util.Log
import com.brycewg.asrkb.store.JevClassifierProvider
import com.brycewg.asrkb.store.LlmModelConfigResolver
import com.brycewg.asrkb.store.LlmModelResolution
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.PromptSelectorModelRef
import com.brycewg.asrkb.store.ResolvedLlmModelConfig
import com.brycewg.asrkb.store.debug.DebugLogManager
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * LLM 与在线 ASR 用的是两个独立的 OkHttp 连接池（见 [AsrHttpClientProvider] 与
 * [LlmPostProcessor.defaultSharedHttpClient]），所以 [AsrConnectionWarmer] 的预热对
 * 润色链路无效，必须在 LLM 自己的池上单独预热。
 *
 * 与 ASR 侧不同，这里的目标 origin 不需要从真实请求里学习：LLM endpoint 由用户直接配置，
 * 可以随时从 [Prefs] 读出来，因此也不需要落盘任何 URL。
 */
internal object LlmConnectionWarmer {
    private const val TAG = "LlmConnectionWarmer"

    // OkHttp 连接池默认空闲保活 5 分钟，留出余量：超过该窗口就认为连接可能已被回收。
    private const val WARM_SKIP_WINDOW_MS = 4 * 60 * 1000L
    private const val WARM_TIMEOUT_SECONDS = 4L

    private val lastTouchedAtByOrigin = ConcurrentHashMap<String, Long>()
    private val warmInFlightOrigins = ConcurrentHashMap<String, Boolean>()

    @Volatile private var requestModePrewarmInFlight = false

    private val prewarmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val warmClient: OkHttpClient by lazy {
        // 必须由 LLM 的共享 client 派生：连接复用要求 OkHttp Address 一致，且共用同一个连接池。
        // 共享 client 的 readTimeout 为 0（SSE 长读），预热必须自己收紧，避免挂在半开连接上。
        LlmPostProcessor.defaultSharedHttpClient().newBuilder()
            .connectTimeout(WARM_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(WARM_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WARM_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(WARM_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 录音会话即将开始时调用。
     *
     * 同时预热默认润色模型与（启用自动选择时的）独立选择模型；同一个 origin 只预热一次。
     * 异步执行、失败静默，不影响会话状态。
     */
    fun warmForImmediateUse(prefs: Prefs) {
        val targets = try {
            buildWarmTargets(prefs)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read LLM config for warmup", t)
            return
        }
        if (targets.isEmpty()) return
        targets.forEach { warmConnection(it.endpoint) }
        buildJevWarmEndpoint(prefs)?.let(::warmConnection)
        prewarmRequestModes(prefs, targets)
    }

    private data class WarmTarget(
        val endpoint: String,
        val resolved: ResolvedLlmModelConfig
    )

    private fun buildWarmTargets(prefs: Prefs): List<WarmTarget> {
        val targets = mutableListOf<WarmTarget>()
        if (prefs.postProcessEnabled && prefs.hasLlmKeys()) {
            val resolved = LlmModelConfigResolver.resolveActiveConfig(prefs)
            if (resolved.endpoint.isNotBlank()) {
                targets += WarmTarget(resolved.endpoint, resolved)
            }
        }
        if (prefs.promptAutoSelectEnabled) {
            val ref = prefs.promptSelectorModelRef
            val resolved = (LlmModelConfigResolver.resolve(prefs, ref) as? LlmModelResolution.Resolved)
                ?.config
            if (resolved != null && resolved.endpoint.isNotBlank()) {
                targets += WarmTarget(resolved.endpoint, resolved)
            }
        }
        // 同一个模型配置只需预热一次（例如选择模型跟随默认润色模型时）。
        return targets.distinctBy { it.resolved.requestModeCapabilityKey }
    }

    private fun buildJevWarmEndpoint(prefs: Prefs): String? {
        if (!prefs.promptAutoSelectEnabled) return null
        val ref = prefs.promptSelectorModelRef as? PromptSelectorModelRef.Jev ?: return null
        return when (JevClassifierProvider.fromId(ref.providerId)) {
            JevClassifierProvider.TYPESAFE -> "https://api.typesafe.ai/v1/systemone"
            JevClassifierProvider.OPENROUTER -> "https://openrouter.ai/api/alpha/decisions"
            JevClassifierProvider.CLOUDFLARE -> "https://api.cloudflare.com/client/v4"
            null -> null
        }
    }

    private fun warmConnection(endpoint: String) {
        // 只取 scheme://host:port：路径与查询串可能带凭证，预热不需要它们。
        val endpointUrl = endpoint.toHttpUrlOrNull() ?: return
        val origin = "${endpointUrl.scheme}://${endpointUrl.host}:${endpointUrl.port}"
        val now = SystemClock.elapsedRealtime()
        val lastTouchedAt = lastTouchedAtByOrigin[origin]
        val stillWarm = lastTouchedAt != null &&
            now - lastTouchedAt < WARM_SKIP_WINDOW_MS &&
            hasPooledConnection()
        if (stillWarm) return
        if (warmInFlightOrigins.putIfAbsent(origin, true) != null) return

        val url = origin.toHttpUrlOrNull()
        if (url == null) {
            warmInFlightOrigins.remove(origin)
            Log.w(TAG, "Discarding unparseable warmup origin")
            return
        }
        // 只发 HEAD 且不带任何凭证：目的仅是让连接进入连接池，响应码是 401/404/405 都无所谓。
        // ApiLogInterceptor 会跳过无 tag 的 HEAD 请求，所以预热不会污染 API Log。
        val request = Request.Builder()
            .url(url)
            .head()
            .build()
        val startedAt = SystemClock.elapsedRealtime()
        try {
            warmClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    warmInFlightOrigins.remove(origin)
                    logWarmResult(url.host, "failed", startedAt, e.javaClass.simpleName)
                }

                override fun onResponse(call: Call, response: Response) {
                    warmInFlightOrigins.remove(origin)
                    response.use { lastTouchedAtByOrigin[origin] = SystemClock.elapsedRealtime() }
                    logWarmResult(url.host, "ok", startedAt, null)
                }
            })
        } catch (t: Throwable) {
            warmInFlightOrigins.remove(origin)
            Log.w(TAG, "Failed to enqueue LLM connection warmup", t)
        }
    }

    /**
     * 首次探测请求模式会发一次完整的 LLM 往返；这里在后台跑，把它挪出停录后的等待窗口。
     * 已探测过的 vendor+endpoint 不会产生任何请求。
     */
    private fun prewarmRequestModes(prefs: Prefs, targets: List<WarmTarget>) {
        if (requestModePrewarmInFlight) return
        requestModePrewarmInFlight = true
        prewarmScope.launch {
            val processor = LlmPostProcessor()
            targets.forEach { target ->
                val startedAt = SystemClock.elapsedRealtime()
                try {
                    val probed = processor.prewarmRequestMode(prefs, target.resolved)
                    if (probed) {
                        DebugLogManager.log(
                            category = "asr",
                            event = "llm_request_mode_prewarm",
                            data = mapOf(
                                "elapsed_ms" to (SystemClock.elapsedRealtime() - startedAt),
                                "vendor" to target.resolved.vendorId
                            )
                        )
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "LLM request mode prewarm failed", t)
                }
            }
            requestModePrewarmInFlight = false
        }
    }

    private fun hasPooledConnection(): Boolean = try {
        warmClient.connectionPool.connectionCount() > 0
    } catch (t: Throwable) {
        Log.w(TAG, "Failed to read LLM connection pool state", t)
        false
    }

    private fun logWarmResult(host: String, result: String, startedAt: Long, errorType: String?) {
        try {
            DebugLogManager.log(
                category = "asr",
                event = "llm_conn_warm",
                data = mapOf(
                    "host" to host,
                    "result" to result,
                    "elapsed_ms" to (SystemClock.elapsedRealtime() - startedAt),
                    "error" to errorType
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to log LLM connection warmup result", t)
        }
    }
}
