/** Translation settings state; model downloads continue in their foreground service. */
package com.brycewg.asrkb.ui.settings.translation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager
import com.brycewg.asrkb.translation.FoxletTranslationService
import com.brycewg.asrkb.translation.TranslationModelCatalog
import com.brycewg.asrkb.translation.supportsFoxletTranslation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class TranslationSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val prefs = Prefs(context)
    val catalog = TranslationModelCatalog(context)
    val supported = supportsFoxletTranslation()
    private val mutableState = MutableStateFlow(State(prefs.translationEnabled, prefs.translationSourceLanguage, prefs.translationTargetLanguage, preload = prefs.translationPreloadEnabled, keepAliveMinutes = prefs.translationKeepAliveMinutes))
    val state = mutableState.asStateFlow()

    data class State(
        val enabled: Boolean,
        val source: String,
        val target: String,
        val installed: Set<String> = emptySet(),
        val packs: List<FoxletTranslationService.InstalledPack> = emptyList(),
        val preload: Boolean = false,
        val keepAliveMinutes: Int = 5,
        val loading: Boolean = true,
        val error: String? = null
    )

    init {
        refresh()
    }

    fun setEnabled(value: Boolean) {
        prefs.translationEnabled = value
        mutableState.update { it.copy(enabled = value) }
    }

    fun setLanguages(source: String, target: String) {
        prefs.translationSourceLanguage = source
        prefs.translationTargetLanguage = target
        mutableState.update { it.copy(source = source, target = target) }
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                val packs = if (supported) withContext(Dispatchers.IO) { FoxletTranslationService.installedPacks(context) } else emptyList()
                mutableState.update { it.copy(installed = packs.map { pack -> pack.id }.toSet(), packs = packs, loading = false, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLogManager.logError("app", "translation_models_read_error", data = mapOf("errorType" to e.javaClass.simpleName))
                mutableState.update { it.copy(loading = false, error = context.getString(R.string.translation_model_error)) }
            }
        }
    }

    fun setPreload(value: Boolean) {
        prefs.translationPreloadEnabled = value
        mutableState.update { it.copy(preload = value) }
    }

    fun setKeepAlive(value: Int) {
        prefs.translationKeepAliveMinutes = value
        mutableState.update { it.copy(keepAliveMinutes = value) }
        viewModelScope.launch(Dispatchers.IO) {
            FoxletTranslationService.updateResidency(context)
        }
    }

    fun delete(route: List<TranslationModelCatalog.Model> = catalog.route(state.value.source, state.value.target)) {
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true) }
            try {
                withContext(Dispatchers.IO) { FoxletTranslationService.delete(context, route) }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLogManager.logError("app", "translation_models_delete_error", data = mapOf("errorType" to e.javaClass.simpleName))
                mutableState.update { it.copy(loading = false, error = context.getString(R.string.translation_delete_failed)) }
            }
        }
    }
}
