/**
 * 本地 ASR 调用日志封装：把模型加载与离线推理摘要写入 API Log。
 *
 * 归属模块：asr
 */
package com.brycewg.asrkb.asr

import android.os.SystemClock
import com.brycewg.asrkb.store.ApiLogStore
import com.brycewg.asrkb.store.Prefs
import java.util.concurrent.atomic.AtomicBoolean

internal object LocalAsrCallLogger {
    private const val MAX_ERROR = 240
    private const val PCM_BYTES_PER_SAMPLE = 2

    fun startInference(
        prefs: Prefs,
        vendor: AsrVendor,
        source: String,
        audioBytes: Int,
        sampleRate: Int,
        startedMs: Long = SystemClock.uptimeMillis()
    ): Session {
        val durationMs = if (sampleRate > 0) {
            (audioBytes / PCM_BYTES_PER_SAMPLE) * 1_000L / sampleRate
        } else {
            0L
        }
        return Session(
            recordBase = RecordBase(
                prefs = prefs,
                vendor = vendor,
                source = source,
                method = "INFER",
                path = "/local/infer",
                requestSummary = "audio=pcm16le; bytes=$audioBytes; durationMs=$durationMs; sampleRate=$sampleRate; source=$source"
            ),
            startedMs = startedMs
        )
    }

    fun startLoad(
        prefs: Prefs,
        vendor: AsrVendor,
        source: String
    ): Session = Session(
        recordBase = RecordBase(
            prefs = prefs,
            vendor = vendor,
            source = source,
            method = "LOAD",
            path = "/local/load",
            requestSummary = "operation=model_load; source=$source"
        )
    )

    fun recordLoadFailure(
        prefs: Prefs,
        vendor: AsrVendor,
        source: String,
        error: String
    ) {
        startLoad(prefs, vendor, source).failure(error)
    }

    class Session internal constructor(
        private val recordBase: RecordBase,
        private val startedMs: Long = SystemClock.uptimeMillis()
    ) {
        private val recorded = AtomicBoolean(false)

        fun success(responseSummary: String = "ok=true") {
            complete(success = true, responseSummary = responseSummary)
        }

        fun successWithText(text: String?) {
            val trimmed = text?.trim().orEmpty()
            complete(
                success = trimmed.isNotEmpty(),
                responseSummary = "resultChars=${trimmed.length}; empty=${trimmed.isEmpty()}",
                errorSummary = if (trimmed.isEmpty()) "Empty ASR result" else ""
            )
        }

        fun failure(error: String) {
            complete(success = false, errorSummary = error)
        }

        fun cancel(reason: String = "Canceled by user") {
            complete(success = false, canceled = true, errorSummary = reason)
        }

        private fun complete(
            success: Boolean,
            responseSummary: String = "",
            canceled: Boolean = false,
            errorSummary: String = ""
        ) {
            if (!recorded.compareAndSet(false, true)) return
            val durationMs = (SystemClock.uptimeMillis() - startedMs).coerceAtLeast(0)
            val base = recordBase
            ApiLogStore.add(
                ApiLogStore.ApiLogRecord(
                    category = "ASR",
                    vendor = base.vendor.id,
                    model = modelVariant(base.prefs, base.vendor),
                    source = base.source,
                    protocol = "Local",
                    method = base.method,
                    host = "",
                    path = base.path,
                    requestSummary = base.requestSummary,
                    requestStructure = configSummary(base.prefs, base.vendor, base.source),
                    responseSummary = responseSummary,
                    success = success,
                    canceled = canceled,
                    durationMs = durationMs,
                    errorSummary = errorSummary.take(MAX_ERROR)
                )
            )
        }
    }

    internal data class RecordBase(
        val prefs: Prefs,
        val vendor: AsrVendor,
        val source: String,
        val method: String,
        val path: String,
        val requestSummary: String
    )

    private fun modelVariant(prefs: Prefs, vendor: AsrVendor): String = safe("-") {
        when (vendor) {
            AsrVendor.SenseVoice -> prefs.svModelVariant
            AsrVendor.FunAsrNano -> normalizeFunAsrNanoVariant(prefs.fnModelVariant)
            AsrVendor.Qwen3Asr -> normalizeQwen3AsrVariant(prefs.qwModelVariant)
            AsrVendor.Parakeet -> normalizeParakeetVariant(prefs.pkModelVariant)
            AsrVendor.GigaAm -> normalizeGigaAmVariant(prefs.gigaAmModelVariant)
            AsrVendor.FireRedAsr -> prefs.frModelVariant
            AsrVendor.XAsr -> prefs.xAsrModelVariant
            else -> vendor.id
        }
    }.ifBlank { "-" }

    private fun configSummary(prefs: Prefs, vendor: AsrVendor, source: String): String = safe("") {
        val parts = mutableListOf(
            "engine=local",
            "source=$source",
            "provider=cpu"
        )
        when (vendor) {
            AsrVendor.SenseVoice -> {
                parts += "threads=${prefs.svNumThreads}"
                parts += "language=${prefs.svLanguage.ifBlank { "auto" }}"
                parts += "itn=${prefs.svUseItn}"
                parts += "keepAliveMinutes=${prefs.svKeepAliveMinutes}"
            }
            AsrVendor.FunAsrNano -> {
                parts += "threads=${prefs.fnNumThreads}"
                parts += "language=${prefs.fnLanguage.ifBlank { "auto" }}"
                parts += "itn=${prefs.fnUseItn}"
                parts += "keepAliveMinutes=${prefs.fnKeepAliveMinutes}"
                parts += "promptChars=${prefs.fnUserPrompt.length}"
            }
            AsrVendor.Qwen3Asr -> {
                parts += "threads=${prefs.qwNumThreads}"
                parts += "itn=${prefs.qwUseItn}"
                parts += "keepAliveMinutes=${prefs.qwKeepAliveMinutes}"
            }
            AsrVendor.Parakeet -> {
                parts += "threads=${prefs.pkNumThreads}"
                parts += "keepAliveMinutes=${prefs.pkKeepAliveMinutes}"
            }
            AsrVendor.GigaAm -> {
                parts += "threads=${prefs.gigaAmNumThreads}"
                parts += "keepAliveMinutes=${prefs.gigaAmKeepAliveMinutes}"
            }
            AsrVendor.FireRedAsr -> {
                parts += "threads=${prefs.frNumThreads}"
                parts += "itn=${prefs.frUseItn}"
                parts += "keepAliveMinutes=${prefs.frKeepAliveMinutes}"
            }
            AsrVendor.XAsr -> {
                parts += "threads=${prefs.xAsrNumThreads}"
                parts += "itn=${prefs.xAsrUseItn}"
                parts += "keepAliveMinutes=${prefs.xAsrKeepAliveMinutes}"
                parts += "streaming=true"
            }
            else -> Unit
        }
        parts.joinToString("; ")
    }

    private inline fun <T> safe(default: T, block: () -> T): T = try {
        block()
    } catch (_: Throwable) {
        default
    }
}
