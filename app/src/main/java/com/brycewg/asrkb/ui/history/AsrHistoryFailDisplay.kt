package com.brycewg.asrkb.ui.history

import android.content.Context
import com.brycewg.asrkb.LocaleHelper
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrFailReasonCodes
import com.brycewg.asrkb.store.AsrHistoryStore

/**
 * 将失败/取消识别记录格式化为历史卡片与详情展示文案。
 *
 * 归属模块：ui/history
 */
internal object AsrHistoryFailDisplay {
    fun cardText(context: Context, record: AsrHistoryStore.AsrHistoryRecord): String = if (record.isUnsuccessful) format(context, record) else record.text

    fun copyText(record: AsrHistoryStore.AsrHistoryRecord): String? = record.text.takeIf { it.isNotBlank() }
        ?: record.rawText?.takeIf { it.isNotBlank() }

    fun format(context: Context, record: AsrHistoryStore.AsrHistoryRecord): String {
        if (!record.isUnsuccessful) return ""
        val localized = LocaleHelper.wrap(context)
        if (record.failStage == AsrHistoryStore.AsrHistoryFailStage.TRANSLATION) {
            return localized.getString(R.string.translation_failed)
        }
        val recording = record.failStage == AsrHistoryStore.AsrHistoryFailStage.RECORDING
        if (record.status == AsrHistoryStore.AsrHistoryStatus.CANCELLED) {
            return if (recording) {
                localized.getString(R.string.history_fail_cancelled_recording)
            } else {
                localized.getString(R.string.history_fail_cancelled_recognition)
            }
        }
        val reason = reasonText(localized, record.failReasonCode)
        return if (recording) {
            localized.getString(R.string.history_fail_error_recording, reason)
        } else {
            localized.getString(R.string.history_fail_error_recognition, reason)
        }
    }

    private fun reasonText(context: Context, code: String?): String {
        val resId = when (code) {
            AsrFailReasonCodes.EMPTY_RESULT -> R.string.history_fail_reason_empty_result
            AsrFailReasonCodes.AUTH_INVALID -> R.string.history_fail_reason_auth_invalid
            AsrFailReasonCodes.AUTH_FORBIDDEN -> R.string.history_fail_reason_auth_forbidden
            AsrFailReasonCodes.MIC_PERMISSION -> R.string.history_fail_reason_mic_permission
            AsrFailReasonCodes.MIC_IN_USE -> R.string.history_fail_reason_mic_in_use
            AsrFailReasonCodes.NETWORK_HANDSHAKE -> R.string.history_fail_reason_network_handshake
            AsrFailReasonCodes.NETWORK -> R.string.history_fail_reason_network
            AsrFailReasonCodes.TIMEOUT -> R.string.history_fail_reason_timeout
            AsrFailReasonCodes.USER_CANCEL -> R.string.history_fail_reason_user_cancel
            else -> R.string.history_fail_reason_unknown
        }
        return context.getString(resId)
    }
}
