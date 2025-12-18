package com.brycewg.asrkb.asr

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.util.TextSanitizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * SenseVoice（本地离线）推 PCM 伪流式引擎：
 * - AIDL writePcm 推流；
 * - VAD 停顿分片做离线预览（onPartial）；
 * - finishPcm/stop 时对整段音频做一次离线识别（onFinal）。
 */
class SenseVoicePushPcmPseudoStreamAsrEngine(
  context: Context,
  scope: CoroutineScope,
  prefs: Prefs,
  listener: StreamingAsrEngine.Listener,
  onRequestDuration: ((Long) -> Unit)? = null
) : PushPcmPseudoStreamAsrEngine(context, scope, prefs, listener, onRequestDuration) {

  companion object {
    private const val TAG = "SvPushPcmPseudo"
  }

  private val previewMutex = Mutex()
  private val previewSegments = mutableListOf<String>()

  override fun ensureReady(): Boolean {
    val manager = SenseVoiceOnnxManager.getInstance()
    if (!manager.isOnnxAvailable()) {
      try {
        listener.onError(context.getString(R.string.error_local_asr_not_ready))
      } catch (t: Throwable) {
        Log.e(TAG, "Failed to send error callback", t)
      }
      return false
    }
    return true
  }

  private fun notifyLoadStart() {
    val ui = (listener as? SenseVoiceFileAsrEngine.LocalModelLoadUi)
    if (ui != null) {
      try { ui.onLocalModelLoadStart() } catch (t: Throwable) { Log.e(TAG, "notify load start failed", t) }
    } else {
      try {
        Handler(Looper.getMainLooper()).post {
          try {
            Toast.makeText(context, context.getString(R.string.sv_loading_model), Toast.LENGTH_SHORT).show()
          } catch (t: Throwable) {
            Log.e(TAG, "show toast failed", t)
          }
        }
      } catch (t: Throwable) {
        Log.e(TAG, "post toast failed", t)
      }
    }
  }

  private fun notifyLoadDone() {
    val ui = (listener as? SenseVoiceFileAsrEngine.LocalModelLoadUi)
    if (ui != null) {
      try { ui.onLocalModelLoadDone() } catch (t: Throwable) { Log.e(TAG, "notify load done failed", t) }
    }
  }

  override fun onSegmentBoundary(pcmSegment: ByteArray) {
    scope.launch(Dispatchers.IO) {
      val text = try {
        decodeOnce(pcmSegment, reportErrorToUser = false)
      } catch (t: Throwable) {
        Log.e(TAG, "Segment decode failed", t)
        null
      } ?: return@launch

      val trimmed = text.trim()
      if (trimmed.isEmpty()) return@launch

      val normalizedSegment = try {
        TextSanitizer.trimTrailingPunctAndEmoji(trimmed)
      } catch (t: Throwable) {
        Log.w(TAG, "trimTrailingPunctAndEmoji failed for segment, fallback to raw", t)
        trimmed
      }
      if (normalizedSegment.isEmpty()) return@launch

      try {
        previewMutex.withLock {
          previewSegments.add(normalizedSegment)
          val merged = previewSegments.joinToString(separator = "")
          val previewOut = try {
            TextSanitizer.trimTrailingPunctAndEmoji(merged)
          } catch (t: Throwable) {
            Log.w(TAG, "trimTrailingPunctAndEmoji failed for preview, fallback to merged", t)
            merged
          }
          try { listener.onPartial(previewOut) } catch (t: Throwable) { Log.e(TAG, "notify partial failed", t) }
        }
      } catch (t: Throwable) {
        Log.e(TAG, "Failed to update preview segments", t)
      }
    }
  }

  override suspend fun onSessionFinished(fullPcm: ByteArray) {
    val t0 = System.currentTimeMillis()
    try {
      val text = decodeOnce(fullPcm, reportErrorToUser = true)
      val dt = System.currentTimeMillis() - t0
      try { onRequestDuration?.invoke(dt) } catch (t: Throwable) { Log.e(TAG, "invoke duration failed", t) }

      if (text.isNullOrBlank()) {
        try { listener.onError(context.getString(R.string.error_asr_empty_result)) } catch (t: Throwable) {
          Log.e(TAG, "notify empty result failed", t)
        }
      } else {
        try { listener.onFinal(text.trim()) } catch (t: Throwable) { Log.e(TAG, "notify final failed", t) }
      }
    } catch (t: Throwable) {
      Log.e(TAG, "Final recognition failed", t)
      try {
        listener.onError(
          context.getString(
            R.string.error_recognize_failed_with_reason,
            t.message ?: ""
          )
        )
      } catch (e: Throwable) {
        Log.w(TAG, "notify final error failed", e)
      }
    } finally {
      try {
        previewMutex.withLock { previewSegments.clear() }
      } catch (t: Throwable) {
        Log.e(TAG, "Failed to reset preview segments after session", t)
      }
    }
  }

  private suspend fun decodeOnce(
    pcm: ByteArray,
    reportErrorToUser: Boolean
  ): String? {
    val manager = SenseVoiceOnnxManager.getInstance()
    if (!manager.isOnnxAvailable()) {
      if (reportErrorToUser) {
        try {
          listener.onError(context.getString(R.string.error_local_asr_not_ready))
        } catch (t: Throwable) {
          Log.w(TAG, "notify not-ready failed", t)
        }
      }
      return null
    }

    val base = try {
      context.getExternalFilesDir(null)
    } catch (t: Throwable) {
      Log.w(TAG, "Failed to get external files dir", t)
      null
    } ?: context.filesDir

    val probeRoot = java.io.File(base, "sensevoice")
    val variant = try {
      prefs.svModelVariant
    } catch (t: Throwable) {
      Log.w(TAG, "Failed to get model variant", t)
      "small-int8"
    }
    val variantDir = when (variant) {
      "small-full" -> java.io.File(probeRoot, "small-full")
      else -> java.io.File(probeRoot, "small-int8")
    }
    val auto = findSvModelDir(variantDir) ?: findSvModelDir(probeRoot)
    if (auto == null) {
      if (reportErrorToUser) {
        try {
          listener.onError(context.getString(R.string.error_sensevoice_model_missing))
        } catch (t: Throwable) {
          Log.w(TAG, "notify model-missing failed", t)
        }
      }
      return null
    }
    val dir = auto.absolutePath

    val tokensPath = java.io.File(auto, "tokens.txt").absolutePath
    val modelFile = selectSvModelFile(auto, variant)
    val modelPath = modelFile?.absolutePath
    val minBytes = 8L * 1024L * 1024L
    if (modelPath == null || !java.io.File(tokensPath).exists() || (modelFile?.length() ?: 0L) < minBytes) {
      if (reportErrorToUser) {
        try {
          listener.onError(context.getString(R.string.error_sensevoice_model_missing))
        } catch (t: Throwable) {
          Log.w(TAG, "notify invalid model failed", t)
        }
      }
      return null
    }

    val samples = pcmToFloatArray(pcm)
    if (samples.isEmpty()) {
      if (reportErrorToUser) {
        try {
          listener.onError(context.getString(R.string.error_audio_empty))
        } catch (t: Throwable) {
          Log.w(TAG, "notify audio empty failed", t)
        }
      }
      return null
    }

    val keepMinutes = try {
      prefs.svKeepAliveMinutes
    } catch (t: Throwable) {
      Log.w(TAG, "Failed to get keep alive minutes", t)
      -1
    }
    val keepMs = if (keepMinutes <= 0) 0L else keepMinutes.toLong() * 60_000L
    val alwaysKeep = keepMinutes < 0

    val text = manager.decodeOffline(
      assetManager = null,
      tokens = tokensPath,
      model = modelPath,
      language = try { prefs.svLanguage } catch (t: Throwable) { Log.w(TAG, "Failed to get language", t); "auto" },
      useItn = try { prefs.svUseItn } catch (t: Throwable) { Log.w(TAG, "Failed to get useItn", t); false },
      provider = "cpu",
      numThreads = try { prefs.svNumThreads } catch (t: Throwable) { Log.w(TAG, "Failed to get num threads", t); 2 },
      samples = samples,
      sampleRate = sampleRate,
      keepAliveMs = keepMs,
      alwaysKeep = alwaysKeep,
      onLoadStart = { notifyLoadStart() },
      onLoadDone = { notifyLoadDone() }
    )

    if (text.isNullOrBlank()) {
      if (reportErrorToUser) {
        try {
          listener.onError(context.getString(R.string.error_asr_empty_result))
        } catch (t: Throwable) {
          Log.w(TAG, "notify empty result failed", t)
        }
      }
      return null
    }
    return text.trim()
  }

  private fun pcmToFloatArray(pcm: ByteArray): FloatArray {
    if (pcm.isEmpty()) return FloatArray(0)
    val n = pcm.size / 2
    val out = FloatArray(n)
    val bb = java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    var i = 0
    while (i < n) {
      val s = bb.short.toInt()
      var f = s / 32768.0f
      if (f > 1f) f = 1f else if (f < -1f) f = -1f
      out[i] = f
      i++
    }
    return out
  }
}
