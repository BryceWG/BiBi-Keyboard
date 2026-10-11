package com.brycewg.asrkb.ime

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrErrorMessageMapper
import com.brycewg.asrkb.asr.AsrFailReasonCodes
import com.brycewg.asrkb.asr.BaseFileAsrEngine

internal class RetryUseCase(
    private val context: Context,
    private val asrManager: AsrSessionManager,
    private val uiListenerProvider: () -> KeyboardActionHandler.UiListener?,
    private val transitionToState: (KeyboardState) -> Unit,
    private val transitionToIdle: () -> Unit,
    private val scheduleProcessingTimeout: () -> Unit,
    private val logTag: String
) {
    fun shouldOfferRetry(message: String): Boolean {
        val engine = try {
            asrManager.getEngine()
        } catch (_: Throwable) {
            null
        }
        val isFileEngine = engine is BaseFileAsrEngine
        if (!isFileEngine) return false

        if (AsrErrorMessageMapper.classify(context, message) == AsrFailReasonCodes.EMPTY_RESULT) {
            return false
        }
        val msgLower = message.lowercase()

        val networkKeywords = arrayOf(
            "网络",
            "超时",
            "timeout",
            "timed out",
            "connect",
            "connection",
            "socket",
            "host",
            "unreachable",
            "rate",
            "too many requests"
        )
        val looksNetwork = networkKeywords.any { kw -> kw in message || kw in msgLower }
        if (!looksNetwork) return false

        return try {
            asrManager.canRetryLastFileRecognition()
        } catch (_: Throwable) {
            false
        }
    }

    fun onRetryClick() {
        try {
            uiListenerProvider()?.onHideRetryChip()
        } catch (_: Throwable) {
        }
        transitionToState(KeyboardState.Processing)
        scheduleProcessingTimeout()
        uiListenerProvider()?.onStatusMessage(context.getString(R.string.status_recognizing))
        val ok = try {
            asrManager.retryLastFileRecognition()
        } catch (t: Throwable) {
            Log.e(logTag, "retryLastFileRecognition threw", t)
            false
        }
        if (!ok) {
            transitionToIdle()
        }
    }
}
