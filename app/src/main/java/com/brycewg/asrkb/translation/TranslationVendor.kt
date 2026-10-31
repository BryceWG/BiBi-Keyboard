/** Translation provider choices and online language capabilities, independent of ASR vendors. */
package com.brycewg.asrkb.translation

import androidx.annotation.StringRes
import com.brycewg.asrkb.R

internal enum class TranslationVendor(val id: String, @param:StringRes val titleRes: Int, val languages: List<String>) {
    Foxlet("foxlet", R.string.translation_foxlet, emptyList()),
    Microsoft("microsoft", R.string.translation_microsoft, listOf("zh-Hans", "zh-Hant", "en", "ja", "ko", "fr", "de", "es", "ru", "pt", "it", "ar")),
    Tencent("tencent", R.string.translation_tencent, listOf("zh-Hans", "zh-Hant", "en", "ja", "ko", "fr", "de", "es", "ru", "pt", "it", "ar")),
    Volcengine("volcengine", R.string.translation_volcengine, listOf("zh-Hans", "zh-Hant", "en", "ja", "ko", "fr", "de", "es", "ru", "pt", "it", "ar")),
    Bilibili("bilibili", R.string.translation_bilibili, listOf("zh-Hans", "zh-Hant", "en", "ja", "ko", "fr", "de", "es", "ru", "pt", "it", "ar")),
    Google("google", R.string.translation_google, listOf("zh-Hans", "zh-Hant", "en", "ja", "ko", "fr", "de", "es", "ru", "pt", "it", "ar"));

    val offline: Boolean get() = this == Foxlet

    companion object {
        fun fromId(id: String): TranslationVendor = entries.first { it.id == id }
    }
}
