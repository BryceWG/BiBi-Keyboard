package com.brycewg.asrkb.asr

/**
 * 识别失败/取消的稳定原因码，供历史记录持久化与展示文案映射。
 *
 * 归属模块：asr
 */
internal object AsrFailReasonCodes {
    const val EMPTY_RESULT = "empty_result"
    const val EMPTY_AUDIO_SKIPPED = "empty_audio_skipped"
    const val AUTH_INVALID = "auth_invalid"
    const val AUTH_FORBIDDEN = "auth_forbidden"
    const val MIC_PERMISSION = "mic_permission"
    const val MIC_IN_USE = "mic_in_use"
    const val NETWORK_HANDSHAKE = "network_handshake"
    const val NETWORK = "network"
    const val TIMEOUT = "timeout"
    const val USER_CANCEL = "user_cancel"
    const val UNKNOWN = "unknown"
}
