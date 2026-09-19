/**
 * 设置页 Compose 路由定义。
 *
 * 归属模块：ui/settings/compose/core
 */
package com.brycewg.asrkb.ui.settings.compose.core

sealed interface BibiSettingsRoute {
    val id: String

    data object Home : BibiSettingsRoute {
        override val id: String = "home"
    }

    data object Input : BibiSettingsRoute {
        override val id: String = "input"
    }

    data object UiSettings : BibiSettingsRoute {
        override val id: String = "ui_settings"
    }

    data object KeyboardLayout : BibiSettingsRoute {
        override val id: String = "keyboard_layout"
    }

    data object RecordingTest : BibiSettingsRoute {
        override val id: String = "recording_test"
    }

    data object Floating : BibiSettingsRoute {
        override val id: String = "floating"
    }

    data object Asr : BibiSettingsRoute {
        override val id: String = "asr"
    }

    data object Ai : BibiSettingsRoute {
        override val id: String = "ai"
    }

    /** AI 设置下的“润色模式（自动选择提示词）”独立页面。 */
    data object PromptSelection : BibiSettingsRoute {
        override val id: String = "prompt_selection"
    }

    data object Backup : BibiSettingsRoute {
        override val id: String = "backup"
    }

    data object Other : BibiSettingsRoute {
        override val id: String = "other"
    }

    data object About : BibiSettingsRoute {
        override val id: String = "about"
    }

    data object Paywall : BibiSettingsRoute {
        override val id: String = "paywall"
    }

    data object UsageStats : BibiSettingsRoute {
        override val id: String = "usage_stats"
    }

    data object Search : BibiSettingsRoute {
        override val id: String = "search"
    }

    data object History : BibiSettingsRoute {
        override val id: String = "history"
    }

    data object ApiLog : BibiSettingsRoute {
        override val id: String = "api_log"
    }

    companion object {
        fun fromId(id: String?): BibiSettingsRoute? = when (id) {
            Home.id -> Home
            Input.id -> Input
            UiSettings.id -> UiSettings
            KeyboardLayout.id -> KeyboardLayout
            RecordingTest.id -> RecordingTest
            Floating.id -> Floating
            Asr.id -> Asr
            Ai.id -> Ai
            PromptSelection.id -> PromptSelection
            Backup.id -> Backup
            Other.id -> Other
            About.id -> About
            Paywall.id -> Paywall
            UsageStats.id -> UsageStats
            Search.id -> Search
            History.id -> History
            ApiLog.id -> ApiLog
            else -> null
        }
    }
}
