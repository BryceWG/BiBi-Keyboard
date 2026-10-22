/**
 * ASR/LLM 外部 API 调用摘要记录器。
 *
 * 归属模块：asr
 */
package com.brycewg.asrkb.asr

import com.brycewg.asrkb.store.ApiLogStore
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject

data class ApiLogMeta(
    val category: String,
    val vendor: String = "",
    val model: String = "",
    val source: String = "",
    val requestStructure: String = "",
    val redactPath: Boolean = false,
    val redactErrorBody: Boolean = false
)

class ApiLogInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("Upgrade")?.equals("websocket", ignoreCase = true) == true) {
            return chain.proceed(request)
        }
        if (request.tag(ApiLogMeta::class.java) == null &&
            (request.method == "HEAD" || request.method == "GET")
        ) {
            return chain.proceed(request)
        }
        val meta = request.tag(ApiLogMeta::class.java)
        val probeAsr = meta == null || meta.category.equals("ASR", ignoreCase = true)
        val requestBytes = requestBodyBytes(request)
        if (probeAsr) {
            AsrCallLatencyProbe.log(
                "asr_http_start",
                buildMap { requestBytes?.let { put("bytes", it) } }
            )
        }
        val started = System.nanoTime()
        return try {
            val response = chain.proceed(request)
            if (probeAsr) {
                AsrCallLatencyProbe.log(
                    "asr_http_end",
                    buildMap {
                        requestBytes?.let { put("bytes", it) }
                        put("elapsed_ms", elapsedMs(started))
                    }
                )
            }
            if (response.code == 101) {
                return response
            }
            val probed = if (probeAsr) probeBodyRead(response, started) else response
            ApiLogRecorder.recordHttp(request, probed, elapsedMs(started), null, canceled = false)
            probed
        } catch (t: IOException) {
            if (probeAsr) {
                AsrCallLatencyProbe.log(
                    "asr_http_end",
                    mapOf("elapsed_ms" to elapsedMs(started))
                )
            }
            ApiLogRecorder.recordHttp(
                request,
                null,
                elapsedMs(started),
                t,
                canceled = chain.call().isCanceled()
            )
            throw t
        }
    }

    private fun elapsedMs(started: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

    /**
     * 包装 ASR 响应体：在 body 被读到 EOF 时打点。
     *
     * `asr_http_end` 只覆盖到响应头到达，body 是流式读取的；两者的差值即响应体下载与读取成本。
     */
    private fun probeBodyRead(response: Response, started: Long): Response {
        val body = response.body ?: return response
        val probedSource = object : ForwardingSource(body.source()) {
            private var reported = false

            override fun read(sink: Buffer, byteCount: Long): Long {
                val read = super.read(sink, byteCount)
                if (read == -1L && !reported) {
                    reported = true
                    AsrCallLatencyProbe.log(
                        "t_http_body_read",
                        mapOf("elapsed_ms" to elapsedMs(started))
                    )
                }
                return read
            }
        }
        return response.newBuilder()
            .body(probedSource.buffer().asResponseBody(body.contentType(), body.contentLength()))
            .build()
    }

    private fun requestBodyBytes(request: Request): Long? {
        val body = request.body ?: return null
        val length = try {
            body.contentLength()
        } catch (_: Throwable) {
            return null
        }
        return length.takeIf { it >= 0L }
    }
}

object ApiLogRecorder {
    private const val MAX_ERROR = 240
    private const val MAX_STRUCTURE_BODY_BYTES = 32 * 1024L
    private const val PEEK_JSON_BYTES = 16 * 1024L

    fun meta(
        category: String,
        vendor: String = "",
        model: String = "",
        source: String = "",
        requestStructure: String = "",
        redactPath: Boolean = false,
        redactErrorBody: Boolean = false
    ) = ApiLogMeta(
        category = category,
        vendor = vendor,
        model = model,
        source = source,
        requestStructure = requestStructure,
        redactPath = redactPath,
        redactErrorBody = redactErrorBody
    )

    fun recordWebSocket(
        request: Request,
        meta: ApiLogMeta,
        success: Boolean,
        durationMs: Long,
        code: Int = 0,
        error: String = "",
        detail: String = ""
    ) {
        val url = request.url
        ApiLogStore.add(
            ApiLogStore.ApiLogRecord(
                category = meta.category,
                vendor = meta.vendor,
                model = meta.model,
                source = meta.source,
                protocol = "WebSocket",
                method = "GET",
                host = url.host,
                path = if (meta.redactPath) "/redacted" else url.encodedPath,
                queryKeys = url.queryParameterNames.sorted(),
                // 无自定义 header 时留空，避免网络日志里出现恒为空的 headers=[]。
                requestSummary = safeHeaderNames(request).let { if (it.isBlank()) "" else "headers=$it" },
                requestStructure = meta.requestStructure,
                responseSummary = buildList {
                    if (code != 0) add("closeCode=$code")
                    if (detail.isNotBlank()) add(detail)
                }.joinToString("; "),
                httpCode = code,
                success = success,
                durationMs = durationMs,
                errorSummary = error.take(MAX_ERROR)
            )
        )
    }

    internal fun recordHttp(
        request: Request,
        response: Response?,
        durationMs: Long,
        throwable: Throwable?,
        canceled: Boolean = false
    ) {
        val meta = request.tag(ApiLogMeta::class.java)
            ?: ApiLogMeta(category = "ASR")
        val businessError = if (throwable == null && !canceled) detectJsonBusinessError(response) else null
        val url = request.url
        ApiLogStore.add(
            ApiLogStore.ApiLogRecord(
                category = meta.category,
                vendor = meta.vendor,
                model = meta.model,
                source = meta.source,
                protocol = "HTTP",
                method = request.method,
                host = url.host,
                path = if (meta.redactPath) "/redacted" else url.encodedPath,
                queryKeys = url.queryParameterNames.sorted(),
                requestSummary = buildRequestSummary(request),
                requestStructure = meta.requestStructure.ifBlank { buildRequestStructure(request) },
                responseSummary = buildResponseSummary(response),
                httpCode = response?.code ?: 0,
                success = response?.isSuccessful == true &&
                    throwable == null &&
                    !canceled &&
                    businessError == null,
                canceled = canceled,
                durationMs = durationMs,
                errorSummary = if (canceled) {
                    "Canceled by user"
                } else if (businessError != null) {
                    businessError.take(MAX_ERROR)
                } else if (meta.redactErrorBody) {
                    throwable?.javaClass?.simpleName.orEmpty().ifBlank {
                        response?.let { "HTTP ${it.code}" }.orEmpty()
                    }
                } else {
                    buildErrorSummary(response, throwable)
                }
            )
        )
    }

    private fun buildRequestSummary(request: Request): String {
        val body = request.body ?: return "headers=${safeHeaderNames(request)}"
        val type = body.contentType()?.toString().orEmpty()
        val length = safeContentLength(body)
        val bodyPart = "body=${type.ifBlank { "unknown" }}"
        val sizePart = if (length >= 0) "bytes=$length" else "bytes=unknown"
        return "$bodyPart; $sizePart; headers=${safeHeaderNames(request)}"
    }

    private fun buildRequestStructure(request: Request): String {
        val body = request.body ?: return ""
        val type = body.contentType()?.toString().orEmpty()
        val length = safeContentLength(body)
        if (type.contains("application/json", ignoreCase = true) ||
            type.contains("+json", ignoreCase = true)
        ) {
            if (length < 0) return "json body; bytes=unknown; structure=skipped_unknown_size"
            if (length > MAX_STRUCTURE_BODY_BYTES) {
                return "json body; bytes=$length; structure=skipped_large_body"
            }
            val raw = try {
                val buffer = Buffer()
                body.writeTo(buffer)
                buffer.readUtf8()
            } catch (_: Throwable) {
                ""
            }
            return describeJsonStructure(raw)
        }
        if (type.contains("x-www-form-urlencoded", ignoreCase = true)) {
            if (length < 0) return "form fields=unknown; bytes=unknown"
            if (length > MAX_STRUCTURE_BODY_BYTES) return "form fields=skipped_large_body; bytes=$length"
            val raw = try {
                val buffer = Buffer()
                body.writeTo(buffer)
                buffer.readUtf8()
            } catch (_: Throwable) {
                ""
            }
            val keys = raw.split("&")
                .mapNotNull { it.substringBefore("=", "").takeIf { key -> key.isNotBlank() } }
                .distinct()
            return if (keys.isEmpty()) "form fields=unknown" else "form fields=${keys.joinToString(", ")}"
        }
        if (type.contains("multipart/", ignoreCase = true)) {
            return "multipart fields=see request summary"
        }
        return "body=${type.ifBlank { "unknown" }}"
    }

    private fun safeContentLength(body: RequestBody): Long = try {
        body.contentLength()
    } catch (_: Throwable) {
        -1L
    }

    private fun describeJsonStructure(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return "json empty"
        return describeJsonStructureOrNull(trimmed) ?: "json structure unavailable"
    }

    private fun describeJsonStructureOrNull(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        return try {
            when {
                trimmed.startsWith("{") -> {
                    val obj = JSONObject(trimmed)
                    val keys = obj.keys().asSequence().toList().sorted()
                    if (keys.isEmpty()) "json object keys=unknown" else "json object keys=${keys.joinToString(", ")}"
                }
                trimmed.startsWith("[") -> {
                    val arr = JSONArray(trimmed)
                    val first = arr.opt(0)
                    if (first is JSONObject) {
                        val keys = first.keys().asSequence().toList().sorted()
                        val head = if (keys.isEmpty()) "unknown" else keys.joinToString(", ")
                        "json array[0] keys=$head"
                    } else {
                        "json array length=${arr.length()}"
                    }
                }
                else -> "json text"
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun buildResponseSummary(response: Response?): String {
        if (response == null) return ""
        val type = response.body.contentType()?.toString().orEmpty()
        val length = try {
            response.body.contentLength()
        } catch (_: Throwable) {
            -1L
        }
        val sizePart = if (length >= 0) "bytes=$length" else "bytes=unknown"
        val base = "contentType=${type.ifBlank { "unknown" }}; $sizePart"
        if (!type.contains("json", ignoreCase = true)) return base
        val raw = try {
            response.peekBody(PEEK_JSON_BYTES).string()
        } catch (_: Throwable) {
            ""
        }
        val shape = describeJsonStructureOrNull(raw) ?: return base
        return "$base; $shape"
    }

    /**
     * 提取「HTTP 2xx + JSON 业务错误」形态（如腾讯极速版的顶层 code、或 Response.Error）。
     *
     * 这类接口失败时仍返回 200，不识别会把失败请求记成成功且错误为空。
     */
    private fun detectJsonBusinessError(response: Response?): String? {
        if (response == null || !response.isSuccessful) return null
        val type = response.body.contentType()?.toString().orEmpty()
        if (!type.contains("json", ignoreCase = true)) return null
        val raw = try {
            response.peekBody(PEEK_JSON_BYTES).string()
        } catch (_: Throwable) {
            return null
        }
        if (raw.isBlank()) return null
        return try {
            val obj = JSONObject(raw)
            val legacy = obj.optJSONObject("Response")?.optJSONObject("Error")
            if (legacy != null) {
                listOf(legacy.optString("Code").trim(), legacy.optString("Message").trim())
                    .filter { it.isNotEmpty() }
                    .joinToString(": ")
                    .ifBlank { null }
            } else {
                val code = obj.opt("code")
                if (code is Number && code.toInt() != 0) {
                    listOf("code=$code", obj.optString("message").trim())
                        .filter { it.isNotEmpty() }
                        .joinToString(": ")
                } else {
                    null
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun buildErrorSummary(response: Response?, throwable: Throwable?): String {
        if (throwable != null) return throwable.message.orEmpty().take(MAX_ERROR)
        if (response == null || response.isSuccessful) return ""
        val bodyText = try {
            response.peekBody(2048L).string()
        } catch (_: Throwable) {
            ""
        }.replace(Regex("\\s+"), " ").trim()
        val fallback = response.message.ifBlank { "HTTP ${response.code}" }
        return bodyText.ifBlank { fallback }.take(MAX_ERROR)
    }

    private fun safeHeaderNames(request: Request): String {
        val hidden = setOf(
            "authorization",
            "proxy-authorization",
            "x-api-key",
            "xi-api-key",
            "x-api-access-key",
            "x-api-app-key",
            "x-goog-api-key",
            "apikey"
        )
        return request.headers.names().sorted().joinToString(prefix = "[", postfix = "]") { name ->
            if (hidden.contains(name.lowercase(Locale.US))) "$name=***" else name
        }
    }
}
