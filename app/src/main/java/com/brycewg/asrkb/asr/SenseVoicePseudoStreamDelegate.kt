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

internal class SenseVoicePseudoStreamDelegate(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val listener: StreamingAsrEngine.Listener,
    private val sampleRate: Int,
    private val onRequestDuration: ((Long) -> Unit)?,
    private val tag: String
) {
    private val previewMutex = Mutex()
    private val previewSegments = mutableListOf<String>()

    fun ensureReady(): Boolean {
        val manager = SenseVoiceOnnxManager.getInstance()
        if (!manager.isOnnxAvailable()) {
            try {
                listener.onError(context.getString(R.string.error_local_asr_not_ready))
            } catch (t: Throwable) {
                Log.e(tag, "Failed to send error callback", t)
            }
            return false
        }
        return true
    }

    fun onSegmentBoundary(pcmSegment: ByteArray) {
        // 预览识别放到后台，避免阻塞录音
        scope.launch(Dispatchers.IO) {
            val text = try {
                decodeOnce(pcmSegment, reportErrorToUser = false)
            } catch (t: Throwable) {
                Log.e(tag, "Segment decode failed", t)
                null
            } ?: return@launch

            val trimmed = text.trim()
            if (trimmed.isEmpty()) return@launch

            // 对当前片段先做一次句末标点/emoji 修剪，然后再参与累积
            val normalizedSegment = try {
                TextSanitizer.trimTrailingPunctAndEmoji(trimmed)
            } catch (t: Throwable) {
                Log.w(tag, "trimTrailingPunctAndEmoji failed for segment, fallback to raw trimmed text", t)
                trimmed
            }
            if (normalizedSegment.isEmpty()) return@launch

            try {
                previewMutex.withLock {
                    previewSegments.add(normalizedSegment)
                    // 简单拼接，避免在中文场景插入多余空格
                    val merged = previewSegments.joinToString(separator = "")
                    // 为安全起见，对整个预览再做一次尾部修剪
                    val previewOut = try {
                        TextSanitizer.trimTrailingPunctAndEmoji(merged)
                    } catch (t: Throwable) {
                        Log.w(tag, "trimTrailingPunctAndEmoji failed for preview, fallback to merged", t)
                        merged
                    }
                    try {
                        listener.onPartial(previewOut)
                    } catch (t: Throwable) {
                        Log.e(tag, "Failed to notify partial", t)
                    }
                }
            } catch (t: Throwable) {
                Log.e(tag, "Failed to update preview segments", t)
            }
        }
    }

    suspend fun onSessionFinished(fullPcm: ByteArray) {
        val t0 = System.currentTimeMillis()
        try {
            val text = decodeOnce(fullPcm, reportErrorToUser = true)
            val dt = System.currentTimeMillis() - t0
            try {
                onRequestDuration?.invoke(dt)
            } catch (t: Throwable) {
                Log.e(tag, "Failed to invoke duration callback", t)
            }

            if (text.isNullOrBlank()) {
                try {
                    listener.onError(context.getString(R.string.error_asr_empty_result))
                } catch (t: Throwable) {
                    Log.e(tag, "Failed to notify empty result error", t)
                }
            } else {
                val raw = text.trim()
                val finalText = try {
                    val variant = try {
                        prefs.svModelVariant
                    } catch (t: Throwable) {
                        Log.w(tag, "Failed to get model variant for punctuation", t)
                        ""
                    }
                    if (variant.startsWith("nano-")) {
                        SherpaPunctuationManager.getInstance().addOfflinePunctuation(context, raw)
                    } else {
                        raw
                    }
                } catch (t: Throwable) {
                    Log.e(tag, "Failed to apply offline punctuation", t)
                    raw
                }
                try {
                    listener.onFinal(finalText)
                } catch (t: Throwable) {
                    Log.e(tag, "Failed to notify final result", t)
                }
            }
        } catch (t: Throwable) {
            Log.e(tag, "Final recognition failed", t)
            try {
                listener.onError(
                    context.getString(
                        R.string.error_recognize_failed_with_reason,
                        t.message ?: ""
                    )
                )
            } catch (e: Throwable) {
                Log.e(tag, "Failed to notify final recognition error", e)
            }
        } finally {
            try {
                previewMutex.withLock {
                    previewSegments.clear()
                }
            } catch (t: Throwable) {
                Log.e(tag, "Failed to reset preview segments after session", t)
            }
        }
    }

    private fun notifyLoadStart() {
        val ui = (listener as? SenseVoiceFileAsrEngine.LocalModelLoadUi)
        if (ui != null) {
            try {
                ui.onLocalModelLoadStart()
            } catch (t: Throwable) {
                Log.e(tag, "Failed to notify load start", t)
            }
        } else {
            try {
                Handler(Looper.getMainLooper()).post {
                    try {
                        Toast.makeText(
                            context,
                            context.getString(R.string.sv_loading_model),
                            Toast.LENGTH_SHORT
                        ).show()
                    } catch (t: Throwable) {
                        Log.e(tag, "Failed to show toast", t)
                    }
                }
            } catch (t: Throwable) {
                Log.e(tag, "Failed to post toast", t)
            }
        }
    }

    private fun notifyLoadDone() {
        val ui = (listener as? SenseVoiceFileAsrEngine.LocalModelLoadUi)
        if (ui != null) {
            try {
                ui.onLocalModelLoadDone()
            } catch (t: Throwable) {
                Log.e(tag, "Failed to notify load done", t)
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
                    Log.e(tag, "Failed to send not-ready error", t)
                }
            } else {
                Log.w(tag, "SenseVoice model not available")
            }
            return null
        }

        val base = try {
            context.getExternalFilesDir(null)
        } catch (t: Throwable) {
            Log.w(tag, "Failed to get external files dir", t)
            null
        } ?: context.filesDir

        val probeRoot = java.io.File(base, "sensevoice")
        val variant = try {
            prefs.svModelVariant
        } catch (t: Throwable) {
            Log.w(tag, "Failed to get model variant", t)
            "small-int8"
        }
        val variantDir = when (variant) {
            "small-full" -> java.io.File(probeRoot, "small-full")
            "nano-full" -> java.io.File(probeRoot, "nano-full")
            "nano-int8" -> java.io.File(probeRoot, "nano-int8")
            else -> java.io.File(probeRoot, "small-int8")
        }
        val auto = findSvModelDir(variantDir) ?: findSvModelDir(probeRoot)
        if (auto == null) {
            if (reportErrorToUser) {
                try {
                    listener.onError(context.getString(R.string.error_sensevoice_model_missing))
                } catch (t: Throwable) {
                    Log.e(tag, "Failed to notify model-missing error", t)
                }
            } else {
                Log.w(tag, "SenseVoice model directory missing")
            }
            return null
        }

        val tokensPath = java.io.File(auto, "tokens.txt").absolutePath
        val modelFile = selectSvModelFile(auto, variant)
        val modelPath = modelFile?.absolutePath
        val minBytes = 8L * 1024L * 1024L
        if (modelPath == null || !java.io.File(tokensPath).exists() || (modelFile?.length() ?: 0L) < minBytes) {
            if (reportErrorToUser) {
                try {
                    listener.onError(context.getString(R.string.error_sensevoice_model_missing))
                } catch (t: Throwable) {
                    Log.e(tag, "Failed to notify invalid model error", t)
                }
            } else {
                Log.w(tag, "SenseVoice model files invalid or missing")
            }
            return null
        }

        val samples = pcmToFloatArray(pcm)
        if (samples.isEmpty()) {
            if (reportErrorToUser) {
                try {
                    listener.onError(context.getString(R.string.error_audio_empty))
                } catch (t: Throwable) {
                    Log.e(tag, "Failed to notify empty audio error", t)
                }
            }
            return null
        }

        val keepMinutes = try {
            prefs.svKeepAliveMinutes
        } catch (t: Throwable) {
            Log.w(tag, "Failed to get keep alive minutes", t)
            -1
        }
        val keepMs = if (keepMinutes <= 0) 0L else keepMinutes.toLong() * 60_000L
        val alwaysKeep = keepMinutes < 0
        val ruleFsts = try {
            if (prefs.svUseItn) ItnAssets.ensureItnFstPath(context) else null
        } catch (t: Throwable) {
            Log.e(tag, "Failed to resolve ITN FST path", t)
            null
        }

        val text = manager.decodeOffline(
            assetManager = null,
            tokens = tokensPath,
            model = modelPath,
            language = try {
                resolveSvLanguageForVariant(prefs.svLanguage, variant)
            } catch (t: Throwable) {
                Log.w(tag, "Failed to get language", t)
                "auto"
            },
            useItn = try {
                prefs.svUseItn
            } catch (t: Throwable) {
                Log.w(tag, "Failed to get useItn", t)
                false
            },
            provider = "cpu",
            numThreads = try {
                prefs.svNumThreads
            } catch (t: Throwable) {
                Log.w(tag, "Failed to get num threads", t)
                2
            },
            ruleFsts = ruleFsts,
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
                    Log.e(tag, "Failed to notify empty result error", t)
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
