/**
 * API 调用日志会话封装，统一处理 WebSocket/SDK 流式请求的单次记录与耗时统计。
 *
 * 归属模块：asr
 */
package com.brycewg.asrkb.asr

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Request

object ApiCallLogger {
    fun meta(
        category: String,
        vendor: String,
        model: String = "",
        source: String = "",
        requestStructure: String = ""
    ): ApiLogMeta = ApiLogRecorder.meta(
        category = category,
        vendor = vendor,
        model = model,
        source = source,
        requestStructure = requestStructure
    )

    fun startWebSocket(request: Request, meta: ApiLogMeta? = null): Session {
        val resolvedMeta = meta ?: request.tag(ApiLogMeta::class.java)
            ?: ApiLogRecorder.meta(category = "ASR")
        return Session(request, resolvedMeta)
    }

    fun startSdkWebSocket(wsUrl: String, meta: ApiLogMeta): Session {
        val request = Request.Builder()
            .url(wsUrl.toHttpUrlForOkHttp())
            .tag(ApiLogMeta::class.java, meta)
            .build()
        return Session(request, meta)
    }

    class Session internal constructor(
        private val request: Request,
        private val meta: ApiLogMeta
    ) {
        private val recorded = AtomicBoolean(false)
        private val startedNs = System.nanoTime()
        private val responseSummary = AtomicReference("")

        /**
         * 记录最近一帧服务端消息的形态摘要（字段名、帧数、业务错误码），不含识别文本。
         *
         * WebSocket 没有 HTTP 响应体，不回填时 API Log 的响应列会一直是空的。
         */
        fun noteResponse(summary: String) {
            if (summary.isNotBlank()) {
                responseSummary.set(summary.take(MAX_RESPONSE_SUMMARY))
            }
        }

        fun success(code: Int = 0) {
            complete(success = true, code = code)
        }

        fun failure(code: Int = 0, error: String = "") {
            complete(success = false, code = code, error = error)
        }

        fun complete(success: Boolean, code: Int = 0, error: String = "") {
            if (!recorded.compareAndSet(false, true)) return
            ApiLogRecorder.recordWebSocket(
                request,
                meta,
                success = success,
                durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs),
                code = code,
                error = error,
                detail = responseSummary.get()
            )
        }
    }

    private const val MAX_RESPONSE_SUMMARY = 240

    private fun String.toHttpUrlForOkHttp(): String = replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")
}
