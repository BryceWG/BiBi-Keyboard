/**
 * fxliang fcitx5 语音输入 Provider 兼容入口。
 *
 * 归属模块：api
 */
package com.brycewg.asrkb.api

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.brycewg.asrkb.BuildConfig
import com.brycewg.asrkb.store.Prefs
import java.util.concurrent.atomic.AtomicBoolean
import org.fcitx.fcitx5.android.common.ipc.IVoiceInputCallback
import org.fcitx.fcitx5.android.common.ipc.IVoiceInputProvider
import org.fcitx.fcitx5.android.common.ipc.VoiceInputIpc

class FxliangFcitxVoiceInputProviderService : Service() {

    private val prefs by lazy { Prefs(this) }
    private val lock = Any()

    @Volatile private var configuredBundle: Bundle? = null

    @Volatile private var nextSessionId = 1

    @Volatile private var activeSession: ExternalSpeechSession? = null

    @Volatile private var activeCallbacks: FcitxExternalCallbacks? = null

    @Volatile private var activeDeathLink: CallbackDeathLink? = null

    @Volatile private var receivedPcmBytes: Long = 0L

    /** endStream 之后结果尚未交付。此时客户端的 cancel/stop 是等待超时，不能丢掉正在进行的识别。 */
    @Volatile private var awaitingRecognitionResult: Boolean = false

    override fun onBind(intent: Intent?): IBinder = binder

    private val binder = object : IVoiceInputProvider.Stub() {
        override fun isAvailable(): Boolean = synchronized(lock) {
            prefs.externalAidlEnabled && activeSession == null
        }

        override fun getPreferredConfig(): Bundle = Bundle().apply {
            putInt(VoiceInputIpc.ConfigKeys.SAMPLE_RATE, SAMPLE_RATE)
            putInt(VoiceInputIpc.ConfigKeys.BITS_PER_SAMPLE, BITS_PER_SAMPLE)
            putInt(VoiceInputIpc.ConfigKeys.CHANNELS, CHANNELS)
            putLong(VoiceInputIpc.ConfigKeys.SILENCE_MS, DEFAULT_SILENCE_MS)
        }

        override fun configure(params: Bundle?) {
            configuredBundle = params?.let { Bundle(it) }
        }

        override fun startSession(cb: IVoiceInputCallback?) {
            val callback = cb ?: return
            val session: ExternalSpeechSession
            synchronized(lock) {
                if (!prefs.externalAidlEnabled) {
                    notifyFailedSession(callback, 403, "feature disabled")
                    return
                }
                if (activeSession != null) {
                    notifyFailedSession(callback, 409, "busy")
                    return
                }
                val sid = nextSessionId++
                val callbacks = FcitxExternalCallbacks(
                    sessionId = sid,
                    remote = callback,
                    onEnded = { endedSessionId -> clearSessionIfCurrent(endedSessionId) }
                )
                session = ExternalSpeechSession(
                    sid,
                    this@FxliangFcitxVoiceInputProviderService,
                    prefs,
                    callbacks
                )
                if (!session.preparePushPcm()) {
                    notifyFailedSession(callback, VoiceInputIpc.ErrorCodes.NOT_AVAILABLE, "unsupported")
                    return
                }
                val deathLink = linkCallbackDeath(sid, callback)
                activeSession = session
                activeCallbacks = callbacks
                activeDeathLink = deathLink
                receivedPcmBytes = 0L
                awaitingRecognitionResult = false
            }
            debugLog("startSession")
            session.start()
            safeRemote("onReady") {
                callback.onReady()
            }
        }

        override fun feedAudio(pcm: ByteArray?, offset: Int, len: Int, ptsMs: Long) {
            val session = activeSession ?: return
            val callbacks = activeCallbacks ?: return
            val bytes = pcm ?: return
            debugLog("feedAudio bytes=${bytes.size} offset=$offset len=$len ptsMs=$ptsMs")
            if (offset < 0 || len < 0 || offset > bytes.size || len > bytes.size - offset) {
                callbacks.errorAndEnd(VoiceInputIpc.ErrorCodes.INVALID_AUDIO, "invalid pcm range")
                session.cancel()
                return
            }
            if (len == 0) return
            receivedPcmBytes += len.toLong()
            val frame = if (offset == 0 && len == bytes.size) {
                bytes
            } else {
                bytes.copyOfRange(offset, offset + len)
            }
            session.onPcmFrame(frame, SAMPLE_RATE, CHANNELS)
        }

        override fun endStream() {
            debugLog("endStream receivedPcmBytes=$receivedPcmBytes")
            val session = synchronized(lock) {
                if (activeSession != null) awaitingRecognitionResult = true
                activeSession
            }
            session?.stop()
        }

        override fun cancelSession() {
            debugLog("cancelSession receivedPcmBytes=$receivedPcmBytes")
            if (deferAbortUntilResult()) return
            cancelActiveSession()
        }

        override fun stopSession() {
            debugLog("stopSession receivedPcmBytes=$receivedPcmBytes")
            if (deferAbortUntilResult()) return
            cancelActiveSession()
        }
    }

    override fun onDestroy() {
        cancelActiveSession(notifyRemote = false)
        super.onDestroy()
    }

    private fun cancelActiveSession(
        notifyRemote: Boolean = true,
        expectedSessionId: Int? = null
    ) {
        val session: ExternalSpeechSession?
        val callbacks: FcitxExternalCallbacks?
        val deathLink: CallbackDeathLink?
        synchronized(lock) {
            val current = activeCallbacks
            if (expectedSessionId != null && current?.sessionId != expectedSessionId) return
            session = activeSession
            callbacks = current
            deathLink = activeDeathLink
            activeSession = null
            activeCallbacks = null
            activeDeathLink = null
            receivedPcmBytes = 0L
            awaitingRecognitionResult = false
        }
        unlinkCallbackDeath(deathLink)
        session?.cancel()
        if (notifyRemote) {
            callbacks?.endSession()
        }
    }

    private fun clearSessionIfCurrent(sessionId: Int) {
        var deathLink: CallbackDeathLink? = null
        synchronized(lock) {
            val current = activeCallbacks
            if (current == null || current.sessionId != sessionId) return
            deathLink = activeDeathLink
            activeSession = null
            activeCallbacks = null
            activeDeathLink = null
            receivedPcmBytes = 0L
            awaitingRecognitionResult = false
        }
        unlinkCallbackDeath(deathLink)
    }

    private fun deferAbortUntilResult(): Boolean = synchronized(lock) {
        awaitingRecognitionResult && activeSession != null
    }

    private fun linkCallbackDeath(
        sessionId: Int,
        callback: IVoiceInputCallback
    ): CallbackDeathLink? {
        val callbackBinder = callback.asBinder() ?: return null
        val deathRecipient = IBinder.DeathRecipient {
            debugLog("fxliang fcitx5 callback binder died sessionId=$sessionId")
            cancelActiveSession(notifyRemote = false, expectedSessionId = sessionId)
        }
        return try {
            callbackBinder.linkToDeath(deathRecipient, 0)
            CallbackDeathLink(callbackBinder, deathRecipient)
        } catch (t: Throwable) {
            Log.w(TAG, "linkToDeath failed for fxliang fcitx5 callback", t)
            null
        }
    }

    private fun unlinkCallbackDeath(deathLink: CallbackDeathLink?) {
        if (deathLink == null) return
        try {
            deathLink.binder.unlinkToDeath(deathLink.recipient, 0)
        } catch (_: NoSuchElementException) {
            // 已经因远端死亡被 Binder 移除。
        } catch (t: Throwable) {
            Log.w(TAG, "unlinkToDeath failed for fxliang fcitx5 callback", t)
        }
    }

    private fun notifyFailedSession(cb: IVoiceInputCallback, code: Int, message: String) {
        safeRemote("onError") {
            cb.onError(code, message)
        }
        safeRemote("onSessionEnded") {
            cb.onSessionEnded()
        }
    }

    private class FcitxExternalCallbacks(
        sessionId: Int,
        private val remote: IVoiceInputCallback,
        private val onEnded: (Int) -> Unit
    ) : ExternalSpeechCallbacks {
        private val ended = AtomicBoolean(false)

        @Volatile var sessionId: Int = sessionId
            private set

        override fun onState(sessionId: Int, state: Int, message: String) {
            this.sessionId = sessionId
        }

        override fun onPartial(sessionId: Int, text: String) {
            this.sessionId = sessionId
            safeRemote("onPartialResult") {
                remote.onPartialResult(text)
            }
        }

        override fun onFinal(sessionId: Int, text: String) {
            this.sessionId = sessionId
            safeRemote("onSegmentFinal") {
                remote.onSegmentFinal(text)
            }
            endSession()
        }

        override fun onError(sessionId: Int, code: Int, message: String) {
            this.sessionId = sessionId
            errorAndEnd(code, message)
        }

        override fun onAmplitude(sessionId: Int, amplitude: Float) {
            this.sessionId = sessionId
            val rms = (amplitude.coerceIn(0f, 1f) * MAX_RMS).toInt()
            safeRemote("onVolumeLevel") {
                remote.onVolumeLevel(rms)
            }
        }

        override fun onSessionDone(sessionId: Int) {
            this.sessionId = sessionId
            endSession()
        }

        fun errorAndEnd(code: Int, message: String) {
            safeRemote("onError") {
                remote.onError(code, message)
            }
            endSession()
        }

        fun endSession() {
            if (!ended.compareAndSet(false, true)) return
            safeRemote("onSessionEnded") {
                remote.onSessionEnded()
            }
            onEnded(sessionId)
        }
    }

    private data class CallbackDeathLink(
        val binder: IBinder,
        val recipient: IBinder.DeathRecipient
    )

    companion object {
        private const val TAG = "FxliangFcitxProvider"
        private const val SAMPLE_RATE = 16_000
        private const val BITS_PER_SAMPLE = 16
        private const val CHANNELS = 1
        private const val DEFAULT_SILENCE_MS = 1_000L
        private const val MAX_RMS = 32_767

        private inline fun safeRemote(name: String, block: () -> Unit) {
            try {
                block()
            } catch (t: Throwable) {
                Log.w(TAG, "$name callback failed", t)
            }
        }

        private fun debugLog(message: String) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, message)
            }
        }
    }
}
