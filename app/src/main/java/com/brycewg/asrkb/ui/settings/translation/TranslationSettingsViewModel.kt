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
import com.brycewg.asrkb.translation.TranslationVendor
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
    private val mutableState = MutableStateFlow(State(prefs.translationEnabled, currentSource(), prefs.translationTargetLanguage, vendor = TranslationVendor.fromId(prefs.translationVendor), preload = prefs.translationPreloadEnabled, keepAliveMinutes = prefs.translationKeepAliveMinutes))
    val state = mutableState.asStateFlow()

    data class State(
        val enabled: Boolean,
        val source: String,
        val target: String,
        val vendor: TranslationVendor,
        val installed: Set<String> = emptySet(),
        val packs: List<FoxletTranslationService.InstalledPack> = emptyList(),
        val preload: Boolean = false,
        val keepAliveMinutes: Int = 5,
        val loading: Boolean = true,
        val error: String? = null,
        val testing: Boolean = false,
        val testResult: String? = null
    )

    init {
        refresh()
    }

    fun setEnabled(value: Boolean) {
        prefs.translationEnabled = value
        mutableState.update { it.copy(enabled = value) }
    }

    fun setLanguages(source: String, target: String) {
        if (state.value.vendor.offline) prefs.translationSourceLanguage = source else prefs.translationOnlineSourceLanguage = source
        prefs.translationTargetLanguage = target
        mutableState.update { it.copy(source = source, target = target) }
    }

    private fun currentSource(): String = if (prefs.translationVendor == "foxlet") prefs.translationSourceLanguage else prefs.translationOnlineSourceLanguage

    fun sources(): List<String> = if (state.value.vendor.offline) catalog.sources else listOf("auto") + state.value.vendor.languages

    fun targets(source: String): List<String> = if (state.value.vendor.offline) catalog.targets(source) else state.value.vendor.languages.filter { it != source }

    fun setVendor(vendor: TranslationVendor) {
        prefs.translationVendor = vendor.id
        mutableState.update { it.copy(vendor = vendor, source = currentSource(), error = null) }
        val allowedSources = sources()
        val source = if (state.value.source in allowedSources) state.value.source else allowedSources.first()
        val allowedTargets = targets(source)
        val target = if (state.value.target in allowedTargets) state.value.target else allowedTargets.first()
        setLanguages(source, target)
        refresh()
    }

    fun dismissTestResult() {
        mutableState.update { it.copy(testResult = null) }
    }

    fun testTranslation() {
        if (state.value.testing) return
        mutableState.update { it.copy(testing = true, testResult = null) }
        viewModelScope.launch {
            val started = android.os.SystemClock.elapsedRealtime()
            val sample = context.getString(
                if (state.value.source.startsWith("zh")) R.string.translation_test_sample_chinese else R.string.translation_test_sample
            )
            try {
                val translated = com.brycewg.asrkb.translation.TranslationPipeline.translate(context, prefs, sample, enabled = true)
                val elapsed = android.os.SystemClock.elapsedRealtime() - started
                mutableState.update {
                    it.copy(testing = false, testResult = context.getString(R.string.translation_test_success, elapsed, sample, translated))
                }
            } catch (e: CancellationException) {
                mutableState.update { it.copy(testing = false) }
                throw e
            } catch (e: Exception) {
                val reason = e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName
                mutableState.update { it.copy(testing = false, testResult = context.getString(R.string.translation_test_failed, reason)) }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                val packs = if (state.value.vendor.offline && supported) withContext(Dispatchers.IO) { FoxletTranslationService.installedPacks(context) } else emptyList()
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
