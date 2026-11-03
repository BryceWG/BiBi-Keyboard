package com.brycewg.asrkb.ui.history

import android.content.Context
import com.brycewg.asrkb.LocaleHelper
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrFailReasonCodes
import com.brycewg.asrkb.asr.AsrRecordedAudioRouteResolver

/**
 * 将重新识别 / 重新后处理的稳定错误码映射为用户可见文案。
 *
 * 归属模块：ui/history
 */
internal object AsrHistoryRerunErrorMessages {
    fun format(context: Context, code: String): String {
        val localized = LocaleHelper.wrap(context)
        if (code == AsrFailReasonCodes.EMPTY_AUDIO_SKIPPED) return localized.getString(R.string.error_audio_empty_skipped)
        return localized.getString(R.string.history_rerun_error, reasonText(localized, code))
    }

    private fun reasonText(context: Context, code: String): String = when (code) {
        "audio_unavailable" -> context.getString(R.string.history_audio_unavailable)
        "llm_unavailable" -> context.getString(R.string.history_llm_unavailable)
        "engine_unavailable", "engine_pcm_unsupported", "engine_not_ready" ->
            context.getString(R.string.history_rerun_engine_unavailable)
        "record_missing" -> context.getString(R.string.history_record_missing)
        AsrFailReasonCodes.EMPTY_RESULT ->
            context.getString(R.string.history_fail_reason_empty_result)
        AsrRecordedAudioRouteResolver.REASON_UNSUPPORTED_OPENAI_STREAMING ->
            context.getString(R.string.history_rerecognition_error_openai_streaming)
        AsrRecordedAudioRouteResolver.REASON_UNSUPPORTED_XASR ->
            context.getString(R.string.history_rerecognition_error_xasr)
        AsrRecordedAudioRouteResolver.REASON_UNSUPPORTED_UNKNOWN_MODEL ->
            context.getString(R.string.history_rerecognition_error_unknown_model)
        AsrRecordedAudioRouteResolver.REASON_UNSUPPORTED_NO_FILE_FALLBACK ->
            context.getString(R.string.history_rerecognition_error_no_file_fallback)
        AsrRecordedAudioRouteResolver.REASON_UNAVAILABLE_CREDENTIALS ->
            context.getString(R.string.history_rerecognition_error_unavailable_credentials)
        else -> if (code.startsWith("unsupported_") || code.startsWith("unavailable_")) {
            context.getString(R.string.history_rerun_engine_unavailable)
        } else {
            code
        }
    }
}
