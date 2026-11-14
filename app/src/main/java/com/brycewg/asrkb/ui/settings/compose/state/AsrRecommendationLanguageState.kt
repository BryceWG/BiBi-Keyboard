/**
 * 设置页与新手引导共用的推荐语言状态，恢复前台时同步持久化选择。
 */
package com.brycewg.asrkb.ui.settings.compose.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager

@Stable
internal class AsrRecommendationLanguageState(private val prefs: Prefs) {
    var selectedTag by mutableStateOf(prefs.asrRecommendationLanguageTag)
        private set

    fun select(tag: String) {
        prefs.asrRecommendationLanguageTag = tag
        selectedTag = tag
        DebugLogManager.log("asr", "recommendation_language_changed", mapOf("language" to tag))
    }

    fun refresh() {
        selectedTag = prefs.asrRecommendationLanguageTag
    }
}

@Composable
internal fun rememberAsrRecommendationLanguageState(prefs: Prefs): AsrRecommendationLanguageState {
    val state = remember(prefs) { AsrRecommendationLanguageState(prefs) }
    LifecycleResumeEffect(state) {
        state.refresh()
        onPauseOrDispose { }
    }
    return state
}
