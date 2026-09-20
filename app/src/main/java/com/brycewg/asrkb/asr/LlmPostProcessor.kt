/**
 * LLM 后处理与 AI 编辑调用入口。
 *
 * 归属模块：asr
 */
package com.brycewg.asrkb.asr

import android.util.Log
import com.brycewg.asrkb.BuildConfig
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.PromptSelectionStatus
import com.brycewg.asrkb.store.ResolvedLlmModelConfig
import com.brycewg.asrkb.store.debug.DebugLogManager
import com.brycewg.asrkb.store.debug.StreamingPreviewDiag
import com.brycewg.asrkb.util.TextSanitizer
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI 格式的 ASR 文本后处理器，用于文本清理和 AI 编辑。
 * 使用与 Chat Completions 兼容的 API，并在存在简单字段时回退使用。
 */
class LlmPostProcessor(private val client: OkHttpClient? = null) {
    enum class LlmResponseMode {
        SSE,
        NON_SSE
    }

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    @Volatile
    private var activeCall: Call? = null

    @Volatile
    private var cancelRequested: Boolean = false

    /** Request modes discovered by an ephemeral caller (for example, a settings preview). */
    private val transientRequestModes = ConcurrentHashMap<String, Prefs.LlmRequestMode>()

    /**
     * LLM 测试结果。responseHeadersMs 覆盖最终 attempt 发起到响应头到达；
     * firstVisibleMs/outputMs 仅用于 SSE，responseBodyMs 仅用于非 SSE。
     */
    data class LlmTestResult(
        val ok: Boolean,
        val httpCode: Int? = null,
        val message: String? = null,
        val contentPreview: String? = null,
        val responseMode: LlmResponseMode? = null,
        val totalMs: Long = 0,
        val connectionMs: Long = 0,
        val responseHeadersMs: Long = 0,
        val firstVisibleMs: Long = 0,
        val outputMs: Long = 0,
        val responseBodyMs: Long = 0,
        val connectionReused: Boolean = false,
        val fallbackUsed: Boolean = false
    )

    /**
     * /models 拉取结果
     */
    data class LlmModelsResult(
        val ok: Boolean,
        val models: List<String> = emptyList(),
        val httpCode: Int? = null,
        val message: String? = null
    )

    /**
     * 统一的底层调用结果
     */
    private data class RawCallResult(
        val ok: Boolean,
        val httpCode: Int? = null,
        val text: String? = null,
        val error: String? = null,
        val responseMode: LlmResponseMode? = null,
        val totalMs: Long = 0,
        val connectionMs: Long = 0,
        val responseHeadersMs: Long = 0,
        val firstVisibleMs: Long = 0,
        val outputMs: Long = 0,
        val responseBodyMs: Long = 0,
        val connectionReused: Boolean = false,
        val fallbackUsed: Boolean = false
    )

    private data class StreamParseResult(
        val text: String,
        val firstVisibleAtElapsed: Long,
        val protocolCompleted: Boolean
    )

    private data class RequestModeProbeResult(
        val mode: Prefs.LlmRequestMode? = null,
        val failure: RawCallResult? = null
    )

    /**
     * 单次 HTTP 调用的连接事件。connectStart 未触发即视为复用了连接池里的连接；
     * 新建连接耗时从 DNS（若有）或 connectStart 起算，到 connectEnd 为止。
     */
    private class LlmHttpEventTiming : EventListener() {
        var connectionReused: Boolean = true
            private set
        var connectionMs: Long = 0
            private set

        private var connectionStartedAtElapsed = 0L

        override fun dnsStart(call: Call, domainName: String) {
            if (connectionStartedAtElapsed == 0L) {
                connectionStartedAtElapsed = elapsedRealtimeMs()
            }
        }

        override fun connectStart(
            call: Call,
            inetSocketAddress: InetSocketAddress,
            proxy: Proxy
        ) {
            connectionReused = false
            if (connectionStartedAtElapsed == 0L) {
                connectionStartedAtElapsed = elapsedRealtimeMs()
            }
        }

        override fun connectEnd(
            call: Call,
            inetSocketAddress: InetSocketAddress,
            proxy: Proxy,
            protocol: Protocol?
        ) {
            if (connectionStartedAtElapsed != 0L) {
                connectionMs =
                    (elapsedRealtimeMs() - connectionStartedAtElapsed).coerceAtLeast(0L)
            }
        }

        override fun connectFailed(
            call: Call,
            inetSocketAddress: InetSocketAddress,
            proxy: Proxy,
            protocol: Protocol?,
            ioe: IOException
        ) {
            connectionReused = false
        }
    }

    /**
     * 标准化的上层处理结果，用于向调用方传递是否成功以及返回文本。
     */
    data class LlmProcessResult(
        val ok: Boolean,
        val text: String,
        val errorMessage: String? = null,
        val httpCode: Int? = null,
        // 表示本次结果是否“实际使用了 AI 输出”（调用成功并采用其文本）
        val usedAi: Boolean = false,
        // 是否实际发起了 LLM 请求（跳过/空输入等场景为 false）
        val attempted: Boolean = false,
        // LLM 请求耗时（毫秒）；未尝试时为 0
        val llmMs: Long = 0,
        // 实际请求使用的 LLM 渠道；未尝试时为 null。
        val llmVendorId: String? = null,
        // 自动选择提示词的结果快照；未启用自动选择时为 null。
        val promptSelectionStatus: PromptSelectionStatus? = null
    )

    /**
     * LLM 请求配置
     */
    private data class LlmRequestConfig(
        val apiKey: String,
        val endpoint: String,
        val model: String,
        val temperature: Double,
        val vendor: LlmVendor,
        val enableReasoning: Boolean,
        val reasoningCharThreshold: Int,
        val supportsReasoningControl: Boolean,
        val useCustomReasoningParams: Boolean,
        val reasoningParamsOnJson: String,
        val reasoningParamsOffJson: String,
        val requestModeCapabilityKey: String
    )

    companion object {
        private const val TAG = "LlmPostProcessor"

        private fun elapsedRealtimeMs(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime())

        /** 连接超时（秒） */
        private const val CONNECT_TIMEOUT_SECONDS = 30L

        /** 协议完成后异步排空剩余 SSE，便于 HTTP/1.1 把连接放回池 */
        private const val STREAM_DRAIN_TIMEOUT_MS = 300L

        private const val CONNECTIVITY_TEST_PROMPT =
            "Rewrite each dictated text below as clean written text. " +
                "Keep the meaning and roughly the same length, output only the two rewritten texts " +
                "labeled [EN] and [ZH]: " +
                "[EN] \"um yesterday like me and my friend we went to the park " +
                "and uh the weather was really nice so we played some football " +
                "and then we had ice cream\"; " +
                "[ZH] \"就是昨天吧，我和我朋友然后去了那个公园，嗯天气特别好，" +
                "我们就踢了会儿足球，然后还吃了个冰淇淋\""

        /** 同进程内只允许一个未知供应商执行首次请求模式探测。 */
        private val requestModeProbeMutex = Mutex()

        private val sharedHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .addInterceptor(ApiLogInterceptor())
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .writeTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
        }

        private val sharedModelsHttpClient: OkHttpClient by lazy {
            sharedHttpClient.newBuilder()
                .readTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
        }

        internal fun defaultSharedHttpClient(): OkHttpClient = sharedHttpClient
    }

    private fun buildRequestConfig(
        apiKey: String,
        endpoint: String,
        model: String,
        temperature: Double,
        vendor: LlmVendor,
        reasoningCharThreshold: Int,
        inputText: String,
        useCustomReasoningParams: Boolean,
        reasoningParamsOnJson: String,
        reasoningParamsOffJson: String,
        capabilityIdentity: String
    ): LlmRequestConfig {
        val supportsReasoning = vendor.supportsReasoningControl(model)
        val threshold = LlmReasoningThreshold.coerce(reasoningCharThreshold)
        return LlmRequestConfig(
            apiKey = apiKey,
            endpoint = endpoint,
            model = model,
            temperature = temperature,
            vendor = vendor,
            enableReasoning = LlmReasoningThreshold.shouldEnable(
                threshold,
                TextSanitizer.countEffectiveChars(inputText)
            ),
            reasoningCharThreshold = threshold,
            supportsReasoningControl = supportsReasoning,
            useCustomReasoningParams = useCustomReasoningParams,
            reasoningParamsOnJson = reasoningParamsOnJson,
            reasoningParamsOffJson = reasoningParamsOffJson,
            requestModeCapabilityKey =
            "$capabilityIdentity|${endpoint.trim().trimEnd('/')}"
        )
    }

    /**
     * 从 Prefs 获取活动的 LLM 配置（使用新的供应商架构）
     */
    private fun getActiveConfig(prefs: Prefs, inputText: String = ""): LlmRequestConfig {
        val vendor = prefs.llmVendor
        if (vendor == LlmVendor.SF_FREE && !prefs.sfFreeLlmUsePaidKey) {
            val effective = prefs.getEffectiveLlmConfig()
            return buildRequestConfig(
                apiKey = BuildConfig.SF_FREE_API_KEY,
                endpoint = Prefs.SF_CHAT_COMPLETIONS_ENDPOINT,
                model = prefs.sfFreeLlmModel,
                temperature = Prefs.DEFAULT_LLM_TEMPERATURE.toDouble(),
                vendor = vendor,
                reasoningCharThreshold = prefs.getLlmVendorReasoningCharThreshold(vendor),
                inputText = inputText,
                useCustomReasoningParams = effective?.useCustomReasoningParams ?: false,
                reasoningParamsOnJson = effective?.reasoningParamsOnJson
                    ?: Prefs.DEFAULT_CUSTOM_REASONING_PARAMS_ON_JSON,
                reasoningParamsOffJson = effective?.reasoningParamsOffJson
                    ?: Prefs.DEFAULT_CUSTOM_REASONING_PARAMS_OFF_JSON,
                capabilityIdentity = vendor.id
            )
        }

        prefs.getEffectiveLlmConfig()?.let { config ->
            val customProviderId = if (config.vendor == LlmVendor.CUSTOM) {
                prefs.getActiveLlmProvider()?.id ?: prefs.activeLlmId.ifBlank { "default" }
            } else {
                null
            }
            return buildRequestConfig(
                apiKey = config.apiKey,
                endpoint = config.endpoint,
                model = config.model,
                temperature = config.temperature.toDouble(),
                vendor = config.vendor,
                reasoningCharThreshold = config.reasoningCharThreshold,
                inputText = inputText,
                useCustomReasoningParams = config.useCustomReasoningParams,
                reasoningParamsOnJson = config.reasoningParamsOnJson,
                reasoningParamsOffJson = config.reasoningParamsOffJson,
                capabilityIdentity = customProviderId?.let { "custom:$it" } ?: config.vendor.id
            )
        }

        val active = prefs.getActiveLlmProvider()
        return buildRequestConfig(
            apiKey = active?.apiKey ?: prefs.llmApiKey,
            endpoint = if (vendor.hasBuiltinEndpoint) vendor.endpoint else active?.endpoint ?: prefs.llmEndpoint,
            model = active?.model ?: prefs.llmModel,
            temperature = (active?.temperature ?: prefs.llmTemperature).toDouble(),
            vendor = vendor,
            reasoningCharThreshold = if (vendor == LlmVendor.CUSTOM) {
                active?.resolvedReasoningCharThreshold() ?: LlmReasoningThreshold.NEVER
            } else {
                prefs.getLlmVendorReasoningCharThreshold(vendor)
            },
            inputText = inputText,
            useCustomReasoningParams = false,
            reasoningParamsOnJson = Prefs.DEFAULT_CUSTOM_REASONING_PARAMS_ON_JSON,
            reasoningParamsOffJson = Prefs.DEFAULT_CUSTOM_REASONING_PARAMS_OFF_JSON,
            capabilityIdentity = if (vendor == LlmVendor.CUSTOM) {
                "custom:${active?.id ?: prefs.activeLlmId.ifBlank { "default" }}"
            } else {
                vendor.id
            }
        )
    }

    /** 由已解析的模型配置构造请求配置；推理开关按输入长度实时求值。 */
    private fun toRequestConfig(
        prefs: Prefs,
        resolved: ResolvedLlmModelConfig,
        inputText: String
    ): LlmRequestConfig = buildRequestConfig(
        apiKey = resolved.apiKey,
        endpoint = resolved.endpoint,
        model = resolved.model,
        temperature = resolved.temperature,
        vendor = resolved.vendor,
        reasoningCharThreshold = resolved.reasoningCharThreshold,
        inputText = inputText,
        useCustomReasoningParams = resolved.useCustomReasoningParams,
        reasoningParamsOnJson = resolved.reasoningParamsOnJson,
        reasoningParamsOffJson = resolved.reasoningParamsOffJson,
        capabilityIdentity = resolved.capabilityIdentity
    )

    /**
     * 解析 URL，自动添加 /chat/completions 后缀
     */
    private fun resolveUrl(base: String): String {
        val raw = base.trim()
        if (raw.isEmpty()) return Prefs.DEFAULT_LLM_ENDPOINT.trimEnd('/') + "/chat/completions"
        val b = raw.trimEnd('/')
        // 要求用户填写完整 URL（包含 http/https），不再自动补全协议
        val hasScheme = b.startsWith("http://", true) || b.startsWith("https://", true)
        if (!hasScheme) {
            throw IllegalArgumentException(
                "Endpoint must start with http:// or https://"
            )
        }

        // 如果已直接指向 chat/completions 或 responses，则原样使用
        if (b.endsWith("/chat/completions")) return b

        // 其他情况：直接补全 /chat/completions
        return "$b/chat/completions"
    }

    /**
     * 解析 /models URL，支持将 /chat/completions 转换为 /models
     */
    private fun resolveModelsUrl(base: String): String {
        val raw = base.trim()
        if (raw.isEmpty()) throw IllegalArgumentException("Missing endpoint")
        val b = raw.trimEnd('/')
        val hasScheme = b.startsWith("http://", true) || b.startsWith("https://", true)
        if (!hasScheme) {
            throw IllegalArgumentException(
                "Endpoint must start with http:// or https://"
            )
        }
        if (b.endsWith("/models")) return b
        if (b.endsWith("/chat/completions")) {
            return b.removeSuffix("/chat/completions") + "/models"
        }
        return "$b/models"
    }

    /**
     * 根据供应商添加推理控制参数到请求体
     *
     * @param body 请求 JSON 对象
     * @param config LLM 配置
     */
    private fun addReasoningParams(body: JSONObject, config: LlmRequestConfig) {
        val vendor = config.vendor
        if (config.useCustomReasoningParams) {
            val raw = if (config.enableReasoning) config.reasoningParamsOnJson else config.reasoningParamsOffJson
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return
            if (!trimmed.startsWith("{")) {
                Log.w(TAG, "Reasoning params must be a JSON object: $trimmed")
                return
            }
            val obj = try {
                JSONObject(trimmed)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to parse reasoning params JSON: $trimmed", t)
                return
            }
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                body.put(key, obj.opt(key))
            }
            return
        }

        if (!config.supportsReasoningControl) return

        when (vendor) {
            LlmVendor.SF_FREE -> {
                // SiliconFlow: enable_thinking 支持显式开关
                body.put("enable_thinking", config.enableReasoning)
                return
            }
            LlmVendor.VOLCENGINE, LlmVendor.ZHIPU -> {
                // 火山/智谱：通过 thinking.type 控制开关
                val type = if (config.enableReasoning) "enabled" else "disabled"
                body.put("thinking", JSONObject().put("type", type))
                return
            }
            LlmVendor.GEMINI -> {
                // Gemini Pro 只能将预算调低；flash 系列可关闭
                if (config.enableReasoning) return
                val modelLower = config.model.lowercase()
                val effort = if (modelLower.contains("pro") ||
                    modelLower.startsWith("gemini-3")
                ) {
                    "low"
                } else {
                    "none"
                }
                body.put("reasoning_effort", effort)
                return
            }
            LlmVendor.GROQ -> {
                // Groq：仅对支持思考的模型下发对应最小值
                if (config.enableReasoning) return
                val modelLower = config.model.lowercase()
                val effort = when {
                    modelLower.contains("qwen3") || modelLower.contains("qwen/") -> "none"
                    modelLower.contains("gpt-oss") -> "low"
                    else -> return
                }
                body.put("reasoning_effort", effort)
                return
            }
            LlmVendor.CEREBRAS -> {
                // Cerebras 仅 gpt-oss-120b 支持 reasoning_effort，且最小为 low
                val isGptOss120b = config.model.equals("gpt-oss-120b", ignoreCase = true)
                if (!isGptOss120b) return
                if (!config.enableReasoning) {
                    body.put("reasoning_effort", "low")
                }
                return
            }
            LlmVendor.FIREWORKS -> {
                // Fireworks 模型有不同的推理控制行为:
                // - DeepSeek V3.1/V3.2: 二进制开关，默认关闭
                // - GLM 4.5/4.6: 二进制开关，默认开启
                // - GPT-OSS: 只支持 low/medium/high，不支持 none
                val modelLower = config.model.lowercase()
                when {
                    modelLower.contains("deepseek") -> {
                        // DeepSeek: 开启发送 medium，关闭发送 none
                        body.put(
                            "reasoning_effort",
                            if (config.enableReasoning) "medium" else "none"
                        )
                    }
                    modelLower.contains("glm") -> {
                        // GLM: 默认开启，仅关闭时发送 none
                        if (!config.enableReasoning) {
                            body.put("reasoning_effort", "none")
                        }
                    }
                    modelLower.contains("gpt-oss") -> {
                        // GPT-OSS: 不支持 none，开启用 medium，关闭用 low
                        body.put(
                            "reasoning_effort",
                            if (config.enableReasoning) "medium" else "low"
                        )
                    }
                }
                return
            }
            else -> {
                // fall through to generic handling
            }
        }

        when (vendor.reasoningMode) {
            ReasoningMode.ENABLE_THINKING -> {
                body.put("enable_thinking", config.enableReasoning)
            }
            ReasoningMode.REASONING_EFFORT -> {
                if (!config.enableReasoning) {
                    body.put("reasoning_effort", "none")
                }
            }
            ReasoningMode.THINKING_TYPE -> {
                val type = if (config.enableReasoning) "enabled" else "disabled"
                body.put("thinking", JSONObject().put("type", type))
            }
            ReasoningMode.MODEL_SELECTION, ReasoningMode.NONE -> {
                // No parameter needed - controlled via model selection or not supported
            }
        }
    }

    /**
     * 构建标准的 OpenAI Chat Completions 请求
     *
     * @param config LLM 配置
     * @param messages 消息列表（JSONArray）
     * @param streaming 是否启用流式传输
     * @return 构建好的 Request 对象
     */
    private fun buildRequest(
        config: LlmRequestConfig,
        messages: JSONArray,
        streaming: Boolean = true
    ): Request {
        val url = resolveUrl(config.endpoint)

        val reqJson = JSONObject().apply {
            if (config.model.isNotBlank()) {
                put("model", config.model)
            }
            put("temperature", kotlin.math.round(config.temperature * 100) / 100)
            put("messages", messages)
            put("stream", streaming)

            // Add reasoning control parameters based on vendor
            addReasoningParams(this, config)
        }.toString()

        val body = reqJson.toRequestBody(jsonMedia)
        val builder = Request.Builder()
            .url(url)
            .tag(
                ApiLogMeta::class.java,
                ApiLogRecorder.meta(
                    category = "LLM",
                    vendor = logVendorId(config),
                    model = config.model,
                    requestStructure = "json object keys=model, temperature, messages, stream"
                )
            )
            .addHeader("Content-Type", "application/json")
            .post(body)

        if (streaming) {
            builder.addHeader("Accept", "text/event-stream, application/json")
        }

        if (config.apiKey.isNotBlank()) {
            builder.addHeader("Authorization", "Bearer ${config.apiKey}")
        }

        return builder.build()
    }

    /**
     * 获取或创建 OkHttpClient。
     *
     * 连接/写入 30s；readTimeout 为 0。后处理的正文首 token 与输出阶段
     * 由 [LlmPostprocessTimeouts] 在 Call/source 上设绝对 deadline，不设 callTimeout。
     */
    private fun getHttpClient(): OkHttpClient = client ?: sharedHttpClient

    /**
     * 获取模型列表时使用的客户端（避免无限读超时）
     */
    private fun getModelsHttpClient(): OkHttpClient = client ?: sharedModelsHttpClient

    private fun logVendorId(config: LlmRequestConfig): String {
        if (config.vendor != LlmVendor.SF_FREE) return config.vendor.id
        val builtinKey = BuildConfig.SF_FREE_API_KEY
        return if (builtinKey.isNotBlank() && config.apiKey == builtinKey) {
            "siliconflow_free"
        } else {
            "siliconflow"
        }
    }

    /**
     * 过滤掉 AI 输出中的 <think>...</think> 标签及其内容
     * 部分模型会将推理内容放在正文中，需要过滤
     *
     * @param text 原始文本
     * @return 过滤后的文本
     */
    private fun filterThinkTags(text: String): String {
        // 使用正则表达式移除 <think>...</think> 标签及其内容
        // (?s) 表示 DOTALL 模式，让 . 可以匹配换行符
        return text.replace(Regex("""(?s)<think>.*?</think>"""), "").trim()
    }

    private fun filterThinkTagsForStreaming(text: String): String {
        val filtered = filterThinkTags(text)
        val start = filtered.indexOf("<think>")
        if (start < 0) return filtered
        val end = filtered.indexOf("</think>", start + 7)
        if (end >= 0) return filtered
        return filtered.substring(0, start).trimEnd()
    }

    /**
     * 从响应 JSON 中提取文本内容
     *
     * 支持标准 OpenAI 格式和自定义 output_text 字段
     *
     * @param responseJson 响应的 JSON 字符串
     * @param fallback 提取失败时的回退文本
     * @return 提取的文本或 fallback
     */
    private fun extractTextFromResponse(responseJson: String, fallback: String): String = try {
        val obj = JSONObject(responseJson)
        val rawText = when {
            obj.has("choices") -> {
                val choices = obj.getJSONArray("choices")
                if (choices.length() > 0) {
                    val msg = choices.getJSONObject(0).optJSONObject("message")
                    msg?.optString("content")?.ifBlank { fallback } ?: fallback
                } else {
                    fallback
                }
            }
            obj.has("output_text") -> obj.optString("output_text", fallback)
            else -> fallback
        }
        // 过滤掉 think 标签及其内容
        filterThinkTags(rawText)
    } catch (t: Throwable) {
        Log.e(TAG, "Failed to extract text from response", t)
        fallback
    }

    /**
     * 无损查看响应开头，正文特征优先于可能被代理改错的 Content-Type。
     */
    private fun detectStreamingResponseMode(
        source: BufferedSource
    ): LlmResponseMode {
        val peek = source.peek()
        while (true) {
            if (peek.exhausted()) return LlmResponseMode.NON_SSE
            val codePoint = peek.readUtf8CodePoint()
            if (codePoint == 0xFEFF || Character.isWhitespace(codePoint)) {
                continue
            }
            return if (codePoint == '{'.code || codePoint == '['.code) {
                LlmResponseMode.NON_SSE
            } else {
                LlmResponseMode.SSE
            }
        }
    }

    /**
     * 解析 OpenAI 标准 /models 返回，抽取模型 ID 列表
     */
    private fun parseModelsFromResponse(responseJson: String): List<String> {
        val obj = JSONObject(responseJson)
        val data = obj.optJSONArray("data") ?: return emptyList()
        val result = mutableListOf<String>()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val id = item.optString("id").trim()
            if (id.isNotEmpty()) {
                result.add(id)
            }
        }
        return result
    }

    /**
     * 从 SSE 流中解析并拼接所有文本内容。
     * 首 token 指过滤 think 后的可见正文，不是任意 SSE / 推理增量。
     *
     * @param source 响应的 BufferedSource
     * @return 拼接文本、首个可见文本时间与协议完成状态
     */
    private fun parseStreamingResponse(
        source: BufferedSource,
        timeoutBudget: LlmPostprocessTimeouts.Budget?,
        firstTokenDeadlineNs: Long?,
        onStreamingUpdate: ((String) -> Unit)? = null
    ): StreamParseResult {
        val contentBuilder = StringBuilder()
        var lastEmittedText: String? = null
        var warnedCumulative = false
        val timeout = source.timeout()
        var waitingFirstContent = true
        var shouldStop = false
        var firstVisibleAtElapsed = 0L
        val eventBuilder = StringBuilder()

        if (timeoutBudget != null && firstTokenDeadlineNs != null) {
            applyAbsoluteDeadline(timeout, firstTokenDeadlineNs)
        }

        fun noteFirstVisibleContentIfNeeded() {
            if (firstVisibleAtElapsed != 0L) return
            if (filterThinkTagsForStreaming(contentBuilder.toString()).isEmpty()) return
            firstVisibleAtElapsed = elapsedRealtimeMs()
        }

        fun emitStreamingUpdateIfNeeded() {
            val handler = onStreamingUpdate ?: return
            val current = filterThinkTagsForStreaming(contentBuilder.toString())
            if (current.isEmpty() || current == lastEmittedText) return
            lastEmittedText = current
            try {
                handler(current)
            } catch (t: Throwable) {
                Log.w(TAG, "Streaming update callback failed", t)
            }
        }

        fun armOutputDeadlineIfFirstContentArrived() {
            if (!waitingFirstContent) return
            val visible = filterThinkTagsForStreaming(contentBuilder.toString())
            if (visible.isEmpty()) return
            waitingFirstContent = false
            if (timeoutBudget == null) return
            val nowNs = System.nanoTime()
            applyAbsoluteDeadline(timeout, timeoutBudget.callDeadlineNs(nowNs, timeoutBudget.outputMs))
        }

        fun flushEvent() {
            if (eventBuilder.isEmpty()) return
            val rawData = eventBuilder.toString().trim()
            eventBuilder.clear()

            if (rawData.isEmpty()) return
            if (rawData == "[DONE]") {
                shouldStop = true
                return
            }

            try {
                val json = JSONObject(rawData)
                val choices = json.optJSONArray("choices") ?: return
                if (choices.length() == 0) return

                val choice = choices.getJSONObject(0)
                val delta = choice.optJSONObject("delta")
                var appended = false
                if (delta != null) {
                    val deltaText = extractDeltaContent(delta)
                    if (!deltaText.isNullOrEmpty()) {
                        if (appendStreamDelta(contentBuilder, deltaText, warnedCumulative)) {
                            warnedCumulative = true
                        }
                        appended = true
                    }
                }
                if (appended) {
                    noteFirstVisibleContentIfNeeded()
                    emitStreamingUpdateIfNeeded()
                    armOutputDeadlineIfFirstContentArrived()
                }

                val finishReason = choice.optString("finish_reason", "")
                if (finishReason == "stop") {
                    shouldStop = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Parse SSE chunk failed: $rawData", e)
            }
        }

        while (!shouldStop) {
            val exhausted = try {
                source.exhausted()
            } catch (e: IOException) {
                if (cancelRequested) throw e
                if (timeoutBudget != null && isTimeoutThrowable(e)) {
                    val reason = if (waitingFirstContent) "first_token" else "output"
                    throw LlmPostprocessTimeoutException(reason)
                }
                throw e
            }
            if (exhausted) break
            val line = try {
                source.readUtf8Line() ?: break
            } catch (e: IOException) {
                if (cancelRequested) throw e
                if (timeoutBudget != null && isTimeoutThrowable(e)) {
                    val reason = if (waitingFirstContent) "first_token" else "output"
                    throw LlmPostprocessTimeoutException(reason)
                }
                throw e
            }

            if (line.isEmpty()) {
                flushEvent()
                continue
            }

            // SSE 格式: 以 data: 开头的事件行，可能跨多行
            if (line.startsWith("data:")) {
                eventBuilder.append(line.removePrefix("data:").trim()).append('\n')
            }
        }

        // 处理未以空行结尾的事件
        if (!shouldStop) {
            flushEvent()
        }

        return StreamParseResult(
            text = contentBuilder.toString(),
            firstVisibleAtElapsed = firstVisibleAtElapsed,
            protocolCompleted = shouldStop
        )
    }

    private fun recycleSseBodyAsync(
        http: OkHttpClient,
        response: Response,
        source: BufferedSource
    ): Boolean = try {
        http.dispatcher.executorService.execute {
            val startedAt = elapsedRealtimeMs()
            val recycled = drainSseQuietly(source)
            try {
                response.close()
            } catch (closeErr: Throwable) {
                Log.w(TAG, "Close drained SSE response failed", closeErr)
            }
            DebugLogManager.logBase(
                category = "asr",
                event = "llm_sse_body_recycled",
                data = mapOf(
                    "durationMs" to
                        (elapsedRealtimeMs() - startedAt).coerceAtLeast(0L),
                    "recycled" to recycled
                )
            )
        }
        true
    } catch (t: Throwable) {
        Log.w(TAG, "Schedule SSE body recycling failed", t)
        false
    }

    private fun drainSseQuietly(source: BufferedSource): Boolean {
        val timeout = source.timeout()
        val originalTimeoutNanos = timeout.timeoutNanos()
        val hadDeadline = timeout.hasDeadline()
        val originalDeadline = if (hadDeadline) timeout.deadlineNanoTime() else 0L
        var recycled = false
        try {
            timeout.timeout(STREAM_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val drainDeadline =
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STREAM_DRAIN_TIMEOUT_MS)
            timeout.deadlineNanoTime(
                if (hadDeadline) minOf(originalDeadline, drainDeadline) else drainDeadline
            )
            val discard = Buffer()
            while (source.read(discard, 8192L) != -1L) {
                discard.clear()
            }
            recycled = true
        } catch (t: Throwable) {
            Log.w(TAG, "Drain remaining SSE failed", t)
        } finally {
            try {
                timeout.clearDeadline()
                if (hadDeadline) timeout.deadlineNanoTime(originalDeadline)
                timeout.timeout(originalTimeoutNanos, TimeUnit.NANOSECONDS)
            } catch (clearErr: Throwable) {
                Log.w(TAG, "Clear SSE drain timeout failed", clearErr)
            }
        }
        return recycled
    }

    private fun extractDeltaContent(delta: JSONObject): String? = when (val content = delta.opt("content")) {
        is String -> content.takeIf { it.isNotEmpty() }
        is JSONArray -> buildString {
            for (i in 0 until content.length()) {
                when (val item = content.get(i)) {
                    is String -> if (item.isNotEmpty()) append(item)
                    is JSONObject -> {
                        val textPart = item.optString("text")
                        if (textPart.isNotEmpty()) append(textPart)
                    }
                }
            }
        }.takeIf { it.isNotEmpty() }
        else -> null
    }

    /**
     * @return true if a cumulative-delta warning was emitted
     */
    private fun appendStreamDelta(
        builder: StringBuilder,
        deltaText: String,
        alreadyWarned: Boolean
    ): Boolean {
        if (!DebugLogManager.isRecording()) {
            builder.append(deltaText)
            return false
        }

        try {
            DebugLogManager.log(
                category = "asr",
                event = "llm_delta",
                data = mapOf(
                    "builderLen" to builder.length,
                    "deltaLen" to deltaText.length,
                    "rel" to StreamingPreviewDiag.relation(builder, deltaText)
                )
            )
        } catch (_: Throwable) { }
        var warned = false
        if (!alreadyWarned && StreamingPreviewDiag.looksCumulativeDelta(builder, deltaText)) {
            try {
                DebugLogManager.logWarning(
                    category = "asr",
                    event = "llm_delta_cumulative",
                    data = mapOf(
                        "builderLen" to builder.length,
                        "deltaLen" to deltaText.length,
                        "rel" to StreamingPreviewDiag.relation(builder, deltaText)
                    )
                )
            } catch (_: Throwable) { }
            warned = true
        }
        builder.append(deltaText)
        return warned
    }

    /**
     * 复用的底层 Chat 调用：构建请求、执行并解析文本。
     * 按已探测的供应商能力选择请求模式，流式响应支持持续接收。
     * 需确保在非主线程调用。
     */
    private fun performChat(
        prefs: Prefs,
        config: LlmRequestConfig,
        messages: JSONArray,
        requestMode: Prefs.LlmRequestMode,
        onStreamingUpdate: ((String) -> Unit)? = null,
        timeoutBudget: LlmPostprocessTimeouts.Budget? = null,
        persistRequestMode: Boolean = true
    ): RawCallResult {
        val logicalStartedAt = elapsedRealtimeMs()
        if (requestMode == Prefs.LlmRequestMode.NON_STREAMING) {
            return performChatInternal(
                config,
                messages,
                streaming = false,
                timeoutBudget = timeoutBudget
            ).copy(totalMs = (elapsedRealtimeMs() - logicalStartedAt).coerceAtLeast(0L))
        }

        val streamingResult = performChatInternal(
            config,
            messages,
            streaming = true,
            onStreamingUpdate = onStreamingUpdate,
            timeoutBudget = timeoutBudget
        )
        if (streamingResult.ok) {
            if (streamingResult.responseMode == LlmResponseMode.NON_SSE) {
                cacheRequestMode(
                    prefs,
                    config,
                    Prefs.LlmRequestMode.NON_STREAMING,
                    persistRequestMode
                )
            }
            return streamingResult.copy(
                totalMs =
                (elapsedRealtimeMs() - logicalStartedAt).coerceAtLeast(0L)
            )
        }
        if (isNonRetryableFailure(streamingResult)) {
            return streamingResult.copy(
                totalMs =
                (elapsedRealtimeMs() - logicalStartedAt).coerceAtLeast(0L)
            )
        }

        // 若服务端拒绝或不支持流式，尝试回退到非流模式
        if (!shouldRetryWithoutStream(streamingResult)) {
            return streamingResult.copy(
                totalMs =
                (elapsedRealtimeMs() - logicalStartedAt).coerceAtLeast(0L)
            )
        }

        Log.w(
            TAG,
            "Streaming call failed (code=${streamingResult.httpCode}): ${streamingResult.error ?: ""}. Retrying without stream."
        )
        val fallback = performChatInternal(
            config,
            messages,
            streaming = false,
            timeoutBudget = timeoutBudget
        )
        val logicalTotalMs =
            (elapsedRealtimeMs() - logicalStartedAt).coerceAtLeast(0L)
        if (fallback.ok) {
            cacheRequestMode(
                prefs,
                config,
                Prefs.LlmRequestMode.NON_STREAMING,
                persistRequestMode
            )
            return fallback.copy(totalMs = logicalTotalMs, fallbackUsed = true)
        }

        return fallback.copy(
            error = fallback.error ?: streamingResult.error,
            totalMs = logicalTotalMs,
            fallbackUsed = true
        )
    }

    private suspend fun ensureRequestMode(
        prefs: Prefs,
        config: LlmRequestConfig,
        timeoutBudget: LlmPostprocessTimeouts.Budget?,
        persistRequestMode: Boolean = true
    ): RequestModeProbeResult {
        getCachedRequestMode(prefs, config.requestModeCapabilityKey)?.let {
            return RequestModeProbeResult(mode = it)
        }

        return requestModeProbeMutex.withLock {
            getCachedRequestMode(prefs, config.requestModeCapabilityKey)?.let {
                return@withLock RequestModeProbeResult(mode = it)
            }
            DebugLogManager.logBase(
                category = "asr",
                event = "llm_request_mode_probe_started",
                data = mapOf("vendor" to config.vendor.id)
            )
            val probeStartedAt = elapsedRealtimeMs()
            val result = probeRequestMode(prefs, config, timeoutBudget, persistRequestMode)
            DebugLogManager.logBase(
                category = "asr",
                event = "llm_request_mode_probe_done",
                data = mapOf(
                    "vendor" to config.vendor.id,
                    "mode" to result.mode?.id,
                    "elapsed_ms" to (elapsedRealtimeMs() - probeStartedAt).coerceAtLeast(0L)
                )
            )
            result
        }
    }

    /**
     * 首次探测供应商支持的请求模式。探测本身是一次完整的 LLM 往返，
     * 调用方需保证它在锁内串行执行，并把耗时单独打点。
     */
    private suspend fun probeRequestMode(
        prefs: Prefs,
        config: LlmRequestConfig,
        timeoutBudget: LlmPostprocessTimeouts.Budget?,
        persistRequestMode: Boolean = true
    ): RequestModeProbeResult {
        val probeMessages = JSONArray().put(
            JSONObject()
                .put("role", "user")
                .put("content", "Reply with OK only.")
        )
        val probeConfig = config.copy(enableReasoning = false)
        val streamingProbe = performChatInternal(
            probeConfig,
            probeMessages,
            streaming = true,
            timeoutBudget = timeoutBudget
        )
        if (streamingProbe.ok) {
            val mode = if (streamingProbe.responseMode == LlmResponseMode.SSE) {
                Prefs.LlmRequestMode.STREAMING
            } else {
                Prefs.LlmRequestMode.NON_STREAMING
            }
            cacheRequestMode(prefs, config, mode, persistRequestMode)
            return RequestModeProbeResult(mode = mode)
        }
        if (!shouldRetryWithoutStream(streamingProbe)) {
            logRequestModeProbeFailed(config, "streaming", streamingProbe)
            return RequestModeProbeResult(failure = streamingProbe)
        }

        val nonStreamingProbe = performChatInternal(
            probeConfig,
            probeMessages,
            streaming = false,
            timeoutBudget = timeoutBudget
        )
        if (!nonStreamingProbe.ok) {
            logRequestModeProbeFailed(config, "non_streaming", nonStreamingProbe)
            return RequestModeProbeResult(failure = nonStreamingProbe)
        }
        cacheRequestMode(prefs, config, Prefs.LlmRequestMode.NON_STREAMING, persistRequestMode)
        return RequestModeProbeResult(mode = Prefs.LlmRequestMode.NON_STREAMING)
    }

    private fun getCachedRequestMode(prefs: Prefs, capabilityKey: String): Prefs.LlmRequestMode? = prefs.getLlmRequestMode(capabilityKey) ?: transientRequestModes[capabilityKey]

    private fun cacheRequestMode(
        prefs: Prefs,
        config: LlmRequestConfig,
        mode: Prefs.LlmRequestMode,
        persist: Boolean
    ) {
        if (persist) {
            saveRequestMode(prefs, config, mode)
        } else {
            transientRequestModes[config.requestModeCapabilityKey] = mode
        }
    }

    private fun saveRequestMode(
        prefs: Prefs,
        config: LlmRequestConfig,
        mode: Prefs.LlmRequestMode
    ) {
        prefs.setLlmRequestMode(config.requestModeCapabilityKey, mode)
        DebugLogManager.logBase(
            category = "asr",
            event = "llm_request_mode_saved",
            data = mapOf(
                "vendor" to config.vendor.id,
                "mode" to mode.id
            )
        )
    }

    private fun logRequestModeProbeFailed(
        config: LlmRequestConfig,
        stage: String,
        result: RawCallResult
    ) {
        DebugLogManager.logWarning(
            category = "asr",
            event = "llm_request_mode_probe_failed",
            data = mapOf(
                "vendor" to config.vendor.id,
                "stage" to stage,
                "httpCode" to result.httpCode
            )
        )
    }

    private fun shouldRetryWithoutStream(result: RawCallResult): Boolean {
        if (result.httpCode in listOf(400, 404, 405, 406, 415, 422)) return true
        val error = result.error?.lowercase().orEmpty()
        return error.contains("streaming is not supported") ||
            error.contains("stream is not supported") ||
            error.contains("unsupported stream") ||
            error.contains("sse is not supported") ||
            error.contains("unsupported sse")
    }

    private fun performChatInternal(
        config: LlmRequestConfig,
        messages: JSONArray,
        streaming: Boolean,
        onStreamingUpdate: ((String) -> Unit)? = null,
        timeoutBudget: LlmPostprocessTimeouts.Budget? = null
    ): RawCallResult {
        if (cancelRequested) {
            return RawCallResult(false, error = "Request canceled")
        }
        val req = try {
            buildRequest(config, messages, streaming = streaming)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to build request", t)
            return RawCallResult(false, error = "Build request failed: ${t.message}")
        }

        val timing = LlmHttpEventTiming()
        val http = getHttpClient().newBuilder().eventListener(timing).build()
        val call = http.newCall(req)
        activeCall = call
        if (cancelRequested) {
            call.cancel()
            if (activeCall === call) {
                activeCall = null
            }
            return RawCallResult(false, error = "Request canceled")
        }
        val t0 = elapsedRealtimeMs()
        val startedAtNs = System.nanoTime()
        val firstTokenDeadlineNs = timeoutBudget?.let {
            it.callDeadlineNs(startedAtNs, it.firstTokenMs)
        }
        if (timeoutBudget != null) {
            applyAbsoluteDeadline(
                call.timeout(),
                timeoutBudget.callDeadlineNs(startedAtNs, timeoutBudget.combinedMs)
            )
        }
        val resp = try {
            call.execute()
        } catch (t: Throwable) {
            if (activeCall === call) {
                activeCall = null
            }
            if (cancelRequested) {
                return RawCallResult(false, error = "Request canceled")
            }
            if (timeoutBudget != null && isTimeoutThrowable(t)) {
                logLlmTimeout("first_token", timeoutBudget, streaming)
                return RawCallResult(false, error = "timeout:first_token")
            }
            Log.e(TAG, "HTTP request failed", t)
            return RawCallResult(false, error = t.message ?: "Network error")
        }
        val tHeaders = elapsedRealtimeMs()
        val responseHeadersMs = (tHeaders - t0).coerceAtLeast(0L)

        if (!resp.isSuccessful) {
            val code = resp.code
            val err = try {
                resp.body.string()
            } catch (_: Throwable) {
                null
            } finally {
                resp.close()
            }
            if (activeCall === call) {
                activeCall = null
            }
            return RawCallResult(false, httpCode = code, error = err?.take(256) ?: "HTTP $code")
        }

        var responseMode = LlmResponseMode.NON_SSE
        var firstVisibleMs = 0L
        var outputMs = 0L
        var responseBodyMs = 0L
        var responseRecyclingScheduled = false
        val text = try {
            val body = resp.body

            val bodyReadStartedAt = elapsedRealtimeMs()
            val source = body.source()
            val contentType =
                resp.header("Content-Type") ?: body.contentType()?.toString().orEmpty()
            val declaredSse = contentType.contains("text/event-stream", ignoreCase = true)
            val responseModeDetected = if (streaming && declaredSse) {
                LlmResponseMode.SSE
            } else if (streaming) {
                if (timeoutBudget != null && firstTokenDeadlineNs != null) {
                    applyAbsoluteDeadline(source.timeout(), firstTokenDeadlineNs)
                }
                try {
                    detectStreamingResponseMode(source)
                } catch (t: Throwable) {
                    if (timeoutBudget != null && isTimeoutThrowable(t)) {
                        throw LlmPostprocessTimeoutException("first_token")
                    }
                    throw t
                }
            } else {
                LlmResponseMode.NON_SSE
            }
            val isEventStream = responseModeDetected == LlmResponseMode.SSE
            if (streaming) {
                DebugLogManager.logBase(
                    category = "asr",
                    event = "llm_response_mode_detected",
                    data = mapOf(
                        "mode" to responseModeDetected.name.lowercase(),
                        "declaredSse" to
                            declaredSse
                    )
                )
            }

            val parsed = if (isEventStream) {
                responseMode = LlmResponseMode.SSE
                val stream = parseStreamingResponse(
                    source,
                    timeoutBudget = timeoutBudget,
                    firstTokenDeadlineNs = firstTokenDeadlineNs,
                    onStreamingUpdate = onStreamingUpdate
                )
                val protocolDoneAt = elapsedRealtimeMs()
                val firstAt = stream.firstVisibleAtElapsed
                if (firstAt > 0L) {
                    firstVisibleMs = (firstAt - tHeaders).coerceAtLeast(0L)
                    outputMs = (protocolDoneAt - firstAt).coerceAtLeast(0L)
                } else {
                    firstVisibleMs = (protocolDoneAt - tHeaders).coerceAtLeast(0L)
                }
                if (stream.protocolCompleted && !cancelRequested) {
                    responseRecyclingScheduled = recycleSseBodyAsync(http, resp, source)
                }
                stream.text
            } else {
                if (timeoutBudget != null) {
                    applyAbsoluteDeadline(
                        source.timeout(),
                        timeoutBudget.callDeadlineNs(startedAtNs, timeoutBudget.combinedMs)
                    )
                }
                val respText = body.string()
                responseBodyMs =
                    (elapsedRealtimeMs() - bodyReadStartedAt).coerceAtLeast(0L)
                extractTextFromResponse(respText, fallback = "")
            }

            val filtered = filterThinkTags(parsed)
            if (filtered.isBlank()) {
                return RawCallResult(false, error = "Empty result")
            }
            filtered
        } catch (t: LlmPostprocessTimeoutException) {
            logLlmTimeout(t.reason, timeoutBudget, streaming)
            return RawCallResult(false, error = "timeout:${t.reason}")
        } catch (t: Throwable) {
            if (cancelRequested) {
                return RawCallResult(false, error = "Request canceled")
            }
            if (timeoutBudget != null && isTimeoutThrowable(t)) {
                logLlmTimeout("output", timeoutBudget, streaming)
                return RawCallResult(false, error = "timeout:output")
            }
            Log.e(
                TAG,
                "Failed to parse ${if (streaming) "streaming" else "non-streaming"} response",
                t
            )
            return RawCallResult(false, error = t.message ?: "Parse error")
        } finally {
            if (!responseRecyclingScheduled) {
                try {
                    resp.close()
                } catch (closeErr: Throwable) {
                    Log.w(TAG, "Close response failed", closeErr)
                }
            }
            if (activeCall === call) {
                activeCall = null
            }
        }

        return RawCallResult(
            ok = true,
            text = text,
            responseMode = responseMode,
            connectionMs = timing.connectionMs,
            responseHeadersMs = responseHeadersMs,
            firstVisibleMs = firstVisibleMs,
            outputMs = outputMs,
            responseBodyMs = responseBodyMs,
            connectionReused = timing.connectionReused,
            fallbackUsed = false
        )
    }

    /**
     * 取消当前进行中的 LLM 请求。
     */
    fun cancelActiveRequest() {
        cancelRequested = true
        val call = activeCall
        if (call == null) return
        try {
            call.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "Cancel active request failed", t)
        }
    }

    /**
     * 带一次自动重试的调用。
     */
    private suspend fun performChatWithRetry(
        prefs: Prefs,
        config: LlmRequestConfig,
        messages: JSONArray,
        maxRetry: Int = 1,
        onStreamingUpdate: ((String) -> Unit)? = null,
        timeoutBudget: LlmPostprocessTimeouts.Budget? = null,
        persistRequestMode: Boolean = true
    ): RawCallResult {
        val probe = ensureRequestMode(prefs, config, timeoutBudget, persistRequestMode)
        if (probe.mode == null) {
            return probe.failure ?: RawCallResult(false, error = "Request mode probe failed")
        }
        var attempt = 0
        var retryModeOverride: Prefs.LlmRequestMode? = null
        var last: RawCallResult
        while (true) {
            if (cancelRequested) {
                return RawCallResult(false, error = "Request canceled")
            }
            attempt++
            val requestMode = retryModeOverride
                ?: getCachedRequestMode(prefs, config.requestModeCapabilityKey)
                ?: probe.mode
            last = performChat(
                prefs,
                config,
                messages,
                requestMode = requestMode,
                onStreamingUpdate = onStreamingUpdate,
                timeoutBudget = timeoutBudget,
                persistRequestMode = persistRequestMode
            )
            if (last.ok) return last
            if (cancelRequested) return last.copy(error = last.error ?: "Request canceled")
            if (isNonRetryableFailure(last)) return last
            if (attempt > maxRetry) return last
            if (last.fallbackUsed) {
                retryModeOverride = Prefs.LlmRequestMode.NON_STREAMING
            }
            Log.w(
                TAG,
                "performChat failed (attempt=$attempt), will retry once: ${last.httpCode ?: ""} ${last.error ?: ""}"
            )
            try {
                kotlinx.coroutines.delay(350)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                Log.w(TAG, "Retry delay interrupted", t)
            }
        }
    }

    /**
     * 录音开始时的请求模式预跑。
     *
     * 首次对某个 vendor+endpoint 调用 LLM 时，正式请求前会先发一次探测请求确认是否支持 SSE，
     * 这笔完整往返原本落在「停录后等润色」的窗口里。这里把它挪到用户说话期间完成。
     * 已探测过则直接返回 false，不产生任何网络请求。
     */
    internal suspend fun prewarmRequestMode(prefs: Prefs): Boolean = withContext(Dispatchers.IO) {
        prewarmRequestModeInternal(prefs, getActiveConfig(prefs))
    }

    /**
     * 用指定（已解析）模型配置预热请求模式。
     *
     * 自动选择的分类模型可能与润色模型不同，需要各自探测一次，避免停录后的分类窗口里
     * 额外插入一次探测往返。
     */
    internal suspend fun prewarmRequestMode(
        prefs: Prefs,
        resolved: ResolvedLlmModelConfig
    ): Boolean = withContext(Dispatchers.IO) {
        prewarmRequestModeInternal(prefs, toRequestConfig(prefs, resolved, ""))
    }

    private suspend fun prewarmRequestModeInternal(
        prefs: Prefs,
        config: LlmRequestConfig
    ): Boolean {
        if (config.endpoint.isBlank()) return false
        if (config.vendor != LlmVendor.CUSTOM && config.model.isBlank()) return false
        if (prefs.getLlmRequestMode(config.requestModeCapabilityKey) != null) return false
        val budget = LlmPostprocessTimeouts.budget(
            reasoningEnabled = false,
            inputCharCount = 0
        )
        return ensureRequestMode(prefs, config, budget).mode != null
    }

    /**
     * 用指定模型配置发起一次完整的 Chat Completions 调用。
     *
     * 供自动选择（PromptSelector）复用：与常规润色共享同一套温度/推理/自定义参数/已探测请求模式。
     *
     * @param totalTimeoutMs 整个逻辑请求（含请求模式探测与重试）的硬超时；null 时按输入长度推导两档预算。
     */
    internal suspend fun processWithResolvedConfig(
        prefs: Prefs,
        resolved: ResolvedLlmModelConfig,
        systemPrompt: String,
        userContent: String,
        totalTimeoutMs: Long? = null,
        persistRequestMode: Boolean = true
    ): LlmProcessResult = withContext(Dispatchers.IO) {
        cancelRequested = false
        val config = toRequestConfig(prefs, resolved, userContent)
        val messages = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                }
            )
            put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", userContent)
                }
            )
        }
        val t0 = System.nanoTime()
        val budget = if (totalTimeoutMs != null) {
            LlmPostprocessTimeouts.totalBudget(
                totalMs = totalTimeoutMs,
                reasoningEnabled = config.enableReasoning,
                inputCharCount = userContent.length,
                nowNs = t0
            )
        } else {
            LlmPostprocessTimeouts.budget(
                reasoningEnabled = config.enableReasoning,
                inputCharCount = userContent.length
            )
        }
        val result = performChatWithRetry(
            prefs,
            config,
            messages,
            onStreamingUpdate = null,
            timeoutBudget = budget,
            persistRequestMode = persistRequestMode
        )
        val dt = TimeUnit.NANOSECONDS
            .toMillis((System.nanoTime() - t0).coerceAtLeast(0L))
            .coerceAtLeast(0L)
        logLlmCallComplete(result, config, dt)
        if (!result.ok) {
            return@withContext LlmProcessResult(
                false,
                text = "",
                errorMessage = result.error,
                httpCode = result.httpCode,
                usedAi = false,
                attempted = true,
                llmMs = dt,
                llmVendorId = config.vendor.id
            )
        }
        LlmProcessResult(
            true,
            text = result.text.orEmpty(),
            usedAi = true,
            attempted = true,
            llmMs = dt,
            llmVendorId = config.vendor.id
        )
    }

    /**
     * 测试 LLM 调用是否可用：发送贴近后处理场景的简易润色 Prompt（中英两段），看是否有返回内容。
     * 输出约 60 token 且受原文长度约束，能同时反映连接/首包延迟与生成速度；
     * 首次测试会先独立探测并保存供应商请求模式，探测耗时不计入正式测速结果。
     */
    suspend fun testConnectivity(prefs: Prefs): LlmTestResult = withContext(Dispatchers.IO) {
        // 基础必填校验（endpoint / model）
        val active = getActiveConfig(prefs, CONNECTIVITY_TEST_PROMPT)
        val requiresModel = active.vendor != LlmVendor.CUSTOM
        if (active.endpoint.isBlank() || (requiresModel && active.model.isBlank())) {
            val message = if (active.endpoint.isBlank()) "Missing endpoint" else "Missing model"
            return@withContext LlmTestResult(
                ok = false,
                message = message
            )
        }

        val messages = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("role", "user")
                    put(
                        "content",
                        CONNECTIVITY_TEST_PROMPT
                    )
                }
            )
        }

        val timeoutBudget = LlmPostprocessTimeouts.connectivityBudget(
            reasoningEnabled = active.enableReasoning
        )
        val probe = ensureRequestMode(prefs, active, timeoutBudget)
        val requestMode = probe.mode
        if (requestMode == null) {
            val failure = probe.failure
            return@withContext LlmTestResult(
                false,
                httpCode = failure?.httpCode,
                message = failure?.error ?: "Request mode probe failed"
            )
        }
        val result = performChat(
            prefs,
            active,
            messages,
            requestMode = requestMode,
            timeoutBudget = timeoutBudget
        )
        if (result.ok) {
            return@withContext LlmTestResult(
                ok = true,
                contentPreview = result.text?.take(120),
                responseMode = result.responseMode,
                totalMs = result.totalMs,
                connectionMs = result.connectionMs,
                responseHeadersMs = result.responseHeadersMs,
                firstVisibleMs = result.firstVisibleMs,
                outputMs = result.outputMs,
                responseBodyMs = result.responseBodyMs,
                connectionReused = result.connectionReused,
                fallbackUsed = result.fallbackUsed
            )
        } else {
            return@withContext LlmTestResult(
                false,
                httpCode = result.httpCode,
                message = result.error
            )
        }
    }

    /**
     * 拉取 OpenAI 标准 /models 列表
     */
    suspend fun fetchModels(endpoint: String, apiKey: String): LlmModelsResult = withContext(Dispatchers.IO) {
        val url = try {
            resolveModelsUrl(endpoint)
        } catch (t: Throwable) {
            Log.e(TAG, "Resolve /models url failed", t)
            return@withContext LlmModelsResult(false, message = t.message ?: "Invalid endpoint")
        }

        val reqBuilder = Request.Builder()
            .url(url)
            .tag(
                ApiLogMeta::class.java,
                ApiLogRecorder.meta(
                    category = "LLM",
                    vendor = "custom",
                    source = "settings_test",
                    requestStructure = "GET /models"
                )
            )
            .get()
            .addHeader("Content-Type", "application/json")

        if (apiKey.isNotBlank()) {
            reqBuilder.addHeader("Authorization", "Bearer $apiKey")
        }

        val resp = try {
            getModelsHttpClient().newCall(reqBuilder.build()).execute()
        } catch (t: Throwable) {
            Log.e(TAG, "Fetch /models failed", t)
            return@withContext LlmModelsResult(false, message = t.message ?: "Network error")
        }

        val code = resp.code
        val isSuccessful = resp.isSuccessful
        val rawBody = try {
            resp.body.string().orEmpty()
        } catch (t: Throwable) {
            Log.w(TAG, "Read /models response failed", t)
            ""
        } finally {
            try {
                resp.close()
            } catch (closeErr: Throwable) {
                Log.w(TAG, "Close /models response failed", closeErr)
            }
        }

        if (!isSuccessful) {
            val msg = rawBody.take(256).ifBlank { "HTTP $code" }
            return@withContext LlmModelsResult(false, httpCode = code, message = msg)
        }

        val models = try {
            parseModelsFromResponse(rawBody)
        } catch (t: Throwable) {
            Log.e(TAG, "Parse /models response failed", t)
            return@withContext LlmModelsResult(
                false,
                httpCode = code,
                message =
                t.message ?: "Parse error"
            )
        }

        if (models.isEmpty()) {
            return@withContext LlmModelsResult(
                false,
                httpCode = code,
                message = "No models found"
            )
        }

        return@withContext LlmModelsResult(true, models = models.distinct())
    }

    /**
     * 与 process 等价，但返回是否成功及错误信息，便于 UI 反馈。
     *
     * 用户选择的 prompt 直接作为完整的 system prompt 使用，
     * 待处理的文本统一放在 user prompt 中，使用简洁的包装格式。
     */
    suspend fun processWithStatus(
        input: String,
        prefs: Prefs,
        promptOverride: String? = null,
        onStreamingUpdate: ((String) -> Unit)? = null
    ): LlmProcessResult = withContext(Dispatchers.IO) {
        cancelRequested = false
        if (input.isBlank()) {
            Log.d(TAG, "Input is blank, skipping processing")
            return@withContext LlmProcessResult(
                ok = true,
                text = input,
                usedAi = false,
                attempted = false,
                llmMs = 0
            )
        }

        val config = getActiveConfig(prefs, input)
        val systemPrompt = (promptOverride ?: prefs.activePromptContent)
        val userInputPrefix = prefs.getLocalizedString(R.string.llm_prompt_user_input_prefix)
        val userContent = "$userInputPrefix$input"

        val messages = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                }
            )
            put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", userContent)
                }
            )
        }

        val t0 = System.nanoTime()
        val timeoutBudget = LlmPostprocessTimeouts.budget(
            reasoningEnabled = config.enableReasoning,
            inputCharCount = input.length
        )
        val result = performChatWithRetry(
            prefs,
            config,
            messages,
            onStreamingUpdate = onStreamingUpdate,
            timeoutBudget = timeoutBudget
        )
        val dt = TimeUnit.NANOSECONDS
            .toMillis((System.nanoTime() - t0).coerceAtLeast(0L))
            .coerceAtLeast(0L)
        logLlmCallComplete(result, config, dt)

        if (!result.ok) {
            if (result.httpCode != null) {
                Log.w(TAG, "LLM process() failed: HTTP ${result.httpCode}, ${result.error}")
            } else {
                Log.w(TAG, "LLM process() failed: ${result.error}")
            }
            return@withContext LlmProcessResult(
                false,
                text = input,
                errorMessage = result.error,
                httpCode = result.httpCode,
                usedAi = false,
                attempted = true,
                llmMs = dt,
                llmVendorId = config.vendor.id
            )
        }

        val text = result.text ?: input
        Log.d(TAG, "Text processing completed, output length: ${text.length}")
        return@withContext LlmProcessResult(
            true,
            text = text,
            usedAi = true,
            attempted = true,
            llmMs = dt,
            llmVendorId = config.vendor.id
        )
    }

    /**
     * 与 editText 等价，但返回是否成功及错误信息，便于 UI 反馈。
     */
    suspend fun editTextWithStatus(
        original: String,
        instruction: String,
        prefs: Prefs
    ): LlmProcessResult = withContext(Dispatchers.IO) {
        cancelRequested = false
        if (original.isBlank() || instruction.isBlank()) {
            Log.d(TAG, "Original or instruction is blank, skipping edit")
            return@withContext LlmProcessResult(
                true,
                text = original,
                usedAi = false,
                attempted = false,
                llmMs = 0
            )
        }

        val config = getActiveConfig(prefs, original)

        val systemPrompt = prefs.getEffectiveAiEditSystemPrompt()
        val instructionLabel = prefs.getLocalizedString(R.string.llm_edit_instruction_label)
        val originalLabel = prefs.getLocalizedString(R.string.llm_edit_original_label)

        val userContent = """
      $instructionLabel
      $instruction

      $originalLabel
      $original
        """.trimIndent()

        val messages = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                }
            )
            put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", userContent)
                }
            )
        }

        val t0 = System.nanoTime()
        val timeoutBudget = LlmPostprocessTimeouts.budget(
            reasoningEnabled = config.enableReasoning,
            inputCharCount = original.length
        )
        val result = performChatWithRetry(
            prefs,
            config,
            messages,
            timeoutBudget = timeoutBudget
        )
        val dt = TimeUnit.NANOSECONDS
            .toMillis((System.nanoTime() - t0).coerceAtLeast(0L))
            .coerceAtLeast(0L)
        logLlmCallComplete(result, config, dt)
        if (!result.ok) {
            if (result.httpCode != null) {
                Log.w(TAG, "LLM editText() failed: HTTP ${result.httpCode}, ${result.error}")
            } else {
                Log.w(TAG, "LLM editText() failed: ${result.error}")
            }
            return@withContext LlmProcessResult(
                false,
                text = original,
                errorMessage = result.error,
                httpCode = result.httpCode,
                usedAi = false,
                attempted = true,
                llmMs = dt
            )
        }

        val out = result.text ?: original

        Log.d(TAG, "Text editing completed, output length: ${out.length}")
        return@withContext LlmProcessResult(
            true,
            text = out,
            usedAi = true,
            attempted = true,
            llmMs = dt
        )
    }

    private class LlmPostprocessTimeoutException(val reason: String) : IOException("timeout:$reason")

    private fun applyAbsoluteDeadline(timeout: okio.Timeout, deadlineNs: Long) {
        timeout.clearTimeout()
        timeout.deadlineNanoTime(deadlineNs)
    }

    private fun isTimeoutThrowable(t: Throwable): Boolean {
        if (t is LlmPostprocessTimeoutException) return true
        var cur: Throwable? = t
        while (cur != null) {
            if (cur is InterruptedIOException || cur is java.net.SocketTimeoutException) {
                return true
            }
            val msg = cur.message.orEmpty().lowercase()
            if (msg.contains("timeout") || msg.contains("deadline")) return true
            cur = cur.cause
        }
        return false
    }

    private fun isNonRetryableFailure(result: RawCallResult): Boolean {
        val err = result.error ?: return false
        return err.startsWith("timeout:") || err == "Request canceled"
    }

    private fun logLlmCallComplete(
        result: RawCallResult,
        config: LlmRequestConfig,
        wallMs: Long
    ) {
        try {
            DebugLogManager.log(
                category = "asr",
                event = "llm_call_complete",
                data = mapOf(
                    "ok" to result.ok,
                    "mode" to result.responseMode?.name?.lowercase(),
                    "reasoning" to config.enableReasoning,
                    "threshold" to config.reasoningCharThreshold,
                    // wallMs 覆盖请求模式探测 + 重试等待 + 最终调用；与 totalMs 的差值即这些额外开销。
                    "wallMs" to wallMs,
                    "totalMs" to result.totalMs,
                    "reused" to result.connectionReused,
                    "connectionMs" to result.connectionMs,
                    "headersMs" to result.responseHeadersMs,
                    "firstVisibleMs" to result.firstVisibleMs,
                    "outputMs" to result.outputMs,
                    "bodyMs" to result.responseBodyMs,
                    "fallback" to result.fallbackUsed
                )
            )
        } catch (_: Throwable) { }
    }

    private fun logLlmTimeout(
        reason: String,
        budget: LlmPostprocessTimeouts.Budget?,
        streaming: Boolean
    ) {
        Log.w(TAG, "LLM postprocess timeout: reason=$reason")
        try {
            DebugLogManager.logBase(
                category = "asr",
                event = "llm_timeout",
                data = mapOf(
                    "reason" to reason,
                    "reasoning" to (budget?.reasoningEnabled ?: false),
                    "charCount" to (budget?.charCount ?: 0),
                    "firstTokenMs" to (budget?.firstTokenMs ?: 0),
                    "outputMs" to (budget?.outputMs ?: 0),
                    "stream" to streaming
                )
            )
        } catch (_: Throwable) { }
    }
}
