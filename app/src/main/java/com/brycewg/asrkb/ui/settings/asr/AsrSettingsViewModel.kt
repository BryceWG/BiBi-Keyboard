// ASR settings state and local model status orchestration.
package com.brycewg.asrkb.ui.settings.asr

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brycewg.asrkb.asr.AsrLocalVendorLifecycles
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.asr.LocalModelCheck
import com.brycewg.asrkb.asr.LocalModelSpecs
import com.brycewg.asrkb.asr.SherpaPunctuationManager
import com.brycewg.asrkb.asr.VolcAsrModelCatalog
import com.brycewg.asrkb.asr.requireModelFilesCached
import com.brycewg.asrkb.store.Prefs
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for ASR Settings screen, managing all state and business logic.
 * UI observes StateFlows and reacts to state changes automatically.
 */
class AsrSettingsViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(AsrSettingsUiState())
    val uiState: StateFlow<AsrSettingsUiState> = _uiState.asStateFlow()

    private lateinit var prefs: Prefs
    private lateinit var appContext: Context
    private var initialized = false
    private var refreshJob: Job? = null

    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        prefs = Prefs(appContext)
        initialized = true
        refreshFromPrefs()
    }

    fun refreshFromPrefs() {
        if (initialized) {
            refreshJob?.cancel()
            val job = viewModelScope.launch(Dispatchers.IO) {
                loadInitialState()
            }
            refreshJob = job
            job.invokeOnCompletion {
                if (refreshJob === job) {
                    refreshJob = null
                }
            }
        }
    }

    private fun loadInitialState() {
        fun isQwenOmniModel(model: String): Boolean = model.startsWith("Qwen/Qwen3-Omni-30B-A3B-")
        _uiState.value = AsrSettingsUiState(
            selectedVendor = prefs.asrVendor,
            recordingAutoStopMode = prefs.recordingAutoStopMode,
            autoStopSilenceEnabled = prefs.autoStopOnSilenceEnabled,
            silenceWindowMs = prefs.autoStopSilenceWindowMs,
            silenceSensitivity = prefs.autoStopSilenceSensitivity,
            recordingMaxDurationMs = prefs.recordingMaxDurationMs,
            aiEditPreferLastAsr = prefs.aiEditDefaultToLastAsr,
            // Volc settings
            volcAsrModel = prefs.volcAsrModel,
            volcDdcEnabled = prefs.volcDdcEnabled,
            volcVadEnabled = prefs.volcVadEnabled,
            volcNonstreamEnabled = prefs.volcNonstreamEnabled,
            volcUseNewAuth = prefs.volcUseNewAuth,
            volcLanguage = prefs.volcLanguage,
            // SiliconFlow settings
            sfUseOmni = isQwenOmniModel(
                prefs.sfModel.ifBlank {
                    com.brycewg.asrkb.store.Prefs.DEFAULT_SF_MODEL
                }
            ),
            // Eleven settings
            elevenStreamingEnabled = prefs.elevenStreamingEnabled,
            // OpenAI settings
            oaAsrStreamingEnabled = prefs.oaAsrStreamingEnabled,
            oaAsrUseCompletions = prefs.oaAsrUseCompletions,
            oaAsrUsePrompt = prefs.oaAsrUsePrompt,
            // Soniox settings
            sonioxStreamingEnabled = prefs.sonioxStreamingEnabled,
            sonioxLanguages = prefs.getSonioxLanguages(),
            // SenseVoice settings
            svModelVariant = prefs.svModelVariant,
            svNumThreads = prefs.svNumThreads,
            svLanguage = prefs.svLanguage,
            svUseItn = prefs.svUseItn,
            svPreloadEnabled = prefs.svPreloadEnabled,
            svKeepAliveMinutes = prefs.svKeepAliveMinutes,
            svPseudoStreamEnabled = prefs.svPseudoStreamEnabled,
            // FunASR Nano settings
            fnModelVariant = prefs.fnModelVariant,
            fnNumThreads = prefs.fnNumThreads,
            fnUseItn = prefs.fnUseItn,
            fnUserPrompt = prefs.fnUserPrompt,
            fnLanguage = prefs.fnLanguage,
            fnPreloadEnabled = prefs.fnPreloadEnabled,
            fnKeepAliveMinutes = prefs.fnKeepAliveMinutes,
            // Qwen3-ASR settings
            qwModelVariant = prefs.qwModelVariant,
            qwNumThreads = prefs.qwNumThreads,
            qwPreloadEnabled = prefs.qwPreloadEnabled,
            qwKeepAliveMinutes = prefs.qwKeepAliveMinutes,
            qwUseItn = prefs.qwUseItn,
            // Parakeet settings
            pkModelVariant = prefs.pkModelVariant,
            pkNumThreads = prefs.pkNumThreads,
            pkPreloadEnabled = prefs.pkPreloadEnabled,
            pkKeepAliveMinutes = prefs.pkKeepAliveMinutes,
            // FireRedASR settings
            frModelVariant = prefs.frModelVariant,
            frNumThreads = prefs.frNumThreads,
            frKeepAliveMinutes = prefs.frKeepAliveMinutes,
            frPreloadEnabled = prefs.frPreloadEnabled,
            frUseItn = prefs.frUseItn,
            frPseudoStreamEnabled = prefs.frPseudoStreamEnabled,
            // X-ASR settings
            xAsrModelVariant = prefs.xAsrModelVariant,
            xAsrNumThreads = prefs.xAsrNumThreads,
            xAsrKeepAliveMinutes = prefs.xAsrKeepAliveMinutes,
            xAsrPreloadEnabled = prefs.xAsrPreloadEnabled,
            xAsrUseItn = prefs.xAsrUseItn,
            // Tencent settings
            tencentVadEnabled = prefs.tencentVadEnabled
        )
    }

    fun updateVendor(vendor: AsrVendor) {
        val oldVendor = prefs.asrVendor
        prefs.asrVendor = vendor
        _uiState.value = _uiState.value.copy(selectedVendor = vendor)

        // Handle local model lifecycle cleanup
        if (oldVendor == AsrVendor.SenseVoice && vendor != AsrVendor.SenseVoice) {
            try {
                com.brycewg.asrkb.asr.unloadSenseVoiceRecognizer()
            } catch (
                e: Throwable
            ) {
                Log.e(TAG, "Failed to unload SenseVoice recognizer", e)
            }
        }
        if (oldVendor == AsrVendor.FunAsrNano && vendor != AsrVendor.FunAsrNano) {
            try {
                com.brycewg.asrkb.asr.unloadFunAsrNanoRecognizer()
            } catch (
                e: Throwable
            ) {
                Log.e(TAG, "Failed to unload FunASR Nano recognizer", e)
            }
        }
        if (oldVendor == AsrVendor.Qwen3Asr && vendor != AsrVendor.Qwen3Asr) {
            try {
                com.brycewg.asrkb.asr.unloadQwen3AsrRecognizer()
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to unload Qwen3-ASR recognizer", e)
            }
        }
        if (oldVendor == AsrVendor.Parakeet && vendor != AsrVendor.Parakeet) {
            try {
                com.brycewg.asrkb.asr.unloadParakeetRecognizer()
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to unload Parakeet recognizer", e)
            }
        }
        if (oldVendor == AsrVendor.FireRedAsr && vendor != AsrVendor.FireRedAsr) {
            try {
                com.brycewg.asrkb.asr.unloadFireRedAsrRecognizer()
            } catch (
                e: Throwable
            ) {
                Log.e(TAG, "Failed to unload FireRedASR recognizer", e)
            }
        }
        if (oldVendor == AsrVendor.XAsr && vendor != AsrVendor.XAsr) {
            try {
                com.brycewg.asrkb.asr.unloadXAsrRecognizer()
            } catch (
                e: Throwable
            ) {
                Log.e(TAG, "Failed to unload X-ASR recognizer", e)
            }
        }

        // FireRedASR 仍依赖通用标点模型，切换时如未安装则提示一次。
        if (vendor == AsrVendor.FireRedAsr) {
            try {
                com.brycewg.asrkb.asr.SherpaPunctuationManager.maybeWarnModelMissing(appContext)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to warn punctuation model missing on vendor change", t)
            }
        }

        if (vendor == AsrVendor.SenseVoice && prefs.svPreloadEnabled) {
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    com.brycewg.asrkb.asr.preloadSenseVoiceIfConfigured(
                        appContext,
                        prefs
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to preload SenseVoice model", e)
                }
            }
        }
        if (vendor == AsrVendor.FunAsrNano && prefs.fnPreloadEnabled) {
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    com.brycewg.asrkb.asr.preloadFunAsrNanoIfConfigured(
                        appContext,
                        prefs
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to preload FunASR Nano model", e)
                }
            }
        }
        if (vendor == AsrVendor.Qwen3Asr && prefs.qwPreloadEnabled) {
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    com.brycewg.asrkb.asr.preloadQwen3AsrIfConfigured(
                        appContext,
                        prefs
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to preload Qwen3-ASR model", e)
                }
            }
        }
        if (vendor == AsrVendor.Parakeet && prefs.pkPreloadEnabled) {
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    com.brycewg.asrkb.asr.preloadParakeetIfConfigured(
                        appContext,
                        prefs
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to preload Parakeet model", e)
                }
            }
        }
        if (vendor == AsrVendor.FireRedAsr && prefs.frPreloadEnabled) {
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    com.brycewg.asrkb.asr.preloadFireRedAsrIfConfigured(
                        appContext,
                        prefs
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to preload FireRedASR model", e)
                }
            }
        }

        if (vendor == AsrVendor.XAsr && prefs.xAsrPreloadEnabled) {
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    com.brycewg.asrkb.asr.preloadXAsrIfConfigured(
                        appContext,
                        prefs
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to preload X-ASR model", e)
                }
            }
        }
    }

    fun updateAutoStopSilence(enabled: Boolean) {
        prefs.autoStopOnSilenceEnabled = enabled
        _uiState.value = _uiState.value.copy(
            recordingAutoStopMode = prefs.recordingAutoStopMode,
            autoStopSilenceEnabled = prefs.autoStopOnSilenceEnabled
        )
    }

    fun updateRecordingAutoStopMode(mode: Prefs.RecordingAutoStopMode) {
        prefs.recordingAutoStopMode = mode
        _uiState.value = _uiState.value.copy(
            recordingAutoStopMode = mode,
            autoStopSilenceEnabled = mode == Prefs.RecordingAutoStopMode.SILENCE
        )
    }

    fun updateSilenceWindow(windowMs: Int) {
        prefs.autoStopSilenceWindowMs = windowMs
        _uiState.value = _uiState.value.copy(silenceWindowMs = windowMs)
    }

    fun updateSilenceSensitivity(sensitivity: Int) {
        prefs.autoStopSilenceSensitivity = sensitivity
        _uiState.value = _uiState.value.copy(silenceSensitivity = prefs.autoStopSilenceSensitivity)
    }

    fun updateRecordingMaxDuration(durationMs: Int) {
        prefs.recordingMaxDurationMs = durationMs
        _uiState.value = _uiState.value.copy(recordingMaxDurationMs = prefs.recordingMaxDurationMs)
    }

    fun updateVolcAsrModel(modelId: String) {
        prefs.volcAsrModel = modelId
        _uiState.value = _uiState.value.copy(volcAsrModel = modelId)
    }

    fun updateVolcDdc(enabled: Boolean) {
        prefs.volcDdcEnabled = enabled
        _uiState.value = _uiState.value.copy(volcDdcEnabled = enabled)
    }

    fun updateVolcVad(enabled: Boolean) {
        prefs.volcVadEnabled = enabled
        _uiState.value = _uiState.value.copy(volcVadEnabled = enabled)
    }

    fun updateVolcNonstream(enabled: Boolean) {
        prefs.volcNonstreamEnabled = enabled
        _uiState.value = _uiState.value.copy(volcNonstreamEnabled = enabled)
    }

    fun updateVolcUseNewAuth(enabled: Boolean) {
        prefs.volcUseNewAuth = enabled
        _uiState.value = _uiState.value.copy(volcUseNewAuth = enabled)
    }

    fun updateVolcLanguage(language: String) {
        prefs.volcLanguage = language
        _uiState.value = _uiState.value.copy(volcLanguage = language)
    }

    fun updateSfUseOmni(enabled: Boolean) {
        prefs.sfUseOmni = enabled
        _uiState.value = _uiState.value.copy(sfUseOmni = enabled)
    }

    fun updateOpenAiStreaming(enabled: Boolean) {
        prefs.oaAsrStreamingEnabled = enabled
        _uiState.value = _uiState.value.copy(oaAsrStreamingEnabled = enabled)
    }

    fun updateOpenAiUsePrompt(enabled: Boolean) {
        prefs.oaAsrUsePrompt = enabled
        _uiState.value = _uiState.value.copy(oaAsrUsePrompt = enabled)
    }

    fun refreshOpenAiProfileState() {
        _uiState.value = _uiState.value.copy(
            oaAsrStreamingEnabled = prefs.oaAsrStreamingEnabled,
            oaAsrUseCompletions = prefs.oaAsrUseCompletions,
            oaAsrUsePrompt = prefs.oaAsrUsePrompt
        )
    }

    fun updateElevenStreaming(enabled: Boolean) {
        prefs.elevenStreamingEnabled = enabled
        _uiState.value = _uiState.value.copy(elevenStreamingEnabled = enabled)
    }

    fun updateSonioxStreaming(enabled: Boolean) {
        prefs.sonioxStreamingEnabled = enabled
        _uiState.value = _uiState.value.copy(sonioxStreamingEnabled = enabled)
    }

    fun updateTencentVadEnabled(enabled: Boolean) {
        prefs.tencentVadEnabled = enabled
        _uiState.value = _uiState.value.copy(tencentVadEnabled = enabled)
    }

    fun updateSonioxLanguages(languages: List<String>) {
        prefs.setSonioxLanguages(languages)
        _uiState.value = _uiState.value.copy(sonioxLanguages = languages)
    }

    fun updateSvModelVariant(variant: String) {
        prefs.svModelVariant = variant
        _uiState.value = _uiState.value.copy(svModelVariant = variant)
        try {
            com.brycewg.asrkb.asr.unloadSenseVoiceRecognizer()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to unload SenseVoice recognizer after variant change", e)
        }
        triggerSvPreloadIfEnabledAndActive("variant change")
    }

    fun updateSvNumThreads(threads: Int) {
        prefs.svNumThreads = threads
        _uiState.value = _uiState.value.copy(svNumThreads = threads)
        try {
            com.brycewg.asrkb.asr.unloadSenseVoiceRecognizer()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to unload SenseVoice recognizer after threads change", e)
        }
        triggerSvPreloadIfEnabledAndActive("threads change")
    }

    fun updateSvLanguage(language: String) {
        prefs.svLanguage = language
        _uiState.value = _uiState.value.copy(svLanguage = language)
        try {
            com.brycewg.asrkb.asr.unloadSenseVoiceRecognizer()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to unload SenseVoice recognizer after language change", e)
        }
        triggerSvPreloadIfEnabledAndActive("language change")
    }

    fun updateSvUseItn(enabled: Boolean) {
        if (prefs.svUseItn != enabled) {
            prefs.svUseItn = enabled
            _uiState.value = _uiState.value.copy(svUseItn = enabled)
            try {
                com.brycewg.asrkb.asr.unloadSenseVoiceRecognizer()
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to unload SenseVoice recognizer after ITN change", e)
            }
            triggerSvPreloadIfEnabledAndActive("ITN change")
        }
    }

    fun updateFrUseItn(enabled: Boolean) {
        if (prefs.frUseItn != enabled) {
            prefs.frUseItn = enabled
            _uiState.value = _uiState.value.copy(frUseItn = enabled)
            try {
                com.brycewg.asrkb.asr.unloadFireRedAsrRecognizer()
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to unload FireRedASR recognizer after ITN change", e)
            }
            triggerFireRedAsrPreloadIfEnabledAndActive("ITN change")
        }
    }

    fun updateXAsrUseItn(enabled: Boolean) {
        if (prefs.xAsrUseItn != enabled) {
            prefs.xAsrUseItn = enabled
            _uiState.value = _uiState.value.copy(xAsrUseItn = enabled)
            try {
                com.brycewg.asrkb.asr.unloadXAsrRecognizer()
            } catch (
                e: Throwable
            ) {
                Log.e(TAG, "Failed to unload X-ASR recognizer after ITN change", e)
            }
            triggerXAsrPreloadIfEnabledAndActive("ITN change")
        }
    }

    fun updateSvPreload(enabled: Boolean) {
        prefs.svPreloadEnabled = enabled
        _uiState.value = _uiState.value.copy(svPreloadEnabled = enabled)

        if (enabled && prefs.asrVendor == AsrVendor.SenseVoice) {
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    com.brycewg.asrkb.asr.preloadSenseVoiceIfConfigured(
                        appContext,
                        prefs
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to preload SenseVoice model", e)
                }
            }
        }
    }

    fun updateSvPseudoStream(enabled: Boolean) {
        prefs.svPseudoStreamEnabled = enabled
        _uiState.value = _uiState.value.copy(svPseudoStreamEnabled = enabled)
    }

    fun updateFnModelVariant(variant: String) {
        prefs.fnModelVariant = variant
        _uiState.value = _uiState.value.copy(fnModelVariant = variant)
        try {
            com.brycewg.asrkb.asr.unloadFunAsrNanoRecognizer()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to unload FunASR Nano recognizer after variant change", e)
        }
        triggerFnPreloadIfEnabledAndActive("variant change")
    }

    fun updateFnNumThreads(threads: Int) {
        prefs.fnNumThreads = threads
        _uiState.value = _uiState.value.copy(fnNumThreads = threads)
        try {
            com.brycewg.asrkb.asr.unloadFunAsrNanoRecognizer()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to unload FunASR Nano recognizer after threads change", e)
        }
        triggerFnPreloadIfEnabledAndActive("threads change")
    }

    fun updateFnUseItn(enabled: Boolean) {
        if (prefs.fnUseItn != enabled) {
            prefs.fnUseItn = enabled
            _uiState.value = _uiState.value.copy(fnUseItn = enabled)
            try {
                com.brycewg.asrkb.asr.unloadFunAsrNanoRecognizer()
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to unload FunASR Nano recognizer after ITN change", e)
            }
            triggerFnPreloadIfEnabledAndActive("ITN change")
        }
    }

    fun updateFnUserPrompt(prompt: String) {
        val newPrompt = prompt.trim()
        if (prefs.fnUserPrompt == newPrompt) return
        prefs.fnUserPrompt = newPrompt
        _uiState.value = _uiState.value.copy(fnUserPrompt = newPrompt)
        try {
            com.brycewg.asrkb.asr.unloadFunAsrNanoRecognizer()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to unload FunASR Nano recognizer after userPrompt change", e)
        }
        triggerFnPreloadIfEnabledAndActive("userPrompt change")
    }

    fun updateFnLanguage(language: String) {
        val newLanguage = language.trim()
        if (prefs.fnLanguage == newLanguage) return
        prefs.fnLanguage = newLanguage
        _uiState.value = _uiState.value.copy(fnLanguage = newLanguage)
        try {
            com.brycewg.asrkb.asr.unloadFunAsrNanoRecognizer()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to unload FunASR Nano recognizer after language change", e)
        }
        triggerFnPreloadIfEnabledAndActive("language change")
    }

    fun updateFnPreload(enabled: Boolean) {
        prefs.fnPreloadEnabled = enabled
        _uiState.value = _uiState.value.copy(fnPreloadEnabled = enabled)

        if (enabled && prefs.asrVendor == AsrVendor.FunAsrNano) {
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    com.brycewg.asrkb.asr.preloadFunAsrNanoIfConfigured(
                        appContext,
                        prefs
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to preload FunASR Nano model", e)
                }
            }
        }
    }

    fun updateFrModelVariant(variant: String) {
        prefs.frModelVariant = variant
        _uiState.value = _uiState.value.copy(frModelVariant = variant)
        try {
            com.brycewg.asrkb.asr.unloadFireRedAsrRecognizer()
        } catch (
            e: Throwable
        ) {
            Log.e(TAG, "Failed to unload FireRedASR recognizer after variant change", e)
        }
        triggerFireRedAsrPreloadIfEnabledAndActive("variant change")
    }

    fun updateFrKeepAlive(minutes: Int) {
        prefs.frKeepAliveMinutes = minutes
        _uiState.value = _uiState.value.copy(frKeepAliveMinutes = minutes)
    }

    fun updateFrNumThreads(v: Int) {
        val vv = v.coerceIn(1, 8)
        prefs.frNumThreads = vv
        _uiState.value = _uiState.value.copy(frNumThreads = vv)
        try {
            com.brycewg.asrkb.asr.unloadFireRedAsrRecognizer()
        } catch (
            e: Throwable
        ) {
            Log.e(TAG, "Failed to unload FireRedASR recognizer after threads change", e)
        }
        triggerFireRedAsrPreloadIfEnabledAndActive("threads change")
    }

    fun updateFrPreload(enabled: Boolean) {
        prefs.frPreloadEnabled = enabled
        _uiState.value = _uiState.value.copy(frPreloadEnabled = enabled)

        if (enabled && prefs.asrVendor == AsrVendor.FireRedAsr) {
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    com.brycewg.asrkb.asr.preloadFireRedAsrIfConfigured(
                        appContext,
                        prefs
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to preload FireRedASR model", e)
                }
            }
        }
    }

    fun updateFrPseudoStream(enabled: Boolean) {
        prefs.frPseudoStreamEnabled = enabled
        _uiState.value = _uiState.value.copy(frPseudoStreamEnabled = enabled)
    }

    fun updateSvKeepAlive(minutes: Int) {
        prefs.svKeepAliveMinutes = minutes
        _uiState.value = _uiState.value.copy(svKeepAliveMinutes = minutes)
    }

    fun updateFnKeepAlive(minutes: Int) {
        prefs.fnKeepAliveMinutes = minutes
        _uiState.value = _uiState.value.copy(fnKeepAliveMinutes = minutes)
    }

    // ----- Qwen3-ASR -----
    fun updateQwModelVariant(variant: String) {
        val normalized = com.brycewg.asrkb.asr.normalizeQwen3AsrVariant(variant)
        prefs.qwModelVariant = normalized
        _uiState.value = _uiState.value.copy(qwModelVariant = normalized)
        try {
            com.brycewg.asrkb.asr.unloadQwen3AsrRecognizer()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to unload Qwen3-ASR recognizer after variant change", e)
        }
        triggerQwPreloadIfEnabledAndActive("variant change")
    }

    fun updateQwKeepAlive(minutes: Int) {
        prefs.qwKeepAliveMinutes = minutes
        _uiState.value = _uiState.value.copy(qwKeepAliveMinutes = minutes)
    }

    fun updateQwNumThreads(v: Int) {
        val vv = v.coerceIn(1, 8)
        prefs.qwNumThreads = vv
        _uiState.value = _uiState.value.copy(qwNumThreads = vv)
        try {
            com.brycewg.asrkb.asr.unloadQwen3AsrRecognizer()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to unload Qwen3-ASR recognizer after threads change", e)
        }
        triggerQwPreloadIfEnabledAndActive("threads change")
    }

    fun updateQwPreload(enabled: Boolean) {
        prefs.qwPreloadEnabled = enabled
        _uiState.value = _uiState.value.copy(qwPreloadEnabled = enabled)

        if (enabled && prefs.asrVendor == AsrVendor.Qwen3Asr) {
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    com.brycewg.asrkb.asr.preloadQwen3AsrIfConfigured(
                        appContext,
                        prefs
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to preload Qwen3-ASR model", e)
                }
            }
        }
    }

    fun updateQwUseItn(enabled: Boolean) {
        if (prefs.qwUseItn != enabled) {
            prefs.qwUseItn = enabled
            _uiState.value = _uiState.value.copy(qwUseItn = enabled)
        }
    }

    // ----- Parakeet -----
    fun updatePkModelVariant(variant: String) {
        val normalized = com.brycewg.asrkb.asr.normalizeParakeetVariant(variant)
        prefs.pkModelVariant = normalized
        _uiState.value = _uiState.value.copy(pkModelVariant = normalized)
        try {
            com.brycewg.asrkb.asr.unloadParakeetRecognizer()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to unload Parakeet recognizer after variant change", e)
        }
        triggerPkPreloadIfEnabledAndActive("variant change")
    }

    fun updatePkKeepAlive(minutes: Int) {
        prefs.pkKeepAliveMinutes = minutes
        _uiState.value = _uiState.value.copy(pkKeepAliveMinutes = minutes)
    }

    fun updatePkNumThreads(v: Int) {
        val vv = v.coerceIn(1, 8)
        prefs.pkNumThreads = vv
        _uiState.value = _uiState.value.copy(pkNumThreads = vv)
        try {
            com.brycewg.asrkb.asr.unloadParakeetRecognizer()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to unload Parakeet recognizer after threads change", e)
        }
        triggerPkPreloadIfEnabledAndActive("threads change")
    }

    fun updatePkPreload(enabled: Boolean) {
        prefs.pkPreloadEnabled = enabled
        _uiState.value = _uiState.value.copy(pkPreloadEnabled = enabled)

        if (enabled && prefs.asrVendor == AsrVendor.Parakeet) {
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    com.brycewg.asrkb.asr.preloadParakeetIfConfigured(
                        appContext,
                        prefs
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to preload Parakeet model", e)
                }
            }
        }
    }

    // ----- X-ASR -----
    fun updateXAsrModelVariant(variant: String) {
        prefs.xAsrModelVariant = variant
        _uiState.value = _uiState.value.copy(xAsrModelVariant = variant)
        try {
            com.brycewg.asrkb.asr.unloadXAsrRecognizer()
        } catch (_: Throwable) { }
        triggerXAsrPreloadIfEnabledAndActive("variant change")
    }

    fun updateXAsrKeepAlive(minutes: Int) {
        prefs.xAsrKeepAliveMinutes = minutes
        _uiState.value = _uiState.value.copy(xAsrKeepAliveMinutes = minutes)
    }

    fun updateXAsrNumThreads(v: Int) {
        val vv = v.coerceIn(1, 8)
        prefs.xAsrNumThreads = vv
        _uiState.value = _uiState.value.copy(xAsrNumThreads = vv)
        // 线程数变化后卸载已缓存识别器，必要时重新预加载
        try {
            com.brycewg.asrkb.asr.unloadXAsrRecognizer()
        } catch (_: Throwable) { }
        triggerXAsrPreloadIfEnabledAndActive("threads change")
    }

    // 统一预加载触发
    private fun triggerSvPreloadIfEnabledAndActive(reason: String) {
        if (prefs.svPreloadEnabled && prefs.asrVendor == AsrVendor.SenseVoice) {
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    com.brycewg.asrkb.asr.preloadSenseVoiceIfConfigured(appContext, prefs)
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to preload SenseVoice after $reason", t)
                }
            }
        }
    }

    private fun triggerFnPreloadIfEnabledAndActive(reason: String) {
        if (prefs.fnPreloadEnabled && prefs.asrVendor == AsrVendor.FunAsrNano) {
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    com.brycewg.asrkb.asr.preloadFunAsrNanoIfConfigured(appContext, prefs)
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to preload FunASR Nano after $reason", t)
                }
            }
        }
    }

    private fun triggerQwPreloadIfEnabledAndActive(reason: String) {
        if (prefs.qwPreloadEnabled && prefs.asrVendor == AsrVendor.Qwen3Asr) {
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    com.brycewg.asrkb.asr.preloadQwen3AsrIfConfigured(appContext, prefs)
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to preload Qwen3-ASR after $reason", t)
                }
            }
        }
    }

    private fun triggerPkPreloadIfEnabledAndActive(reason: String) {
        if (prefs.pkPreloadEnabled && prefs.asrVendor == AsrVendor.Parakeet) {
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    com.brycewg.asrkb.asr.preloadParakeetIfConfigured(appContext, prefs)
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to preload Parakeet after $reason", t)
                }
            }
        }
    }

    private fun triggerFireRedAsrPreloadIfEnabledAndActive(reason: String) {
        if (prefs.frPreloadEnabled && prefs.asrVendor == AsrVendor.FireRedAsr) {
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    com.brycewg.asrkb.asr.preloadFireRedAsrIfConfigured(appContext, prefs)
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to preload FireRedASR after $reason", t)
                }
            }
        }
    }

    private fun triggerXAsrPreloadIfEnabledAndActive(reason: String) {
        if (prefs.xAsrPreloadEnabled && prefs.asrVendor == AsrVendor.XAsr) {
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    com.brycewg.asrkb.asr.preloadXAsrIfConfigured(appContext, prefs)
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to preload X-ASR after $reason", t)
                }
            }
        }
    }

    fun updateXAsrPreload(enabled: Boolean) {
        prefs.xAsrPreloadEnabled = enabled
        _uiState.value = _uiState.value.copy(xAsrPreloadEnabled = enabled)

        if (enabled && prefs.asrVendor == AsrVendor.XAsr) {
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    com.brycewg.asrkb.asr.preloadXAsrIfConfigured(
                        appContext,
                        prefs
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to preload X-ASR model", e)
                }
            }
        }
    }

    internal fun checkLocalVendorModelStatus(context: Context, vendor: AsrVendor): LocalModelCheck<*> = localVendorModelStatusOrMissing(
        vendor = vendor,
        modelStatus = { AsrLocalVendorLifecycles.modelStatus(context, prefs, vendor) },
        logFailure = { failedVendor, throwable ->
            Log.w(TAG, "Failed to check local ASR model for $failedVendor", throwable)
        }
    )

    fun checkLocalVendorModelDownloaded(context: Context, vendor: AsrVendor): Boolean = checkLocalVendorModelStatus(context, vendor) is LocalModelCheck.Ready

    internal fun checkXAsrModelStatus(context: Context): LocalModelCheck<*> = checkLocalVendorModelStatus(context, AsrVendor.XAsr)

    fun checkXAsrModelDownloaded(context: Context): Boolean = checkXAsrModelStatus(context) is LocalModelCheck.Ready

    internal fun checkSvModelStatus(context: Context): LocalModelCheck<*> = checkLocalVendorModelStatus(context, AsrVendor.SenseVoice)

    fun checkSvModelDownloaded(context: Context): Boolean = checkSvModelStatus(context) is LocalModelCheck.Ready

    internal fun checkFnModelStatus(context: Context): LocalModelCheck<*> = checkLocalVendorModelStatus(context, AsrVendor.FunAsrNano)

    fun checkFnModelDownloaded(context: Context): Boolean = checkFnModelStatus(context) is LocalModelCheck.Ready

    internal fun checkQwModelStatus(context: Context): LocalModelCheck<*> = checkLocalVendorModelStatus(context, AsrVendor.Qwen3Asr)

    fun checkQwModelDownloaded(context: Context): Boolean = checkQwModelStatus(context) is LocalModelCheck.Ready

    internal fun checkPkModelStatus(context: Context): LocalModelCheck<*> = checkLocalVendorModelStatus(context, AsrVendor.Parakeet)

    fun checkPkModelDownloaded(context: Context): Boolean = checkPkModelStatus(context) is LocalModelCheck.Ready

    internal fun checkFrModelStatus(context: Context): LocalModelCheck<*> = checkLocalVendorModelStatus(context, AsrVendor.FireRedAsr)

    fun checkFrModelDownloaded(context: Context): Boolean = checkFrModelStatus(context) is LocalModelCheck.Ready

    internal fun checkPunctuationModelStatus(context: Context): LocalModelCheck<Unit> = try {
        val dir = SherpaPunctuationManager.findPunctuationModelDir(context) ?: return LocalModelCheck.Missing
        requireModelFilesCached(
            context,
            File(dir, "model.int8.onnx") to LocalModelSpecs.Punctuation.model,
            File(dir, "tokens.json") to LocalModelSpecs.Punctuation.tokens
        )
    } catch (t: Throwable) {
        Log.w(TAG, "Failed to check punctuation model installed", t)
        LocalModelCheck.Missing
    }

    fun isPunctuationModelInstalled(context: Context): Boolean = checkPunctuationModelStatus(context) is LocalModelCheck.Ready

    private fun findModelDir(root: File): File? {
        if (!root.exists()) return null
        val direct = File(root, "tokens.txt")
        if (direct.exists()) return root
        val subs = root.listFiles() ?: return null
        subs.forEach { f ->
            if (f.isDirectory) {
                val t = File(f, "tokens.txt")
                if (t.exists()) return f
            }
        }
        return null
    }

    companion object {
        private const val TAG = "AsrSettingsViewModel"
    }
}

internal fun localVendorModelStatusOrMissing(
    vendor: AsrVendor,
    modelStatus: () -> LocalModelCheck<*>?,
    logFailure: (AsrVendor, Throwable) -> Unit = { _, _ -> }
): LocalModelCheck<*> = try {
    modelStatus() ?: LocalModelCheck.Missing
} catch (t: Throwable) {
    logFailure(vendor, t)
    LocalModelCheck.Missing
}

/**
 * UI State for ASR Settings screen.
 * Contains all configuration values and visibility flags.
 */
data class AsrSettingsUiState(
    val selectedVendor: AsrVendor = AsrVendor.Volc,
    val recordingAutoStopMode: Prefs.RecordingAutoStopMode = Prefs.RecordingAutoStopMode.MANUAL,
    val autoStopSilenceEnabled: Boolean = false,
    val silenceWindowMs: Int = 1200,
    val silenceSensitivity: Int = Prefs.DEFAULT_SILENCE_SENSITIVITY,
    val recordingMaxDurationMs: Int = Prefs.DEFAULT_RECORDING_MAX_DURATION_MS,
    val aiEditPreferLastAsr: Boolean = false,
    // Volcengine settings
    val volcAsrModel: String = VolcAsrModelCatalog.DEFAULT_ID,
    val volcDdcEnabled: Boolean = false,
    val volcVadEnabled: Boolean = false,
    val volcNonstreamEnabled: Boolean = false,
    val volcUseNewAuth: Boolean = false,
    val volcLanguage: String = "",
    // SiliconFlow settings
    val sfUseOmni: Boolean = false,
    // ElevenLabs settings
    val elevenStreamingEnabled: Boolean = false,
    // OpenAI settings
    val oaAsrStreamingEnabled: Boolean = false,
    val oaAsrUseCompletions: Boolean = false,
    val oaAsrUsePrompt: Boolean = false,
    // Soniox settings
    val sonioxStreamingEnabled: Boolean = false,
    val sonioxLanguages: List<String> = emptyList(),
    // SenseVoice settings
    val svModelVariant: String = "small-int8",
    val svNumThreads: Int = 2,
    val svLanguage: String = "auto",
    val svUseItn: Boolean = true,
    val svPreloadEnabled: Boolean = false,
    val svKeepAliveMinutes: Int = -1,
    val svPseudoStreamEnabled: Boolean = false,
    // FunASR Nano settings
    val fnModelVariant: String = "nano-int8",
    val fnNumThreads: Int = 2,
    val fnUseItn: Boolean = true,
    val fnUserPrompt: String = "语音转写：",
    val fnLanguage: String = "",
    val fnPreloadEnabled: Boolean = false,
    val fnKeepAliveMinutes: Int = -1,
    // Qwen3-ASR settings
    val qwModelVariant: String = "qwen3-0.6b-int8",
    val qwNumThreads: Int = 3,
    val qwPreloadEnabled: Boolean = false,
    val qwKeepAliveMinutes: Int = -1,
    val qwUseItn: Boolean = true,
    // Parakeet settings
    val pkModelVariant: String = "0.6b-v3-int8",
    val pkNumThreads: Int = 3,
    val pkPreloadEnabled: Boolean = false,
    val pkKeepAliveMinutes: Int = -1,
    // FireRedASR settings
    val frModelVariant: String = "ctc-int8",
    val frNumThreads: Int = 2,
    val frKeepAliveMinutes: Int = -1,
    val frPreloadEnabled: Boolean = false,
    val frUseItn: Boolean = true,
    val frPseudoStreamEnabled: Boolean = false,
    // X-ASR settings
    val xAsrModelVariant: String = "x-asr-480ms",
    val xAsrNumThreads: Int = 2,
    val xAsrKeepAliveMinutes: Int = -1,
    val xAsrPreloadEnabled: Boolean = true,
    val xAsrUseItn: Boolean = false,
    // Tencent settings
    val tencentVadEnabled: Boolean = false
) {
    val isVolcStreaming: Boolean
        get() = VolcAsrModelCatalog.isStreaming(volcAsrModel)

    // Computed visibility properties based on selected vendor
    val isVolcVisible: Boolean get() = selectedVendor == AsrVendor.Volc
    val isSfVisible: Boolean get() = selectedVendor == AsrVendor.SiliconFlow
    val isElevenVisible: Boolean get() = selectedVendor == AsrVendor.ElevenLabs
    val isOpenAiVisible: Boolean get() = selectedVendor == AsrVendor.OpenAI
    val isOpenRouterVisible: Boolean get() = selectedVendor == AsrVendor.OpenRouter
    val isDashVisible: Boolean get() = selectedVendor == AsrVendor.DashScope
    val isGeminiVisible: Boolean get() = selectedVendor == AsrVendor.Gemini
    val isSonioxVisible: Boolean get() = selectedVendor == AsrVendor.Soniox
    val isStepAudioVisible: Boolean get() = selectedVendor == AsrVendor.StepAudio
    val isSenseVoiceVisible: Boolean get() = selectedVendor == AsrVendor.SenseVoice
    val isFunAsrNanoVisible: Boolean get() = selectedVendor == AsrVendor.FunAsrNano
    val isQwen3AsrVisible: Boolean get() = selectedVendor == AsrVendor.Qwen3Asr
    val isParakeetVisible: Boolean get() = selectedVendor == AsrVendor.Parakeet
    val isFireRedAsrVisible: Boolean get() = selectedVendor == AsrVendor.FireRedAsr
    val isXAsrVisible: Boolean get() = selectedVendor == AsrVendor.XAsr
}
