// Local ASR model runtime catalog shared by recording entry points.
package com.brycewg.asrkb.asr

import android.content.Context
import androidx.annotation.StringRes
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs

internal data class AsrLocalModelCatalogEntry(
    val vendor: AsrVendor,
    val lifecycle: AsrLocalVendorLifecycle,
    @param:StringRes val missingModelErrorRes: Int
)

internal object AsrLocalModelCatalog {
    private val entryByVendor: Map<AsrVendor, AsrLocalModelCatalogEntry> by lazy {
        listOf(
            entry(AsrVendor.SenseVoice, R.string.error_sensevoice_model_missing),
            entry(AsrVendor.FunAsrNano, R.string.error_funasr_model_missing),
            entry(AsrVendor.Qwen3Asr, R.string.error_qwen3_asr_model_missing),
            entry(AsrVendor.Parakeet, R.string.error_parakeet_model_missing),
            entry(AsrVendor.GigaAm, R.string.error_giga_am_model_missing),
            entry(AsrVendor.FireRedAsr, R.string.error_firered_asr_model_missing),
            entry(AsrVendor.XAsr, R.string.error_x_asr_model_missing)
        ).associateBy { it.vendor }
    }

    fun all(): List<AsrLocalModelCatalogEntry> = entryByVendor.values.toList()

    fun entryFor(vendor: AsrVendor): AsrLocalModelCatalogEntry? = entryByVendor[vendor]

    @StringRes
    fun missingModelErrorRes(vendor: AsrVendor): Int? = entryFor(vendor)?.missingModelErrorRes

    fun modelStatus(context: Context, prefs: Prefs, vendor: AsrVendor): LocalModelCheck<*>? = entryFor(vendor)?.lifecycle?.modelStatus(context, prefs)

    fun isModelReady(context: Context, prefs: Prefs, vendor: AsrVendor): Boolean = modelStatus(context, prefs, vendor) is LocalModelCheck.Ready

    fun unload(vendor: AsrVendor): Boolean {
        val lifecycle = entryFor(vendor)?.lifecycle ?: return false
        lifecycle.unload()
        return true
    }

    private fun entry(
        vendor: AsrVendor,
        @StringRes missingModelErrorRes: Int
    ): AsrLocalModelCatalogEntry = AsrLocalModelCatalogEntry(
        vendor = vendor,
        lifecycle = requireNotNull(AsrLocalVendorLifecycles.lifecycleFor(vendor)) {
            "Missing local ASR lifecycle for model catalog $vendor"
        },
        missingModelErrorRes = missingModelErrorRes
    )
}
