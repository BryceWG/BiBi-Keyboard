/**
 * 设置备份与导入导出实现。
 *
 * 归属模块：store
 */
package com.brycewg.asrkb.store

import android.util.Log
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.asr.LlmReasoningThreshold
import com.brycewg.asrkb.asr.LlmVendor
import com.brycewg.asrkb.asr.VolcAsrModelCatalog
import com.brycewg.asrkb.clipboard.ClipboardHistoryStore
import com.brycewg.asrkb.clipboard.ClipboardSyncReceiveMode

/**
 * 设置备份/导入导出逻辑（从 [Prefs] 中拆出）。
 *
 * 约定：
 * - JSON key 与 SharedPreferences key 保持一致，确保兼容历史备份与跨版本迁移。
 * - [Prefs] 仍保留对外 API（`exportJsonString()` / `importJsonString()`），这里只做实现承载。
 */
internal object PrefsBackup {
    // 保持与 Prefs 原日志 tag 一致，便于排查
    private const val TAG = "Prefs"

    fun exportJsonString(prefs: Prefs): String = prefs.run {
        val o = org.json.JSONObject()
        o.put("_version", 1)
        o.put(KEY_APP_KEY, appKey)
        o.put(KEY_ACCESS_KEY, accessKey)
        o.put(KEY_TRIM_FINAL_TRAILING_PUNCT, trimFinalTrailingPunct)
        o.put(KEY_TRIM_FINAL_TRAILING_PUNCT_THRESHOLD, trimFinalTrailingPunctThreshold)
        o.put(KEY_HAPTIC_FEEDBACK_LEVEL, hapticFeedbackLevel)
        o.put(KEY_MIC_HAPTIC_ENABLED, micHapticEnabled)
        o.put(KEY_MIC_TAP_TOGGLE_ENABLED, micTapToggleEnabled)
        o.put(KEY_AUTO_START_RECORDING_ON_SHOW, autoStartRecordingOnShow)
        o.put(KEY_AUTO_ENTER_AFTER_ASR, autoEnterAfterAsrEnabled)
        o.put(KEY_AUTO_SWITCH_IME_AFTER_ASR, autoSwitchImeAfterAsrEnabled)
        o.put(KEY_CONTINUOUS_CAPTURE_ENABLED, continuousCaptureEnabled)
        o.put(KEY_KEEP_SCREEN_ON_WHILE_RECORDING, keepScreenOnWhileRecording)
        o.put(KEY_DUCK_MEDIA_ON_RECORD, duckMediaOnRecordEnabled)
        o.put(KEY_AUTO_CANCEL_EMPTY_AUDIO_INPUT, autoCancelEmptyAudioInputEnabled)
        o.put(KEY_AUTO_FILTER_SILENT_AUDIO_SEGMENTS, autoFilterSilentAudioSegmentsEnabled)
        o.put(KEY_OFFLINE_DENOISE_ENABLED, offlineDenoiseEnabled)
        o.put(KEY_UPLOAD_AUDIO_COMPRESSION_ENABLED, uploadAudioCompressionEnabled)
        o.put(KEY_RECORDING_AUTO_STOP_MODE, recordingAutoStopMode.id)
        o.put(KEY_RECORDING_MAX_DURATION_MS, recordingMaxDurationMs)
        o.put(KEY_AUTO_STOP_ON_SILENCE_ENABLED, autoStopOnSilenceEnabled)
        o.put(KEY_AUTO_STOP_SILENCE_WINDOW_MS, autoStopSilenceWindowMs)
        o.put(KEY_AUTO_STOP_SILENCE_SENSITIVITY, autoStopSilenceSensitivity)
        o.put(KEY_KEYBOARD_HEIGHT_TIER, keyboardHeightTier)
        o.put(KEY_KEYBOARD_BOTTOM_PADDING_DP, keyboardBottomPaddingDp)
        o.put(KEY_IME_TABLET_FLOATING_KEYBOARD_ENABLED, imeTabletFloatingKeyboardEnabled)
        o.put(KEY_IME_FLOATING_KEYBOARD_X_FRACTION, imeFloatingKeyboardXFraction.toDouble())
        o.put(KEY_IME_FLOATING_KEYBOARD_Y_FRACTION, imeFloatingKeyboardYFraction.toDouble())
        o.put(KEY_IME_FLOATING_KEYBOARD_WIDTH_SCALE, imeFloatingKeyboardWidthScale.toDouble())
        o.put(KEY_WAVEFORM_SENSITIVITY, waveformSensitivity)
        o.put(KEY_SWAP_AI_EDIT_IME_SWITCHER, swapAiEditWithImeSwitcher)
        o.put(KEY_FCITX5_RETURN_ON_SWITCHER, fcitx5ReturnOnImeSwitch)
        o.put(KEY_RETURN_PREV_IME_ON_HIDE, returnPrevImeOnHide)
        o.put(KEY_IME_SWITCH_TARGET_ID, imeSwitchTargetId)
        o.put(KEY_HIDE_RECENT_TASK_CARD, hideRecentTaskCard)
        o.put(KEY_KEEP_ALIVE_NOTIFICATION_CLICK_ROUTE, keepAliveNotificationClickRoute)
        o.put(KEY_APP_LANGUAGE_TAG, appLanguageTag)
        o.put(KEY_SETTINGS_UI_MODE, settingsUiMode)
        o.put(KEY_SETTINGS_THEME_MODE, settingsThemeMode)
        o.put(KEY_AUTO_UPDATE_CHECK_ENABLED, autoUpdateCheckEnabled)
        o.put(KEY_FLOATING_SWITCHER_ENABLED, floatingSwitcherEnabled)
        o.put(KEY_FLOATING_SWITCHER_ALPHA, floatingSwitcherAlpha)
        o.put(KEY_FLOATING_BALL_SIZE_DP, floatingBallSizeDp)
        o.put(KEY_FLOATING_POS_X, floatingBallPosX)
        o.put(KEY_FLOATING_POS_Y, floatingBallPosY)
        o.put(KEY_FLOATING_DOCK_SIDE, floatingBallDockSide)
        o.put(KEY_FLOATING_DOCK_FRACTION, floatingBallDockFraction.toDouble())
        o.put(KEY_FLOATING_DOCK_HIDDEN, floatingBallDockHidden)
        o.put(KEY_FLOATING_HOLD_TO_RECORD_ENABLED, floatingBallHoldToRecordEnabled)
        o.put(KEY_FLOATING_DIRECT_DRAG_ENABLED, floatingBallDirectDragEnabled)
        o.put(KEY_FLOATING_ASR_ENABLED, floatingAsrEnabled)
        o.put(KEY_FLOATING_ONLY_WHEN_IME_VISIBLE, floatingSwitcherOnlyWhenImeVisible)
        o.put(KEY_VOLUME_KEY_RECORDING_ENABLED, volumeKeyRecordingEnabled)
        o.put(KEY_VOLUME_KEY_RECORDING_MODE, volumeKeyRecordingMode)
        o.put(KEY_VOLUME_KEY_STATUS_TOAST_ENABLED, volumeKeyStatusToastEnabled)
        o.put(KEY_VOLUME_KEY_STOP_ON_IME_HIDDEN, volumeKeyStopOnImeHidden)
        o.put(KEY_FLOATING_KEEP_ALIVE_ENABLED, floatingKeepAliveEnabled)
        o.put(KEY_FLOATING_KEEP_ALIVE_PRIVILEGED_ENABLED, floatingKeepAlivePrivilegedEnabled)

        o.put(KEY_POSTPROC_ENABLED, postProcessEnabled)
        o.put(KEY_POSTPROC_TYPEWRITER_ENABLED, postprocTypewriterEnabled)
        o.put(KEY_AI_EDIT_DEFAULT_TO_LAST_ASR, aiEditDefaultToLastAsr)
        o.put(
            KEY_AI_EDIT_CUSTOM_SYSTEM_PROMPT_ENABLED,
            aiEditCustomSystemPromptEnabled
        )
        o.put(KEY_AI_EDIT_SYSTEM_PROMPT, aiEditSystemPrompt)
        o.put(KEY_HEADSET_MIC_PRIORITY_ENABLED, headsetMicPriorityEnabled)
        o.put(KEY_LLM_ENDPOINT, llmEndpoint)
        o.put(KEY_LLM_API_KEY, llmApiKey)
        o.put(KEY_LLM_MODEL, llmModel)
        o.put(KEY_LLM_TEMPERATURE, llmTemperature.toDouble())
        // SiliconFlow 免费/付费 LLM 配置
        o.put(KEY_SF_FREE_LLM_ENABLED, sfFreeLlmEnabled)
        o.put(KEY_SF_FREE_LLM_MODEL, sfFreeLlmModel)
        o.put(KEY_SF_FREE_LLM_USE_PAID_KEY, sfFreeLlmUsePaidKey)
        o.put(KEY_SF_OMNI_PROMPT, sfOmniPrompt)
        // OpenAI ASR：流式/Prompt 开关（布尔）
        o.put(KEY_OA_ASR_STREAMING_ENABLED, oaAsrStreamingEnabled)
        o.put(KEY_OA_ASR_USE_PROMPT, oaAsrUsePrompt)
        o.put(KEY_OA_ASR_USE_COMPLETIONS, oaAsrUseCompletions)
        getOpenAiAsrProviders()
        o.put(KEY_OA_ASR_PROVIDERS, openAiAsrProvidersJson)
        o.put(KEY_OA_ASR_ACTIVE_ID, activeOpenAiAsrProviderId)
        // OpenRouter ASR 字符串字段（endpoint/apiKey/model）
        // DashScope legacy streaming toggles, kept for old backup compatibility.
        o.put(KEY_DASH_STREAMING_ENABLED, dashStreamingEnabled)
        o.put(KEY_DASH_FUNASR_ENABLED, dashFunAsrEnabled)
        o.put(KEY_DASH_ASR_MODEL, dashAsrModel)
        // Soniox 单值语言字段保留显式导出；数组字段走 supplier schema。
        o.put(KEY_SONIOX_LANGUAGE, sonioxLanguage)
        o.put(KEY_GEM_PROMPT, gemPrompt)
        // 多 LLM 配置
        o.put(KEY_LLM_PROVIDERS, llmProvidersJson)
        o.put(KEY_LLM_ACTIVE_ID, activeLlmId)
        o.put(KEY_LLM_REQUEST_MODES, llmRequestModesJson)
        // 兼容旧字段
        o.put(KEY_LLM_PROMPT, llmPrompt)
        o.put(KEY_LLM_PROMPT_PRESETS, promptPresetsJson)
        o.put(KEY_LLM_PROMPT_ACTIVE_ID, activePromptId)
        // 语音预设
        o.put(KEY_SPEECH_PRESETS, speechPresetsJson)
        o.put(KEY_SPEECH_PRESET_ACTIVE_ID, activeSpeechPresetId)
        // 供应商设置（通用导出）
        o.put(KEY_ASR_VENDOR, asrVendor.id)
        o.put(KEY_BACKUP_ASR_ENABLED, backupAsrEnabled)
        o.put(KEY_BACKUP_ASR_VENDOR, backupAsrVendor.id)
        o.put(KEY_BACKUP_ASR_TIMEOUT_SENSITIVITY, backupAsrTimeoutSensitivity)
        o.put(KEY_BACKUP_ASR_LOCAL_RESIDENCY, backupAsrLocalResidency.id)
        // 遍历所有供应商字段，统一导出，避免逐个硬编码
        PrefsAsrVendorFields.exportToJson(asVendorFieldStore(), o)
        // 自定义标点
        o.put(KEY_PUNCT_1, punct1)
        o.put(KEY_PUNCT_2, punct2)
        o.put(KEY_PUNCT_3, punct3)
        o.put(KEY_PUNCT_4, punct4)
        // 自定义扩展按钮
        o.put(KEY_EXT_BTN_1, extBtn1.id)
        o.put(KEY_EXT_BTN_2, extBtn2.id)
        o.put(KEY_EXT_BTN_3, extBtn3.id)
        o.put(KEY_EXT_BTN_4, extBtn4.id)
        o.put(KEY_CUSTOM_KEYBOARD_LAYOUTS_JSON, customKeyboardLayoutsJson)
        // 统计信息
        o.put(KEY_TOTAL_ASR_CHARS, totalAsrChars)
        // 使用统计（聚合）与首次使用日期
        try {
            o.put(KEY_USAGE_STATS_JSON, getPrefString(KEY_USAGE_STATS_JSON, ""))
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to export usage stats", t)
        }
        // 历史记录纳入备份范围
        o.put(KEY_ASR_HISTORY_JSON, AsrHistoryStore(appContext).exportJson())
        try {
            o.put(KEY_FIRST_USE_DATE, firstUseDate)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to export first use date", t)
        }
        try {
            o.put(KEY_SHOWN_ONBOARDING_GUIDE_V2_ONCE, hasShownOnboardingGuideV2Once)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to export onboarding guide state", t)
        }
        // 写入兼容/粘贴方案
        o.put(KEY_FLOATING_WRITE_COMPAT_ENABLED, floatingWriteTextCompatEnabled)
        o.put(KEY_FLOATING_WRITE_COMPAT_PACKAGES, floatingWriteCompatPackages)
        o.put(KEY_FLOATING_WRITE_PASTE_ENABLED, floatingWriteTextPasteEnabled)
        o.put(KEY_FLOATING_WRITE_PASTE_PACKAGES, floatingWritePastePackages)
        o.put(KEY_FLOATING_A11Y_ANDROID13_API_ENABLED, floatingA11yAndroid13ApiEnabled)
        o.put(KEY_FLOATING_IME_BRIDGE_ENABLED, floatingImeBridgeEnabled)
        o.put(KEY_IME_BRIDGE_PCM_RECORDING_ENABLED, imeBridgePcmRecordingEnabled)
        // 允许外部输入法联动（AIDL）
        o.put(KEY_EXTERNAL_AIDL_ENABLED, externalAidlEnabled)
        // FireRedASR（本地 ASR）
        o.put(KEY_FR_MODEL_VARIANT, frModelVariant)
        o.put(KEY_FR_NUM_THREADS, frNumThreads)
        o.put(KEY_FR_KEEP_ALIVE_MINUTES, frKeepAliveMinutes)
        o.put(KEY_FR_PRELOAD_ENABLED, frPreloadEnabled)
        o.put(KEY_FR_USE_ITN, frUseItn)
        o.put(KEY_FR_PSEUDO_STREAM_ENABLED, frPseudoStreamEnabled)
        // X-ASR（本地 ASR）
        o.put(KEY_X_ASR_MODEL_VARIANT, xAsrModelVariant)
        o.put(KEY_X_ASR_NUM_THREADS, xAsrNumThreads)
        o.put(KEY_X_ASR_KEEP_ALIVE_MINUTES, xAsrKeepAliveMinutes)
        o.put(KEY_X_ASR_PRELOAD_ENABLED, xAsrPreloadEnabled)
        o.put(KEY_X_ASR_USE_ITN, xAsrUseItn)
        // SyncClipboard 配置
        o.put(KEY_SC_ENABLED, syncClipboardEnabled)
        o.put(KEY_SC_SERVER_BASE, syncClipboardServerBase)
        o.put(KEY_SC_USERNAME, syncClipboardUsername)
        o.put(KEY_SC_PASSWORD, syncClipboardPassword)
        o.put(KEY_SC_RECEIVE_MODE, syncClipboardReceiveMode.id)
        o.put(KEY_SC_KEEP_BACKGROUND_REALTIME, syncClipboardKeepBackgroundRealtimeEnabled)
        // 旧字段：保持导出兼容，便于旧版导入
        o.put(KEY_SC_AUTO_PULL, syncClipboardReceiveMode != ClipboardSyncReceiveMode.OFF)
        o.put(KEY_SC_REALTIME, syncClipboardReceiveMode == ClipboardSyncReceiveMode.REALTIME)
        o.put(KEY_SC_PULL_INTERVAL_SEC, syncClipboardPullIntervalSec)
        o.put(KEY_SC_SYNC_IMAGES, syncClipboardImagesEnabled)
        o.put(KEY_SC_SYNC_FILES, syncClipboardFilesEnabled)
        o.put(KEY_SC_ATTACHMENT_MAX_SIZE_MB, syncClipboardAttachmentMaxSizeMb)
        o.put(KEY_SC_WATCH_TREE_URI, syncClipboardWatchTreeUri)
        // WebDAV（可选）
        o.put(KEY_WD_URL, webdavUrl)
        o.put(KEY_WD_USERNAME, webdavUsername)
        o.put(KEY_WD_PASSWORD, webdavPassword)
        // 仅导出固定的剪贴板记录
        o.put(
            KEY_CLIP_PINNED_JSON,
            ClipboardHistoryStore(appContext, this).exportPinnedJson()
        )
        // 隐私开关
        try {
            o.put(KEY_DISABLE_ASR_HISTORY, disableAsrHistory)
            o.put(KEY_AUDIO_HISTORY_RETENTION_COUNT, audioHistoryRetentionCount)
        } catch (_: Throwable) {}
        try {
            o.put(KEY_DISABLE_USAGE_STATS, disableUsageStats)
        } catch (_: Throwable) {}
        try {
            o.put(KEY_DATA_COLLECTION_ENABLED, dataCollectionEnabled)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to export data collection enabled", t)
        }
        // AI 后处理：少于字数跳过
        try {
            o.put(KEY_POSTPROC_SKIP_UNDER_CHARS, postprocSkipUnderChars)
        } catch (_: Throwable) {}
        // LLM 供应商选择（新架构）
        try {
            o.put(KEY_LLM_VENDOR, llmVendor.id)
        } catch (_: Throwable) {}
        // 内置供应商配置（遍历所有内置供应商）
        for (vendor in LlmVendor.builtinVendors()) {
            val keyPrefix = "llm_vendor_${vendor.id}"
            try {
                o.put("${keyPrefix}_api_key", getLlmVendorApiKey(vendor))
            } catch (_: Throwable) {}
            try {
                val model = if (vendor == LlmVendor.SF_FREE && !sfFreeLlmUsePaidKey) {
                    sfFreeLlmModel
                } else {
                    getLlmVendorModel(vendor)
                }
                o.put("${keyPrefix}_model", model)
            } catch (_: Throwable) {}
            try {
                o.put("${keyPrefix}_temperature", getLlmVendorTemperature(vendor).toDouble())
            } catch (_: Throwable) {}
            try {
                val threshold = getLlmVendorReasoningCharThreshold(vendor)
                o.put("${keyPrefix}_reasoning_char_threshold", threshold)
                o.put("${keyPrefix}_reasoning_enabled", threshold == LlmReasoningThreshold.ALWAYS)
            } catch (_: Throwable) {}
            try {
                o.put(
                    "${keyPrefix}_custom_reasoning_params",
                    getLlmVendorCustomReasoningParamsEnabled(vendor)
                )
            } catch (_: Throwable) {}
            try {
                o.put("${keyPrefix}_reasoning_on_json", getLlmVendorReasoningParamsOnJson(vendor))
            } catch (_: Throwable) {}
            try {
                o.put("${keyPrefix}_reasoning_off_json", getLlmVendorReasoningParamsOffJson(vendor))
            } catch (_: Throwable) {}
            try {
                o.put("${keyPrefix}_models_json", getPrefString("${keyPrefix}_models_json", ""))
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to export LLM vendor models", t)
            }
        }
        return o.toString()
    }

    fun importJsonString(prefs: Prefs, jsonString: String): Boolean = prefs.run {
        return try {
            val o = org.json.JSONObject(jsonString)
            Log.i(TAG, "Starting import of settings from JSON")
            fun optBool(key: String, default: Boolean? = null): Boolean? = if (o.has(key)) o.optBoolean(key) else default
            fun optString(key: String, default: String? = null): String? = if (o.has(key)) o.optString(key) else default
            fun optFloat(key: String, default: Float? = null): Float? = if (o.has(key)) o.optDouble(key).toFloat() else default
            fun optInt(key: String, default: Int? = null): Int? = if (o.has(key)) o.optInt(key) else default

            optString(KEY_APP_KEY)?.let { appKey = it }
            optString(KEY_ACCESS_KEY)?.let { accessKey = it }
            optBool(KEY_TRIM_FINAL_TRAILING_PUNCT)?.let { trimFinalTrailingPunct = it }
            optInt(KEY_TRIM_FINAL_TRAILING_PUNCT_THRESHOLD)?.let { trimFinalTrailingPunctThreshold = it }
            val importedHapticLevel = optInt(KEY_HAPTIC_FEEDBACK_LEVEL)
            if (importedHapticLevel != null) {
                hapticFeedbackLevel = importedHapticLevel
            } else {
                optBool(KEY_MIC_HAPTIC_ENABLED)?.let { micHapticEnabled = it }
            }
            optBool(KEY_MIC_TAP_TOGGLE_ENABLED)?.let { micTapToggleEnabled = it }
            optBool(KEY_AUTO_START_RECORDING_ON_SHOW)?.let { autoStartRecordingOnShow = it }
            optBool(KEY_AUTO_ENTER_AFTER_ASR)?.let { autoEnterAfterAsrEnabled = it }
            optBool(KEY_AUTO_SWITCH_IME_AFTER_ASR)?.let { autoSwitchImeAfterAsrEnabled = it }
            optBool(KEY_CONTINUOUS_CAPTURE_ENABLED)?.let { continuousCaptureEnabled = it }
            optBool(KEY_KEEP_SCREEN_ON_WHILE_RECORDING)?.let { keepScreenOnWhileRecording = it }
            optBool(KEY_DUCK_MEDIA_ON_RECORD)?.let { duckMediaOnRecordEnabled = it }
            optBool(KEY_AUTO_CANCEL_EMPTY_AUDIO_INPUT)?.let { autoCancelEmptyAudioInputEnabled = it }
            optBool(KEY_AUTO_FILTER_SILENT_AUDIO_SEGMENTS)?.let { autoFilterSilentAudioSegmentsEnabled = it }
            optBool(KEY_OFFLINE_DENOISE_ENABLED)?.let { offlineDenoiseEnabled = it }
            optBool(KEY_UPLOAD_AUDIO_COMPRESSION_ENABLED)?.let { uploadAudioCompressionEnabled = it }
            val importedAutoStopMode = optString(KEY_RECORDING_AUTO_STOP_MODE)
            if (importedAutoStopMode != null) {
                recordingAutoStopMode = Prefs.RecordingAutoStopMode.fromId(importedAutoStopMode)
            } else {
                optBool(KEY_AUTO_STOP_ON_SILENCE_ENABLED)?.let { autoStopOnSilenceEnabled = it }
            }
            optInt(KEY_RECORDING_MAX_DURATION_MS)?.let { recordingMaxDurationMs = it }
            optInt(KEY_AUTO_STOP_SILENCE_WINDOW_MS)?.let { autoStopSilenceWindowMs = it }
            optInt(KEY_AUTO_STOP_SILENCE_SENSITIVITY)?.let { autoStopSilenceSensitivity = it }
            optInt(KEY_KEYBOARD_HEIGHT_TIER)?.let { keyboardHeightTier = it }
            optInt(KEY_KEYBOARD_BOTTOM_PADDING_DP)?.let { keyboardBottomPaddingDp = it }
            optBool(KEY_IME_TABLET_FLOATING_KEYBOARD_ENABLED)?.let { imeTabletFloatingKeyboardEnabled = it }
            optFloat(KEY_IME_FLOATING_KEYBOARD_X_FRACTION)?.let { imeFloatingKeyboardXFraction = it }
            optFloat(KEY_IME_FLOATING_KEYBOARD_Y_FRACTION)?.let { imeFloatingKeyboardYFraction = it }
            optFloat(KEY_IME_FLOATING_KEYBOARD_WIDTH_SCALE)?.let { imeFloatingKeyboardWidthScale = it }
            optInt(KEY_WAVEFORM_SENSITIVITY)?.let { waveformSensitivity = it }
            optBool(KEY_SWAP_AI_EDIT_IME_SWITCHER)?.let { swapAiEditWithImeSwitcher = it }
            optBool(KEY_FCITX5_RETURN_ON_SWITCHER)?.let { fcitx5ReturnOnImeSwitch = it }
            optBool(KEY_HIDE_RECENT_TASK_CARD)?.let { hideRecentTaskCard = it }
            optString(KEY_KEEP_ALIVE_NOTIFICATION_CLICK_ROUTE)?.let {
                keepAliveNotificationClickRoute = it
            }
            optString(KEY_APP_LANGUAGE_TAG)?.let { appLanguageTag = it }
            optString(KEY_SETTINGS_UI_MODE)?.let { settingsUiMode = it }
            optString(KEY_SETTINGS_THEME_MODE)?.let { settingsThemeMode = it }
            optBool(KEY_AUTO_UPDATE_CHECK_ENABLED)?.let { autoUpdateCheckEnabled = it }
            optBool(KEY_POSTPROC_ENABLED)?.let { postProcessEnabled = it }
            optBool(KEY_POSTPROC_TYPEWRITER_ENABLED)?.let { postprocTypewriterEnabled = it }
            optBool(KEY_HEADSET_MIC_PRIORITY_ENABLED)?.let { headsetMicPriorityEnabled = it }
            // SiliconFlow 免费/付费 LLM 配置
            optBool(KEY_SF_FREE_LLM_ENABLED)?.let { sfFreeLlmEnabled = it }
            optString(KEY_SF_FREE_LLM_MODEL)?.let { sfFreeLlmModel = it }
            optBool(KEY_SF_FREE_LLM_USE_PAID_KEY)?.let { sfFreeLlmUsePaidKey = it }
            optString(KEY_SF_OMNI_PROMPT)?.let { sfOmniPrompt = it }
            // 外部输入法联动（AIDL）
            optBool(KEY_EXTERNAL_AIDL_ENABLED)?.let { externalAidlEnabled = it }
            optBool(KEY_FLOATING_SWITCHER_ENABLED)?.let { floatingSwitcherEnabled = it }
            optFloat(KEY_FLOATING_SWITCHER_ALPHA)?.let {
                floatingSwitcherAlpha =
                    it.coerceIn(0.2f, 1.0f)
            }
            optInt(KEY_FLOATING_BALL_SIZE_DP)?.let { floatingBallSizeDp = it.coerceIn(28, 96) }
            optInt(KEY_FLOATING_POS_X)?.let { floatingBallPosX = it }
            optInt(KEY_FLOATING_POS_Y)?.let { floatingBallPosY = it }
            optInt(KEY_FLOATING_DOCK_SIDE)?.let { floatingBallDockSide = it }
            optFloat(KEY_FLOATING_DOCK_FRACTION)?.let { floatingBallDockFraction = it }
            optBool(KEY_FLOATING_DOCK_HIDDEN)?.let { floatingBallDockHidden = it }
            optBool(KEY_FLOATING_HOLD_TO_RECORD_ENABLED)?.let {
                floatingBallHoldToRecordEnabled = it
            }
            optBool(KEY_FLOATING_DIRECT_DRAG_ENABLED)?.let { floatingBallDirectDragEnabled = it }
            optBool(KEY_FLOATING_ASR_ENABLED)?.let { floatingAsrEnabled = it }
            optBool(KEY_FLOATING_ONLY_WHEN_IME_VISIBLE)?.let {
                floatingSwitcherOnlyWhenImeVisible =
                    it
            }
            optBool(KEY_VOLUME_KEY_RECORDING_ENABLED)?.let { volumeKeyRecordingEnabled = it }
            optString(KEY_VOLUME_KEY_RECORDING_MODE)?.let { volumeKeyRecordingMode = it }
            optBool(KEY_VOLUME_KEY_STATUS_TOAST_ENABLED)?.let { volumeKeyStatusToastEnabled = it }
            optBool(KEY_VOLUME_KEY_STOP_ON_IME_HIDDEN)?.let { volumeKeyStopOnImeHidden = it }
            optBool(KEY_FLOATING_KEEP_ALIVE_ENABLED)?.let { floatingKeepAliveEnabled = it }
            optBool(KEY_FLOATING_KEEP_ALIVE_PRIVILEGED_ENABLED)?.let {
                floatingKeepAlivePrivilegedEnabled =
                    it
            }

            optBool(KEY_FLOATING_WRITE_COMPAT_ENABLED)?.let { floatingWriteTextCompatEnabled = it }
            optString(KEY_FLOATING_WRITE_COMPAT_PACKAGES)?.let { floatingWriteCompatPackages = it }
            optBool(KEY_FLOATING_WRITE_PASTE_ENABLED)?.let { floatingWriteTextPasteEnabled = it }
            optString(KEY_FLOATING_WRITE_PASTE_PACKAGES)?.let { floatingWritePastePackages = it }
            optBool(KEY_FLOATING_A11Y_ANDROID13_API_ENABLED)?.let { floatingA11yAndroid13ApiEnabled = it }
            optBool(KEY_FLOATING_IME_BRIDGE_ENABLED)?.let { floatingImeBridgeEnabled = it }
            optBool(KEY_IME_BRIDGE_PCM_RECORDING_ENABLED)?.let {
                imeBridgePcmRecordingEnabled = it
            }

            optString(KEY_LLM_ENDPOINT)?.let {
                llmEndpoint =
                    it.ifBlank { Prefs.DEFAULT_LLM_ENDPOINT }
            }
            optString(KEY_LLM_API_KEY)?.let { llmApiKey = it }
            optString(KEY_LLM_MODEL)?.let { llmModel = it.ifBlank { Prefs.DEFAULT_LLM_MODEL } }
            optFloat(KEY_LLM_TEMPERATURE)?.let { llmTemperature = it.coerceIn(0f, 2f) }
            optBool(KEY_AI_EDIT_DEFAULT_TO_LAST_ASR)?.let { aiEditDefaultToLastAsr = it }
            val importedAiEditCustomPromptEnabled =
                optBool(KEY_AI_EDIT_CUSTOM_SYSTEM_PROMPT_ENABLED)
            importedAiEditCustomPromptEnabled?.let {
                aiEditCustomSystemPromptEnabled = it
            }
            optString(KEY_AI_EDIT_SYSTEM_PROMPT)?.let { prompt ->
                aiEditSystemPrompt = prompt
                if (importedAiEditCustomPromptEnabled == null && prompt.isNotBlank()) {
                    aiEditCustomSystemPromptEnabled = true
                }
            }
            optInt(KEY_POSTPROC_SKIP_UNDER_CHARS)?.let { postprocSkipUnderChars = it }
            val importedOpenAiStreaming = optBool(KEY_OA_ASR_STREAMING_ENABLED)
            val importedOpenAiUsePrompt = optBool(KEY_OA_ASR_USE_PROMPT)
            val importedOpenAiUseCompletions = optBool(KEY_OA_ASR_USE_COMPLETIONS)
            val hasOpenAiProviders = o.has(KEY_OA_ASR_PROVIDERS)
            val importedOpenAiProviders = optString(KEY_OA_ASR_PROVIDERS)
            val importedOpenAiActiveId = optString(KEY_OA_ASR_ACTIVE_ID)
            // DashScope：优先读取新模型字段；否则回退旧开关并迁移
            val importedDashModel = optString(KEY_DASH_ASR_MODEL)
            if (importedDashModel != null) {
                dashAsrModel = importedDashModel
            } else {
                val importedDashStreaming = optBool(KEY_DASH_STREAMING_ENABLED)
                val importedDashFunAsr = optBool(KEY_DASH_FUNASR_ENABLED)
                importedDashStreaming?.let { dashStreamingEnabled = it }
                importedDashFunAsr?.let { dashFunAsrEnabled = it }
                if (importedDashStreaming != null || importedDashFunAsr != null) {
                    dashAsrModel = deriveDashAsrModelFromLegacyFlagsForBackup()
                }
            }
            optBool(KEY_RETURN_PREV_IME_ON_HIDE)?.let { returnPrevImeOnHide = it }
            optString(KEY_IME_SWITCH_TARGET_ID)?.let { imeSwitchTargetId = it }
            // Soniox（若提供数组则优先；否则回退单值）
            if (o.has(KEY_SONIOX_LANGUAGES)) {
                optString(KEY_SONIOX_LANGUAGES)?.let { sonioxLanguagesJson = it }
            } else {
                optString(KEY_SONIOX_LANGUAGE)?.let { sonioxLanguage = it }
            }
            optString(KEY_GEM_PROMPT)?.let { gemPrompt = it }
            // 多 LLM 配置（优先于旧字段，仅当存在时覆盖）
            optString(KEY_LLM_PROVIDERS)?.let { llmProvidersJson = it }
            optString(KEY_LLM_ACTIVE_ID)?.let { activeLlmId = it }
            optString(KEY_LLM_REQUEST_MODES)?.let { llmRequestModesJson = it }
            // 兼容：先读新预设；若“未提供”或“提供但为空字符串”，则回退旧单一 Prompt
            val importedPresets = optString(KEY_LLM_PROMPT_PRESETS)
            if (importedPresets != null) {
                promptPresetsJson = importedPresets
            }
            optString(KEY_LLM_PROMPT_ACTIVE_ID)?.let { activePromptId = it }
            if (importedPresets.isNullOrBlank()) {
                optString(KEY_LLM_PROMPT)?.let { llmPrompt = it }
            }
            // 语音预设
            optString(KEY_SPEECH_PRESETS)?.let { speechPresetsJson = it }
            optString(KEY_SPEECH_PRESET_ACTIVE_ID)?.let { activeSpeechPresetId = it }

            optString(KEY_ASR_VENDOR)?.let { asrVendor = AsrVendor.fromId(it) }
            optBool(KEY_BACKUP_ASR_ENABLED)?.let { backupAsrEnabled = it }
            optString(KEY_BACKUP_ASR_VENDOR)?.let { backupAsrVendor = AsrVendor.fromId(it) }
            optInt(KEY_BACKUP_ASR_TIMEOUT_SENSITIVITY)?.let { backupAsrTimeoutSensitivity = it }
            optString(KEY_BACKUP_ASR_LOCAL_RESIDENCY)?.let {
                backupAsrLocalResidency = com.brycewg.asrkb.asr.BackupAsrLocalResidency.fromId(it)
            }
            // 供应商设置（通用导入）
            // OpenRouter ASR 字符串字段（endpoint/apiKey/model）在这里随 vendorFields 导入。
            PrefsAsrVendorFields.importFromJson(asVendorFieldStore(), o)
            val importedVolcModel = optString(KEY_VOLC_ASR_MODEL)
            if (!importedVolcModel.isNullOrBlank()) {
                volcAsrModel = importedVolcModel
            } else if (
                o.has(KEY_VOLC_STREAMING_ENABLED) ||
                o.has(KEY_VOLC_FILE_STANDARD_ENABLED) ||
                o.has(KEY_VOLC_MODEL_V2_ENABLED)
            ) {
                volcAsrModel = VolcAsrModelCatalog.fromLegacyFlags(
                    volcStreamingEnabled,
                    volcFileStandardEnabled,
                    volcModelV2Enabled
                ).id
            }
            if (hasOpenAiProviders) {
                openAiAsrProvidersJson = importedOpenAiProviders.orEmpty()
                activeOpenAiAsrProviderId = importedOpenAiActiveId.orEmpty()
            } else {
                openAiAsrProvidersJson = ""
                activeOpenAiAsrProviderId = ""
            }
            importedOpenAiStreaming?.let { oaAsrStreamingEnabled = it }
            importedOpenAiUsePrompt?.let { oaAsrUsePrompt = it }
            if (!hasOpenAiProviders) {
                importedOpenAiUseCompletions?.let { oaAsrUseCompletions = it }
            }
            syncLegacyOpenAiAsrFields(getActiveOpenAiAsrProvider())
            optString(KEY_PUNCT_1)?.let { punct1 = it }
            optString(KEY_PUNCT_2)?.let { punct2 = it }
            optString(KEY_PUNCT_3)?.let { punct3 = it }
            optString(KEY_PUNCT_4)?.let { punct4 = it }
            // 自定义扩展按钮（可选）
            optString(KEY_EXT_BTN_1)?.let {
                extBtn1 =
                    com.brycewg.asrkb.ime.ExtensionButtonAction.fromId(it)
            }
            optString(KEY_EXT_BTN_2)?.let {
                extBtn2 =
                    com.brycewg.asrkb.ime.ExtensionButtonAction.fromId(it)
            }
            optString(KEY_EXT_BTN_3)?.let {
                extBtn3 =
                    com.brycewg.asrkb.ime.ExtensionButtonAction.fromId(it)
            }
            optString(KEY_EXT_BTN_4)?.let {
                extBtn4 =
                    com.brycewg.asrkb.ime.ExtensionButtonAction.fromId(it)
            }
            optString(KEY_CUSTOM_KEYBOARD_LAYOUTS_JSON)?.let { customKeyboardLayoutsJson = it }
            // 统计信息（可选）
            if (o.has(KEY_TOTAL_ASR_CHARS)) {
                // 使用 optLong，若类型为字符串/浮点将尽力转换
                val v = try {
                    o.optLong(KEY_TOTAL_ASR_CHARS)
                } catch (_: Throwable) {
                    0L
                }
                if (v >= 0L) totalAsrChars = v
            }
            // 使用统计（可选）
            optString(KEY_USAGE_STATS_JSON)?.let { setPrefString(KEY_USAGE_STATS_JSON, it) }
            // 历史记录纳入恢复范围
            optString(KEY_ASR_HISTORY_JSON)?.let { AsrHistoryStore(appContext).replaceAllFromJson(it) }
            optString(KEY_FIRST_USE_DATE)?.let { firstUseDate = it }
            optBool(KEY_SHOWN_ONBOARDING_GUIDE_V2_ONCE)?.let { hasShownOnboardingGuideV2Once = it }
            // FireRedASR（本地 ASR）
            (optString(KEY_FR_MODEL_VARIANT) ?: optString(KEY_TS_MODEL_VARIANT))?.let {
                frModelVariant = it
            }
            (optInt(KEY_FR_NUM_THREADS) ?: optInt(KEY_TS_NUM_THREADS))?.let {
                frNumThreads = it.coerceIn(1, 8)
            }
            (optInt(KEY_FR_KEEP_ALIVE_MINUTES) ?: optInt(KEY_TS_KEEP_ALIVE_MINUTES))?.let {
                frKeepAliveMinutes = it
            }
            (optBool(KEY_FR_PRELOAD_ENABLED) ?: optBool(KEY_TS_PRELOAD_ENABLED))?.let {
                frPreloadEnabled = it
            }
            (optBool(KEY_FR_USE_ITN) ?: optBool(KEY_TS_USE_ITN))?.let { frUseItn = it }
            (optBool(KEY_FR_PSEUDO_STREAM_ENABLED) ?: optBool(KEY_TS_PSEUDO_STREAM_ENABLED))?.let {
                frPseudoStreamEnabled = it
            }
            // X-ASR（本地 ASR）
            (optString(KEY_X_ASR_MODEL_VARIANT) ?: optString(LEGACY_KEY_X_ASR_MODEL_VARIANT))?.let {
                xAsrModelVariant = it
            }
            (optInt(KEY_X_ASR_NUM_THREADS) ?: optInt(LEGACY_KEY_X_ASR_NUM_THREADS))?.let {
                xAsrNumThreads = it.coerceIn(1, 8)
            }
            (optInt(KEY_X_ASR_KEEP_ALIVE_MINUTES) ?: optInt(LEGACY_KEY_X_ASR_KEEP_ALIVE_MINUTES))?.let {
                xAsrKeepAliveMinutes = it
            }
            (optBool(KEY_X_ASR_PRELOAD_ENABLED) ?: optBool(LEGACY_KEY_X_ASR_PRELOAD_ENABLED))?.let {
                xAsrPreloadEnabled = it
            }
            (optBool(KEY_X_ASR_USE_ITN) ?: optBool(LEGACY_KEY_X_ASR_USE_ITN))?.let {
                xAsrUseItn = it
            }
            // SyncClipboard 配置
            optBool(KEY_SC_ENABLED)?.let { syncClipboardEnabled = it }
            optString(KEY_SC_SERVER_BASE)?.let { syncClipboardServerBase = it }
            optString(KEY_SC_USERNAME)?.let { syncClipboardUsername = it }
            optString(KEY_SC_PASSWORD)?.let { syncClipboardPassword = it }
            val importedReceiveMode = optString(KEY_SC_RECEIVE_MODE)
            if (importedReceiveMode != null) {
                syncClipboardReceiveMode = ClipboardSyncReceiveMode.fromId(importedReceiveMode)
                optBool(KEY_SC_KEEP_BACKGROUND_REALTIME)?.let {
                    syncClipboardKeepBackgroundRealtimeEnabled = it
                }
            } else {
                // 旧备份：仅有 realtime/auto-pull 时映射到 Receive Mode
                val legacyRealtime = optBool(KEY_SC_REALTIME)
                val legacyAutoPull = optBool(KEY_SC_AUTO_PULL)
                if (legacyRealtime != null || legacyAutoPull != null) {
                    val mode = ClipboardSyncReceiveMode.fromLegacy(
                        realtime = legacyRealtime ?: false,
                        autoPull = legacyAutoPull ?: false
                    )
                    syncClipboardReceiveMode = mode
                    if (optBool(KEY_SC_KEEP_BACKGROUND_REALTIME) == null) {
                        syncClipboardKeepBackgroundRealtimeEnabled = false
                    }
                }
            }
            optInt(KEY_SC_PULL_INTERVAL_SEC)?.let { syncClipboardPullIntervalSec = it }
            optBool(KEY_SC_SYNC_IMAGES)?.let { syncClipboardImagesEnabled = it }
            optBool(KEY_SC_SYNC_FILES)?.let { syncClipboardFilesEnabled = it }
            optInt(KEY_SC_ATTACHMENT_MAX_SIZE_MB)?.let { syncClipboardAttachmentMaxSizeMb = it }
            optString(KEY_SC_WATCH_TREE_URI)?.let { syncClipboardWatchTreeUri = it }
            // 隐私开关
            optBool(KEY_DISABLE_ASR_HISTORY)?.let { disableAsrHistory = it }
            optInt(KEY_AUDIO_HISTORY_RETENTION_COUNT)?.let { audioHistoryRetentionCount = it }
            optBool(KEY_DISABLE_USAGE_STATS)?.let { disableUsageStats = it }
            optBool(KEY_DATA_COLLECTION_ENABLED)?.let { dataCollectionEnabled = it }
            // WebDAV 备份
            optString(KEY_WD_URL)?.let { webdavUrl = it }
            optString(KEY_WD_USERNAME)?.let { webdavUsername = it }
            optString(KEY_WD_PASSWORD)?.let { webdavPassword = it }
            // 剪贴板固定记录（仅覆盖固定集合；非固定不导入）
            optString(KEY_CLIP_PINNED_JSON)?.let {
                ClipboardHistoryStore(appContext, this).replacePinnedFromJson(it)
            }
            // LLM 供应商选择（新架构）
            optString(KEY_LLM_VENDOR)?.let { llmVendor = LlmVendor.fromId(it) }
            // 内置供应商配置（遍历所有内置供应商）
            for (vendor in LlmVendor.builtinVendors()) {
                val keyPrefix = "llm_vendor_${vendor.id}"
                optString("${keyPrefix}_api_key")?.let { setLlmVendorApiKey(vendor, it) }
                optString("${keyPrefix}_model")?.let { setLlmVendorModel(vendor, it) }
                optFloat("${keyPrefix}_temperature")?.let { setLlmVendorTemperature(vendor, it) }
                val importedThreshold = optInt("${keyPrefix}_reasoning_char_threshold")
                if (importedThreshold != null) {
                    setLlmVendorReasoningCharThreshold(vendor, importedThreshold)
                } else {
                    optBool("${keyPrefix}_reasoning_enabled")?.let {
                        setLlmVendorReasoningCharThreshold(
                            vendor,
                            LlmReasoningThreshold.fromLegacyEnabled(it)
                        )
                    }
                }
                optBool("${keyPrefix}_custom_reasoning_params")?.let {
                    setLlmVendorCustomReasoningParamsEnabled(vendor, it)
                }
                optString("${keyPrefix}_reasoning_on_json")?.let {
                    setLlmVendorReasoningParamsOnJson(vendor, it)
                }
                optString("${keyPrefix}_reasoning_off_json")?.let {
                    setLlmVendorReasoningParamsOffJson(vendor, it)
                }
                optString("${keyPrefix}_models_json")?.let { setLlmVendorModelsJson(vendor, it) }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import settings from JSON", e)
            false
        }
    }

    private fun Prefs.deriveDashAsrModelFromLegacyFlagsForBackup(): String {
        val streaming = dashStreamingEnabled
        if (!streaming) return Prefs.DEFAULT_DASH_MODEL
        val funAsr = dashFunAsrEnabled
        return if (funAsr) Prefs.DASH_MODEL_FUN_ASR_REALTIME else Prefs.DASH_MODEL_QWEN_AUDIO_REALTIME
    }

    private fun Prefs.asVendorFieldStore(): VendorFieldStore = object : VendorFieldStore {
        override fun getString(key: String, default: String): String = getPrefString(key, default)

        override fun putString(key: String, value: String) {
            setPrefString(key, value)
        }

        override fun getBoolean(key: String, default: Boolean): Boolean = getPrefBoolean(key, default)

        override fun putBoolean(key: String, value: Boolean) {
            setPrefBoolean(key, value)
        }

        override fun getInt(key: String, default: Int): Int = getPrefInt(key, default)

        override fun putInt(key: String, value: Int) {
            setPrefInt(key, value)
        }
    }
}
