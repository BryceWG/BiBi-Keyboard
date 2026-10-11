package com.brycewg.asrkb.asr

import android.content.Context
import com.brycewg.asrkb.R
import java.util.Locale

internal object AsrErrorMessageMapper {
    fun map(context: Context, raw: String): String? {
        if (raw.isEmpty()) return null
        if (raw == context.getString(R.string.error_audio_empty_skipped)) return raw

        return when (classify(context, raw)) {
            AsrFailReasonCodes.EMPTY_RESULT -> context.getString(R.string.asr_error_empty_result)
            AsrFailReasonCodes.AUTH_INVALID -> context.getString(R.string.asr_error_auth_invalid)
            AsrFailReasonCodes.AUTH_FORBIDDEN -> context.getString(R.string.asr_error_auth_forbidden)
            AsrFailReasonCodes.MIC_PERMISSION -> context.getString(R.string.asr_error_mic_permission_denied)
            AsrFailReasonCodes.MIC_IN_USE -> context.getString(R.string.asr_error_mic_in_use)
            AsrFailReasonCodes.NETWORK_HANDSHAKE -> context.getString(R.string.asr_error_network_handshake)
            AsrFailReasonCodes.NETWORK -> context.getString(R.string.asr_error_network_unavailable)
            AsrFailReasonCodes.TIMEOUT -> context.getString(R.string.error_asr_timeout)
            else -> null
        }
    }

    fun classify(context: Context, raw: String): String {
        if (raw.isEmpty()) return AsrFailReasonCodes.UNKNOWN
        val lower = raw.lowercase(Locale.ROOT)

        if (isEmptyResult(context, raw) || isEmptyAudio(context, raw)) {
            return AsrFailReasonCodes.EMPTY_RESULT
        }

        // Realtime / streaming：音频太短导致提交失败
        if (lower.contains("buffer too small") ||
            lower.contains("expected at least 100ms") ||
            (lower.contains("commit") && lower.contains("input audio buffer") && lower.contains("too small"))
        ) {
            return AsrFailReasonCodes.EMPTY_RESULT
        }

        // HTTP 状态码
        val httpCode = Regex("HTTP\\s+(\\d{3})").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
        when (httpCode) {
            401, 429 -> return AsrFailReasonCodes.AUTH_INVALID
            403 -> return AsrFailReasonCodes.AUTH_FORBIDDEN
        }

        // WebSocket code
        val code = Regex("(?:ASR\\s*Error|status|code)\\s*(\\d{3})")
            .find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
        when (code) {
            401 -> return AsrFailReasonCodes.AUTH_INVALID
            403 -> return AsrFailReasonCodes.AUTH_FORBIDDEN
        }

        // 录音权限
        val permHints = listOf(
            context.getString(R.string.error_record_permission_denied),
            context.getString(R.string.hint_need_permission),
            "record audio permission"
        )
        if (containsAny(lower, permHints)) {
            return AsrFailReasonCodes.MIC_PERMISSION
        }

        // 麦克风被占用
        val micBusyHints = listOf(
            context.getString(R.string.error_audio_init_failed),
            "audio recorder busy",
            "resource busy",
            "in use",
            "device busy"
        )
        if (containsAny(lower, micBusyHints)) {
            return AsrFailReasonCodes.MIC_IN_USE
        }

        // SSL/TLS 握手失败
        if (lower.contains("handshake") ||
            lower.contains("sslhandshakeexception") ||
            lower.contains("trust anchor") ||
            lower.contains("certificate")
        ) {
            return AsrFailReasonCodes.NETWORK_HANDSHAKE
        }

        // 识别超时（须在网络兜底之前，避免英文 timed out 被当成 NETWORK）
        val timeoutHints = listOf(
            context.getString(R.string.error_asr_timeout),
            "recognition timed out",
            "asr timeout"
        )
        if (containsAny(lower, timeoutHints)) {
            return AsrFailReasonCodes.TIMEOUT
        }

        // 网络不可用（含连接/读超时）
        if (lower.contains("unable to resolve host") ||
            lower.contains("no address associated") ||
            lower.contains("failed to connect") ||
            lower.contains("connect exception") ||
            lower.contains("network is unreachable") ||
            lower.contains("software caused connection abort") ||
            lower.contains("timeout") ||
            lower.contains("timed out")
        ) {
            return AsrFailReasonCodes.NETWORK
        }

        return AsrFailReasonCodes.UNKNOWN
    }

    fun isEmptyResult(context: Context, raw: String): Boolean {
        val lower = raw.lowercase(Locale.ROOT)
        return containsAny(
            lower,
            listOf(
                context.getString(R.string.error_asr_empty_result),
                context.getString(R.string.asr_error_empty_result)
            ) + emptyResultSignals
        ) ||
            containsNoMatchSignal(lower)
    }

    /**
     * 供应商常把空白识别写成 HTTP/业务错误。这些片段与厂商无关，
     * 备用切换也要先认出来，避免静音被当成主引擎故障。
     */
    fun containsEmptyRecognitionSignal(raw: String): Boolean {
        if (raw.isEmpty()) return false
        val lower = raw.lowercase(Locale.ROOT)
        return containsAny(lower, emptyResultSignals) ||
            containsAny(lower, emptyAudioSignals) ||
            containsNoMatchSignal(lower)
    }

    private fun isEmptyAudio(context: Context, raw: String): Boolean {
        val lower = raw.lowercase(Locale.ROOT)
        return containsAny(
            lower,
            listOf(context.getString(R.string.error_audio_empty)) + emptyAudioSignals
        )
    }

    private fun containsAny(lower: String, hints: List<String>): Boolean = hints.any { hint ->
        hint.isNotBlank() && lower.contains(hint.lowercase(Locale.ROOT))
    }

    /** "no match" 算空结果，但 "no matching …" 是别的失败，不能算进去。 */
    private fun containsNoMatchSignal(lower: String): Boolean {
        if (lower.contains("nomatch")) return true
        return containsPhraseNotFollowedBy(lower, "no match", "ing") ||
            containsPhraseNotFollowedBy(lower, "no_match", "ing") ||
            containsPhraseNotFollowedBy(lower, "no-match", "ing")
    }

    private fun containsPhraseNotFollowedBy(lower: String, phrase: String, suffix: String): Boolean {
        var from = 0
        while (true) {
            val index = lower.indexOf(phrase, from)
            if (index < 0) return false
            val after = index + phrase.length
            if (!lower.startsWith(suffix, after)) return true
            from = after
        }
    }

    private val emptyResultSignals = listOf(
        "empty asr result",
        "empty asr",
        "empty transcript",
        "empty transcription",
        "recognition result is empty",
        "result is empty",
        "no speech",
        "no_speech",
        "nospeech",
        "speech not detected",
        "no valid speech",
        "no words",
        "no_words",
        "asr_response_have_no_words",
        "nomatch",
        "initial silence timeout",
        "initialsilencetimeout",
        "no human voice",
        "silent_speech",
        "识别返回为空",
        "识别结果为空",
        "識別回傳為空",
        "識別結果為空",
        "未识别到内容",
        "未识别到语音",
        "未識別到內容",
        "未識別到語音",
        "未检测到语音",
        "未检测到人声",
        "未檢測到語音",
        "未檢測到人聲",
        "没有识别到",
        "沒有識別到",
        "无有效语音",
        "無有效語音",
        "没有有效的语音",
        "沒有有效的語音",
        "无语音内容",
        "無語音內容",
        "没有说话",
        "沒有說話"
    )

    private val emptyAudioSignals = listOf(
        "empty audio",
        "audio is empty",
        "no valid audio",
        "no audio data",
        "silent audio",
        "空音频",
        "无音频",
        "無音訊",
        "音频为空",
        "音訊為空",
        "音频内容为空",
        "音訊內容為空"
    )
}
