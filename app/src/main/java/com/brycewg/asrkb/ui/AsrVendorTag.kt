package com.brycewg.asrkb.ui

import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import com.brycewg.asrkb.R

enum class AsrVendorTag(
    @param:StringRes val labelResId: Int,
    @param:ColorRes val bgColorResId: Int,
    @param:ColorRes val textColorResId: Int
) {
    Online(
        labelResId = R.string.asr_vendor_tag_online,
        bgColorResId = R.color.asr_tag_bg_online,
        textColorResId = R.color.asr_tag_fg_online
    ),
    Local(
        labelResId = R.string.asr_vendor_tag_local,
        bgColorResId = R.color.asr_tag_bg_local,
        textColorResId = R.color.asr_tag_fg_local
    ),
    Recommended(
        labelResId = R.string.asr_vendor_tag_recommended,
        bgColorResId = R.color.asr_tag_bg_recommended,
        textColorResId = R.color.asr_tag_fg_recommended
    ),
    Streaming(
        labelResId = R.string.asr_vendor_tag_streaming,
        bgColorResId = R.color.asr_tag_bg_streaming,
        textColorResId = R.color.asr_tag_fg_streaming
    ),
    NonStreaming(
        labelResId = R.string.asr_vendor_tag_non_streaming,
        bgColorResId = R.color.asr_tag_bg_non_streaming,
        textColorResId = R.color.asr_tag_fg_non_streaming
    ),
    PseudoStreaming(
        labelResId = R.string.asr_vendor_tag_pseudo_streaming,
        bgColorResId = R.color.asr_tag_bg_pseudo_streaming,
        textColorResId = R.color.asr_tag_fg_pseudo_streaming
    ),
    Custom(
        labelResId = R.string.asr_vendor_tag_custom,
        bgColorResId = R.color.asr_tag_bg_custom,
        textColorResId = R.color.asr_tag_fg_custom
    ),
    ChineseDialect(
        labelResId = R.string.asr_vendor_tag_chinese_dialect,
        bgColorResId = R.color.asr_tag_bg_cn_dialect,
        textColorResId = R.color.asr_tag_fg_cn_dialect
    ),
    Accurate(
        labelResId = R.string.asr_vendor_tag_accurate,
        bgColorResId = R.color.asr_tag_bg_accurate,
        textColorResId = R.color.asr_tag_fg_accurate
    )
}
