package com.brycewg.asrkb.asr

import android.util.Log
import com.brycewg.asrkb.BuildConfig
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OpenAI 格式的 ASR 文本后处理器，用于文本清理和 AI 编辑。
 * 使用与 Chat Completions 兼容的 API，并在存在简单字段时回退使用。
 */
class LlmPostProcessor(private val client: OkHttpClient? = null) {
  private val jsonMedia = "application/json; charset=utf-8".toMediaType()

  /**
   * LLM 测试结果
   */
  data class LlmTestResult(
    val ok: Boolean,
    val httpCode: Int? = null,
    val message: String? = null,
    val contentPreview: String? = null
  )

  /**
   * 统一的底层调用结果
   */
  private data class RawCallResult(
    val ok: Boolean,
    val httpCode: Int? = null,
    val text: String? = null,
    val error: String? = null
  )

  /**
   * 标准化的上层处理结果，用于向调用方传递是否成功以及返回文本。
   */
  data class LlmProcessResult(
    val ok: Boolean,
    val text: String,
    val errorMessage: String? = null,
    val httpCode: Int? = null,
    // 表示本次结果是否“实际使用了 AI 输出”（调用成功并采用其文本）
    val usedAi: Boolean = false
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
    val supportsReasoningControl: Boolean
  )

  companion object {
    private const val TAG = "LlmPostProcessor"

    /** 连接超时（秒） */
    private const val CONNECT_TIMEOUT_SECONDS = 30L

    /** 首 token 超时（秒）- streaming 模式下等待首个数据块的最大时间 */
    private const val FIRST_TOKEN_TIMEOUT_SECONDS = 60L

    /**
     * 用于包装用户输入文本的前缀。
     * System prompt 应该已经独立完整，这个包装仅用于明确标识待处理内容。
     */
    private const val USER_INPUT_PREFIX = "待处理文本:\n"
  }

  private fun buildRequestConfig(
    apiKey: String,
    endpoint: String,
    model: String,
    temperature: Double,
    vendor: LlmVendor,
    enableReasoning: Boolean
  ): LlmRequestConfig {
    val supportsReasoning = vendor.supportsReasoningControl(model)
    return LlmRequestConfig(
      apiKey = apiKey,
      endpoint = endpoint,
      model = model,
      temperature = temperature,
      vendor = vendor,
      enableReasoning = enableReasoning,
      supportsReasoningControl = supportsReasoning
    )
  }

  /**
   * 从 Prefs 获取活动的 LLM 配置（使用新的供应商架构）
   */
  private fun getActiveConfig(prefs: Prefs): LlmRequestConfig {
    val vendor = prefs.llmVendor

    // SiliconFlow 免费服务特殊处理
    if (vendor == LlmVendor.SF_FREE && !prefs.sfFreeLlmUsePaidKey) {
      val model = prefs.sfFreeLlmModel
      return buildRequestConfig(
        apiKey = BuildConfig.SF_FREE_API_KEY,
        endpoint = Prefs.SF_CHAT_COMPLETIONS_ENDPOINT,
        model = model,
        temperature = Prefs.DEFAULT_LLM_TEMPERATURE.toDouble(),
        vendor = vendor,
        enableReasoning = prefs.getLlmVendorReasoningEnabled(vendor)
      )
    }

    // 使用统一的 getEffectiveLlmConfig
    val config = prefs.getEffectiveLlmConfig()
    if (config != null) {
      return buildRequestConfig(
        apiKey = config.apiKey,
        endpoint = config.endpoint,
        model = config.model,
        temperature = config.temperature.toDouble(),
        vendor = config.vendor,
        enableReasoning = config.enableReasoning
      )
    }

    // 回退到旧的逻辑（兼容性）
    val active = prefs.getActiveLlmProvider()
    val fallbackEndpoint = if (vendor.hasBuiltinEndpoint) vendor.endpoint else (active?.endpoint ?: prefs.llmEndpoint)
    return buildRequestConfig(
      apiKey = active?.apiKey ?: prefs.llmApiKey,
      endpoint = fallbackEndpoint,
      model = active?.model ?: prefs.llmModel,
      temperature = (active?.temperature ?: prefs.llmTemperature).toDouble(),
      vendor = vendor,
      enableReasoning = prefs.getLlmVendorReasoningEnabled(vendor)
    )
  }

  /**
   * 解析 URL，自动添加 /chat/completions 后缀
   */
  private fun resolveUrl(base: String): String {
    val raw = base.trim()
    if (raw.isEmpty()) return Prefs.DEFAULT_LLM_ENDPOINT.trimEnd('/') + "/chat/completions"
    val b = raw.trimEnd('/')
    // 要求用户填写完整 URL（包含 http/https），不再自动补全协议
    val hasScheme = b.startsWith("http://", true) || b.startsWith("https://", true)
    if (!hasScheme) throw IllegalArgumentException("Endpoint must start with http:// or https://")

    // 如果已直接指向 chat/completions 或 responses，则原样使用
    if (b.endsWith("/chat/completions")) return b

    // 其他情况：直接补全 /chat/completions
    return "$b/chat/completions"
  }

  /**
   * 根据供应商添加推理控制参数到请求体
   *
   * @param body 请求 JSON 对象
   * @param config LLM 配置
   */
  private fun addReasoningParams(body: JSONObject, config: LlmRequestConfig) {
    val vendor = config.vendor
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
        val effort = if (modelLower.contains("pro") || modelLower.startsWith("gemini-3")) "low" else "none"
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
      put("model", config.model)
      put("temperature", kotlin.math.round(config.temperature * 100) / 100)
      put("messages", messages)
      put("stream", streaming)

      // Add reasoning control parameters based on vendor
      addReasoningParams(this, config)
    }.toString()

    val body = reqJson.toRequestBody(jsonMedia)
    val builder = Request.Builder()
      .url(url)
      .addHeader("Content-Type", "application/json")
      .post(body)

    if (config.apiKey.isNotBlank()) {
      builder.addHeader("Authorization", "Bearer ${config.apiKey}")
    }

    return builder.build()
  }

  /**
   * 获取或创建 OkHttpClient
   *
   * 超时策略说明：
   * - connectTimeout: 建立连接的超时时间
   * - readTimeout: 等待首个数据块的超时时间（首 token 超时）
   * - writeTimeout: 写入请求体的超时时间
   * - 不设置 callTimeout: streaming 模式下总时长不受限制
   */
  private fun getHttpClient(): OkHttpClient {
    return client ?: OkHttpClient.Builder()
      .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      .readTimeout(0, TimeUnit.SECONDS)
      .writeTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      // 不设置 callTimeout，让 streaming 可以持续接收数据
      .build()
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

  /**
   * 从响应 JSON 中提取文本内容
   *
   * 支持标准 OpenAI 格式和自定义 output_text 字段
   *
   * @param responseJson 响应的 JSON 字符串
   * @param fallback 提取失败时的回退文本
   * @return 提取的文本或 fallback
   */
  private fun extractTextFromResponse(responseJson: String, fallback: String): String {
    return try {
      val obj = JSONObject(responseJson)
      val rawText = when {
        obj.has("choices") -> {
          val choices = obj.getJSONArray("choices")
          if (choices.length() > 0) {
            val msg = choices.getJSONObject(0).optJSONObject("message")
            msg?.optString("content")?.ifBlank { fallback } ?: fallback
          } else fallback
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
  }

  /**
   * 从 SSE 流中解析并拼接所有文本内容
   *
   * @param source 响应的 BufferedSource
   * @return 拼接后的完整文本
   */
  private fun parseStreamingResponse(source: BufferedSource): String {
    val contentBuilder = StringBuilder()
    val timeout = source.timeout()
    // 仅首个数据块启用超时，之后允许长间隔
    timeout.timeout(FIRST_TOKEN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    var waitingFirstEvent = true
    var shouldStop = false
    val eventBuilder = StringBuilder()

    fun flushEvent() {
      if (eventBuilder.isEmpty()) return
      val rawData = eventBuilder.toString().trim()
      eventBuilder.clear()

      if (waitingFirstEvent) {
        timeout.timeout(0, TimeUnit.MILLISECONDS)
        timeout.clearDeadline()
        waitingFirstEvent = false
      }

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
        if (delta != null) {
          when (val content = delta.opt("content")) {
            is String -> if (content.isNotEmpty()) contentBuilder.append(content)
            is JSONArray -> {
              for (i in 0 until content.length()) {
                when (val item = content.get(i)) {
                  is String -> if (item.isNotEmpty()) contentBuilder.append(item)
                  is JSONObject -> {
                    val textPart = item.optString("text")
                    if (textPart.isNotEmpty()) contentBuilder.append(textPart)
                  }
                }
              }
            }
          }
        }

        val finishReason = choice.optString("finish_reason", "")
        if (finishReason == "stop") {
          shouldStop = true
        }
      } catch (e: Exception) {
        Log.w(TAG, "Parse SSE chunk failed: $rawData", e)
      }
    }

    while (!source.exhausted() && !shouldStop) {
      val line = try {
        source.readUtf8Line() ?: break
      } catch (e: IOException) {
        Log.w(TAG, "Read line failed", e)
        break
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

    return contentBuilder.toString()
  }

  /**
   * 复用的底层 Chat 调用：构建请求、执行并解析文本。
   * 使用 streaming 模式，支持长时间等待和持续接收。
   * 需确保在非主线程调用。
   */
  private fun performChat(config: LlmRequestConfig, messages: JSONArray): RawCallResult {
    val streamingResult = performChatInternal(config, messages, streaming = true)
    if (streamingResult.ok) return streamingResult

    // 若服务端拒绝或不支持流式，尝试回退到非流模式
    val shouldRetryWithoutStream = streamingResult.httpCode in listOf(400, 404, 405, 415, 422) ||
      (streamingResult.error?.contains("stream", ignoreCase = true) == true) ||
      (streamingResult.error?.contains("sse", ignoreCase = true) == true)

    if (!shouldRetryWithoutStream) return streamingResult

    Log.w(TAG, "Streaming call failed (code=${streamingResult.httpCode}): ${streamingResult.error ?: ""}. Retrying without stream.")
    val fallback = performChatInternal(config, messages, streaming = false)
    if (fallback.ok) return fallback

    return fallback.copy(error = fallback.error ?: streamingResult.error)
  }

  private fun performChatInternal(
    config: LlmRequestConfig,
    messages: JSONArray,
    streaming: Boolean
  ): RawCallResult {
    val req = try {
      buildRequest(config, messages, streaming = streaming)
    } catch (t: Throwable) {
      Log.e(TAG, "Failed to build request", t)
      return RawCallResult(false, error = "Build request failed: ${t.message}")
    }

    val http = getHttpClient()
    val resp = try {
      http.newCall(req).execute()
    } catch (t: Throwable) {
      Log.e(TAG, "HTTP request failed", t)
      return RawCallResult(false, error = t.message ?: "Network error")
    }

    if (!resp.isSuccessful) {
      val code = resp.code
      val err = try { resp.body?.string() } catch (_: Throwable) { null } finally { resp.close() }
      return RawCallResult(false, httpCode = code, error = err?.take(256) ?: "HTTP $code")
    }

    val text = try {
      val body = resp.body ?: run {
        Log.w(TAG, "Response body is null")
        return RawCallResult(false, error = "Empty body")
      }

      val contentType = resp.header("Content-Type") ?: body.contentType()?.toString().orEmpty()
      val isEventStream = streaming && contentType.contains("text/event-stream", ignoreCase = true)

      val parsed = if (isEventStream) {
        parseStreamingResponse(body.source())
      } else {
        val respText = body.string()
        extractTextFromResponse(respText, fallback = "")
      }

      val filtered = filterThinkTags(parsed)
      if (filtered.isBlank()) {
        return RawCallResult(false, error = "Empty result")
      }
      filtered
    } catch (t: Throwable) {
      Log.e(TAG, "Failed to parse ${if (streaming) "streaming" else "non-streaming"} response", t)
      return RawCallResult(false, error = t.message ?: "Parse error")
    } finally {
      try {
        resp.close()
      } catch (closeErr: Throwable) {
        Log.w(TAG, "Close response failed", closeErr)
      }
    }

    return RawCallResult(true, text = text)
  }

  /**
   * 带一次自动重试的调用。
   */
  private suspend fun performChatWithRetry(
    config: LlmRequestConfig,
    messages: JSONArray,
    maxRetry: Int = 1
  ): RawCallResult {
    var attempt = 0
    var last: RawCallResult
    while (true) {
      attempt++
      last = performChat(config, messages)
      if (last.ok) return last
      if (attempt > maxRetry) return last
      Log.w(TAG, "performChat failed (attempt=$attempt), will retry once: ${last.httpCode ?: ""} ${last.error ?: ""}")
      try {
        kotlinx.coroutines.delay(350)
      } catch (t: Throwable) {
        Log.w(TAG, "Retry delay interrupted", t)
      }
    }
  }

  /**
   * 测试 LLM 调用是否可用：发送最简单 Prompt，看是否有返回内容。
   * 不改变任何业务状态，仅用于连通性自检/配置校验。
   */
  suspend fun testConnectivity(prefs: Prefs): LlmTestResult = withContext(Dispatchers.IO) {
    // 基础必填校验（endpoint / model）
    val active = getActiveConfig(prefs)
    if (active.endpoint.isBlank() || active.model.isBlank()) {
      return@withContext LlmTestResult(
        ok = false,
        message = "Missing endpoint or model"
      )
    }

    val messages = JSONArray().apply {
      put(JSONObject().apply {
        put("role", "user")
        put("content", "say `hi`")
      })
    }

    val result = performChat(active, messages)
    if (result.ok) {
      return@withContext LlmTestResult(true, contentPreview = result.text?.take(120))
    } else {
      return@withContext LlmTestResult(false, httpCode = result.httpCode, message = result.error)
    }
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
    promptOverride: String? = null
  ): LlmProcessResult = withContext(Dispatchers.IO) {
    if (input.isBlank()) {
      Log.d(TAG, "Input is blank, skipping processing")
      return@withContext LlmProcessResult(ok = true, text = input, usedAi = false)
    }

    val config = getActiveConfig(prefs)
    val systemPrompt = (promptOverride ?: prefs.activePromptContent)
    val userContent = "$USER_INPUT_PREFIX$input"

    val messages = JSONArray().apply {
      put(JSONObject().apply {
        put("role", "system")
        put("content", systemPrompt)
      })
      put(JSONObject().apply {
        put("role", "user")
        put("content", userContent)
      })
    }

    val result = performChatWithRetry(config, messages)
    if (!result.ok) {
      if (result.httpCode != null) {
        Log.w(TAG, "LLM process() failed: HTTP ${result.httpCode}, ${result.error}")
      } else {
        Log.w(TAG, "LLM process() failed: ${result.error}")
      }
      return@withContext LlmProcessResult(false, text = input, errorMessage = result.error, httpCode = result.httpCode, usedAi = false)
    }

    val text = result.text ?: input
    Log.d(TAG, "Text processing completed, output length: ${text.length}")
    return@withContext LlmProcessResult(true, text = text, usedAi = true)
  }

  /**
   * 与 editText 等价，但返回是否成功及错误信息，便于 UI 反馈。
   */
  suspend fun editTextWithStatus(original: String, instruction: String, prefs: Prefs): LlmProcessResult = withContext(Dispatchers.IO) {
    if (original.isBlank() || instruction.isBlank()) {
      Log.d(TAG, "Original or instruction is blank, skipping edit")
      return@withContext LlmProcessResult(true, text = original, usedAi = false)
    }

    val config = getActiveConfig(prefs)

    val systemPrompt = """
      你是一个精确的中文文本编辑助手。你的任务是根据"编辑指令"对"原文"进行最小必要修改。
      规则：
      - 只输出最终结果文本，不要输出任何解释、前后缀或引号。
      - 如指令含糊、矛盾或不可执行，原样返回原文。
      - 不要编造内容；除非指令明确要求，否则不要增删信息、不要改变语气与长度。
      - 保留原有段落、换行、空白与标点格式（除非指令要求变更）。
      - 保持语言/文字风格与原文一致；中文按原文简繁体维持不变。
      - 涉及脱敏时，仅将需脱敏片段替换为『[REDACTED]』，其余保持不变。
      - Output must be ONLY the edited text.

      示例（仅用于学习风格，不要照搬示例文本）：
      1) 指令：将口语化改为书面语；保留含义
         原文：我今天有点事儿，可能晚点到，你们先开始别等我
         输出：我今天有事，可能会晚到，请先开始，无需等待我。
      2) 指令：纠正错别字
         原文：这个方案挺好得，就是数据那块需要再核实一下
         输出：这个方案挺好的，就是数据那块需要再核实一下。
      3) 指令：把 comet 更改为 Kotlin
         原文：最近高强度写 comet,感觉效果还不错
         输出：最近高强度写 Kotlin,感觉效果还不错
      4) 指令：把列表换成逗号分隔的一行
         原文：苹果\n香蕉\n葡萄
         输出：苹果，香蕉，葡萄。
      5) 指令：脱敏姓名与电话
         原文：联系人张三，电话 13800000000
         输出：联系人[REDACTED]，电话[REDACTED]
    """.trimIndent()

    val userContent = """
      【编辑指令】
      $instruction

      【原文】
      $original
    """.trimIndent()

    val messages = JSONArray().apply {
      put(JSONObject().apply {
        put("role", "system")
        put("content", systemPrompt)
      })
      put(JSONObject().apply {
        put("role", "user")
        put("content", userContent)
      })
    }

    val result = performChatWithRetry(config, messages)
    if (!result.ok) {
      if (result.httpCode != null) {
        Log.w(TAG, "LLM editText() failed: HTTP ${result.httpCode}, ${result.error}")
      } else {
        Log.w(TAG, "LLM editText() failed: ${result.error}")
      }
      return@withContext LlmProcessResult(false, text = original, errorMessage = result.error, httpCode = result.httpCode, usedAi = false)
    }

    val out = result.text ?: original

    Log.d(TAG, "Text editing completed, output length: ${out.length}")
    return@withContext LlmProcessResult(true, text = out, usedAi = true)
  }
}
