/**
 * API Log Compose 页面格式化工具。
 *
 * 归属模块：ui/history/compose/apilog
 */
package com.brycewg.asrkb.ui.history.compose.apilog

import android.content.Context
import com.brycewg.asrkb.LocaleHelper
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.ApiLogStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun apiLogSearchableText(record: ApiLogStore.ApiLogRecord): String = listOf(
    record.category,
    record.vendor,
    record.model,
    record.source,
    record.protocol,
    record.method,
    record.host,
    record.path,
    record.queryKeys.joinToString(","),
    record.requestSummary,
    record.requestStructure,
    record.responseSummary,
    record.errorSummary
).joinToString(" ")

internal fun apiLogTitle(context: Context, record: ApiLogStore.ApiLogRecord): String {
    val status = when {
        record.canceled -> context.getString(R.string.api_log_status_cancelled)
        record.success -> context.getString(R.string.api_log_status_success)
        else -> context.getString(R.string.api_log_status_failed)
    }
    val vendor = formatApiLogVendorName(
        record.vendor.ifBlank { context.getString(R.string.api_log_unknown) }
    )
    return "$status · ${record.category} · $vendor"
}

internal fun apiLogMeta(context: Context, record: ApiLogStore.ApiLogRecord): String {
    val codePart = if (record.httpCode != 0) "code ${record.httpCode}" else record.protocol
    val modelPart = record.model.takeIf { it.isNotBlank() } ?: "-"
    return context.getString(
        R.string.api_log_meta_format,
        codePart,
        record.durationMs,
        modelPart
    )
}

internal fun apiLogTime(context: Context, record: ApiLogStore.ApiLogRecord): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", LocaleHelper.locale(context)).format(Date(record.timestamp))

fun formatApiLogDetail(context: Context, record: ApiLogStore.ApiLogRecord): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", LocaleHelper.locale(context))
    return buildString {
        val status = when {
            record.canceled -> "CANCELED"
            record.success -> "SUCCESS"
            else -> "FAILED"
        }
        appendLine("${record.category} $status")
        appendLine("time=${fmt.format(Date(record.timestamp))}")
        appendLine("protocol=${record.protocol}")
        appendLine("method=${record.method}")
        appendLine("target=${formatApiLogEndpoint(context, record)}")
        appendLine("queryKeys=${record.queryKeys}")
        appendLine("vendor=${formatApiLogVendorName(record.vendor)}")
        appendLine("model=${record.model}")
        appendLine("httpCode=${record.httpCode}")
        appendLine("durationMs=${record.durationMs}")
        appendLine("request=${record.requestSummary}")
        appendLine("structure=${record.requestStructure}")
        appendLine("response=${record.responseSummary}")
        if (record.errorSummary.isNotBlank()) appendLine("error=${record.errorSummary}")
    }
}

internal fun formatApiLogEndpoint(context: Context, record: ApiLogStore.ApiLogRecord): String {
    if (record.protocol.equals("Local", ignoreCase = true)) {
        val action = formatLocalAction(context, record.method)
        val source = formatLocalSource(context, record.source.ifBlank { record.path.ifBlank { record.vendor } })
        return context.getString(R.string.api_log_local_endpoint_format, action, source)
    }
    return "${record.method.ifBlank { record.protocol }} ${record.host}${record.path}"
}

private fun formatLocalAction(context: Context, action: String): String = when (action.lowercase(Locale.US)) {
    "infer" -> context.getString(R.string.api_log_local_action_infer)
    "load" -> context.getString(R.string.api_log_local_action_load)
    else -> action.ifBlank { context.getString(R.string.api_log_unknown) }
}

private fun formatLocalSource(context: Context, source: String): String = when (source.lowercase(Locale.US)) {
    "preload" -> context.getString(R.string.api_log_local_source_preload)
    "file" -> context.getString(R.string.api_log_local_source_file)
    "inference_load" -> context.getString(R.string.api_log_local_source_inference_load)
    "pseudo_stream_final" -> context.getString(R.string.api_log_local_source_pseudo_stream_final)
    "pseudo_stream_final_load" -> context.getString(R.string.api_log_local_source_pseudo_stream_final_load)
    "pseudo_stream_preview_load" -> context.getString(R.string.api_log_local_source_pseudo_stream_preview_load)
    "streaming_load" -> context.getString(R.string.api_log_local_source_streaming_load)
    "streaming" -> context.getString(R.string.api_log_local_source_streaming)
    "external_pcm_stream" -> context.getString(R.string.api_log_local_source_external_pcm_stream)
    else -> source.ifBlank { context.getString(R.string.api_log_unknown) }
}

internal fun formatApiLogVendorName(vendor: String): String = when (vendor.lowercase(Locale.US)) {
    "sf_free", "siliconflow_free" -> "SiliconFlow Free"
    "siliconflow" -> "SiliconFlow"
    "openai" -> "OpenAI"
    "openrouter" -> "OpenRouter"
    "dashscope" -> "DashScope"
    "gemini" -> "Gemini"
    "soniox" -> "Soniox"
    "stepaudio" -> "StepAudio"
    "zhipu" -> "Zhipu"
    "cohere" -> "Cohere"
    "volcengine" -> "Volcengine"
    "elevenlabs" -> "ElevenLabs"
    "deepseek" -> "DeepSeek"
    "moonshot" -> "Moonshot"
    "groq" -> "Groq"
    "cerebras" -> "Cerebras"
    "ohmygpt" -> "OhMyGPT"
    "requesty" -> "Requesty"
    "fireworks" -> "Fireworks"
    "custom" -> "Custom"
    else -> vendor
}
