package com.brycewg.asrkb.ui.settings.asr

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.brycewg.asrkb.ui.BaseActivity
import androidx.lifecycle.lifecycleScope
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.asr.SherpaPunctuationManager
import com.brycewg.asrkb.ui.AsrVendorUi
import com.brycewg.asrkb.ui.SettingsOptionSheet
import com.brycewg.asrkb.ui.installExplainedSwitch
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.asr.VadDetector
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * ASR Settings Activity - Refactored version with ViewModel pattern.
 *
 * Key improvements:
 * 1. Introduced AsrSettingsViewModel to manage state and business logic
 * 2. Split giant onCreate into focused setup methods
 * 3. Created reusable showSingleChoiceDialog function
 * 4. Added proper logging to all catch blocks
 * 5. Vendor-specific settings organized in separate methods
 */
class AsrSettingsActivity : BaseActivity() {

    private lateinit var viewModel: AsrSettingsViewModel
    private lateinit var prefs: Prefs

    // File pickers for model import
    private val modelFilePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handleModelImport(it) }
    }
    private val tsModelFilePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handleTsModelImport(it) }
    }
    private val pfModelFilePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handlePfModelImport(it) }
    }
    private val punctModelFilePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handlePunctModelImport(it) }
    }

    // View references grouped by function
    private lateinit var tvAsrVendor: TextView

    // Silence detection views
    private lateinit var switchAutoStopSilence: MaterialSwitch
    private lateinit var tvSilenceWindowLabel: View
    private lateinit var sliderSilenceWindow: Slider
    private lateinit var tvSilenceSensitivityLabel: View
    private lateinit var sliderSilenceSensitivity: Slider

    // Vendor group containers
    private lateinit var groupVolc: View
    private lateinit var groupSf: View
    private lateinit var groupEleven: View
    private lateinit var groupOpenAi: View
    private lateinit var groupDash: View
    private lateinit var groupGemini: View
    private lateinit var groupSoniox: View
    private lateinit var groupZhipu: View
    private lateinit var groupSenseVoice: View
    private lateinit var groupTelespeech: View
    private lateinit var groupParaformer: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_asr_settings)

        // 应用 Window Insets 以适配 Android 15 边缘到边缘显示
        findViewById<View>(android.R.id.content).let { rootView ->
            com.brycewg.asrkb.ui.WindowInsetsHelper.applySystemBarsInsets(rootView)
        }

        prefs = Prefs(this)
        viewModel = ViewModelProvider(this)[AsrSettingsViewModel::class.java]
        viewModel.initialize(this)

        setupToolbar()
        initializeViews()
        setupVendorSelection()
        setupSilenceDetection()
        setupVendorSpecificSettings()
        observeViewModel()

    }

    override fun onResume() {
        super.onResume()
        updateSvDownloadUiVisibility()
        updateTsDownloadUiVisibility()
        updatePfDownloadUiVisibility()
        updatePunctDownloadUiVisibility()
    }

    private fun setupToolbar() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setTitle(R.string.title_asr_settings)
        toolbar.setNavigationOnClickListener { finish() }
    }

    private fun initializeViews() {
        // ASR Vendor
        tvAsrVendor = findViewById(R.id.tvAsrVendorValue)

        // Silence auto-stop controls
        switchAutoStopSilence = findViewById(R.id.switchAutoStopSilence)
        tvSilenceWindowLabel = findViewById(R.id.tvSilenceWindowLabel)
        sliderSilenceWindow = findViewById(R.id.sliderSilenceWindow)
        tvSilenceSensitivityLabel = findViewById(R.id.tvSilenceSensitivityLabel)
        sliderSilenceSensitivity = findViewById(R.id.sliderSilenceSensitivity)

        // Vendor groups
        groupVolc = findViewById(R.id.groupVolc)
        groupSf = findViewById(R.id.groupSf)
        groupEleven = findViewById(R.id.groupEleven)
        groupOpenAi = findViewById(R.id.groupOpenAI)
        groupDash = findViewById(R.id.groupDashScope)
        groupGemini = findViewById(R.id.groupGemini)
        groupSoniox = findViewById(R.id.groupSoniox)
        groupZhipu = findViewById(R.id.groupZhipu)
        groupSenseVoice = findViewById(R.id.groupSenseVoice)
        groupTelespeech = findViewById(R.id.groupTelespeech)
        groupParaformer = findViewById(R.id.groupParaformer)
    }

    private fun setupVendorSelection() {
        val vendorOrder = AsrVendorUi.ordered()
        val vendorItems = AsrVendorUi.names(this)

        tvAsrVendor.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val curIdx = vendorOrder.indexOf(prefs.asrVendor).coerceAtLeast(0)
            showSingleChoiceDialog(
                titleResId = R.string.label_asr_vendor,
                items = vendorItems.toTypedArray(),
                currentIndex = curIdx
            ) { selectedIdx ->
                val vendor = vendorOrder.getOrNull(selectedIdx) ?: AsrVendor.Volc
                viewModel.updateVendor(vendor)
            }
        }
    }

    private fun setupSilenceDetection() {
        // Initial values
        switchAutoStopSilence.isChecked = prefs.autoStopOnSilenceEnabled
        sliderSilenceWindow.value = prefs.autoStopSilenceWindowMs.toFloat()
        sliderSilenceSensitivity.value = prefs.autoStopSilenceSensitivity.toFloat()

        // Switch listener with explainer dialog
        switchAutoStopSilence.installExplainedSwitch(
            context = this,
            titleRes = R.string.label_auto_stop_silence,
            offDescRes = R.string.feature_auto_stop_silence_off_desc,
            onDescRes = R.string.feature_auto_stop_silence_on_desc,
            preferenceKey = "auto_stop_silence_explained",
            readPref = { prefs.autoStopOnSilenceEnabled },
            writePref = { v -> viewModel.updateAutoStopSilence(v) },
            onChanged = { enabled ->
                // 若开启，则立即预加载全局 VAD，避免下次录音首次加载
                if (enabled) {
                    try { VadDetector.preload(applicationContext, 16000, prefs.autoStopSilenceSensitivity) } catch (_: Throwable) { }
                }
            },
            hapticFeedback = { hapticTapIfEnabled(it) }
        )

        // Sliders
        setupSlider(sliderSilenceWindow) { value ->
            viewModel.updateSilenceWindow(value.toInt().coerceIn(300, 5000))
        }

        setupSlider(sliderSilenceSensitivity) { value ->
            viewModel.updateSilenceSensitivity(value.toInt().coerceIn(1, 10))
        }
        // 在松手时“立即生效”：重建全局 VAD，以新的灵敏度用于后续会话
        sliderSilenceSensitivity.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) { /* no-op */ }
            override fun onStopTrackingTouch(slider: Slider) {
                try {
                    if (prefs.autoStopOnSilenceEnabled) {
                        VadDetector.rebuildGlobal(applicationContext, 16000, prefs.autoStopSilenceSensitivity)
                        Toast.makeText(this@AsrSettingsActivity, R.string.toast_vad_sensitivity_applied, Toast.LENGTH_SHORT).show()
                    }
                } catch (_: Throwable) { }
            }
        })

    }

    private fun setupVendorSpecificSettings() {
        setupVolcengineSettings()
        setupSiliconFlowSettings()
        setupElevenLabsSettings()
        setupOpenAISettings()
        setupDashScopeSettings()
        setupGeminiSettings()
        setupSonioxSettings()
        setupZhipuSettings()
        setupSenseVoiceSettings()
        setupTelespeechSettings()
        setupParaformerSettings()
    }

    private fun setupVolcengineSettings() {

        // EditTexts
        findViewById<EditText>(R.id.etAppKey).apply {
            setText(prefs.appKey)
            bindString { prefs.appKey = it }
        }
        findViewById<EditText>(R.id.etAccessKey).apply {
            setText(prefs.accessKey)
            bindString { prefs.accessKey = it }
        }

        // Switches with explainer dialogs
        findViewById<MaterialSwitch>(R.id.switchVolcStreaming).apply {
            isChecked = prefs.volcStreamingEnabled
            installExplainedSwitch(
                context = this@AsrSettingsActivity,
                titleRes = R.string.label_volc_streaming,
                offDescRes = R.string.feature_volc_streaming_off_desc,
                onDescRes = R.string.feature_volc_streaming_on_desc,
                preferenceKey = "volc_streaming_explained",
                readPref = { prefs.volcStreamingEnabled },
                writePref = { v -> viewModel.updateVolcStreaming(v) },
                hapticFeedback = { hapticTapIfEnabled(it) }
            )
        }

        findViewById<MaterialSwitch>(R.id.switchVolcFileStandard).apply {
            isChecked = prefs.volcFileStandardEnabled
            installExplainedSwitch(
                context = this@AsrSettingsActivity,
                titleRes = R.string.label_volc_file_standard,
                offDescRes = R.string.feature_volc_file_standard_off_desc,
                onDescRes = R.string.feature_volc_file_standard_on_desc,
                preferenceKey = "volc_file_standard_explained",
                readPref = { prefs.volcFileStandardEnabled },
                writePref = { v -> viewModel.updateVolcFileStandard(v) },
                hapticFeedback = { hapticTapIfEnabled(it) }
            )
        }

        findViewById<MaterialSwitch>(R.id.switchVolcModelV2).apply {
            isChecked = prefs.volcModelV2Enabled
            installExplainedSwitch(
                context = this@AsrSettingsActivity,
                titleRes = R.string.label_volc_model_v2,
                offDescRes = R.string.feature_volc_model_v2_off_desc,
                onDescRes = R.string.feature_volc_model_v2_on_desc,
                preferenceKey = "volc_model_v2_explained",
                readPref = { prefs.volcModelV2Enabled },
                writePref = { v -> viewModel.updateVolcModelV2(v) },
                hapticFeedback = { hapticTapIfEnabled(it) }
            )
        }

        findViewById<MaterialSwitch>(R.id.switchVolcBidiStreaming).apply {
            isChecked = prefs.volcBidiStreamingEnabled
            installExplainedSwitch(
                context = this@AsrSettingsActivity,
                titleRes = R.string.label_volc_bidi_streaming,
                offDescRes = R.string.feature_volc_bidi_streaming_off_desc,
                onDescRes = R.string.feature_volc_bidi_streaming_on_desc,
                preferenceKey = "volc_bidi_streaming_explained",
                readPref = { prefs.volcBidiStreamingEnabled },
                writePref = { v -> viewModel.updateVolcBidiStreaming(v) },
                onChanged = { enabled ->
                    // 若关闭双向流式，自动关闭并隐藏"二遍识别"
                    if (!enabled) {
                        try {
                            findViewById<MaterialSwitch>(R.id.switchVolcNonstream).isChecked = false
                        } catch (e: Throwable) {
                            android.util.Log.e(TAG, "Failed to turn off two-pass switch when bidi disabled", e)
                        }
                    }
                },
                hapticFeedback = { hapticTapIfEnabled(it) }
            )
        }

        findViewById<MaterialSwitch>(R.id.switchVolcDdc).apply {
            isChecked = prefs.volcDdcEnabled
            installExplainedSwitch(
                context = this@AsrSettingsActivity,
                titleRes = R.string.label_volc_ddc,
                offDescRes = R.string.feature_volc_ddc_off_desc,
                onDescRes = R.string.feature_volc_ddc_on_desc,
                preferenceKey = "volc_ddc_explained",
                readPref = { prefs.volcDdcEnabled },
                writePref = { v -> viewModel.updateVolcDdc(v) },
                hapticFeedback = { hapticTapIfEnabled(it) }
            )
        }

        findViewById<MaterialSwitch>(R.id.switchVolcVad).apply {
            isChecked = prefs.volcVadEnabled
            installExplainedSwitch(
                context = this@AsrSettingsActivity,
                titleRes = R.string.label_volc_vad,
                offDescRes = R.string.feature_volc_vad_off_desc,
                onDescRes = R.string.feature_volc_vad_on_desc,
                preferenceKey = "volc_vad_explained",
                readPref = { prefs.volcVadEnabled },
                writePref = { v -> viewModel.updateVolcVad(v) },
                hapticFeedback = { hapticTapIfEnabled(it) }
            )
        }

        findViewById<MaterialSwitch>(R.id.switchVolcNonstream).apply {
            isChecked = prefs.volcNonstreamEnabled
            installExplainedSwitch(
                context = this@AsrSettingsActivity,
                titleRes = R.string.label_volc_nonstream,
                offDescRes = R.string.feature_volc_nonstream_off_desc,
                onDescRes = R.string.feature_volc_nonstream_on_desc,
                preferenceKey = "volc_nonstream_explained",
                readPref = { prefs.volcNonstreamEnabled },
                writePref = { v -> viewModel.updateVolcNonstream(v) },
                hapticFeedback = { hapticTapIfEnabled(it) }
            )
        }

        findViewById<MaterialSwitch>(R.id.switchVolcFirstCharAccel).apply {
            isChecked = prefs.volcFirstCharAccelEnabled
            installExplainedSwitch(
                context = this@AsrSettingsActivity,
                titleRes = R.string.label_volc_first_char_accel,
                offDescRes = R.string.feature_volc_first_char_accel_off_desc,
                onDescRes = R.string.feature_volc_first_char_accel_on_desc,
                preferenceKey = "volc_first_char_accel_explained",
                readPref = { prefs.volcFirstCharAccelEnabled },
                writePref = { v -> viewModel.updateVolcFirstCharAccel(v) },
                hapticFeedback = { hapticTapIfEnabled(it) }
            )
        }

        // Language selection
        setupVolcLanguageSelection()

        // Key guide link
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnVolcGetKey).setOnClickListener { v ->
            hapticTapIfEnabled(v)
            openUrlSafely("https://brycewg.notion.site/bibi-keyboard-providers-guide")
        }
    }

    private fun setupVolcLanguageSelection() {
        val langLabels = listOf(
            getString(R.string.volc_lang_auto), getString(R.string.volc_lang_en_us),
            getString(R.string.volc_lang_ja_jp), getString(R.string.volc_lang_id_id),
            getString(R.string.volc_lang_es_mx), getString(R.string.volc_lang_pt_br),
            getString(R.string.volc_lang_de_de), getString(R.string.volc_lang_fr_fr),
            getString(R.string.volc_lang_ko_kr), getString(R.string.volc_lang_fil_ph),
            getString(R.string.volc_lang_ms_my), getString(R.string.volc_lang_th_th),
            getString(R.string.volc_lang_ar_sa)
        )
        val langCodes = listOf(
            "", "en-US", "ja-JP", "id-ID", "es-MX", "pt-BR",
            "de-DE", "fr-FR", "ko-KR", "fil-PH", "ms-MY", "th-TH", "ar-SA"
        )
        val tvVolcLanguage = findViewById<TextView>(R.id.tvVolcLanguageValue)

        fun updateVolcLangSummary() {
            val idx = langCodes.indexOf(prefs.volcLanguage).coerceAtLeast(0)
            tvVolcLanguage.text = langLabels[idx]
        }

        updateVolcLangSummary()
        tvVolcLanguage.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val cur = langCodes.indexOf(prefs.volcLanguage).coerceAtLeast(0)
            showSingleChoiceDialog(R.string.label_volc_language, langLabels.toTypedArray(), cur) { which ->
                val code = langCodes.getOrNull(which) ?: ""
                viewModel.updateVolcLanguage(code)
                updateVolcLangSummary()
            }
        }
    }

    private fun setupSiliconFlowSettings() {
        // 免费服务开关与 UI 切换
        val switchSfFreeEnabled = findViewById<MaterialSwitch>(R.id.switchSfFreeEnabled)
        val groupSfFreeModel = findViewById<View>(R.id.groupSfFreeModel)
        val groupSfApiKey = findViewById<View>(R.id.groupSfApiKey)
        val tvSfFreeModelValue = findViewById<TextView>(R.id.tvSfFreeModelValue)
        val imgSfFreePoweredBy = findViewById<ImageView>(R.id.imgSfFreePoweredBy)

        // 根据深色模式设置 Powered by 图片
        val isDarkMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        imgSfFreePoweredBy.setImageResource(
            if (isDarkMode) R.drawable.powered_by_siliconflow_dark else R.drawable.powered_by_siliconflow_light
        )

        fun updateSfFreeUi(freeEnabled: Boolean) {
            groupSfFreeModel.visibility = if (freeEnabled) View.VISIBLE else View.GONE
            groupSfApiKey.visibility = if (freeEnabled) View.GONE else View.VISIBLE
        }

        // 初始化免费服务开关
        switchSfFreeEnabled.isChecked = prefs.sfFreeAsrEnabled
        updateSfFreeUi(prefs.sfFreeAsrEnabled)

        switchSfFreeEnabled.setOnCheckedChangeListener { _, isChecked ->
            prefs.sfFreeAsrEnabled = isChecked
            updateSfFreeUi(isChecked)
            hapticTapIfEnabled(switchSfFreeEnabled)
        }

        // 免费服务模型选择
        val sfFreeModels = Prefs.SF_FREE_ASR_MODELS
        val initialFreeModel = prefs.sfFreeAsrModel.ifBlank { Prefs.DEFAULT_SF_FREE_ASR_MODEL }
        if (initialFreeModel != prefs.sfFreeAsrModel) prefs.sfFreeAsrModel = initialFreeModel
        tvSfFreeModelValue.text = initialFreeModel

        tvSfFreeModelValue.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val curIdx = sfFreeModels.indexOf(prefs.sfFreeAsrModel).coerceAtLeast(0)
            showSingleChoiceDialog(
                titleResId = R.string.label_sf_model_select,
                items = sfFreeModels.toTypedArray(),
                currentIndex = curIdx
            ) { which ->
                val selected = sfFreeModels.getOrNull(which) ?: Prefs.DEFAULT_SF_FREE_ASR_MODEL
                if (selected != prefs.sfFreeAsrModel) {
                    prefs.sfFreeAsrModel = selected
                    tvSfFreeModelValue.text = selected
                }
            }
        }

        // 免费服务注册按钮
        findViewById<MaterialButton>(R.id.btnSfFreeRegister).setOnClickListener { v ->
            hapticTapIfEnabled(v)
            openUrlSafely("https://cloud.siliconflow.cn/i/g8thUcWa")
        }

        // 免费服务配置教程按钮
        findViewById<MaterialButton>(R.id.btnSfFreeGuide).setOnClickListener { v ->
            hapticTapIfEnabled(v)
            openUrlSafely("https://brycewg.notion.site/bibi-keyboard-providers-guide")
        }

        // 自有 API Key 配置
        findViewById<EditText>(R.id.etSfApiKey).apply {
            setText(prefs.sfApiKey)
            bindString { prefs.sfApiKey = it }
        }

        // 自有 API Key 模型选项
        val tvSfModelValue = findViewById<TextView>(R.id.tvSfModelValue)
        val sfModels = listOf(
            "Qwen/Qwen3-Omni-30B-A3B-Instruct",
            "Qwen/Qwen3-Omni-30B-A3B-Thinking",
            "TeleAI/TeleSpeechASR",
            "FunAudioLLM/SenseVoiceSmall"
        )
        fun isOmni(model: String): Boolean {
            return model.startsWith("Qwen/Qwen3-Omni-30B-A3B-")
        }
        fun ensureValidModel(current: String): String {
            return if (current in sfModels) current else {
                if (prefs.sfUseOmni) Prefs.DEFAULT_SF_OMNI_MODEL else Prefs.DEFAULT_SF_MODEL
            }
        }
        val initialModel = ensureValidModel(prefs.sfModel.ifBlank { Prefs.DEFAULT_SF_MODEL })
        if (initialModel != prefs.sfModel) prefs.sfModel = initialModel
        tvSfModelValue.text = initialModel

        tvSfModelValue.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val curIdx = sfModels.indexOf(prefs.sfModel).coerceAtLeast(0)
            showSingleChoiceDialog(
                titleResId = R.string.label_sf_model_select,
                items = sfModels.toTypedArray(),
                currentIndex = curIdx
            ) { which ->
                val selected = sfModels.getOrNull(which) ?: Prefs.DEFAULT_SF_MODEL
                if (selected != prefs.sfModel) {
                    prefs.sfModel = selected
                    tvSfModelValue.text = selected
                    viewModel.updateSfUseOmni(isOmni(selected))
                }
            }
        }

        findViewById<EditText>(R.id.etSfOmniPrompt).apply {
            setText(prefs.sfOmniPrompt)
            bindString { prefs.sfOmniPrompt = it }
        }

        // Key guide link
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSfGetKey).setOnClickListener { v ->
            hapticTapIfEnabled(v)
            openUrlSafely("https://brycewg.notion.site/bibi-keyboard-providers-guide")
        }
    }

    private fun setupElevenLabsSettings() {
        findViewById<EditText>(R.id.etElevenApiKey).apply {
            setText(prefs.elevenApiKey)
            bindString { prefs.elevenApiKey = it }
        }
        setupElevenLanguageSelection()

        findViewById<MaterialSwitch>(R.id.switchElevenStreaming).apply {
            isChecked = prefs.elevenStreamingEnabled
            installExplainedSwitch(
                context = this@AsrSettingsActivity,
                titleRes = R.string.label_eleven_streaming,
                offDescRes = R.string.feature_eleven_streaming_off_desc,
                onDescRes = R.string.feature_eleven_streaming_on_desc,
                preferenceKey = "eleven_streaming_explained",
                readPref = { prefs.elevenStreamingEnabled },
                writePref = { v -> viewModel.updateElevenStreaming(v) },
                hapticFeedback = { hapticTapIfEnabled(it) }
            )
        }

        // Key guide link
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnElevenGetKey).setOnClickListener { v ->
            hapticTapIfEnabled(v)
            openUrlSafely("https://brycewg.notion.site/bibi-keyboard-providers-guide")
        }
    }

    private fun setupElevenLanguageSelection() {
        val elLabels = listOf(
            getString(R.string.eleven_lang_auto), getString(R.string.eleven_lang_zh),
            getString(R.string.eleven_lang_en), getString(R.string.eleven_lang_ja),
            getString(R.string.eleven_lang_ko), getString(R.string.eleven_lang_de),
            getString(R.string.eleven_lang_fr), getString(R.string.eleven_lang_es),
            getString(R.string.eleven_lang_pt), getString(R.string.eleven_lang_ru),
            getString(R.string.eleven_lang_it)
        )
        val elCodes = listOf("", "zh", "en", "ja", "ko", "de", "fr", "es", "pt", "ru", "it")
        val tvElevenLanguage = findViewById<TextView>(R.id.tvElevenLanguageValue)

        fun updateElSummary() {
            val idx = elCodes.indexOf(prefs.elevenLanguageCode).coerceAtLeast(0)
            tvElevenLanguage.text = elLabels[idx]
        }

        updateElSummary()
        tvElevenLanguage.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val cur = elCodes.indexOf(prefs.elevenLanguageCode).coerceAtLeast(0)
            showSingleChoiceDialog(R.string.label_eleven_language, elLabels.toTypedArray(), cur) { which ->
                val code = elCodes.getOrNull(which) ?: ""
                if (code != prefs.elevenLanguageCode) prefs.elevenLanguageCode = code
                updateElSummary()
            }
        }
    }

    private fun setupOpenAISettings() {
        findViewById<EditText>(R.id.etOpenAiAsrEndpoint).apply {
            setText(prefs.oaAsrEndpoint)
            bindString { prefs.oaAsrEndpoint = it }
        }
        findViewById<EditText>(R.id.etOpenAiApiKey).apply {
            setText(prefs.oaAsrApiKey)
            bindString { prefs.oaAsrApiKey = it }
        }
        findViewById<EditText>(R.id.etOpenAiModel).apply {
            setText(prefs.oaAsrModel)
            bindString { prefs.oaAsrModel = it }
        }
        findViewById<EditText>(R.id.etOpenAiPrompt).apply {
            setText(prefs.oaAsrPrompt)
            bindString { prefs.oaAsrPrompt = it }
        }

        findViewById<MaterialSwitch>(R.id.switchOpenAiUsePrompt).apply {
            isChecked = prefs.oaAsrUsePrompt
            installExplainedSwitch(
                context = this@AsrSettingsActivity,
                titleRes = R.string.label_openai_use_prompt,
                offDescRes = R.string.feature_openai_use_prompt_off_desc,
                onDescRes = R.string.feature_openai_use_prompt_on_desc,
                preferenceKey = "openai_use_prompt_explained",
                readPref = { prefs.oaAsrUsePrompt },
                writePref = { v -> viewModel.updateOpenAiUsePrompt(v) },
                hapticFeedback = { hapticTapIfEnabled(it) }
            )
        }

        setupOpenAILanguageSelection()

        // Key guide link
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnOpenAiGetKey).setOnClickListener { v ->
            hapticTapIfEnabled(v)
            openUrlSafely("https://brycewg.notion.site/bibi-keyboard-providers-guide")
        }
    }

    private fun setupOpenAILanguageSelection() {
        val langLabels = listOf(
            getString(R.string.dash_lang_auto), getString(R.string.dash_lang_zh),
            getString(R.string.dash_lang_en), getString(R.string.dash_lang_ja),
            getString(R.string.dash_lang_de), getString(R.string.dash_lang_ko),
            getString(R.string.dash_lang_ru), getString(R.string.dash_lang_fr),
            getString(R.string.dash_lang_pt), getString(R.string.dash_lang_ar),
            getString(R.string.dash_lang_it), getString(R.string.dash_lang_es)
        )
        val langCodes = listOf("", "zh", "en", "ja", "de", "ko", "ru", "fr", "pt", "ar", "it", "es")
        val tvOpenAiLanguage = findViewById<TextView>(R.id.tvOpenAiLanguageValue)

        fun updateOaLangSummary() {
            val idx = langCodes.indexOf(prefs.oaAsrLanguage).coerceAtLeast(0)
            tvOpenAiLanguage.text = langLabels[idx]
        }

        updateOaLangSummary()
        tvOpenAiLanguage.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val cur = langCodes.indexOf(prefs.oaAsrLanguage).coerceAtLeast(0)
            showSingleChoiceDialog(R.string.label_openai_language, langLabels.toTypedArray(), cur) { which ->
                val code = langCodes.getOrNull(which) ?: ""
                if (code != prefs.oaAsrLanguage) prefs.oaAsrLanguage = code
                updateOaLangSummary()
            }
        }
    }

    private fun setupDashScopeSettings() {
        findViewById<EditText>(R.id.etDashApiKey).apply {
            setText(prefs.dashApiKey)
            bindString { prefs.dashApiKey = it }
        }
        findViewById<EditText>(R.id.etDashPrompt).apply {
            setText(prefs.dashPrompt)
            bindString { prefs.dashPrompt = it }
        }

        // Fun-ASR 语义断句开关
        findViewById<MaterialSwitch>(R.id.switchDashFunAsrSemanticPunct).apply {
            isChecked = prefs.dashFunAsrSemanticPunctEnabled
            installExplainedSwitch(
                context = this@AsrSettingsActivity,
                titleRes = R.string.label_dash_funasr_semantic_punct,
                offDescRes = R.string.feature_dash_funasr_semantic_punct_off_desc,
                onDescRes = R.string.feature_dash_funasr_semantic_punct_on_desc,
                preferenceKey = "dash_funasr_semantic_punct_explained",
                readPref = { prefs.dashFunAsrSemanticPunctEnabled },
                writePref = { v -> prefs.dashFunAsrSemanticPunctEnabled = v },
                hapticFeedback = { hapticTapIfEnabled(it) }
            )
        }

        setupDashModelSelection()
        setupDashLanguageSelection()
        setupDashRegionSelection()

        // Key guide link
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDashGetKey).setOnClickListener { v ->
            hapticTapIfEnabled(v)
            openUrlSafely("https://brycewg.notion.site/bibi-keyboard-providers-guide")
        }
    }

    private fun setupDashModelSelection() {
        val modelLabels = listOf(
            getString(R.string.dash_model_qwen_file),
            getString(R.string.dash_model_qwen_realtime),
            getString(R.string.dash_model_fun_realtime)
        )
        val modelValues = listOf(
            Prefs.DEFAULT_DASH_MODEL,
            Prefs.DASH_MODEL_QWEN3_REALTIME,
            Prefs.DASH_MODEL_FUN_ASR_REALTIME
        )
        val tvDashModel = findViewById<TextView>(R.id.tvDashModelValue)

        fun normalizeModel(model: String): String {
            return model.trim().ifBlank { Prefs.DEFAULT_DASH_MODEL }
        }

        fun updateModelSummary() {
            val cur = normalizeModel(prefs.dashAsrModel)
            val idx = modelValues.indexOf(cur).coerceAtLeast(0)
            tvDashModel.text = modelLabels[idx]
            updateDashPromptVisibility(cur)
        }

        updateModelSummary()
        tvDashModel.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val cur = normalizeModel(prefs.dashAsrModel)
            val curIdx = modelValues.indexOf(cur).coerceAtLeast(0)
            showSingleChoiceDialog(R.string.label_dash_model, modelLabels.toTypedArray(), curIdx) { which ->
                val value = modelValues.getOrNull(which) ?: Prefs.DEFAULT_DASH_MODEL
                if (value != prefs.dashAsrModel) prefs.dashAsrModel = value
                updateModelSummary()
            }
        }
    }

    private fun updateDashPromptVisibility(model: String = prefs.dashAsrModel) {
        val isFunAsr = model.startsWith("fun-asr", ignoreCase = true)

        // Prompt 输入框：仅非 Fun-ASR 模型显示
        val til = findViewById<View>(R.id.tilDashPrompt)
        val promptVis = if (!isFunAsr) View.VISIBLE else View.GONE
        if (til.visibility != promptVis) til.visibility = promptVis

        // 语义断句开关：仅 Fun-ASR 模型显示
        val switchSemanticPunct = findViewById<View>(R.id.switchDashFunAsrSemanticPunct)
        val semanticVis = if (isFunAsr) View.VISIBLE else View.GONE
        if (switchSemanticPunct.visibility != semanticVis) switchSemanticPunct.visibility = semanticVis
    }

    private fun setupDashLanguageSelection() {
        val langLabels = listOf(
            getString(R.string.dash_lang_auto), getString(R.string.dash_lang_zh),
            getString(R.string.dash_lang_en), getString(R.string.dash_lang_ja),
            getString(R.string.dash_lang_de), getString(R.string.dash_lang_ko),
            getString(R.string.dash_lang_ru), getString(R.string.dash_lang_fr),
            getString(R.string.dash_lang_pt), getString(R.string.dash_lang_ar),
            getString(R.string.dash_lang_it), getString(R.string.dash_lang_es)
        )
        val langCodes = listOf("", "zh", "en", "ja", "de", "ko", "ru", "fr", "pt", "ar", "it", "es")
        val tvDashLanguage = findViewById<TextView>(R.id.tvDashLanguageValue)

        fun updateDashLangSummary() {
            val idx = langCodes.indexOf(prefs.dashLanguage).coerceAtLeast(0)
            tvDashLanguage.text = langLabels[idx]
        }

        updateDashLangSummary()
        tvDashLanguage.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val cur = langCodes.indexOf(prefs.dashLanguage).coerceAtLeast(0)
            showSingleChoiceDialog(R.string.label_dash_language, langLabels.toTypedArray(), cur) { which ->
                val code = langCodes.getOrNull(which) ?: ""
                if (code != prefs.dashLanguage) prefs.dashLanguage = code
                updateDashLangSummary()
            }
        }
    }

    private fun setupDashRegionSelection() {
        val regionLabels = listOf(
            getString(R.string.dash_region_cn),
            getString(R.string.dash_region_intl)
        )
        val regionValues = listOf("cn", "intl")
        val tvDashRegion = findViewById<TextView>(R.id.tvDashRegionValue)

        fun updateRegionSummary() {
            val idx = regionValues.indexOf(prefs.dashRegion.ifBlank { "cn" }).coerceAtLeast(0)
            tvDashRegion.text = regionLabels[idx]
        }

        updateRegionSummary()
        tvDashRegion.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val cur = regionValues.indexOf(prefs.dashRegion.ifBlank { "cn" }).coerceAtLeast(0)
            showSingleChoiceDialog(R.string.label_dash_region, regionLabels.toTypedArray(), cur) { which ->
                val value = regionValues.getOrNull(which) ?: "cn"
                if (value != prefs.dashRegion) prefs.dashRegion = value
                updateRegionSummary()
            }
        }
    }

    private fun setupGeminiSettings() {
        findViewById<EditText>(R.id.etGeminiEndpoint).apply {
            setText(prefs.gemEndpoint)
            bindString { prefs.gemEndpoint = it }
        }
        findViewById<EditText>(R.id.etGeminiApiKey).apply {
            setText(prefs.gemApiKey)
            bindString { prefs.gemApiKey = it }
        }
        findViewById<EditText>(R.id.etGeminiModel).apply {
            setText(prefs.gemModel)
            bindString { prefs.gemModel = it }
        }
        findViewById<EditText>(R.id.etGeminiPrompt).apply {
            setText(prefs.gemPrompt)
            bindString { prefs.gemPrompt = it }
        }

        findViewById<MaterialSwitch>(R.id.switchGeminiDisableThinking).apply {
            isChecked = prefs.geminiDisableThinking
            installExplainedSwitch(
                context = this@AsrSettingsActivity,
                titleRes = R.string.label_gemini_disable_thinking,
                offDescRes = R.string.feature_gemini_disable_thinking_off_desc,
                onDescRes = R.string.feature_gemini_disable_thinking_on_desc,
                preferenceKey = "gemini_disable_thinking_explained",
                readPref = { prefs.geminiDisableThinking },
                writePref = { v -> prefs.geminiDisableThinking = v },
                hapticFeedback = { hapticTapIfEnabled(it) }
            )
        }

        // Key guide link
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnGeminiGetKey).setOnClickListener { v ->
            hapticTapIfEnabled(v)
            openUrlSafely("https://brycewg.notion.site/bibi-keyboard-providers-guide")
        }
    }

    private fun setupSonioxSettings() {
        findViewById<EditText>(R.id.etSonioxApiKey).apply {
            setText(prefs.sonioxApiKey)
            bindString { prefs.sonioxApiKey = it }
        }

        findViewById<MaterialSwitch>(R.id.switchSonioxStreaming).apply {
            isChecked = prefs.sonioxStreamingEnabled
            installExplainedSwitch(
                context = this@AsrSettingsActivity,
                titleRes = R.string.label_soniox_streaming,
                offDescRes = R.string.feature_soniox_streaming_off_desc,
                onDescRes = R.string.feature_soniox_streaming_on_desc,
                preferenceKey = "soniox_streaming_explained",
                readPref = { prefs.sonioxStreamingEnabled },
                writePref = { v -> viewModel.updateSonioxStreaming(v) },
                hapticFeedback = { hapticTapIfEnabled(it) }
            )
        }

        setupSonioxLanguageSelection()

        // 语言严格限制开关
        findViewById<MaterialSwitch>(R.id.switchSonioxLanguageStrict).apply {
            isChecked = prefs.sonioxLanguageHintsStrict
            installExplainedSwitch(
                context = this@AsrSettingsActivity,
                titleRes = R.string.label_soniox_language_strict,
                offDescRes = R.string.feature_soniox_language_strict_off_desc,
                onDescRes = R.string.feature_soniox_language_strict_on_desc,
                preferenceKey = "soniox_language_strict_explained",
                readPref = { prefs.sonioxLanguageHintsStrict },
                writePref = { v -> prefs.sonioxLanguageHintsStrict = v },
                hapticFeedback = { hapticTapIfEnabled(it) }
            )
        }

        // Key guide link
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSonioxGetKey).setOnClickListener { v ->
            hapticTapIfEnabled(v)
            openUrlSafely("https://brycewg.notion.site/bibi-keyboard-providers-guide")
        }
    }

    private fun setupSonioxLanguageSelection() {
        val sonioxLangLabels = listOf(
            getString(R.string.soniox_lang_auto), getString(R.string.soniox_lang_en),
            getString(R.string.soniox_lang_zh), getString(R.string.soniox_lang_ja),
            getString(R.string.soniox_lang_ko), getString(R.string.soniox_lang_es),
            getString(R.string.soniox_lang_pt), getString(R.string.soniox_lang_de),
            getString(R.string.soniox_lang_fr), getString(R.string.soniox_lang_id),
            getString(R.string.soniox_lang_ru), getString(R.string.soniox_lang_ar),
            getString(R.string.soniox_lang_hi), getString(R.string.soniox_lang_vi),
            getString(R.string.soniox_lang_th), getString(R.string.soniox_lang_ms),
            getString(R.string.soniox_lang_fil)
        )
        val sonioxLangCodes = listOf(
            "", "en", "zh", "ja", "ko", "es", "pt", "de",
            "fr", "id", "ru", "ar", "hi", "vi", "th", "ms", "fil"
        )
        val tvSonioxLanguage = findViewById<TextView>(R.id.tvSonioxLanguageValue)

        fun updateSonioxLangSummary() {
            val selected = prefs.getSonioxLanguages()
            if (selected.isEmpty()) {
                tvSonioxLanguage.text = getString(R.string.soniox_lang_auto)
                return
            }
            val names = selected.mapNotNull { code ->
                val idx = sonioxLangCodes.indexOf(code)
                if (idx >= 0) sonioxLangLabels[idx] else null
            }
            tvSonioxLanguage.text = if (names.isEmpty()) {
                getString(R.string.soniox_lang_auto)
            } else {
                names.joinToString(separator = "、")
            }
        }

        updateSonioxLangSummary()
        tvSonioxLanguage.setOnClickListener {
            val saved = prefs.getSonioxLanguages()
            val checked = BooleanArray(sonioxLangCodes.size) { idx ->
                if (idx == 0) saved.isEmpty() else sonioxLangCodes[idx] in saved
            }
            val builder = androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.label_soniox_language)
                .setMultiChoiceItems(sonioxLangLabels.toTypedArray(), checked) { _, which, isChecked ->
                    if (which == 0) {
                        if (isChecked) {
                            for (i in 1 until checked.size) checked[i] = false
                        }
                    } else if (isChecked) {
                        checked[0] = false
                    }
                }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val codes = mutableListOf<String>()
                    for (i in checked.indices) {
                        if (checked[i]) {
                            val code = sonioxLangCodes[i]
                            if (code.isNotEmpty()) codes.add(code)
                        }
                    }
                    viewModel.updateSonioxLanguages(codes)
                    updateSonioxLangSummary()
                }
                .setNegativeButton(R.string.btn_cancel, null)
            builder.show()
        }
    }

    private fun setupZhipuSettings() {
        findViewById<EditText>(R.id.etZhipuApiKey).apply {
            setText(prefs.zhipuApiKey)
            bindString { prefs.zhipuApiKey = it }
        }

        findViewById<Slider>(R.id.sliderZhipuTemperature).apply {
            value = prefs.zhipuTemperature.coerceIn(0f, 1f)
            addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    prefs.zhipuTemperature = value.coerceIn(0f, 1f)
                }
            }
        }

        // Prompt for context
        findViewById<EditText>(R.id.etZhipuPrompt).apply {
            setText(prefs.zhipuPrompt)
            bindString { prefs.zhipuPrompt = it }
        }

        // Key guide link
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnZhipuGetKey).setOnClickListener { v ->
            hapticTapIfEnabled(v)
            openUrlSafely("https://bigmodel.cn/usercenter/proj-mgmt/apikeys")
        }
    }

    private fun setupSenseVoiceSettings() {
        // Model variant
        setupSvModelVariantSelection()

        // Language
        setupSvLanguageSelection()

        // Thread count
        findViewById<Slider>(R.id.sliderSvThreads).apply {
            value = prefs.svNumThreads.coerceIn(1, 8).toFloat()
            addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    val v = value.toInt().coerceIn(1, 8)
                    if (v != prefs.svNumThreads) {
                        viewModel.updateSvNumThreads(v)
                    }
                }
            }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) = hapticTapIfEnabled(slider)
                override fun onStopTrackingTouch(slider: Slider) = hapticTapIfEnabled(slider)
            })
        }

        // Switches with explainer dialogs
        findViewById<MaterialSwitch>(R.id.switchSvUseItn).apply {
            isChecked = prefs.svUseItn
            installExplainedSwitch(
                context = this@AsrSettingsActivity,
                titleRes = R.string.label_sv_use_itn,
                offDescRes = R.string.feature_sv_use_itn_off_desc,
                onDescRes = R.string.feature_sv_use_itn_on_desc,
                preferenceKey = "sv_use_itn_explained",
                readPref = { prefs.svUseItn },
                writePref = { v -> viewModel.updateSvUseItn(v) },
                hapticFeedback = { hapticTapIfEnabled(it) }
            )
        }

        findViewById<MaterialSwitch>(R.id.switchSvPreload).apply {
            isChecked = prefs.svPreloadEnabled
            installExplainedSwitch(
                context = this@AsrSettingsActivity,
                titleRes = R.string.label_sv_preload,
                offDescRes = R.string.feature_sv_preload_off_desc,
                onDescRes = R.string.feature_sv_preload_on_desc,
                preferenceKey = "sv_preload_explained",
                readPref = { prefs.svPreloadEnabled },
                writePref = { v -> viewModel.updateSvPreload(v) },
                hapticFeedback = { hapticTapIfEnabled(it) }
            )
        }

        findViewById<MaterialSwitch>(R.id.switchSvPseudoStream).apply {
            isChecked = prefs.svPseudoStreamEnabled
            installExplainedSwitch(
                context = this@AsrSettingsActivity,
                titleRes = R.string.label_sv_pseudo_stream,
                offDescRes = R.string.feature_sv_pseudo_stream_off_desc,
                onDescRes = R.string.feature_sv_pseudo_stream_on_desc,
                preferenceKey = "sv_pseudo_stream_explained",
                readPref = { prefs.svPseudoStreamEnabled },
                writePref = { v -> viewModel.updateSvPseudoStream(v) },
                hapticFeedback = { hapticTapIfEnabled(it) }
            )
        }

        // Keep alive
        setupSvKeepAliveSelection()

        // Download/Clear buttons
        setupSvDownloadButtons()

        // 通用标点模型（FunASR Nano 也共用同一套模型）
        setupPunctDownloadButtons(
            btnDownloadId = R.id.btnSvDownloadPunct,
            btnImportId = R.id.btnSvImportPunct,
            btnClearId = R.id.btnSvClearPunct,
            statusTextId = R.id.tvSvPunctStatus
        )
    }

    private fun setupParaformerSettings() {
        // 变体选择（四种）
        val tvPfVariant = findViewById<TextView>(R.id.tvPfModelVariantValue)
        val btnPfDownload = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPfDownloadModel)
        val variantLabels = listOf(
            getString(R.string.pf_variant_bilingual_int8),
            getString(R.string.pf_variant_bilingual_fp32),
            getString(R.string.pf_variant_trilingual_int8),
            getString(R.string.pf_variant_trilingual_fp32)
        )
        val variantCodes = listOf(
            "bilingual-int8", "bilingual-fp32", "trilingual-int8", "trilingual-fp32"
        )
        fun updateVariantSummary() {
            val idx = variantCodes.indexOf(prefs.pfModelVariant).coerceAtLeast(0)
            tvPfVariant.text = variantLabels[idx]
        }
        updateVariantSummary()
        tvPfVariant.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val cur = variantCodes.indexOf(prefs.pfModelVariant).coerceAtLeast(0)
            showSingleChoiceDialog(R.string.label_pf_model_variant, variantLabels.toTypedArray(), cur) { which ->
                val code = variantCodes.getOrNull(which) ?: "bilingual-int8"
                if (code != prefs.pfModelVariant) {
                    viewModel.updatePfModelVariant(code)
                }
                updateVariantSummary()
                updatePfDownloadUiVisibility()
            }
        }

        // 保留时长
        val tvKeep = findViewById<TextView>(R.id.tvPfKeepAliveValue)
        fun updateKeepAliveSummary() {
            val values = listOf(0, 5, 15, 30, -1)
            val labels = listOf(
                getString(R.string.sv_keep_alive_immediate),
                getString(R.string.sv_keep_alive_5m),
                getString(R.string.sv_keep_alive_15m),
                getString(R.string.sv_keep_alive_30m),
                getString(R.string.sv_keep_alive_always)
            )
            val idx = values.indexOf(prefs.pfKeepAliveMinutes).let { if (it >= 0) it else values.size - 1 }
            tvKeep.text = labels[idx]
        }
        updateKeepAliveSummary()
        tvKeep.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val labels = arrayOf(
                getString(R.string.sv_keep_alive_immediate),
                getString(R.string.sv_keep_alive_5m),
                getString(R.string.sv_keep_alive_15m),
                getString(R.string.sv_keep_alive_30m),
                getString(R.string.sv_keep_alive_always)
            )
            val values = listOf(0, 5, 15, 30, -1)
            val cur = values.indexOf(prefs.pfKeepAliveMinutes).let { if (it >= 0) it else values.size - 1 }
            showSingleChoiceDialog(R.string.label_pf_keep_alive, labels, cur) { which ->
                val vv = values.getOrNull(which) ?: -1
                if (vv != prefs.pfKeepAliveMinutes) {
                    prefs.pfKeepAliveMinutes = vv
                }
                updateKeepAliveSummary()
            }
        }

        // 线程数滑块（1-8）
        findViewById<com.google.android.material.slider.Slider>(R.id.sliderPfThreads).apply {
            value = prefs.pfNumThreads.coerceIn(1, 8).toFloat()
            addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    val v = value.toInt().coerceIn(1, 8)
                    if (v != prefs.pfNumThreads) {
                        viewModel.updatePfNumThreads(v)
                    }
                }
            }
            addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) = hapticTapIfEnabled(slider)
                override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) = hapticTapIfEnabled(slider)
            })
        }

        // 首次显示时加载模型
        findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchPfPreload).apply {
            isChecked = prefs.pfPreloadEnabled
            installExplainedSwitch(
                context = this@AsrSettingsActivity,
                titleRes = R.string.label_pf_preload,
                offDescRes = R.string.feature_pf_preload_off_desc,
                onDescRes = R.string.feature_pf_preload_on_desc,
                preferenceKey = "pf_preload_explained",
                readPref = { prefs.pfPreloadEnabled },
                writePref = { v -> viewModel.updatePfPreload(v) },
                hapticFeedback = { hapticTapIfEnabled(it) }
            )
        }

        // ITN 开关
        findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchPfItn).apply {
            isChecked = try { prefs.pfUseItn } catch (_: Throwable) { false }
            installExplainedSwitch(
                context = this@AsrSettingsActivity,
                titleRes = R.string.label_pf_use_itn,
                offDescRes = R.string.feature_pf_use_itn_off_desc,
                onDescRes = R.string.feature_pf_use_itn_on_desc,
                preferenceKey = "pf_use_itn_explained",
                readPref = { try { prefs.pfUseItn } catch (_: Throwable) { false } },
                writePref = { v -> viewModel.updatePfUseItn(v) },
                hapticFeedback = { hapticTapIfEnabled(it) }
            )
        }

        // 下载/清理
        setupPfDownloadButtons()

        // 通用标点模型（TeleSpeech / Paraformer 共用）
        setupPunctDownloadButtons(
            btnDownloadId = R.id.btnPfDownloadPunct,
            btnImportId = R.id.btnPfImportPunct,
            btnClearId = R.id.btnPfClearPunct,
            statusTextId = R.id.tvPfPunctStatus
        )
    }

    private fun setupPfDownloadButtons() {
        val btnDl = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPfDownloadModel)
        val btnImport = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPfImportModel)
        val btnClear = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPfClearModel)
        val tvStatus = findViewById<TextView>(R.id.tvPfDownloadStatus)

        btnImport.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            pfModelFilePicker.launch("application/zip")
        }

        btnDl.setOnClickListener { v ->
            v.isEnabled = false
            tvStatus.text = ""
            val sources = arrayOf(
                getString(R.string.download_source_github_official),
                getString(R.string.download_source_mirror_ghproxy),
                getString(R.string.download_source_mirror_gitmirror),
                getString(R.string.download_source_mirror_gh_proxynet)
            )
            val variant = prefs.pfModelVariant
            val isTri = variant.startsWith("trilingual")
            val urlOfficial = if (isTri) {
                "https://github.com/BryceWG/BiBi-Keyboard/releases/download/models/sherpa-onnx-streaming-paraformer-trilingual-zh-cantonese-en.zip"
            } else {
                "https://github.com/BryceWG/BiBi-Keyboard/releases/download/models/sherpa-onnx-streaming-paraformer-bilingual-zh-en.zip"
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.download_source_title)
                .setItems(sources) { dlg, which ->
                    dlg.dismiss()
                    val url = when (which) {
                        1 -> "https://ghproxy.net/$urlOfficial"
                        2 -> "https://hub.gitmirror.com/$urlOfficial"
                        3 -> "https://gh-proxy.net/$urlOfficial"
                        else -> urlOfficial
                    }
                    try {
                        // 统一下载服务
                        ModelDownloadService.startDownload(this, url, variant, "paraformer")
                        tvStatus.text = getString(R.string.pf_download_started_in_bg)
                    } catch (e: Throwable) {
                        android.util.Log.e(TAG, "Failed to start paraformer download", e)
                        tvStatus.text = getString(R.string.pf_download_status_failed)
                    } finally { v.isEnabled = true }
                }
                .setOnDismissListener { v.isEnabled = true }
                .show()
        }

        btnClear.setOnClickListener { v ->
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.pf_clear_confirm_title)
                .setMessage(R.string.pf_clear_confirm_message)
                .setPositiveButton(android.R.string.ok) { d, _ ->
                    d.dismiss()
                    v.isEnabled = false
                    lifecycleScope.launch {
                        try {
                            val base = getExternalFilesDir(null) ?: filesDir
                            val root = java.io.File(base, "paraformer")
                            val group = if (prefs.pfModelVariant.startsWith("trilingual")) "trilingual" else "bilingual"
                            val outDir = java.io.File(root, group)
                            if (outDir.exists()) withContext(Dispatchers.IO) { outDir.deleteRecursively() }
                            try { com.brycewg.asrkb.asr.unloadParaformerRecognizer() } catch (_: Throwable) { }
                            tvStatus.text = getString(R.string.pf_clear_done)
                        } catch (e: Throwable) {
                            android.util.Log.e(TAG, "Failed to clear paraformer model", e)
                            tvStatus.text = getString(R.string.pf_clear_failed)
                        } finally {
                            v.isEnabled = true
                            updatePfDownloadUiVisibility()
                        }
                    }
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .create()
                .show()
        }
    }

    private fun updatePfDownloadUiVisibility() {
        val ready = viewModel.checkPfModelDownloaded(this)
        val btn = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPfDownloadModel)
        val btnImport = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPfImportModel)
        val btnClear = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPfClearModel)
        val tv = findViewById<TextView>(R.id.tvPfDownloadStatus)

        // 模型已安装时：隐藏下载和导入按钮，显示清理按钮
        // 模型未安装时：显示下载和导入按钮，隐藏清理按钮
        btn.visibility = if (ready) View.GONE else View.VISIBLE
        btnImport.visibility = if (ready) View.GONE else View.VISIBLE
        btnClear.visibility = if (ready) View.VISIBLE else View.GONE

        if (ready && tv.text.isNullOrBlank()) {
            tv.text = getString(R.string.pf_download_status_done)
        }
    }

    private fun setupSvModelVariantSelection() {
        val variantLabels = listOf(
            getString(R.string.sv_model_nano_int8),
            getString(R.string.sv_model_nano_full),
            getString(R.string.sv_model_small_int8),
            getString(R.string.sv_model_small_full)
        )
        val variantCodes = listOf("nano-int8", "nano-full", "small-int8", "small-full")
        val tvSvModelVariant = findViewById<TextView>(R.id.tvSvModelVariantValue)
        val btnSvDownload = findViewById<MaterialButton>(R.id.btnSvDownloadModel)

        fun updateVariantSummary() {
            val idx = variantCodes.indexOf(prefs.svModelVariant).coerceAtLeast(0)
            tvSvModelVariant.text = variantLabels[idx]
        }

        fun updateDownloadButtonText() {
            // 统一使用简洁的按钮文本，不再显示详细信息
            btnSvDownload.text = getString(R.string.btn_sv_download_model)
        }

        updateVariantSummary()
        updateDownloadButtonText()
        updateSvLanguageVisibility()

        tvSvModelVariant.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val cur = variantCodes.indexOf(prefs.svModelVariant).coerceAtLeast(0)
            showSingleChoiceDialog(R.string.label_sv_model_variant, variantLabels.toTypedArray(), cur) { which ->
                val code = variantCodes.getOrNull(which) ?: "nano-int8"
                if (code != prefs.svModelVariant) {
                    viewModel.updateSvModelVariant(code)
                }
                updateVariantSummary()
                updateDownloadButtonText()
                updateSvLanguageVisibility()
                updateSvDownloadUiVisibility()
            }
        }
    }

    private fun setupSvLanguageSelection() {
        val labels = listOf(
            getString(R.string.sv_lang_auto), getString(R.string.sv_lang_zh),
            getString(R.string.sv_lang_en), getString(R.string.sv_lang_ja),
            getString(R.string.sv_lang_ko), getString(R.string.sv_lang_yue)
        )
        val codes = listOf("auto", "zh", "en", "ja", "ko", "yue")
        val tvSvLanguage = findViewById<TextView>(R.id.tvSvLanguageValue)

        fun updateSvLangSummary() {
            val idx = codes.indexOf(prefs.svLanguage).coerceAtLeast(0)
            tvSvLanguage.text = labels[idx]
        }

        updateSvLangSummary()
        updateSvLanguageVisibility()
        tvSvLanguage.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val cur = codes.indexOf(prefs.svLanguage).coerceAtLeast(0)
            showSingleChoiceDialog(R.string.label_sv_language, labels.toTypedArray(), cur) { which ->
                val code = codes.getOrNull(which) ?: "auto"
                if (code != prefs.svLanguage) {
                    viewModel.updateSvLanguage(code)
                }
                updateSvLangSummary()
            }
        }
    }

    private fun updateSvLanguageVisibility() {
        val label = findViewById<TextView?>(R.id.tvSvLanguageLabel)
        val value = findViewById<TextView?>(R.id.tvSvLanguageValue)
        if (label == null || value == null) return
        val visible = !prefs.svModelVariant.startsWith("nano-")
        val visibility = if (visible) View.VISIBLE else View.GONE
        label.visibility = visibility
        value.visibility = visibility
    }

    private fun setupSvKeepAliveSelection() {
        val labels = listOf(
            getString(R.string.sv_keep_alive_immediate),
            getString(R.string.sv_keep_alive_5m),
            getString(R.string.sv_keep_alive_15m),
            getString(R.string.sv_keep_alive_30m),
            getString(R.string.sv_keep_alive_always)
        )
        val values = listOf(0, 5, 15, 30, -1)
        val tvSvKeepAlive = findViewById<TextView>(R.id.tvSvKeepAliveValue)

        fun updateKeepAliveSummary() {
            val idx = values.indexOf(prefs.svKeepAliveMinutes).let { if (it >= 0) it else values.size - 1 }
            tvSvKeepAlive.text = labels[idx]
        }

        updateKeepAliveSummary()
        tvSvKeepAlive.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val cur = values.indexOf(prefs.svKeepAliveMinutes).let { if (it >= 0) it else values.size - 1 }
            showSingleChoiceDialog(R.string.label_sv_keep_alive, labels.toTypedArray(), cur) { which ->
                val vv = values.getOrNull(which) ?: -1
                if (vv != prefs.svKeepAliveMinutes) {
                    viewModel.updateSvKeepAlive(vv)
                }
                updateKeepAliveSummary()
            }
        }
    }

    private fun setupSvDownloadButtons() {
        val btnSvDownload = findViewById<MaterialButton>(R.id.btnSvDownloadModel)
        val btnSvImport = findViewById<MaterialButton>(R.id.btnSvImportModel)
        val btnSvClear = findViewById<MaterialButton>(R.id.btnSvClearModel)
        val tvSvDownloadStatus = findViewById<TextView>(R.id.tvSvDownloadStatus)

        btnSvDownload.setOnClickListener { v ->
            v.isEnabled = false
            tvSvDownloadStatus.text = ""

            val sources = arrayOf(
                getString(R.string.download_source_github_official),
                getString(R.string.download_source_mirror_ghproxy),
                getString(R.string.download_source_mirror_gitmirror),
                getString(R.string.download_source_mirror_gh_proxynet)
            )
            val variant = prefs.svModelVariant
            val urlOfficial = when (variant) {
                "small-full" -> "https://github.com/BryceWG/BiBi-Keyboard/releases/download/models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.zip"
                "small-int8" -> "https://github.com/BryceWG/BiBi-Keyboard/releases/download/models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.zip"
                "nano-full" -> "https://github.com/BryceWG/BiBi-Keyboard/releases/download/models/sherpa-onnx-sense-voice-funasr-nano-2025-12-17.zip"
                else -> "https://github.com/BryceWG/BiBi-Keyboard/releases/download/models/sherpa-onnx-sense-voice-funasr-nano-int8-2025-12-17.zip"
            }

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.download_source_title)
                .setItems(sources) { dlg, which ->
                    dlg.dismiss()
                    val url = when (which) {
                        1 -> "https://ghproxy.net/$urlOfficial"
                        2 -> "https://hub.gitmirror.com/$urlOfficial"
                        3 -> "https://gh-proxy.net/$urlOfficial"
                        else -> urlOfficial
                    }
                    try {
                        ModelDownloadService.startDownload(this, url, variant)
                        tvSvDownloadStatus.text = getString(R.string.sv_download_started_in_bg)
                    } catch (e: Throwable) {
                        android.util.Log.e(TAG, "Failed to start model download", e)
                        tvSvDownloadStatus.text = getString(R.string.sv_download_status_failed)
                    } finally {
                        v.isEnabled = true
                    }
                }
                .setOnDismissListener { v.isEnabled = true }
                .show()
        }

        btnSvClear.setOnClickListener { v ->
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.sv_clear_confirm_title)
                .setMessage(R.string.sv_clear_confirm_message)
                .setPositiveButton(android.R.string.ok) { d, _ ->
                    d.dismiss()
                    v.isEnabled = false
                    lifecycleScope.launch {
                        try {
                            val base = getExternalFilesDir(null) ?: filesDir
                            val variant = prefs.svModelVariant
                            val outDirRoot = File(base, "sensevoice")
                            val outDir = when (variant) {
                                "small-full" -> File(outDirRoot, "small-full")
                                "nano-full" -> File(outDirRoot, "nano-full")
                                "nano-int8" -> File(outDirRoot, "nano-int8")
                                else -> File(outDirRoot, "small-int8")
                            }
                            if (outDir.exists()) {
                                withContext(Dispatchers.IO) { outDir.deleteRecursively() }
                            }
                            try {
                                com.brycewg.asrkb.asr.unloadSenseVoiceRecognizer()
                            } catch (e: Throwable) {
                                android.util.Log.e(TAG, "Failed to unload SenseVoice recognizer", e)
                            }
                            tvSvDownloadStatus.text = getString(R.string.sv_clear_done)
                        } catch (e: Throwable) {
                            android.util.Log.e(TAG, "Failed to clear model", e)
                            tvSvDownloadStatus.text = getString(R.string.sv_clear_failed)
                        } finally {
                            v.isEnabled = true
                            updateSvDownloadUiVisibility()
                        }
                    }
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .create()
                .show()
        }

        btnSvImport.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            modelFilePicker.launch("application/zip")
        }
    }

    private fun setupTsDownloadButtons() {
        val btnDl = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnTsDownloadModel)
        val btnImport = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnTsImportModel)
        val btnClear = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnTsClearModel)
        val tvStatus = findViewById<TextView>(R.id.tvTsDownloadStatus)

        btnImport.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            tsModelFilePicker.launch("application/zip")
        }

        btnDl.setOnClickListener { v ->
            v.isEnabled = false
            tvStatus.text = ""
            val sources = arrayOf(
                getString(R.string.download_source_github_official),
                getString(R.string.download_source_mirror_ghproxy),
                getString(R.string.download_source_mirror_gitmirror),
                getString(R.string.download_source_mirror_gh_proxynet)
            )
            val variant = prefs.tsModelVariant
            // TeleSpeech：int8/fp32 使用 GitHub 发布的官方 ZIP
            val urlOfficial = when (variant) {
                "full" -> "https://github.com/BryceWG/BiBi-Keyboard/releases/download/models/sherpa-onnx-telespeech-ctc-zh-2024-06-04.zip"
                else -> "https://github.com/BryceWG/BiBi-Keyboard/releases/download/models/sherpa-onnx-telespeech-ctc-int8-zh-2024-06-04.zip"
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.download_source_title)
                .setItems(sources) { dlg, which ->
                    dlg.dismiss()
                    val url = when (which) {
                        1 -> "https://ghproxy.net/$urlOfficial"
                        2 -> "https://hub.gitmirror.com/$urlOfficial"
                        3 -> "https://gh-proxy.net/$urlOfficial"
                        else -> urlOfficial
                    }
                    try {
                        ModelDownloadService.startDownload(this, url, variant, "telespeech")
                        tvStatus.text = getString(R.string.ts_download_started_in_bg)
                    } catch (e: Throwable) {
                        android.util.Log.e(TAG, "Failed to start telespeech model download", e)
                        tvStatus.text = getString(R.string.ts_download_status_failed)
                    } finally {
                        v.isEnabled = true
                    }
                }
                .setOnDismissListener { v.isEnabled = true }
                .show()
        }

        btnClear.setOnClickListener { v ->
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.ts_clear_confirm_title)
                .setMessage(R.string.ts_clear_confirm_message)
                .setPositiveButton(android.R.string.ok) { d, _ ->
                    d.dismiss()
                    v.isEnabled = false
                    lifecycleScope.launch {
                        try {
                            val base = getExternalFilesDir(null) ?: filesDir
                            val variant = prefs.tsModelVariant
                            val outDirRoot = File(base, "telespeech")
                            val outDir = if (variant == "full") {
                                File(outDirRoot, "full")
                            } else {
                                File(outDirRoot, "int8")
                            }
                            if (outDir.exists()) {
                                withContext(Dispatchers.IO) { outDir.deleteRecursively() }
                            }
                            try {
                                com.brycewg.asrkb.asr.unloadTelespeechRecognizer()
                            } catch (e: Throwable) {
                                android.util.Log.e(TAG, "Failed to unload TeleSpeech recognizer", e)
                            }
                            tvStatus.text = getString(R.string.ts_clear_done)
                        } catch (e: Throwable) {
                            android.util.Log.e(TAG, "Failed to clear telespeech model", e)
                            tvStatus.text = getString(R.string.ts_clear_failed)
                        } finally {
                            v.isEnabled = true
                            updateTsDownloadUiVisibility()
                        }
                    }
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .create()
                .show()
        }
    }

    /**
     * 通用标点模型下载/导入/清理（本地 sherpa-onnx 引擎共用：FunASR Nano / TeleSpeech / Paraformer）
     */
    private fun setupPunctDownloadButtons(
        btnDownloadId: Int,
        btnImportId: Int,
        btnClearId: Int,
        statusTextId: Int
    ) {
        val btnDl = findViewById<MaterialButton?>(btnDownloadId) ?: return
        val btnImport = findViewById<MaterialButton?>(btnImportId) ?: return
        val btnClear = findViewById<MaterialButton?>(btnClearId) ?: return
        val tvStatus = findViewById<TextView?>(statusTextId) ?: return

        val variant = "ct-zh-en-int8"
        val urlOfficial =
            "https://github.com/BryceWG/BiBi-Keyboard/releases/download/models/sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8.zip"

        btnImport.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            punctModelFilePicker.launch("application/zip")
        }

        btnDl.setOnClickListener { v ->
            v.isEnabled = false
            tvStatus.text = ""
            val sources = arrayOf(
                getString(R.string.download_source_github_official),
                getString(R.string.download_source_mirror_ghproxy),
                getString(R.string.download_source_mirror_gitmirror),
                getString(R.string.download_source_mirror_gh_proxynet)
            )
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.download_source_title)
                .setItems(sources) { dlg, which ->
                    dlg.dismiss()
                    val url = when (which) {
                        1 -> "https://ghproxy.net/$urlOfficial"
                        2 -> "https://hub.gitmirror.com/$urlOfficial"
                        3 -> "https://gh-proxy.net/$urlOfficial"
                        else -> urlOfficial
                    }
                    try {
                        ModelDownloadService.startDownload(this, url, variant, "punctuation")
                        tvStatus.text = getString(R.string.punct_download_started_in_bg)
                    } catch (e: Throwable) {
                        android.util.Log.e(TAG, "Failed to start punctuation model download", e)
                        tvStatus.text = getString(R.string.punct_download_status_failed)
                    } finally {
                        v.isEnabled = true
                    }
                }
                .setOnDismissListener { v.isEnabled = true }
                .show()
        }

        btnClear.setOnClickListener { v ->
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.punct_clear_confirm_title)
                .setMessage(R.string.punct_clear_confirm_message)
                .setPositiveButton(android.R.string.ok) { d, _ ->
                    d.dismiss()
                    v.isEnabled = false
                    lifecycleScope.launch {
                        try {
                            val deleted = withContext(Dispatchers.IO) {
                                SherpaPunctuationManager.clearInstalledModel(this@AsrSettingsActivity)
                            }
                            tvStatus.text = if (deleted) {
                                getString(R.string.punct_clear_done)
                            } else {
                                getString(R.string.punct_clear_failed)
                            }
                        } catch (e: Throwable) {
                            android.util.Log.e(TAG, "Failed to clear punctuation model", e)
                            tvStatus.text = getString(R.string.punct_clear_failed)
                        } finally {
                            v.isEnabled = true
                            updatePunctDownloadUiVisibility()
                        }
                    }
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .create()
                .show()
        }
    }

    private fun setupTelespeechSettings() {
        val tvVariant = findViewById<TextView>(R.id.tvTsModelVariantValue)
        val variantLabels = arrayOf(
            getString(R.string.ts_model_int8),
            getString(R.string.ts_model_full)
        )
        val variantCodes = arrayOf("int8", "full")
        fun updateVariantSummary() {
            val idx = variantCodes.indexOf(prefs.tsModelVariant).coerceAtLeast(0)
            tvVariant.text = variantLabels[idx]
        }
        updateVariantSummary()
        tvVariant.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val cur = variantCodes.indexOf(prefs.tsModelVariant).coerceAtLeast(0)
            showSingleChoiceDialog(R.string.label_ts_model_variant, variantLabels, cur) { which ->
                val code = variantCodes.getOrNull(which) ?: "int8"
                if (code != prefs.tsModelVariant) {
                    viewModel.updateTsModelVariant(code)
                }
                updateVariantSummary()
                updateTsDownloadUiVisibility()
            }
        }

        // 保留时长
        val tvKeep = findViewById<TextView>(R.id.tvTsKeepAliveValue)
        fun updateKeepAliveSummary() {
            val values = listOf(0, 5, 15, 30, -1)
            val labels = arrayOf(
                getString(R.string.sv_keep_alive_immediate),
                getString(R.string.sv_keep_alive_5m),
                getString(R.string.sv_keep_alive_15m),
                getString(R.string.sv_keep_alive_30m),
                getString(R.string.sv_keep_alive_always)
            )
            val idx = values.indexOf(prefs.tsKeepAliveMinutes).let { if (it >= 0) it else values.size - 1 }
            tvKeep.text = labels[idx]
        }
        updateKeepAliveSummary()
        tvKeep.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val labels = arrayOf(
                getString(R.string.sv_keep_alive_immediate),
                getString(R.string.sv_keep_alive_5m),
                getString(R.string.sv_keep_alive_15m),
                getString(R.string.sv_keep_alive_30m),
                getString(R.string.sv_keep_alive_always)
            )
            val values = listOf(0, 5, 15, 30, -1)
            val cur = values.indexOf(prefs.tsKeepAliveMinutes).let { if (it >= 0) it else values.size - 1 }
            showSingleChoiceDialog(R.string.label_ts_keep_alive, labels, cur) { which ->
                val vv = values.getOrNull(which) ?: -1
                if (vv != prefs.tsKeepAliveMinutes) {
                    viewModel.updateTsKeepAlive(vv)
                }
                updateKeepAliveSummary()
            }
        }

        // 线程数滑块（1-8）
        findViewById<com.google.android.material.slider.Slider>(R.id.sliderTsThreads).apply {
            value = prefs.tsNumThreads.coerceIn(1, 8).toFloat()
            addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    val v = value.toInt().coerceIn(1, 8)
                    if (v != prefs.tsNumThreads) {
                        viewModel.updateTsNumThreads(v)
                    }
                }
            }
            addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) = hapticTapIfEnabled(slider)
                override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) = hapticTapIfEnabled(slider)
            })
        }

        // 首次显示时加载模型
        findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchTsPreload).apply {
            isChecked = prefs.tsPreloadEnabled
            installExplainedSwitch(
                context = this@AsrSettingsActivity,
                titleRes = R.string.label_ts_preload,
                offDescRes = R.string.feature_ts_preload_off_desc,
                onDescRes = R.string.feature_ts_preload_on_desc,
                preferenceKey = "ts_preload_explained",
                readPref = { prefs.tsPreloadEnabled },
                writePref = { v -> viewModel.updateTsPreload(v) },
                hapticFeedback = { hapticTapIfEnabled(it) }
            )
        }

        findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchTsUseItn).apply {
            isChecked = prefs.tsUseItn
            installExplainedSwitch(
                context = this@AsrSettingsActivity,
                titleRes = R.string.label_ts_use_itn,
                offDescRes = R.string.feature_ts_use_itn_off_desc,
                onDescRes = R.string.feature_ts_use_itn_on_desc,
                preferenceKey = "ts_use_itn_explained",
                readPref = { prefs.tsUseItn },
                writePref = { v -> viewModel.updateTsUseItn(v) },
                hapticFeedback = { hapticTapIfEnabled(it) }
            )
        }

        findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchTsPseudoStream).apply {
            isChecked = prefs.tsPseudoStreamEnabled
            installExplainedSwitch(
                context = this@AsrSettingsActivity,
                titleRes = R.string.label_ts_pseudo_stream,
                offDescRes = R.string.feature_ts_pseudo_stream_off_desc,
                onDescRes = R.string.feature_ts_pseudo_stream_on_desc,
                preferenceKey = "ts_pseudo_stream_explained",
                readPref = { prefs.tsPseudoStreamEnabled },
                writePref = { v -> viewModel.updateTsPseudoStream(v) },
                hapticFeedback = { hapticTapIfEnabled(it) }
            )
        }

        setupTsDownloadButtons()

        // 通用标点模型（TeleSpeech / Paraformer 共用）
        setupPunctDownloadButtons(
            btnDownloadId = R.id.btnTsDownloadPunct,
            btnImportId = R.id.btnTsImportPunct,
            btnClearId = R.id.btnTsClearPunct,
            statusTextId = R.id.tvTsPunctStatus
        )
    }

    private fun handleModelImport(uri: Uri) {
        val tvSvDownloadStatus = findViewById<TextView>(R.id.tvSvDownloadStatus)
        tvSvDownloadStatus.text = ""

        try {
            if (!isZipUri(uri)) {
                tvSvDownloadStatus.text = getString(R.string.sv_import_failed, getString(R.string.error_only_zip_supported))
                return
            }
            val variant = prefs.svModelVariant
            ModelDownloadService.startImport(this, uri, variant)
            tvSvDownloadStatus.text = getString(R.string.sv_import_started_in_bg)
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "Failed to start model import", e)
            tvSvDownloadStatus.text = getString(R.string.sv_import_failed, e.message ?: "Unknown error")
        }
    }

    private fun handleTsModelImport(uri: Uri) {
        val tvStatus = findViewById<TextView>(R.id.tvTsDownloadStatus)
        tvStatus.text = ""

        try {
            if (!isZipUri(uri)) {
                tvStatus.text = getString(R.string.ts_import_failed, getString(R.string.error_only_zip_supported))
                return
            }
            val variant = prefs.tsModelVariant
            ModelDownloadService.startImport(this, uri, variant)
            tvStatus.text = getString(R.string.ts_import_started_in_bg)
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "Failed to start telespeech model import", e)
            tvStatus.text = getString(R.string.ts_import_failed, e.message ?: "Unknown error")
        }
    }

    private fun handlePfModelImport(uri: Uri) {
        val tvStatus = findViewById<TextView>(R.id.tvPfDownloadStatus)
        tvStatus.text = ""

        try {
            if (!isZipUri(uri)) {
                tvStatus.text = getString(R.string.pf_import_failed, getString(R.string.error_only_zip_supported))
                return
            }
            val variant = prefs.pfModelVariant
            ModelDownloadService.startImport(this, uri, variant)
            tvStatus.text = getString(R.string.pf_import_started_in_bg)
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "Failed to start paraformer model import", e)
            tvStatus.text = getString(R.string.pf_import_failed, e.message ?: "Unknown error")
        }
    }

    private fun handlePunctModelImport(uri: Uri) {
        // 更新三个状态文本区域
        val statusTextViews = listOf(
            findViewById<TextView?>(R.id.tvTsPunctStatus),
            findViewById<TextView?>(R.id.tvPfPunctStatus)
        ).filterNotNull()

        statusTextViews.forEach { it.text = "" }

        try {
            if (!isZipUri(uri)) {
                val errMsg = getString(R.string.punct_import_failed, getString(R.string.error_only_zip_supported))
                statusTextViews.forEach { it.text = errMsg }
                return
            }
            val variant = "ct-zh-en-int8"
            ModelDownloadService.startImport(this, uri, variant, "punctuation")
            val startMsg = getString(R.string.punct_import_started_in_bg)
            statusTextViews.forEach { it.text = startMsg }
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "Failed to start punctuation model import", e)
            val errMsg = getString(R.string.punct_import_failed, e.message ?: "Unknown error")
            statusTextViews.forEach { it.text = errMsg }
        }
    }

    private fun isZipUri(uri: Uri): Boolean {
        val name = getDisplayName(uri) ?: uri.lastPathSegment ?: ""
        return name.lowercase().endsWith(".zip")
    }

    private fun getDisplayName(uri: Uri): String? {
        return try {
            val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
            contentResolver.query(uri, projection, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (_: Throwable) { null }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                updateVendorSummary(state.selectedVendor)
                updateVendorVisibility(state)
                updateSilenceOptionsVisibility(state.autoStopSilenceEnabled)
                updateSfOmniVisibility(state.sfUseOmni)
                updateOpenAiPromptVisibility(state.oaAsrUsePrompt)
                updateVolcFileModeVisibility(state.volcStreamingEnabled)
                updateVolcModelV2Visibility(state.volcStreamingEnabled, state.volcFileStandardEnabled)
                findViewById<MaterialSwitch>(R.id.switchVolcFileStandard).let { sw ->
                    if (sw.isChecked != state.volcFileStandardEnabled) {
                        sw.isChecked = state.volcFileStandardEnabled
                    }
                }
                findViewById<MaterialSwitch>(R.id.switchVolcModelV2).let { sw ->
                    if (sw.isChecked != state.volcModelV2Enabled) {
                        sw.isChecked = state.volcModelV2Enabled
                    }
                }
                updateVolcStreamOptionsVisibility(state.volcStreamingEnabled)
                updateVolcTwoPassVisibility(state.volcStreamingEnabled, state.volcBidiStreamingEnabled)
            }
        }
    }

    private fun updateVendorSummary(vendor: AsrVendor) {
        val vendorOrder = AsrVendorUi.ordered()
        val vendorItems = AsrVendorUi.names(this)
        val idx = vendorOrder.indexOf(vendor).coerceAtLeast(0)
        tvAsrVendor.text = vendorItems[idx]
    }

    private fun updateVendorVisibility(state: AsrSettingsUiState) {
        val visMap = mapOf(
            AsrVendor.Volc to groupVolc,
            AsrVendor.SiliconFlow to groupSf,
            AsrVendor.ElevenLabs to groupEleven,
            AsrVendor.OpenAI to groupOpenAi,
            AsrVendor.DashScope to groupDash,
            AsrVendor.Gemini to groupGemini,
            AsrVendor.Soniox to groupSoniox,
            AsrVendor.Zhipu to groupZhipu,
            AsrVendor.SenseVoice to groupSenseVoice,
            AsrVendor.Telespeech to groupTelespeech,
            AsrVendor.Paraformer to groupParaformer
        )
        visMap.forEach { (vendor, view) ->
            val vis = if (vendor == state.selectedVendor) View.VISIBLE else View.GONE
            if (view.visibility != vis) view.visibility = vis
        }
    }

    private fun updateSilenceOptionsVisibility(enabled: Boolean) {
        val vis = if (enabled) View.VISIBLE else View.GONE
        if (tvSilenceWindowLabel.visibility != vis) tvSilenceWindowLabel.visibility = vis
        if (sliderSilenceWindow.visibility != vis) sliderSilenceWindow.visibility = vis
        if (tvSilenceSensitivityLabel.visibility != vis) tvSilenceSensitivityLabel.visibility = vis
        if (sliderSilenceSensitivity.visibility != vis) sliderSilenceSensitivity.visibility = vis
    }

    private fun updateSfOmniVisibility(enabled: Boolean) {
        val til = findViewById<View>(R.id.tilSfOmniPrompt)
        val vis = if (enabled) View.VISIBLE else View.GONE
        if (til.visibility != vis) til.visibility = vis
    }

    private fun updateOpenAiPromptVisibility(enabled: Boolean) {
        val til = findViewById<View>(R.id.tilOpenAiPrompt)
        val vis = if (enabled) View.VISIBLE else View.GONE
        if (til.visibility != vis) til.visibility = vis
    }

    private fun updateVolcFileModeVisibility(streamingEnabled: Boolean) {
        val switch = findViewById<MaterialSwitch>(R.id.switchVolcFileStandard)
        val vis = if (streamingEnabled) View.GONE else View.VISIBLE
        if (switch.visibility != vis) switch.visibility = vis
    }

    private fun updateVolcModelV2Visibility(streamingEnabled: Boolean, fileStandardEnabled: Boolean) {
        val switch = findViewById<MaterialSwitch>(R.id.switchVolcModelV2)
        val vis = if (streamingEnabled || fileStandardEnabled) View.VISIBLE else View.GONE
        if (switch.visibility != vis) switch.visibility = vis
    }

    private fun updateVolcStreamOptionsVisibility(enabled: Boolean) {
        val vis = if (enabled) View.VISIBLE else View.GONE
        fun setIfChanged(v: View) { if (v.visibility != vis) v.visibility = vis }
        setIfChanged(findViewById<MaterialSwitch>(R.id.switchVolcVad))
        setIfChanged(findViewById<MaterialSwitch>(R.id.switchVolcFirstCharAccel))
        setIfChanged(findViewById<TextView>(R.id.tvVolcLanguageValue))
        setIfChanged(findViewById<View>(R.id.tvVolcLanguageLabel))
        setIfChanged(findViewById<MaterialSwitch>(R.id.switchVolcBidiStreaming))
    }

    private fun updateVolcTwoPassVisibility(streamingEnabled: Boolean, bidiEnabled: Boolean) {
        val vis = if (streamingEnabled && bidiEnabled) View.VISIBLE else View.GONE
        val v = findViewById<View>(R.id.switchVolcNonstream)
        if (v.visibility != vis) v.visibility = vis
    }

    private fun updateSvDownloadUiVisibility() {
        val ready = viewModel.checkSvModelDownloaded(this)
        val btn = findViewById<MaterialButton>(R.id.btnSvDownloadModel)
        val btnImport = findViewById<MaterialButton>(R.id.btnSvImportModel)
        val btnClear = findViewById<MaterialButton>(R.id.btnSvClearModel)
        val tv = findViewById<TextView>(R.id.tvSvDownloadStatus)
        btn.visibility = if (ready) View.GONE else View.VISIBLE
        btnImport.visibility = if (ready) View.GONE else View.VISIBLE
        btnClear.visibility = if (ready) View.VISIBLE else View.GONE
        if (ready && tv.text.isNullOrBlank()) {
            tv.text = getString(R.string.sv_download_status_done)
        }
    }

    private fun updateTsDownloadUiVisibility() {
        val ready = viewModel.checkTsModelDownloaded(this)
        val btn = findViewById<MaterialButton>(R.id.btnTsDownloadModel)
        val btnImport = findViewById<MaterialButton>(R.id.btnTsImportModel)
        val btnClear = findViewById<MaterialButton>(R.id.btnTsClearModel)
        val tv = findViewById<TextView>(R.id.tvTsDownloadStatus)
        btn.visibility = if (ready) View.GONE else View.VISIBLE
        btnImport.visibility = if (ready) View.GONE else View.VISIBLE
        btnClear.visibility = if (ready) View.VISIBLE else View.GONE
        if (ready && tv.text.isNullOrBlank()) {
            tv.text = getString(R.string.ts_download_status_done)
        }
    }

    /**
     * 更新通用标点模型下载/清理按钮的可见性与状态（多个本地引擎共用同一套模型）
     */
    private fun updatePunctDownloadUiVisibility() {
        val ready = try {
            viewModel.isPunctuationModelInstalled(this)
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "Failed to check punctuation model installed", t)
            false
        }

        fun apply(btnDownloadId: Int, btnImportId: Int, btnClearId: Int, statusTextId: Int) {
            val btnDl = findViewById<MaterialButton?>(btnDownloadId) ?: return
            val btnImport = findViewById<MaterialButton?>(btnImportId) ?: return
            val btnClear = findViewById<MaterialButton?>(btnClearId) ?: return
            val tv = findViewById<TextView?>(statusTextId) ?: return

            // 模型已安装时：隐藏下载和导入按钮，显示清理按钮
            // 模型未安装时：显示下载和导入按钮，隐藏清理按钮
            btnDl.visibility = if (ready) View.GONE else View.VISIBLE
            btnImport.visibility = if (ready) View.GONE else View.VISIBLE
            btnClear.visibility = if (ready) View.VISIBLE else View.GONE

            if (ready && tv.text.isNullOrBlank()) {
                tv.text = getString(R.string.punct_download_status_done)
            }
        }

        apply(R.id.btnSvDownloadPunct, R.id.btnSvImportPunct, R.id.btnSvClearPunct, R.id.tvSvPunctStatus)
        apply(R.id.btnTsDownloadPunct, R.id.btnTsImportPunct, R.id.btnTsClearPunct, R.id.tvTsPunctStatus)
        apply(R.id.btnPfDownloadPunct, R.id.btnPfImportPunct, R.id.btnPfClearPunct, R.id.tvPfPunctStatus)
    }

    // ====== Helper Functions ======

    /**
     * Reusable function for showing single-choice dialogs.
     * Reduces repetitive code across vendor language selections.
     */
    private fun showSingleChoiceDialog(
        titleResId: Int,
        items: Array<String>,
        currentIndex: Int,
        onSelected: (Int) -> Unit
    ) {
        SettingsOptionSheet.showSingleChoice(
            context = this,
            titleResId = titleResId,
            items = items.toList(),
            selectedIndex = currentIndex,
            onSelected = onSelected
        )
    }

    /**
     * Extension function to bind EditText changes to Prefs.
     * Simplifies two-way binding setup.
     */
    private fun EditText.bindString(onChange: (String) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                onChange(s?.toString() ?: "")
            }
        })
    }

    /**
     * Helper to setup slider with haptic feedback and value change listener.
     */
    private fun setupSlider(slider: Slider, onValueChange: (Float) -> Unit) {
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                onValueChange(value)
            }
        }
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = hapticTapIfEnabled(slider)
            override fun onStopTrackingTouch(slider: Slider) = hapticTapIfEnabled(slider)
        })
    }

    private fun hapticTapIfEnabled(view: View?) {
        try {
            if (prefs.micHapticEnabled) {
                view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "Failed to perform haptic feedback", e)
        }
    }

    private fun openUrlSafely(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "Failed to open url: $url", e)
            Toast.makeText(this, getString(R.string.error_open_browser), Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "AsrSettingsActivity"
    }
}
