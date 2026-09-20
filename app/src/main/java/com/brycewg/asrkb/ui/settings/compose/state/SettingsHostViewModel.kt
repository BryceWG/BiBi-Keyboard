/**
 * 设置页 Compose 宿主状态。
 *
 * 归属模块：ui/settings/compose/state
 */
package com.brycewg.asrkb.ui.settings.compose.state

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import com.brycewg.asrkb.ime.AsrKeyboardService
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.floating.FloatingAsrService
import com.brycewg.asrkb.ui.settings.compose.core.BibiSettingsRoute
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SettingsHostUiState(
    val uiMode: BibiUiMode = BibiUiMode.Miuix,
    val themeMode: String = "system",
    val selectedHomeTab: Int = 0,
    val highlightTargetId: String? = null,
    val backStack: List<BibiSettingsRoute> = listOf(BibiSettingsRoute.Home)
)

class SettingsHostViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = Prefs(application)
    private val _uiState = MutableStateFlow(
        SettingsHostUiState(
            uiMode = BibiUiMode.fromId(prefs.settingsUiMode),
            themeMode = prefs.settingsThemeMode
        )
    )
    val uiState: StateFlow<SettingsHostUiState> = _uiState.asStateFlow()

    fun setUiMode(mode: BibiUiMode) {
        prefs.settingsUiMode = mode.id
        _uiState.update { it.copy(uiMode = mode) }
        refreshInputSurfaces()
    }

    fun setThemeMode(mode: String) {
        prefs.settingsThemeMode = mode
        _uiState.update { it.copy(themeMode = prefs.settingsThemeMode) }
        refreshInputSurfaces()
    }

    fun selectHomeTab(index: Int) {
        _uiState.update { it.copy(selectedHomeTab = index.coerceIn(0, 2)) }
    }

    fun push(route: BibiSettingsRoute) {
        _uiState.update { state ->
            if (state.backStack.lastOrNull() == route) {
                state
            } else {
                state.copy(backStack = state.backStack + route)
            }
        }
    }

    fun openRoute(route: BibiSettingsRoute) {
        openRoute(route, highlightTargetId = null)
    }

    fun openRoute(route: BibiSettingsRoute, highlightTargetId: String?) {
        if (route == BibiSettingsRoute.Home) {
            _uiState.update {
                it.copy(
                    highlightTargetId = highlightTargetId,
                    backStack = listOf(BibiSettingsRoute.Home)
                )
            }
            return
        }
        _uiState.update { state ->
            state.copy(
                selectedHomeTab = route.homeTabIndex(),
                highlightTargetId = highlightTargetId,
                backStack = listOf(BibiSettingsRoute.Home, route)
            )
        }
    }

    fun pop(): Boolean {
        val state = _uiState.value
        if (state.backStack.size <= 1) return false
        _uiState.value = state.copy(backStack = state.backStack.dropLast(1))
        return true
    }

    private fun BibiSettingsRoute.homeTabIndex(): Int = when (this) {
        BibiSettingsRoute.Input,
        BibiSettingsRoute.UiSettings,
        BibiSettingsRoute.KeyboardLayout,
        BibiSettingsRoute.RecordingTest,
        BibiSettingsRoute.Floating -> 0

        BibiSettingsRoute.Asr,
        BibiSettingsRoute.Ai,
        BibiSettingsRoute.PromptSelection,
        BibiSettingsRoute.PromptSelectionPreview -> 1

        BibiSettingsRoute.Backup,
        BibiSettingsRoute.Other,
        BibiSettingsRoute.About,
        BibiSettingsRoute.Paywall,
        BibiSettingsRoute.UsageStats,
        BibiSettingsRoute.Search,
        BibiSettingsRoute.History,
        BibiSettingsRoute.ApiLog,
        BibiSettingsRoute.Home -> 2
    }

    private fun refreshInputSurfaces() {
        val app = getApplication<Application>()
        app.sendBroadcast(
            Intent(AsrKeyboardService.ACTION_REFRESH_IME_UI).setPackage(app.packageName)
        )
        if (prefs.floatingAsrEnabled) {
            app.startService(
                Intent(app, FloatingAsrService::class.java).apply {
                    action = FloatingAsrService.ACTION_REFRESH_UI
                }
            )
        }
    }
}
