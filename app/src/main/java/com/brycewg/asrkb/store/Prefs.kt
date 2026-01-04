package com.brycewg.asrkb.store

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.asr.LlmVendor
import com.brycewg.asrkb.store.debug.DebugLogManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.reflect.KProperty
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("asr_prefs", Context.MODE_PRIVATE)
    init {
        registerGlobalToggleListenerIfNeeded()
    }

    // --- JSON 配置：宽松模式（容忍未知键，优雅处理格式错误）---
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    // --- 小工具：统一的偏好项委托，减少重复 getter/setter 代码 ---
    private fun stringPref(key: String, default: String = "") = object {
        @Suppress("unused")
        operator fun getValue(thisRef: Prefs, property: KProperty<*>): String =
            sp.getString(key, default) ?: default

        @Suppress("unused")
        operator fun setValue(thisRef: Prefs, property: KProperty<*>, value: String) {
            sp.edit { putString(key, value.trim()) }
        }
    }

    // 直接从 SP 读取字符串，供通用导入/导出和校验使用
    private fun getPrefString(key: String, default: String = ""): String =
        sp.getString(key, default) ?: default

    private fun setPrefString(key: String, value: String) {
        sp.edit { putString(key, value.trim()) }
    }

    private fun registerGlobalToggleListenerIfNeeded() {
        if (!TOGGLE_LISTENER_REGISTERED) {
            try {
                sp.registerOnSharedPreferenceChangeListener(globalToggleListener)
                TOGGLE_LISTENER_REGISTERED = true
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to register global toggle listener", t)
            }
        }
    }

    // 火山引擎凭证
    var appKey: String by stringPref(KEY_APP_KEY, "")

    var accessKey: String by stringPref(KEY_ACCESS_KEY, "")

    var trimFinalTrailingPunct: Boolean
        get() = sp.getBoolean(KEY_TRIM_FINAL_TRAILING_PUNCT, true)
        set(value) = sp.edit { putBoolean(KEY_TRIM_FINAL_TRAILING_PUNCT, value) }

    // 移除：键盘内“切换输入法”按钮显示开关（按钮始终显示）


    // 麦克风按钮触觉反馈
    var micHapticEnabled: Boolean
        get() = sp.getBoolean(KEY_MIC_HAPTIC_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_MIC_HAPTIC_ENABLED, value) }

    // 麦克风点按控制（点按开始/停止），默认关闭：使用长按说话
    var micTapToggleEnabled: Boolean
        get() = sp.getBoolean(KEY_MIC_TAP_TOGGLE_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_MIC_TAP_TOGGLE_ENABLED, value) }

    // 启动键盘面板时自动开始录音（默认关闭）
    var autoStartRecordingOnShow: Boolean
        get() = sp.getBoolean(KEY_AUTO_START_RECORDING_ON_SHOW, false)
        set(value) = sp.edit { putBoolean(KEY_AUTO_START_RECORDING_ON_SHOW, value) }

    // 录音时音频避让（请求短时独占音频焦点以暂停/静音媒体），默认开启
    var duckMediaOnRecordEnabled: Boolean
        get() = sp.getBoolean(KEY_DUCK_MEDIA_ON_RECORD, true)
        set(value) = sp.edit { putBoolean(KEY_DUCK_MEDIA_ON_RECORD, value) }

    // 非流式音频降噪（上传前/本地离线识别前），默认开启
    var offlineDenoiseEnabled: Boolean
        get() = sp.getBoolean(KEY_OFFLINE_DENOISE_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_OFFLINE_DENOISE_ENABLED, value) }

    // AI 编辑默认范围：无选区时优先使用"上次识别结果"
    var aiEditDefaultToLastAsr: Boolean
        get() = sp.getBoolean(KEY_AI_EDIT_DEFAULT_TO_LAST_ASR, false)
        set(value) = sp.edit { putBoolean(KEY_AI_EDIT_DEFAULT_TO_LAST_ASR, value) }

    // AI 后处理：少于该字数时自动跳过（0 表示不启用）
    var postprocSkipUnderChars: Int
        get() = sp.getInt(KEY_POSTPROC_SKIP_UNDER_CHARS, 0).coerceIn(0, 1000)
        set(value) = sp.edit { putInt(KEY_POSTPROC_SKIP_UNDER_CHARS, value.coerceIn(0, 1000)) }

    // 耳机麦克风优先（自动切换到蓝牙/有线耳机麦克风），默认关闭
    var headsetMicPriorityEnabled: Boolean
        get() = sp.getBoolean(KEY_HEADSET_MIC_PRIORITY_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_HEADSET_MIC_PRIORITY_ENABLED, value) }

    // 允许外部输入法（如小企鹅）通过 AIDL 联动，默认关闭
    var externalAidlEnabled: Boolean
        get() = sp.getBoolean(KEY_EXTERNAL_AIDL_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_EXTERNAL_AIDL_ENABLED, value) }

    // 外部输入法联动指引是否已显示（用于"不再提醒"功能）
    var externalAidlGuideShown: Boolean
        get() = sp.getBoolean(KEY_EXTERNAL_AIDL_GUIDE_SHOWN, false)
        set(value) = sp.edit { putBoolean(KEY_EXTERNAL_AIDL_GUIDE_SHOWN, value) }

    // 静音自动判停：开关
    var autoStopOnSilenceEnabled: Boolean
        get() = sp.getBoolean(KEY_AUTO_STOP_ON_SILENCE_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_AUTO_STOP_ON_SILENCE_ENABLED, value) }

    // 静音自动判停：时间窗口（ms），连续低能量超过该时间则自动停止
    var autoStopSilenceWindowMs: Int
        get() = sp.getInt(KEY_AUTO_STOP_SILENCE_WINDOW_MS, DEFAULT_SILENCE_WINDOW_MS).coerceIn(300, 5000)
        set(value) = sp.edit { putInt(KEY_AUTO_STOP_SILENCE_WINDOW_MS, value.coerceIn(300, 5000)) }

    // 静音自动判停：灵敏度（1-10，数值越大越容易判定无人说话）
    var autoStopSilenceSensitivity: Int
        get() = sp.getInt(KEY_AUTO_STOP_SILENCE_SENSITIVITY, DEFAULT_SILENCE_SENSITIVITY).coerceIn(1, 10)
        set(value) = sp.edit { putInt(KEY_AUTO_STOP_SILENCE_SENSITIVITY, value.coerceIn(1, 10)) }

    // 键盘高度档位（1/2/3），默认中档
    var keyboardHeightTier: Int
        get() = sp.getInt(KEY_KEYBOARD_HEIGHT_TIER, 2).coerceIn(1, 3)
        set(value) = sp.edit { putInt(KEY_KEYBOARD_HEIGHT_TIER, value.coerceIn(1, 3)) }

    // 键盘底部间距（单位 dp，范围 0-100，默认 0）
    var keyboardBottomPaddingDp: Int
        get() = sp.getInt(KEY_KEYBOARD_BOTTOM_PADDING_DP, 0).coerceIn(0, 100)
        set(value) = sp.edit { putInt(KEY_KEYBOARD_BOTTOM_PADDING_DP, value.coerceIn(0, 100)) }

    // 波形灵敏度（1-10，数值越大响应越明显），默认 5
    var waveformSensitivity: Int
        get() = sp.getInt(KEY_WAVEFORM_SENSITIVITY, 5).coerceIn(1, 10)
        set(value) = sp.edit { putInt(KEY_WAVEFORM_SENSITIVITY, value.coerceIn(1, 10)) }

    // 是否交换 AI 编辑与输入法切换按钮位置
    var swapAiEditWithImeSwitcher: Boolean
        get() = sp.getBoolean(KEY_SWAP_AI_EDIT_IME_SWITCHER, false)
        set(value) = sp.edit { putBoolean(KEY_SWAP_AI_EDIT_IME_SWITCHER, value) }

    // Fcitx5 联动：通过“输入法切换”键返回（隐藏自身）
    var fcitx5ReturnOnImeSwitch: Boolean
        get() = sp.getBoolean(KEY_FCITX5_RETURN_ON_SWITCHER, false)
        set(value) = sp.edit { putBoolean(KEY_FCITX5_RETURN_ON_SWITCHER, value) }

    // 键盘收起后切回上一个输入法（默认关闭）
    var returnPrevImeOnHide: Boolean
        get() = sp.getBoolean(KEY_RETURN_PREV_IME_ON_HIDE, false)
        set(value) = sp.edit { putBoolean(KEY_RETURN_PREV_IME_ON_HIDE, value) }

    // 输入法切换目标（空字符串表示使用上一个输入法）
    var imeSwitchTargetId: String
        get() = sp.getString(KEY_IME_SWITCH_TARGET_ID, "") ?: ""
        set(value) = sp.edit { putString(KEY_IME_SWITCH_TARGET_ID, value.trim()) }

    // 后台隐藏任务卡片（最近任务不显示预览图）
    var hideRecentTaskCard: Boolean
        get() = sp.getBoolean(KEY_HIDE_RECENT_TASK_CARD, false)
        set(value) = sp.edit { putBoolean(KEY_HIDE_RECENT_TASK_CARD, value) }


    // 应用内语言（空字符串表示跟随系统；如："zh-Hans"、"en"）
    var appLanguageTag: String
        get() = sp.getString(KEY_APP_LANGUAGE_TAG, "") ?: ""
        set(value) = sp.edit { putString(KEY_APP_LANGUAGE_TAG, value) }

    // 最近一次检查更新的日期（格式：yyyyMMdd，本地时区）；用于“每天首次进入设置页自动检查”
    var lastUpdateCheckDate: String by stringPref(KEY_LAST_UPDATE_CHECK_DATE, "")

    // 输入法切换悬浮球开关
    var floatingSwitcherEnabled: Boolean
        get() = sp.getBoolean(KEY_FLOATING_SWITCHER_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_FLOATING_SWITCHER_ENABLED, value) }

    // 仅在输入法面板显示时显示悬浮球
    var floatingSwitcherOnlyWhenImeVisible: Boolean
        get() = sp.getBoolean(KEY_FLOATING_ONLY_WHEN_IME_VISIBLE, true)
        set(value) = sp.edit { putBoolean(KEY_FLOATING_ONLY_WHEN_IME_VISIBLE, value) }

    // 悬浮球透明度（0.2f - 1.0f）
    var floatingSwitcherAlpha: Float
        get() = sp.getFloat(KEY_FLOATING_SWITCHER_ALPHA, 1.0f).coerceIn(0.2f, 1.0f)
        set(value) = sp.edit { putFloat(KEY_FLOATING_SWITCHER_ALPHA, value.coerceIn(0.2f, 1.0f)) }

    // 悬浮球大小（单位 dp，范围 28 - 96，默认 44）
    var floatingBallSizeDp: Int
        get() = sp.getInt(KEY_FLOATING_BALL_SIZE_DP, DEFAULT_FLOATING_BALL_SIZE_DP).coerceIn(28, 96)
        set(value) = sp.edit { putInt(KEY_FLOATING_BALL_SIZE_DP, value.coerceIn(28, 96)) }

    // 悬浮球位置（px，屏幕坐标，-1 表示未设置）
    var floatingBallPosX: Int
        get() = sp.getInt(KEY_FLOATING_POS_X, -1)
        set(value) = sp.edit { putInt(KEY_FLOATING_POS_X, value) }

    var floatingBallPosY: Int
        get() = sp.getInt(KEY_FLOATING_POS_Y, -1)
        set(value) = sp.edit { putInt(KEY_FLOATING_POS_Y, value) }

    // 悬浮球：直接拖动移动（无需长按进入移动模式）
    var floatingBallDirectDragEnabled: Boolean
        get() = sp.getBoolean(KEY_FLOATING_DIRECT_DRAG_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_FLOATING_DIRECT_DRAG_ENABLED, value) }

    // 悬浮球语音识别模式开关
    var floatingAsrEnabled: Boolean
        get() = sp.getBoolean(KEY_FLOATING_ASR_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_FLOATING_ASR_ENABLED, value) }

    // 悬浮球：写入文字兼容性模式（统一控制使用“全选+粘贴”等策略），默认开启
    var floatingWriteTextCompatEnabled: Boolean
        get() = sp.getBoolean(KEY_FLOATING_WRITE_COMPAT_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_FLOATING_WRITE_COMPAT_ENABLED, value) }

    // 兼容目标包名（每行一个；支持前缀匹配，例如 org.telegram）
    var floatingWriteCompatPackages: String
        get() = sp.getString(KEY_FLOATING_WRITE_COMPAT_PACKAGES, DEFAULT_FLOATING_WRITE_COMPAT_PACKAGES) ?: DEFAULT_FLOATING_WRITE_COMPAT_PACKAGES
        set(value) = sp.edit { putString(KEY_FLOATING_WRITE_COMPAT_PACKAGES, value) }

    // 悬浮球：写入采取粘贴方案（根据包名将结果仅复制到粘贴板），默认关闭
    var floatingWriteTextPasteEnabled: Boolean
        get() = sp.getBoolean(KEY_FLOATING_WRITE_PASTE_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_FLOATING_WRITE_PASTE_ENABLED, value) }

    // 粘贴方案目标包名（每行一个；all 表示全局生效；支持前缀匹配）
    var floatingWritePastePackages: String
        get() = sp.getString(KEY_FLOATING_WRITE_PASTE_PACKAGES, "") ?: ""
        set(value) = sp.edit { putString(KEY_FLOATING_WRITE_PASTE_PACKAGES, value) }

    // LLM后处理设置（旧版单一字段；当存在多配置且已选择活动项时仅作回退）
    var postProcessEnabled: Boolean
        get() = sp.getBoolean(KEY_POSTPROC_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_POSTPROC_ENABLED, value) }

    var llmEndpoint: String
        get() = sp.getString(KEY_LLM_ENDPOINT, DEFAULT_LLM_ENDPOINT) ?: DEFAULT_LLM_ENDPOINT
        set(value) = sp.edit { putString(KEY_LLM_ENDPOINT, value.trim()) }

    var llmApiKey: String
        get() = sp.getString(KEY_LLM_API_KEY, "") ?: ""
        set(value) = sp.edit { putString(KEY_LLM_API_KEY, value.trim()) }

    var llmModel: String
        get() = sp.getString(KEY_LLM_MODEL, DEFAULT_LLM_MODEL) ?: DEFAULT_LLM_MODEL
        set(value) = sp.edit { putString(KEY_LLM_MODEL, value.trim()) }

    var llmTemperature: Float
        get() = sp.getFloat(KEY_LLM_TEMPERATURE, DEFAULT_LLM_TEMPERATURE)
        set(value) = sp.edit { putFloat(KEY_LLM_TEMPERATURE, value) }

    // 多 LLM 配置（OpenAI 兼容 API）
    var llmProvidersJson: String
        get() = sp.getString(KEY_LLM_PROVIDERS, "") ?: ""
        set(value) = sp.edit { putString(KEY_LLM_PROVIDERS, value) }

    var activeLlmId: String
        get() = sp.getString(KEY_LLM_ACTIVE_ID, "") ?: ""
        set(value) = sp.edit { putString(KEY_LLM_ACTIVE_ID, value) }

    // 数字/符号小键盘：中文标点模式（true=中文形态，false=英文/ASCII 形态）
    var numpadCnPunctEnabled: Boolean
        get() = sp.getBoolean(KEY_NUMPAD_CN_PUNCT, true)
        set(value) = sp.edit { putBoolean(KEY_NUMPAD_CN_PUNCT, value) }

    @Serializable
    data class LlmProvider(
        val id: String,
        val name: String,
        val endpoint: String,
        val apiKey: String,
        val model: String,
        val temperature: Float,
        val models: List<String> = emptyList()
    )

    fun getLlmProviders(): List<LlmProvider> {
        // 首次使用：若未初始化，迁移旧字段为一个默认配置
        if (llmProvidersJson.isBlank()) {
            val migrated = LlmProvider(
                id = "default",
                name = "默认",
                endpoint = llmEndpoint.ifBlank { DEFAULT_LLM_ENDPOINT },
                apiKey = llmApiKey,
                model = llmModel.ifBlank { DEFAULT_LLM_MODEL },
                temperature = llmTemperature
            )
            setLlmProviders(listOf(migrated))
            if (activeLlmId.isBlank()) activeLlmId = migrated.id
        }
        return try {
            json.decodeFromString<List<LlmProvider>>(llmProvidersJson)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse LlmProviders JSON", e)
            emptyList()
        }
    }

    fun setLlmProviders(list: List<LlmProvider>) {
        try {
            llmProvidersJson = json.encodeToString(list)
            if (list.none { it.id == activeLlmId }) {
                activeLlmId = list.firstOrNull()?.id ?: ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serialize LlmProviders", e)
        }
    }

    fun getActiveLlmProvider(): LlmProvider? {
        val id = activeLlmId
        val list = getLlmProviders()
        return list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }

    // 已弃用：单一提示词。保留用于向后兼容/迁移。
    var llmPrompt: String
        get() = sp.getString(KEY_LLM_PROMPT, "") ?: ""
        set(value) = sp.edit { putString(KEY_LLM_PROMPT, value) }

    // 多个预设提示词，包含标题和活动选择
    var promptPresetsJson: String
        get() = sp.getString(KEY_LLM_PROMPT_PRESETS, "") ?: ""
        set(value) = sp.edit { putString(KEY_LLM_PROMPT_PRESETS, value) }

    var activePromptId: String
        get() = sp.getString(KEY_LLM_PROMPT_ACTIVE_ID, "") ?: ""
        set(value) = sp.edit { putString(KEY_LLM_PROMPT_ACTIVE_ID, value) }

    fun getPromptPresets(): List<PromptPreset> {
        val legacyPrompt = llmPrompt.trim()
        var initializedFromDefaults = false
        // 如果未设置预设，初始化默认预设
        if (promptPresetsJson.isBlank()) {
            initializedFromDefaults = true
            val defaults = buildDefaultPromptPresets()
            setPromptPresets(defaults)
            // 将第一个设为活动状态
            if (activePromptId.isBlank()) activePromptId = defaults.firstOrNull()?.id ?: ""
            return migrateLegacyPromptIfNeeded(defaults, legacyPrompt, initializedFromDefaults)
        }
        val parsed = try {
            val list = json.decodeFromString<List<PromptPreset>>(promptPresetsJson)
            list.ifEmpty { buildDefaultPromptPresets() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse PromptPresets JSON", e)
            buildDefaultPromptPresets()
        }
        return migrateLegacyPromptIfNeeded(parsed, legacyPrompt, initializedFromDefaults)
    }

    fun setPromptPresets(list: List<PromptPreset>) {
        try {
            promptPresetsJson = json.encodeToString(list)
            // 确保活动ID有效
            if (list.none { it.id == activePromptId }) {
                activePromptId = list.firstOrNull()?.id ?: ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serialize PromptPresets", e)
        }
    }

    private fun migrateLegacyPromptIfNeeded(
        current: List<PromptPreset>,
        legacyPrompt: String,
        initializedFromDefaults: Boolean
    ): List<PromptPreset> {
        if (legacyPrompt.isBlank()) return current
        if (current.any { it.content == legacyPrompt }) return current

        val migratedPreset = PromptPreset(
            id = java.util.UUID.randomUUID().toString(),
            title = "我的提示词",
            content = legacyPrompt
        )
        val updated = current + migratedPreset
        val shouldActivate = initializedFromDefaults ||
            activePromptId.isBlank() ||
            current.none { it.id == activePromptId } ||
            matchesDefaultPromptPresets(current)
        if (shouldActivate) {
            activePromptId = migratedPreset.id
        }
        setPromptPresets(updated)
        return updated
    }

    private fun matchesDefaultPromptPresets(presets: List<PromptPreset>): Boolean {
        val defaults = buildDefaultPromptPresets()
        if (presets.size != defaults.size) return false
        return presets.map { it.title to it.content } == defaults.map { it.title to it.content }
    }

    /**
     * 获取当前选中预设的 prompt 内容。
     * 回退顺序：选中的预设 -> 第一个预设（保证始终有值）
     */
    val activePromptContent: String
        get() {
            val presets = getPromptPresets()
            val id = activePromptId
            val legacyPrompt = llmPrompt.trim().takeIf { it.isNotEmpty() }
            return presets.firstOrNull { it.id == id }?.content
                ?: presets.firstOrNull()?.content
                ?: legacyPrompt
                ?: ""
        }

    // 语音预置信息（触发短语 -> 替换内容）
    var speechPresetsJson: String
        get() = sp.getString(KEY_SPEECH_PRESETS, "") ?: ""
        set(value) = sp.edit { putString(KEY_SPEECH_PRESETS, value) }

    var activeSpeechPresetId: String
        get() = sp.getString(KEY_SPEECH_PRESET_ACTIVE_ID, "") ?: ""
        set(value) = sp.edit { putString(KEY_SPEECH_PRESET_ACTIVE_ID, value) }

    fun getSpeechPresets(): List<SpeechPreset> {
        if (speechPresetsJson.isBlank()) return emptyList()
        return try {
            json.decodeFromString<List<SpeechPreset>>(speechPresetsJson)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse SpeechPresets JSON", e)
            emptyList()
        }
    }

    fun setSpeechPresets(list: List<SpeechPreset>) {
        val sanitized = list.mapNotNull { p ->
            val name = p.name.trim()
            if (name.isEmpty()) {
                null
            } else {
                val id = p.id.ifBlank { java.util.UUID.randomUUID().toString() }
                SpeechPreset(id, name, p.content)
            }
        }
        try {
            speechPresetsJson = json.encodeToString(sanitized)
            if (sanitized.none { it.id == activeSpeechPresetId }) {
                activeSpeechPresetId = sanitized.firstOrNull()?.id ?: ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serialize SpeechPresets", e)
        }
    }

    fun findSpeechPresetReplacement(original: String): String? {
        val normalized = original.trim()
        if (normalized.isEmpty()) return null
        val presets = getSpeechPresets()
        val strict = presets.firstOrNull { it.name.trim() == normalized }
        val match = strict ?: presets.firstOrNull { it.name.trim().equals(normalized, ignoreCase = true) }
        return match?.content
    }

    // SiliconFlow凭证
    var sfApiKey: String by stringPref(KEY_SF_API_KEY, "")

    var sfModel: String by stringPref(KEY_SF_MODEL, DEFAULT_SF_MODEL)

    // SiliconFlow：是否使用多模态（Qwen3-Omni 系列，通过 chat/completions）
    var sfUseOmni: Boolean
        get() = sp.getBoolean(KEY_SF_USE_OMNI, false)
        set(value) = sp.edit { putBoolean(KEY_SF_USE_OMNI, value) }

    // SiliconFlow：多模态识别提示词（chat/completions 文本部分）
    var sfOmniPrompt: String
        get() = sp.getString(KEY_SF_OMNI_PROMPT, DEFAULT_SF_OMNI_PROMPT) ?: DEFAULT_SF_OMNI_PROMPT
        set(value) = sp.edit { putString(KEY_SF_OMNI_PROMPT, value) }

    // SiliconFlow 免费服务：是否启用免费 ASR 服务（新用户默认启用）
    var sfFreeAsrEnabled: Boolean
        get() = sp.getBoolean(KEY_SF_FREE_ASR_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_SF_FREE_ASR_ENABLED, value) }

    // SiliconFlow 免费服务：ASR 模型选择
    var sfFreeAsrModel: String
        get() = sp.getString(KEY_SF_FREE_ASR_MODEL, DEFAULT_SF_FREE_ASR_MODEL) ?: DEFAULT_SF_FREE_ASR_MODEL
        set(value) = sp.edit { putString(KEY_SF_FREE_ASR_MODEL, value) }

    // SiliconFlow 免费服务：是否启用免费 LLM（AI 后处理，新用户默认启用）
    var sfFreeLlmEnabled: Boolean
        get() = sp.getBoolean(KEY_SF_FREE_LLM_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_SF_FREE_LLM_ENABLED, value) }

    // SiliconFlow 免费服务：LLM 模型选择
    var sfFreeLlmModel: String
        get() = sp.getString(KEY_SF_FREE_LLM_MODEL, DEFAULT_SF_FREE_LLM_MODEL) ?: DEFAULT_SF_FREE_LLM_MODEL
        set(value) = sp.edit { putString(KEY_SF_FREE_LLM_MODEL, value) }

    // SiliconFlow：是否使用自己的付费 API Key（而非免费服务）
    var sfFreeLlmUsePaidKey: Boolean
        get() = sp.getBoolean(KEY_SF_FREE_LLM_USE_PAID_KEY, false)
        set(value) = sp.edit { putBoolean(KEY_SF_FREE_LLM_USE_PAID_KEY, value) }

    // ========== LLM 供应商选择（新架构） ==========

    // 当前选择的 LLM 供应商（默认使用 SiliconFlow 免费服务）
    var llmVendor: LlmVendor
        get() {
            val stored = sp.getString(KEY_LLM_VENDOR, null)
            // 兼容旧版本：如果未设置供应商，进行智能迁移
            if (stored == null) {
                // 如果启用了 SF 免费 LLM，使用 SF_FREE
                if (sfFreeLlmEnabled) return LlmVendor.SF_FREE
                // 检查是否有配置完整的自定义供应商
                val provider = getActiveLlmProvider()
                if (provider != null && provider.endpoint.isNotBlank() && provider.model.isNotBlank()) {
                    return LlmVendor.CUSTOM
                }
                // 兜底：默认使用 SF_FREE（免费服务无需配置即可使用）
                return LlmVendor.SF_FREE
            }
            return LlmVendor.fromId(stored)
        }
        set(value) = sp.edit { putString(KEY_LLM_VENDOR, value.id) }

    // 内置供应商 API Key 存储（按供应商 ID 分别存储）
    fun getLlmVendorApiKey(vendor: LlmVendor): String {
        val key = "llm_vendor_${vendor.id}_api_key"
        return sp.getString(key, "") ?: ""
    }

    fun setLlmVendorApiKey(vendor: LlmVendor, apiKey: String) {
        val key = "llm_vendor_${vendor.id}_api_key"
        sp.edit { putString(key, apiKey.trim()) }
    }

    // 内置供应商模型选择（按供应商 ID 分别存储）
    fun getLlmVendorModel(vendor: LlmVendor): String {
        val key = "llm_vendor_${vendor.id}_model"
        val stored = sp.getString(key, null)
        // 如果未设置，返回供应商默认模型
        return stored ?: vendor.defaultModel
    }

    fun setLlmVendorModel(vendor: LlmVendor, model: String) {
        val key = "llm_vendor_${vendor.id}_model"
        sp.edit { putString(key, model.trim()) }
    }

    // 内置供应商 Temperature（按供应商 ID 分别存储）
    fun getLlmVendorTemperature(vendor: LlmVendor): Float {
        val key = "llm_vendor_${vendor.id}_temperature"
        return sp.getFloat(key, DEFAULT_LLM_TEMPERATURE)
    }

    fun setLlmVendorTemperature(vendor: LlmVendor, temperature: Float) {
        val key = "llm_vendor_${vendor.id}_temperature"
        sp.edit { putFloat(key, temperature.coerceIn(vendor.temperatureMin, vendor.temperatureMax)) }
    }

    // 内置供应商推理模式开关（按供应商 ID 分别存储，默认关闭）
    fun getLlmVendorReasoningEnabled(vendor: LlmVendor): Boolean {
        val key = "llm_vendor_${vendor.id}_reasoning_enabled"
        return sp.getBoolean(key, false)
    }

    fun setLlmVendorReasoningEnabled(vendor: LlmVendor, enabled: Boolean) {
        val key = "llm_vendor_${vendor.id}_reasoning_enabled"
        sp.edit { putBoolean(key, enabled) }
    }

    /**
     * 获取当前有效的 LLM 配置（根据选择的供应商）
     * @return EffectiveLlmConfig 或 null（如果配置无效）
     */
    fun getEffectiveLlmConfig(): EffectiveLlmConfig? {
        return when (val vendor = llmVendor) {
            LlmVendor.SF_FREE -> {
                val model = if (sfFreeLlmUsePaidKey) {
                    getLlmVendorModel(LlmVendor.SF_FREE).ifBlank { sfFreeLlmModel }
                } else {
                    sfFreeLlmModel
                }
                if (sfFreeLlmUsePaidKey) {
                    // 使用用户自己的付费 API Key
                    val apiKey = getLlmVendorApiKey(LlmVendor.SF_FREE)
                    if (apiKey.isBlank()) {
                        null // 需要 API Key 但未配置
                    } else {
                        EffectiveLlmConfig(
                            endpoint = vendor.endpoint,
                            apiKey = apiKey,
                            model = model,
                            temperature = getLlmVendorTemperature(LlmVendor.SF_FREE),
                            vendor = vendor,
                            enableReasoning = getLlmVendorReasoningEnabled(vendor)
                        )
                    }
                } else {
                    // SiliconFlow 免费服务：使用内置端点和模型，无需 API Key
                    // 实际 API Key 在 LlmPostProcessor 中注入
                    EffectiveLlmConfig(
                        endpoint = vendor.endpoint,
                        apiKey = "", // 免费服务在调用层注入内置 Key
                        model = model,
                        temperature = DEFAULT_LLM_TEMPERATURE,
                        vendor = vendor,
                        enableReasoning = getLlmVendorReasoningEnabled(vendor)
                    )
                }
            }
            LlmVendor.CUSTOM -> {
                // 自定义供应商：使用用户配置的 LlmProvider
                val provider = getActiveLlmProvider()
                if (provider != null && provider.endpoint.isNotBlank()) {
                    EffectiveLlmConfig(
                        endpoint = provider.endpoint,
                        apiKey = provider.apiKey,
                        model = provider.model,
                        temperature = provider.temperature,
                        vendor = vendor,
                        enableReasoning = false  // Custom vendor doesn't support reasoning control
                    )
                } else null
            }
            else -> {
                // 内置供应商：使用预设端点 + 用户 API Key + 用户选择的模型
                val apiKey = getLlmVendorApiKey(vendor)
                val model = getLlmVendorModel(vendor).ifBlank { vendor.defaultModel }
                if (vendor.requiresApiKey && apiKey.isBlank()) {
                    null // 需要 API Key 但未配置
                } else {
                    EffectiveLlmConfig(
                        endpoint = vendor.endpoint,
                        apiKey = apiKey,
                        model = model,
                        temperature = getLlmVendorTemperature(vendor),
                        vendor = vendor,
                        enableReasoning = getLlmVendorReasoningEnabled(vendor)
                    )
                }
            }
        }
    }

    /** 有效的 LLM 配置数据类 */
    data class EffectiveLlmConfig(
        val endpoint: String,
        val apiKey: String,
        val model: String,
        val temperature: Float,
        val vendor: LlmVendor,
        val enableReasoning: Boolean
    )

    // 阿里云百炼（DashScope）凭证
    var dashApiKey: String by stringPref(KEY_DASH_API_KEY, "")


    // DashScope：自定义识别上下文（提示词）
    var dashPrompt: String by stringPref(KEY_DASH_PROMPT, "")

    // DashScope：识别语言（空字符串表示自动/未指定）
    var dashLanguage: String
        get() = sp.getString(KEY_DASH_LANGUAGE, "") ?: ""
        set(value) = sp.edit { putString(KEY_DASH_LANGUAGE, value.trim()) }

    // DashScope：地域（cn=中国大陆，intl=新加坡/国际）。默认 cn
    var dashRegion: String by stringPref(KEY_DASH_REGION, "cn")

    fun getDashHttpBaseUrl(): String {
        return if (dashRegion.equals("intl", ignoreCase = true))
            "https://dashscope-intl.aliyuncs.com/api/v1" else "https://dashscope.aliyuncs.com/api/v1"
    }

    // DashScope：ASR 模型选择（用于替代“流式开关 + Fun-ASR 开关”的组合）
    // - qwen3-asr-flash：非流式
    // - qwen3-asr-flash-realtime：流式（Qwen3）
    // - fun-asr-realtime：流式（Fun-ASR）
    var dashAsrModel: String
        get() {
            val v = (sp.getString(KEY_DASH_ASR_MODEL, "") ?: "").trim()
            if (v.isNotBlank()) return v

            val derived = deriveDashAsrModelFromLegacyFlags()
            // 迁移：写回新 key，后续直接读取
            try {
                sp.edit { putString(KEY_DASH_ASR_MODEL, derived) }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to migrate DashScope model to dash_asr_model", t)
            }
            return derived
        }
        set(value) {
            val model = value.trim().ifBlank { DEFAULT_DASH_MODEL }
            sp.edit {
                putString(KEY_DASH_ASR_MODEL, model)
                // 同步旧开关，便于兼容旧版本导入/导出
                putBoolean(KEY_DASH_STREAMING_ENABLED, model.endsWith("-realtime", ignoreCase = true))
                putBoolean(KEY_DASH_FUNASR_ENABLED, model.startsWith("fun-asr", ignoreCase = true))
            }
        }

    fun isDashStreamingModelSelected(): Boolean {
        return dashAsrModel.endsWith("-realtime", ignoreCase = true)
    }

    fun isDashPromptSupportedByModel(): Boolean {
        return !dashAsrModel.startsWith("fun-asr", ignoreCase = true)
    }

    private fun deriveDashAsrModelFromLegacyFlags(): String {
        val streaming = sp.getBoolean(KEY_DASH_STREAMING_ENABLED, false)
        if (!streaming) return DEFAULT_DASH_MODEL
        val funAsr = sp.getBoolean(KEY_DASH_FUNASR_ENABLED, false)
        return if (funAsr) DASH_MODEL_FUN_ASR_REALTIME else DASH_MODEL_QWEN3_REALTIME
    }

    // DashScope: streaming toggle（legacy，已由 dashAsrModel 替代）
    var dashStreamingEnabled: Boolean
        get() = sp.getBoolean(KEY_DASH_STREAMING_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_DASH_STREAMING_ENABLED, value) }

    // DashScope: streaming 使用 Fun-ASR 模型（legacy，已由 dashAsrModel 替代）
    var dashFunAsrEnabled: Boolean
        get() = sp.getBoolean(KEY_DASH_FUNASR_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_DASH_FUNASR_ENABLED, value) }

    // DashScope: Fun-ASR 使用语义断句（开启时关闭 VAD 断句）
    var dashFunAsrSemanticPunctEnabled: Boolean
        get() = sp.getBoolean(KEY_DASH_FUNASR_SEMANTIC_PUNCT_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_DASH_FUNASR_SEMANTIC_PUNCT_ENABLED, value) }

    // ElevenLabs凭证
    var elevenApiKey: String by stringPref(KEY_ELEVEN_API_KEY, "")

    // ElevenLabs：流式识别开关
    var elevenStreamingEnabled: Boolean
        get() = sp.getBoolean(KEY_ELEVEN_STREAMING_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_ELEVEN_STREAMING_ENABLED, value) }

    // OpenAI 语音转文字（ASR）配置
    var oaAsrEndpoint: String by stringPref(KEY_OA_ASR_ENDPOINT, DEFAULT_OA_ASR_ENDPOINT)

    var oaAsrApiKey: String by stringPref(KEY_OA_ASR_API_KEY, "")

    var oaAsrModel: String by stringPref(KEY_OA_ASR_MODEL, DEFAULT_OA_ASR_MODEL)

    // OpenAI：是否启用自定义 Prompt（部分模型不支持）
    var oaAsrUsePrompt: Boolean
        get() = sp.getBoolean(KEY_OA_ASR_USE_PROMPT, false)
        set(value) = sp.edit { putBoolean(KEY_OA_ASR_USE_PROMPT, value) }

    // OpenAI：自定义识别 Prompt（可选）
    var oaAsrPrompt: String by stringPref(KEY_OA_ASR_PROMPT, "")

    // OpenAI：识别语言（空字符串表示不指定）
    var oaAsrLanguage: String
        get() = sp.getString(KEY_OA_ASR_LANGUAGE, "") ?: ""
        set(value) = sp.edit { putString(KEY_OA_ASR_LANGUAGE, value.trim()) }

    // Google Gemini 语音理解（通过提示词转写）
    var gemEndpoint: String by stringPref(KEY_GEM_ENDPOINT, DEFAULT_GEM_ENDPOINT)

    var gemApiKey: String by stringPref(KEY_GEM_API_KEY, "")

    fun getGeminiApiKeys(): List<String> {
        return gemApiKey.split("\n").map { it.trim() }.filter { it.isNotBlank() }
    }

    var gemModel: String by stringPref(KEY_GEM_MODEL, DEFAULT_GEM_MODEL)

    var gemPrompt: String
        get() = sp.getString(KEY_GEM_PROMPT, DEFAULT_GEM_PROMPT) ?: DEFAULT_GEM_PROMPT
        set(value) = sp.edit { putString(KEY_GEM_PROMPT, value) }

    var geminiDisableThinking: Boolean
        get() = sp.getBoolean(KEY_GEMINI_DISABLE_THINKING, false)
        set(value) = sp.edit { putBoolean(KEY_GEMINI_DISABLE_THINKING, value) }

    // Soniox 语音识别
    var sonioxApiKey: String by stringPref(KEY_SONIOX_API_KEY, "")

    // Soniox：流式识别开关（默认关闭）
    var sonioxStreamingEnabled: Boolean
        get() = sp.getBoolean(KEY_SONIOX_STREAMING_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_SONIOX_STREAMING_ENABLED, value) }

    // Soniox：识别语言提示（language_hints）；空字符串表示不设置（多语言自动）
    var sonioxLanguage: String
        get() = sp.getString(KEY_SONIOX_LANGUAGE, "") ?: ""
        set(value) = sp.edit { putString(KEY_SONIOX_LANGUAGE, value.trim()) }

    // Soniox：多语言提示（JSON 数组字符串），优先于单一字段
    var sonioxLanguagesJson: String by stringPref(KEY_SONIOX_LANGUAGES, "")

    // Soniox：语言严格限制（language_hints_strict）；默认关闭
    var sonioxLanguageHintsStrict: Boolean
        get() = sp.getBoolean(KEY_SONIOX_LANGUAGE_HINTS_STRICT, false)
        set(value) = sp.edit { putBoolean(KEY_SONIOX_LANGUAGE_HINTS_STRICT, value) }

    // 智谱 GLM ASR
    var zhipuApiKey: String by stringPref(KEY_ZHIPU_API_KEY, "")

    // 智谱 GLM：temperature 参数（0.0-1.0，默认 0.95）
    var zhipuTemperature: Float
        get() = sp.getFloat(KEY_ZHIPU_TEMPERATURE, DEFAULT_ZHIPU_TEMPERATURE).coerceIn(0f, 1f)
        set(value) = sp.edit { putFloat(KEY_ZHIPU_TEMPERATURE, value.coerceIn(0f, 1f)) }

    // 智谱 GLM：上下文提示（prompt），用于长文本场景的前文上下文，建议小于8000字
    var zhipuPrompt: String by stringPref(KEY_ZHIPU_PROMPT, "")

    fun getSonioxLanguages(): List<String> {
        val raw = sonioxLanguagesJson.trim()
        if (raw.isBlank()) {
            val single = sonioxLanguage.trim()
            return if (single.isNotEmpty()) listOf(single) else emptyList()
        }
        return try {
            json.decodeFromString<List<String>>(raw).filter { it.isNotBlank() }.distinct()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Soniox languages JSON, falling back to single value", e)
            // 回退到旧的单一字段
            val single = sonioxLanguage.trim()
            if (single.isNotEmpty()) listOf(single) else emptyList()
        }
    }

    fun setSonioxLanguages(list: List<String>) {
        val distinct = list.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        try {
            sonioxLanguagesJson = json.encodeToString(distinct)
            // 兼容旧字段：保留第一个；为空则清空
            sonioxLanguage = distinct.firstOrNull() ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serialize Soniox languages", e)
        }
    }

    // 火山引擎：流式识别开关（与文件模式共享凭证）
    var volcStreamingEnabled: Boolean
        get() = sp.getBoolean(KEY_VOLC_STREAMING_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_VOLC_STREAMING_ENABLED, value) }

    // 火山引擎：双向流式开关（bigmodel_async vs bigmodel_nostream），默认开启
    var volcBidiStreamingEnabled: Boolean
        get() = sp.getBoolean(KEY_VOLC_BIDI_STREAMING_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_VOLC_BIDI_STREAMING_ENABLED, value) }

    // 火山引擎：语义顺滑开关（enable_ddc）
    var volcDdcEnabled: Boolean
        get() = sp.getBoolean(KEY_VOLC_DDC_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_VOLC_DDC_ENABLED, value) }

    // 火山引擎：VAD 分句开关（控制判停参数）
    var volcVadEnabled: Boolean
        get() = sp.getBoolean(KEY_VOLC_VAD_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_VOLC_VAD_ENABLED, value) }

    // 火山引擎：二遍识别开关（enable_nonstream）
    var volcNonstreamEnabled: Boolean
        get() = sp.getBoolean(KEY_VOLC_NONSTREAM_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_VOLC_NONSTREAM_ENABLED, value) }

    // 火山引擎：识别语言（nostream 支持；空=自动中英/方言）
    var volcLanguage: String
        get() = sp.getString(KEY_VOLC_LANGUAGE, "") ?: ""
        set(value) = sp.edit { putString(KEY_VOLC_LANGUAGE, value.trim()) }

    // 火山引擎：首字加速（客户端减小分包时长）
    var volcFirstCharAccelEnabled: Boolean
        get() = sp.getBoolean(KEY_VOLC_FIRST_CHAR_ACCEL_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_VOLC_FIRST_CHAR_ACCEL_ENABLED, value) }

    // 火山引擎：文件识别标准版开关（submit/query）
    var volcFileStandardEnabled: Boolean
        get() = sp.getBoolean(KEY_VOLC_FILE_STANDARD_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_VOLC_FILE_STANDARD_ENABLED, value) }

    // 火山引擎：使用 2.0 模型（默认 true）
    var volcModelV2Enabled: Boolean
        get() = sp.getBoolean(KEY_VOLC_MODEL_V2_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_VOLC_MODEL_V2_ENABLED, value) }

    // 选中的ASR供应商（默认使用 SiliconFlow 免费服务）
    var asrVendor: AsrVendor
        get() = AsrVendor.fromId(sp.getString(KEY_ASR_VENDOR, AsrVendor.SiliconFlow.id))
        set(value) = sp.edit { putString(KEY_ASR_VENDOR, value.id) }

    // ElevenLabs：语言代码（空=自动识别）
    var elevenLanguageCode: String
        get() = sp.getString(KEY_ELEVEN_LANGUAGE_CODE, "") ?: ""
        set(value) = sp.edit { putString(KEY_ELEVEN_LANGUAGE_CODE, value.trim()) }

    // SenseVoice（本地 ASR）设置
    var svModelDir: String
        get() = sp.getString(KEY_SV_MODEL_DIR, "") ?: ""
        set(value) = sp.edit { putString(KEY_SV_MODEL_DIR, value.trim()) }

    // SenseVoice 模型版本：small-int8 / small-full / nano-int8 / nano-full（默认 nano-int8，用于新安装）
    var svModelVariant: String
        get() = sp.getString(KEY_SV_MODEL_VARIANT, "nano-int8") ?: "nano-int8"
        set(value) = sp.edit { putString(KEY_SV_MODEL_VARIANT, value.trim().ifBlank { "nano-int8" }) }

    var svNumThreads: Int
        get() = sp.getInt(KEY_SV_NUM_THREADS, 2).coerceIn(1, 8)
        set(value) = sp.edit { putInt(KEY_SV_NUM_THREADS, value.coerceIn(1, 8)) }

    // SenseVoice：语言（auto/zh/en/ja/ko/yue）与 ITN 开关
    var svLanguage: String
        get() = sp.getString(KEY_SV_LANGUAGE, "auto") ?: "auto"
        set(value) = sp.edit { putString(KEY_SV_LANGUAGE, value.trim().ifBlank { "auto" }) }

    var svUseItn: Boolean
        get() = sp.getBoolean(KEY_SV_USE_ITN, true)
        set(value) = sp.edit { putBoolean(KEY_SV_USE_ITN, value) }

    // SenseVoice：首次显示时预加载（默认关闭）
    var svPreloadEnabled: Boolean
        get() = sp.getBoolean(KEY_SV_PRELOAD_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_SV_PRELOAD_ENABLED, value) }

    // SenseVoice：模型保留时长（分钟）。-1=始终保持；0=识别后立即卸载。
    var svKeepAliveMinutes: Int
        get() = sp.getInt(KEY_SV_KEEP_ALIVE_MINUTES, -1)
        set(value) = sp.edit { putInt(KEY_SV_KEEP_ALIVE_MINUTES, value) }

    // SenseVoice：伪流式模式开关（基于 VAD 分句预览）
    var svPseudoStreamEnabled: Boolean
        get() = sp.getBoolean(KEY_SV_PSEUDO_STREAM_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_SV_PSEUDO_STREAM_ENABLED, value) }

    // TeleSpeech（本地 ASR）
    var tsModelVariant: String
        get() = sp.getString(KEY_TS_MODEL_VARIANT, "int8") ?: "int8"
        set(value) = sp.edit { putString(KEY_TS_MODEL_VARIANT, value.trim().ifBlank { "int8" }) }

    var tsNumThreads: Int
        get() = sp.getInt(KEY_TS_NUM_THREADS, 2).coerceIn(1, 8)
        set(value) = sp.edit { putInt(KEY_TS_NUM_THREADS, value.coerceIn(1, 8)) }

    var tsKeepAliveMinutes: Int
        get() = sp.getInt(KEY_TS_KEEP_ALIVE_MINUTES, -1)
        set(value) = sp.edit { putInt(KEY_TS_KEEP_ALIVE_MINUTES, value) }

    var tsPreloadEnabled: Boolean
        get() = sp.getBoolean(KEY_TS_PRELOAD_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_TS_PRELOAD_ENABLED, value) }

    // TeleSpeech：ITN 开关（反向文本规范化）
    var tsUseItn: Boolean
        get() = sp.getBoolean(KEY_TS_USE_ITN, true)
        set(value) = sp.edit { putBoolean(KEY_TS_USE_ITN, value) }

    // TeleSpeech：伪流式模式开关（基于 VAD 分句预览）
    var tsPseudoStreamEnabled: Boolean
        get() = sp.getBoolean(KEY_TS_PSEUDO_STREAM_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_TS_PSEUDO_STREAM_ENABLED, value) }

    // Paraformer（本地 ASR）
    var pfModelVariant: String
        get() = sp.getString(KEY_PF_MODEL_VARIANT, "bilingual-int8") ?: "bilingual-int8"
        set(value) = sp.edit { putString(KEY_PF_MODEL_VARIANT, value.trim().ifBlank { "bilingual-int8" }) }

    var pfNumThreads: Int
        get() = sp.getInt(KEY_PF_NUM_THREADS, 2).coerceIn(1, 8)
        set(value) = sp.edit { putInt(KEY_PF_NUM_THREADS, value.coerceIn(1, 8)) }

    var pfKeepAliveMinutes: Int
        get() = sp.getInt(KEY_PF_KEEP_ALIVE_MINUTES, -1)
        set(value) = sp.edit { putInt(KEY_PF_KEEP_ALIVE_MINUTES, value) }

    var pfPreloadEnabled: Boolean
        get() = sp.getBoolean(KEY_PF_PRELOAD_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_PF_PRELOAD_ENABLED, value) }

    // Paraformer: ITN 开关（反向文本规范化）
    var pfUseItn: Boolean
        get() = sp.getBoolean(KEY_PF_USE_ITN, true)
        set(value) = sp.edit { putBoolean(KEY_PF_USE_ITN, value) }

    // Zipformer 模型清理标记（移除 Zipformer 支持后仅执行一次）
    var zipformerCleanupDone: Boolean
        get() = sp.getBoolean(KEY_ZIPFORMER_CLEANUP_DONE, false)
        set(value) = sp.edit { putBoolean(KEY_ZIPFORMER_CLEANUP_DONE, value) }
    // --- 供应商配置通用化 ---
    private data class VendorField(val key: String, val required: Boolean = false, val default: String = "")

    private val vendorFields: Map<AsrVendor, List<VendorField>> = mapOf(
        AsrVendor.Volc to listOf(
            VendorField(KEY_APP_KEY, required = true),
            VendorField(KEY_ACCESS_KEY, required = true)
        ),
        // SiliconFlow：免费服务启用时无需 API Key
        AsrVendor.SiliconFlow to listOf(
            VendorField(KEY_SF_API_KEY, required = false),  // 免费服务时无需 API Key
            VendorField(KEY_SF_MODEL, default = DEFAULT_SF_MODEL)
        ),
        AsrVendor.ElevenLabs to listOf(
            VendorField(KEY_ELEVEN_API_KEY, required = true),
            VendorField(KEY_ELEVEN_LANGUAGE_CODE)
        ),
        AsrVendor.OpenAI to listOf(
            VendorField(KEY_OA_ASR_ENDPOINT, required = true, default = DEFAULT_OA_ASR_ENDPOINT),
            VendorField(KEY_OA_ASR_API_KEY, required = false),
            VendorField(KEY_OA_ASR_MODEL, required = true, default = DEFAULT_OA_ASR_MODEL),
            // 可选 Prompt 字段（字符串）；开关为布尔，单独在导入/导出处理
            VendorField(KEY_OA_ASR_PROMPT, required = false, default = ""),
            // 可选语言字段（字符串）
            VendorField(KEY_OA_ASR_LANGUAGE, required = false, default = "")
        ),
        AsrVendor.DashScope to listOf(
            VendorField(KEY_DASH_API_KEY, required = true),
            VendorField(KEY_DASH_PROMPT, default = ""),
            VendorField(KEY_DASH_LANGUAGE, default = "")
        ),
        AsrVendor.Gemini to listOf(
            VendorField(KEY_GEM_ENDPOINT, required = true, default = DEFAULT_GEM_ENDPOINT),
            VendorField(KEY_GEM_API_KEY, required = true),
            VendorField(KEY_GEM_MODEL, required = true, default = DEFAULT_GEM_MODEL),
            VendorField(KEY_GEM_PROMPT, default = DEFAULT_GEM_PROMPT)
        ),
        AsrVendor.Soniox to listOf(
            VendorField(KEY_SONIOX_API_KEY, required = true)
        ),
        AsrVendor.Zhipu to listOf(
            VendorField(KEY_ZHIPU_API_KEY, required = true)
        ),
        // 本地 SenseVoice（sherpa-onnx）无需鉴权
        AsrVendor.SenseVoice to emptyList(),
        // 本地 TeleSpeech（sherpa-onnx）无需鉴权
        AsrVendor.Telespeech to emptyList(),
        // 本地 Paraformer（sherpa-onnx）无需鉴权
        AsrVendor.Paraformer to emptyList()
    )

    fun hasVendorKeys(v: AsrVendor): Boolean {
        val fields = vendorFields[v] ?: return false
        return fields.filter { it.required }.all { f ->
            getPrefString(f.key, f.default).isNotBlank()
        }
    }

    fun hasVolcKeys(): Boolean = hasVendorKeys(AsrVendor.Volc)
    fun hasSfKeys(): Boolean = sfFreeAsrEnabled || sfApiKey.isNotBlank()  // 免费服务启用或有 API Key
    fun hasDashKeys(): Boolean = hasVendorKeys(AsrVendor.DashScope)
    fun hasElevenKeys(): Boolean = hasVendorKeys(AsrVendor.ElevenLabs)
    fun hasOpenAiKeys(): Boolean = hasVendorKeys(AsrVendor.OpenAI)
    fun hasGeminiKeys(): Boolean = hasVendorKeys(AsrVendor.Gemini)
    fun hasSonioxKeys(): Boolean = hasVendorKeys(AsrVendor.Soniox)
    fun hasZhipuKeys(): Boolean = hasVendorKeys(AsrVendor.Zhipu)
    fun hasAsrKeys(): Boolean = hasVendorKeys(asrVendor)
    fun hasLlmKeys(): Boolean {
        // 使用新的 getEffectiveLlmConfig 检查配置有效性
        return getEffectiveLlmConfig() != null
    }

    // 自定义标点按钮（4个位置）
    var punct1: String
        get() = (sp.getString(KEY_PUNCT_1, DEFAULT_PUNCT_1) ?: DEFAULT_PUNCT_1).trim()
        set(value) = sp.edit { putString(KEY_PUNCT_1, value.trim()) }

    var punct2: String
        get() = (sp.getString(KEY_PUNCT_2, DEFAULT_PUNCT_2) ?: DEFAULT_PUNCT_2).trim()
        set(value) = sp.edit { putString(KEY_PUNCT_2, value.trim()) }

    var punct3: String
        get() = (sp.getString(KEY_PUNCT_3, DEFAULT_PUNCT_3) ?: DEFAULT_PUNCT_3).trim()
        set(value) = sp.edit { putString(KEY_PUNCT_3, value.trim()) }

    var punct4: String
        get() = (sp.getString(KEY_PUNCT_4, DEFAULT_PUNCT_4) ?: DEFAULT_PUNCT_4).trim()
        set(value) = sp.edit { putString(KEY_PUNCT_4, value.trim()) }

    // 自定义扩展按钮（4个位置，存储动作类型ID）
    // 默认值（从左到右）：撤销、全选、复制、收起键盘
    var extBtn1: com.brycewg.asrkb.ime.ExtensionButtonAction
        get() {
            val stored = sp.getString(KEY_EXT_BTN_1, null)
            return if (stored == null) {
                com.brycewg.asrkb.ime.ExtensionButtonAction.getDefaults()[0]
            } else {
                com.brycewg.asrkb.ime.ExtensionButtonAction.fromId(stored)
            }
        }
        set(value) = sp.edit { putString(KEY_EXT_BTN_1, value.id) }

    var extBtn2: com.brycewg.asrkb.ime.ExtensionButtonAction
        get() {
            val stored = sp.getString(KEY_EXT_BTN_2, null)
            return if (stored == null) {
                com.brycewg.asrkb.ime.ExtensionButtonAction.getDefaults()[1]
            } else {
                com.brycewg.asrkb.ime.ExtensionButtonAction.fromId(stored)
            }
        }
        set(value) = sp.edit { putString(KEY_EXT_BTN_2, value.id) }

    var extBtn3: com.brycewg.asrkb.ime.ExtensionButtonAction
        get() {
            val stored = sp.getString(KEY_EXT_BTN_3, null)
            return if (stored == null) {
                com.brycewg.asrkb.ime.ExtensionButtonAction.getDefaults()[2]
            } else {
                com.brycewg.asrkb.ime.ExtensionButtonAction.fromId(stored)
            }
        }
        set(value) = sp.edit { putString(KEY_EXT_BTN_3, value.id) }

    var extBtn4: com.brycewg.asrkb.ime.ExtensionButtonAction
        get() {
            val stored = sp.getString(KEY_EXT_BTN_4, null)
            return if (stored == null) {
                com.brycewg.asrkb.ime.ExtensionButtonAction.getDefaults()[3]
            } else {
                com.brycewg.asrkb.ime.ExtensionButtonAction.fromId(stored)
            }
        }
        set(value) = sp.edit { putString(KEY_EXT_BTN_4, value.id) }

    // 历史语音识别总字数（仅统计最终提交到编辑器的识别结果；AI编辑不计入）
    var totalAsrChars: Long
        get() = sp.getLong(KEY_TOTAL_ASR_CHARS, 0L).coerceAtLeast(0L)
        set(value) = sp.edit { putLong(KEY_TOTAL_ASR_CHARS, value.coerceAtLeast(0L)) }

    // 首次启动引导是否已展示
    var hasShownQuickGuideOnce: Boolean
        get() = sp.getBoolean(KEY_SHOWN_QUICK_GUIDE_ONCE, false)
        set(value) = sp.edit { putBoolean(KEY_SHOWN_QUICK_GUIDE_ONCE, value) }

    // 模型选择引导是否已展示
    var hasShownModelGuideOnce: Boolean
        get() = sp.getBoolean(KEY_SHOWN_MODEL_GUIDE_ONCE, false)
        set(value) = sp.edit { putBoolean(KEY_SHOWN_MODEL_GUIDE_ONCE, value) }

    // Pro 版宣传弹窗是否已显示过
    var proPromoShown: Boolean
        get() = sp.getBoolean(KEY_PRO_PROMO_SHOWN, false)
        set(value) = sp.edit { putBoolean(KEY_PRO_PROMO_SHOWN, value) }

    // 隐私：关闭识别历史记录
    var disableAsrHistory: Boolean
        get() = sp.getBoolean(KEY_DISABLE_ASR_HISTORY, false)
        set(value) = sp.edit { putBoolean(KEY_DISABLE_ASR_HISTORY, value) }

    // 隐私：关闭数据统计记录
    var disableUsageStats: Boolean
        get() = sp.getBoolean(KEY_DISABLE_USAGE_STATS, false)
        set(value) = sp.edit { putBoolean(KEY_DISABLE_USAGE_STATS, value) }

    // 隐私：匿名数据采集（PocketBase）开关
    var dataCollectionEnabled: Boolean
        get() = sp.getBoolean(KEY_DATA_COLLECTION_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_DATA_COLLECTION_ENABLED, value) }

    // 匿名数据采集首次同意弹窗是否已展示
    var dataCollectionConsentShown: Boolean
        get() = sp.getBoolean(KEY_DATA_COLLECTION_CONSENT_SHOWN, false)
        set(value) = sp.edit { putBoolean(KEY_DATA_COLLECTION_CONSENT_SHOWN, value) }

    // 匿名统计用户标识（随机生成）
    var analyticsUserId: String
        get() = sp.getString(KEY_ANALYTICS_USER_ID, "").orEmpty()
        set(value) = sp.edit { putString(KEY_ANALYTICS_USER_ID, value) }

    // 每日随机上报时间（分钟，0..1439）
    var analyticsReportMinuteOfDay: Int
        get() = sp.getInt(KEY_ANALYTICS_REPORT_MINUTE, -1)
        set(value) = sp.edit { putInt(KEY_ANALYTICS_REPORT_MINUTE, value.coerceIn(0, 1439)) }

    // 上次成功上报的本地日期（epochDay）
    var analyticsLastUploadEpochDay: Long
        get() = sp.getLong(KEY_ANALYTICS_LAST_UPLOAD_EPOCH_DAY, -1L)
        set(value) = sp.edit { putLong(KEY_ANALYTICS_LAST_UPLOAD_EPOCH_DAY, value) }

    // 上次尝试上报的本地日期（epochDay，用于失败后当天不再重复触发）
    var analyticsLastAttemptEpochDay: Long
        get() = sp.getLong(KEY_ANALYTICS_LAST_ATTEMPT_EPOCH_DAY, -1L)
        set(value) = sp.edit { putLong(KEY_ANALYTICS_LAST_ATTEMPT_EPOCH_DAY, value) }

    // 上次尝试上报的时间戳（ms，用于冷却重试）
    var analyticsLastAttemptEpochMs: Long
        get() = sp.getLong(KEY_ANALYTICS_LAST_ATTEMPT_EPOCH_MS, -1L)
        set(value) = sp.edit { putLong(KEY_ANALYTICS_LAST_ATTEMPT_EPOCH_MS, value) }

    // 当天是否已用过一次冷却重试（epochDay）
    var analyticsRetryUsedEpochDay: Long
        get() = sp.getLong(KEY_ANALYTICS_RETRY_USED_EPOCH_DAY, -1L)
        set(value) = sp.edit { putLong(KEY_ANALYTICS_RETRY_USED_EPOCH_DAY, value) }

    // 一次性迁移标志：重置同意弹窗以重新采集设备信息（v1 修复打包问题后）
    var analyticsConsentResetV1Done: Boolean
        get() = sp.getBoolean(KEY_ANALYTICS_CONSENT_RESET_V1_DONE, false)
        set(value) = sp.edit { putBoolean(KEY_ANALYTICS_CONSENT_RESET_V1_DONE, value) }

    fun addAsrChars(delta: Int) {
        if (delta <= 0) return
        val cur = totalAsrChars
        val next = (cur + delta).coerceAtLeast(0L)
        totalAsrChars = next
    }

    // ===== 使用统计（聚合） =====

    @Serializable
    data class VendorAgg(
        var sessions: Long = 0,
        var chars: Long = 0,
        var audioMs: Long = 0,
        // 非流式请求的供应商处理耗时聚合（毫秒）
        var procMs: Long = 0
    )

    @Serializable
    data class DayAgg(
        var sessions: Long = 0,
        var chars: Long = 0,
        var audioMs: Long = 0,
        var procMs: Long = 0
    )

    @Serializable
    data class UsageStats(
        var totalSessions: Long = 0,
        var totalChars: Long = 0,
        var totalAudioMs: Long = 0,
        var totalProcMs: Long = 0,
        var perVendor: MutableMap<String, VendorAgg> = mutableMapOf(),
        var daily: MutableMap<String, DayAgg> = mutableMapOf(),
        var firstUseDate: String = ""
    )

    private var usageStatsJson: String by stringPref(KEY_USAGE_STATS_JSON, "")

    // 首次使用日期（yyyyMMdd）。若为空将在首次读取 UsageStats 时写入今天。
    var firstUseDate: String by stringPref(KEY_FIRST_USE_DATE, "")

    fun getUsageStats(): UsageStats {
        if (usageStatsJson.isBlank()) {
            val today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
            if (firstUseDate.isBlank()) firstUseDate = today
            return UsageStats(firstUseDate = firstUseDate)
        }
        return try {
            val stats = json.decodeFromString<UsageStats>(usageStatsJson)
            // 兼容老数据：填充 firstUseDate
            if (stats.firstUseDate.isBlank()) {
                val fud = firstUseDate.ifBlank { LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) }
                stats.firstUseDate = fud
                setUsageStats(stats)
            }
            stats
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse UsageStats JSON", e)
            val today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
            if (firstUseDate.isBlank()) firstUseDate = today
            UsageStats(firstUseDate = firstUseDate)
        }
    }

    private fun setUsageStats(stats: UsageStats) {
        try {
            usageStatsJson = json.encodeToString(stats)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serialize UsageStats", e)
        }
    }

    /**
     * 清空使用统计聚合与总字数。
     * 注：firstUseDate 不清空，以保持“陪伴天数”展示的连续性。
     */
    fun resetUsageStats() {
        try {
            usageStatsJson = ""
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to reset usageStatsJson", t)
        }
        try {
            totalAsrChars = 0
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to reset totalAsrChars", t)
        }
    }

    /**
     * 记录一次“最终提交”的使用统计（仅在有最终文本提交时调用）。
     * @param source 用途来源："ime" / "floating" / "aiEdit"（当前仅 ime 与 floating 计入平均值）
     * @param vendor 供应商（用于 perVendor 聚合）
     * @param audioMs 本次会话的录音时长（毫秒）
     * @param chars 提交的字符数
     */
    fun recordUsageCommit(source: String, vendor: AsrVendor, audioMs: Long, chars: Int, procMs: Long = 0L) {
        if (chars <= 0 && audioMs <= 0) return
        val today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
        val stats = getUsageStats()

        stats.totalSessions += 1
        stats.totalChars += chars.coerceAtLeast(0)
        stats.totalAudioMs += audioMs.coerceAtLeast(0L)
        stats.totalProcMs += procMs.coerceAtLeast(0L)

        val key = vendor.id
        val va = stats.perVendor[key] ?: VendorAgg()
        va.sessions += 1
        va.chars += chars.coerceAtLeast(0)
        va.audioMs += audioMs.coerceAtLeast(0L)
        va.procMs += procMs.coerceAtLeast(0L)
        stats.perVendor[key] = va

        val da = stats.daily[today] ?: DayAgg()
        da.sessions += 1
        da.chars += chars.coerceAtLeast(0)
        da.audioMs += audioMs.coerceAtLeast(0L)
        da.procMs += procMs.coerceAtLeast(0L)
        stats.daily[today] = da

        // 裁剪 daily 至最近 400 天（防止无限增长）
        try {
            if (stats.daily.size > 400) {
                val keys = stats.daily.keys.sorted()
                val toDrop = keys.size - 400
                keys.take(toDrop).forEach { stats.daily.remove(it) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to prune daily stats", t)
        }

        setUsageStats(stats)
        val syncedTotalChars = stats.totalChars.coerceAtLeast(0)
        if (syncedTotalChars > totalAsrChars) {
            totalAsrChars = syncedTotalChars
        }
    }

    /**
     * 计算“陪伴天数”。若缺少 firstUseDate，以今天为首次使用（=1天）。
     */
    fun getDaysSinceFirstUse(): Long {
        val fud = firstUseDate.ifBlank {
            val today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
            firstUseDate = today
            today
        }
        return try {
            val start = LocalDate.parse(fud, DateTimeFormatter.BASIC_ISO_DATE)
            val now = LocalDate.now()
            java.time.temporal.ChronoUnit.DAYS.between(start, now) + 1 // 含当天
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse firstUseDate '$fud'", e)
            1
        }
    }

    // ---- SyncClipboard 偏好项 ----
    var syncClipboardEnabled: Boolean
        get() = sp.getBoolean(KEY_SC_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_SC_ENABLED, value) }

    var syncClipboardServerBase: String
        get() = sp.getString(KEY_SC_SERVER_BASE, "") ?: ""
        set(value) = sp.edit { putString(KEY_SC_SERVER_BASE, value.trim()) }

    var syncClipboardUsername: String
        get() = sp.getString(KEY_SC_USERNAME, "") ?: ""
        set(value) = sp.edit { putString(KEY_SC_USERNAME, value.trim()) }

    var syncClipboardPassword: String
        get() = sp.getString(KEY_SC_PASSWORD, "") ?: ""
        set(value) = sp.edit { putString(KEY_SC_PASSWORD, value.trim()) }

    var syncClipboardAutoPullEnabled: Boolean
        get() = sp.getBoolean(KEY_SC_AUTO_PULL, false)
        set(value) = sp.edit { putBoolean(KEY_SC_AUTO_PULL, value) }

    var syncClipboardPullIntervalSec: Int
        get() = sp.getInt(KEY_SC_PULL_INTERVAL_SEC, 15).coerceIn(1, 600)
        set(value) = sp.edit { putInt(KEY_SC_PULL_INTERVAL_SEC, value.coerceIn(1, 600)) }

    // 仅用于变更检测（不上报/不导出）
    var syncClipboardLastUploadedHash: String
        get() = sp.getString(KEY_SC_LAST_UP_HASH, "") ?: ""
        set(value) = sp.edit { putString(KEY_SC_LAST_UP_HASH, value) }

    // 记录最近一次处理的云端剪贴板文件名（用于避免重复文件预览）
    var syncClipboardLastFileName: String
        get() = sp.getString(KEY_SC_LAST_FILE_NAME, "") ?: ""
        set(value) = sp.edit { putString(KEY_SC_LAST_FILE_NAME, value) }

    // ---- 备份/同步（WebDAV）偏好项 ----
    var webdavUrl: String
        get() = sp.getString(KEY_WD_URL, "") ?: ""
        set(value) = sp.edit { putString(KEY_WD_URL, value.trim()) }

    var webdavUsername: String
        get() = sp.getString(KEY_WD_USERNAME, "") ?: ""
        set(value) = sp.edit { putString(KEY_WD_USERNAME, value.trim()) }

    var webdavPassword: String
        get() = sp.getString(KEY_WD_PASSWORD, "") ?: ""
        set(value) = sp.edit { putString(KEY_WD_PASSWORD, value.trim()) }

    // ---- APK 更新：待安装文件路径（授权后自动继续安装使用；不参与导出） ----
    var pendingApkPath: String
        get() = sp.getString(KEY_PENDING_APK_PATH, "") ?: ""
        set(value) = sp.edit { putString(KEY_PENDING_APK_PATH, value.trim()) }

    companion object {
        private const val TAG = "Prefs"
        @Volatile private var TOGGLE_LISTENER_REGISTERED: Boolean = false
        private val globalToggleListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            try {
                val v = prefs.all[key]
                if (v is Boolean) {
                    DebugLogManager.log("prefs", "toggle", mapOf("key" to key, "value" to v))
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to log pref toggle", t)
            }
        }

        private const val KEY_APP_KEY = "app_key"
        private const val KEY_ACCESS_KEY = "access_key"
        private const val KEY_TRIM_FINAL_TRAILING_PUNCT = "trim_final_trailing_punct"
        private const val KEY_MIC_HAPTIC_ENABLED = "mic_haptic_enabled"
        private const val KEY_MIC_TAP_TOGGLE_ENABLED = "mic_tap_toggle_enabled"
        private const val KEY_AUTO_START_RECORDING_ON_SHOW = "auto_start_recording_on_show"
        private const val KEY_DUCK_MEDIA_ON_RECORD = "duck_media_on_record"
        private const val KEY_OFFLINE_DENOISE_ENABLED = "offline_denoise_enabled"
        private const val KEY_AUTO_STOP_ON_SILENCE_ENABLED = "auto_stop_on_silence_enabled"
        private const val KEY_AUTO_STOP_SILENCE_WINDOW_MS = "auto_stop_silence_window_ms"
        private const val KEY_AUTO_STOP_SILENCE_SENSITIVITY = "auto_stop_silence_sensitivity"
        private const val KEY_KEYBOARD_HEIGHT_TIER = "keyboard_height_tier"
        private const val KEY_KEYBOARD_BOTTOM_PADDING_DP = "keyboard_bottom_padding_dp"
        private const val KEY_WAVEFORM_SENSITIVITY = "waveform_sensitivity"
        private const val KEY_FLOATING_SWITCHER_ENABLED = "floating_switcher_enabled"
        private const val KEY_FLOATING_SWITCHER_ALPHA = "floating_switcher_alpha"
        private const val KEY_FLOATING_BALL_SIZE_DP = "floating_ball_size_dp"
        private const val KEY_FLOATING_POS_X = "floating_ball_pos_x"
        private const val KEY_FLOATING_POS_Y = "floating_ball_pos_y"
        private const val KEY_FLOATING_DIRECT_DRAG_ENABLED = "floating_ball_direct_drag_enabled"
        private const val KEY_SWAP_AI_EDIT_IME_SWITCHER = "swap_ai_edit_ime_switcher"
        private const val KEY_FCITX5_RETURN_ON_SWITCHER = "fcitx5_return_on_switcher"
        private const val KEY_RETURN_PREV_IME_ON_HIDE = "return_prev_ime_on_hide"
        private const val KEY_IME_SWITCH_TARGET_ID = "ime_switch_target_id"
        private const val KEY_HIDE_RECENT_TASK_CARD = "hide_recent_task_card"
        private const val KEY_FLOATING_WRITE_COMPAT_ENABLED = "floating_write_compat_enabled"
        private const val KEY_FLOATING_WRITE_PASTE_ENABLED = "floating_write_paste_enabled"
        private const val KEY_FLOATING_ASR_ENABLED = "floating_asr_enabled"
        private const val KEY_FLOATING_ONLY_WHEN_IME_VISIBLE = "floating_only_when_ime_visible"
        
        private const val KEY_FLOATING_WRITE_COMPAT_PACKAGES = "floating_write_compat_packages"
        private const val KEY_FLOATING_WRITE_PASTE_PACKAGES = "floating_write_paste_packages"
        private const val KEY_POSTPROC_ENABLED = "postproc_enabled"
        private const val KEY_APP_LANGUAGE_TAG = "app_language_tag"
        private const val KEY_LAST_UPDATE_CHECK_DATE = "last_update_check_date"
        private const val KEY_LLM_ENDPOINT = "llm_endpoint"
        private const val KEY_LLM_API_KEY = "llm_api_key"
        private const val KEY_LLM_MODEL = "llm_model"
        private const val KEY_LLM_TEMPERATURE = "llm_temperature"
        private const val KEY_LLM_PROVIDERS = "llm_providers"
        private const val KEY_LLM_ACTIVE_ID = "llm_active_id"
        private const val KEY_LLM_VENDOR = "llm_vendor"
        private const val KEY_LLM_PROMPT = "llm_prompt"
        private const val KEY_LLM_PROMPT_PRESETS = "llm_prompt_presets"
        private const val KEY_LLM_PROMPT_ACTIVE_ID = "llm_prompt_active_id"
        private const val KEY_SPEECH_PRESETS = "speech_presets"
        private const val KEY_SPEECH_PRESET_ACTIVE_ID = "speech_preset_active_id"
        private const val KEY_ASR_VENDOR = "asr_vendor"
        private const val KEY_SF_API_KEY = "sf_api_key"
        private const val KEY_SF_MODEL = "sf_model"
        private const val KEY_SF_USE_OMNI = "sf_use_omni"
        private const val KEY_SF_OMNI_PROMPT = "sf_omni_prompt"
        // SiliconFlow 免费服务
        private const val KEY_SF_FREE_ASR_ENABLED = "sf_free_asr_enabled"
        private const val KEY_SF_FREE_ASR_MODEL = "sf_free_asr_model"
        private const val KEY_SF_FREE_LLM_ENABLED = "sf_free_llm_enabled"
        private const val KEY_SF_FREE_LLM_MODEL = "sf_free_llm_model"
        private const val KEY_SF_FREE_LLM_USE_PAID_KEY = "sf_free_llm_use_paid_key"
        private const val KEY_ELEVEN_API_KEY = "eleven_api_key"
        private const val KEY_ELEVEN_STREAMING_ENABLED = "eleven_streaming_enabled"
        private const val KEY_ELEVEN_LANGUAGE_CODE = "eleven_language_code"
        private const val KEY_OA_ASR_ENDPOINT = "oa_asr_endpoint"
        private const val KEY_OA_ASR_API_KEY = "oa_asr_api_key"
        private const val KEY_OA_ASR_MODEL = "oa_asr_model"
        private const val KEY_OA_ASR_USE_PROMPT = "oa_asr_use_prompt"
        private const val KEY_OA_ASR_PROMPT = "oa_asr_prompt"
        private const val KEY_OA_ASR_LANGUAGE = "oa_asr_language"
        private const val KEY_NUMPAD_CN_PUNCT = "numpad_cn_punct"
        private const val KEY_GEM_ENDPOINT = "gem_endpoint"
        private const val KEY_GEM_API_KEY = "gem_api_key"
        private const val KEY_GEM_MODEL = "gem_model"
        private const val KEY_GEM_PROMPT = "gem_prompt"
        private const val KEY_GEMINI_DISABLE_THINKING = "gemini_disable_thinking"
        // Zhipu GLM ASR
        private const val KEY_ZHIPU_API_KEY = "zhipu_api_key"
        private const val KEY_ZHIPU_TEMPERATURE = "zhipu_temperature"
        private const val KEY_ZHIPU_PROMPT = "zhipu_prompt"
        private const val KEY_VOLC_STREAMING_ENABLED = "volc_streaming_enabled"
        private const val KEY_VOLC_BIDI_STREAMING_ENABLED = "volc_bidi_streaming_enabled"
        private const val KEY_DASH_STREAMING_ENABLED = "dash_streaming_enabled"
        private const val KEY_DASH_FUNASR_ENABLED = "dash_funasr_enabled"
        private const val KEY_DASH_ASR_MODEL = "dash_asr_model"
        private const val KEY_DASH_FUNASR_SEMANTIC_PUNCT_ENABLED = "dash_funasr_semantic_punct_enabled"
        private const val KEY_VOLC_DDC_ENABLED = "volc_ddc_enabled"
        private const val KEY_VOLC_VAD_ENABLED = "volc_vad_enabled"
        private const val KEY_VOLC_NONSTREAM_ENABLED = "volc_nonstream_enabled"
        private const val KEY_VOLC_LANGUAGE = "volc_language"
        private const val KEY_VOLC_FIRST_CHAR_ACCEL_ENABLED = "volc_first_char_accel_enabled"
        private const val KEY_VOLC_FILE_STANDARD_ENABLED = "volc_file_standard_enabled"
        private const val KEY_VOLC_MODEL_V2_ENABLED = "volc_model_v2_enabled"
        private const val KEY_DASH_API_KEY = "dash_api_key"
        private const val KEY_DASH_PROMPT = "dash_prompt"
        private const val KEY_DASH_LANGUAGE = "dash_language"
        private const val KEY_DASH_REGION = "dash_region"
        private const val KEY_SONIOX_API_KEY = "soniox_api_key"
        private const val KEY_SONIOX_STREAMING_ENABLED = "soniox_streaming_enabled"
        private const val KEY_SONIOX_LANGUAGE = "soniox_language"
        private const val KEY_SONIOX_LANGUAGES = "soniox_languages"
        private const val KEY_SONIOX_LANGUAGE_HINTS_STRICT = "soniox_language_hints_strict"
        private const val KEY_PUNCT_1 = "punct_1"
        private const val KEY_PUNCT_2 = "punct_2"
        private const val KEY_PUNCT_3 = "punct_3"
        private const val KEY_PUNCT_4 = "punct_4"
        private const val KEY_EXT_BTN_1 = "ext_btn_1"
        private const val KEY_EXT_BTN_2 = "ext_btn_2"
        private const val KEY_EXT_BTN_3 = "ext_btn_3"
        private const val KEY_EXT_BTN_4 = "ext_btn_4"
        private const val KEY_TOTAL_ASR_CHARS = "total_asr_chars"
        // SenseVoice（本地 ASR）
        private const val KEY_SV_MODEL_DIR = "sv_model_dir"
        private const val KEY_SV_MODEL_VARIANT = "sv_model_variant"
        private const val KEY_SV_NUM_THREADS = "sv_num_threads"
        private const val KEY_SV_LANGUAGE = "sv_language"
        private const val KEY_SV_USE_ITN = "sv_use_itn"
        private const val KEY_SV_PRELOAD_ENABLED = "sv_preload_enabled"
        private const val KEY_SV_KEEP_ALIVE_MINUTES = "sv_keep_alive_minutes"
        private const val KEY_SV_PSEUDO_STREAM_ENABLED = "sv_pseudo_stream_enabled"
        // TeleSpeech（本地 ASR）
        private const val KEY_TS_MODEL_VARIANT = "ts_model_variant"
        private const val KEY_TS_NUM_THREADS = "ts_num_threads"
        private const val KEY_TS_KEEP_ALIVE_MINUTES = "ts_keep_alive_minutes"
        private const val KEY_TS_PRELOAD_ENABLED = "ts_preload_enabled"
        private const val KEY_TS_USE_ITN = "ts_use_itn"
        private const val KEY_TS_PSEUDO_STREAM_ENABLED = "ts_pseudo_stream_enabled"
        // Paraformer（本地 ASR）
        private const val KEY_PF_MODEL_VARIANT = "pf_model_variant"
        private const val KEY_PF_NUM_THREADS = "pf_num_threads"
        private const val KEY_PF_KEEP_ALIVE_MINUTES = "pf_keep_alive_minutes"
        private const val KEY_PF_PRELOAD_ENABLED = "pf_preload_enabled"
        private const val KEY_PF_USE_ITN = "pf_use_itn"
        private const val KEY_ZIPFORMER_CLEANUP_DONE = "zipformer_cleanup_done"
        private const val KEY_AI_EDIT_DEFAULT_TO_LAST_ASR = "ai_edit_default_to_last_asr"
        private const val KEY_POSTPROC_SKIP_UNDER_CHARS = "postproc_skip_under_chars"
        private const val KEY_HEADSET_MIC_PRIORITY_ENABLED = "headset_mic_priority_enabled"
        // 允许外部输入法联动（AIDL）
        const val KEY_EXTERNAL_AIDL_ENABLED = "external_aidl_enabled"
        private const val KEY_EXTERNAL_AIDL_GUIDE_SHOWN = "external_aidl_guide_shown"
        private const val KEY_USAGE_STATS_JSON = "usage_stats"
        // ASR 历史（JSON 数组字符串），用于备份/恢复
        private const val KEY_ASR_HISTORY_JSON = "asr_history"
        // 剪贴板历史：非固定与固定分开存储；仅固定参与备份
        private const val KEY_CLIP_HISTORY_JSON = "clip_history"
        private const val KEY_CLIP_PINNED_JSON = "clip_pinned"
        private const val KEY_FIRST_USE_DATE = "first_use_date"
        private const val KEY_SHOWN_QUICK_GUIDE_ONCE = "shown_quick_guide_once"
        private const val KEY_SHOWN_MODEL_GUIDE_ONCE = "shown_model_guide_once"
        private const val KEY_PRO_PROMO_SHOWN = "pro_promo_shown"

        // 隐私：关闭识别历史与使用统计记录
        private const val KEY_DISABLE_ASR_HISTORY = "disable_asr_history"
        private const val KEY_DISABLE_USAGE_STATS = "disable_usage_stats"
        private const val KEY_DATA_COLLECTION_ENABLED = "data_collection_enabled"
        private const val KEY_DATA_COLLECTION_CONSENT_SHOWN = "data_collection_consent_shown"
        private const val KEY_ANALYTICS_USER_ID = "analytics_user_id"
        private const val KEY_ANALYTICS_REPORT_MINUTE = "analytics_report_minute"
        private const val KEY_ANALYTICS_LAST_UPLOAD_EPOCH_DAY = "analytics_last_upload_epoch_day"
        private const val KEY_ANALYTICS_LAST_ATTEMPT_EPOCH_DAY = "analytics_last_attempt_epoch_day"
        private const val KEY_ANALYTICS_LAST_ATTEMPT_EPOCH_MS = "analytics_last_attempt_epoch_ms"
        private const val KEY_ANALYTICS_RETRY_USED_EPOCH_DAY = "analytics_retry_used_epoch_day"
        private const val KEY_ANALYTICS_CONSENT_RESET_V1_DONE = "analytics_consent_reset_v1_done"

        // SyncClipboard keys
        private const val KEY_SC_ENABLED = "syncclip_enabled"
        private const val KEY_SC_SERVER_BASE = "syncclip_server_base"
        private const val KEY_SC_USERNAME = "syncclip_username"
        private const val KEY_SC_PASSWORD = "syncclip_password"
        private const val KEY_SC_AUTO_PULL = "syncclip_auto_pull"
        private const val KEY_SC_PULL_INTERVAL_SEC = "syncclip_pull_interval_sec"
        private const val KEY_SC_LAST_UP_HASH = "syncclip_last_uploaded_hash"
        private const val KEY_SC_LAST_FILE_NAME = "syncclip_last_file_name"

        // WebDAV 备份
        private const val KEY_WD_URL = "wd_url"
        private const val KEY_WD_USERNAME = "wd_username"
        private const val KEY_WD_PASSWORD = "wd_password"
        private const val KEY_PENDING_APK_PATH = "pending_apk_path"

        const val DEFAULT_ENDPOINT = "https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash"
        const val SF_ENDPOINT = "https://api.siliconflow.cn/v1/audio/transcriptions"
        const val SF_CHAT_COMPLETIONS_ENDPOINT = "https://api.siliconflow.cn/v1/chat/completions"
        const val DEFAULT_SF_MODEL = "FunAudioLLM/SenseVoiceSmall"
        const val DEFAULT_SF_OMNI_MODEL = "Qwen/Qwen3-Omni-30B-A3B-Instruct"
        const val DEFAULT_SF_OMNI_PROMPT = "请将以下音频逐字转写为文本，不要输出解释或前后缀。输入语言可能是中文、英文或其他语言"

        // SiliconFlow 免费服务模型配置
        const val DEFAULT_SF_FREE_ASR_MODEL = "FunAudioLLM/SenseVoiceSmall"  // 免费 ASR 默认模型
        const val DEFAULT_SF_FREE_LLM_MODEL = "Qwen/Qwen3-8B"  // 免费 LLM 默认模型
        // 免费 ASR 可选模型列表
        val SF_FREE_ASR_MODELS = listOf(
            "FunAudioLLM/SenseVoiceSmall",
            "TeleAI/TeleSpeechASR"
        )
        // 免费 LLM 可选模型列表
        val SF_FREE_LLM_MODELS = listOf(
            "Qwen/Qwen3-8B",
            "THUDM/GLM-4-9B-0414"
        )

        // OpenAI Audio Transcriptions 默认值
        const val DEFAULT_OA_ASR_ENDPOINT = "https://api.openai.com/v1/audio/transcriptions"
        const val DEFAULT_OA_ASR_MODEL = "gpt-4o-mini-transcribe"

        // DashScope 默认
        const val DEFAULT_DASH_MODEL = "qwen3-asr-flash"
        const val DASH_MODEL_QWEN3_REALTIME = "qwen3-asr-flash-realtime"
        const val DASH_MODEL_FUN_ASR_REALTIME = "fun-asr-realtime"
        // Gemini 默认
        const val DEFAULT_GEM_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta"
        const val DEFAULT_GEM_MODEL = "gemini-2.5-flash"
        const val DEFAULT_GEM_PROMPT = "请将以下音频逐字转写为文本，不要输出解释或前后缀。"

        // Zhipu GLM ASR 默认
        const val DEFAULT_ZHIPU_TEMPERATURE = 0.95f

        // 合理的OpenAI格式默认值
        const val DEFAULT_LLM_ENDPOINT = "https://api.openai.com/v1"
        const val DEFAULT_LLM_MODEL = "gpt-4o-mini"
        const val DEFAULT_LLM_TEMPERATURE = 0.2f

        // 静音自动判停默认值
        const val DEFAULT_SILENCE_WINDOW_MS = 1200
        const val DEFAULT_SILENCE_SENSITIVITY = 4 // 1-10

        // 标点按钮默认值
        const val DEFAULT_PUNCT_1 = "，"
        const val DEFAULT_PUNCT_2 = "。"
        const val DEFAULT_PUNCT_3 = "！"
        const val DEFAULT_PUNCT_4 = "？"

        // 悬浮球默认大小（dp）
        const val DEFAULT_FLOATING_BALL_SIZE_DP = 44
        // 悬浮写入兼容：默认目标包名（每行一个；支持前缀匹配）
        const val DEFAULT_FLOATING_WRITE_COMPAT_PACKAGES = "org.telegram.messenger\nnu.gpu.nagram"
        

        // Soniox 默认端点
        const val SONIOX_API_BASE_URL = "https://api.soniox.com"
        const val SONIOX_FILES_ENDPOINT = "$SONIOX_API_BASE_URL/v1/files"
        const val SONIOX_TRANSCRIPTIONS_ENDPOINT = "$SONIOX_API_BASE_URL/v1/transcriptions"
        const val SONIOX_WS_URL = "wss://stt-rt.soniox.com/transcribe-websocket"

        private fun buildDefaultPromptPresets(): List<PromptPreset> {
            // p0: 通用后处理（默认预设）
            val p0 = PromptPreset(
                id = java.util.UUID.randomUUID().toString(),
                title = "通用后处理",
                content = """# 角色

你是一个顶级的 ASR（自动语音识别）后处理专家。

# 任务

用户会向你发送一段由 ASR 系统转录的原始文本。你的任务是将其处理一遍。

# 规则

1.  **去除无关填充词**: 彻底删除所有无意义的语气词、犹豫词和口头禅。
    - **示例**: "嗯"、"啊"、"呃"、"那个"、"然后"、"就是说"等。
2.  **合并重复与修正口误**: 当说话者重复单词、短语或进行自我纠正时，整合这些内容，只保留其最终的、最清晰的意图。
    - **重复示例**: 将"我想...我想去..."修正为"我想去..."。
    - **口误修正示例**: 将"我们明天去上海，哦不对，去苏州开会"修正为"我们明天去苏州开会"。
3.  **修正识别错误**: 根据上下文语境，纠正明显不符合逻辑的同音、近音词汇。
    - **同音词示例**: 将"请大家准时参加明天的『会意』"修正为"请大家准时参加明天的『会议』"
4.  **保持语义完整性**: 确保修正后的文本忠实于说话者的原始意图，不要进行主观臆断或添加额外信息。保留用户语气，无需进行书面化等风格化处理。
5.  输入格式提示：用户输入会以“待处理文本:”开头，该标签仅用于标记，请忽略标签本身，只处理其后的正文。

# 输出要求

- 只输出修正后的最终文本
- 不要输出任何解释、前后缀、引号或 Markdown 格式
- 如果输入文本已经足够规范，直接原样输出

# 示例

**输入**: "嗯...那个...我想确认一下，我们明天，我们明天的那个会意，啊不对，会议，时间是不是...是不是上午九点？"
**输出**: 我想确认一下，我们明天的会议时间是不是上午九点？"""
            )
            val p1 = PromptPreset(
                id = java.util.UUID.randomUUID().toString(),
                title = "基础文本润色",
                content = """# 角色

你是一个专业的中文文本编辑器。

# 任务

用户会向你发送一段由语音识别（ASR）系统生成的原始文本。你的任务是对其进行润色和修正。

# 规则

1. 修正所有错别字和语法错误
2. 添加正确、自然的标点符号
3. 删除口语化的词语、重复和无意义的填充词（例如嗯、啊、那个）
4. 在保持原意不变的前提下，让句子表达更流畅、更书面化
5. 不要添加任何原始文本中没有的信息
6. 忽略用户输入开头的“待处理文本:”标签，只处理标签后的正文

# 输出要求

- 只输出润色后的文本
- 不要输出任何解释、前后缀、引号或 Markdown 格式

# 示例

**输入**: "那个我觉得这个方案还是有点问题，嗯，主要是成本太高了，然后时间也不够"
**输出**: 我觉得这个方案存在一些问题，主要是成本过高，而且时间也不充裕。"""
            )
            val p2 = PromptPreset(
                id = java.util.UUID.randomUUID().toString(),
                title = "翻译为英文",
                content = """# 角色

你是一个专业的翻译助手。

# 任务

用户会向你发送一段文本。你的任务是将其翻译为英语。

# 规则

1. 准确传达原文的核心意思
2. 保持原文的语气（例如，正式、非正式、紧急等）
3. 译文流畅、符合目标语言的表达习惯
4. 忽略输入开头用于标记的“待处理文本:”标签，只翻译其后的正文

# 输出要求

- 只输出翻译后的英文文本
- 不要输出任何解释、前后缀、引号或 Markdown 格式

# 示例

**输入**: "请在下周五之前提交季度报告"
**输出**: Please submit the quarterly report by next Friday."""
            )
            val p3 = PromptPreset(
                id = java.util.UUID.randomUUID().toString(),
                title = "提取关键要点",
                content = """# 角色

你是一个专业的信息提取助手。

# 任务

用户会向你发送一段文本。你的任务是从中提取核心要点。

# 规则

1. 识别并提取文本中的核心信息和关键要点
2. 每个要点应简洁明了
3. 使用无序列表（bullet points）格式呈现
4. 忽略输入开头用于标记的“待处理文本:”标签，只处理标签后的正文

# 输出要求

- 输出格式为无序列表
- 不要添加额外的解释或前后缀

# 示例

**输入**: "今天的会议主要讨论了三件事，第一是下个月的产品发布时间定在15号，第二是需要增加两名测试人员，第三是市场部提出要加大社交媒体的推广力度"
**输出**:
- 产品发布时间定于下月15号
- 需增加两名测试人员
- 市场部建议加强社交媒体推广"""
            )
            val p4 = PromptPreset(
                id = java.util.UUID.randomUUID().toString(),
                title = "提取待办事项",
                content = """# 角色

你是一个专业的任务提取助手。

# 任务

用户会向你发送一段文本。你的任务是从中识别并提取所有待办事项（Action Items）。

# 规则

1. 识别文本中提到的任务、行动项目或需要完成的事项
2. 如果文本中提到了负责人和截止日期，一并提取
3. 如果信息不完整，则省略相应部分
4. 忽略输入开头用于标记的“待处理文本:”标签，只处理标签后的正文

# 输出要求

使用以下格式输出：
- [ ] [任务内容1]
- [ ] [任务内容2]

如果没有找到任何待办事项，输出"未找到待办事项"。

# 示例

**输入**: "小王你记一下，这周五之前把测试报告发给我，还有让小李下周一之前完成UI设计稿"
**输出**:
- [ ] 小王：本周五前提交测试报告
- [ ] 小李：下周一前完成UI设计稿"""
            )
            return listOf(p0, p1, p2, p3, p4)
        }
    }

    // 导出全部设置为 JSON 字符串（包含密钥，仅用于本地备份/迁移）
    fun exportJsonString(): String {
        val o = org.json.JSONObject()
        o.put("_version", 1)
        o.put(KEY_APP_KEY, appKey)
        o.put(KEY_ACCESS_KEY, accessKey)
        o.put(KEY_TRIM_FINAL_TRAILING_PUNCT, trimFinalTrailingPunct)
        o.put(KEY_MIC_HAPTIC_ENABLED, micHapticEnabled)
        o.put(KEY_MIC_TAP_TOGGLE_ENABLED, micTapToggleEnabled)
        o.put(KEY_AUTO_START_RECORDING_ON_SHOW, autoStartRecordingOnShow)
        o.put(KEY_DUCK_MEDIA_ON_RECORD, duckMediaOnRecordEnabled)
        o.put(KEY_OFFLINE_DENOISE_ENABLED, offlineDenoiseEnabled)
        o.put(KEY_AUTO_STOP_ON_SILENCE_ENABLED, autoStopOnSilenceEnabled)
        o.put(KEY_AUTO_STOP_SILENCE_WINDOW_MS, autoStopSilenceWindowMs)
        o.put(KEY_AUTO_STOP_SILENCE_SENSITIVITY, autoStopSilenceSensitivity)
        o.put(KEY_KEYBOARD_HEIGHT_TIER, keyboardHeightTier)
        o.put(KEY_KEYBOARD_BOTTOM_PADDING_DP, keyboardBottomPaddingDp)
        o.put(KEY_WAVEFORM_SENSITIVITY, waveformSensitivity)
        o.put(KEY_SWAP_AI_EDIT_IME_SWITCHER, swapAiEditWithImeSwitcher)
        o.put(KEY_FCITX5_RETURN_ON_SWITCHER, fcitx5ReturnOnImeSwitch)
        o.put(KEY_RETURN_PREV_IME_ON_HIDE, returnPrevImeOnHide)
        o.put(KEY_IME_SWITCH_TARGET_ID, imeSwitchTargetId)
        o.put(KEY_HIDE_RECENT_TASK_CARD, hideRecentTaskCard)
        o.put(KEY_APP_LANGUAGE_TAG, appLanguageTag)
        o.put(KEY_FLOATING_SWITCHER_ENABLED, floatingSwitcherEnabled)
        o.put(KEY_FLOATING_SWITCHER_ALPHA, floatingSwitcherAlpha)
        o.put(KEY_FLOATING_BALL_SIZE_DP, floatingBallSizeDp)
        o.put(KEY_FLOATING_POS_X, floatingBallPosX)
        o.put(KEY_FLOATING_POS_Y, floatingBallPosY)
        o.put(KEY_FLOATING_DIRECT_DRAG_ENABLED, floatingBallDirectDragEnabled)
        o.put(KEY_FLOATING_ASR_ENABLED, floatingAsrEnabled)
        o.put(KEY_FLOATING_ONLY_WHEN_IME_VISIBLE, floatingSwitcherOnlyWhenImeVisible)
        
        o.put(KEY_POSTPROC_ENABLED, postProcessEnabled)
        o.put(KEY_AI_EDIT_DEFAULT_TO_LAST_ASR, aiEditDefaultToLastAsr)
        o.put(KEY_HEADSET_MIC_PRIORITY_ENABLED, headsetMicPriorityEnabled)
        o.put(KEY_LLM_ENDPOINT, llmEndpoint)
        o.put(KEY_LLM_API_KEY, llmApiKey)
        o.put(KEY_LLM_MODEL, llmModel)
        o.put(KEY_LLM_TEMPERATURE, llmTemperature.toDouble())
        // SiliconFlow 免费/付费 LLM 配置
        o.put(KEY_SF_FREE_LLM_ENABLED, sfFreeLlmEnabled)
        o.put(KEY_SF_FREE_LLM_MODEL, sfFreeLlmModel)
        o.put(KEY_SF_FREE_LLM_USE_PAID_KEY, sfFreeLlmUsePaidKey)
        // SiliconFlow ASR 配置
        o.put(KEY_SF_FREE_ASR_ENABLED, sfFreeAsrEnabled)
        o.put(KEY_SF_FREE_ASR_MODEL, sfFreeAsrModel)
        o.put(KEY_SF_USE_OMNI, sfUseOmni)
        o.put(KEY_SF_OMNI_PROMPT, sfOmniPrompt)
        // OpenAI ASR：Prompt 开关（布尔）
        o.put(KEY_OA_ASR_USE_PROMPT, oaAsrUsePrompt)
        // Volcano streaming toggle
        o.put(KEY_VOLC_STREAMING_ENABLED, volcStreamingEnabled)
        o.put(KEY_VOLC_BIDI_STREAMING_ENABLED, volcBidiStreamingEnabled)
        // DashScope streaming toggle
        o.put(KEY_DASH_STREAMING_ENABLED, dashStreamingEnabled)
        o.put(KEY_DASH_REGION, dashRegion)
        o.put(KEY_DASH_FUNASR_ENABLED, dashFunAsrEnabled)
        o.put(KEY_DASH_ASR_MODEL, dashAsrModel)
        o.put(KEY_DASH_FUNASR_SEMANTIC_PUNCT_ENABLED, dashFunAsrSemanticPunctEnabled)
        // Volcano extras
        o.put(KEY_VOLC_DDC_ENABLED, volcDdcEnabled)
        o.put(KEY_VOLC_VAD_ENABLED, volcVadEnabled)
        o.put(KEY_VOLC_NONSTREAM_ENABLED, volcNonstreamEnabled)
        o.put(KEY_VOLC_LANGUAGE, volcLanguage)
        o.put(KEY_VOLC_FILE_STANDARD_ENABLED, volcFileStandardEnabled)
        o.put(KEY_VOLC_MODEL_V2_ENABLED, volcModelV2Enabled)
        // Soniox（同时导出单值与数组，便于兼容）
        o.put(KEY_SONIOX_LANGUAGE, sonioxLanguage)
        o.put(KEY_SONIOX_LANGUAGES, sonioxLanguagesJson)
        o.put(KEY_SONIOX_STREAMING_ENABLED, sonioxStreamingEnabled)
        // Gemini 设置
        o.put(KEY_GEMINI_DISABLE_THINKING, geminiDisableThinking)
        // ElevenLabs streaming toggle
        o.put(KEY_ELEVEN_STREAMING_ENABLED, elevenStreamingEnabled)
        o.put(KEY_VOLC_FIRST_CHAR_ACCEL_ENABLED, volcFirstCharAccelEnabled)
        // 多 LLM 配置
        o.put(KEY_LLM_PROVIDERS, llmProvidersJson)
        o.put(KEY_LLM_ACTIVE_ID, activeLlmId)
        // 兼容旧字段
        o.put(KEY_LLM_PROMPT, llmPrompt)
        o.put(KEY_LLM_PROMPT_PRESETS, promptPresetsJson)
        o.put(KEY_LLM_PROMPT_ACTIVE_ID, activePromptId)
        // 语音预设
        o.put(KEY_SPEECH_PRESETS, speechPresetsJson)
        o.put(KEY_SPEECH_PRESET_ACTIVE_ID, activeSpeechPresetId)
        // 供应商设置（通用导出）
        o.put(KEY_ASR_VENDOR, asrVendor.id)
        // 遍历所有供应商字段，统一导出，避免逐个硬编码
        vendorFields.values.flatten().forEach { f ->
            o.put(f.key, getPrefString(f.key, f.default))
        }
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
        // 统计信息
        o.put(KEY_TOTAL_ASR_CHARS, totalAsrChars)
        // 使用统计（聚合）与首次使用日期
        try { o.put(KEY_USAGE_STATS_JSON, usageStatsJson) } catch (t: Throwable) { Log.w(TAG, "Failed to export usage stats", t) }
        // 历史记录纳入备份范围
        try { o.put(KEY_ASR_HISTORY_JSON, getPrefString(KEY_ASR_HISTORY_JSON, "")) } catch (t: Throwable) { Log.w(TAG, "Failed to export ASR history", t) }
        try { o.put(KEY_FIRST_USE_DATE, firstUseDate) } catch (t: Throwable) { Log.w(TAG, "Failed to export first use date", t) }
        // 写入兼容/粘贴方案
        o.put(KEY_FLOATING_WRITE_COMPAT_ENABLED, floatingWriteTextCompatEnabled)
        o.put(KEY_FLOATING_WRITE_COMPAT_PACKAGES, floatingWriteCompatPackages)
        o.put(KEY_FLOATING_WRITE_PASTE_ENABLED, floatingWriteTextPasteEnabled)
        o.put(KEY_FLOATING_WRITE_PASTE_PACKAGES, floatingWritePastePackages)
        // 允许外部输入法联动（AIDL）
        o.put(KEY_EXTERNAL_AIDL_ENABLED, externalAidlEnabled)
        // SenseVoice（本地 ASR）
        o.put(KEY_SV_MODEL_DIR, svModelDir)
        o.put(KEY_SV_MODEL_VARIANT, svModelVariant)
        o.put(KEY_SV_NUM_THREADS, svNumThreads)
        o.put(KEY_SV_LANGUAGE, svLanguage)
        o.put(KEY_SV_USE_ITN, svUseItn)
        o.put(KEY_SV_PRELOAD_ENABLED, svPreloadEnabled)
        o.put(KEY_SV_KEEP_ALIVE_MINUTES, svKeepAliveMinutes)
        o.put(KEY_SV_PSEUDO_STREAM_ENABLED, svPseudoStreamEnabled)
        // TeleSpeech（本地 ASR）
        o.put(KEY_TS_MODEL_VARIANT, tsModelVariant)
        o.put(KEY_TS_NUM_THREADS, tsNumThreads)
        o.put(KEY_TS_KEEP_ALIVE_MINUTES, tsKeepAliveMinutes)
        o.put(KEY_TS_PRELOAD_ENABLED, tsPreloadEnabled)
        o.put(KEY_TS_USE_ITN, tsUseItn)
        o.put(KEY_TS_PSEUDO_STREAM_ENABLED, tsPseudoStreamEnabled)
        // Paraformer（本地 ASR）
        o.put(KEY_PF_MODEL_VARIANT, pfModelVariant)
        o.put(KEY_PF_NUM_THREADS, pfNumThreads)
        o.put(KEY_PF_KEEP_ALIVE_MINUTES, pfKeepAliveMinutes)
        o.put(KEY_PF_PRELOAD_ENABLED, pfPreloadEnabled)
        o.put(KEY_PF_USE_ITN, pfUseItn)
        // SyncClipboard 配置
        o.put(KEY_SC_ENABLED, syncClipboardEnabled)
        o.put(KEY_SC_SERVER_BASE, syncClipboardServerBase)
        o.put(KEY_SC_USERNAME, syncClipboardUsername)
        o.put(KEY_SC_PASSWORD, syncClipboardPassword)
        o.put(KEY_SC_AUTO_PULL, syncClipboardAutoPullEnabled)
        o.put(KEY_SC_PULL_INTERVAL_SEC, syncClipboardPullIntervalSec)
        // WebDAV（可选）
        o.put(KEY_WD_URL, webdavUrl)
        o.put(KEY_WD_USERNAME, webdavUsername)
        o.put(KEY_WD_PASSWORD, webdavPassword)
        // 仅导出固定的剪贴板记录
        try { o.put(KEY_CLIP_PINNED_JSON, getPrefString(KEY_CLIP_PINNED_JSON, "")) } catch (t: Throwable) { Log.w(TAG, "Failed to export pinned clip", t) }
        // 隐私开关
        try { o.put(KEY_DISABLE_ASR_HISTORY, disableAsrHistory) } catch (_: Throwable) {}
        try { o.put(KEY_DISABLE_USAGE_STATS, disableUsageStats) } catch (_: Throwable) {}
        try { o.put(KEY_DATA_COLLECTION_ENABLED, dataCollectionEnabled) } catch (t: Throwable) { Log.w(TAG, "Failed to export data collection enabled", t) }
        // AI 后处理：少于字数跳过
        try { o.put(KEY_POSTPROC_SKIP_UNDER_CHARS, postprocSkipUnderChars) } catch (_: Throwable) {}
        // LLM 供应商选择（新架构）
        try { o.put(KEY_LLM_VENDOR, llmVendor.id) } catch (_: Throwable) {}
        // 内置供应商配置（遍历所有内置供应商）
        for (vendor in LlmVendor.builtinVendors()) {
            val keyPrefix = "llm_vendor_${vendor.id}"
            try { o.put("${keyPrefix}_api_key", getLlmVendorApiKey(vendor)) } catch (_: Throwable) {}
            try {
                val model = if (vendor == LlmVendor.SF_FREE && !sfFreeLlmUsePaidKey) {
                    sfFreeLlmModel
                } else {
                    getLlmVendorModel(vendor)
                }
                o.put("${keyPrefix}_model", model)
            } catch (_: Throwable) {}
            try { o.put("${keyPrefix}_temperature", getLlmVendorTemperature(vendor).toDouble()) } catch (_: Throwable) {}
        }
        return o.toString()
    }

    // 从 JSON 字符串导入。仅覆盖提供的键；解析失败返回 false。
    fun importJsonString(json: String): Boolean {
        return try {
            val o = org.json.JSONObject(json)
            Log.i(TAG, "Starting import of settings from JSON")
            fun optBool(key: String, default: Boolean? = null): Boolean? =
                if (o.has(key)) o.optBoolean(key) else default
            fun optString(key: String, default: String? = null): String? =
                if (o.has(key)) o.optString(key) else default
            fun optFloat(key: String, default: Float? = null): Float? =
                if (o.has(key)) o.optDouble(key).toFloat() else default
            fun optInt(key: String, default: Int? = null): Int? =
                if (o.has(key)) o.optInt(key) else default

            optString(KEY_APP_KEY)?.let { appKey = it }
            optString(KEY_ACCESS_KEY)?.let { accessKey = it }
            optBool(KEY_TRIM_FINAL_TRAILING_PUNCT)?.let { trimFinalTrailingPunct = it }
            optBool(KEY_MIC_HAPTIC_ENABLED)?.let { micHapticEnabled = it }
            optBool(KEY_MIC_TAP_TOGGLE_ENABLED)?.let { micTapToggleEnabled = it }
            optBool(KEY_AUTO_START_RECORDING_ON_SHOW)?.let { autoStartRecordingOnShow = it }
            optBool(KEY_DUCK_MEDIA_ON_RECORD)?.let { duckMediaOnRecordEnabled = it }
            optBool(KEY_OFFLINE_DENOISE_ENABLED)?.let { offlineDenoiseEnabled = it }
            optBool(KEY_AUTO_STOP_ON_SILENCE_ENABLED)?.let { autoStopOnSilenceEnabled = it }
            optInt(KEY_AUTO_STOP_SILENCE_WINDOW_MS)?.let { autoStopSilenceWindowMs = it }
            optInt(KEY_AUTO_STOP_SILENCE_SENSITIVITY)?.let { autoStopSilenceSensitivity = it }
            optInt(KEY_KEYBOARD_HEIGHT_TIER)?.let { keyboardHeightTier = it }
            optInt(KEY_KEYBOARD_BOTTOM_PADDING_DP)?.let { keyboardBottomPaddingDp = it }
            optInt(KEY_WAVEFORM_SENSITIVITY)?.let { waveformSensitivity = it }
            optBool(KEY_SWAP_AI_EDIT_IME_SWITCHER)?.let { swapAiEditWithImeSwitcher = it }
            optBool(KEY_FCITX5_RETURN_ON_SWITCHER)?.let { fcitx5ReturnOnImeSwitch = it }
            optBool(KEY_HIDE_RECENT_TASK_CARD)?.let { hideRecentTaskCard = it }
            optString(KEY_APP_LANGUAGE_TAG)?.let { appLanguageTag = it }
            optBool(KEY_POSTPROC_ENABLED)?.let { postProcessEnabled = it }
            optBool(KEY_HEADSET_MIC_PRIORITY_ENABLED)?.let { headsetMicPriorityEnabled = it }
            // SiliconFlow 免费/付费 LLM 配置
            optBool(KEY_SF_FREE_LLM_ENABLED)?.let { sfFreeLlmEnabled = it }
            optString(KEY_SF_FREE_LLM_MODEL)?.let { sfFreeLlmModel = it }
            optBool(KEY_SF_FREE_LLM_USE_PAID_KEY)?.let { sfFreeLlmUsePaidKey = it }
            // SiliconFlow ASR 配置
            optBool(KEY_SF_FREE_ASR_ENABLED)?.let { sfFreeAsrEnabled = it }
            optString(KEY_SF_FREE_ASR_MODEL)?.let { sfFreeAsrModel = it }
            optBool(KEY_SF_USE_OMNI)?.let { sfUseOmni = it }
            optString(KEY_SF_OMNI_PROMPT)?.let { sfOmniPrompt = it }
            // 外部输入法联动（AIDL）
            optBool(KEY_EXTERNAL_AIDL_ENABLED)?.let { externalAidlEnabled = it }
            optBool(KEY_FLOATING_SWITCHER_ENABLED)?.let { floatingSwitcherEnabled = it }
            optFloat(KEY_FLOATING_SWITCHER_ALPHA)?.let { floatingSwitcherAlpha = it.coerceIn(0.2f, 1.0f) }
            optInt(KEY_FLOATING_BALL_SIZE_DP)?.let { floatingBallSizeDp = it.coerceIn(28, 96) }
            optInt(KEY_FLOATING_POS_X)?.let { floatingBallPosX = it }
            optInt(KEY_FLOATING_POS_Y)?.let { floatingBallPosY = it }
            optBool(KEY_FLOATING_DIRECT_DRAG_ENABLED)?.let { floatingBallDirectDragEnabled = it }
            optBool(KEY_FLOATING_ASR_ENABLED)?.let { floatingAsrEnabled = it }
            optBool(KEY_FLOATING_ONLY_WHEN_IME_VISIBLE)?.let { floatingSwitcherOnlyWhenImeVisible = it }
            
            optBool(KEY_FLOATING_WRITE_COMPAT_ENABLED)?.let { floatingWriteTextCompatEnabled = it }
            optString(KEY_FLOATING_WRITE_COMPAT_PACKAGES)?.let { floatingWriteCompatPackages = it }
            optBool(KEY_FLOATING_WRITE_PASTE_ENABLED)?.let { floatingWriteTextPasteEnabled = it }
            optString(KEY_FLOATING_WRITE_PASTE_PACKAGES)?.let { floatingWritePastePackages = it }

            optString(KEY_LLM_ENDPOINT)?.let { llmEndpoint = it.ifBlank { DEFAULT_LLM_ENDPOINT } }
            optString(KEY_LLM_API_KEY)?.let { llmApiKey = it }
            optString(KEY_LLM_MODEL)?.let { llmModel = it.ifBlank { DEFAULT_LLM_MODEL } }
            optFloat(KEY_LLM_TEMPERATURE)?.let { llmTemperature = it.coerceIn(0f, 2f) }
            optBool(KEY_AI_EDIT_DEFAULT_TO_LAST_ASR)?.let { aiEditDefaultToLastAsr = it }
            optInt(KEY_POSTPROC_SKIP_UNDER_CHARS)?.let { postprocSkipUnderChars = it }
            // OpenAI ASR：Prompt 开关
            optBool(KEY_OA_ASR_USE_PROMPT)?.let { oaAsrUsePrompt = it }
            optBool(KEY_VOLC_STREAMING_ENABLED)?.let { volcStreamingEnabled = it }
            optBool(KEY_VOLC_BIDI_STREAMING_ENABLED)?.let { volcBidiStreamingEnabled = it }
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
                    dashAsrModel = deriveDashAsrModelFromLegacyFlags()
                }
            }
            optString(KEY_DASH_REGION)?.let { dashRegion = it }
            optBool(KEY_DASH_FUNASR_SEMANTIC_PUNCT_ENABLED)?.let { dashFunAsrSemanticPunctEnabled = it }
            optBool(KEY_VOLC_DDC_ENABLED)?.let { volcDdcEnabled = it }
            optBool(KEY_VOLC_VAD_ENABLED)?.let { volcVadEnabled = it }
            optBool(KEY_VOLC_NONSTREAM_ENABLED)?.let { volcNonstreamEnabled = it }
            optString(KEY_VOLC_LANGUAGE)?.let { volcLanguage = it }
            optBool(KEY_RETURN_PREV_IME_ON_HIDE)?.let { returnPrevImeOnHide = it }
            optString(KEY_IME_SWITCH_TARGET_ID)?.let { imeSwitchTargetId = it }
            optBool(KEY_VOLC_FIRST_CHAR_ACCEL_ENABLED)?.let { volcFirstCharAccelEnabled = it }
            optBool(KEY_VOLC_FILE_STANDARD_ENABLED)?.let { volcFileStandardEnabled = it }
            optBool(KEY_VOLC_MODEL_V2_ENABLED)?.let { volcModelV2Enabled = it }
            // Soniox（若提供数组则优先；否则回退单值）
            if (o.has(KEY_SONIOX_LANGUAGES)) {
                optString(KEY_SONIOX_LANGUAGES)?.let { sonioxLanguagesJson = it }
            } else {
                optString(KEY_SONIOX_LANGUAGE)?.let { sonioxLanguage = it }
            }
            optBool(KEY_SONIOX_STREAMING_ENABLED)?.let { sonioxStreamingEnabled = it }
            // ElevenLabs streaming toggle
            optBool(KEY_ELEVEN_STREAMING_ENABLED)?.let { elevenStreamingEnabled = it }
            // Gemini 设置
            optBool(KEY_GEMINI_DISABLE_THINKING)?.let { geminiDisableThinking = it }
            // 多 LLM 配置（优先于旧字段，仅当存在时覆盖）
            optString(KEY_LLM_PROVIDERS)?.let { llmProvidersJson = it }
            optString(KEY_LLM_ACTIVE_ID)?.let { activeLlmId = it }
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
            // 供应商设置（通用导入）
            vendorFields.values.flatten().forEach { f ->
                optString(f.key)?.let { v ->
                    val final = v.ifBlank { f.default }
                    setPrefString(f.key, final)
                }
            }
            optString(KEY_PUNCT_1)?.let { punct1 = it }
            optString(KEY_PUNCT_2)?.let { punct2 = it }
            optString(KEY_PUNCT_3)?.let { punct3 = it }
            optString(KEY_PUNCT_4)?.let { punct4 = it }
            // 自定义扩展按钮（可选）
            optString(KEY_EXT_BTN_1)?.let { extBtn1 = com.brycewg.asrkb.ime.ExtensionButtonAction.fromId(it) }
            optString(KEY_EXT_BTN_2)?.let { extBtn2 = com.brycewg.asrkb.ime.ExtensionButtonAction.fromId(it) }
            optString(KEY_EXT_BTN_3)?.let { extBtn3 = com.brycewg.asrkb.ime.ExtensionButtonAction.fromId(it) }
            optString(KEY_EXT_BTN_4)?.let { extBtn4 = com.brycewg.asrkb.ime.ExtensionButtonAction.fromId(it) }
            // 统计信息（可选）
            if (o.has(KEY_TOTAL_ASR_CHARS)) {
                // 使用 optLong，若类型为字符串/浮点将尽力转换
                val v = try { o.optLong(KEY_TOTAL_ASR_CHARS) } catch (_: Throwable) { 0L }
                if (v >= 0L) totalAsrChars = v
            }
            // 使用统计（可选）
            optString(KEY_USAGE_STATS_JSON)?.let { usageStatsJson = it }
            // 历史记录纳入恢复范围
            optString(KEY_ASR_HISTORY_JSON)?.let { setPrefString(KEY_ASR_HISTORY_JSON, it) }
            optString(KEY_FIRST_USE_DATE)?.let { firstUseDate = it }
            // SenseVoice（本地 ASR）
            optString(KEY_SV_MODEL_DIR)?.let { svModelDir = it }
            optString(KEY_SV_MODEL_VARIANT)?.let { svModelVariant = it }
            optInt(KEY_SV_NUM_THREADS)?.let { svNumThreads = it.coerceIn(1, 8) }
            optString(KEY_SV_LANGUAGE)?.let { svLanguage = it }
            optBool(KEY_SV_USE_ITN)?.let { svUseItn = it }
            optBool(KEY_SV_PRELOAD_ENABLED)?.let { svPreloadEnabled = it }
            optInt(KEY_SV_KEEP_ALIVE_MINUTES)?.let { svKeepAliveMinutes = it }
            optBool(KEY_SV_PSEUDO_STREAM_ENABLED)?.let { svPseudoStreamEnabled = it }
            // TeleSpeech（本地 ASR）
            optString(KEY_TS_MODEL_VARIANT)?.let { tsModelVariant = it }
            optInt(KEY_TS_NUM_THREADS)?.let { tsNumThreads = it.coerceIn(1, 8) }
            optInt(KEY_TS_KEEP_ALIVE_MINUTES)?.let { tsKeepAliveMinutes = it }
            optBool(KEY_TS_PRELOAD_ENABLED)?.let { tsPreloadEnabled = it }
            optBool(KEY_TS_USE_ITN)?.let { tsUseItn = it }
            optBool(KEY_TS_PSEUDO_STREAM_ENABLED)?.let { tsPseudoStreamEnabled = it }
            // Paraformer（本地 ASR）
            optString(KEY_PF_MODEL_VARIANT)?.let { pfModelVariant = it }
            optInt(KEY_PF_NUM_THREADS)?.let { pfNumThreads = it.coerceIn(1, 8) }
            optInt(KEY_PF_KEEP_ALIVE_MINUTES)?.let { pfKeepAliveMinutes = it }
            optBool(KEY_PF_PRELOAD_ENABLED)?.let { pfPreloadEnabled = it }
            optBool(KEY_PF_USE_ITN)?.let { pfUseItn = it }
            // SyncClipboard 配置
            optBool(KEY_SC_ENABLED)?.let { syncClipboardEnabled = it }
            optString(KEY_SC_SERVER_BASE)?.let { syncClipboardServerBase = it }
            optString(KEY_SC_USERNAME)?.let { syncClipboardUsername = it }
            optString(KEY_SC_PASSWORD)?.let { syncClipboardPassword = it }
            optBool(KEY_SC_AUTO_PULL)?.let { syncClipboardAutoPullEnabled = it }
            optInt(KEY_SC_PULL_INTERVAL_SEC)?.let { syncClipboardPullIntervalSec = it }
            // 隐私开关
            optBool(KEY_DISABLE_ASR_HISTORY)?.let { disableAsrHistory = it }
            optBool(KEY_DISABLE_USAGE_STATS)?.let { disableUsageStats = it }
            optBool(KEY_DATA_COLLECTION_ENABLED)?.let { dataCollectionEnabled = it }
            // WebDAV 备份
            optString(KEY_WD_URL)?.let { webdavUrl = it }
            optString(KEY_WD_USERNAME)?.let { webdavUsername = it }
            optString(KEY_WD_PASSWORD)?.let { webdavPassword = it }
            // 剪贴板固定记录（仅覆盖固定集合；非固定不导入）
            optString(KEY_CLIP_PINNED_JSON)?.let { setPrefString(KEY_CLIP_PINNED_JSON, it) }
            // LLM 供应商选择（新架构）
            optString(KEY_LLM_VENDOR)?.let { llmVendor = LlmVendor.fromId(it) }
            // 内置供应商配置（遍历所有内置供应商）
            for (vendor in LlmVendor.builtinVendors()) {
                val keyPrefix = "llm_vendor_${vendor.id}"
                optString("${keyPrefix}_api_key")?.let { setLlmVendorApiKey(vendor, it) }
                optString("${keyPrefix}_model")?.let { setLlmVendorModel(vendor, it) }
                optFloat("${keyPrefix}_temperature")?.let { setLlmVendorTemperature(vendor, it) }
            }
            Log.i(TAG, "Successfully imported settings from JSON")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import settings from JSON", e)
            false
        }
    }

}
