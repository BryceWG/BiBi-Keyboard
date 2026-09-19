/**
 * Compose 设置项的风格无关模型。
 *
 * 归属模块：ui/settings/compose/model
 */
package com.brycewg.asrkb.ui.settings.compose.model

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector

sealed interface SettingsEntry {
    val id: String

    @get:StringRes val titleRes: Int
    val title: String?

    @get:StringRes val summaryRes: Int?
    val summary: String?
    val icon: ImageVector?
    val enabled: Boolean

    data class Action(
        override val id: String,
        @param:StringRes override val titleRes: Int,
        override val title: String? = null,
        @param:StringRes override val summaryRes: Int? = null,
        override val summary: String? = null,
        override val icon: ImageVector? = null,
        override val enabled: Boolean = true,
        val onClick: () -> Unit
    ) : SettingsEntry

    data class Switch(
        override val id: String,
        @param:StringRes override val titleRes: Int,
        override val title: String? = null,
        @param:StringRes override val summaryRes: Int? = null,
        override val summary: String? = null,
        override val icon: ImageVector? = null,
        override val enabled: Boolean = true,
        val checked: Boolean,
        val onCheckedChange: (Boolean) -> Unit
    ) : SettingsEntry

    data class Dropdown(
        override val id: String,
        @param:StringRes override val titleRes: Int,
        override val title: String? = null,
        @param:StringRes override val summaryRes: Int? = null,
        override val summary: String? = null,
        override val icon: ImageVector? = null,
        override val enabled: Boolean = true,
        val options: List<DropdownOption>,
        val selectedOptionId: String,
        val onSelectedOptionChange: (String) -> Unit
    ) : SettingsEntry
}

data class DropdownOption(
    val id: String,
    val label: String
)

data class SettingsSection(
    val id: String,
    @param:StringRes val titleRes: Int? = null,
    val entries: List<SettingsEntry>
)
