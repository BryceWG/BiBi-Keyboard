package com.brycewg.asrkb.store

import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.asr.VolcAsrModelCatalog
import org.json.JSONObject

/**
 * ASR 供应商所需配置字段（从 [Prefs] 中拆出）。
 *
 * 用途：
 * - `hasVendorKeys(...)` 校验必填项
 * - 备份导入导出时遍历供应商字段，避免逐个硬编码
 */
internal object PrefsAsrVendorFields {
    internal val vendorFields: Map<AsrVendor, List<VendorField>> = mapOf(
        AsrVendor.Volc to listOf(
            VendorField.credential(KEY_APP_KEY),
            VendorField.credential(KEY_ACCESS_KEY),
            VendorField.credential(KEY_VOLC_API_KEY, required = false),
            VendorField.model(KEY_VOLC_ASR_MODEL, default = VolcAsrModelCatalog.DEFAULT_ID),
            VendorField.streamingToggle(KEY_VOLC_STREAMING_ENABLED, default = true),
            VendorField.boolean(KEY_VOLC_DDC_ENABLED, default = true),
            VendorField.boolean(KEY_VOLC_VAD_ENABLED, default = false),
            VendorField.streamingToggle(KEY_VOLC_NONSTREAM_ENABLED, default = true),
            VendorField.language(KEY_VOLC_LANGUAGE),
            VendorField.boolean(KEY_VOLC_FILE_STANDARD_ENABLED, default = true),
            VendorField.boolean(KEY_VOLC_MODEL_V2_ENABLED, default = true),
            VendorField.boolean(KEY_VOLC_USE_NEW_AUTH, default = false)
        ),
        // SiliconFlow：免费服务启用时无需 API Key
        AsrVendor.SiliconFlow to listOf(
            VendorField.credential(KEY_SF_API_KEY, required = false), // 免费服务时无需 API Key
            VendorField.model(KEY_SF_MODEL, default = Prefs.DEFAULT_SF_MODEL),
            VendorField.boolean(KEY_SF_FREE_ASR_ENABLED, default = true),
            VendorField.model(KEY_SF_FREE_ASR_MODEL, default = Prefs.DEFAULT_SF_FREE_ASR_MODEL),
            VendorField.boolean(KEY_SF_USE_OMNI, default = false)
        ),
        AsrVendor.ElevenLabs to listOf(
            VendorField.credential(KEY_ELEVEN_API_KEY),
            VendorField.language(KEY_ELEVEN_LANGUAGE_CODE),
            VendorField.streamingToggle(KEY_ELEVEN_STREAMING_ENABLED, default = true)
        ),
        AsrVendor.OpenAI to listOf(
            VendorField.endpoint(
                KEY_OA_ASR_ENDPOINT,
                required = true,
                default = Prefs.DEFAULT_OA_ASR_ENDPOINT
            ),
            VendorField.credential(KEY_OA_ASR_API_KEY, required = false),
            VendorField.model(KEY_OA_ASR_MODEL, required = true, default = Prefs.DEFAULT_OA_ASR_MODEL),
            // 可选 Prompt 字段（字符串）；开关为布尔，单独在导入/导出处理
            VendorField.prompt(KEY_OA_ASR_PROMPT),
            // 可选语言字段（字符串）
            VendorField.language(KEY_OA_ASR_LANGUAGE)
        ),
        AsrVendor.OpenRouter to listOf(
            VendorField.endpoint(
                KEY_OPENROUTER_ASR_ENDPOINT,
                required = true,
                default = Prefs.DEFAULT_OPENROUTER_ASR_ENDPOINT
            ),
            VendorField.credential(KEY_OPENROUTER_ASR_API_KEY),
            VendorField.model(
                KEY_OPENROUTER_ASR_MODEL,
                required = true,
                default = Prefs.DEFAULT_OPENROUTER_ASR_MODEL
            )
        ),
        AsrVendor.DashScope to listOf(
            VendorField.credential(KEY_DASH_API_KEY),
            VendorField.model(KEY_DASH_ASR_MODEL, default = Prefs.DEFAULT_DASH_MODEL),
            VendorField.prompt(KEY_DASH_PROMPT),
            VendorField.language(KEY_DASH_LANGUAGE),
            VendorField.boolean(KEY_DASH_KEEP_DIALECT, default = false),
            VendorField.boolean(KEY_DASH_AUTO_POLISH, default = true),
            VendorField(KEY_DASH_REGION, default = "cn")
        ),
        AsrVendor.Gemini to listOf(
            VendorField.endpoint(KEY_GEM_ENDPOINT, required = true, default = Prefs.DEFAULT_GEM_ENDPOINT),
            VendorField.credential(KEY_GEM_API_KEY),
            VendorField.model(KEY_GEM_MODEL, required = true, default = Prefs.DEFAULT_GEM_MODEL),
            VendorField.boolean(KEY_GEMINI_DISABLE_THINKING, default = false),
            VendorField(KEY_GEMINI_ASR_MODE, default = "gemini"),
            VendorField.credential(KEY_GEM_TRANSCRIBE_API_KEY, required = false),
            VendorField.endpoint(KEY_GEM_TRANSCRIBE_ENDPOINT, default = Prefs.DEFAULT_GEM_ENDPOINT),
            VendorField.model(KEY_GEM_TRANSCRIBE_MODEL, default = Prefs.DEFAULT_GEM_TRANSCRIBE_MODEL),
            VendorField.language(KEY_GEM_TRANSCRIBE_LANGUAGE),
            VendorField.boolean(KEY_GEM_TRANSCRIBE_SMART_ENABLED, default = false)
        ),
        AsrVendor.MiMo to listOf(
            VendorField(KEY_MIMO_ASR_API_KEYS_JSON, default = ""),
            VendorField.credential(KEY_MIMO_ASR_API_KEY, required = false),
            VendorField.endpoint(
                KEY_MIMO_ASR_ENDPOINT,
                default = Prefs.DEFAULT_MIMO_ASR_ENDPOINT
            ),
            VendorField(KEY_MIMO_ASR_ENDPOINT_PRESET, default = Prefs.MIMO_ENDPOINT_PRESET_PAYGO),
            VendorField.model(KEY_MIMO_ASR_MODEL),
            VendorField.language(KEY_MIMO_ASR_LANGUAGE, default = Prefs.DEFAULT_MIMO_ASR_LANGUAGE),
            VendorField.prompt(KEY_MIMO_ASR_PROMPT, default = "请将以下音频准确转写为文字"),
            VendorField.boolean(KEY_MIMO_ASR_DISABLE_THINKING, default = false)
        ),
        AsrVendor.Soniox to listOf(
            VendorField.credential(KEY_SONIOX_API_KEY),
            VendorField.streamingToggle(KEY_SONIOX_STREAMING_ENABLED, default = true),
            VendorField.int(
                KEY_SONIOX_ENDPOINT_SENSITIVITY_LEVEL,
                default = Prefs.DEFAULT_SONIOX_ENDPOINT_SENSITIVITY_LEVEL,
                range = Prefs.SONIOX_ENDPOINT_SENSITIVITY_LEVEL_MIN..Prefs.SONIOX_ENDPOINT_SENSITIVITY_LEVEL_MAX
            ),
            // 导入仍在 PrefsBackup 中保留“数组优先、单值回退”的显式兼容逻辑。
            VendorField.language(KEY_SONIOX_LANGUAGES),
            VendorField.boolean(KEY_SONIOX_LANGUAGE_HINTS_STRICT, default = false)
        ),
        AsrVendor.StepAudio to listOf(
            VendorField(KEY_STEPAUDIO_API_KEYS_JSON, default = ""),
            VendorField.credential(KEY_STEPAUDIO_API_KEY, required = false),
            VendorField.endpoint(
                KEY_STEPAUDIO_ENDPOINT,
                default = Prefs.DEFAULT_STEPAUDIO_ASR_ENDPOINT
            ),
            VendorField(
                KEY_STEPAUDIO_ENDPOINT_PRESET,
                default = Prefs.STEPAUDIO_ENDPOINT_PRESET_PAYGO
            ),
            VendorField.model(KEY_STEPAUDIO_MODEL, required = true, default = Prefs.DEFAULT_STEPAUDIO_ASR_MODEL),
            VendorField.language(KEY_STEPAUDIO_LANGUAGE, default = "zh"),
            VendorField.boolean(KEY_STEPAUDIO_USE_ITN, default = true)
        ),
        AsrVendor.Zhipu to listOf(
            VendorField.credential(KEY_ZHIPU_API_KEY)
        ),
        AsrVendor.Cohere to listOf(
            VendorField.credential(KEY_COHERE_API_KEY),
            VendorField.model(
                KEY_COHERE_ASR_MODEL,
                required = true,
                default = Prefs.DEFAULT_COHERE_ASR_MODEL
            ),
            VendorField.language(
                KEY_COHERE_ASR_LANGUAGE,
                default = Prefs.DEFAULT_COHERE_ASR_LANGUAGE
            )
        ),
        // 本地 SenseVoice（sherpa-onnx）无需鉴权
        AsrVendor.SenseVoice to listOf(
            VendorField.localModel(KEY_SV_MODEL_DIR),
            VendorField.localModel(KEY_SV_MODEL_VARIANT, default = "small-int8"),
            VendorField.int(KEY_SV_NUM_THREADS, default = 2, range = 1..8, role = VendorFieldRole.LocalModel),
            VendorField.language(KEY_SV_LANGUAGE, default = "auto"),
            VendorField.boolean(KEY_SV_USE_ITN, default = true),
            VendorField.boolean(KEY_SV_PRELOAD_ENABLED, default = true, role = VendorFieldRole.LocalModel),
            VendorField.int(KEY_SV_KEEP_ALIVE_MINUTES, default = -1, role = VendorFieldRole.LocalModel),
            VendorField.streamingToggle(KEY_SV_PSEUDO_STREAM_ENABLED, default = false)
        ),
        // 本地 FunASR Nano（sherpa-onnx）无需鉴权
        AsrVendor.FunAsrNano to listOf(
            VendorField.localModel(KEY_FN_MODEL_VARIANT, default = "nano-int8"),
            VendorField.int(KEY_FN_NUM_THREADS, default = 4, range = 1..8, role = VendorFieldRole.LocalModel),
            VendorField.boolean(KEY_FN_USE_ITN, default = true),
            VendorField.prompt(KEY_FN_USER_PROMPT, default = "语音转写："),
            VendorField.language(KEY_FN_LANGUAGE),
            VendorField.boolean(KEY_FN_PRELOAD_ENABLED, default = true, role = VendorFieldRole.LocalModel),
            VendorField.int(KEY_FN_KEEP_ALIVE_MINUTES, default = -1, role = VendorFieldRole.LocalModel)
        ),
        // 本地 Qwen3-ASR（sherpa-onnx）无需鉴权
        AsrVendor.Qwen3Asr to listOf(
            VendorField.localModel(KEY_QW_MODEL_VARIANT, default = "qwen3-0.6b-int8"),
            VendorField.int(KEY_QW_NUM_THREADS, default = 3, range = 1..8, role = VendorFieldRole.LocalModel),
            VendorField.boolean(KEY_QW_PRELOAD_ENABLED, default = true, role = VendorFieldRole.LocalModel),
            VendorField.int(KEY_QW_KEEP_ALIVE_MINUTES, default = -1, role = VendorFieldRole.LocalModel),
            VendorField.boolean(KEY_QW_USE_ITN, default = true)
        ),
        // 本地 Parakeet（sherpa-onnx）无需鉴权
        AsrVendor.Parakeet to listOf(
            VendorField.localModel(KEY_PK_MODEL_VARIANT, default = "0.6b-v3-int8"),
            VendorField.int(KEY_PK_NUM_THREADS, default = 3, range = 1..8, role = VendorFieldRole.LocalModel),
            VendorField.boolean(KEY_PK_PRELOAD_ENABLED, default = true, role = VendorFieldRole.LocalModel),
            VendorField.int(KEY_PK_KEEP_ALIVE_MINUTES, default = -1, role = VendorFieldRole.LocalModel)
        ),
        // 本地 FireRedASR（sherpa-onnx）无需鉴权
        AsrVendor.FireRedAsr to emptyList(),
        // 本地 X-ASR（sherpa-onnx）无需鉴权
        AsrVendor.XAsr to emptyList()
    )

    internal val backupFields: List<VendorField> =
        vendorFields.values.flatten().distinctBy { it.key }

    internal fun fieldsFor(vendor: AsrVendor): List<VendorField> = vendorFields[vendor].orEmpty()

    internal fun fieldsByRole(vendor: AsrVendor, role: VendorFieldRole): List<VendorField> = fieldsFor(vendor).filter { it.role == role }

    internal fun requiredCredentialFields(vendor: AsrVendor): List<VendorField> = fieldsByRole(vendor, VendorFieldRole.Credential)
        .filter { it.required && it.type == VendorFieldType.String }

    internal fun requiredStringFieldsForValidation(vendor: AsrVendor): List<VendorField> = fieldsFor(vendor).filter { it.required && it.type == VendorFieldType.String }

    internal fun exportToJson(
        store: VendorFieldStore,
        output: JSONObject,
        fields: List<VendorField> = backupFields
    ) = export(store, JSONObjectVendorFieldExportSink(output), fields)

    internal fun importFromJson(
        store: VendorFieldStore,
        input: JSONObject,
        fields: List<VendorField> = backupFields
    ) = import(store, JSONObjectVendorFieldImportSource(input), fields)

    internal fun export(
        store: VendorFieldStore,
        output: VendorFieldExportSink,
        fields: List<VendorField> = backupFields
    ) {
        fields.forEach { field ->
            output.put(field.key, field.readFrom(store))
        }
    }

    internal fun import(
        store: VendorFieldStore,
        input: VendorFieldImportSource,
        fields: List<VendorField> = backupFields
    ) {
        fields.forEach { field ->
            if (input.has(field.key)) {
                field.writeTo(store, input)
            }
        }
    }
}

internal data class VendorField(
    val key: String,
    val required: Boolean = false,
    val default: String = "",
    val role: VendorFieldRole = VendorFieldRole.RuntimeOption,
    val type: VendorFieldType = VendorFieldType.String,
    val booleanDefault: Boolean = false,
    val intDefault: Int = 0,
    val intRange: IntRange? = null
) {
    fun readFrom(store: VendorFieldStore): Any = when (type) {
        VendorFieldType.String -> store.getString(key, default)
        VendorFieldType.Boolean -> store.getBoolean(key, booleanDefault)
        VendorFieldType.Int -> normalizeInt(store.getInt(key, intDefault))
    }

    fun writeTo(store: VendorFieldStore, input: VendorFieldImportSource) {
        when (type) {
            VendorFieldType.String -> {
                store.putString(key, input.optString(key))
            }
            VendorFieldType.Boolean -> store.putBoolean(key, input.optBoolean(key, booleanDefault))
            VendorFieldType.Int -> store.putInt(key, normalizeInt(input.optInt(key, intDefault)))
        }
    }

    private fun normalizeInt(value: Int): Int = intRange?.let { value.coerceIn(it) } ?: value

    companion object {
        fun credential(key: String, required: Boolean = true, default: String = ""): VendorField = VendorField(
            key = key,
            required = required,
            default = default,
            role = VendorFieldRole.Credential
        )

        fun endpoint(key: String, required: Boolean = false, default: String = ""): VendorField = VendorField(
            key = key,
            required = required,
            default = default,
            role = VendorFieldRole.Endpoint
        )

        fun model(key: String, required: Boolean = false, default: String = ""): VendorField = VendorField(
            key = key,
            required = required,
            default = default,
            role = VendorFieldRole.Model
        )

        fun language(key: String, required: Boolean = false, default: String = ""): VendorField = VendorField(
            key = key,
            required = required,
            default = default,
            role = VendorFieldRole.Language
        )

        fun prompt(key: String, required: Boolean = false, default: String = ""): VendorField = VendorField(
            key = key,
            required = required,
            default = default,
            role = VendorFieldRole.Prompt
        )

        fun localModel(key: String, default: String = ""): VendorField = VendorField(
            key = key,
            default = default,
            role = VendorFieldRole.LocalModel
        )

        fun boolean(
            key: String,
            default: Boolean,
            role: VendorFieldRole = VendorFieldRole.RuntimeOption
        ): VendorField = VendorField(
            key = key,
            role = role,
            type = VendorFieldType.Boolean,
            booleanDefault = default
        )

        fun streamingToggle(key: String, default: Boolean): VendorField = boolean(key, default, role = VendorFieldRole.StreamingToggle)

        fun int(
            key: String,
            default: Int,
            range: IntRange? = null,
            role: VendorFieldRole = VendorFieldRole.RuntimeOption
        ): VendorField = VendorField(
            key = key,
            role = role,
            type = VendorFieldType.Int,
            intDefault = default,
            intRange = range
        )
    }
}

internal enum class VendorFieldType {
    String,
    Boolean,
    Int
}

internal enum class VendorFieldRole {
    Credential,
    Endpoint,
    Model,
    Language,
    Prompt,
    StreamingToggle,
    LocalModel,
    RuntimeOption
}

internal interface VendorFieldStore {
    fun getString(key: String, default: String): String
    fun putString(key: String, value: String)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
}

internal interface VendorFieldExportSink {
    fun put(key: String, value: Any)
}

internal interface VendorFieldImportSource {
    fun has(key: String): Boolean
    fun optString(key: String): String
    fun optBoolean(key: String, default: Boolean): Boolean
    fun optInt(key: String, default: Int): Int
}

private class JSONObjectVendorFieldExportSink(
    private val output: JSONObject
) : VendorFieldExportSink {
    override fun put(key: String, value: Any) {
        output.put(key, value)
    }
}

private class JSONObjectVendorFieldImportSource(
    private val input: JSONObject
) : VendorFieldImportSource {
    override fun has(key: String): Boolean = input.has(key)

    override fun optString(key: String): String = input.optString(key)

    override fun optBoolean(key: String, default: Boolean): Boolean = if (input.has(key)) input.optBoolean(key) else default

    override fun optInt(key: String, default: Int): Int = if (input.has(key)) input.optInt(key) else default
}
