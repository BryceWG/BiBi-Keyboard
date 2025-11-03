package com.brycewg.asrkb.asr

import android.content.Context
import android.util.Log
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult
import com.alibaba.dashscope.common.MultiModalMessage
import com.alibaba.dashscope.common.Role
import com.alibaba.dashscope.exception.ApiException
import com.alibaba.dashscope.exception.NoApiKeyException
import com.alibaba.dashscope.exception.UploadFileException
import com.alibaba.dashscope.utils.Constants
import com.alibaba.dashscope.utils.JsonUtils
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * 使用阿里云百炼（DashScope）Java SDK 直传本地文件的非流式 ASR 引擎。
 * - 通过 MultiModalConversation 直接传入 file:// 路径，SDK 负责上传与调用，减少往返延迟。
 */
class DashscopeFileAsrEngine(
    context: Context,
    scope: CoroutineScope,
    prefs: Prefs,
    listener: StreamingAsrEngine.Listener,
    onRequestDuration: ((Long) -> Unit)? = null
) : BaseFileAsrEngine(context, scope, prefs, listener, onRequestDuration) {

    companion object {
        private const val TAG = "DashscopeFileAsrEngine"
    }

    // DashScope：官方限制 3 分钟
    override val maxRecordDurationMillis: Int = 3 * 60 * 1000

    override fun ensureReady(): Boolean {
        if (!super.ensureReady()) return false
        if (prefs.dashApiKey.isBlank()) {
            listener.onError(context.getString(R.string.error_missing_dashscope_key))
            return false
        }
        return true
    }

    override suspend fun recognize(pcm: ByteArray) {
        // 1) 将 PCM 写成临时 WAV 文件
        val tmp = try {
            val wav = pcmToWav(pcm)
            File.createTempFile("asr_dash_", ".wav", context.cacheDir).also { f ->
                FileOutputStream(f).use { it.write(wav) }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to materialize WAV file", e)
            listener.onError(
                context.getString(R.string.error_recognize_failed_with_reason, e.message ?: "")
            )
            return
        }

        try {
            // 2) 选择地域：默认使用中国大陆地域
            Constants.baseHttpApiUrl = "https://dashscope.aliyuncs.com/api/v1"

            // 3) 组装消息：用户音频 +（可选）系统提示词
            val audioPath = "file://" + tmp.absolutePath
            val userMessage = MultiModalMessage.builder()
                .role(Role.USER.getValue())
                .content(listOf(mapOf("audio" to audioPath)))
                .build()

            val basePrompt = prefs.dashPrompt.trim()
            val sysPrompt = try {
                // Pro 版可注入个性化上下文
                com.brycewg.asrkb.asr.ProAsrHelper.buildPromptWithContext(context, basePrompt)
            } catch (t: Throwable) {
                basePrompt
            }
            val systemMessage = MultiModalMessage.builder()
                .role(Role.SYSTEM.getValue())
                .content(listOf(mapOf("text" to sysPrompt)))
                .build()

            // 4) ASR 参数
            val asrOptions = HashMap<String, Any>(4).apply {
                put("enable_itn", true)
                val lang = prefs.dashLanguage.trim()
                if (lang.isNotEmpty()) put("language", lang)
            }

            // 5) 构建调用参数并请求
            val param = MultiModalConversationParam.builder()
                .apiKey(prefs.dashApiKey)
                .model(Prefs.DEFAULT_DASH_MODEL)
                // 顺序不敏感，此处与官方示例一致
                .message(userMessage)
                .message(systemMessage)
                .parameter("asr_options", asrOptions)
                .build()

            val conv = MultiModalConversation()
            val t0 = System.nanoTime()
            val result: MultiModalConversationResult = conv.call(param)

            // 6) 解析结果（沿用原有 JSON 解析逻辑）
            val json = try { JsonUtils.toJson(result) } catch (e: Throwable) { "" }
            val text = parseDashscopeText(json)
            if (text.isNotBlank()) {
                val dt = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
                try { onRequestDuration?.invoke(dt) } catch (e: Throwable) {
                    Log.w(TAG, "Failed to dispatch duration", e)
                }
                listener.onFinal(text)
            } else {
                listener.onError(context.getString(R.string.error_asr_empty_result))
            }
        }finally {
            tmp.delete()
        }
    }

    /**
     * 从 DashScope 响应体中解析转写文本
     */
    private fun parseDashscopeText(body: String): String {
        if (body.isBlank()) return ""
        return try {
            val obj = JSONObject(body)
            val output = obj.optJSONObject("output") ?: return ""
            val choices = output.optJSONArray("choices") ?: return ""
            if (choices.length() == 0) return ""
            val msg = choices.optJSONObject(0)?.optJSONObject("message") ?: return ""
            val content = msg.optJSONArray("content") ?: return ""
            var txt = ""
            for (i in 0 until content.length()) {
                val it = content.optJSONObject(i) ?: continue
                if (it.has("text")) {
                    txt = it.optString("text").trim()
                    if (txt.isNotEmpty()) break
                }
            }
            txt
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to parse DashScope response", t)
            ""
        }
    }

    /**
     * 获取文件名的安全方法
     */
    private fun File.nameIfExists(): String {
        return try { name } catch (t: Throwable) {
            Log.e(TAG, "Failed to get file name", t)
            "upload.wav"
        }
    }
}
