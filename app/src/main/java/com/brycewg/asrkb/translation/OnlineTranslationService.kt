/** Anonymous online translation protocols; no credentials, provider switching or local models. */
package com.brycewg.asrkb.translation

import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

internal object OnlineTranslationService {
    private val client = OkHttpClient.Builder()
        .callTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun translate(vendor: TranslationVendor, text: String, source: String, target: String): String = withContext(Dispatchers.IO) {
        require(!vendor.offline && target in vendor.languages && (source == "auto" || source in vendor.languages))
        if (source == target) return@withContext text
        val result = StringBuilder()
        var offset = 0
        val budget = if (vendor == TranslationVendor.Bilibili) 2000 else 1000
        while (offset < text.length) {
            currentCoroutineContext().ensureActive()
            val count = minOf(budget, text.codePointCount(offset, text.length))
            val limit = text.offsetByCodePoints(offset, count)
            val boundary = if (limit < text.length) {
                (limit - 1 downTo offset + (limit - offset) / 2).firstOrNull { text[it].isWhitespace() || text[it] in "。！？.!?;；" }
            } else {
                null
            }
            val end = boundary?.plus(1) ?: limit
            val part = text.substring(offset, end)
            if (part.isBlank()) {
                result.append(part)
            } else {
                val start = part.indexOfFirst { !it.isWhitespace() }
                val stop = part.indexOfLast { !it.isWhitespace() } + 1
                result.append(part.substring(0, start))
                result.append(translatePart(vendor, part.substring(start, stop), source, target).trim())
                result.append(part.substring(stop))
            }
            offset = end
        }
        result.toString()
    }

    private suspend fun translatePart(vendor: TranslationVendor, text: String, source: String, target: String): String {
        val from = language(vendor, source, sourceLanguage = true)
        val to = language(vendor, target)
        val request = when (vendor) {
            TranslationVendor.Microsoft -> {
                val url = "https://edge.microsoft.com/translate/translatetext".toHttpUrl().newBuilder()
                    .addQueryParameter("from", if (source == "auto") "" else from)
                    .addQueryParameter("to", to)
                    .addQueryParameter("isEnterpriseClient", "false").build()
                Request.Builder().url(url).post(JSONArray().put(escapeHtml(text)).toString().toRequestBody(jsonType)).build()
            }
            TranslationVendor.Tencent -> jsonRequest(
                "https://transmart.qq.com/api/imt",
                JSONObject()
                    .put("header", JSONObject().put("fn", "auto_translation").put("client_key", "browser-chrome-110.0.0-Android-${UUID.randomUUID()}-${System.currentTimeMillis()}"))
                    .put("type", "plain").put("model_category", "normal")
                    .put("source", JSONObject().put("text_list", JSONArray().put(text)).put("lang", from))
                    .put("target", JSONObject().put("lang", to))
            )
            TranslationVendor.Volcengine -> {
                val body = JSONObject().put("target_language", to).put("text", text)
                if (source != "auto") body.put("source_language", from)
                jsonRequest("https://translate.volcengine.com/crx/translate/v1/", body)
            }
            TranslationVendor.Bilibili -> {
                val sourceName = if (source == "auto") "" else languageName(source)
                val instruction = "Translate the following $sourceName text into ${languageName(target)}. Preserve code, formulas, names and links. Output only the translation without explanation.\n\n$text"
                jsonRequest(
                    "https://index-translate.bilibili.com/v1/chat/completions",
                    JSONObject().put("model", "Index-Translate-35B-A3B")
                        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", instruction)))
                        .put("temperature", 0).put("stream", false).put("max_tokens", 4096)
                        .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
                )
            }
            TranslationVendor.Google -> {
                val url = "https://translate.googleapis.com/translate_a/t".toHttpUrl().newBuilder()
                    .addQueryParameter("client", "gtx").addQueryParameter("sl", from)
                    .addQueryParameter("tl", to).addQueryParameter("dt", "t").build()
                Request.Builder().url(url).post(FormBody.Builder().add("q", text).build()).build()
            }
            TranslationVendor.Foxlet -> error("Offline provider has no HTTP protocol")
        }
        val body = execute(request)
        val translated = when (vendor) {
            TranslationVendor.Microsoft -> decodeHtml(JSONArray(body).getJSONObject(0).getJSONArray("translations").getJSONObject(0).getString("text"))
            TranslationVendor.Tencent -> {
                val response = JSONObject(body)
                check(response.getJSONObject("header").getString("ret_code") == "succ")
                val parts = response.getJSONArray("auto_translation")
                (0 until parts.length()).joinToString("") { parts.getString(it) }
            }
            TranslationVendor.Volcengine -> {
                val response = JSONObject(body)
                check(response.getJSONObject("base_resp").getInt("status_code") == 0)
                response.getString("translation")
            }
            TranslationVendor.Bilibili -> {
                val choice = JSONObject(body).getJSONArray("choices").getJSONObject(0)
                check(choice.getString("finish_reason") != "length")
                val content = choice.getJSONObject("message").getString("content")
                val plain = content.replace(Regex("""<think>[\s\S]*?</think>"""), "").trim()
                check(!plain.contains("<think>") && !plain.contains("</think>"))
                plain
            }
            TranslationVendor.Google -> {
                val response = JSONArray(body)
                if (source == "auto") response.getJSONArray(0).getString(0) else response.getString(0)
            }
            TranslationVendor.Foxlet -> error("Offline provider has no HTTP response")
        }
        check(translated.isNotBlank())
        return translated
    }

    private fun jsonRequest(url: String, body: JSONObject): Request = Request.Builder().url(url)
        .header("User-Agent", "BiBi-Keyboard")
        .post(body.toString().toRequestBody(jsonType)).build()

    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        if (!it.isSuccessful) throw IOException("Translation HTTP ${it.code}")
                        it.body.string()
                    }
                }
                continuation.resumeWith(result)
            }
        })
    }

    private fun language(vendor: TranslationVendor, tag: String, sourceLanguage: Boolean = false): String = when {
        vendor == TranslationVendor.Google && tag == "zh-Hans" -> "zh-CN"
        vendor == TranslationVendor.Google && tag == "zh-Hant" -> "zh-TW"
        vendor in listOf(TranslationVendor.Tencent, TranslationVendor.Volcengine) && tag == "zh-Hans" -> "zh"
        vendor in listOf(TranslationVendor.Tencent, TranslationVendor.Volcengine) && tag == "zh-Hant" && sourceLanguage -> "zh"
        vendor == TranslationVendor.Tencent && tag == "zh-Hant" -> "zh-tw"
        else -> tag
    }

    private fun languageName(tag: String): String = Locale.forLanguageTag(tag).getDisplayName(Locale.ENGLISH)

    private fun escapeHtml(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

    private fun decodeHtml(text: String): String = text.replace(Regex("""&#(?:0*39);|&#x0*27;""", RegexOption.IGNORE_CASE), "'").replace("&quot;", "\"")
        .replace("&gt;", ">").replace("&lt;", "<").replace("&amp;", "&")
}
