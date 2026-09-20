/**
 * 设置搜索索引构建与缓存。
 *
 * 归属模块：ui/settings/search
 */
package com.brycewg.asrkb.ui.settings.search

import android.content.Context
import androidx.annotation.StringRes
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.asr.LlmVendor
import com.brycewg.asrkb.ui.AsrVendorUi
import com.brycewg.asrkb.ui.settings.compose.core.BibiSettingsRoute
import java.util.Locale

object SettingsSearchIndex {

    @Volatile
    private var cachedLocaleTag: String? = null

    @Volatile
    private var cachedEntries: List<SettingsSearchEntry>? = null

    fun get(context: Context): List<SettingsSearchEntry> {
        val localeTag = context.resources.configuration.locales[0]?.toLanguageTag() ?: ""
        val existing = cachedEntries
        if (existing != null && cachedLocaleTag == localeTag) {
            return existing
        }
        synchronized(this) {
            val existing2 = cachedEntries
            if (existing2 != null && cachedLocaleTag == localeTag) {
                return existing2
            }
            val built = buildIndex(context)
            cachedLocaleTag = localeTag
            cachedEntries = built
            return built
        }
    }

    private fun buildIndex(context: Context): List<SettingsSearchEntry> {
        val unique = LinkedHashMap<String, SettingsSearchEntry>()
        fun add(entry: SettingsSearchEntry) {
            unique.putIfAbsent(entry.uniqueKey(), entry)
        }

        baseEntries().forEach { add(it.toSearchEntry(context)) }
        asrVendorEntries(context).forEach(::add)
        llmVendorEntries(context).forEach(::add)

        return unique.values.toList()
    }

    private data class DeclarativeEntry(
        @param:StringRes @field:StringRes val titleRes: Int,
        @param:StringRes @field:StringRes val screenTitleRes: Int,
        val route: BibiSettingsRoute,
        @param:StringRes @field:StringRes val sectionTitleRes: Int? = null,
        val keywords: List<String> = emptyList(),
        val forceAsrVendorId: String? = null,
        val forceLlmVendorId: String? = null
    ) {
        fun toSearchEntry(context: Context): SettingsSearchEntry = SettingsSearchEntry(
            title = context.getString(titleRes),
            sectionPath = sectionTitleRes?.let { listOf(context.getString(it)) }.orEmpty(),
            screenTitleResId = screenTitleRes,
            composeRoute = route,
            targetEntryId = context.resources.getResourceEntryName(titleRes).toTargetEntryId(),
            keywords = keywords,
            forceAsrVendorId = forceAsrVendorId,
            forceLlmVendorId = forceLlmVendorId
        )
    }

    private fun baseEntries(): List<DeclarativeEntry> = buildList {
        add(DeclarativeEntry(R.string.btn_one_click_setup, R.string.settings_title, BibiSettingsRoute.Home))
        add(DeclarativeEntry(R.string.btn_test_input, R.string.settings_title, BibiSettingsRoute.Home))
        add(DeclarativeEntry(R.string.title_input_settings, R.string.settings_title, BibiSettingsRoute.Input))
        add(DeclarativeEntry(R.string.title_floating_settings, R.string.settings_title, BibiSettingsRoute.Floating))
        add(DeclarativeEntry(R.string.title_asr_settings, R.string.settings_title, BibiSettingsRoute.Asr))
        add(DeclarativeEntry(R.string.title_ai_settings, R.string.settings_title, BibiSettingsRoute.Ai))
        add(DeclarativeEntry(R.string.btn_open_asr_history, R.string.settings_title, BibiSettingsRoute.History))
        add(DeclarativeEntry(R.string.btn_check_update, R.string.settings_title, BibiSettingsRoute.Home))
        add(DeclarativeEntry(R.string.title_backup_settings, R.string.settings_title, BibiSettingsRoute.Backup))
        add(DeclarativeEntry(R.string.title_other_settings, R.string.settings_title, BibiSettingsRoute.Other))
        add(DeclarativeEntry(R.string.about_title, R.string.settings_title, BibiSettingsRoute.About))
        add(DeclarativeEntry(R.string.about_stats_title, R.string.about_stats_title, BibiSettingsRoute.UsageStats))

        addInputEntries()
        addFloatingEntries()
        addAsrEntries()
        addAiEntries()
        addOtherEntries()
        addBackupEntries()
        addAboutEntries()
        addUsageStatsEntries()
        addUiSettingsEntries()
    }

    private fun MutableList<DeclarativeEntry>.addInputEntries() {
        val route = BibiSettingsRoute.Input
        val screen = R.string.title_input_settings
        fun item(@StringRes title: Int, @StringRes section: Int, vararg keywords: String) {
            add(DeclarativeEntry(title, screen, route, section, keywords.toList()))
        }
        item(R.string.label_trim_trailing_punct, R.string.section_input_behavior)
        item(R.string.label_mic_tap_toggle, R.string.section_input_behavior)
        item(R.string.label_auto_start_recording_on_show, R.string.section_input_behavior)
        item(R.string.label_continuous_capture, R.string.section_input_behavior)
        item(
            R.string.label_keep_screen_on_while_recording,
            R.string.section_input_behavior,
            "常亮",
            "息屏",
            "亮屏",
            "screen",
            "wake"
        )
        item(R.string.label_auto_enter_after_asr, R.string.section_input_behavior, "enter", "回车", "发送")
        item(R.string.label_auto_switch_ime_after_asr, R.string.section_input_behavior, "ime", "输入法", "二次编辑")
        item(R.string.label_fcitx5_return_on_switcher, R.string.section_input_behavior, "fcitx5")
        item(R.string.label_return_prev_ime_on_hide, R.string.section_input_behavior)
        item(R.string.label_ime_switch_target, R.string.section_input_behavior)
        item(R.string.label_audio_ducking_on_record, R.string.section_audio_and_link)
        item(R.string.label_offline_denoise, R.string.section_audio_and_link)
        item(R.string.label_auto_cancel_empty_audio_input, R.string.section_audio_and_link)
        item(R.string.label_auto_filter_silent_audio_segments, R.string.section_audio_and_link)
        item(R.string.label_upload_audio_compression, R.string.section_audio_and_link)
        item(R.string.label_headset_mic_priority, R.string.section_audio_and_link)
        item(R.string.label_external_ime_link_aidl, R.string.section_audio_and_link, "aidl")
    }

    private fun MutableList<DeclarativeEntry>.addUiSettingsEntries() {
        val route = BibiSettingsRoute.UiSettings
        val screen = R.string.section_ui_settings
        fun item(@StringRes title: Int, @StringRes section: Int, vararg keywords: String) {
            add(DeclarativeEntry(title, screen, route, section, keywords.toList()))
        }
        item(R.string.label_language, R.string.section_main_ui)
        item(R.string.settings_ui_mode, R.string.section_main_ui)
        item(R.string.settings_theme_mode, R.string.section_main_ui)
        item(R.string.label_hide_recent_task_card, R.string.section_main_ui)
        item(R.string.label_keyboard_height, R.string.section_keyboard_ui)
        item(R.string.label_ime_tablet_floating_keyboard, R.string.section_keyboard_ui)
        item(R.string.label_haptic_feedback_strength, R.string.section_keyboard_ui)
        item(R.string.label_keyboard_bottom_padding, R.string.section_keyboard_ui)
        item(R.string.label_extension_buttons, R.string.section_keyboard_ui)
    }

    private fun MutableList<DeclarativeEntry>.addFloatingEntries() {
        val route = BibiSettingsRoute.Floating
        val screen = R.string.title_floating_settings
        fun item(@StringRes title: Int, @StringRes section: Int, vararg keywords: String) {
            add(DeclarativeEntry(title, screen, route, section, keywords.toList()))
        }
        item(R.string.label_floating_asr, R.string.section_floating_basic)
        item(R.string.label_floating_only_when_ime_visible, R.string.section_floating_basic)
        item(R.string.label_floating_hold_to_record, R.string.section_floating_basic)
        item(R.string.label_floating_direct_drag, R.string.section_floating_basic)
        item(R.string.label_floating_alpha, R.string.section_floating_basic)
        item(R.string.label_floating_size, R.string.section_floating_basic)
        item(R.string.label_reset_floating_position, R.string.section_floating_basic)
        item(R.string.label_volume_key_recording, R.string.section_volume_key_recording, "volume", "音量")
        item(R.string.label_volume_key_recording_mode, R.string.section_volume_key_recording, "volume+", "volume-", "音量+", "音量-")
        item(R.string.label_volume_key_status_toast, R.string.section_volume_key_recording, "toast", "音量")
        item(R.string.label_volume_key_stop_on_ime_hidden, R.string.section_volume_key_recording, "volume", "音量")
        item(
            R.string.label_floating_a11y_android13_api,
            R.string.section_floating_compat,
            "Android 13",
            "commitText",
            "新版 API"
        )
        item(R.string.label_floating_write_compat, R.string.section_floating_compat)
        item(R.string.label_floating_write_compat_pkgs, R.string.section_floating_compat)
        item(R.string.label_floating_write_paste, R.string.section_floating_compat)
        item(R.string.label_floating_write_paste_pkgs, R.string.section_floating_compat)
    }

    private fun MutableList<DeclarativeEntry>.addAsrEntries() {
        val route = BibiSettingsRoute.Asr
        val screen = R.string.title_asr_settings
        fun item(
            @StringRes title: Int,
            @StringRes section: Int,
            vararg keywords: String,
            forceAsrVendorId: String? = null
        ) {
            add(DeclarativeEntry(title, screen, route, section, keywords.toList(), forceAsrVendorId))
        }
        item(R.string.label_recording_auto_stop_mode, R.string.section_silence_autostop, "auto stop", "判停")
        item(R.string.label_auto_stop_silence, R.string.label_auto_stop_silence)
        item(R.string.label_silence_window_ms, R.string.label_auto_stop_silence)
        item(R.string.label_silence_sensitivity, R.string.label_auto_stop_silence, "vad", "判停", "灵敏度")
        item(R.string.label_recording_max_duration, R.string.section_silence_autostop, "maximum duration", "最长")
        item(R.string.label_asr_vendor, R.string.label_asr_vendor)
        item(
            R.string.label_volc_asr_model,
            R.string.label_asr_vendor,
            "豆包",
            "火山",
            "streaming",
            "file",
            "V1",
            "V2",
            "极速",
            "doubao",
            "volc",
            forceAsrVendorId = AsrVendor.Volc.id
        )
        item(
            R.string.label_gemini_asr_mode,
            R.string.label_asr_vendor,
            "Gemini",
            "Transcribe",
            forceAsrVendorId = AsrVendor.Gemini.id
        )
        item(
            R.string.label_gemini_transcribe_language,
            R.string.label_asr_vendor,
            "BCP-47",
            forceAsrVendorId = AsrVendor.Gemini.id
        )
        item(
            R.string.label_gemini_transcribe_smart,
            R.string.label_asr_vendor,
            "verbatim",
            forceAsrVendorId = AsrVendor.Gemini.id
        )
        item(R.string.label_backup_asr_vendor, R.string.label_backup_asr_engine)
        item(R.string.label_backup_asr_timeout_sensitivity, R.string.label_backup_asr_engine)
        item(R.string.label_backup_asr_local_residency, R.string.label_backup_asr_engine)
    }

    private fun MutableList<DeclarativeEntry>.addAiEntries() {
        val route = BibiSettingsRoute.Ai
        val screen = R.string.title_ai_settings
        fun item(@StringRes title: Int, @StringRes section: Int, vararg keywords: String) {
            add(DeclarativeEntry(title, screen, route, section, keywords.toList()))
        }
        item(R.string.label_ai_post_process_enabled, R.string.section_post_process_scope)
        item(R.string.label_postproc_typewriter_enabled, R.string.section_post_process_scope)
        item(R.string.title_ai_skip_under, R.string.section_post_process_scope)
        item(R.string.label_ai_edit_default_use_last_asr, R.string.section_ai_edit)
        item(R.string.label_ai_edit_custom_prompt_enabled, R.string.section_ai_edit, "prompt")
        item(R.string.label_ai_edit_system_prompt, R.string.section_ai_edit, "prompt")
        item(R.string.label_llm_vendor, R.string.section_post_process_model, "llm")
        item(R.string.label_llm_choose_profile, R.string.section_post_process_model)
        item(R.string.label_llm_model_select, R.string.section_post_process_model)
        item(
            R.string.title_ai_reasoning_threshold,
            R.string.section_post_process_model,
            "reasoning",
            "深度思考",
            "启用深度思考",
            "Enable Deep Thinking"
        )
        item(R.string.label_custom_reasoning_params, R.string.section_post_process_model)
        item(R.string.label_llm_prompt_presets, R.string.label_llm_prompt_presets)
        item(R.string.label_llm_prompt_skill, R.string.label_llm_prompt_presets, "skill", "prompt")
        addPromptSelectionEntries()
    }

    private fun MutableList<DeclarativeEntry>.addPromptSelectionEntries() {
        val route = BibiSettingsRoute.PromptSelection
        val screen = R.string.title_prompt_selection
        fun item(@StringRes title: Int, @StringRes section: Int, vararg keywords: String) {
            add(DeclarativeEntry(title, screen, route, section, keywords.toList()))
        }
        item(R.string.title_prompt_selection, R.string.section_prompt_selection_switch)
        add(
            DeclarativeEntry(
                R.string.title_prompt_selection_preview,
                R.string.title_prompt_selection,
                BibiSettingsRoute.PromptSelectionPreview,
                R.string.section_prompt_selection_preview,
                listOf("preview", "效果预览", "分类效果")
            )
        )
        item(
            R.string.label_prompt_auto_select_enabled,
            R.string.section_prompt_selection_switch,
            "auto select",
            "自动选择",
            "分类"
        )
        item(
            R.string.label_prompt_selection_candidates,
            R.string.section_prompt_selection_candidates,
            "candidates",
            "候选"
        )
        item(
            R.string.label_prompt_selection_model,
            R.string.section_prompt_selection_model,
            "selector model",
            "选择模型"
        )
    }

    private fun MutableList<DeclarativeEntry>.addOtherEntries() {
        val route = BibiSettingsRoute.Other
        val screen = R.string.title_other_settings
        fun item(@StringRes title: Int, @StringRes section: Int, vararg keywords: String) {
            add(DeclarativeEntry(title, screen, route, section, keywords.toList()))
        }
        item(R.string.label_floating_keep_alive_foreground, R.string.section_general)
        item(
            R.string.label_keep_alive_notification_click,
            R.string.section_general,
            "notification",
            "keep-alive",
            "常驻通知",
            "保活"
        )
        item(R.string.label_floating_keep_alive_privileged, R.string.section_general, "shizuku", "root")
        item(R.string.label_request_battery_whitelist, R.string.section_general)
        item(R.string.label_disable_asr_history, R.string.section_data_retention)
        item(R.string.label_disable_usage_stats, R.string.section_data_retention)
        item(R.string.label_data_collection, R.string.section_data_retention)
        item(R.string.label_custom_punct_1, R.string.custom_punct_section_title)
        item(R.string.label_custom_punct_2, R.string.custom_punct_section_title)
        item(R.string.label_custom_punct_3, R.string.custom_punct_section_title)
        item(R.string.label_custom_punct_4, R.string.custom_punct_section_title)
        item(R.string.label_speech_preset_section, R.string.label_speech_preset_section)
        item(R.string.section_sync_clipboard, R.string.section_sync_clipboard, "syncclipboard")
        item(R.string.label_enable_sync_clipboard, R.string.section_sync_clipboard, "syncclipboard")
        item(R.string.label_sc_server_base, R.string.section_sync_clipboard, "syncclipboard")
        item(R.string.label_sc_username, R.string.section_sync_clipboard, "syncclipboard")
        item(R.string.label_sc_password, R.string.section_sync_clipboard, "syncclipboard")
        item(R.string.label_sc_auto_receive, R.string.section_sync_clipboard, "syncclipboard")
        item(R.string.label_sc_pull_interval, R.string.section_sync_clipboard, "syncclipboard")
        item(R.string.label_sc_sync_images, R.string.section_sync_clipboard, "syncclipboard", "image")
        item(R.string.label_sc_sync_files, R.string.section_sync_clipboard, "syncclipboard", "file")
        item(R.string.label_sc_attachment_max_size, R.string.section_sync_clipboard, "syncclipboard", "attachment")
        item(R.string.btn_sc_choose_watch_tree, R.string.section_sync_clipboard, "syncclipboard", "folder")
    }

    private fun MutableList<DeclarativeEntry>.addBackupEntries() {
        val route = BibiSettingsRoute.Backup
        val screen = R.string.title_backup_settings
        fun item(@StringRes title: Int, @StringRes section: Int, vararg keywords: String) {
            add(DeclarativeEntry(title, screen, route, section, keywords.toList()))
        }
        item(R.string.btn_export_to_file, R.string.section_file_backup)
        item(R.string.btn_import_from_file, R.string.section_file_backup)
        item(R.string.hint_webdav_url, R.string.section_webdav_sync, "webdav")
        item(R.string.hint_webdav_username, R.string.section_webdav_sync, "webdav")
        item(R.string.hint_webdav_password, R.string.section_webdav_sync, "webdav")
        item(R.string.btn_webdav_upload, R.string.section_webdav_sync, "webdav")
        item(R.string.btn_webdav_download, R.string.section_webdav_sync, "webdav")
    }

    private fun MutableList<DeclarativeEntry>.addAboutEntries() {
        val route = BibiSettingsRoute.About
        val screen = R.string.about_title
        fun item(@StringRes title: Int, @StringRes section: Int? = null, vararg keywords: String) {
            add(DeclarativeEntry(title, screen, route, section, keywords.toList()))
        }
        item(R.string.about_auto_update_check)
        item(R.string.about_open_github)
        item(R.string.about_open_website)
        item(R.string.about_open_docs)
        item(R.string.about_btn_learn_pro, null, "pro")
        item(R.string.about_view_full_licenses, R.string.about_acknowledgements_title)
        item(R.string.btn_debug_export, R.string.about_debug_title)
    }

    private fun MutableList<DeclarativeEntry>.addUsageStatsEntries() {
        val route = BibiSettingsRoute.UsageStats
        val screen = R.string.about_stats_title
        fun item(@StringRes title: Int, vararg keywords: String) {
            add(
                DeclarativeEntry(
                    titleRes = title,
                    screenTitleRes = screen,
                    route = route,
                    sectionTitleRes = R.string.about_stats_title,
                    keywords = keywords.toList()
                )
            )
        }
        item(R.string.about_stats_overview, "overview", "总览", "概要")
        item(R.string.about_stats_share, "share", "分享", "导出")
        item(R.string.about_by_vendor, "provider", "vendor", "供应商")
        item(R.string.about_online_asr_failure_title, "failure", "错误", "失败")
        item(R.string.about_last_7_days, "daily", "每日", "最近")
    }

    private fun asrVendorEntries(context: Context): List<SettingsSearchEntry> {
        val section = context.getString(R.string.label_asr_vendor)
        return AsrVendorUi.ordered().map { vendor ->
            SettingsSearchEntry(
                title = AsrVendorUi.name(context, vendor),
                sectionPath = listOf(section),
                screenTitleResId = R.string.title_asr_settings,
                composeRoute = BibiSettingsRoute.Asr,
                targetEntryId = "asr_vendor",
                keywords = listOf(vendor.id),
                forceAsrVendorId = vendor.id
            )
        }
    }

    private fun llmVendorEntries(context: Context): List<SettingsSearchEntry> {
        val section = context.getString(R.string.section_post_process_model)
        return LlmVendor.allVendors().map { vendor ->
            SettingsSearchEntry(
                title = context.getString(vendor.displayNameResId),
                sectionPath = listOf(section),
                screenTitleResId = R.string.title_ai_settings,
                composeRoute = BibiSettingsRoute.Ai,
                targetEntryId = "llm_vendor",
                keywords = listOf(vendor.id),
                forceLlmVendorId = vendor.id
            )
        }
    }

    private fun SettingsSearchEntry.uniqueKey(): String = buildString {
        append(composeRoute?.id.orEmpty())
        append('#')
        append(title.lowercase(Locale.ROOT))
        if (sectionPath.isNotEmpty()) {
            append('#')
            append(sectionPath.joinToString(">").lowercase(Locale.ROOT))
        }
        if (!forceAsrVendorId.isNullOrBlank()) {
            append("#asr=")
            append(forceAsrVendorId.lowercase(Locale.ROOT))
        }
        if (!forceLlmVendorId.isNullOrBlank()) {
            append("#llm=")
            append(forceLlmVendorId.lowercase(Locale.ROOT))
        }
    }
}

private fun String.toTargetEntryId(): String = removePrefix("label_")
    .removePrefix("btn_")
    .removePrefix("title_")
    .removePrefix("hint_")
