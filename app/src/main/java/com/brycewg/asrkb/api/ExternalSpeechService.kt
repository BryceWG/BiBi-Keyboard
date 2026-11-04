/**
 * 对外导出的语音识别服务与会话编排入口。
 *
 * 归属模块：api
 */
package com.brycewg.asrkb.api

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.aidl.SpeechConfig
import com.brycewg.asrkb.store.Prefs
import java.util.concurrent.ConcurrentHashMap

/**
 * 对外导出的语音服务（Binder 手写协议，兼容 AIDL 生成的代理）。
 * - 接口描述符需与 AIDL 一致：com.brycewg.asrkb.aidl.IExternalSpeechService。
 * - 方法顺序与 AIDL 保持一致，以匹配事务码。
 */
class ExternalSpeechService : Service() {

    private val prefs by lazy { Prefs(this) }
    private val sessions = ConcurrentHashMap<Int, ExternalSpeechSession>()
    private val callbackDeathLinks = ConcurrentHashMap<Int, CallbackDeathLink>()

    @Volatile private var nextId: Int = 1

    override fun onBind(intent: Intent?): IBinder? = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                INTERFACE_TRANSACTION -> {
                    reply?.writeString(DESCRIPTOR_SVC)
                    return true
                }
                TRANSACTION_START_SESSION -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    val cfg = if (data.readInt() !=
                        0
                    ) {
                        SpeechConfig.CREATOR.createFromParcel(data)
                    } else {
                        null
                    }
                    val cbBinder = data.readStrongBinder()
                    val cb = CallbackProxy(cbBinder)

                    // 开关与权限检查：仅要求开启外部联动
                    if (!prefs.externalAidlEnabled) {
                        safe { cb.onError(-1, 403, "feature disabled") }
                        reply?.apply {
                            writeNoException()
                            writeInt(-3)
                        }
                        return true
                    }
                    // 联通测试：当 vendorId == "mock" 时，无需录音权限，直接回调固定内容并结束
                    if (cfg?.vendorId == "mock") {
                        val sid = synchronized(this@ExternalSpeechService) { nextId++ }
                        safe { cb.onState(sid, STATE_RECORDING, "recording") }
                        safe { cb.onPartial(sid, "【联通测试中】……") }
                        safe { cb.onFinal(sid, "说点啥外部AIDL联通成功（mock）") }
                        safe { cb.onState(sid, STATE_IDLE, "final") }
                        reply?.apply {
                            writeNoException()
                            writeInt(sid)
                        }
                        return true
                    }

                    val permOk = ContextCompat.checkSelfPermission(
                        this@ExternalSpeechService,
                        android.Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!permOk) {
                        safe { cb.onError(-1, 401, "record permission denied") }
                        reply?.apply {
                            writeNoException()
                            writeInt(-4)
                        }
                        return true
                    }
                    if (sessions.values.any { it.engine?.isRunning == true }) {
                        reply?.apply {
                            writeNoException()
                            writeInt(-2)
                        }
                        return true
                    }

                    val sid = synchronized(this@ExternalSpeechService) { nextId++ }
                    val s = ExternalSpeechSession(
                        sid,
                        this@ExternalSpeechService,
                        prefs,
                        ExternalAidlCallbacks(cb, ::onSessionDone)
                    )
                    if (!s.prepare()) {
                        reply?.apply {
                            writeNoException()
                            writeInt(-3)
                        }
                        return true
                    }
                    sessions[sid] = s
                    linkCallbackDeath(sid, cbBinder)
                    s.start()
                    reply?.apply {
                        writeNoException()
                        writeInt(sid)
                    }
                    return true
                }
                TRANSACTION_STOP_SESSION -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    val sid = data.readInt()
                    sessions[sid]?.stop()
                    reply?.writeNoException()
                    return true
                }
                TRANSACTION_CANCEL_SESSION -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    val sid = data.readInt()
                    sessions.remove(sid)?.cancel()
                    unlinkCallbackDeath(sid)
                    reply?.writeNoException()
                    return true
                }
                TRANSACTION_IS_RECORDING -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    val sid = data.readInt()
                    val r = sessions[sid]?.engine?.isRunning == true
                    reply?.apply {
                        writeNoException()
                        writeInt(if (r) 1 else 0)
                    }
                    return true
                }
                TRANSACTION_IS_ANY_RECORDING -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    val r = sessions.values.any { it.engine?.isRunning == true }
                    reply?.apply {
                        writeNoException()
                        writeInt(if (r) 1 else 0)
                    }
                    return true
                }
                TRANSACTION_GET_VERSION -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    reply?.apply {
                        writeNoException()
                        writeString(com.brycewg.asrkb.BuildConfig.VERSION_NAME)
                    }
                    return true
                }
                // ================= 推送 PCM 模式 =================
                TRANSACTION_START_PCM_SESSION -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    if (data.readInt() != 0) {
                        SpeechConfig.CREATOR.createFromParcel(data)
                    }
                    val cbBinder = data.readStrongBinder()
                    val cb = CallbackProxy(cbBinder)

                    if (!prefs.externalAidlEnabled) {
                        safe { cb.onError(-1, 403, "feature disabled") }
                        reply?.apply {
                            writeNoException()
                            writeInt(-3)
                        }
                        return true
                    }
                    if (sessions.values.any { it.engine?.isRunning == true }) {
                        reply?.apply {
                            writeNoException()
                            writeInt(-2)
                        }
                        return true
                    }

                    val sid = synchronized(this@ExternalSpeechService) { nextId++ }
                    val s = ExternalSpeechSession(
                        sid,
                        this@ExternalSpeechService,
                        prefs,
                        ExternalAidlCallbacks(cb, ::onSessionDone)
                    )
                    if (!s.preparePushPcm()) {
                        reply?.apply {
                            writeNoException()
                            writeInt(-5)
                        }
                        return true
                    }
                    sessions[sid] = s
                    linkCallbackDeath(sid, cbBinder)
                    s.start()
                    reply?.apply {
                        writeNoException()
                        writeInt(sid)
                    }
                    return true
                }
                TRANSACTION_WRITE_PCM -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    val sid = data.readInt()
                    val bytes = data.createByteArray() ?: ByteArray(0)
                    val sr = data.readInt()
                    val ch = data.readInt()
                    sessions[sid]?.onPcmFrame(bytes, sr, ch)
                    reply?.writeNoException()
                    return true
                }
                TRANSACTION_FINISH_PCM -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    val sid = data.readInt()
                    sessions[sid]?.stop()
                    reply?.writeNoException()
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private class CallbackProxy(private val remote: IBinder?) {
        fun onState(sessionId: Int, state: Int, msg: String) {
            transact(CB_ON_STATE) { data ->
                data.writeInterfaceToken(DESCRIPTOR_CB)
                data.writeInt(sessionId)
                data.writeInt(state)
                data.writeString(msg)
            }
        }
        fun onPartial(sessionId: Int, text: String) {
            transact(CB_ON_PARTIAL) { data ->
                data.writeInterfaceToken(DESCRIPTOR_CB)
                data.writeInt(sessionId)
                data.writeString(text)
            }
        }
        fun onFinal(sessionId: Int, text: String) {
            transact(CB_ON_FINAL) { data ->
                data.writeInterfaceToken(DESCRIPTOR_CB)
                data.writeInt(sessionId)
                data.writeString(text)
            }
        }
        fun onError(sessionId: Int, code: Int, message: String) {
            transact(CB_ON_ERROR) { data ->
                data.writeInterfaceToken(DESCRIPTOR_CB)
                data.writeInt(sessionId)
                data.writeInt(code)
                data.writeString(message)
            }
        }
        fun onAmplitude(sessionId: Int, amp: Float) {
            transact(CB_ON_AMPLITUDE) { data ->
                data.writeInterfaceToken(DESCRIPTOR_CB)
                data.writeInt(sessionId)
                data.writeFloat(amp)
            }
        }

        private inline fun transact(code: Int, fill: (Parcel) -> Unit) {
            val b = remote ?: return
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                fill(data)
                b.transact(code, data, reply, 0)
                reply.readException()
            } catch (t: Throwable) {
                Log.w(TAG, "callback transact failed: code=$code", t)
            } finally {
                try {
                    data.recycle()
                } catch (t: Throwable) {
                    Log.w(TAG, "data.recycle failed", t)
                }
                try {
                    reply.recycle()
                } catch (t: Throwable) {
                    Log.w(TAG, "reply.recycle failed", t)
                }
            }
        }
    }

    override fun onDestroy() {
        sessions.keys.toList().forEach { sid ->
            sessions.remove(sid)?.cancel()
            unlinkCallbackDeath(sid)
        }
        super.onDestroy()
    }

    private class ExternalAidlCallbacks(
        private val cb: CallbackProxy,
        private val onDone: (Int) -> Unit
    ) : ExternalSpeechCallbacks {
        override fun onState(sessionId: Int, state: Int, message: String) {
            cb.onState(sessionId, state, message)
        }

        override fun onPartial(sessionId: Int, text: String) {
            cb.onPartial(sessionId, text)
        }

        override fun onFinal(sessionId: Int, text: String) {
            cb.onFinal(sessionId, text)
        }

        override fun onError(sessionId: Int, code: Int, message: String) {
            cb.onError(sessionId, code, message)
        }

        override fun onAmplitude(sessionId: Int, amplitude: Float) {
            cb.onAmplitude(sessionId, amplitude)
        }

        override fun onSessionDone(sessionId: Int) {
            onDone(sessionId)
        }
    }

    private data class CallbackDeathLink(
        val binder: IBinder,
        val recipient: IBinder.DeathRecipient
    )

    companion object {
        private const val TAG = "ExternalSpeechSvc"

        // 与 AIDL 生成的 Stub 保持一致的描述符与事务号
        private const val DESCRIPTOR_SVC = "com.brycewg.asrkb.aidl.IExternalSpeechService"
        private const val TRANSACTION_START_SESSION = IBinder.FIRST_CALL_TRANSACTION + 0
        private const val TRANSACTION_STOP_SESSION = IBinder.FIRST_CALL_TRANSACTION + 1
        private const val TRANSACTION_CANCEL_SESSION = IBinder.FIRST_CALL_TRANSACTION + 2
        private const val TRANSACTION_IS_RECORDING = IBinder.FIRST_CALL_TRANSACTION + 3
        private const val TRANSACTION_IS_ANY_RECORDING = IBinder.FIRST_CALL_TRANSACTION + 4
        private const val TRANSACTION_GET_VERSION = IBinder.FIRST_CALL_TRANSACTION + 5
        private const val TRANSACTION_START_PCM_SESSION = IBinder.FIRST_CALL_TRANSACTION + 6
        private const val TRANSACTION_WRITE_PCM = IBinder.FIRST_CALL_TRANSACTION + 7
        private const val TRANSACTION_FINISH_PCM = IBinder.FIRST_CALL_TRANSACTION + 8

        private const val DESCRIPTOR_CB = "com.brycewg.asrkb.aidl.ISpeechCallback"
        private const val CB_ON_STATE = IBinder.FIRST_CALL_TRANSACTION + 0
        private const val CB_ON_PARTIAL = IBinder.FIRST_CALL_TRANSACTION + 1
        private const val CB_ON_FINAL = IBinder.FIRST_CALL_TRANSACTION + 2
        private const val CB_ON_ERROR = IBinder.FIRST_CALL_TRANSACTION + 3
        private const val CB_ON_AMPLITUDE = IBinder.FIRST_CALL_TRANSACTION + 4

        private const val STATE_IDLE = 0
        private const val STATE_RECORDING = 1

        private inline fun safe(block: () -> Unit) {
            try {
                block()
            } catch (t: Throwable) {
                Log.w(TAG, "callback failed", t)
            }
        }
    }

    // 统一的会话清理入口：在 onFinal/onError 触发后移除，避免内存泄漏
    private fun onSessionDone(sessionId: Int) {
        try {
            sessions.remove(sessionId)
            unlinkCallbackDeath(sessionId)
        } catch (t: Throwable) {
            Log.w(TAG, "sessions.remove failed for id=$sessionId", t)
        }
    }

    private fun linkCallbackDeath(sessionId: Int, binder: IBinder?) {
        val callbackBinder = binder ?: return
        val deathRecipient = IBinder.DeathRecipient {
            onCallbackDied(sessionId)
        }
        val deathLink = CallbackDeathLink(callbackBinder, deathRecipient)
        callbackDeathLinks[sessionId] = deathLink
        try {
            callbackBinder.linkToDeath(deathRecipient, 0)
        } catch (t: Throwable) {
            callbackDeathLinks.remove(sessionId, deathLink)
            Log.w(TAG, "linkToDeath failed for external AIDL callback id=$sessionId", t)
        }
    }

    private fun onCallbackDied(sessionId: Int) {
        val session = sessions.remove(sessionId)
        unlinkCallbackDeath(sessionId)
        if (session == null) return
        Log.w(TAG, "external AIDL callback binder died id=$sessionId")
        session.cancel()
    }

    private fun unlinkCallbackDeath(sessionId: Int) {
        val deathLink = callbackDeathLinks.remove(sessionId) ?: return
        try {
            deathLink.binder.unlinkToDeath(deathLink.recipient, 0)
        } catch (_: NoSuchElementException) {
            // 已经因远端死亡被 Binder 移除。
        } catch (t: Throwable) {
            Log.w(TAG, "unlinkToDeath failed for external AIDL callback id=$sessionId", t)
        }
    }
}
