/**
 * LLM 供应商可用性判断与分组工具：
 * - 内置供应商：按 API Key 是否已配置判断
 * - SF 免费服务：按是否启用免费服务 / 或付费 Key 是否已配置判断
 * - 自定义供应商：按活动 Provider 的 endpoint/model 是否已配置判断
 */
package com.brycewg.asrkb.asr

import android.util.Log
import com.brycewg.asrkb.store.Prefs

internal data class LlmVendorPartition(
    val configured: List<LlmVendor>,
    val unconfigured: List<LlmVendor>
)

internal fun partitionLlmVendorsByConfigured(
    prefs: Prefs,
    vendors: List<LlmVendor>
): LlmVendorPartition {
    val configured = mutableListOf<LlmVendor>()
    val unconfigured = mutableListOf<LlmVendor>()
    vendors.forEach { vendor ->
        if (isLlmVendorConfigured(prefs, vendor)) {
            configured.add(vendor)
        } else {
            unconfigured.add(vendor)
        }
    }
    return LlmVendorPartition(
        configured = configured,
        unconfigured = unconfigured
    )
}

internal fun isLlmVendorConfigured(prefs: Prefs, vendor: LlmVendor): Boolean = try {
    when (vendor) {
        LlmVendor.SF_FREE -> {
            if (prefs.sfFreeLlmUsePaidKey) {
                prefs.getLlmVendorApiKey(LlmVendor.SF_FREE).isNotBlank()
            } else {
                prefs.sfFreeLlmEnabled
            }
        }

        LlmVendor.CUSTOM -> {
            val provider = prefs.getActiveLlmProvider()
            provider != null &&
                provider.endpoint.isNotBlank() &&
                provider.model.isNotBlank()
        }

        LlmVendor.TYPESAFE -> when (prefs.jevClassifierProvider) {
            com.brycewg.asrkb.store.JevClassifierProvider.TYPESAFE -> prefs.jevTypesafeApiKey.isNotBlank()
            com.brycewg.asrkb.store.JevClassifierProvider.OPENROUTER -> prefs.jevOpenRouterApiKey.isNotBlank()
            com.brycewg.asrkb.store.JevClassifierProvider.CLOUDFLARE ->
                prefs.jevCloudflareApiKey.isNotBlank() && prefs.jevCloudflareAccountId.isNotBlank()
        }

        else -> prefs.getLlmVendorApiKey(vendor).isNotBlank()
    }
} catch (t: Throwable) {
    Log.w(TAG, "Failed to check LLM vendor availability: $vendor", t)
    false
}

private const val TAG = "LlmVendorAvailability"
