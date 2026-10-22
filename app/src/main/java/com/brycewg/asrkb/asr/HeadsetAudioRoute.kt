/**
 * 耳机麦克风路由（仅在录音窗口内生效）。
 *
 * 归属模块：asr
 *
 * API 31+ 使用 `AudioManager.setCommunicationDevice` 把通信音频切到蓝牙/有线耳机；
 * 旧版本使用经典 SCO（`startBluetoothSco`）。两者都会等待系统确认或失败后再返回，
 * 调用方据此决定 AudioRecord 是否使用 VOICE_COMMUNICATION 采集。
 */
package com.brycewg.asrkb.asr

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.store.debug.DebugLogManager
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

internal class HeadsetAudioRoute(context: Context) {
    companion object {
        private const val TAG = "HeadsetAudioRoute"

        /** setCommunicationDevice 后等待路由生效的上限。 */
        private const val COMM_DEVICE_WAIT_MS = 2000L

        /** 旧版 SCO 从 startBluetoothSco 到 CONNECTED 的上限。 */
        private const val SCO_WAIT_MS = 2500L
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** 选中或复用的耳机设备；即使路由失败也可能非空，用于诊断。 */
    var selectedDevice: AudioDeviceInfo? = null
        private set

    /** 通信路由是否已受理（setCommunicationDevice 返回 true 或 SCO 已连接）。 */
    var activated: Boolean = false
        private set

    /** 路由是否走通信路径（决定 AudioRecord 是否使用 VOICE_COMMUNICATION）。 */
    var viaCommunicationPath: Boolean = false
        private set

    var prepareElapsedMs: Long = 0L
        private set

    var failureReason: String? = null
        private set

    private var commDeviceSet = false
    private var ownedCommunicationDeviceId: Int? = null
    private var lastReconnectAtMs = 0L
    private var commListener: AudioManager.OnCommunicationDeviceChangedListener? = null
    private var scoStarted = false
    private var scoWasOnBefore = false
    private var audioModeChanged = false
    private var previousAudioMode: Int = AudioManager.MODE_NORMAL
    private var released = false

    /**
     * 选择并激活耳机路由。按优先级逐个尝试候选设备（BLE 耳机 > 经典 SCO > 有线耳机）；
     * 全部失败时返回 false，调用方应继续使用手机麦克风录音。
     */
    suspend fun prepare(): Boolean {
        if (released) return false
        val startedAtMs = SystemClock.elapsedRealtime()
        try {
            val targets = selectTargetDevices()
            if (targets.isEmpty()) {
                if (failureReason == null) failureReason = "no_headset"
                return false
            }
            for (target in targets) {
                selectedDevice = target
                val routeReady = when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                        activateCommunicationDevice(target)
                    }
                    target.type == AudioDeviceInfo.TYPE_WIRED_HEADSET -> {
                        // 旧版有线耳机没有通信设备 API，靠 AudioRecord.setPreferredDevice 直接选输入设备。
                        viaCommunicationPath = false
                        true
                    }
                    else -> activateLegacySco()
                }
                if (routeReady) {
                    activated = true
                    failureReason = null
                    return true
                }
                // 换下一个候选前清掉本次尝试留下的通信路径标记，避免状态串到下一次尝试。
                viaCommunicationPath = false
                scoStarted = false
            }
            if (failureReason == null) failureReason = "route_failed"
            return false
        } catch (t: Throwable) {
            // 取消必须继续向上传播，否则停录会卡在最长 2.5s 的 SCO 等待里。
            if (t is CancellationException) throw t
            failureReason = "exception"
            Log.w(TAG, "prepare failed", t)
            return false
        } finally {
            prepareElapsedMs = SystemClock.elapsedRealtime() - startedAtMs
        }
    }

    /**
     * 释放路由。可重复调用；只恢复本类真正改动过的状态。
     */
    fun release() {
        if (released) return
        released = true
        commListener?.let { listener ->
            try {
                audioManager.removeOnCommunicationDeviceChangedListener(listener)
            } catch (t: Throwable) {
                Log.w(TAG, "removeOnCommunicationDeviceChangedListener failed", t)
            }
        }
        commListener = null
        if (commDeviceSet) {
            try {
                if (audioManager.communicationDevice?.id == ownedCommunicationDeviceId) {
                    audioManager.clearCommunicationDevice()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "clear owned communication device failed", t)
            }
            commDeviceSet = false
            ownedCommunicationDeviceId = null
        }
        if (scoStarted && !scoWasOnBefore) {
            stopSco()
        }
        scoStarted = false
        if (audioModeChanged) {
            try {
                audioManager.mode = previousAudioMode
            } catch (t: Throwable) {
                Log.w(TAG, "restore audio mode failed", t)
            }
            audioModeChanged = false
        }
        selectedDevice = null
        activated = false
        viaCommunicationPath = false
    }

    /**
     * 按优先级返回候选耳机设备；API 31+ 额外包含 BLE 耳机。查询失败的原因写入 [failureReason]。
     */
    private fun selectTargetDevices(): List<AudioDeviceInfo> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val candidates = try {
                audioManager.availableCommunicationDevices
            } catch (se: SecurityException) {
                Log.w(TAG, "BLUETOOTH_CONNECT not granted or unavailable when listing comm devices", se)
                failureReason = "permission"
                return emptyList()
            } catch (t: Throwable) {
                Log.w(TAG, "getAvailableCommunicationDevices failed", t)
                return emptyList()
            }
            return candidates
                .filter { it.isHeadsetType() }
                .sortedBy { headsetPriority(it.type) }
        }
        val inputs = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        } catch (t: Throwable) {
            Log.w(TAG, "getDevices(GET_DEVICES_INPUTS) failed", t)
            emptyList()
        }
        // 旧版看不到 BLE 耳机；有线耳机作为第二候选，SCO 不可用时仍能路由到有线麦克风。
        return listOfNotNull(
            inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO },
            inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
        )
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun headsetPriority(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BLE_HEADSET -> 0
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 1
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> 2
        else -> 3
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun activateCommunicationDevice(target: AudioDeviceInfo): Boolean {
        // 系统或其他应用已经切到某个耳机时直接复用，避免把通话中的路由抢走。
        val existing = try {
            audioManager.communicationDevice
        } catch (t: Throwable) {
            Log.w(TAG, "getCommunicationDevice failed", t)
            null
        }
        if (existing != null && existing.isHeadsetType()) {
            selectedDevice = existing
            viaCommunicationPath = true
            return true
        }

        val setOk = try {
            audioManager.setCommunicationDevice(target)
        } catch (se: SecurityException) {
            Log.w(TAG, "setCommunicationDevice denied by BLUETOOTH_CONNECT", se)
            failureReason = "permission"
            false
        } catch (t: Throwable) {
            Log.w(TAG, "setCommunicationDevice failed", t)
            failureReason = "set_failed"
            false
        }
        if (!setOk) return false

        commDeviceSet = true
        ownedCommunicationDeviceId = target.id
        viaCommunicationPath = true

        val confirmed = CompletableDeferred<Unit>()
        val executor = Executor { runnable ->
            try {
                runnable.run()
            } catch (t: Throwable) {
                Log.w(TAG, "comm device listener error", t)
            }
        }
        val listener = AudioManager.OnCommunicationDeviceChangedListener { device ->
            if (device != null && device.id == ownedCommunicationDeviceId) {
                confirmed.complete(Unit)
            } else if (confirmed.isCompleted) {
                DebugLogManager.logBase(
                    category = "audio",
                    event = if (device == null) "headset_route_lost" else "headset_route_changed",
                    data = mapOf(
                        "expected_type" to target.type,
                        "expected_name" to target.productName,
                        "actual_type" to device?.type,
                        "actual_name" to device?.productName
                    )
                )
                reconnectCommunicationDevice()
            }
        }
        commListener = listener
        try {
            audioManager.addOnCommunicationDeviceChangedListener(executor, listener)
        } catch (t: Throwable) {
            Log.w(TAG, "addOnCommunicationDeviceChangedListener failed", t)
        }

        // setCommunicationDevice 可能同步生效，先查一次避免不必要的等待。
        val current = try {
            audioManager.communicationDevice
        } catch (t: Throwable) {
            Log.w(TAG, "getCommunicationDevice after set failed", t)
            null
        }
        if (current != null && current.id == target.id) {
            confirmed.complete(Unit)
            return true
        }

        val routeReady = withTimeoutOrNull(COMM_DEVICE_WAIT_MS) { confirmed.await() } != null
        if (!routeReady) {
            Log.w(TAG, "Communication device not confirmed in ${COMM_DEVICE_WAIT_MS}ms")
        }
        // setCommunicationDevice 已受理即视为激活：AudioRecord 用 VOICE_COMMUNICATION 等待系统完成切换。
        return true
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun reconnectCommunicationDevice() {
        if (released || !commDeviceSet) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastReconnectAtMs < 500L) return
        lastReconnectAtMs = now

        val target = selectTargetDevices().firstOrNull() ?: return
        if (target.id == audioManager.communicationDevice?.id) return

        val setOk = try {
            audioManager.setCommunicationDevice(target)
        } catch (t: Throwable) {
            Log.w(TAG, "communication device reconnect failed", t)
            false
        }
        if (setOk) {
            selectedDevice = target
            ownedCommunicationDeviceId = target.id
            DebugLogManager.logBase(
                category = "audio",
                event = "headset_route_reconnected",
                data = mapOf("device_type" to target.type)
            )
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun activateLegacySco(): Boolean {
        val available = try {
            audioManager.isBluetoothScoAvailableOffCall
        } catch (t: Throwable) {
            Log.w(TAG, "isBluetoothScoAvailableOffCall failed", t)
            false
        }
        if (!available) {
            failureReason = "sco_unavailable"
            return false
        }
        viaCommunicationPath = true
        scoWasOnBefore = isScoOn()
        if (scoWasOnBefore) return true

        applyCommunicationMode()
        scoStarted = startScoAndAwaitConnected()
        if (!scoStarted) {
            // 超时后 SCO 仍可能稍后才连上，主动关闭，避免耳机停在通话模式、音乐被降质。
            stopSco()
            failureReason = "sco_not_connected"
            return false
        }
        return true
    }

    private fun applyCommunicationMode() {
        val currentMode = try {
            audioManager.mode
        } catch (t: Throwable) {
            Log.w(TAG, "read audio mode failed", t)
            AudioManager.MODE_NORMAL
        }
        if (currentMode == AudioManager.MODE_IN_COMMUNICATION) return
        previousAudioMode = currentMode
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioModeChanged = true
        } catch (t: Throwable) {
            Log.w(TAG, "set MODE_IN_COMMUNICATION failed", t)
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun startScoAndAwaitConnected(): Boolean {
        val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        var receiver: BroadcastReceiver? = null
        return try {
            withTimeoutOrNull(SCO_WAIT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val broadcastReceiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) {
                            if (intent?.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return
                            when (
                                intent.getIntExtra(
                                    AudioManager.EXTRA_SCO_AUDIO_STATE,
                                    AudioManager.SCO_AUDIO_STATE_ERROR
                                )
                            ) {
                                AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                                    if (continuation.isActive) continuation.resume(true)
                                }
                                AudioManager.SCO_AUDIO_STATE_ERROR -> {
                                    if (continuation.isActive) continuation.resume(false)
                                }
                            }
                        }
                    }
                    receiver = broadcastReceiver
                    try {
                        ContextCompat.registerReceiver(
                            appContext,
                            broadcastReceiver,
                            filter,
                            ContextCompat.RECEIVER_NOT_EXPORTED
                        )
                    } catch (t: Throwable) {
                        Log.w(TAG, "register SCO receiver failed", t)
                    }
                    try {
                        if (!audioManager.isBluetoothScoOn) audioManager.startBluetoothSco()
                    } catch (t: Throwable) {
                        Log.w(TAG, "startBluetoothSco failed", t)
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
            } ?: false
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "startScoAndAwaitConnected failed", t)
            false
        } finally {
            try {
                receiver?.let { appContext.unregisterReceiver(it) }
            } catch (_: Throwable) {
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun isScoOn(): Boolean = try {
        audioManager.isBluetoothScoOn
    } catch (t: Throwable) {
        Log.w(TAG, "query isBluetoothScoOn failed", t)
        false
    }

    @Suppress("DEPRECATION")
    private fun stopSco() {
        try {
            audioManager.stopBluetoothSco()
        } catch (t: Throwable) {
            Log.w(TAG, "stopBluetoothSco failed", t)
        }
    }

    private fun AudioDeviceInfo.isHeadsetType(): Boolean = type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
        type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET)
}
