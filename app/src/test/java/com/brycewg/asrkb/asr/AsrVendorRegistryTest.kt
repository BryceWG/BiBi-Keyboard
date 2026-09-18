// Tests the lightweight ASR supplier registry metadata.
package com.brycewg.asrkb.asr

import com.brycewg.asrkb.R
import com.brycewg.asrkb.ui.AsrVendorTag
import com.brycewg.asrkb.ui.AsrVendorUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrVendorRegistryTest {
    @Test
    fun descriptorsCoverEverySupportedVendor() {
        val descriptors = AsrVendorRegistry.descriptors

        assertEquals(AsrVendor.entries.size, descriptors.size)
        assertEquals(AsrVendor.entries.toSet(), descriptors.map { it.vendor }.toSet())
        AsrVendor.entries.forEach { vendor ->
            assertSame(vendor, AsrVendorRegistry.descriptorFor(vendor).vendor)
        }
    }

    @Test
    fun canonicalIdsLegacyIdsAndPickerOrdersAreUnique() {
        val descriptors = AsrVendorRegistry.descriptors
        val canonicalIds = descriptors.map { it.id }
        val allIds = descriptors.flatMap { it.allIds }
        val pickerOrders = descriptors.map { it.pickerOrder }

        assertEquals(canonicalIds.toSet().size, canonicalIds.size)
        assertEquals(allIds.toSet().size, allIds.size)
        assertEquals(pickerOrders.toSet().size, pickerOrders.size)
        assertEquals((0 until descriptors.size).toList(), pickerOrders.sorted())
        descriptors.forEach { descriptor ->
            assertEquals(descriptor.vendor.id, descriptor.id)
            assertFalse(descriptor.legacyIds.contains(descriptor.id))
            assertTrue(descriptor.displayNameResId != 0)
            assertTrue(descriptor.tags.isNotEmpty())
            assertTrue(descriptor.capabilities.isNotEmpty())
        }
    }

    @Test
    fun orderedRegistryMatchesCurrentPickerOrder() {
        assertEquals(
            listOf(
                AsrVendor.SiliconFlow,
                AsrVendor.Volc,
                AsrVendor.ElevenLabs,
                AsrVendor.OpenAI,
                AsrVendor.OpenRouter,
                AsrVendor.DashScope,
                AsrVendor.Gemini,
                AsrVendor.MiMo,
                AsrVendor.Soniox,
                AsrVendor.StepAudio,
                AsrVendor.Zhipu,
                AsrVendor.Cohere,
                AsrVendor.SenseVoice,
                AsrVendor.FunAsrNano,
                AsrVendor.Qwen3Asr,
                AsrVendor.Parakeet,
                AsrVendor.FireRedAsr,
                AsrVendor.XAsr
            ),
            AsrVendorRegistry.ordered().map { it.vendor }
        )
    }

    @Test
    fun asrVendorUiDisplayMetadataIsDerivedFromRegistry() {
        assertEquals(
            AsrVendorRegistry.ordered().map { it.vendor },
            AsrVendorUi.ordered()
        )

        AsrVendor.entries.forEach { vendor ->
            val descriptor = AsrVendorRegistry.descriptorFor(vendor)
            assertEquals(
                "display name resource for $vendor",
                descriptor.displayNameResId,
                AsrVendorUi.displayNameResId(vendor)
            )
            assertEquals(
                "UI tags for $vendor",
                descriptor.tags.map { it.toUiTag() },
                AsrVendorUi.tags(vendor)
            )
        }
    }

    @Test
    fun displayNamesAndTagsMatchCurrentUiMetadata() {
        val expected = mapOf(
            AsrVendor.Volc to metadata(
                R.string.vendor_volc,
                AsrVendorDisplayTag.Online,
                AsrVendorDisplayTag.Recommended,
                AsrVendorDisplayTag.Streaming,
                AsrVendorDisplayTag.NonStreaming,
                AsrVendorDisplayTag.ChineseDialect,
                AsrVendorDisplayTag.Accurate
            ),
            AsrVendor.SiliconFlow to metadata(
                R.string.vendor_sf,
                AsrVendorDisplayTag.Online,
                AsrVendorDisplayTag.NonStreaming,
                AsrVendorDisplayTag.Custom
            ),
            AsrVendor.ElevenLabs to metadata(
                R.string.vendor_eleven,
                AsrVendorDisplayTag.Online,
                AsrVendorDisplayTag.Streaming,
                AsrVendorDisplayTag.NonStreaming
            ),
            AsrVendor.OpenAI to metadata(
                R.string.vendor_openai,
                AsrVendorDisplayTag.Online,
                AsrVendorDisplayTag.Streaming,
                AsrVendorDisplayTag.NonStreaming,
                AsrVendorDisplayTag.Custom
            ),
            AsrVendor.OpenRouter to metadata(
                R.string.vendor_openrouter,
                AsrVendorDisplayTag.Online,
                AsrVendorDisplayTag.NonStreaming,
                AsrVendorDisplayTag.Custom
            ),
            AsrVendor.DashScope to metadata(
                R.string.vendor_dashscope,
                AsrVendorDisplayTag.Online,
                AsrVendorDisplayTag.Recommended,
                AsrVendorDisplayTag.Streaming,
                AsrVendorDisplayTag.NonStreaming,
                AsrVendorDisplayTag.ChineseDialect,
                AsrVendorDisplayTag.Accurate
            ),
            AsrVendor.Gemini to metadata(
                R.string.vendor_gemini,
                AsrVendorDisplayTag.Online,
                AsrVendorDisplayTag.NonStreaming,
                AsrVendorDisplayTag.Custom
            ),
            AsrVendor.MiMo to metadata(
                R.string.vendor_mimo,
                AsrVendorDisplayTag.Online,
                AsrVendorDisplayTag.NonStreaming,
                AsrVendorDisplayTag.Custom
            ),
            AsrVendor.Soniox to metadata(
                R.string.vendor_soniox,
                AsrVendorDisplayTag.Online,
                AsrVendorDisplayTag.Streaming,
                AsrVendorDisplayTag.NonStreaming,
                AsrVendorDisplayTag.Accurate
            ),
            AsrVendor.StepAudio to metadata(
                R.string.vendor_stepaudio,
                AsrVendorDisplayTag.Online,
                AsrVendorDisplayTag.NonStreaming,
                AsrVendorDisplayTag.ChineseDialect,
                AsrVendorDisplayTag.Accurate
            ),
            AsrVendor.Zhipu to metadata(
                R.string.vendor_zhipu,
                AsrVendorDisplayTag.Online,
                AsrVendorDisplayTag.NonStreaming,
                AsrVendorDisplayTag.ChineseDialect
            ),
            AsrVendor.Cohere to metadata(
                R.string.vendor_cohere,
                AsrVendorDisplayTag.Online,
                AsrVendorDisplayTag.NonStreaming,
                AsrVendorDisplayTag.Custom
            ),
            AsrVendor.SenseVoice to metadata(
                R.string.vendor_sensevoice,
                AsrVendorDisplayTag.Local,
                AsrVendorDisplayTag.NonStreaming,
                AsrVendorDisplayTag.PseudoStreaming
            ),
            AsrVendor.FunAsrNano to metadata(
                R.string.vendor_funasr_nano,
                AsrVendorDisplayTag.Local,
                AsrVendorDisplayTag.NonStreaming,
                AsrVendorDisplayTag.ChineseDialect
            ),
            AsrVendor.Qwen3Asr to metadata(
                R.string.vendor_qwen3_asr,
                AsrVendorDisplayTag.Local,
                AsrVendorDisplayTag.Recommended,
                AsrVendorDisplayTag.NonStreaming,
                AsrVendorDisplayTag.ChineseDialect,
                AsrVendorDisplayTag.Accurate
            ),
            AsrVendor.Parakeet to metadata(
                R.string.vendor_parakeet,
                AsrVendorDisplayTag.Local,
                AsrVendorDisplayTag.NonStreaming
            ),
            AsrVendor.FireRedAsr to metadata(
                R.string.vendor_firered_asr,
                AsrVendorDisplayTag.Local,
                AsrVendorDisplayTag.NonStreaming,
                AsrVendorDisplayTag.PseudoStreaming
            ),
            AsrVendor.XAsr to metadata(
                R.string.vendor_x_asr,
                AsrVendorDisplayTag.Local,
                AsrVendorDisplayTag.Recommended,
                AsrVendorDisplayTag.Streaming,
                AsrVendorDisplayTag.Accurate
            )
        )

        expected.forEach { (vendor, metadata) ->
            val descriptor = AsrVendorRegistry.descriptorFor(vendor)
            assertEquals("displayNameResId for $vendor", metadata.displayNameResId, descriptor.displayNameResId)
            assertEquals("tags for $vendor", metadata.tags, descriptor.tags)
        }
    }

    @Test
    fun capabilityMetadataMatchesCurrentConstructionBaseline() {
        val streaming = setOf(
            AsrVendor.Volc,
            AsrVendor.ElevenLabs,
            AsrVendor.OpenAI,
            AsrVendor.DashScope,
            AsrVendor.Soniox,
            AsrVendor.XAsr
        )
        val pseudoStreaming = setOf(AsrVendor.SenseVoice, AsrVendor.FireRedAsr)
        val local = setOf(
            AsrVendor.SenseVoice,
            AsrVendor.FunAsrNano,
            AsrVendor.Qwen3Asr,
            AsrVendor.Parakeet,
            AsrVendor.FireRedAsr,
            AsrVendor.XAsr
        )
        val customEndpoint = setOf(
            AsrVendor.SiliconFlow,
            AsrVendor.OpenAI,
            AsrVendor.OpenRouter,
            AsrVendor.Gemini,
            AsrVendor.MiMo
        )

        AsrVendorRegistry.descriptors.forEach { descriptor ->
            val capabilities = descriptor.capabilities
            assertEquals(
                "streaming capability for ${descriptor.vendor}",
                descriptor.vendor in streaming,
                AsrVendorCapability.StreamingRecognition in capabilities
            )
            assertEquals(
                "pseudo streaming capability for ${descriptor.vendor}",
                descriptor.vendor in pseudoStreaming,
                AsrVendorCapability.PseudoStreamingRecognition in capabilities
            )
            assertEquals(
                "local capability for ${descriptor.vendor}",
                descriptor.vendor in local,
                AsrVendorCapability.LocalRecognition in capabilities
            )
            assertEquals(
                "custom endpoint capability for ${descriptor.vendor}",
                descriptor.vendor in customEndpoint,
                AsrVendorCapability.CustomEndpoint in capabilities
            )
            assertEquals(
                "file capability for ${descriptor.vendor}",
                descriptor.vendor != AsrVendor.XAsr,
                AsrVendorCapability.FileRecognition in capabilities
            )
            assertTrue(
                "all current suppliers can be used by backup construction: ${descriptor.vendor}",
                AsrVendorCapability.BackupCandidate in capabilities
            )
            assertTrue(
                "all current suppliers have some Push PCM path: ${descriptor.vendor}",
                AsrVendorCapability.NativePushPcmInput in capabilities ||
                    AsrVendorCapability.PushPcmFileAdapter in capabilities
            )
        }
    }

    @Test
    fun localDescriptorsPointAtLocalAvailabilityAndLifecycleLinks() {
        AsrVendorRegistry.descriptors.forEach { descriptor ->
            val isLocal = AsrVendorCapability.LocalRecognition in descriptor.capabilities
            assertEquals(
                "availability link for ${descriptor.vendor}",
                if (isLocal) {
                    AsrVendorAvailabilityLink.LocalModelInstallation
                } else {
                    AsrVendorAvailabilityLink.OnlineConfiguration
                },
                descriptor.availabilityLink
            )
            assertEquals(
                "lifecycle link for ${descriptor.vendor}",
                if (isLocal) {
                    AsrVendorLifecycleLink.LocalModelPreload
                } else {
                    AsrVendorLifecycleLink.None
                },
                descriptor.lifecycleLink
            )
            assertEquals(
                "local lifecycle object for ${descriptor.vendor}",
                isLocal,
                descriptor.localLifecycle != null
            )
        }
    }

    @Test
    fun conditionalCapabilityMetadataCarriesRequiredDisplayContract() {
        AsrVendorRegistry.descriptors.forEach { descriptor ->
            val capabilities = descriptor.capabilities
            val tags = descriptor.tags

            assertEquals(
                "online/local tag for ${descriptor.vendor}",
                AsrVendorCapability.LocalRecognition in capabilities,
                AsrVendorDisplayTag.Local in tags
            )
            assertEquals(
                "online tag for ${descriptor.vendor}",
                AsrVendorCapability.LocalRecognition !in capabilities,
                AsrVendorDisplayTag.Online in tags
            )
            assertEquals(
                "streaming tag for ${descriptor.vendor}",
                AsrVendorCapability.StreamingRecognition in capabilities,
                AsrVendorDisplayTag.Streaming in tags
            )
            assertEquals(
                "pseudo-streaming tag for ${descriptor.vendor}",
                AsrVendorCapability.PseudoStreamingRecognition in capabilities,
                AsrVendorDisplayTag.PseudoStreaming in tags
            )
            if (AsrVendorCapability.CustomEndpoint in capabilities) {
                assertTrue(
                    "custom endpoint tag for ${descriptor.vendor}",
                    AsrVendorDisplayTag.Custom in tags
                )
            }
            if (
                AsrVendorDisplayTag.Custom in tags &&
                AsrVendorCapability.CustomEndpoint !in capabilities
            ) {
                assertSame(
                    "Cohere is the named custom-model-only tag exception",
                    AsrVendor.Cohere,
                    descriptor.vendor
                )
            }
        }
    }

    @Test
    fun legacyIdLookupMatchesCurrentVendorParserAliases() {
        val aliases = mapOf(
            "volc" to AsrVendor.Volc,
            "siliconflow" to AsrVendor.SiliconFlow,
            "elevenlabs" to AsrVendor.ElevenLabs,
            "openai" to AsrVendor.OpenAI,
            "openrouter" to AsrVendor.OpenRouter,
            "open_router" to AsrVendor.OpenRouter,
            "dashscope" to AsrVendor.DashScope,
            "gemini" to AsrVendor.Gemini,
            "soniox" to AsrVendor.Soniox,
            "stepaudio" to AsrVendor.StepAudio,
            "step_audio" to AsrVendor.StepAudio,
            "stepfun" to AsrVendor.StepAudio,
            "zhipu" to AsrVendor.Zhipu,
            "cohere" to AsrVendor.Cohere,
            "sensevoice" to AsrVendor.SenseVoice,
            "funasr_nano" to AsrVendor.FunAsrNano,
            "funasr" to AsrVendor.FunAsrNano,
            "qwen3_asr" to AsrVendor.Qwen3Asr,
            "qwen_asr" to AsrVendor.Qwen3Asr,
            "qwen3asr" to AsrVendor.Qwen3Asr,
            "parakeet" to AsrVendor.Parakeet,
            "nemo_parakeet" to AsrVendor.Parakeet,
            "firered_asr" to AsrVendor.FireRedAsr,
            "telespeech" to AsrVendor.FireRedAsr,
            "x_asr" to AsrVendor.XAsr,
            "x-asr" to AsrVendor.XAsr,
            LEGACY_X_ASR_VENDOR_ID to AsrVendor.XAsr,
            "mimo" to AsrVendor.MiMo,
            "mimo_asr" to AsrVendor.MiMo
        )

        aliases.forEach { (id, vendor) ->
            val descriptor = AsrVendorRegistry.findById(id)

            assertNotNull("descriptor for $id", descriptor)
            assertSame("registry lookup for $id", vendor, descriptor?.vendor)
            assertSame("case-insensitive registry lookup for $id", vendor, AsrVendorRegistry.findById(id.uppercase())?.vendor)
            assertSame("current parser for $id", AsrVendor.fromId(id), descriptor?.vendor)
        }
        assertNull(AsrVendorRegistry.findById(null))
        assertNull(AsrVendorRegistry.findById("unknown_vendor"))
        assertSame(AsrVendor.Volc, AsrVendor.fromId(null))
        assertSame(AsrVendor.Volc, AsrVendor.fromId("unknown_vendor"))
    }

    private fun metadata(
        displayNameResId: Int,
        vararg tags: AsrVendorDisplayTag
    ): ExpectedDisplayMetadata = ExpectedDisplayMetadata(
        displayNameResId = displayNameResId,
        tags = tags.toList()
    )

    private data class ExpectedDisplayMetadata(
        val displayNameResId: Int,
        val tags: List<AsrVendorDisplayTag>
    )

    private fun AsrVendorDisplayTag.toUiTag(): AsrVendorTag = when (this) {
        AsrVendorDisplayTag.Online -> AsrVendorTag.Online
        AsrVendorDisplayTag.Local -> AsrVendorTag.Local
        AsrVendorDisplayTag.Recommended -> AsrVendorTag.Recommended
        AsrVendorDisplayTag.Streaming -> AsrVendorTag.Streaming
        AsrVendorDisplayTag.NonStreaming -> AsrVendorTag.NonStreaming
        AsrVendorDisplayTag.PseudoStreaming -> AsrVendorTag.PseudoStreaming
        AsrVendorDisplayTag.Custom -> AsrVendorTag.Custom
        AsrVendorDisplayTag.ChineseDialect -> AsrVendorTag.ChineseDialect
        AsrVendorDisplayTag.Accurate -> AsrVendorTag.Accurate
    }
}
