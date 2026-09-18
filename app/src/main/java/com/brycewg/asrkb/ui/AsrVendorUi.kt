package com.brycewg.asrkb.ui

import android.content.Context
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.asr.AsrVendorDisplayTag
import com.brycewg.asrkb.asr.AsrVendorRegistry

/**
 * 统一提供 ASR 供应商的顺序、显示名与 UI 标签。
 */
object AsrVendorUi {
    /** Registry-backed 供应商顺序（设置页/菜单统一使用）。 */
    fun ordered(): List<AsrVendor> = AsrVendorRegistry.ordered().map { it.vendor }

    /** 指定 vendor 的多语言显示名。 */
    fun name(context: Context, v: AsrVendor): String = context.getString(displayNameResId(v))

    /** 指定 vendor 的标签（用于选择器展示）。 */
    fun tags(v: AsrVendor): List<AsrVendorTag> = AsrVendorRegistry.descriptorFor(v).tags.map { it.toUiTag() }

    /** 顺序化的 (Vendor, 显示名) 列表 */
    fun pairs(context: Context): List<Pair<AsrVendor, String>> = ordered().map {
        it to
            name(context, it)
    }

    /** 顺序化的显示名列表 */
    fun names(context: Context): List<String> = ordered().map { name(context, it) }

    internal fun displayNameResId(v: AsrVendor): Int = AsrVendorRegistry.descriptorFor(v).displayNameResId

    private fun AsrVendorDisplayTag.toUiTag(): AsrVendorTag = when (this) {
        AsrVendorDisplayTag.Online -> AsrVendorTag.Online
        AsrVendorDisplayTag.Local -> AsrVendorTag.Local
        AsrVendorDisplayTag.Recommended -> AsrVendorTag.Recommended
        AsrVendorDisplayTag.Streaming -> AsrVendorTag.Streaming
        AsrVendorDisplayTag.NonStreaming -> AsrVendorTag.NonStreaming
        AsrVendorDisplayTag.PseudoStreaming -> AsrVendorTag.PseudoStreaming
        AsrVendorDisplayTag.Custom -> AsrVendorTag.Custom
        AsrVendorDisplayTag.ChineseDialect -> AsrVendorTag.ChineseDialect
        AsrVendorDisplayTag.Accurate -> AsrVendorTag.Accurate
    }
}
