package com.brycewg.asrkb.asr

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import io.github.jaredmdobson.concentus.OpusApplication
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

/**
 * 豆包输入法逆向双工流式 ASR 引擎（WebSocket + Protobuf + 10ms Opus）。
 *
 * 无需手动配置密钥，首次使用时自动向字节设备注册接口申请匿名 device_id / install_id 并本地缓存。
 *
 * 归属模块：asr
 */
internal class DoubaoImeStreamAsrEngine(
    context: Context,
    scope: CoroutineScope,
    prefs: Prefs,
    listener: StreamingAsrEngine.Listener,
    httpClient: OkHttpClient? = null,
    externalPcmMode: Boolean = false
) : BaseOpusStreamAsrEngine(
    context = context,
    scope = scope,
    prefs = prefs,
    listener = listener,
    externalPcmMode = externalPcmMode,
    chunkMillis = CHUNK_MILLIS,
    maxPrebufferMs = MAX_PREBUFFER_MS
) {

    override val logTag: String = TAG

    private val client: OkHttpClient = httpClient ?: OkHttpClient.Builder()
        .addInterceptor(ApiLogInterceptor())
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    override suspend fun runSession(audioChan: Channel<ByteArray>) {
        val identity = try {
            DoubaoImeIdentityStore.ensureIdentity(context, client)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to ensure Doubao IME device identity", t)
            running.set(false)
            if (!cancelled.get()) {
                listener.onError(
                    context.getString(
                        R.string.error_recognize_failed_with_reason,
                        t.message ?: "device registration failed"
                    )
                )
            }
            return
        }

        if (cancelled.get()) return

        val taskId = UUID.randomUUID().toString()
        val url = buildString {
            append(WS_ENDPOINT)
            append("?device_id=").append(identity.deviceId)
            append("&aid=").append(AID)
            if (identity.installId.isNotBlank()) {
                append("&iid=").append(identity.installId)
            }
        }

        val meta = ApiCallLogger.meta(
            category = "ASR",
            vendor = "doubao_ime",
            model = MODEL_NAME,
            requestStructure = "WebSocket protobuf duplex; 10ms speech_opus frames"
        )
        val request = Request.Builder()
            .url(url)
            .header("proto-version", "v2")
            .header("sdk-version", "2")
            .header("x-custom-keepalive", "true")
            .tag(ApiLogMeta::class.java, meta)
            .build()
        val apiLogSession = ApiCallLogger.startWebSocket(request, meta)

        val openSignal = CompletableDeferred<Unit>()
        val finalSignal = CompletableDeferred<Unit>()
        val terminalDelivered = AtomicBoolean(false)
        val wsClosed = AtomicBoolean(false)
        val committedText = StringBuilder()

        val latestTextRef = AtomicReference("")
        var inboundFrames = 0

        fun deliverError(message: String) {
            if (cancelled.get()) return
            if (terminalDelivered.compareAndSet(false, true)) {
                listener.onError(message)
            }
        }

        fun deliverFinal(text: String) {
            if (cancelled.get()) return
            if (terminalDelivered.compareAndSet(false, true)) {
                listener.onFinal(text)
            }
        }

        val ws = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (cancelled.get()) {
                        webSocket.close(1000, "cancelled")
                        return
                    }
                    try {
                        webSocket.send(
                            DoubaoImeProtocol.encodeRequest(
                                namespace = NAMESPACE,
                                event = "StartTask",
                                taskId = taskId,
                                appkey = APP_KEY
                            ).toByteString()
                        )
                        val startPayload = JSONObject().apply {
                            put(
                                "audio_info",
                                JSONObject().apply {
                                    put("sample_rate", sampleRate)
                                    put("channel", 1)
                                    put("format", CODEC)
                                }
                            )
                            put(
                                "extra",
                                JSONObject().apply {
                                    put("enable_punctuation", true)
                                    put("enable_asr_twopass", true)
                                    put("enable_asr_threepass", true)
                                    put("end_smooth_window_ms", 800)
                                    put("did", identity.deviceId)
                                    put("app_name", APP_NAME)
                                    put("app_version", APP_VERSION)
                                }
                            )
                        }.toString()
                        webSocket.send(
                            DoubaoImeProtocol.encodeRequest(
                                namespace = NAMESPACE,
                                event = "StartSession",
                                taskId = taskId,
                                appkey = APP_KEY,
                                payload = startPayload
                            ).toByteString()
                        )
                        ready.set(true)
                        openSignal.complete(Unit)
                    } catch (t: Throwable) {
                        openSignal.completeExceptionally(t)
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (cancelled.get()) return
                    val resp = try {
                        DoubaoImeProtocol.decodeResponse(bytes.toByteArray())
                    } catch (t: Throwable) {
                        Log.w(TAG, "Failed to parse protobuf frame", t)
                        return
                    }
                    if (resp.taskId.isNotEmpty() && resp.taskId != taskId) return
                    inboundFrames++
                    when (resp.event) {
                        "Ping" -> {
                            webSocket.send(
                                DoubaoImeProtocol.encodeRequest(
                                    namespace = NAMESPACE,
                                    event = "Pong",
                                    taskId = taskId,
                                    appkey = APP_KEY
                                ).toByteString()
                            )
                            return
                        }
                        "Pong" -> return
                        "SessionFailed", "TaskFailed" -> {
                            val detail = "${resp.event}: ${resp.statusCode} ${resp.statusText}".trim()
                            apiLogSession.complete(
                                success = false,
                                code = resp.statusCode,
                                error = detail
                            )
                            deliverError(
                                context.getString(R.string.error_recognize_failed_with_reason, detail)
                            )
                            wsClosed.set(true)
                            finalSignal.complete(Unit)
                            return
                        }
                    }

                    if (resp.payload.isNotEmpty()) {
                        val parsed = parseResultPayload(resp.payload)
                        if (parsed != null && parsed.text.isNotEmpty()) {
                            val totalText = committedText.toString() + parsed.text
                            if (totalText != latestTextRef.get()) {
                                latestTextRef.set(totalText)
                                if (running.get() && !cancelled.get()) {
                                    listener.onPartial(totalText)
                                }
                            }
                            if (parsed.isVadFinished) {
                                committedText.append(parsed.text)
                            }
                        }
                    }

                    if (resp.event == "SessionFinished" || resp.event == "TaskFinished") {
                        if (resp.event != "TaskFinished") {
                            try {
                                webSocket.send(
                                    DoubaoImeProtocol.encodeRequest(
                                        namespace = NAMESPACE,
                                        event = "FinishTask",
                                        taskId = taskId,
                                        appkey = APP_KEY
                                    ).toByteString()
                                )
                            } catch (_: Throwable) { }
                        }
                        val finalText = latestTextRef.get()
                        apiLogSession.noteResponse(
                            "frames=$inboundFrames; event=${resp.event}; chars=${finalText.length}"
                        )
                        apiLogSession.complete(success = true)
                        deliverFinal(finalText)
                        wsClosed.set(true)
                        finalSignal.complete(Unit)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    wsClosed.set(true)
                    try {
                        webSocket.close(code, reason)
                    } catch (_: Throwable) { }
                    val currentText = latestTextRef.get()
                    if (!terminalDelivered.get()) {
                        apiLogSession.complete(
                            success = currentText.isNotEmpty(),
                            code = code,
                            error = reason
                        )
                        if (currentText.isNotEmpty()) {
                            deliverFinal(currentText)
                        }
                    }
                    finalSignal.complete(Unit)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    wsClosed.set(true)
                    if (!openSignal.isCompleted) {
                        openSignal.completeExceptionally(t)
                    }
                    val currentText = latestTextRef.get()
                    if (!terminalDelivered.get() && !cancelled.get()) {
                        apiLogSession.failure(
                            code = response?.code ?: 0,
                            error = response?.message ?: t.message.orEmpty()
                        )
                        if (currentText.isNotEmpty()) {
                            deliverFinal(currentText)
                        } else {
                            deliverError(
                                context.getString(
                                    R.string.error_recognize_failed_with_reason,
                                    t.message ?: "network error"
                                )
                            )
                        }
                    }
                    running.set(false)
                    cancelAudioCapture()
                    finalSignal.complete(Unit)
                }
            }
        )
        activeWs = ws

        try {
            val opened = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                openSignal.await()
                true
            } ?: false
            if (!opened) {
                throw IllegalStateException("Doubao IME WebSocket connect timeout")
            }

            val opusEncoder = RawOpusFrameEncoder(
                sampleRate = sampleRate,
                channels = 1,
                application = OpusApplication.OPUS_APPLICATION_AUDIO,
                bitrate = OPUS_BITRATE,
                useVbr = false,
                frameSamples = OPUS_FRAME_SAMPLES
            )
            var sequence = 0L

            fun sendOpusFrame(opusPacket: ByteArray) {
                if (wsClosed.get() || cancelled.get()) return
                sequence++
                val payload = "{\"extra\":{},\"timestamp_ms\":${System.currentTimeMillis()}}"
                ws.send(
                    DoubaoImeProtocol.encodeRequest(
                        namespace = NAMESPACE,
                        event = "TaskRequest",
                        taskId = taskId,
                        appkey = "",
                        payload = payload,
                        audio = opusPacket,
                        seqId = sequence
                    ).toByteString()
                )
            }

            drainPrebuffer()?.forEach { pcm ->
                if (!wsClosed.get() && !cancelled.get()) {
                    opusEncoder.feed(pcm, ::sendOpusFrame)
                }
            }

            while (!wsClosed.get() && !cancelled.get()) {
                val pcm = audioChan.receiveCatching().getOrNull() ?: break
                opusEncoder.feed(pcm, ::sendOpusFrame)
            }

            if (!wsClosed.get() && !cancelled.get()) {
                opusEncoder.flush(::sendOpusFrame)
                ws.send(
                    DoubaoImeProtocol.encodeRequest(
                        namespace = NAMESPACE,
                        event = "FinishSession",
                        taskId = taskId,
                        appkey = APP_KEY
                    ).toByteString()
                )
                withTimeoutOrNull(FINISH_GRACE_MS) {
                    finalSignal.await()
                }
                if (!terminalDelivered.get() && !cancelled.get()) {
                    apiLogSession.complete(success = true)
                    deliverFinal(latestTextRef.get())
                }
            }
        } catch (t: Throwable) {
            if (!cancelled.get()) {
                Log.e(TAG, "Doubao IME streaming error", t)
                apiLogSession.failure(code = 0, error = t.message.orEmpty())
                deliverError(
                    context.getString(
                        R.string.error_recognize_failed_with_reason,
                        t.message ?: ""
                    )
                )
            }
        } finally {
            ready.set(false)
            running.set(false)
            try {
                ws.close(1000, "done")
            } catch (_: Throwable) { }
            if (activeWs === ws) {
                activeWs = null
            }
        }
    }

    private data class ParsedPayload(val text: String, val isVadFinished: Boolean)

    private fun parseResultPayload(payload: String): ParsedPayload? = try {
        val obj = JSONObject(payload)
        val results = obj.optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val first = results.optJSONObject(0) ?: return null
        ParsedPayload(
            text = first.optString("text", "").trim(),
            isVadFinished = first.optBoolean("is_vad_finished", false)
        )
    } catch (_: Throwable) {
        null
    }

    companion object {
        private const val TAG = "DoubaoImeStreamAsrEngine"
        private const val WS_ENDPOINT = "wss://frontier-audio-ime-ws.doubao.com/ocean/api/v1/ws"
        private const val APP_KEY = "OrnqKvSSrs"
        private const val NAMESPACE = "ASR"
        private const val AID = "401734"
        private const val APP_NAME = "oime"
        private const val APP_VERSION = "1.5.1"
        private const val MODEL_NAME = "doubao_ime_streaming"
        private const val CODEC = "speech_opus"
        private const val CHUNK_MILLIS = 100
        private const val MAX_PREBUFFER_MS = 3000
        private const val OPUS_BITRATE = 32000
        private const val OPUS_FRAME_SAMPLES = 160 // 10ms @ 16kHz
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val FINISH_GRACE_MS = 9_000L
    }
}

internal object DoubaoImeProtocol {
    fun encodeRequest(
        namespace: String = "",
        event: String = "",
        taskId: String = "",
        appkey: String = "",
        payload: String = "",
        audio: ByteArray = ByteArray(0),
        seqId: Long = 0L
    ): ByteArray {
        val out = ByteArrayOutputStream()
        if (appkey.isNotEmpty()) out.write(LiteProtobuf.encodeString(2, appkey))
        if (namespace.isNotEmpty()) out.write(LiteProtobuf.encodeString(3, namespace))
        if (event.isNotEmpty()) out.write(LiteProtobuf.encodeString(5, event))
        if (payload.isNotEmpty()) out.write(LiteProtobuf.encodeString(6, payload))
        if (audio.isNotEmpty()) out.write(LiteProtobuf.encodeBytes(7, audio))
        if (taskId.isNotEmpty()) out.write(LiteProtobuf.encodeString(8, taskId))
        if (seqId != 0L) out.write(LiteProtobuf.encodeInt(9, seqId))
        return out.toByteArray()
    }

    data class ResponseFrame(
        val taskId: String,
        val event: String,
        val statusCode: Int,
        val statusText: String,
        val payload: String,
        val seqId: Long
    )

    fun decodeResponse(data: ByteArray): ResponseFrame {
        val fields = LiteProtobuf.parseFields(data)
        return ResponseFrame(
            taskId = LiteProtobuf.lastString(fields, 1),
            event = LiteProtobuf.lastString(fields, 4),
            statusCode = LiteProtobuf.lastInt(fields, 5).toInt(),
            statusText = LiteProtobuf.lastString(fields, 6),
            payload = LiteProtobuf.lastString(fields, 7),
            seqId = LiteProtobuf.lastInt(fields, 9)
        )
    }
}

internal object DoubaoImeIdentityStore {
    private const val FILE_NAME = "doubao_ime_credentials.json"
    private const val REGISTER_URL = "https://log.snssdk.com/service/2/device_register/"
    private val lock = Any()
    private val secureRandom = SecureRandom()

    private val TT_ENCRYPT_MAGIC = byteArrayOf(0x74, 0x63, 0x05, 0x10, 0x00, 0x00)
    private val TT_ENCRYPT_CONSTANT = hexToBytes(
        "4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524" +
            "f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58"
    )

    data class Identity(
        val deviceId: String,
        val installId: String,
        val cdid: String,
        val openudid: String,
        val clientudid: String,
        val firstInstallTime: Long
    )

    fun resetIdentity(context: Context) {
        synchronized(lock) {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) {
                file.delete()
            }
        }
    }

    fun ensureIdentity(context: Context, client: OkHttpClient): Identity {
        synchronized(lock) {
            val file = File(context.filesDir, FILE_NAME)
            val existing = readValidIdentity(file)
            if (existing != null) return existing

            val openudid = randomHex(10)
            val clientudid = UUID.randomUUID().toString()
            val cdid = UUID.randomUUID().toString()
            val firstInstallTime = System.currentTimeMillis()

            val reqId = UUID.randomUUID().toString()
            val header = JSONObject().apply {
                put("aid", 401734)
                put("app_name", "oime")
                put("channel", "official")
                put("package", "com.bytedance.android.doubaoime")
                put("app_version", "1.5.1")
                put("app_version_minor", "6")
                put("version_code", 100501006)
                put("update_version_code", 100501006)
                put("manifest_version_code", 100501006)
                put("not_request_sender", 1)
                put("release_build", "")
                put("custom", JSONObject())
                put("device_platform", "android")
                put("os", "Android")
                put("os_version", "14")
                put("os_api", 34)
                put("device_model", "Android")
                put("device_brand", "generic")
                put("device_manufacturer", "generic")
                put("cpu_abi", "arm64-v8a")
                put("language", "zh")
                put("timezone", 8)
                put("tz_name", "Asia/Shanghai")
                put("tz_offset", 28800)
                put("access", "wifi")
                put("openudid", openudid)
                put("clientudid", clientudid)
                put("cdid", cdid)
                put("git_hash", "b416399")
                put("sdk_version_code", 4030263)
                put("sdk_target_version", 30)
                put("sdk_version", "4.3.2-rc.13")
                put("sdk_flavor", "cnInner")
                put("guest_mode", 0)
                put("req_id", reqId)
                put("apk_first_install_time", firstInstallTime)
                put("is_system_app", 0)
            }
            val bodyJson = JSONObject().apply {
                put("magic_tag", "ss_app_log")
                put("_gen_time", System.currentTimeMillis())
                put("header", header)
            }.toString()

            val gzipped = ByteArrayOutputStream().use { bos ->
                GZIPOutputStream(bos).use { it.write(bodyJson.toByteArray(Charsets.UTF_8)) }
                bos.toByteArray()
            }
            val encrypted = encodeTtEncrypt(gzipped)
            val meta = ApiLogMeta(
                category = "ASR",
                vendor = "doubao_ime",
                model = "device_register",
                requestStructure = "POST ttEncrypt device_register",
                redactPath = true
            )
            val request = Request.Builder()
                .url("$REGISTER_URL?req_id=$reqId&tt_data=a")
                .post(encrypted.toRequestBody("application/octet-stream;tt-data=a".toMediaType()))
                .tag(ApiLogMeta::class.java, meta)
                .build()
            val respBody = client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    error("Device registration failed: HTTP ${resp.code}")
                }
                resp.body.string()
            }
            val respJson = JSONObject(respBody)
            val did = respJson.optString("device_id_str").ifBlank {
                respJson.opt("device_id")?.toString().orEmpty()
            }
            val iid = respJson.optString("install_id_str").ifBlank {
                respJson.opt("install_id")?.toString().orEmpty()
            }
            if (did.isBlank() || iid.isBlank() || did == "0" || iid == "0") {
                error("Device registration returned empty or zero device_id/install_id")
            }

            val identity = Identity(
                deviceId = did,
                installId = iid,
                cdid = cdid,
                openudid = openudid,
                clientudid = clientudid,
                firstInstallTime = firstInstallTime
            )
            saveIdentity(file, identity)
            return identity
        }
    }

    internal fun encodeTtEncrypt(gzipBytes: ByteArray, fixedSalt: ByteArray? = null): ByteArray {
        val salt = fixedSalt ?: ByteArray(32).also { secureRandom.nextBytes(it) }
        require(salt.size == 32) { "ttEncrypt salt must be 32 bytes" }
        val sha512 = MessageDigest.getInstance("SHA-512")
        val saltHash = sha512.digest(salt)
        sha512.reset()
        sha512.update(saltHash)
        sha512.update(TT_ENCRYPT_CONSTANT)
        val digest = sha512.digest()
        val key = digest.copyOfRange(0, 16)
        val iv = digest.copyOfRange(16, 32)

        sha512.reset()
        val payloadHash = sha512.digest(gzipBytes)
        val payload = payloadHash + gzipBytes
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(payload)
        return TT_ENCRYPT_MAGIC + salt + encrypted
    }

    private fun readValidIdentity(file: File): Identity? {
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val did = json.optString("device_id")
            val iid = json.optString("install_id")
            val cdid = json.optString("cdid")
            val openudid = json.optString("openudid")
            val clientudid = json.optString("clientudid")
            val firstInstallTime = json.optLong("first_install_time", 0L)
            val invalid = listOf(did, iid, cdid, openudid, clientudid).any { it.isBlank() } ||
                did == "0" ||
                iid == "0"
            if (invalid) {
                null
            } else {
                Identity(did, iid, cdid, openudid, clientudid, firstInstallTime)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun saveIdentity(file: File, identity: Identity) {
        val json = JSONObject().apply {
            put("device_id", identity.deviceId)
            put("install_id", identity.installId)
            put("cdid", identity.cdid)
            put("openudid", identity.openudid)
            put("clientudid", identity.clientudid)
            put("first_install_time", identity.firstInstallTime)
        }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(json.toString(), Charsets.UTF_8)
            tmp.delete()
        }
    }

    private fun randomHex(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }
}
