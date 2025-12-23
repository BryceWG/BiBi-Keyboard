package com.brycewg.asrkb.asr

import android.content.Context
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope

/**
 * TeleSpeech（本地离线）推 PCM 伪流式引擎：
 * - AIDL writePcm 推流；
 * - 定时分片 + VAD 静音过滤做离线预览（onPartial）；
 * - finishPcm/stop 时对整段音频做一次离线识别（onFinal）。
 */
class TelespeechPushPcmPseudoStreamAsrEngine(
  context: Context,
  scope: CoroutineScope,
  prefs: Prefs,
  listener: StreamingAsrEngine.Listener,
  onRequestDuration: ((Long) -> Unit)? = null
) : PushPcmPseudoStreamAsrEngine(context, scope, prefs, listener, onRequestDuration) {

  companion object {
    private const val TAG = "TsPushPcmPseudo"
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
