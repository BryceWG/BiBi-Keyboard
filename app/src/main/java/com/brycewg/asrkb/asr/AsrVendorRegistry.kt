// Lightweight ASR supplier descriptor registry.
package com.brycewg.asrkb.asr

import android.content.Context
import androidx.annotation.StringRes
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs

internal data class AsrVendorDescriptor(
    val vendor: AsrVendor,
    val id: String,
    val legacyIds: Set<String>,
    @param:StringRes val displayNameResId: Int,
    val pickerOrder: Int,
    val tags: List<AsrVendorDisplayTag>,
    val capabilities: Set<AsrVendorCapability>,
    val availabilityLink: AsrVendorAvailabilityLink,
    val lifecycleLink: AsrVendorLifecycleLink
) {
    val allIds: Set<String> = setOf(id) + legacyIds

    val availabilityClassification: AsrVendorAvailabilityClassification
        get() = when (availabilityLink) {
            AsrVendorAvailabilityLink.OnlineConfiguration -> AsrVendorAvailabilityClassification.OnlineConfiguration
            AsrVendorAvailabilityLink.LocalModelInstallation -> AsrVendorAvailabilityClassification.LocalModelReadiness
        }

    val localLifecycle: AsrLocalVendorLifecycle?
        get() = AsrLocalVendorLifecycles.lifecycleFor(vendor)

    fun checkAvailability(context: Context, prefs: Prefs): AsrVendorReadiness = checkAsrVendorAvailability(context, prefs, vendor)

    fun checkAvailability(checkers: AsrVendorAvailabilityCheckers): AsrVendorReadiness = checkAsrVendorAvailability(vendor, checkers)
}

internal enum class AsrVendorDisplayTag {
    Online,
    Local,
    Recommended,
    Streaming,
    NonStreaming,
    PseudoStreaming,
    Custom,
    ChineseDialect,
    Accurate
}

internal enum class AsrVendorCapability {
    FileRecognition,
    StreamingRecognition,
    PseudoStreamingRecognition,
    NativePushPcmInput,
    PushPcmFileAdapter,
    LocalRecognition,
    BackupCandidate,
    CustomEndpoint
}

internal enum class AsrVendorAvailabilityLink {
    OnlineConfiguration,
    LocalModelInstallation
}

internal enum class AsrVendorLifecycleLink {
    None,
    LocalModelPreload
}

internal object AsrVendorRegistry {
    val descriptors: List<AsrVendorDescriptor> = listOf(
        descriptor(
            vendor = AsrVendor.SiliconFlow,
            displayNameResId = R.string.vendor_sf,
            pickerOrder = 0,
            tags = listOf(
                AsrVendorDisplayTag.Online,
                AsrVendorDisplayTag.NonStreaming,
                AsrVendorDisplayTag.Custom
            ),
            capabilities = onlineFileCapabilities(customEndpoint = true)
        ),
        descriptor(
            vendor = AsrVendor.Volc,
            displayNameResId = R.string.vendor_volc,
            pickerOrder = 1,
            tags = listOf(
                AsrVendorDisplayTag.Online,
                AsrVendorDisplayTag.Recommended,
                AsrVendorDisplayTag.Streaming,
                AsrVendorDisplayTag.NonStreaming,
                AsrVendorDisplayTag.ChineseDialect,
                AsrVendorDisplayTag.Accurate
            ),
            capabilities = onlineFileAndStreamCapabilities()
        ),
        descriptor(
            vendor = AsrVendor.ElevenLabs,
            displayNameResId = R.string.vendor_eleven,
            pickerOrder = 2,
            tags = listOf(
                AsrVendorDisplayTag.Online,
                AsrVendorDisplayTag.Streaming,
                AsrVendorDisplayTag.NonStreaming
            ),
            capabilities = onlineFileAndStreamCapabilities()
        ),
        descriptor(
            vendor = AsrVendor.OpenAI,
            displayNameResId = R.string.vendor_openai,
            pickerOrder = 3,
            tags = listOf(
                AsrVendorDisplayTag.Online,
                AsrVendorDisplayTag.Streaming,
                AsrVendorDisplayTag.NonStreaming,
                AsrVendorDisplayTag.Custom
            ),
            capabilities = onlineFileAndStreamCapabilities(customEndpoint = true)
        ),
        descriptor(
            vendor = AsrVendor.OpenRouter,
            legacyIds = setOf("open_router"),
            displayNameResId = R.string.vendor_openrouter,
            pickerOrder = 4,
            tags = listOf(
                AsrVendorDisplayTag.Online,
                AsrVendorDisplayTag.NonStreaming,
                AsrVendorDisplayTag.Custom
            ),
            capabilities = onlineFileCapabilities(customEndpoint = true)
        ),
        descriptor(
            vendor = AsrVendor.DashScope,
            displayNameResId = R.string.vendor_dashscope,
            pickerOrder = 5,
            tags = listOf(
                AsrVendorDisplayTag.Online,
                AsrVendorDisplayTag.Recommended,
                AsrVendorDisplayTag.Streaming,
                AsrVendorDisplayTag.NonStreaming,
                AsrVendorDisplayTag.ChineseDialect,
                AsrVendorDisplayTag.Accurate
            ),
            capabilities = onlineFileAndStreamCapabilities()
        ),
        descriptor(
            vendor = AsrVendor.Gemini,
            displayNameResId = R.string.vendor_gemini,
            pickerOrder = 6,
            tags = listOf(
                AsrVendorDisplayTag.Online,
                AsrVendorDisplayTag.NonStreaming,
                AsrVendorDisplayTag.Custom
            ),
            capabilities = onlineFileCapabilities(customEndpoint = true)
        ),
        descriptor(
            vendor = AsrVendor.MiMo,
            legacyIds = setOf("mimo_asr"),
            displayNameResId = R.string.vendor_mimo,
            pickerOrder = 7,
            tags = listOf(
                AsrVendorDisplayTag.Online,
                AsrVendorDisplayTag.NonStreaming,
                AsrVendorDisplayTag.Custom
            ),
            capabilities = onlineFileCapabilities(customEndpoint = true)
        ),
        descriptor(
            vendor = AsrVendor.Soniox,
            displayNameResId = R.string.vendor_soniox,
            pickerOrder = 8,
            tags = listOf(
                AsrVendorDisplayTag.Online,
                AsrVendorDisplayTag.Streaming,
                AsrVendorDisplayTag.NonStreaming,
                AsrVendorDisplayTag.Accurate
            ),
            capabilities = onlineFileAndStreamCapabilities()
        ),
        descriptor(
            vendor = AsrVendor.StepAudio,
            legacyIds = setOf("step_audio", "stepfun"),
            displayNameResId = R.string.vendor_stepaudio,
            pickerOrder = 9,
            tags = listOf(
                AsrVendorDisplayTag.Online,
                AsrVendorDisplayTag.NonStreaming,
                AsrVendorDisplayTag.ChineseDialect,
                AsrVendorDisplayTag.Accurate
            ),
            capabilities = onlineFileCapabilities()
        ),
        descriptor(
            vendor = AsrVendor.Zhipu,
            displayNameResId = R.string.vendor_zhipu,
            pickerOrder = 10,
            tags = listOf(
                AsrVendorDisplayTag.Online,
                AsrVendorDisplayTag.NonStreaming,
                AsrVendorDisplayTag.ChineseDialect
            ),
            capabilities = onlineFileCapabilities()
        ),
        descriptor(
            vendor = AsrVendor.Cohere,
            displayNameResId = R.string.vendor_cohere,
            pickerOrder = 11,
            tags = listOf(
                AsrVendorDisplayTag.Online,
                AsrVendorDisplayTag.NonStreaming,
                AsrVendorDisplayTag.Custom
            ),
            capabilities = onlineFileCapabilities()
        ),
        descriptor(
            vendor = AsrVendor.SenseVoice,
            displayNameResId = R.string.vendor_sensevoice,
            pickerOrder = 12,
            tags = listOf(
                AsrVendorDisplayTag.Local,
                AsrVendorDisplayTag.NonStreaming,
                AsrVendorDisplayTag.PseudoStreaming
            ),
            capabilities = localFileAndPseudoCapabilities()
        ),
        descriptor(
            vendor = AsrVendor.FunAsrNano,
            legacyIds = setOf("funasr"),
            displayNameResId = R.string.vendor_funasr_nano,
            pickerOrder = 13,
            tags = listOf(
                AsrVendorDisplayTag.Local,
                AsrVendorDisplayTag.NonStreaming,
                AsrVendorDisplayTag.ChineseDialect
            ),
            capabilities = localFileCapabilities()
        ),
        descriptor(
            vendor = AsrVendor.Qwen3Asr,
            legacyIds = setOf("qwen_asr", "qwen3asr"),
            displayNameResId = R.string.vendor_qwen3_asr,
            pickerOrder = 14,
            tags = listOf(
                AsrVendorDisplayTag.Local,
                AsrVendorDisplayTag.Recommended,
                AsrVendorDisplayTag.NonStreaming,
                AsrVendorDisplayTag.ChineseDialect,
                AsrVendorDisplayTag.Accurate
            ),
            capabilities = localFileCapabilities()
        ),
        descriptor(
            vendor = AsrVendor.Parakeet,
            legacyIds = setOf("nemo_parakeet"),
            displayNameResId = R.string.vendor_parakeet,
            pickerOrder = 15,
            tags = listOf(
                AsrVendorDisplayTag.Local,
                AsrVendorDisplayTag.NonStreaming
            ),
            capabilities = localFileCapabilities()
        ),
        descriptor(
            vendor = AsrVendor.FireRedAsr,
            legacyIds = setOf("telespeech"),
            displayNameResId = R.string.vendor_firered_asr,
            pickerOrder = 16,
            tags = listOf(
                AsrVendorDisplayTag.Local,
                AsrVendorDisplayTag.NonStreaming,
                AsrVendorDisplayTag.PseudoStreaming
            ),
            capabilities = localFileAndPseudoCapabilities()
        ),
        descriptor(
            vendor = AsrVendor.XAsr,
            legacyIds = setOf("x-asr", LEGACY_X_ASR_VENDOR_ID),
            displayNameResId = R.string.vendor_x_asr,
            pickerOrder = 17,
            tags = listOf(
                AsrVendorDisplayTag.Local,
                AsrVendorDisplayTag.Recommended,
                AsrVendorDisplayTag.Streaming,
                AsrVendorDisplayTag.Accurate
            ),
            capabilities = localStreamingCapabilities()
        )
    )

    private val descriptorByVendor: Map<AsrVendor, AsrVendorDescriptor> =
        descriptors.associateBy { it.vendor }

    private val descriptorById: Map<String, AsrVendorDescriptor> =
        descriptors.flatMap { descriptor ->
            descriptor.allIds.map { id -> id.lowercase() to descriptor }
        }.toMap()

    fun ordered(): List<AsrVendorDescriptor> = descriptors.sortedBy { it.pickerOrder }

    fun descriptorFor(vendor: AsrVendor): AsrVendorDescriptor = descriptorByVendor.getValue(vendor)

    fun findById(id: String?): AsrVendorDescriptor? = id?.lowercase()?.let { descriptorById[it] }

    fun vendorFromIdOrNull(id: String?): AsrVendor? = findById(id)?.vendor

    private fun descriptor(
        vendor: AsrVendor,
        legacyIds: Set<String> = emptySet(),
        @StringRes displayNameResId: Int,
        pickerOrder: Int,
        tags: List<AsrVendorDisplayTag>,
        capabilities: Set<AsrVendorCapability>
    ): AsrVendorDescriptor {
        val isLocal = AsrVendorCapability.LocalRecognition in capabilities
        return AsrVendorDescriptor(
            vendor = vendor,
            id = vendor.id,
            legacyIds = legacyIds,
            displayNameResId = displayNameResId,
            pickerOrder = pickerOrder,
            tags = tags,
            capabilities = capabilities,
            availabilityLink = if (isLocal) {
                AsrVendorAvailabilityLink.LocalModelInstallation
            } else {
                AsrVendorAvailabilityLink.OnlineConfiguration
            },
            lifecycleLink = if (isLocal) {
                AsrVendorLifecycleLink.LocalModelPreload
            } else {
                AsrVendorLifecycleLink.None
            }
        )
    }

    private fun onlineFileCapabilities(customEndpoint: Boolean = false): Set<AsrVendorCapability> = buildSet {
        add(AsrVendorCapability.FileRecognition)
        add(AsrVendorCapability.PushPcmFileAdapter)
        add(AsrVendorCapability.BackupCandidate)
        if (customEndpoint) add(AsrVendorCapability.CustomEndpoint)
    }

    private fun onlineFileAndStreamCapabilities(
        customEndpoint: Boolean = false
    ): Set<AsrVendorCapability> = onlineFileCapabilities(customEndpoint) + setOf(
        AsrVendorCapability.StreamingRecognition,
        AsrVendorCapability.NativePushPcmInput
    )

    private fun localFileCapabilities(): Set<AsrVendorCapability> = setOf(
        AsrVendorCapability.FileRecognition,
        AsrVendorCapability.PushPcmFileAdapter,
        AsrVendorCapability.LocalRecognition,
        AsrVendorCapability.BackupCandidate
    )

    private fun localFileAndPseudoCapabilities(): Set<AsrVendorCapability> = localFileCapabilities() + setOf(
        AsrVendorCapability.PseudoStreamingRecognition,
        AsrVendorCapability.NativePushPcmInput
    )

    private fun localStreamingCapabilities(): Set<AsrVendorCapability> = setOf(
        AsrVendorCapability.StreamingRecognition,
        AsrVendorCapability.NativePushPcmInput,
        AsrVendorCapability.LocalRecognition,
        AsrVendorCapability.BackupCandidate
    )
}
