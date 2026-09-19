package com.brycewg.asrkb.ui.floating

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.LocaleHelper
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.asr.BluetoothRouteManager
import com.brycewg.asrkb.asr.ContinuousCaptureCoordinator
import com.brycewg.asrkb.asr.ContinuousCaptureOwner
import com.brycewg.asrkb.imebridge.ImeBridgeClient
import com.brycewg.asrkb.imebridge.ImeBridgeContract
import com.brycewg.asrkb.imebridge.ImeBridgeResult
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager
import com.brycewg.asrkb.ui.floatingball.AsrSessionManager
import com.brycewg.asrkb.ui.floatingball.FloatingBallStateMachine
import com.brycewg.asrkb.ui.floatingball.FloatingBallTouchHandler
import com.brycewg.asrkb.ui.floatingball.FloatingBallViewManager
import com.brycewg.asrkb.ui.floatingball.FloatingMenuHelper
import com.brycewg.asrkb.util.HapticFeedbackHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 悬浮球语音识别服务
 *
 * 组件装配：
 * - [FloatingBallViewManager]：WindowManager 视图与动画
 * - [AsrSessionManager]：录音/识别会话
 * - [FloatingBallTouchHandler]：触摸手势识别
 * - [FloatingAsrInteractionController]：交互与菜单动作编排
 * - [FloatingVisibilityCoordinator]：可见性策略
 */
class FloatingAsrService : Service() {
    companion object {
        private const val TAG = "FloatingAsrService"
        private const val RECORDING_CHANNEL_ID = "floating_recording"
        private const val RECORDING_NOTIFICATION_ID = 4102

        const val ACTION_SHOW = "com.brycewg.asrkb.action.FLOATING_ASR_SHOW"
        const val ACTION_HIDE = "com.brycewg.asrkb.action.FLOATING_ASR_HIDE"
        const val ACTION_RESET_POSITION = "com.brycewg.asrkb.action.FLOATING_ASR_RESET_POS"
        const val ACTION_REFRESH_UI = "com.brycewg.asrkb.action.FLOATING_ASR_REFRESH_UI"
        const val ACTION_REFRESH_NOTIFICATION_LANGUAGE =
            "com.brycewg.asrkb.action.FLOATING_ASR_REFRESH_NOTIFICATION_LANGUAGE"
        const val ACTION_VOLUME_KEY_START = "com.brycewg.asrkb.action.VOLUME_KEY_RECORDING_START"
        const val ACTION_VOLUME_KEY_STOP = "com.brycewg.asrkb.action.VOLUME_KEY_RECORDING_STOP"
        const val ACTION_VOLUME_KEY_TOGGLE = "com.brycewg.asrkb.action.VOLUME_KEY_RECORDING_TOGGLE"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayWindowContext: Context
    private lateinit var displayManager: DisplayManager
    private lateinit var prefs: Prefs
    private lateinit var viewManager: FloatingBallViewManager
    private lateinit var asrSessionManager: AsrSessionManager
    private lateinit var touchHandler: FloatingBallTouchHandler
    private lateinit var visibilityCoordinator: FloatingVisibilityCoordinator
    private lateinit var overlayPermissionGate: OverlayPermissionGate
    private lateinit var notifier: UserNotifier
    private lateinit var interactionController: FloatingAsrInteractionController
    private lateinit var notificationManager: NotificationManager

    private val stateMachine = FloatingBallStateMachine()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    private var imeVisible: Boolean = false
    private var bridgeImeVisible: Boolean? = null
    private var localPreloadTriggered: Boolean = false
    private var recordingForegroundActive: Boolean = false
    private var continuousCaptureForegroundActive: Boolean = false
    private var displayListener: DisplayManager.DisplayListener? = null
    private var pendingDisplayRemapReason: String = "display_changed"
    private val displayRemapRunnable = Runnable {
        if (!::viewManager.isInitialized) return@Runnable
        try {
            viewManager.remapPositionForCurrentDisplay(pendingDisplayRemapReason)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to remap floating ball position on display change", e)
        }
    }
    private val imeBridgeClient by lazy { ImeBridgeClient(applicationContext) }

    private val hintReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                FloatingImeHints.ACTION_HINT_IME_VISIBLE -> {
                    handleAccessibilityImeVisibilityHint(true, "hint_visible")
                }
                FloatingImeHints.ACTION_HINT_IME_HIDDEN -> {
                    handleAccessibilityImeVisibilityHint(false, "hint_hidden")
                }
                ACTION_REFRESH_NOTIFICATION_LANGUAGE -> refreshRecordingNotification()
            }
        }
    }

    private val bridgeHintReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ImeBridgeContract.ACTION_IME_WINDOW_VISIBILITY_CHANGED) return
            handleBridgeImeVisibilityHint("bridge_hint", intent)
        }
    }

    override fun attachBaseContext(newBase: Context?) {
        val wrapped = newBase?.let { LocaleHelper.wrap(it) }
        super.attachBaseContext(wrapped ?: newBase)
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        displayManager = getSystemService(DisplayManager::class.java)
        overlayWindowContext = createOverlayWindowContext()
        windowManager = overlayWindowContext.getSystemService(WindowManager::class.java)
        notificationManager = getSystemService(NotificationManager::class.java)
        prefs = Prefs(this)
        notifier = UserNotifier(this, handler, TAG)
        overlayPermissionGate = OverlayPermissionGate(this, notifier, TAG)
        ensureRecordingChannel()

        viewManager = FloatingBallViewManager(overlayWindowContext, prefs, windowManager)

        val menuHelper = FloatingMenuHelper(overlayWindowContext, windowManager)
        val menuController = FloatingMenuController(menuHelper)
        interactionController = FloatingAsrInteractionController(
            context = this,
            prefs = prefs,
            viewManager = viewManager,
            menuController = menuController,
            stateMachine = stateMachine,
            notifier = notifier,
            scope = serviceScope,
            tag = TAG,
            isImeVisible = { isEffectiveImeVisible() },
            startRecordingForeground = { startRecordingForeground() },
            stopRecordingForeground = { stopRecordingForeground() }
        )
        asrSessionManager = AsrSessionManager(
            this,
            prefs,
            serviceScope,
            interactionController
        ) { enabled ->
            viewManager.setKeepScreenOn(enabled)
        }
        interactionController.asrSessionManager = asrSessionManager
        touchHandler =
            FloatingBallTouchHandler(
                overlayWindowContext,
                prefs,
                viewManager,
                windowManager,
                interactionController
            )

        registerDisplayListener()

        visibilityCoordinator = FloatingVisibilityCoordinator(
            prefs = prefs,
            stateMachine = stateMachine,
            viewManager = viewManager,
            tag = TAG,
            hasOverlayPermission = { overlayPermissionGate.hasPermission() },
            isImeVisible = { isEffectiveImeVisible() },
            isForceVisibleActive = { interactionController.isForceVisibleActive() },
            showBall = { src -> showBall(src) },
            hideBall = { hideBall() }
        )
        interactionController.applyVisibility =
            { src -> visibilityCoordinator.applyVisibility(src) }

        try {
            val filter = android.content.IntentFilter().apply {
                addAction(FloatingImeHints.ACTION_HINT_IME_VISIBLE)
                addAction(FloatingImeHints.ACTION_HINT_IME_HIDDEN)
                addAction(ACTION_REFRESH_NOTIFICATION_LANGUAGE)
            }
            ContextCompat.registerReceiver(
                /* context = */
                this,
                /* receiver = */
                hintReceiver,
                /* filter = */
                filter,
                /* flags = */
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to register hint receiver", e)
        }
        try {
            val filter = android.content.IntentFilter().apply {
                addAction(ImeBridgeContract.ACTION_IME_WINDOW_VISIBILITY_CHANGED)
            }
            ContextCompat.registerReceiver(
                this,
                bridgeHintReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to register bridge hint receiver", e)
        }

        refreshBridgeImeVisibility("service_create")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshRecordingNotification()
        scheduleDisplayRemap("service_onConfigurationChanged:${newConfig.orientation}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val enabled = try {
            prefs.floatingAsrEnabled
        } catch (_: Throwable) {
            false
        }
        Log.d(TAG, "onStartCommand: action=${intent?.action}, floatingAsrEnabled=$enabled")

        when (intent?.action) {
            ACTION_SHOW -> {
                if (enabled && !overlayPermissionGate.hasPermission()) {
                    overlayPermissionGate.showMissingPermissionToast()
                }
                refreshBridgeImeVisibility("start_action_show")
                visibilityCoordinator.applyVisibility("start_action_show")
            }
            ACTION_HIDE -> hideBall()
            ACTION_RESET_POSITION -> handleResetBallPosition()
            ACTION_REFRESH_UI -> {
                viewManager.applyBallTheme()
                viewManager.applyBallAlpha()
                viewManager.updateStateVisual(stateMachine.state, force = true)
                refreshBridgeImeVisibility("refresh_ui")
            }
            ACTION_VOLUME_KEY_START -> interactionController.onVolumeKeyStart()
            ACTION_VOLUME_KEY_STOP -> interactionController.onVolumeKeyStop()
            ACTION_VOLUME_KEY_TOGGLE -> interactionController.onVolumeKeyToggle()
            FloatingImeHints.ACTION_HINT_IME_VISIBLE -> {
                handleAccessibilityImeVisibilityHint(true, "start_hint_visible")
            }
            FloatingImeHints.ACTION_HINT_IME_HIDDEN -> {
                handleAccessibilityImeVisibilityHint(false, "start_hint_hidden")
            }
            ImeBridgeContract.ACTION_IME_WINDOW_VISIBILITY_CHANGED -> {
                handleBridgeImeVisibilityHint("start_bridge_hint", intent)
            }
            else -> visibilityCoordinator.applyVisibility("start_default")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")

        handler.removeCallbacks(displayRemapRunnable)
        displayListener?.let(displayManager::unregisterDisplayListener)
        displayListener = null

        try {
            if (::interactionController.isInitialized) interactionController.cleanup()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to cleanup interaction controller", e)
        }
        try {
            if (::notifier.isInitialized) notifier.cancel()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to cancel notifier", e)
        }
        stopRecordingForeground(force = true)

        hideBall()
        viewManager.cleanup()
        asrSessionManager.cleanup()
        touchHandler.cleanup()

        try {
            serviceScope.cancel()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to cancel service scope", e)
        }
        try {
            unregisterReceiver(hintReceiver)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to unregister receiver", e)
        }
        try {
            unregisterReceiver(bridgeHintReceiver)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to unregister bridge hint receiver", e)
        }
    }

    private fun createOverlayWindowContext(): Context {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return this
        val defaultDisplay = requireNotNull(displayManager.getDisplay(Display.DEFAULT_DISPLAY)) {
            "Default display is unavailable for the floating overlay"
        }
        return createDisplayContext(defaultDisplay).createWindowContext(
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            null
        )
    }

    private fun registerDisplayListener() {
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit

            override fun onDisplayRemoved(displayId: Int) = Unit

            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) {
                    scheduleDisplayRemap("display_onDisplayChanged:$displayId")
                }
            }
        }
        displayManager.registerDisplayListener(listener, handler)
        displayListener = listener
    }

    private fun scheduleDisplayRemap(reason: String) {
        pendingDisplayRemapReason = reason
        handler.removeCallbacks(displayRemapRunnable)
        handler.post(displayRemapRunnable)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRecordingForeground(): Boolean {
        if (recordingForegroundActive) return true
        val notification = buildRecordingNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    RECORDING_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(RECORDING_NOTIFICATION_ID, notification)
            }
            recordingForegroundActive = true
            return true
        } catch (t: RuntimeException) {
            Log.w(TAG, "Failed to enter microphone foreground state", t)
            try {
                notificationManager.cancel(RECORDING_NOTIFICATION_ID)
            } catch (cancelError: Throwable) {
                Log.w(TAG, "Failed to cancel recording notification after start failure", cancelError)
            }
            return false
        }
    }

    private fun stopRecordingForeground(force: Boolean = false) {
        if (!force && continuousCaptureForegroundActive) return
        if (!recordingForegroundActive) return
        recordingForegroundActive = false
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to stop recording foreground state", e)
        }
        try {
            notificationManager.cancel(RECORDING_NOTIFICATION_ID)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to cancel recording notification", e)
        }
    }

    private fun localizedContext(): Context = LocaleHelper.wrap(this)

    private fun refreshRecordingNotification() {
        if (!recordingForegroundActive || !::notificationManager.isInitialized) return
        try {
            notificationManager.notify(RECORDING_NOTIFICATION_ID, buildRecordingNotification())
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to refresh recording notification language", e)
        }
    }

    private fun buildRecordingNotification(): Notification {
        val localized = localizedContext()
        ensureRecordingChannel(localized)
        val openIntent = KeepAliveNotificationClick.openSettingsIntent(this)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = localized.getString(R.string.notif_floating_recording_desc)
        return NotificationCompat.Builder(localized, RECORDING_CHANNEL_ID)
            .setContentTitle(
                localized.getString(
                    R.string.notif_floating_recording_title,
                    localized.getString(R.string.app_name)
                )
            )
            .setContentText(text)
            .setSmallIcon(R.drawable.microphone)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun ensureRecordingChannel(localized: Context = localizedContext()) {
        val channel = NotificationChannel(
            RECORDING_CHANNEL_ID,
            localized.getString(R.string.notif_channel_floating_recording),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = localized.getString(R.string.notif_channel_floating_recording_desc)
        try {
            notificationManager.createNotificationChannel(channel)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to create recording channel", e)
        }
    }

    private fun showBall(src: String = "update_visibility") {
        Log.d(TAG, "showBall called: src=$src")

        if (viewManager.getBallView() != null) {
            viewManager.applyBallTheme()
            viewManager.applyBallAlpha()
            viewManager.applyBallSize()
            viewManager.updateStateVisual(stateMachine.state)
            startContinuousCaptureForBall()
            return
        }

        val touchListener = touchHandler.createTouchListener { stateMachine.isMoveMode }
        val success = viewManager.showBall(
            onClickListener = { hapticTapIfEnabled(it) },
            onTouchListener = touchListener,
            initialState = stateMachine.state
        )
        if (!success) {
            if (DebugLogManager.isRecording()) {
                DebugLogManager.log("float", "show_failed")
            }
            return
        }
        if (DebugLogManager.isRecording()) {
            DebugLogManager.log("float", "show_success")
        }

        startContinuousCaptureForBall()
        tryPreloadLocalAsrOnce()
    }

    private fun hideBall() {
        stopContinuousCaptureForBall()
        viewManager.hideBall()
        if (DebugLogManager.isRecording()) {
            DebugLogManager.log("float", "hide")
        }
    }

    private fun startContinuousCaptureForBall() {
        if (!prefs.continuousCaptureEnabled) {
            stopContinuousCaptureForBall()
            return
        }
        if (!startRecordingForeground()) {
            ContinuousCaptureCoordinator.release(ContinuousCaptureOwner.FloatingBall)
            return
        }
        continuousCaptureForegroundActive = true
        ContinuousCaptureCoordinator.acquire(ContinuousCaptureOwner.FloatingBall, this)
    }

    private fun stopContinuousCaptureForBall() {
        ContinuousCaptureCoordinator.release(ContinuousCaptureOwner.FloatingBall)
        if (!continuousCaptureForegroundActive) return
        continuousCaptureForegroundActive = false
        stopRecordingForeground(force = true)
    }

    private fun handleResetBallPosition() {
        try {
            prefs.floatingBallPosX = -1
            prefs.floatingBallPosY = -1
            prefs.floatingBallDockSide = 0
            prefs.floatingBallDockFraction = -1f
            prefs.floatingBallDockHidden = false
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to reset saved position in prefs", e)
        }

        val hasView = viewManager.getBallView() != null
        if (hasView) {
            try {
                viewManager.resetPositionToDefault()
                if (!prefs.floatingSwitcherOnlyWhenImeVisible && !isEffectiveImeVisible()) {
                    viewManager.animateHideToEdgePartialIfNeeded()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to apply default position reset on existing view", e)
            }
        } else {
            visibilityCoordinator.applyVisibility("reset_pos_no_view")
        }
    }

    private fun handleAccessibilityImeVisibilityHint(visible: Boolean, src: String) {
        imeVisible = visible
        applyImeVisibilitySideEffects(src)
    }

    private fun handleBridgeImeVisibilityHint(src: String, intent: Intent?) {
        val fallbackOnFailure = bridgeHiddenFallbackFrom(intent)
        refreshBridgeImeVisibility(src, fallbackOnFailure)
    }

    private fun refreshBridgeImeVisibility(src: String, fallbackOnFailure: Boolean? = null) {
        if (!isImeBridgeEnabled()) {
            bridgeImeVisible = null
            return
        }
        serviceScope.launch(Dispatchers.IO) {
            val result = imeBridgeClient.queryStatus(timeoutMs = 250L)
            handler.post {
                applyBridgeImeVisibilityResult(src, result, fallbackOnFailure)
            }
        }
    }

    private fun applyBridgeImeVisibilityResult(
        src: String,
        result: ImeBridgeResult,
        fallbackOnFailure: Boolean?
    ) {
        if (!::visibilityCoordinator.isInitialized) return
        bridgeImeVisible = if (result.isSuccess) {
            result.isImeWindowVisible
        } else {
            fallbackOnFailure
        }
        applyImeVisibilitySideEffects(src)
    }

    private fun bridgeHiddenFallbackFrom(intent: Intent?): Boolean? {
        if (intent?.hasExtra(ImeBridgeContract.EXTRA_IME_WINDOW_VISIBLE) != true) return null
        val visible = intent.getBooleanExtra(ImeBridgeContract.EXTRA_IME_WINDOW_VISIBLE, false)
        return if (visible) null else false
    }

    private fun applyImeVisibilitySideEffects(src: String) {
        if (!::visibilityCoordinator.isInitialized) return
        val visible = isEffectiveImeVisible()
        if (DebugLogManager.isRecording()) {
            DebugLogManager.log(
                "float",
                "hint",
                mapOf(
                    "action" to if (visible) "VISIBLE" else "HIDDEN",
                    "src" to src,
                    "a11yVisible" to imeVisible,
                    "bridgeVisible" to (bridgeImeVisible ?: "")
                )
            )
        }
        if (!visible && ::interactionController.isInitialized) {
            interactionController.stopVolumeKeyRecordingOnImeHidden()
        }
        visibilityCoordinator.applyVisibility(src)
        try {
            BluetoothRouteManager.setImeActive(this, visible)
        } catch (t: Throwable) {
            Log.w(TAG, "BluetoothRouteManager setImeActive($visible)", t)
        }
    }

    private fun isEffectiveImeVisible(): Boolean = if (isImeBridgeEnabled()) bridgeImeVisible ?: imeVisible else imeVisible

    private fun isImeBridgeEnabled(): Boolean = try {
        prefs.floatingImeBridgeEnabled
    } catch (e: Throwable) {
        Log.w(TAG, "Failed to read IME bridge preference", e)
        false
    }

    private fun hapticTapIfEnabled(view: View?) {
        HapticFeedbackHelper.performTap(this, prefs, view)
    }

    private fun tryPreloadLocalAsrOnce() {
        if (localPreloadTriggered) return

        val enabled = when (prefs.asrVendor) {
            AsrVendor.SenseVoice -> prefs.svPreloadEnabled
            AsrVendor.FunAsrNano -> prefs.fnPreloadEnabled
            AsrVendor.Qwen3Asr -> prefs.qwPreloadEnabled
            AsrVendor.Parakeet -> prefs.pkPreloadEnabled
            AsrVendor.FireRedAsr -> prefs.frPreloadEnabled
            AsrVendor.XAsr -> prefs.xAsrPreloadEnabled
            else -> false
        }
        if (!enabled) return
        if (com.brycewg.asrkb.asr.isLocalAsrPrepared(prefs)) {
            localPreloadTriggered = true
            return
        }

        localPreloadTriggered = true
        serviceScope.launch(Dispatchers.Default) {
            com.brycewg.asrkb.asr.preloadLocalAsrIfConfigured(this@FloatingAsrService, prefs)
        }
    }
}
