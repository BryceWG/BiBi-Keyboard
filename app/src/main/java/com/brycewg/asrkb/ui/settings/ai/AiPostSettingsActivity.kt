package com.brycewg.asrkb.ui.settings.ai

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.brycewg.asrkb.ui.BaseActivity
import androidx.lifecycle.lifecycleScope
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.LlmPostProcessor
import com.brycewg.asrkb.asr.LlmVendor
import com.brycewg.asrkb.ime.AsrKeyboardService
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.PromptPreset
import com.brycewg.asrkb.ui.SettingsOptionSheet
import com.brycewg.asrkb.ui.installExplainedSwitch
import com.brycewg.asrkb.util.HapticFeedbackHelper
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Activity for configuring AI post-processing settings
 * Manages LLM vendors, providers and prompt presets with reactive UI updates
 */
class AiPostSettingsActivity : BaseActivity() {

    private lateinit var prefs: Prefs
    private lateinit var viewModel: AiPostSettingsViewModel

    // LLM Vendor Selection Views
    private lateinit var tvLlmVendor: TextView

    // SiliconFlow LLM Views
    private lateinit var groupSfFreeLlm: View
    private lateinit var switchSfUseFreeService: MaterialSwitch
    private lateinit var tvSfFreeServiceDesc: TextView
    private lateinit var tilSfApiKey: View
    private lateinit var etSfApiKey: EditText
    private lateinit var tvSfFreeLlmModel: TextView
    private lateinit var btnSfFreeLlmFetchModels: Button
    private lateinit var tilSfCustomModelId: View
    private lateinit var etSfCustomModelId: EditText
    private lateinit var layoutSfReasoningMode: View
    private lateinit var switchSfReasoningMode: MaterialSwitch
    private lateinit var tvSfReasoningModeHint: TextView
    private lateinit var layoutSfReasoningParams: View
    private lateinit var etSfReasoningParamsOnJson: EditText
    private lateinit var etSfReasoningParamsOffJson: EditText
    private lateinit var layoutSfTemperature: View
    private lateinit var sliderSfTemperature: Slider
    private lateinit var tvSfTemperatureValue: TextView

    // Builtin LLM Views
    private lateinit var groupBuiltinLlm: View
    private lateinit var etBuiltinApiKey: EditText
    private lateinit var tvBuiltinModel: TextView
    private lateinit var btnBuiltinFetchModels: Button
    private lateinit var tilBuiltinCustomModelId: View
    private lateinit var etBuiltinCustomModelId: EditText
    private lateinit var layoutBuiltinReasoningMode: View
    private lateinit var switchBuiltinReasoningMode: MaterialSwitch
    private lateinit var tvBuiltinReasoningModeHint: TextView
    private lateinit var layoutBuiltinReasoningParams: View
    private lateinit var etBuiltinReasoningParamsOnJson: EditText
    private lateinit var etBuiltinReasoningParamsOffJson: EditText
    private lateinit var sliderBuiltinTemperature: Slider
    private lateinit var tvBuiltinTemperatureValue: TextView
    private lateinit var btnBuiltinRegister: Button
    private lateinit var btnBuiltinTestCall: Button

    // Custom LLM Profile Views
    private lateinit var groupCustomLlm: View
    private lateinit var tvLlmProfiles: TextView
    private lateinit var etLlmProfileName: EditText
    private lateinit var etLlmEndpoint: EditText
    private lateinit var etLlmApiKey: EditText
    private lateinit var tvCustomLlmModel: TextView
    private lateinit var btnCustomLlmFetchModels: Button
    private lateinit var tilCustomModelId: View
    private lateinit var etCustomModelId: EditText
    private lateinit var layoutCustomReasoningMode: View
    private lateinit var switchCustomReasoningMode: MaterialSwitch
    private lateinit var tvCustomReasoningModeHint: TextView
    private lateinit var etCustomReasoningParamsOnJson: EditText
    private lateinit var etCustomReasoningParamsOffJson: EditText
    private lateinit var sliderLlmTemperature: Slider
    private lateinit var tvLlmTemperatureValue: TextView
    private lateinit var btnLlmAddProfile: Button
    private lateinit var btnLlmDeleteProfile: Button
    private lateinit var btnLlmTestCall: Button

    // Prompt Preset Views
    private lateinit var tvPromptPresets: TextView
    private lateinit var etLlmPromptTitle: EditText
    private lateinit var etLlmPrompt: EditText
    private lateinit var btnAddPromptPreset: Button
    private lateinit var btnDeletePromptPreset: Button
    private lateinit var switchPostProcessEnabled: MaterialSwitch
    private lateinit var switchAiEditPreferLastAsr: MaterialSwitch
    private lateinit var sliderSkipAiUnderChars: Slider
    private lateinit var tvSkipAiUnderCharsValue: TextView

    // Flag to prevent recursive updates during programmatic text changes
    private var isUpdatingProgrammatically = false
    private var isCustomModelInputVisible = false
    private var lastCustomProfileId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_post_settings)

        // 应用 Window Insets 以适配 Android 15 边缘到边缘显示
        findViewById<android.view.View>(android.R.id.content).let { rootView ->
            com.brycewg.asrkb.ui.WindowInsetsHelper.applySystemBarsInsets(rootView)
        }

        prefs = Prefs(this)
        viewModel = ViewModelProvider(this)[AiPostSettingsViewModel::class.java]

        initViews()
        setupVendorSection()
        setupLlmProfileSection()
        setupPromptPresetSection()
        observeViewModelState()
        loadInitialData()
    }

    // ======== Initialization Methods ========

    private fun initViews() {
        // Toolbar
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setTitle(R.string.title_ai_settings)
            setNavigationOnClickListener { finish() }
        }

        // 启用 AI 后处理
        switchPostProcessEnabled = findViewById(R.id.switchPostProcessEnabled)
        switchPostProcessEnabled.isChecked = prefs.postProcessEnabled
        switchPostProcessEnabled.installExplainedSwitch(
            context = this,
            titleRes = R.string.label_ai_post_process_enabled,
            offDescRes = R.string.feature_ai_post_process_off_desc,
            onDescRes = R.string.feature_ai_post_process_on_desc,
            preferenceKey = "ai_post_process_enabled_explained",
            readPref = { prefs.postProcessEnabled },
            writePref = { v -> prefs.postProcessEnabled = v },
            onChanged = { sendRefreshBroadcast() },
            hapticFeedback = { hapticTapIfEnabled(it) }
        )

        // AI 编辑默认范围开关：使用上次识别结果
        switchAiEditPreferLastAsr = findViewById(R.id.switchAiEditPreferLastAsr)
        switchAiEditPreferLastAsr.isChecked = prefs.aiEditDefaultToLastAsr
        switchAiEditPreferLastAsr.installExplainedSwitch(
            context = this,
            titleRes = R.string.label_ai_edit_default_use_last_asr,
            offDescRes = R.string.feature_ai_edit_default_use_last_asr_off_desc,
            onDescRes = R.string.feature_ai_edit_default_use_last_asr_on_desc,
            preferenceKey = "ai_edit_default_use_last_asr_explained",
            readPref = { prefs.aiEditDefaultToLastAsr },
            writePref = { v -> prefs.aiEditDefaultToLastAsr = v },
            hapticFeedback = { hapticTapIfEnabled(it) }
        )

        // 少于特定字数跳过 AI 后处理
        sliderSkipAiUnderChars = findViewById(R.id.sliderSkipAiUnderChars)
        tvSkipAiUnderCharsValue = findViewById(R.id.tvSkipAiUnderCharsValue)
        sliderSkipAiUnderChars.value = prefs.postprocSkipUnderChars.coerceIn(0, 100).toFloat()
        tvSkipAiUnderCharsValue.text = prefs.postprocSkipUnderChars.toString()
        sliderSkipAiUnderChars.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val intValue = value.toInt()
                prefs.postprocSkipUnderChars = intValue
                tvSkipAiUnderCharsValue.text = intValue.toString()
            }
        }
        sliderSkipAiUnderChars.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = hapticTapIfEnabled(slider)
            override fun onStopTrackingTouch(slider: Slider) = hapticTapIfEnabled(slider)
        })

        // LLM Vendor Selection
        tvLlmVendor = findViewById(R.id.tvLlmVendor)
        tvLlmVendor.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            showVendorSelectionDialog()
        }

        // SiliconFlow LLM Group
        groupSfFreeLlm = findViewById(R.id.groupSfFreeLlm)
        switchSfUseFreeService = findViewById(R.id.switchSfUseFreeService)
        tvSfFreeServiceDesc = findViewById(R.id.tvSfFreeServiceDesc)
        tilSfApiKey = findViewById(R.id.tilSfApiKey)
        etSfApiKey = findViewById(R.id.etSfApiKey)
        tvSfFreeLlmModel = findViewById(R.id.tvSfFreeLlmModel)
        btnSfFreeLlmFetchModels = findViewById(R.id.btnSfFreeLlmFetchModels)
        tilSfCustomModelId = findViewById(R.id.tilSfCustomModelId)
        etSfCustomModelId = findViewById(R.id.etSfCustomModelId)
        layoutSfReasoningMode = findViewById(R.id.layoutSfReasoningMode)
        switchSfReasoningMode = findViewById(R.id.switchSfReasoningMode)
        tvSfReasoningModeHint = findViewById(R.id.tvSfReasoningModeHint)
        layoutSfReasoningParams = findViewById(R.id.layoutSfReasoningParams)
        etSfReasoningParamsOnJson = findViewById(R.id.etSfReasoningParamsOnJson)
        etSfReasoningParamsOffJson = findViewById(R.id.etSfReasoningParamsOffJson)
        layoutSfTemperature = findViewById(R.id.layoutSfTemperature)
        sliderSfTemperature = findViewById(R.id.sliderSfTemperature)
        tvSfTemperatureValue = findViewById(R.id.tvSfTemperatureValue)

        // Initialize SF free/paid toggle
        switchSfUseFreeService.isChecked = !prefs.sfFreeLlmUsePaidKey
        updateSfFreePaidUI(!prefs.sfFreeLlmUsePaidKey)
        switchSfUseFreeService.setOnCheckedChangeListener { _, isChecked ->
            prefs.sfFreeLlmUsePaidKey = !isChecked
            updateSfFreePaidUI(isChecked)
        }

        // SF API key listener
        etSfApiKey.addTextChangeListener { text ->
            prefs.setLlmVendorApiKey(LlmVendor.SF_FREE, text)
        }
        // Load SF API key if exists
        etSfApiKey.setText(prefs.getLlmVendorApiKey(LlmVendor.SF_FREE))

        // SF model selection
        tvSfFreeLlmModel.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            showSfFreeLlmModelSelectionDialog()
        }
        btnSfFreeLlmFetchModels.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            handleFetchSfModels()
        }

        // SF custom model ID listener
        etSfCustomModelId.addTextChangeListener { text ->
            if (text.isNotBlank()) {
                if (prefs.sfFreeLlmUsePaidKey) {
                    prefs.setLlmVendorModel(LlmVendor.SF_FREE, text)
                } else {
                    prefs.sfFreeLlmModel = text
                }
                updateSfReasoningModeUI()
            }
        }

        // SF temperature slider listener
        sliderSfTemperature.addOnChangeListener { _, value, fromUser ->
            if (fromUser && prefs.sfFreeLlmUsePaidKey) {
                val coerced = value.coerceIn(0f, 2f)
                prefs.setLlmVendorTemperature(LlmVendor.SF_FREE, coerced)
                tvSfTemperatureValue.text = String.format("%.1f", coerced)
            }
        }
        sliderSfTemperature.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = hapticTapIfEnabled(slider)
            override fun onStopTrackingTouch(slider: Slider) = hapticTapIfEnabled(slider)
        })

        // 根据深色模式设置 Powered by 图片
        val imgSfFreeLlmPoweredBy = findViewById<ImageView>(R.id.imgSfFreeLlmPoweredBy)
        val isDarkMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        imgSfFreeLlmPoweredBy.setImageResource(
            if (isDarkMode) R.drawable.powered_by_siliconflow_dark else R.drawable.powered_by_siliconflow_light
        )

        // SF register button
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSfFreeLlmRegister).setOnClickListener { v ->
            hapticTapIfEnabled(v)
            openUrlSafely(LlmVendor.SF_FREE.registerUrl)
        }

        // SF test call button
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSfFreeLlmTestCall).setOnClickListener { v ->
            hapticTapIfEnabled(v)
            handleTestLlmCall()
        }

        // Builtin LLM Group
        groupBuiltinLlm = findViewById(R.id.groupBuiltinLlm)
        etBuiltinApiKey = findViewById(R.id.etBuiltinApiKey)
        tvBuiltinModel = findViewById(R.id.tvBuiltinModel)
        btnBuiltinFetchModels = findViewById(R.id.btnBuiltinFetchModels)
        tilBuiltinCustomModelId = findViewById(R.id.tilBuiltinCustomModelId)
        etBuiltinCustomModelId = findViewById(R.id.etBuiltinCustomModelId)
        layoutBuiltinReasoningMode = findViewById(R.id.layoutBuiltinReasoningMode)
        switchBuiltinReasoningMode = findViewById(R.id.switchBuiltinReasoningMode)
        tvBuiltinReasoningModeHint = findViewById(R.id.tvBuiltinReasoningModeHint)
        layoutBuiltinReasoningParams = findViewById(R.id.layoutBuiltinReasoningParams)
        etBuiltinReasoningParamsOnJson = findViewById(R.id.etBuiltinReasoningParamsOnJson)
        etBuiltinReasoningParamsOffJson = findViewById(R.id.etBuiltinReasoningParamsOffJson)
        sliderBuiltinTemperature = findViewById(R.id.sliderBuiltinTemperature)
        tvBuiltinTemperatureValue = findViewById(R.id.tvBuiltinTemperatureValue)
        btnBuiltinRegister = findViewById(R.id.btnBuiltinRegister)
        btnBuiltinTestCall = findViewById(R.id.btnBuiltinTestCall)

        // Custom LLM Group
        groupCustomLlm = findViewById(R.id.groupCustomLlm)
        tvLlmProfiles = findViewById(R.id.tvLlmProfilesValue)
        etLlmProfileName = findViewById(R.id.etLlmProfileName)
        etLlmEndpoint = findViewById(R.id.etLlmEndpoint)
        etLlmApiKey = findViewById(R.id.etLlmApiKey)
        tvCustomLlmModel = findViewById(R.id.tvCustomLlmModel)
        btnCustomLlmFetchModels = findViewById(R.id.btnCustomLlmFetchModels)
        tilCustomModelId = findViewById(R.id.tilCustomModelId)
        etCustomModelId = findViewById(R.id.etCustomModelId)
        layoutCustomReasoningMode = findViewById(R.id.layoutCustomReasoningMode)
        switchCustomReasoningMode = findViewById(R.id.switchCustomReasoningMode)
        tvCustomReasoningModeHint = findViewById(R.id.tvCustomReasoningModeHint)
        etCustomReasoningParamsOnJson = findViewById(R.id.etCustomReasoningParamsOnJson)
        etCustomReasoningParamsOffJson = findViewById(R.id.etCustomReasoningParamsOffJson)
        sliderLlmTemperature = findViewById(R.id.sliderLlmTemperature)
        tvLlmTemperatureValue = findViewById(R.id.tvLlmTemperatureValue)
        btnLlmAddProfile = findViewById(R.id.btnLlmAddProfile)
        btnLlmDeleteProfile = findViewById(R.id.btnLlmDeleteProfile)
        btnLlmTestCall = findViewById(R.id.btnLlmTestCall)

        // Prompt Preset Views
        tvPromptPresets = findViewById(R.id.tvPromptPresetsValue)
        etLlmPromptTitle = findViewById(R.id.etLlmPromptTitle)
        etLlmPrompt = findViewById(R.id.etLlmPrompt)
        btnAddPromptPreset = findViewById(R.id.btnAddPromptPreset)
        btnDeletePromptPreset = findViewById(R.id.btnDeletePromptPreset)
    }

    // ======== Vendor Section Setup ========

    private fun setupVendorSection() {
        // Builtin vendor API key listener
        etBuiltinApiKey.addTextChangeListener { text ->
            viewModel.updateBuiltinApiKey(prefs, text)
        }

        // Builtin vendor temperature slider listener
        sliderBuiltinTemperature.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                viewModel.updateBuiltinTemperature(prefs, value)
                tvBuiltinTemperatureValue.text = String.format("%.1f", value)
            }
        }
        sliderBuiltinTemperature.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = hapticTapIfEnabled(slider)
            override fun onStopTrackingTouch(slider: Slider) = hapticTapIfEnabled(slider)
        })

        // Builtin model selection
        tvBuiltinModel.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            showBuiltinModelSelectionDialog()
        }
        btnBuiltinFetchModels.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            handleFetchBuiltinModels(viewModel.selectedVendor.value)
        }

        // Builtin custom model ID listener
        etBuiltinCustomModelId.addTextChangeListener { text ->
            if (text.isNotBlank()) {
                viewModel.updateBuiltinModel(prefs, text)
            }
        }

        // Builtin reasoning mode switch
        switchBuiltinReasoningMode.setOnCheckedChangeListener { view, isChecked ->
            if (!isUpdatingProgrammatically) {
                hapticTapIfEnabled(view)
            }
            viewModel.updateBuiltinReasoningEnabled(prefs, isChecked)
        }
        etBuiltinReasoningParamsOnJson.addTextChangeListener { text ->
            val vendor = viewModel.selectedVendor.value
            if (vendor == LlmVendor.CUSTOM || vendor == LlmVendor.SF_FREE) return@addTextChangeListener
            prefs.setLlmVendorReasoningParamsOnJson(vendor, text)
        }
        etBuiltinReasoningParamsOffJson.addTextChangeListener { text ->
            val vendor = viewModel.selectedVendor.value
            if (vendor == LlmVendor.CUSTOM || vendor == LlmVendor.SF_FREE) return@addTextChangeListener
            prefs.setLlmVendorReasoningParamsOffJson(vendor, text)
        }

        // SF reasoning mode switch
        switchSfReasoningMode.setOnCheckedChangeListener { view, isChecked ->
            if (!isUpdatingProgrammatically) {
                hapticTapIfEnabled(view)
            }
            prefs.setLlmVendorReasoningEnabled(LlmVendor.SF_FREE, isChecked)
        }
        etSfReasoningParamsOnJson.addTextChangeListener { text ->
            prefs.setLlmVendorReasoningParamsOnJson(LlmVendor.SF_FREE, text)
        }
        etSfReasoningParamsOffJson.addTextChangeListener { text ->
            prefs.setLlmVendorReasoningParamsOffJson(LlmVendor.SF_FREE, text)
        }

        // Builtin register button
        btnBuiltinRegister.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val vendor = viewModel.selectedVendor.value
            if (vendor.registerUrl.isNotBlank()) {
                openUrlSafely(vendor.registerUrl)
            }
        }
        btnBuiltinTestCall.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            handleTestLlmCall()
        }
    }

    // ======== LLM Profile Section Setup ========

    private fun setupLlmProfileSection() {
        tvLlmProfiles.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            showLlmProfileSelectionDialog()
        }

        etLlmProfileName.addTextChangeListener { text ->
            viewModel.updateActiveLlmProvider(prefs) { it.copy(name = text) }
        }
        etLlmEndpoint.addTextChangeListener { text ->
            viewModel.updateActiveLlmProvider(prefs) { it.copy(endpoint = text) }
        }
        etLlmApiKey.addTextChangeListener { text ->
            viewModel.updateActiveLlmProvider(prefs) { it.copy(apiKey = text) }
        }
        etCustomModelId.addTextChangeListener { text ->
            viewModel.updateActiveLlmProvider(prefs) { it.copy(model = text) }
        }
        switchCustomReasoningMode.setOnCheckedChangeListener { view, isChecked ->
            if (!isUpdatingProgrammatically) {
                hapticTapIfEnabled(view)
            }
            viewModel.updateActiveLlmProvider(prefs) { it.copy(enableReasoning = isChecked) }
        }
        etCustomReasoningParamsOnJson.addTextChangeListener { text ->
            viewModel.updateActiveLlmProvider(prefs) { it.copy(reasoningParamsOnJson = text) }
        }
        etCustomReasoningParamsOffJson.addTextChangeListener { text ->
            viewModel.updateActiveLlmProvider(prefs) { it.copy(reasoningParamsOffJson = text) }
        }
        tvCustomLlmModel.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            showCustomLlmModelSelectionDialog()
        }
        btnCustomLlmFetchModels.setOnClickListener { view ->
            hapticTapIfEnabled(view)
            handleFetchCustomModels()
        }
        // Custom LLM temperature slider listener
        sliderLlmTemperature.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val coerced = value.coerceIn(0f, 2f)
                viewModel.updateActiveLlmProvider(prefs) { it.copy(temperature = coerced) }
                tvLlmTemperatureValue.text = String.format("%.1f", coerced)
            }
        }
        sliderLlmTemperature.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = hapticTapIfEnabled(slider)
            override fun onStopTrackingTouch(slider: Slider) = hapticTapIfEnabled(slider)
        })

        btnLlmTestCall.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            handleTestLlmCall()
        }
        btnLlmAddProfile.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            handleAddLlmProfile()
        }
        btnLlmDeleteProfile.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            handleDeleteLlmProfile()
        }
    }

    // ======== Prompt Preset Section Setup ========

    private fun setupPromptPresetSection() {
        tvPromptPresets.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            showPromptPresetSelectionDialog()
        }

        etLlmPromptTitle.addTextChangeListener { text ->
            viewModel.updateActivePromptPreset(prefs) { it.copy(title = text) }
        }
        etLlmPrompt.addTextChangeListener { text ->
            viewModel.updateActivePromptPreset(prefs) { it.copy(content = text) }
        }

        btnAddPromptPreset.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            handleAddPromptPreset()
        }
        btnDeletePromptPreset.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            handleDeletePromptPreset()
        }
    }

    // ======== Observer Methods ========

    private fun observeViewModelState() {
        lifecycleScope.launch {
            viewModel.selectedVendor.collectLatest { vendor ->
                updateVendorUI(vendor)
            }
        }
        lifecycleScope.launch {
            viewModel.builtinVendorConfig.collectLatest { config ->
                updateBuiltinConfigUI(config)
            }
        }
        lifecycleScope.launch {
            viewModel.activeLlmProvider.collectLatest { provider ->
                updateLlmProfileUI(provider)
            }
        }
        lifecycleScope.launch {
            viewModel.activePromptPreset.collectLatest { preset ->
                updatePromptPresetUI(preset)
            }
        }
    }

    private fun loadInitialData() {
        viewModel.loadData(prefs)
        updateSfFreeLlmModelDisplay()
        updateSfReasoningModeUI()
    }

    // ======== UI Update Methods ========

    private fun updateVendorUI(vendor: LlmVendor) {
        tvLlmVendor.text = getString(vendor.displayNameResId)

        // Show/hide groups based on vendor type
        groupSfFreeLlm.visibility = if (vendor == LlmVendor.SF_FREE) View.VISIBLE else View.GONE
        groupBuiltinLlm.visibility = if (vendor != LlmVendor.SF_FREE && vendor != LlmVendor.CUSTOM) View.VISIBLE else View.GONE
        groupCustomLlm.visibility = if (vendor == LlmVendor.CUSTOM) View.VISIBLE else View.GONE
    }

    private fun updateBuiltinConfigUI(config: AiPostSettingsViewModel.BuiltinVendorConfig) {
        isUpdatingProgrammatically = true
        etBuiltinApiKey.setTextIfDifferent(config.apiKey)
        val vendor = viewModel.selectedVendor.value
        val displayModel = config.model.ifBlank { vendor.defaultModel }
        val presetModels = prefs.getLlmVendorModels(vendor)
        val isPresetModel = displayModel.isNotBlank() && presetModels.contains(displayModel)
        val isBuiltinModel = displayModel.isNotBlank() && vendor.models.contains(displayModel)
        val showCustomModelInput = displayModel.isNotBlank() && !isPresetModel
        tvBuiltinModel.text = displayModel
        tilBuiltinCustomModelId.visibility = if (showCustomModelInput) View.VISIBLE else View.GONE
        if (showCustomModelInput) {
            etBuiltinCustomModelId.setTextIfDifferent(displayModel)
        }
        // Update slider range based on vendor temperature limits and set value
        sliderBuiltinTemperature.valueFrom = vendor.temperatureMin
        sliderBuiltinTemperature.valueTo = vendor.temperatureMax
        val coercedTemp = config.temperature.coerceIn(vendor.temperatureMin, vendor.temperatureMax)
        sliderBuiltinTemperature.value = coercedTemp
        tvBuiltinTemperatureValue.text = String.format("%.1f", coercedTemp)

        // Update reasoning mode switch visibility and state
        val supportsReasoning = viewModel.supportsReasoningSwitch(vendor, displayModel)
        val showCustomReasoningParams = displayModel.isNotBlank() && !isBuiltinModel
        val showReasoning = supportsReasoning || showCustomReasoningParams
        layoutBuiltinReasoningMode.visibility = if (showReasoning) View.VISIBLE else View.GONE
        layoutBuiltinReasoningParams.visibility = if (showCustomReasoningParams) View.VISIBLE else View.GONE
        if (showReasoning) {
            switchBuiltinReasoningMode.isChecked = config.reasoningEnabled
        }
        if (showCustomReasoningParams) {
            etBuiltinReasoningParamsOnJson.setTextIfDifferent(
                prefs.getLlmVendorReasoningParamsOnJson(vendor)
            )
            etBuiltinReasoningParamsOffJson.setTextIfDifferent(
                prefs.getLlmVendorReasoningParamsOffJson(vendor)
            )
        }
        isUpdatingProgrammatically = false
    }

    private fun updateSfFreePaidUI(isFreeMode: Boolean) {
        tvSfFreeServiceDesc.visibility = if (isFreeMode) View.VISIBLE else View.GONE
        tilSfApiKey.visibility = if (isFreeMode) View.GONE else View.VISIBLE
        layoutSfTemperature.visibility = if (isFreeMode) View.GONE else View.VISIBLE
        btnSfFreeLlmFetchModels.visibility = if (isFreeMode) View.GONE else View.VISIBLE
        // Update model display based on mode
        updateSfFreeLlmModelDisplay()
        updateSfReasoningModeUI()
        if (!isFreeMode) {
            updateSfTemperatureDisplay()
        }
    }

    private fun getSfPresetModels(): List<String> {
        return if (prefs.sfFreeLlmUsePaidKey) {
            prefs.getLlmVendorModels(LlmVendor.SF_FREE)
        } else {
            Prefs.SF_FREE_LLM_MODELS
        }
    }

    private fun getSfStaticModels(): List<String> {
        return if (prefs.sfFreeLlmUsePaidKey) {
            LlmVendor.SF_FREE.models
        } else {
            Prefs.SF_FREE_LLM_MODELS
        }
    }

    private fun updateSfFreeLlmModelDisplay() {
        isUpdatingProgrammatically = true
        val model = if (prefs.sfFreeLlmUsePaidKey) {
            prefs.getLlmVendorModel(LlmVendor.SF_FREE).ifBlank { prefs.sfFreeLlmModel }
        } else {
            prefs.sfFreeLlmModel
        }
        // Check if it's a custom model (not in preset list)
        val presetModels = getSfPresetModels()
        val isPresetModel = model.isNotBlank() && presetModels.contains(model)
        val showCustomModelInput = model.isNotBlank() && !isPresetModel
        tvSfFreeLlmModel.text = if (showCustomModelInput) model else model
        tilSfCustomModelId.visibility = if (showCustomModelInput) View.VISIBLE else View.GONE
        if (showCustomModelInput) {
            etSfCustomModelId.setTextIfDifferent(model)
        }
        isUpdatingProgrammatically = false
    }

    private fun updateSfTemperatureDisplay() {
        isUpdatingProgrammatically = true
        val temperature = prefs.getLlmVendorTemperature(LlmVendor.SF_FREE)
        val coerced = temperature.coerceIn(0f, 2f)
        sliderSfTemperature.value = coerced
        tvSfTemperatureValue.text = String.format("%.1f", coerced)
        isUpdatingProgrammatically = false
    }

    private fun updateSfReasoningModeUI() {
        isUpdatingProgrammatically = true
        val model = if (prefs.sfFreeLlmUsePaidKey) {
            prefs.getLlmVendorModel(LlmVendor.SF_FREE).ifBlank { prefs.sfFreeLlmModel }
        } else {
            prefs.sfFreeLlmModel
        }
        val staticModels = getSfStaticModels()
        val showCustomReasoningParams = model.isNotBlank() && !staticModels.contains(model)
        val supportsReasoning = viewModel.supportsReasoningSwitch(LlmVendor.SF_FREE, model)
        val showReasoning = supportsReasoning || showCustomReasoningParams
        layoutSfReasoningMode.visibility = if (showReasoning) View.VISIBLE else View.GONE
        layoutSfReasoningParams.visibility = if (showCustomReasoningParams) View.VISIBLE else View.GONE
        if (showReasoning) {
            switchSfReasoningMode.isChecked = prefs.getLlmVendorReasoningEnabled(LlmVendor.SF_FREE)
        }
        if (showCustomReasoningParams) {
            etSfReasoningParamsOnJson.setTextIfDifferent(
                prefs.getLlmVendorReasoningParamsOnJson(LlmVendor.SF_FREE)
            )
            etSfReasoningParamsOffJson.setTextIfDifferent(
                prefs.getLlmVendorReasoningParamsOffJson(LlmVendor.SF_FREE)
            )
        }
        isUpdatingProgrammatically = false
    }

    private fun updateLlmProfileUI(provider: Prefs.LlmProvider?) {
        isUpdatingProgrammatically = true
        if (provider?.id != lastCustomProfileId) {
            lastCustomProfileId = provider?.id
            isCustomModelInputVisible = false
        }
        val displayName = (provider?.name ?: "").ifBlank { getString(R.string.untitled_profile) }
        tvLlmProfiles.text = displayName
        etLlmProfileName.setTextIfDifferent(provider?.name ?: "")
        etLlmEndpoint.setTextIfDifferent(provider?.endpoint ?: prefs.llmEndpoint)
        etLlmApiKey.setTextIfDifferent(provider?.apiKey ?: prefs.llmApiKey)
        val customModels = provider?.models.orEmpty().map { it.trim() }.filter { it.isNotBlank() }
        val hasPresetModels = customModels.isNotEmpty()
        val model = (provider?.model ?: prefs.llmModel).trim()
        val isPresetModel = model.isNotBlank() && customModels.contains(model)
        if (isPresetModel) {
            isCustomModelInputVisible = false
        } else if (model.isNotBlank()) {
            isCustomModelInputVisible = true
        }
        tvCustomLlmModel.text = if (model.isNotBlank()) model else getString(R.string.option_custom_model)
        tilCustomModelId.visibility = if (isCustomModelInputVisible) View.VISIBLE else View.GONE
        btnCustomLlmFetchModels.visibility = if (isCustomModelInputVisible && hasPresetModels) View.GONE else View.VISIBLE
        if (isCustomModelInputVisible) {
            etCustomModelId.setTextIfDifferent(model)
        } else {
            etCustomModelId.setTextIfDifferent("")
        }
        layoutCustomReasoningMode.visibility = View.VISIBLE
        switchCustomReasoningMode.isChecked = provider?.enableReasoning ?: false
        etCustomReasoningParamsOnJson.setTextIfDifferent(
            provider?.reasoningParamsOnJson.orEmpty()
        )
        etCustomReasoningParamsOffJson.setTextIfDifferent(
            provider?.reasoningParamsOffJson.orEmpty()
        )
        val temperature = (provider?.temperature ?: prefs.llmTemperature).coerceIn(0f, 2f)
        sliderLlmTemperature.value = temperature
        tvLlmTemperatureValue.text = String.format("%.1f", temperature)
        isUpdatingProgrammatically = false
    }

    private fun updatePromptPresetUI(preset: PromptPreset?) {
        isUpdatingProgrammatically = true
        tvPromptPresets.text = (preset?.title ?: "").ifBlank { getString(R.string.untitled_preset) }
        etLlmPromptTitle.setTextIfDifferent(preset?.title ?: "")
        etLlmPrompt.setTextIfDifferent(preset?.content ?: "")
        isUpdatingProgrammatically = false
    }

    // ======== Dialog Methods ========

    private fun showSingleChoiceBottomSheet(
        titleResId: Int,
        items: List<String>,
        selectedIndex: Int,
        onSelected: (Int) -> Unit
    ) {
        SettingsOptionSheet.showSingleChoice(
            context = this,
            titleResId = titleResId,
            items = items,
            selectedIndex = selectedIndex,
            onSelected = onSelected
        )
    }

    private fun showVendorSelectionDialog() {
        val vendors = LlmVendor.allVendors()
        val titles = vendors.map { getString(it.displayNameResId) }.toTypedArray()
        val currentVendor = viewModel.selectedVendor.value
        val selectedIndex = vendors.indexOf(currentVendor).coerceAtLeast(0)

        showSingleChoiceBottomSheet(
            titleResId = R.string.label_llm_vendor,
            items = titles.toList(),
            selectedIndex = selectedIndex
        ) { which ->
            val selected = vendors.getOrNull(which)
            if (selected != null) {
                viewModel.selectVendor(prefs, selected)
            }
        }
    }

    private fun showSfFreeLlmModelSelectionDialog() {
        val customOption = getString(R.string.option_custom_model)
        val presetModels = getSfPresetModels()
        val models = (presetModels + customOption).toTypedArray()

        val currentModel = if (prefs.sfFreeLlmUsePaidKey) {
            prefs.getLlmVendorModel(LlmVendor.SF_FREE).ifBlank { prefs.sfFreeLlmModel }
        } else {
            prefs.sfFreeLlmModel
        }
        val isCurrentCustom = !presetModels.contains(currentModel) && currentModel.isNotBlank()
        val selectedIndex = if (isCurrentCustom) {
            models.size - 1 // Custom option
        } else {
            presetModels.indexOf(currentModel).coerceAtLeast(0)
        }

        showSingleChoiceBottomSheet(
            titleResId = R.string.label_sf_free_llm_model,
            items = models.toList(),
            selectedIndex = selectedIndex
        ) { which ->
            if (which == models.size - 1) {
                // Custom option selected - show input field
                tilSfCustomModelId.visibility = View.VISIBLE
                etSfCustomModelId.requestFocus()
                tvSfFreeLlmModel.text = customOption
            } else {
                val selected = presetModels.getOrNull(which)
                if (selected != null) {
                    if (prefs.sfFreeLlmUsePaidKey) {
                        prefs.setLlmVendorModel(LlmVendor.SF_FREE, selected)
                    } else {
                        prefs.sfFreeLlmModel = selected
                    }
                    tilSfCustomModelId.visibility = View.GONE
                    updateSfFreeLlmModelDisplay()
                }
            }
            // Update reasoning mode UI based on new model
            updateSfReasoningModeUI()
        }
    }

    private fun showBuiltinModelSelectionDialog() {
        val vendor = viewModel.selectedVendor.value
        val customOption = getString(R.string.option_custom_model)
        val presetModels = prefs.getLlmVendorModels(vendor)
        val models = (presetModels + customOption).toTypedArray()

        val currentModel = viewModel.builtinVendorConfig.value.model.ifBlank { vendor.defaultModel }
        val isCurrentCustom = !presetModels.contains(currentModel) && currentModel.isNotBlank()
        val selectedIndex = if (isCurrentCustom) {
            models.size - 1 // Custom option
        } else {
            presetModels.indexOf(currentModel).coerceAtLeast(0)
        }

        showSingleChoiceBottomSheet(
            titleResId = R.string.label_llm_model_select,
            items = models.toList(),
            selectedIndex = selectedIndex
        ) { which ->
            if (which == models.size - 1) {
                // Custom option selected - show input field
                tilBuiltinCustomModelId.visibility = View.VISIBLE
                etBuiltinCustomModelId.requestFocus()
                tvBuiltinModel.text = customOption
            } else {
                val selected = presetModels.getOrNull(which)
                if (selected != null) {
                    viewModel.updateBuiltinModel(prefs, selected)
                    tilBuiltinCustomModelId.visibility = View.GONE
                }
            }
        }
    }

    private fun showLlmProfileSelectionDialog() {
        val profiles = viewModel.llmProfiles.value
        if (profiles.isEmpty()) return

        val titles = profiles.map { it.name.ifBlank { getString(R.string.untitled_profile) } }.toTypedArray()
        val selectedIndex = viewModel.getActiveLlmProviderIndex()

        showSingleChoiceBottomSheet(
            titleResId = R.string.label_llm_choose_profile,
            items = titles.toList(),
            selectedIndex = selectedIndex
        ) { which ->
            val selected = profiles.getOrNull(which)
            if (selected != null) {
                viewModel.selectLlmProvider(prefs, selected.id)
            }
        }
    }

    private fun showCustomLlmModelSelectionDialog() {
        val provider = viewModel.activeLlmProvider.value
        val customOption = getString(R.string.option_custom_model)
        val presetModels = provider?.models.orEmpty().map { it.trim() }.filter { it.isNotBlank() }
        val hasPresetModels = presetModels.isNotEmpty()
        val models = (presetModels + customOption).toTypedArray()
        val currentModel = provider?.model.orEmpty()
        val isCustom = currentModel.isBlank() || !presetModels.contains(currentModel)
        val selectedIndex = if (isCustom) {
            models.size - 1
        } else {
            presetModels.indexOf(currentModel).coerceAtLeast(0)
        }

        showSingleChoiceBottomSheet(
            titleResId = R.string.label_llm_model_select,
            items = models.toList(),
            selectedIndex = selectedIndex
        ) { which ->
            if (which == models.size - 1) {
                tilCustomModelId.visibility = View.VISIBLE
                btnCustomLlmFetchModels.visibility = if (hasPresetModels) View.GONE else View.VISIBLE
                isCustomModelInputVisible = true
                etCustomModelId.requestFocus()
                val nextModel = currentModel.takeIf { it.isNotBlank() && !presetModels.contains(it) }.orEmpty()
                viewModel.updateActiveLlmProvider(prefs) { it.copy(model = nextModel) }
            } else {
                val selected = presetModels.getOrNull(which)
                if (selected != null) {
                    isCustomModelInputVisible = false
                    viewModel.updateActiveLlmProvider(prefs) { it.copy(model = selected) }
                    tilCustomModelId.visibility = View.GONE
                    btnCustomLlmFetchModels.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun handleFetchBuiltinModels(vendor: LlmVendor) {
        if (vendor == LlmVendor.CUSTOM || vendor == LlmVendor.SF_FREE) return
        val endpoint = vendor.endpoint
        val apiKey = prefs.getLlmVendorApiKey(vendor)
        if (endpoint.isBlank()) {
            Toast.makeText(this, getString(R.string.llm_test_missing_params), Toast.LENGTH_SHORT).show()
            return
        }

        val progressDialog = MaterialAlertDialogBuilder(this)
            .setMessage(R.string.llm_models_fetching)
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            val result = LlmPostProcessor().fetchModels(endpoint, apiKey)
            progressDialog.dismiss()
            if (result.ok) {
                showBuiltinModelsPickerDialog(vendor, result.models)
            } else {
                val msg = result.message ?: getString(R.string.llm_test_failed_generic)
                MaterialAlertDialogBuilder(this@AiPostSettingsActivity)
                    .setTitle(R.string.llm_models_fetch_failed_title)
                    .setMessage(getString(R.string.llm_models_fetch_failed, msg))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun handleFetchSfModels() {
        if (!prefs.sfFreeLlmUsePaidKey) return
        val endpoint = LlmVendor.SF_FREE.endpoint
        val apiKey = prefs.getLlmVendorApiKey(LlmVendor.SF_FREE)
        if (endpoint.isBlank()) {
            Toast.makeText(this, getString(R.string.llm_test_missing_params), Toast.LENGTH_SHORT).show()
            return
        }

        val progressDialog = MaterialAlertDialogBuilder(this)
            .setMessage(R.string.llm_models_fetching)
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            val result = LlmPostProcessor().fetchModels(endpoint, apiKey)
            progressDialog.dismiss()
            if (result.ok) {
                showBuiltinModelsPickerDialog(LlmVendor.SF_FREE, result.models)
            } else {
                val msg = result.message ?: getString(R.string.llm_test_failed_generic)
                MaterialAlertDialogBuilder(this@AiPostSettingsActivity)
                    .setTitle(R.string.llm_models_fetch_failed_title)
                    .setMessage(getString(R.string.llm_models_fetch_failed, msg))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun handleFetchCustomModels() {
        val provider = viewModel.activeLlmProvider.value
        val endpoint = provider?.endpoint?.ifBlank { prefs.llmEndpoint } ?: prefs.llmEndpoint
        val apiKey = provider?.apiKey?.ifBlank { prefs.llmApiKey } ?: prefs.llmApiKey
        if (endpoint.isBlank()) {
            Toast.makeText(this, getString(R.string.llm_test_missing_params), Toast.LENGTH_SHORT).show()
            return
        }

        val progressDialog = MaterialAlertDialogBuilder(this)
            .setMessage(R.string.llm_models_fetching)
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            val result = LlmPostProcessor().fetchModels(endpoint, apiKey)
            progressDialog.dismiss()
            if (result.ok) {
                showCustomModelsPickerDialog(result.models)
            } else {
                val msg = result.message ?: getString(R.string.llm_test_failed_generic)
                MaterialAlertDialogBuilder(this@AiPostSettingsActivity)
                    .setTitle(R.string.llm_models_fetch_failed_title)
                    .setMessage(getString(R.string.llm_models_fetch_failed, msg))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun showBuiltinModelsPickerDialog(vendor: LlmVendor, models: List<String>) {
        val currentModels = prefs.getLlmVendorModels(vendor)
        val uniqueModels = models.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (uniqueModels.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.llm_models_fetch_failed_title)
                .setMessage(getString(R.string.llm_models_fetch_failed, getString(R.string.llm_test_failed_generic)))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val checkedItems = uniqueModels.map { currentModels.contains(it) }.toBooleanArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.llm_models_select_title)
            .setMultiChoiceItems(uniqueModels.toTypedArray(), checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton(R.string.btn_llm_models_add) { dialog, _ ->
                val selected = uniqueModels.filterIndexed { index, _ -> checkedItems[index] }
                if (selected.isEmpty()) {
                    Toast.makeText(this, getString(R.string.toast_llm_models_none_selected), Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    return@setPositiveButton
                }
                prefs.setLlmVendorModels(vendor, selected)
                val currentModel = if (vendor == LlmVendor.SF_FREE && !prefs.sfFreeLlmUsePaidKey) {
                    prefs.sfFreeLlmModel
                } else {
                    prefs.getLlmVendorModel(vendor)
                }
                val nextModel = when {
                    currentModel.isNotBlank() && selected.contains(currentModel) -> currentModel
                    else -> selected.first()
                }
                if (vendor == LlmVendor.SF_FREE && !prefs.sfFreeLlmUsePaidKey) {
                    prefs.sfFreeLlmModel = nextModel
                } else {
                    prefs.setLlmVendorModel(vendor, nextModel)
                }
                if (vendor == LlmVendor.SF_FREE) {
                    updateSfFreeLlmModelDisplay()
                    updateSfReasoningModeUI()
                } else {
                    viewModel.updateBuiltinModel(prefs, nextModel)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showCustomModelsPickerDialog(models: List<String>) {
        val provider = viewModel.activeLlmProvider.value
        val currentModels = provider?.models.orEmpty().map { it.trim() }.filter { it.isNotBlank() }
        val uniqueModels = models.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (uniqueModels.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.llm_models_fetch_failed_title)
                .setMessage(getString(R.string.llm_models_fetch_failed, getString(R.string.llm_test_failed_generic)))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val checkedItems = uniqueModels.map { currentModels.contains(it) }.toBooleanArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.llm_models_select_title)
            .setMultiChoiceItems(uniqueModels.toTypedArray(), checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton(R.string.btn_llm_models_add) { dialog, _ ->
                val selected = uniqueModels.filterIndexed { index, _ -> checkedItems[index] }
                if (selected.isEmpty()) {
                    Toast.makeText(this, getString(R.string.toast_llm_models_none_selected), Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    return@setPositiveButton
                }
                val currentModel = provider?.model.orEmpty()
                val nextModel = when {
                    currentModel.isNotBlank() && selected.contains(currentModel) -> currentModel
                    else -> selected.first()
                }
                viewModel.updateActiveLlmProvider(prefs) { it.copy(models = selected, model = nextModel) }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showPromptPresetSelectionDialog() {
        val presets = viewModel.promptPresets.value
        if (presets.isEmpty()) return

        val titles = presets.map { it.title.ifBlank { getString(R.string.untitled_preset) } }.toTypedArray()
        val selectedIndex = viewModel.getActivePromptPresetIndex()

        showSingleChoiceBottomSheet(
            titleResId = R.string.label_llm_prompt_presets,
            items = titles.toList(),
            selectedIndex = selectedIndex
        ) { which ->
            val selected = presets.getOrNull(which)
            if (selected != null) {
                viewModel.selectPromptPreset(prefs, selected.id)
            }
        }
    }

    // ======== Action Handlers ========

    private fun handleAddLlmProfile() {
        val defaultName = getString(R.string.untitled_profile)
        if (viewModel.addLlmProvider(prefs, defaultName)) {
            Toast.makeText(this, getString(R.string.toast_llm_profile_added), Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleDeleteLlmProfile() {
        if (viewModel.deleteActiveLlmProvider(prefs)) {
            Toast.makeText(this, getString(R.string.toast_llm_profile_deleted), Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleTestLlmCall() {
        val progressDialog = MaterialAlertDialogBuilder(this)
            .setMessage(R.string.llm_test_running)
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            try {
                val processor = LlmPostProcessor()
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    processor.testConnectivity(prefs)
                }
                progressDialog.dismiss()

                if (result.ok) {
                    val preview = result.contentPreview ?: ""
                    MaterialAlertDialogBuilder(this@AiPostSettingsActivity)
                        .setTitle(R.string.llm_test_success_title)
                        .setMessage(getString(R.string.llm_test_success_preview, preview))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } else {
                    val msg = when {
                        result.message?.contains("Missing endpoint", ignoreCase = true) == true ||
                            result.message?.contains("Missing model", ignoreCase = true) == true ->
                            getString(R.string.llm_test_missing_params)
                        result.httpCode != null ->
                            "HTTP ${result.httpCode}: ${result.message ?: ""}"
                        else -> result.message ?: getString(R.string.llm_test_failed_generic)
                    }
                    MaterialAlertDialogBuilder(this@AiPostSettingsActivity)
                        .setTitle(R.string.llm_test_failed_title)
                        .setMessage(getString(R.string.llm_test_failed_reason, msg))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            } catch (e: Exception) {
                progressDialog.dismiss()
                MaterialAlertDialogBuilder(this@AiPostSettingsActivity)
                    .setTitle(R.string.llm_test_failed_title)
                    .setMessage(getString(R.string.llm_test_failed_reason, e.message ?: "unknown"))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun handleAddPromptPreset() {
        val defaultTitle = getString(R.string.untitled_preset)
        val defaultContent = ""
        if (viewModel.addPromptPreset(prefs, defaultTitle, defaultContent)) {
            Toast.makeText(this, getString(R.string.toast_preset_added), Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleDeletePromptPreset() {
        if (viewModel.deleteActivePromptPreset(prefs)) {
            Toast.makeText(this, getString(R.string.toast_preset_deleted), Toast.LENGTH_SHORT).show()
        }
    }

    // ======== Extension Functions ========

    private fun EditText.addTextChangeListener(onChange: (String) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isUpdatingProgrammatically) return
                onChange(s?.toString() ?: "")
            }
        })
    }

    private fun EditText.setTextIfDifferent(newText: String) {
        val currentText = this.text?.toString() ?: ""
        if (currentText != newText) {
            setText(newText)
        }
    }

    private fun hapticTapIfEnabled(view: View?) {
        HapticFeedbackHelper.performTap(this, prefs, view)
    }

    /**
     * Send broadcast to refresh keyboard UI (e.g., update AI post-process button toggle state)
     */
    private fun sendRefreshBroadcast() {
        sendBroadcast(Intent(AsrKeyboardService.ACTION_REFRESH_IME_UI).apply {
            setPackage(packageName)
        })
    }

    private fun openUrlSafely(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Throwable) {
            Toast.makeText(this, getString(R.string.error_open_browser), Toast.LENGTH_SHORT).show()
        }
    }
}
