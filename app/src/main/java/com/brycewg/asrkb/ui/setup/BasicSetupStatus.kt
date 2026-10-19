/**
 * 基础设置完成态：首页进度和一键设置下一步读同一份当前系统状态。
 *
 * 归属模块：ui/setup
 */
package com.brycewg.asrkb.ui.setup

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.ime.AsrKeyboardService
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.AsrAccessibilityService
import com.brycewg.asrkb.ui.floating.floatingInputNeedsAccessibility

private const val TAG = "BasicSetupStatus"

/**
 * 基础设置里还需要用户动手的一步，顺序与一键设置的执行顺序一致。
 *
 * 只表达“缺什么”，文案由 UI 层映射到 string 资源，避免本文件依赖资源。
 */
internal enum class BasicSetupStep {
    EnableIme,
    Microphone,
    Overlay,
    Notification,
    Accessibility,
    SwitchIme
}

internal data class BasicSetupStatus(
    val imeEnabled: Boolean,
    val imeCurrent: Boolean,
    val microphoneGranted: Boolean,
    val notificationGranted: Boolean,
    val overlayRequired: Boolean,
    val overlayGranted: Boolean,
    val accessibilityRequired: Boolean,
    val accessibilityGranted: Boolean
) {
    val notificationApplicable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    val requiredPermissionsGranted: Boolean
        get() = microphoneGranted &&
            (!overlayRequired || overlayGranted) &&
            (!accessibilityRequired || accessibilityGranted)

    val optionalPermissionMissing: Boolean
        get() = notificationApplicable && !notificationGranted

    /**
     * 首页进度展示用的完整清单，在必需项之外把通知权限也算进来，让用户知道还有一项可选待办。
     *
     * 是否算作“设置完成”看这里是否全为 true；但一键设置是否会跳过权限步骤看
     * [requiredPermissionsGranted]，通知权限不会阻塞核心流程，两者刻意不同。
     */
    fun progressChecks(): List<Boolean> = buildList {
        add(imeEnabled)
        add(imeCurrent)
        add(microphoneGranted)
        if (notificationApplicable) add(notificationGranted)
        if (overlayRequired) add(overlayGranted)
        if (accessibilityRequired) add(accessibilityGranted)
    }

    /**
     * 下一步该做什么；返回 null 表示必需项已齐。
     *
     * 顺序与一键设置的执行顺序保持一致（先启用输入法，再补权限，最后切当前输入法），
     * 这样首页提示和点进一键设置后实际发生的动作不会互相矛盾。
     */
    fun nextStep(): BasicSetupStep? = when {
        !imeEnabled -> BasicSetupStep.EnableIme
        !microphoneGranted -> BasicSetupStep.Microphone
        overlayRequired && !overlayGranted -> BasicSetupStep.Overlay
        optionalPermissionMissing -> BasicSetupStep.Notification
        accessibilityRequired && !accessibilityGranted -> BasicSetupStep.Accessibility
        !imeCurrent -> BasicSetupStep.SwitchIme
        else -> null
    }

    companion object {
        private val observedSecureSettings = listOf(
            Settings.Secure.ENABLED_INPUT_METHODS,
            Settings.Secure.DEFAULT_INPUT_METHOD,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )

        fun read(context: Context, prefs: Prefs): BasicSetupStatus {
            val floatingEnabled = prefs.floatingAsrEnabled
            val overlayRequired = floatingEnabled
            val accessibilityRequired = floatingInputNeedsAccessibility(
                floatingEnabled = floatingEnabled,
                volumeKeyEnabled = prefs.volumeKeyRecordingEnabled,
                imeBridgeEnabled = prefs.floatingImeBridgeEnabled,
                shakeRecordingEnabled = prefs.shakeRecordingEnabled
            )
            return BasicSetupStatus(
                imeEnabled = isOurImeEnabled(context),
                imeCurrent = isOurImeCurrent(context),
                microphoneGranted = hasMicrophonePermission(context),
                notificationGranted = hasNotificationPermission(context),
                overlayRequired = overlayRequired,
                overlayGranted = hasOverlayPermission(context),
                accessibilityRequired = accessibilityRequired,
                accessibilityGranted = isAccessibilityServiceEnabled(context)
            )
        }

        /**
         * 输入法启用/切换、无障碍列表都写在 Secure 设置里。
         * 输入法选择器只抢窗口焦点，不会让设置页走 ON_RESUME，所以进度不能只靠进入页面时重读。
         * 返回的函数用于注销观察。
         */
        fun observeSystemChanges(context: Context, onChange: () -> Unit): () -> Unit {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    onChange()
                }
            }
            val resolver = context.applicationContext.contentResolver
            observedSecureSettings.forEach { key ->
                resolver.registerContentObserver(
                    Settings.Secure.getUriFor(key),
                    false,
                    observer
                )
            }
            return { resolver.unregisterContentObserver(observer) }
        }
    }
}

/**
 * 系统设置和 [com.brycewg.asrkb.BootReceiver] 都把组件写成 [ComponentName.flattenToString]，
 * 再用冒号拼进启用列表。旧系统上这个字符串是短类名（`包名/.ui.AsrAccessibilityService`），
 * 新系统上是全类名。 [ComponentName.unflattenFromString] 两种都还原成同一个组件。
 * 不用无障碍服务的进程内实例，那个只表示当前是否已绑定。
 */
internal fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expected = ComponentName(context, AsrAccessibilityService::class.java)
    val enabledServicesSetting = readEnabledAccessibilityServices(context) ?: return false
    return enabledServicesSetting.split(':').any { token ->
        ComponentName.unflattenFromString(token) == expected
    }
}

internal fun accessibilityMismatchDiagnostics(context: Context): Map<String, Any?> {
    val expected = ComponentName(context, AsrAccessibilityService::class.java)
    val enabledServicesSetting = readEnabledAccessibilityServices(context)
        ?: return mapOf("readFailed" to true)
    val tokens = enabledServicesSetting.split(':').filter { it.isNotEmpty() }
    val ownPackagePrefix = expected.packageName + "/"
    val ownTokens = tokens.filter { it.startsWith(ownPackagePrefix) }
    val parsedOwn = ownTokens.mapNotNull { ComponentName.unflattenFromString(it) }
    return mapOf(
        "readFailed" to false,
        "tokenCount" to tokens.size,
        "ownTokenCount" to ownTokens.size,
        "ownParsedCount" to parsedOwn.size,
        "containsOwnPackage" to enabledServicesSetting.contains(ownPackagePrefix),
        "ownClasses" to parsedOwn.joinToString(",") { it.className },
        "granted" to parsedOwn.any { it == expected }
    )
}

private fun readEnabledAccessibilityServices(context: Context): String? = try {
    Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    )
} catch (e: Exception) {
    Log.e(TAG, "Failed to read enabled accessibility services", e)
    null
}

private fun hasMicrophonePermission(context: Context): Boolean = ContextCompat.checkSelfPermission(
    context,
    Manifest.permission.RECORD_AUDIO
) == PackageManager.PERMISSION_GRANTED

private fun hasOverlayPermission(context: Context): Boolean = Settings.canDrawOverlays(context)

private fun hasNotificationPermission(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED
} else {
    true
}

private fun isOurImeEnabled(context: Context): Boolean {
    val imm = try {
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    } catch (e: Exception) {
        Log.e(TAG, "Failed to get InputMethodManager", e)
        return false
    }
    val enabledList = try {
        imm.enabledInputMethodList
    } catch (e: Exception) {
        Log.e(TAG, "Failed to get enabled IME list", e)
        null
    }
    if (enabledList?.any { it.packageName == context.packageName } == true) return true
    return try {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_INPUT_METHODS
        )
        val ids = ourImeIdCandidates(context)
        ids.any { enabled?.contains(it) == true } ||
            (enabled?.split(':')?.any { it.startsWith(context.packageName) } == true)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to check IME enabled via Settings", e)
        false
    }
}

internal fun isOurImeCurrent(context: Context): Boolean = try {
    val current = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.DEFAULT_INPUT_METHOD
    )
    current != null && ourImeIdCandidates(context).contains(current)
} catch (e: Exception) {
    Log.e(TAG, "Failed to check current IME", e)
    false
}

private fun ourImeIdCandidates(context: Context): Set<String> {
    val component = ComponentName(context, AsrKeyboardService::class.java)
    return setOf(component.flattenToShortString(), component.flattenToString())
}
