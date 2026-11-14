/**
 * ASR 推荐语言的系统解析与显示名称；独立于界面语言和识别请求的语言参数。
 */
package com.brycewg.asrkb.ui

import android.content.Context
import androidx.core.app.LocaleManagerCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrVendorRecommendations
import java.text.Collator
import java.util.Locale

internal object AsrRecommendationLanguages {
    const val OTHER = "other"

    fun resolve(context: Context, selectedTag: String): String {
        val tag = if (selectedTag.isEmpty()) {
            languageTag(LocaleManagerCompat.getSystemLocales(context)[0]!!)
        } else {
            selectedTag
        }
        return if (tag in AsrVendorRecommendations.adaptedLanguageTags) tag else OTHER
    }

    fun displayName(context: Context, tag: String): String = when (tag) {
        OTHER -> context.getString(R.string.asr_recommendation_language_other)
        "zh-Hans" -> context.getString(R.string.lang_zh_cn)
        "zh-Hant" -> context.getString(R.string.lang_zh_tw)
        else -> Locale.forLanguageTag(tag).getDisplayLanguage(context.resources.configuration.locales[0])
    }

    fun options(context: Context): List<String> {
        val collator = Collator.getInstance(context.resources.configuration.locales[0])
        return AsrVendorRecommendations.adaptedLanguageTags
            .filter { it != OTHER }
            .sortedWith { first, second -> collator.compare(displayName(context, first), displayName(context, second)) } + OTHER
    }

    private fun languageTag(locale: Locale): String = when {
        locale.language != "zh" -> locale.language
        locale.script == "Hant" -> "zh-Hant"
        locale.script == "Hans" -> "zh-Hans"
        locale.country in setOf("TW", "HK", "MO") -> "zh-Hant"
        else -> "zh-Hans"
    }
}
