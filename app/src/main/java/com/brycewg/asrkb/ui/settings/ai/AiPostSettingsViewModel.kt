package com.brycewg.asrkb.ui.settings.ai

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brycewg.asrkb.asr.LlmReasoningThreshold
import com.brycewg.asrkb.asr.LlmVendor
import com.brycewg.asrkb.asr.ReasoningMode
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.PromptPreset
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for AI Post-Processing Settings
 * Manages LLM vendors, providers and prompt presets with reactive state updates
 */
class AiPostSettingsViewModel : ViewModel() {
    companion object {
        private const val TAG = "AiPostSettingsVM"
    }

    // State for LLM vendor selection
    private val _selectedVendor = MutableStateFlow(LlmVendor.SF_FREE)
    val selectedVendor: StateFlow<LlmVendor> = _selectedVendor.asStateFlow()

    // State for builtin vendor configuration
    data class BuiltinVendorConfig(
        val apiKey: String = "",
        val model: String = "",
        val temperature: Float = Prefs.DEFAULT_LLM_TEMPERATURE,
        val reasoningCharThreshold: Int = LlmReasoningThreshold.NEVER,
        val customReasoningParamsEnabled: Boolean = false
    )

    private val _builtinVendorConfig = MutableStateFlow(BuiltinVendorConfig())
    val builtinVendorConfig: StateFlow<BuiltinVendorConfig> = _builtinVendorConfig.asStateFlow()

    // State for LLM providers (custom profiles)
    private val _llmProfiles = MutableStateFlow<List<Prefs.LlmProvider>>(emptyList())
    val llmProfiles: StateFlow<List<Prefs.LlmProvider>> = _llmProfiles.asStateFlow()

    private val _activeLlmProvider = MutableStateFlow<Prefs.LlmProvider?>(null)
    val activeLlmProvider: StateFlow<Prefs.LlmProvider?> = _activeLlmProvider.asStateFlow()

    // State for prompt presets
    private val _promptPresets = MutableStateFlow<List<PromptPreset>>(emptyList())
    val promptPresets: StateFlow<List<PromptPreset>> = _promptPresets.asStateFlow()

    private val _activePromptPreset = MutableStateFlow<PromptPreset?>(null)
    val activePromptPreset: StateFlow<PromptPreset?> = _activePromptPreset.asStateFlow()
    private var pendingLlmProviders: List<Prefs.LlmProvider>? = null
    private var pendingPromptPresets: List<PromptPreset>? = null
    private var llmProvidersPersistJob: Job? = null
    private var promptPresetsPersistJob: Job? = null
    private var latestPrefs: Prefs? = null

    // ======== Initialization ========

    /**
     * Loads all data from preferences and updates state flows
     */
    fun loadData(prefs: Prefs) {
        rememberPrefs(prefs)
        loadLlmVendor(prefs)
        loadLlmProviders(prefs)
        loadPromptPresets(prefs)
    }

    // ======== LLM Vendor Management ========

    /**
     * Loads LLM vendor selection from preferences
     */
    private fun loadLlmVendor(prefs: Prefs) {
        try {
            val vendor = prefs.llmVendor
            _selectedVendor.value = vendor
            loadBuiltinVendorConfig(prefs, vendor)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load LLM vendor", e)
        }
    }

    /**
     * Loads builtin vendor configuration for the specified vendor
     */
    private fun loadBuiltinVendorConfig(prefs: Prefs, vendor: LlmVendor) {
        if (vendor == LlmVendor.CUSTOM || vendor == LlmVendor.SF_FREE) {
            // Custom uses LlmProvider, SF_FREE uses sfFreeLlmModel
            _builtinVendorConfig.value = BuiltinVendorConfig()
            return
        }
        _builtinVendorConfig.value = BuiltinVendorConfig(
            apiKey = prefs.getLlmVendorApiKey(vendor),
            model = prefs.getLlmVendorModel(vendor),
            temperature = prefs.getLlmVendorTemperature(vendor),
            reasoningCharThreshold = prefs.getLlmVendorReasoningCharThreshold(vendor),
            customReasoningParamsEnabled = prefs.getLlmVendorCustomReasoningParamsEnabled(vendor)
        )
    }

    /**
     * Selects a LLM vendor
     */
    fun selectVendor(prefs: Prefs, vendor: LlmVendor) {
        rememberPrefs(prefs)
        try {
            if (!vendor.classifierOnly) {
                prefs.llmVendor = vendor
            }
            _selectedVendor.value = vendor
            loadBuiltinVendorConfig(prefs, vendor)
            Log.d(TAG, "Selected LLM vendor: ${vendor.id}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to select LLM vendor", e)
        }
    }

    /**
     * Updates builtin vendor API key
     */
    fun updateBuiltinApiKey(prefs: Prefs, apiKey: String) {
        rememberPrefs(prefs)
        val vendor = _selectedVendor.value
        if (vendor == LlmVendor.CUSTOM || vendor == LlmVendor.SF_FREE) return
        try {
            prefs.setLlmVendorApiKey(vendor, apiKey)
            _builtinVendorConfig.value = _builtinVendorConfig.value.copy(apiKey = apiKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update builtin API key", e)
        }
    }

    /**
     * Updates builtin vendor model
     */
    fun updateBuiltinModel(prefs: Prefs, model: String) {
        rememberPrefs(prefs)
        val vendor = _selectedVendor.value
        if (vendor == LlmVendor.CUSTOM || vendor == LlmVendor.SF_FREE) return
        try {
            prefs.setLlmVendorModel(vendor, model)
            _builtinVendorConfig.value = _builtinVendorConfig.value.copy(model = model)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update builtin model", e)
        }
    }

    /**
     * Updates builtin vendor temperature
     */
    fun updateBuiltinTemperature(prefs: Prefs, temperature: Float) {
        rememberPrefs(prefs)
        val vendor = _selectedVendor.value
        if (vendor == LlmVendor.CUSTOM || vendor == LlmVendor.SF_FREE) return
        try {
            val coerced = temperature.coerceIn(0f, 2f)
            prefs.setLlmVendorTemperature(vendor, coerced)
            _builtinVendorConfig.value = _builtinVendorConfig.value.copy(temperature = coerced)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update builtin temperature", e)
        }
    }

    /**
     * Updates builtin vendor reasoning character threshold
     */
    fun updateBuiltinReasoningCharThreshold(prefs: Prefs, threshold: Int) {
        rememberPrefs(prefs)
        val vendor = _selectedVendor.value
        if (vendor == LlmVendor.CUSTOM || vendor == LlmVendor.SF_FREE) return
        try {
            val coerced = LlmReasoningThreshold.coerce(threshold)
            prefs.setLlmVendorReasoningCharThreshold(vendor, coerced)
            _builtinVendorConfig.value = _builtinVendorConfig.value.copy(reasoningCharThreshold = coerced)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update builtin reasoning threshold", e)
        }
    }

    /**
     * Updates builtin vendor custom reasoning params enabled state
     */
    fun updateBuiltinCustomReasoningParamsEnabled(prefs: Prefs, enabled: Boolean) {
        rememberPrefs(prefs)
        val vendor = _selectedVendor.value
        if (vendor == LlmVendor.CUSTOM || vendor == LlmVendor.SF_FREE) return
        try {
            prefs.setLlmVendorCustomReasoningParamsEnabled(vendor, enabled)
            _builtinVendorConfig.value =
                _builtinVendorConfig.value.copy(customReasoningParamsEnabled = enabled)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update builtin custom reasoning params enabled", e)
        }
    }

    /**
     * Checks if the current vendor/model supports reasoning mode switch
     * Returns true only for vendors with switch-controlled reasoning (not MODEL_SELECTION)
     */
    fun supportsReasoningSwitch(vendor: LlmVendor, model: String): Boolean = when (vendor.reasoningMode) {
        ReasoningMode.ENABLE_THINKING,
        ReasoningMode.REASONING_EFFORT,
        ReasoningMode.THINKING_TYPE -> {
            // Only show switch if the model supports reasoning
            vendor.reasoningModels.isEmpty() || vendor.reasoningModels.contains(model)
        }
        ReasoningMode.MODEL_SELECTION,
        ReasoningMode.NONE -> false
    }

    // ======== LLM Provider Management (Custom) ========

    /**
     * Loads LLM providers from preferences
     */
    private fun loadLlmProviders(prefs: Prefs) {
        try {
            val profiles = prefs.getLlmProviders()
            _llmProfiles.value = profiles
            _activeLlmProvider.value = prefs.getActiveLlmProvider()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load LLM providers", e)
        }
    }

    /**
     * Selects a specific LLM provider by ID
     */
    fun selectLlmProvider(prefs: Prefs, profileId: String) {
        rememberPrefs(prefs)
        try {
            flushPendingLlmProviders(prefs)
            val profiles = _llmProfiles.value
            val selected = profiles.firstOrNull { it.id == profileId }
            if (selected != null) {
                prefs.activeLlmId = profileId
                _activeLlmProvider.value = selected
                Log.d(TAG, "Selected LLM provider: ${selected.name}")
            } else {
                Log.w(TAG, "LLM provider not found: $profileId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to select LLM provider", e)
        }
    }

    /**
     * Updates the active LLM provider with a mutation function
     */
    fun updateActiveLlmProvider(prefs: Prefs, mutator: (Prefs.LlmProvider) -> Prefs.LlmProvider) {
        rememberPrefs(prefs)
        try {
            val list = _llmProfiles.value.toMutableList()
            val activeId = prefs.activeLlmId
            val idx = list.indexOfFirst { it.id == activeId }

            if (idx >= 0) {
                list[idx] = mutator(list[idx])
            } else if (list.isNotEmpty()) {
                // Fallback to first profile if active ID is invalid
                list[0] = mutator(list[0])
                prefs.activeLlmId = list[0].id
            } else {
                // Create new profile if none exists
                val created = createDefaultLlmProvider(mutator)
                list.add(created)
                prefs.activeLlmId = created.id
            }

            scheduleLlmProvidersPersist(prefs, list)
            _llmProfiles.value = list
            _activeLlmProvider.value = list.firstOrNull { it.id == prefs.activeLlmId }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update active LLM provider", e)
        }
    }

    /**
     * Creates a new default LLM provider with optional mutation
     */
    private fun createDefaultLlmProvider(
        mutator: ((Prefs.LlmProvider) -> Prefs.LlmProvider)? = null
    ): Prefs.LlmProvider {
        val defaultProvider = Prefs.LlmProvider(
            id = UUID.randomUUID().toString(),
            name = "",
            endpoint = Prefs.DEFAULT_LLM_ENDPOINT,
            apiKey = "",
            model = Prefs.DEFAULT_LLM_MODEL,
            temperature = Prefs.DEFAULT_LLM_TEMPERATURE,
            models = emptyList()
        )
        return mutator?.invoke(defaultProvider) ?: defaultProvider
    }

    /**
     * Adds a new LLM provider
     */
    fun addLlmProvider(prefs: Prefs, defaultProfileName: String): Boolean = try {
        rememberPrefs(prefs)
        flushPendingLlmProviders(prefs)
        val id = UUID.randomUUID().toString()
        val newProvider = Prefs.LlmProvider(
            id = id,
            name = defaultProfileName,
            endpoint = Prefs.DEFAULT_LLM_ENDPOINT,
            apiKey = "",
            model = Prefs.DEFAULT_LLM_MODEL,
            temperature = Prefs.DEFAULT_LLM_TEMPERATURE,
            models = emptyList()
        )

        val list = _llmProfiles.value.toMutableList()
        list.add(newProvider)
        prefs.setLlmProviders(list)
        prefs.activeLlmId = id

        _llmProfiles.value = list
        _activeLlmProvider.value = newProvider
        Log.d(TAG, "Added new LLM provider: $defaultProfileName")
        true
    } catch (e: Exception) {
        Log.e(TAG, "Failed to add LLM provider", e)
        false
    }

    /**
     * Deletes the currently active LLM provider
     */
    fun deleteActiveLlmProvider(prefs: Prefs): Boolean {
        rememberPrefs(prefs)
        flushPendingLlmProviders(prefs)
        return try {
            val list = _llmProfiles.value.toMutableList()
            if (list.isEmpty()) {
                Log.w(TAG, "Cannot delete: no LLM providers exist")
                return false
            }

            val activeId = prefs.activeLlmId
            val idx = list.indexOfFirst { it.id == activeId }
            if (idx >= 0) {
                list.removeAt(idx)
                prefs.setLlmProviders(list)

                val newActive = list.firstOrNull()
                prefs.activeLlmId = newActive?.id ?: ""

                _llmProfiles.value = list
                _activeLlmProvider.value = newActive
                Log.d(TAG, "Deleted LLM provider at index $idx")
                true
            } else {
                Log.w(TAG, "Active LLM provider not found for deletion")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete LLM provider", e)
            false
        }
    }

    // ======== Prompt Preset Management ========

    /**
     * Loads prompt presets from preferences
     */
    private fun loadPromptPresets(prefs: Prefs) {
        try {
            val presets = prefs.getPromptPresets()
            _promptPresets.value = presets
            val activeId = prefs.activePromptId
            _activePromptPreset.value = presets.firstOrNull { it.id == activeId }
                ?: presets.firstOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load prompt presets", e)
        }
    }

    /**
     * Selects a specific prompt preset by ID
     */
    fun selectPromptPreset(prefs: Prefs, presetId: String) {
        rememberPrefs(prefs)
        try {
            flushPendingPromptPresets(prefs)
            val presets = _promptPresets.value
            val selected = presets.firstOrNull { it.id == presetId }
            if (selected != null) {
                prefs.activePromptId = presetId
                _activePromptPreset.value = selected
                Log.d(TAG, "Selected prompt preset: ${selected.title}")
            } else {
                Log.w(TAG, "Prompt preset not found: $presetId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to select prompt preset", e)
        }
    }

    /**
     * Updates the active prompt preset with a mutation function
     */
    fun updateActivePromptPreset(prefs: Prefs, mutator: (PromptPreset) -> PromptPreset) {
        rememberPrefs(prefs)
        try {
            val list = _promptPresets.value.toMutableList()
            val activeId = prefs.activePromptId
            val idx = list.indexOfFirst { it.id == activeId }

            if (idx >= 0) {
                list[idx] = mutator(list[idx])
                schedulePromptPresetsPersist(prefs, list)
                _promptPresets.value = list
                _activePromptPreset.value = list[idx]
            } else {
                Log.w(TAG, "Active prompt preset not found for update")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update active prompt preset", e)
        }
    }

    /**
     * Adds a new prompt preset
     */
    fun addPromptPreset(prefs: Prefs, defaultTitle: String, defaultContent: String): Boolean = try {
        rememberPrefs(prefs)
        flushPendingPromptPresets(prefs)
        val newPreset = PromptPreset(
            id = UUID.randomUUID().toString(),
            title = defaultTitle,
            content = defaultContent
        )

        val list = _promptPresets.value.toMutableList()
        list.add(newPreset)
        prefs.setPromptPresets(list)
        prefs.activePromptId = newPreset.id

        _promptPresets.value = list
        _activePromptPreset.value = newPreset
        Log.d(TAG, "Added new prompt preset: $defaultTitle")
        true
    } catch (e: Exception) {
        Log.e(TAG, "Failed to add prompt preset", e)
        false
    }

    /**
     * Deletes the currently active prompt preset
     */
    fun deleteActivePromptPreset(prefs: Prefs): Boolean {
        rememberPrefs(prefs)
        flushPendingPromptPresets(prefs)
        return try {
            val list = _promptPresets.value.toMutableList()
            if (list.isEmpty()) {
                Log.w(TAG, "Cannot delete: no prompt presets exist")
                return false
            }

            val activeId = prefs.activePromptId
            val idx = list.indexOfFirst { it.id == activeId }
            if (idx >= 0) {
                list.removeAt(idx)
                prefs.setPromptPresets(list)

                val newActive = list.firstOrNull()
                prefs.activePromptId = newActive?.id ?: ""

                _promptPresets.value = list
                _activePromptPreset.value = newActive
                Log.d(TAG, "Deleted prompt preset at index $idx")
                true
            } else {
                Log.w(TAG, "Active prompt preset not found for deletion")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete prompt preset", e)
            false
        }
    }

    // ======== Helper Methods ========

    /**
     * Gets the index of the active LLM provider
     */
    fun getActiveLlmProviderIndex(): Int {
        val activeId = _activeLlmProvider.value?.id ?: return 0
        return _llmProfiles.value.indexOfFirst { it.id == activeId }.let { if (it < 0) 0 else it }
    }

    /**
     * Gets the index of the active prompt preset
     */
    fun getActivePromptPresetIndex(): Int {
        val activeId = _activePromptPreset.value?.id ?: return 0
        return _promptPresets.value.indexOfFirst { it.id == activeId }.let { if (it < 0) 0 else it }
    }

    fun flushPendingWrites(prefs: Prefs) {
        rememberPrefs(prefs)
        flushPendingLlmProviders(prefs)
        flushPendingPromptPresets(prefs)
    }

    private fun rememberPrefs(prefs: Prefs) {
        latestPrefs = prefs
    }

    private fun scheduleLlmProvidersPersist(prefs: Prefs, list: List<Prefs.LlmProvider>) {
        pendingLlmProviders = list
        llmProvidersPersistJob?.cancel()
        llmProvidersPersistJob = viewModelScope.launch {
            delay(350L)
            flushPendingLlmProviders(prefs)
        }
    }

    private fun flushPendingLlmProviders(prefs: Prefs) {
        llmProvidersPersistJob?.cancel()
        llmProvidersPersistJob = null
        pendingLlmProviders?.let { pending ->
            prefs.setLlmProviders(pending)
            pendingLlmProviders = null
        }
    }

    private fun schedulePromptPresetsPersist(prefs: Prefs, list: List<PromptPreset>) {
        pendingPromptPresets = list
        promptPresetsPersistJob?.cancel()
        promptPresetsPersistJob = viewModelScope.launch {
            delay(350L)
            flushPendingPromptPresets(prefs)
        }
    }

    private fun flushPendingPromptPresets(prefs: Prefs) {
        promptPresetsPersistJob?.cancel()
        promptPresetsPersistJob = null
        pendingPromptPresets?.let { pending ->
            prefs.setPromptPresets(pending)
            pendingPromptPresets = null
        }
    }

    override fun onCleared() {
        latestPrefs?.let { flushPendingWrites(it) }
        super.onCleared()
    }
}
