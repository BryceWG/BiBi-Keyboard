package com.brycewg.asrkb.ui.settings.input

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.MotionEvent
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ime.AsrKeyboardService
import com.brycewg.asrkb.store.Prefs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

class InputSettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "InputSettingsActivity"
        private const val REQ_BT_CONNECT = 2001
    }

    // 标志位：防止程序内部修改开关状态时触发监听器递归
    private var isUpdatingSwitchTrimTrailingPunct = false
    private var isUpdatingSwitchMicHaptic = false
    private var isUpdatingSwitchMicTapToggle = false
    private var isUpdatingSwitchMicSwipeUpAutoEnter = false
    private var isUpdatingSwitchAutoStartRecordingOnShow = false
    private var isUpdatingSwitchFcitx5ReturnOnSwitcher = false
    private var isUpdatingSwitchReturnPrevImeOnHide = false
    private var isUpdatingSwitchHideRecentTasks = false
    private var isUpdatingSwitchDuckMediaOnRecord = false
    private var isUpdatingSwitchHeadsetMicPriority = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_input_settings)

        // 应用 Window Insets 以适配 Android 15 边缘到边缘显示
        findViewById<View>(android.R.id.content).let { rootView ->
            com.brycewg.asrkb.ui.WindowInsetsHelper.applySystemBarsInsets(rootView)
        }

        val prefs = Prefs(this)

        val switchTrimTrailingPunct = findViewById<MaterialSwitch>(R.id.switchTrimTrailingPunct)
        val switchMicHaptic = findViewById<MaterialSwitch>(R.id.switchMicHaptic)
        val switchMicTapToggle = findViewById<MaterialSwitch>(R.id.switchMicTapToggle)
        val switchMicSwipeUpAutoEnter = findViewById<MaterialSwitch>(R.id.switchMicSwipeUpAutoEnter)
        val switchAutoStartRecordingOnShow = findViewById<MaterialSwitch>(R.id.switchAutoStartRecordingOnShow)
        val switchFcitx5ReturnOnSwitcher = findViewById<MaterialSwitch>(R.id.switchFcitx5ReturnOnSwitcher)
        val switchReturnPrevImeOnHide = findViewById<MaterialSwitch>(R.id.switchReturnPrevImeOnHide)
        val switchHideRecentTasks = findViewById<MaterialSwitch>(R.id.switchHideRecentTasks)
        val switchDuckMediaOnRecord = findViewById<MaterialSwitch>(R.id.switchDuckMediaOnRecord)
        val switchHeadsetMicPriority = findViewById<MaterialSwitch>(R.id.switchHeadsetMicPriority)
        val switchExternalImeAidl = findViewById<MaterialSwitch>(R.id.switchExternalImeAidl)
        val tvKeyboardHeight = findViewById<TextView>(R.id.tvKeyboardHeightValue)
        val tvLanguage = findViewById<TextView>(R.id.tvLanguageValue)
        val sliderBottomPadding = findViewById<com.google.android.material.slider.Slider>(R.id.sliderBottomPadding)
        val tvBottomPaddingValue = findViewById<TextView>(R.id.tvBottomPaddingValue)
        val tvExtensionButtons = findViewById<TextView>(R.id.tvExtensionButtonsValue)

        fun applyPrefsToUi() {
            isUpdatingSwitchTrimTrailingPunct = true
            switchTrimTrailingPunct.isChecked = prefs.trimFinalTrailingPunct
            isUpdatingSwitchTrimTrailingPunct = false

            isUpdatingSwitchMicHaptic = true
            switchMicHaptic.isChecked = prefs.micHapticEnabled
            isUpdatingSwitchMicHaptic = false

            isUpdatingSwitchMicTapToggle = true
            switchMicTapToggle.isChecked = prefs.micTapToggleEnabled
            isUpdatingSwitchMicTapToggle = false

            isUpdatingSwitchFcitx5ReturnOnSwitcher = true
            switchFcitx5ReturnOnSwitcher.isChecked = prefs.fcitx5ReturnOnImeSwitch
            isUpdatingSwitchFcitx5ReturnOnSwitcher = false

            isUpdatingSwitchReturnPrevImeOnHide = true
            switchReturnPrevImeOnHide.isChecked = prefs.returnPrevImeOnHide
            isUpdatingSwitchReturnPrevImeOnHide = false

            isUpdatingSwitchHideRecentTasks = true
            switchHideRecentTasks.isChecked = prefs.hideRecentTaskCard
            isUpdatingSwitchHideRecentTasks = false

            isUpdatingSwitchDuckMediaOnRecord = true
            switchDuckMediaOnRecord.isChecked = prefs.duckMediaOnRecordEnabled
            isUpdatingSwitchDuckMediaOnRecord = false

            isUpdatingSwitchHeadsetMicPriority = true
            switchHeadsetMicPriority.isChecked = prefs.headsetMicPriorityEnabled
            isUpdatingSwitchHeadsetMicPriority = false

            switchExternalImeAidl.isChecked = prefs.externalAidlEnabled

            isUpdatingSwitchMicSwipeUpAutoEnter = true
            switchMicSwipeUpAutoEnter.isChecked = prefs.micSwipeUpAutoEnterEnabled
            isUpdatingSwitchMicSwipeUpAutoEnter = false

            isUpdatingSwitchAutoStartRecordingOnShow = true
            switchAutoStartRecordingOnShow.isChecked = prefs.autoStartRecordingOnShow
            isUpdatingSwitchAutoStartRecordingOnShow = false
        }
        applyPrefsToUi()

        // 键盘高度：三档（点击弹出单选对话框）
        setupKeyboardHeightSelection(prefs, tvKeyboardHeight)

        // 底部间距调节
        setupBottomPaddingSlider(prefs, sliderBottomPadding, tvBottomPaddingValue)

        // 应用语言选择（点击弹出单选对话框）
        setupLanguageSelection(prefs, tvLanguage)

        // 扩展按钮配置（点击弹出多选对话框）
        setupExtensionButtonsSelection(prefs, tvExtensionButtons)

        // 监听与保存（统一通过触摸拦截 + 弹窗确认）
        installExplainedSwitch(
            switch = switchTrimTrailingPunct,
            titleRes = R.string.label_trim_trailing_punct,
            offDescRes = R.string.feature_trim_trailing_punct_off_desc,
            onDescRes = R.string.feature_trim_trailing_punct_on_desc,
            preferenceKey = "trim_trailing_punct_explained",
            readPref = { prefs.trimFinalTrailingPunct },
            writePref = { v -> prefs.trimFinalTrailingPunct = v }
        )
        installExplainedSwitch(
            switch = switchMicHaptic,
            titleRes = R.string.label_mic_haptic,
            offDescRes = R.string.feature_mic_haptic_off_desc,
            onDescRes = R.string.feature_mic_haptic_on_desc,
            preferenceKey = "mic_haptic_explained",
            readPref = { prefs.micHapticEnabled },
            writePref = { v -> prefs.micHapticEnabled = v },
            onChanged = { enabled ->
                if (enabled) {
                    switchMicHaptic.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
            }
        )
        switchExternalImeAidl.setOnCheckedChangeListener { btn, isChecked ->
            hapticTapIfEnabled(btn)
            prefs.externalAidlEnabled = isChecked

            // 当开启时显示使用指引（如果尚未显示过）
            if (isChecked && !prefs.externalAidlGuideShown) {
                showExternalAidlGuide(prefs)
            }
        }
        installExplainedSwitch(
            switch = switchMicTapToggle,
            titleRes = R.string.label_mic_tap_toggle,
            offDescRes = R.string.feature_mic_tap_toggle_off_desc,
            onDescRes = R.string.feature_mic_tap_toggle_on_desc,
            preferenceKey = "mic_tap_toggle_explained",
            readPref = { prefs.micTapToggleEnabled },
            writePref = { v -> prefs.micTapToggleEnabled = v },
            onChanged = { enabled ->
                if (enabled && prefs.micSwipeUpAutoEnterEnabled) {
                    prefs.micSwipeUpAutoEnterEnabled = false
                    switchMicSwipeUpAutoEnter.isChecked = false
                }
            }
        )
        installExplainedSwitch(
            switch = switchMicSwipeUpAutoEnter,
            titleRes = R.string.label_mic_swipe_up_auto_enter,
            offDescRes = R.string.feature_mic_swipe_up_auto_enter_off_desc,
            onDescRes = R.string.feature_mic_swipe_up_auto_enter_on_desc,
            preferenceKey = "mic_swipe_up_auto_enter_explained",
            readPref = { prefs.micSwipeUpAutoEnterEnabled },
            writePref = { v -> prefs.micSwipeUpAutoEnterEnabled = v },
            onChanged = { enabled ->
                if (enabled && prefs.micTapToggleEnabled) {
                    prefs.micTapToggleEnabled = false
                    switchMicTapToggle.isChecked = false
                }
            }
        )
        installExplainedSwitch(
            switch = switchAutoStartRecordingOnShow,
            titleRes = R.string.label_auto_start_recording_on_show,
            offDescRes = R.string.feature_auto_start_recording_on_show_off_desc,
            onDescRes = R.string.feature_auto_start_recording_on_show_on_desc,
            preferenceKey = "auto_start_recording_on_show_explained",
            readPref = { prefs.autoStartRecordingOnShow },
            writePref = { v -> prefs.autoStartRecordingOnShow = v }
        )
        installExplainedSwitch(
            switch = switchReturnPrevImeOnHide,
            titleRes = R.string.label_return_prev_ime_on_hide,
            offDescRes = R.string.feature_return_prev_ime_on_hide_off_desc,
            onDescRes = R.string.feature_return_prev_ime_on_hide_on_desc,
            preferenceKey = "return_prev_ime_on_hide_explained",
            readPref = { prefs.returnPrevImeOnHide },
            writePref = { v -> prefs.returnPrevImeOnHide = v }
        )
        installExplainedSwitch(
            switch = switchFcitx5ReturnOnSwitcher,
            titleRes = R.string.label_fcitx5_return_on_switcher,
            offDescRes = R.string.feature_fcitx5_return_on_switcher_off_desc,
            onDescRes = R.string.feature_fcitx5_return_on_switcher_on_desc,
            preferenceKey = "fcitx5_return_on_switcher_explained",
            readPref = { prefs.fcitx5ReturnOnImeSwitch },
            writePref = { v -> prefs.fcitx5ReturnOnImeSwitch = v }
        )
        installExplainedSwitch(
            switch = switchHideRecentTasks,
            titleRes = R.string.label_hide_recent_task_card,
            offDescRes = R.string.feature_hide_recent_tasks_off_desc,
            onDescRes = R.string.feature_hide_recent_tasks_on_desc,
            preferenceKey = "hide_recent_tasks_explained",
            readPref = { prefs.hideRecentTaskCard },
            writePref = { v -> prefs.hideRecentTaskCard = v },
            onChanged = { enabled -> applyExcludeFromRecents(enabled) }
        )
        installExplainedSwitch(
            switch = switchDuckMediaOnRecord,
            titleRes = R.string.label_audio_ducking_on_record,
            offDescRes = R.string.feature_duck_media_on_record_off_desc,
            onDescRes = R.string.feature_duck_media_on_record_on_desc,
            preferenceKey = "duck_media_on_record_explained",
            readPref = { prefs.duckMediaOnRecordEnabled },
            writePref = { v -> prefs.duckMediaOnRecordEnabled = v }
        )
        installExplainedSwitch(
            switch = switchHeadsetMicPriority,
            titleRes = R.string.label_headset_mic_priority,
            offDescRes = R.string.feature_headset_mic_priority_off_desc,
            onDescRes = R.string.feature_headset_mic_priority_on_desc,
            preferenceKey = "headset_mic_priority_explained",
            readPref = { prefs.headsetMicPriorityEnabled },
            writePref = { v -> prefs.headsetMicPriorityEnabled = v },
            preCheck = { target ->
                if (target && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT_CONNECT)
                        false
                    } else true
                } else true
            },
            onChanged = { enabled ->
                if (!enabled) {
                    com.brycewg.asrkb.asr.BluetoothRouteManager.onRecordingStopped(this)
                    com.brycewg.asrkb.asr.BluetoothRouteManager.setImeActive(this, false)
                }
            }
        )

        // 初始应用一次"从最近任务中排除"设置
        applyExcludeFromRecents(prefs.hideRecentTaskCard)

        // Pro：注入输入设置额外 UI
        val root = findViewById<View>(android.R.id.content)
        com.brycewg.asrkb.ProUiInjector.injectIntoInputSettings(this, root)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BT_CONNECT) {
            val switchHeadsetMicPriority = findViewById<MaterialSwitch>(R.id.switchHeadsetMicPriority)
            val prefs = Prefs(this)
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                prefs.headsetMicPriorityEnabled = true
                isUpdatingSwitchHeadsetMicPriority = true
                switchHeadsetMicPriority.isChecked = true
                isUpdatingSwitchHeadsetMicPriority = false
            } else {
                prefs.headsetMicPriorityEnabled = false
                isUpdatingSwitchHeadsetMicPriority = true
                switchHeadsetMicPriority.isChecked = false
                isUpdatingSwitchHeadsetMicPriority = false
                Toast.makeText(this, getString(R.string.toast_bt_connect_permission_denied), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 设置键盘高度选择对话框
     */
    private fun setupKeyboardHeightSelection(prefs: Prefs, tvKeyboardHeight: TextView) {
        val options = listOf(
            getString(R.string.keyboard_height_small),
            getString(R.string.keyboard_height_medium),
            getString(R.string.keyboard_height_large)
        )

        fun updateSummary() {
            val idx = (prefs.keyboardHeightTier - 1).coerceIn(0, 2)
            tvKeyboardHeight.text = options[idx]
        }
        updateSummary()

        tvKeyboardHeight.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val currentIndex = (prefs.keyboardHeightTier - 1).coerceIn(0, 2)
            showSingleChoiceDialog(
                titleRes = R.string.label_keyboard_height,
                items = options,
                currentIndex = currentIndex,
                onSelected = { selectedIndex ->
                    val tier = (selectedIndex + 1).coerceIn(1, 3)
                    if (prefs.keyboardHeightTier != tier) {
                        prefs.keyboardHeightTier = tier
                        updateSummary()
                        sendRefreshBroadcast()
                    }
                }
            )
        }
    }

    /**
     * 设置底部间距调节滑动条
     */
    private fun setupBottomPaddingSlider(
        prefs: Prefs,
        slider: com.google.android.material.slider.Slider,
        tvValue: TextView
    ) {
        // 初始化滑动条值
        slider.value = prefs.keyboardBottomPaddingDp.toFloat()
        tvValue.text = getString(R.string.keyboard_bottom_padding_value, prefs.keyboardBottomPaddingDp)

        // 监听滑动条变化
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val dp = value.toInt()
                prefs.keyboardBottomPaddingDp = dp
                tvValue.text = getString(R.string.keyboard_bottom_padding_value, dp)
                sendRefreshBroadcast()
            }
        }
    }

    /**
     * 设置应用语言选择对话框
     */
    private fun setupLanguageSelection(prefs: Prefs, tvLanguage: TextView) {
        val options = listOf(
            getString(R.string.lang_follow_system),
            getString(R.string.lang_zh_cn),
            getString(R.string.lang_zh_tw),
            getString(R.string.lang_ja),
            getString(R.string.lang_en)
        )

        fun getLanguageIndex(tag: String): Int = when (tag) {
            "zh", "zh-CN", "zh-Hans" -> 1
            "zh-TW", "zh-Hant" -> 2
            "ja" -> 3
            "en" -> 4
            else -> 0
        }

        fun updateSummary() {
            tvLanguage.text = options[getLanguageIndex(prefs.appLanguageTag)]
        }
        updateSummary()

        tvLanguage.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val currentIndex = getLanguageIndex(prefs.appLanguageTag)
            showSingleChoiceDialog(
                titleRes = R.string.label_language,
                items = options,
                currentIndex = currentIndex,
                onSelected = { selectedIndex ->
                    val newTag = when (selectedIndex) {
                        1 -> "zh-CN"
                        2 -> "zh-TW"
                        3 -> "ja"
                        4 -> "en"
                        else -> ""
                    }
                    if (newTag != prefs.appLanguageTag) {
                        prefs.appLanguageTag = newTag
                        updateSummary()
                        val locales = if (newTag.isBlank()) {
                            LocaleListCompat.getEmptyLocaleList()
                        } else {
                            LocaleListCompat.forLanguageTags(newTag)
                        }
                        AppCompatDelegate.setApplicationLocales(locales)
                    }
                }
            )
        }
    }

    /**
     * 通用单选对话框显示函数
     *
     * @param titleRes 对话框标题资源 ID
     * @param items 选项列表
     * @param currentIndex 当前选中的索引
     * @param onSelected 选中回调，参数为选中的索引
     */
    private fun showSingleChoiceDialog(
        titleRes: Int,
        items: List<String>,
        currentIndex: Int,
        onSelected: (Int) -> Unit
    ) {
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setSingleChoiceItems(items.toTypedArray(), currentIndex) { dialog, which ->
                onSelected(which)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /**
     * 发送刷新 IME UI 的广播
     */
    private fun sendRefreshBroadcast() {
        sendBroadcast(Intent(AsrKeyboardService.ACTION_REFRESH_IME_UI))
    }

    /**
     * 根据设置执行触觉反馈
     */
    private fun hapticTapIfEnabled(view: View?) {
        if (Prefs(this).micHapticEnabled) {
            view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    /**
     * 应用"从最近任务中排除"设置
     */
    private fun applyExcludeFromRecents(enabled: Boolean) {
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        am.appTasks?.forEach { it.setExcludeFromRecents(enabled) }
    }

    /**
     * 设置扩展按钮配置对话框
     */
    private fun setupExtensionButtonsSelection(prefs: Prefs, tvExtensionButtons: TextView) {
        // 获取所有可用的按钮动作（排除 NONE 与 NUMPAD：数字/符号键盘改为固定入口）
        val allActions = com.brycewg.asrkb.ime.ExtensionButtonAction.values()
            .filter { it != com.brycewg.asrkb.ime.ExtensionButtonAction.NONE && it != com.brycewg.asrkb.ime.ExtensionButtonAction.NUMPAD }

        fun updateSummary() {
            val current = listOf(prefs.extBtn1, prefs.extBtn2, prefs.extBtn3, prefs.extBtn4)
                .filter { it != com.brycewg.asrkb.ime.ExtensionButtonAction.NONE }
            if (current.isEmpty()) {
                tvExtensionButtons.text = getString(R.string.ext_btn_none_selected)
            } else {
                val names = current.map { getString(it.titleResId) }
                tvExtensionButtons.text = names.joinToString(", ")
            }
        }
        updateSummary()

        tvExtensionButtons.setOnClickListener { v ->
            hapticTapIfEnabled(v)

            // 当前配置
            val current = listOf(prefs.extBtn1, prefs.extBtn2, prefs.extBtn3, prefs.extBtn4)
                .filter { it != com.brycewg.asrkb.ime.ExtensionButtonAction.NONE }

            // 构建选项列表
            val items = allActions.map { getString(it.titleResId) }.toTypedArray()
            val checked = BooleanArray(allActions.size) { idx ->
                current.contains(allActions[idx])
            }

            val dialog = MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ext_btn_must_select_4)
                .setMultiChoiceItems(items, checked) { dialog, which, isChecked ->
                    val alertDialog = dialog as? androidx.appcompat.app.AlertDialog
                    val listView = alertDialog?.listView

                    // 先模拟这次操作的结果
                    val tempChecked = checked.copyOf()
                    tempChecked[which] = isChecked
                    val wouldBeSelected = tempChecked.count { it }

                    // 如果操作后选中数量超过4个，拒绝操作
                    if (wouldBeSelected > 4) {
                        listView?.setItemChecked(which, false)
                        Toast.makeText(this, R.string.ext_btn_max_4, Toast.LENGTH_SHORT).show()
                        return@setMultiChoiceItems
                    }

                    // 操作合法，更新 checked 数组
                    checked[which] = isChecked
                    val selectedCount = checked.count { it }

                    // 更新确定按钮的启用状态
                    alertDialog?.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.isEnabled = (selectedCount == 4)

                    // 更新列表项的视觉状态和禁用状态
                    listView?.post {
                        for (i in 0 until listView.childCount) {
                            val position = listView.firstVisiblePosition + i
                            if (position >= 0 && position < checked.size) {
                                val itemView = listView.getChildAt(i)
                                val shouldDisable = (selectedCount >= 4 && !checked[position])
                                if (shouldDisable) {
                                    // 已选4个且当前项未选中：变灰并禁用
                                    itemView?.alpha = 0.5f
                                    itemView?.isEnabled = false
                                } else {
                                    // 恢复正常
                                    itemView?.alpha = 1.0f
                                    itemView?.isEnabled = true
                                }
                            }
                        }
                    }
                }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    // 收集选中的按钮动作
                    val selected = allActions.filterIndexed { idx, _ -> checked[idx] }

                    // 填充到4个位置
                    prefs.extBtn1 = selected.getOrElse(0) { com.brycewg.asrkb.ime.ExtensionButtonAction.NONE }
                    prefs.extBtn2 = selected.getOrElse(1) { com.brycewg.asrkb.ime.ExtensionButtonAction.NONE }
                    prefs.extBtn3 = selected.getOrElse(2) { com.brycewg.asrkb.ime.ExtensionButtonAction.NONE }
                    prefs.extBtn4 = selected.getOrElse(3) { com.brycewg.asrkb.ime.ExtensionButtonAction.NONE }

                    updateSummary()
                    sendRefreshBroadcast()
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .create()

            dialog.show()

            // 初始根据当前选择数量设置确定按钮的启用状态
            val initialSelectedCount = checked.count { it }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.isEnabled = (initialSelectedCount == 4)

            // 初始化列表项的视觉状态和禁用状态
            dialog.listView?.post {
                val listView = dialog.listView
                for (i in 0 until listView.childCount) {
                    val position = listView.firstVisiblePosition + i
                    if (position >= 0 && position < checked.size) {
                        val itemView = listView.getChildAt(i)
                        val shouldDisable = (initialSelectedCount >= 4 && !checked[position])
                        if (shouldDisable) {
                            // 已选4个且当前项未选中：变灰并禁用
                            itemView?.alpha = 0.5f
                            itemView?.isEnabled = false
                        } else {
                            // 恢复正常
                            itemView?.alpha = 1.0f
                            itemView?.isEnabled = true
                        }
                    }
                }
            }
        }
    }

    /**
     * 为开关安装“说明弹窗 + 拦截”逻辑：
     * - 首次点击显示说明弹窗，由弹窗确认决定是否切换
     * - 勾选“不再提醒”后，后续点击直接切换
     * - 过程中不出现“先切换再撤回”的闪烁
     */
    private fun installExplainedSwitch(
        switch: MaterialSwitch,
        titleRes: Int,
        offDescRes: Int,
        onDescRes: Int,
        preferenceKey: String,
        readPref: () -> Boolean,
        writePref: (Boolean) -> Unit,
        onChanged: ((Boolean) -> Unit)? = null,
        preCheck: ((Boolean) -> Boolean)? = null,
    ) {
        // 通过触摸事件拦截系统默认切换，改为由弹窗控制
        switch.setOnTouchListener { v, event ->
            if (event?.action == MotionEvent.ACTION_UP) {
                v?.isPressed = false
                v?.cancelPendingInputEvents()
                hapticTapIfEnabled(v)

                val current = readPref()
                val target = !current

                com.brycewg.asrkb.ui.FeatureExplainerDialog.Builder(this)
                    .setTitle(titleRes)
                    .setOffDescription(offDescRes)
                    .setOnDescription(onDescRes)
                    .setCurrentState(current)
                    .setPreferenceKey(preferenceKey)
                    .setOnConfirm {
                        // 额外前置校验（如权限）
                        if (preCheck != null && !preCheck(target)) {
                            return@setOnConfirm
                        }

                        // 真正提交：写入偏好并更新 UI
                        writePref(target)
                        switch.isChecked = target
                        switch.isPressed = false
                        onChanged?.invoke(target)
                    }
                    .setOnCancel {
                        // 取消时不修改当前状态
                    }
                    .showIfNeeded()

                // 消耗事件，阻止系统默认切换造成的闪烁
                return@setOnTouchListener true
            }
            // 其他事件不拦截，交给系统用于按压态等效果
            false
        }
    }

    /**
     * 显示外部输入法联动使用指引弹窗
     * 按钮布局：不再提醒(左) - 打开Release页(中) - 关闭(右)
     */
    private fun showExternalAidlGuide(prefs: Prefs) {
        val message = getString(R.string.external_aidl_guide_message)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.external_aidl_guide_title)
            .setMessage(message)
            .setNeutralButton(R.string.external_aidl_guide_btn_no_remind) { _, _ ->
                prefs.externalAidlGuideShown = true
            }
            .setNegativeButton(R.string.external_aidl_guide_btn_open_release) { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = android.net.Uri.parse("https://github.com/BryceWG/fcitx5-android-lexi-keyboard/releases")
                }
                startActivity(intent)
            }
            .setPositiveButton(R.string.external_aidl_guide_btn_close, null)
            .show()
    }
}
