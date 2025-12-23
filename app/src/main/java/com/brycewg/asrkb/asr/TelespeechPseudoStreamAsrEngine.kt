package com.brycewg.asrkb.asr

import android.content.Context
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope

/**
 * TeleSpeech 本地模型伪流式引擎：
 * - 定时分片 + VAD 静音过滤，小片段离线识别后通过 onPartial 预览；
 * - 会话结束时对整段音频再识别一次，通过 onFinal 覆盖最终结果。
 */
class TelespeechPseudoStreamAsrEngine(
    context: Context,
    scope: CoroutineScope,
    prefs: Prefs,
    listener: StreamingAsrEngine.Listener,
    onRequestDuration: ((Long) -> Unit)? = null
) : LocalModelPseudoStreamAsrEngine(context, scope, prefs, listener, onRequestDuration) {

    companion object {
        private const val TAG = "TsPseudoStreamEngine"
    }

    private val delegate = TelespeechPseudoStreamDelegate(
        context = context,
        scope = scope,
        prefs = prefs,
        listener = listener,
        sampleRate = sampleRate,
        onRequestDuration = onRequestDuration,
        tag = TAG
    )

    override fun ensureReady(): Boolean {
        return delegate.ensureReady()
    }

    override fun onSegmentBoundary(pcmSegment: ByteArray) {
        delegate.onSegmentBoundary(pcmSegment)
    }

    override suspend fun onSessionFinished(fullPcm: ByteArray) {
        delegate.onSessionFinished(fullPcm)
    }
}
