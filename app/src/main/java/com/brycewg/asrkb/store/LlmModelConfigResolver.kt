/**
 * 可复用的 LLM 模型配置解析。
 *
 * 归属模块：store
 *
 * 解析只产出“供应商 + 明确模型名”，地址、凭证、温度、推理参数一律从供应商配置实时读取，
 * 因此用户在设置页改动供应商配置后立即生效。
 */
package com.brycewg.asrkb.store

import com.brycewg.asrkb.BuildConfig
import com.brycewg.asrkb.asr.LlmReasoningThreshold
import com.brycewg.asrkb.asr.LlmVendor

/** 模型配置不可用的稳定原因码。 */
enum class LlmModelUnavailableReason(val code: String) {
    /** 当前没有可解析的激活配置（旧数据兼容分支也拿不到 endpoint）。 */
    NO_ACTIVE_CONFIG("no_active_config"),

    /** 引用的自定义供应商配置不存在。 */
    PROVIDER_MISSING("provider_missing"),

    /** 供应商需要 API Key 但未配置。 */
    MISSING_API_KEY("missing_api_key"),

    /** 未配置 endpoint。 */
    MISSING_ENDPOINT("missing_endpoint"),

    /** 未指定模型。 */
    MISSING_MODEL("missing_model")
}

/**
 * 解析完成、可直接用于发起 Chat Completions 请求的模型配置。
 */
data class ResolvedLlmModelConfig(
    val vendor: LlmVendor,
    /** 内置供应商 ID；自定义配置固定为 "custom"。 */
    val vendorId: String,
    val customProviderId: String?,
    val endpoint: String,
    val apiKey: String,
    val model: String,
    val temperature: Double,
    val reasoningCharThreshold: Int,
    val useCustomReasoningParams: Boolean,
    val reasoningParamsOnJson: String,
    val reasoningParamsOffJson: String,
    /** 请求模式能力键前缀：`vendor.id` 或 `custom:<providerId>`。 */
    val capabilityIdentity: String
) {
    /** 与 [com.brycewg.asrkb.asr.LlmPostProcessor] 内部一致的请求模式能力键。 */
    val requestModeCapabilityKey: String
        get() = "$capabilityIdentity|${endpoint.trim().trimEnd('/')}"
}

sealed interface LlmModelResolution {
    data class Resolved(val config: ResolvedLlmModelConfig) : LlmModelResolution

    data class Unavailable(val reason: LlmModelUnavailableReason) : LlmModelResolution
}

/** 供设置页展示的模型引用摘要。 */
data class PromptSelectorModelSummary(
    val ref: PromptSelectorModelRef,
    val model: String,
    /** 内置供应商的本地化名称资源；自定义配置为 null。 */
    val vendorNameResId: Int?,
    /** 自定义供应商配置的名称；内置供应商为 null。 */
    val customProviderName: String?,
    /** 自定义供应商配置 ID；内置供应商为 null。 */
    val customProviderId: String?,
    val available: Boolean,
    val unavailableReason: LlmModelUnavailableReason?
)

/** 模型选择第一层：内置供应商及其配置状态。 */
data class LlmVendorOption(
    val vendor: LlmVendor,
    val configured: Boolean
)

/** 模型选择第一层：自定义供应商配置及其配置状态。 */
data class LlmCustomProviderOption(
    val providerId: String,
    val name: String,
    val configured: Boolean
)

object LlmModelConfigResolver {

    /**
     * 解析当前激活的润色模型配置（“跟随默认”）。
     *
     * 与 [LlmPostProcessor] 的历史行为保持一致：优先新供应商架构，必要时回退旧单配置字段。
     */
    fun resolveActiveConfig(prefs: Prefs): ResolvedLlmModelConfig {
        val vendor = prefs.llmVendor

        // SiliconFlow 免费服务特殊处理：使用内置 Key
        if (vendor == LlmVendor.SF_FREE && !prefs.sfFreeLlmUsePaidKey) {
            val effective = prefs.getEffectiveLlmConfig()
            return ResolvedLlmModelConfig(
                vendor = vendor,
                vendorId = vendor.id,
                customProviderId = null,
                endpoint = Prefs.SF_CHAT_COMPLETIONS_ENDPOINT,
                apiKey = BuildConfig.SF_FREE_API_KEY,
                model = prefs.sfFreeLlmModel,
                temperature = Prefs.DEFAULT_LLM_TEMPERATURE.toDouble(),
                reasoningCharThreshold = prefs.getLlmVendorReasoningCharThreshold(vendor),
                useCustomReasoningParams = effective?.useCustomReasoningParams ?: false,
                reasoningParamsOnJson = effective?.reasoningParamsOnJson
                    ?: Prefs.DEFAULT_CUSTOM_REASONING_PARAMS_ON_JSON,
                reasoningParamsOffJson = effective?.reasoningParamsOffJson
                    ?: Prefs.DEFAULT_CUSTOM_REASONING_PARAMS_OFF_JSON,
                capabilityIdentity = vendor.id
            )
        }

        // 统一的供应商配置
        val config = prefs.getEffectiveLlmConfig()
        if (config != null) {
            val customProviderId = if (config.vendor == LlmVendor.CUSTOM) {
                prefs.getActiveLlmProvider()?.id ?: prefs.activeLlmId.ifBlank { "default" }
            } else {
                null
            }
            return resolvedFromEffective(
                config = config,
                customProviderId = customProviderId,
                capabilityIdentity = customProviderId?.let { "custom:$it" } ?: config.vendor.id
            )
        }

        // 回退到旧逻辑（兼容性）
        val active = prefs.getActiveLlmProvider()
        val fallbackEndpoint = if (vendor.hasBuiltinEndpoint) {
            vendor.endpoint
        } else {
            active?.endpoint ?: prefs.llmEndpoint
        }
        return ResolvedLlmModelConfig(
            vendor = vendor,
            vendorId = vendor.id,
            customProviderId = if (vendor == LlmVendor.CUSTOM) {
                active?.id ?: prefs.activeLlmId.ifBlank { "default" }
            } else {
                null
            },
            endpoint = fallbackEndpoint,
            apiKey = active?.apiKey ?: prefs.llmApiKey,
            model = active?.model ?: prefs.llmModel,
            temperature = (active?.temperature ?: prefs.llmTemperature).toDouble(),
            reasoningCharThreshold = if (vendor == LlmVendor.CUSTOM) {
                active?.resolvedReasoningCharThreshold() ?: LlmReasoningThreshold.NEVER
            } else {
                prefs.getLlmVendorReasoningCharThreshold(vendor)
            },
            useCustomReasoningParams = false,
            reasoningParamsOnJson = Prefs.DEFAULT_CUSTOM_REASONING_PARAMS_ON_JSON,
            reasoningParamsOffJson = Prefs.DEFAULT_CUSTOM_REASONING_PARAMS_OFF_JSON,
            capabilityIdentity = if (vendor == LlmVendor.CUSTOM) {
                "custom:${active?.id ?: prefs.activeLlmId.ifBlank { "default" }}"
            } else {
                vendor.id
            }
        )
    }

    /** 解析任意模型引用。 */
    fun resolve(prefs: Prefs, ref: PromptSelectorModelRef): LlmModelResolution {
        when (ref) {
            PromptSelectorModelRef.FollowDefault -> {
                val config = resolveActiveConfig(prefs)
                return validate(config)?.let { LlmModelResolution.Unavailable(it) }
                    ?: LlmModelResolution.Resolved(config)
            }

            is PromptSelectorModelRef.Builtin -> {
                val vendor = LlmVendor.builtinVendors().firstOrNull { it.id == ref.vendorId }
                if (vendor == null) {
                    return LlmModelResolution.Unavailable(LlmModelUnavailableReason.PROVIDER_MISSING)
                }
                val config = prefs.effectiveLlmConfigForVendor(vendor, ref.model)
                    ?.let { resolvedFromEffective(it, null, vendor.id) }
                    ?.let { base -> applyFreeTierKeyIfNeeded(prefs, vendor, base) }
                return when (config) {
                    null -> LlmModelResolution.Unavailable(unavailableReasonFor(vendor))
                    else -> validate(config)?.let { LlmModelResolution.Unavailable(it) }
                        ?: LlmModelResolution.Resolved(config)
                }
            }

            is PromptSelectorModelRef.Custom -> {
                val provider = prefs.getLlmProviders().firstOrNull { it.id == ref.providerId }
                    ?: return LlmModelResolution.Unavailable(LlmModelUnavailableReason.PROVIDER_MISSING)
                if (provider.endpoint.isBlank()) {
                    return LlmModelResolution.Unavailable(LlmModelUnavailableReason.MISSING_ENDPOINT)
                }
                val config = ResolvedLlmModelConfig(
                    vendor = LlmVendor.CUSTOM,
                    vendorId = LlmVendor.CUSTOM.id,
                    customProviderId = provider.id,
                    endpoint = provider.endpoint,
                    apiKey = provider.apiKey,
                    model = ref.model.trim().takeUnless { it.isEmpty() } ?: provider.model,
                    temperature = provider.temperature.toDouble(),
                    reasoningCharThreshold = provider.resolvedReasoningCharThreshold(),
                    useCustomReasoningParams = provider.useCustomReasoningParams,
                    reasoningParamsOnJson = provider.reasoningParamsOnJson,
                    reasoningParamsOffJson = provider.reasoningParamsOffJson,
                    capabilityIdentity = "custom:${provider.id}"
                )
                return validate(config)?.let { LlmModelResolution.Unavailable(it) }
                    ?: LlmModelResolution.Resolved(config)
            }
        }
    }

    /** 未解析配置时的校验：地址与模型必须有值。 */
    fun validate(config: ResolvedLlmModelConfig): LlmModelUnavailableReason? = when {
        config.endpoint.isBlank() -> LlmModelUnavailableReason.MISSING_ENDPOINT
        config.model.isBlank() -> LlmModelUnavailableReason.MISSING_MODEL
        config.vendor.requiresApiKey && config.apiKey.isBlank() ->
            LlmModelUnavailableReason.MISSING_API_KEY
        else -> null
    }

    /** 指定内置供应商当前是否配置完整（用于“未配置状态”展示与启用前校验）。 */
    fun isBuiltinVendorConfigured(prefs: Prefs, vendor: LlmVendor): Boolean = prefs.effectiveLlmConfigForVendor(vendor, null) != null

    fun isCustomProviderConfigured(prefs: Prefs, providerId: String): Boolean {
        val provider = prefs.getLlmProviders().firstOrNull { it.id == providerId } ?: return false
        return provider.endpoint.isNotBlank() && provider.apiKey.isNotBlank()
    }

    fun builtinVendorOptions(prefs: Prefs): List<LlmVendorOption> = LlmVendor.builtinVendors().map { LlmVendorOption(it, isBuiltinVendorConfigured(prefs, it)) }

    fun customProviderOptions(prefs: Prefs): List<LlmCustomProviderOption> = prefs.getLlmProviders().map {
        LlmCustomProviderOption(
            providerId = it.id,
            name = it.name,
            configured = isCustomProviderConfigured(prefs, it.id)
        )
    }

    /**
     * 已保存的模型列表（含当前引用模型，去重保序）。
     *
     * 不提供自由输入：候选来源只有供应商配置里已保存的模型集合。
     */
    fun savedModels(prefs: Prefs, ref: PromptSelectorModelRef): List<String> {
        val currentModel = when (ref) {
            PromptSelectorModelRef.FollowDefault -> resolveActiveConfig(prefs).model
            is PromptSelectorModelRef.Builtin -> ref.model
            is PromptSelectorModelRef.Custom -> ref.model
        }.trim()
        val stored = when (ref) {
            PromptSelectorModelRef.FollowDefault -> {
                val active = resolveActiveConfig(prefs)
                active.customProviderId?.let { id ->
                    prefs.getLlmProviders().firstOrNull { it.id == id }?.models.orEmpty()
                } ?: prefs.getLlmVendorModels(active.vendor)
            }

            is PromptSelectorModelRef.Builtin -> {
                val vendor = LlmVendor.builtinVendors().firstOrNull { it.id == ref.vendorId }
                    ?: return emptyList()
                if (vendor.id == LlmVendor.CUSTOM.id) emptyList() else prefs.getLlmVendorModels(vendor)
            }

            is PromptSelectorModelRef.Custom -> prefs.getLlmProviders()
                .firstOrNull { it.id == ref.providerId }
                ?.models
                .orEmpty()
        }
        return (listOf(currentModel) + stored)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /** 设置页摘要：模型名 + 供应商展示信息 + 可用性。 */
    fun summarize(prefs: Prefs, ref: PromptSelectorModelRef): PromptSelectorModelSummary {
        val resolution = resolve(prefs, ref)
        return when (ref) {
            PromptSelectorModelRef.FollowDefault -> PromptSelectorModelSummary(
                ref = ref,
                model = (resolution as? LlmModelResolution.Resolved)?.config?.model
                    ?: resolveActiveConfig(prefs).model,
                vendorNameResId = null,
                customProviderName = null,
                customProviderId = null,
                available = resolution is LlmModelResolution.Resolved,
                unavailableReason = (resolution as? LlmModelResolution.Unavailable)?.reason
            )

            is PromptSelectorModelRef.Builtin -> {
                val vendor = LlmVendor.builtinVendors().firstOrNull { it.id == ref.vendorId }
                PromptSelectorModelSummary(
                    ref = ref,
                    model = (resolution as? LlmModelResolution.Resolved)?.config?.model ?: ref.model,
                    vendorNameResId = vendor?.displayNameResId,
                    customProviderName = null,
                    customProviderId = null,
                    available = resolution is LlmModelResolution.Resolved,
                    unavailableReason = (resolution as? LlmModelResolution.Unavailable)?.reason
                )
            }

            is PromptSelectorModelRef.Custom -> {
                val provider = prefs.getLlmProviders().firstOrNull { it.id == ref.providerId }
                PromptSelectorModelSummary(
                    ref = ref,
                    model = (resolution as? LlmModelResolution.Resolved)?.config?.model ?: ref.model,
                    vendorNameResId = null,
                    customProviderName = provider?.name,
                    customProviderId = ref.providerId,
                    available = resolution is LlmModelResolution.Resolved,
                    unavailableReason = (resolution as? LlmModelResolution.Unavailable)?.reason
                )
            }
        }
    }

    /**
     * SiliconFlow 免费服务不使用用户凭证：地址走内置 chat/completions 端点、Key 由构建期注入，
     * 与 [resolveActiveConfig] 的免费分支保持一致。付费 Key 路径不受影响。
     */
    private fun applyFreeTierKeyIfNeeded(
        prefs: Prefs,
        vendor: LlmVendor,
        config: ResolvedLlmModelConfig
    ): ResolvedLlmModelConfig {
        if (vendor != LlmVendor.SF_FREE || prefs.sfFreeLlmUsePaidKey) return config
        return config.copy(
            apiKey = BuildConfig.SF_FREE_API_KEY,
            endpoint = Prefs.SF_CHAT_COMPLETIONS_ENDPOINT,
            temperature = Prefs.DEFAULT_LLM_TEMPERATURE.toDouble()
        )
    }

    private fun resolvedFromEffective(
        config: Prefs.EffectiveLlmConfig,
        customProviderId: String?,
        capabilityIdentity: String
    ): ResolvedLlmModelConfig = ResolvedLlmModelConfig(
        vendor = config.vendor,
        vendorId = config.vendor.id,
        customProviderId = customProviderId,
        endpoint = config.endpoint,
        apiKey = config.apiKey,
        model = config.model,
        temperature = config.temperature.toDouble(),
        reasoningCharThreshold = config.reasoningCharThreshold,
        useCustomReasoningParams = config.useCustomReasoningParams,
        reasoningParamsOnJson = config.reasoningParamsOnJson,
        reasoningParamsOffJson = config.reasoningParamsOffJson,
        capabilityIdentity = capabilityIdentity
    )

    private fun unavailableReasonFor(vendor: LlmVendor): LlmModelUnavailableReason = when {
        vendor == LlmVendor.CUSTOM -> LlmModelUnavailableReason.PROVIDER_MISSING
        vendor.requiresApiKey -> LlmModelUnavailableReason.MISSING_API_KEY
        else -> LlmModelUnavailableReason.NO_ACTIVE_CONFIG
    }
}
