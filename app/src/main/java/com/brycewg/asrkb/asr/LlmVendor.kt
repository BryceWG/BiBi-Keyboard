package com.brycewg.asrkb.asr

import com.brycewg.asrkb.R

/**
 * Reasoning mode control methods for different LLM vendors.
 */
enum class ReasoningMode {
    /** No reasoning control support */
    NONE,

    /** Control via model selection (DeepSeek, Moonshot) */
    MODEL_SELECTION,

    /** SiliconFlow: enable_thinking parameter */
    ENABLE_THINKING,

    /** Gemini/Groq/Cerebras/OhMyGPT: reasoning_effort parameter */
    REASONING_EFFORT,

    /** Volcengine/Zhipu: thinking.type parameter */
    THINKING_TYPE
}

/**
 * LLM (Large Language Model) vendor enumeration for AI post-processing.
 * Defines built-in providers with their endpoints, models, and configuration URLs.
 */
enum class LlmVendor(
    val id: String,
    val displayNameResId: Int,
    val endpoint: String,
    val defaultModel: String,
    val models: List<String>,
    val registerUrl: String,
    val guideUrl: String,
    /** Minimum temperature value supported by this vendor */
    val temperatureMin: Float = 0f,
    /** Maximum temperature value supported by this vendor */
    val temperatureMax: Float = 2f,
    /** How this vendor controls reasoning/thinking mode */
    val reasoningMode: ReasoningMode = ReasoningMode.NONE,
    /** Models that support reasoning control (empty = all models) */
    val reasoningModels: Set<String> = emptySet()
) {
    /** SiliconFlow - supports free tier and paid API */
    SF_FREE(
        id = "sf_free",
        displayNameResId = R.string.llm_vendor_sf_free,
        endpoint = "https://api.siliconflow.cn/v1",
        defaultModel = "Qwen/Qwen3-8B",
        models = listOf(
            "Qwen/Qwen3-8B",
            "Qwen/Qwen3-14B",
            "Qwen/Qwen3-32B",
            "Qwen/Qwen3-30B-A3B-Instruct-2507",
            "Qwen/Qwen3-30B-A3B-Thinking-2507",
            "Qwen/Qwen3-235B-A22B-Instruct-2507",
            "Qwen/Qwen3-235B-A22B-Thinking-2507",
            "Qwen/Qwen3-Next-80B-A3B-Instruct",
            "Qwen/Qwen3-Next-80B-A3B-Thinking",
            "deepseek-ai/DeepSeek-V3.1-Terminus",
            "deepseek-ai/DeepSeek-V3.2",
            "zai-org/GLM-4.6"
        ),
        registerUrl = "https://cloud.siliconflow.cn/i/g8thUcWa",
        guideUrl = "https://docs.siliconflow.cn/cn/api-reference/chat-completions/chat-completions",
        temperatureMin = 0f,
        temperatureMax = 2f,
        reasoningMode = ReasoningMode.ENABLE_THINKING,
        reasoningModels = setOf(
            "Qwen/Qwen3-8B",
            "Qwen/Qwen3-14B",
            "Qwen/Qwen3-32B",
            "Qwen/Qwen3-30B-A3B-Instruct-2507",
            "Qwen/Qwen3-30B-A3B-Thinking-2507",
            "Qwen/Qwen3-235B-A22B-Instruct-2507",
            "Qwen/Qwen3-235B-A22B-Thinking-2507",
            "deepseek-ai/DeepSeek-V3.1-Terminus",
            "deepseek-ai/DeepSeek-V3.2",
            "zai-org/GLM-4.6"
        )
    ),

    /** OpenAI - GPT models */
    OPENAI(
        id = "openai",
        displayNameResId = R.string.llm_vendor_openai,
        endpoint = "https://api.openai.com/v1",
        defaultModel = "gpt-4o-mini",
        models = listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "gpt-4.1"),
        registerUrl = "https://platform.openai.com/signup",
        guideUrl = "https://platform.openai.com/docs/quickstart",
        temperatureMin = 0f,
        temperatureMax = 2f,
        reasoningMode = ReasoningMode.NONE
    ),

    /** Google Gemini */
    GEMINI(
        id = "gemini",
        displayNameResId = R.string.llm_vendor_gemini,
        endpoint = "https://generativelanguage.googleapis.com/v1beta/openai",
        defaultModel = "gemini-2.0-flash",
        models = listOf(
            "gemini-2.0-flash",
            "gemini-2.5-flash-lite",
            "gemini-2.5-flash",
            "gemini-2.5-pro",
            "gemini-3-flash-preview",
            "gemini-3-pro-preview"
        ),
        registerUrl = "https://aistudio.google.com/apikey",
        guideUrl = "https://ai.google.dev/gemini-api/docs/openai?hl=zh-cn",
        temperatureMin = 0f,
        temperatureMax = 2f,
        reasoningMode = ReasoningMode.REASONING_EFFORT,
        reasoningModels = setOf(
            "gemini-2.5-flash-lite",
            "gemini-2.5-flash",
            "gemini-2.5-pro",
            "gemini-3-flash-preview",
            "gemini-3-pro-preview"
        )
    ),

    /** DeepSeek - V3.2 models */
    DEEPSEEK(
        id = "deepseek",
        displayNameResId = R.string.llm_vendor_deepseek,
        endpoint = "https://api.deepseek.com/v1",
        defaultModel = "deepseek-chat",
        models = listOf("deepseek-chat", "deepseek-reasoner"),
        registerUrl = "https://platform.deepseek.com/",
        guideUrl = "https://api-docs.deepseek.com/",
        temperatureMin = 0f,
        temperatureMax = 2f,
        reasoningMode = ReasoningMode.MODEL_SELECTION, // chat=non-thinking, reasoner=thinking
        reasoningModels = setOf("deepseek-reasoner")
    ),

    /** Moonshot (Kimi) */
    MOONSHOT(
        id = "moonshot",
        displayNameResId = R.string.llm_vendor_moonshot,
        endpoint = "https://api.moonshot.cn/v1",
        defaultModel = "kimi-k2-0905-preview",
        models = listOf(
            "kimi-k2-0905-preview",
            "kimi-k2-thinking"
        ),
        registerUrl = "https://platform.moonshot.cn/console/api-keys",
        guideUrl = "https://platform.moonshot.cn/docs/api/chat",
        temperatureMin = 0f,
        temperatureMax = 1f,
        reasoningMode = ReasoningMode.MODEL_SELECTION,
        reasoningModels = setOf("kimi-k2-thinking")
    ),

    /** Zhipu GLM */
    ZHIPU(
        id = "zhipu",
        displayNameResId = R.string.llm_vendor_zhipu,
        endpoint = "https://open.bigmodel.cn/api/paas/v4",
        defaultModel = "glm-4.6",
        models = listOf(
            "glm-4.7",
            "glm-4.6",
            "glm-4.5",
            "glm-4.5-air",
            "glm-4.5-flash",
            "glm-4-plus",
            "glm-4-flashx"
        ),
        registerUrl = "https://bigmodel.cn/usercenter/proj-mgmt/apikeys",
        guideUrl = "https://docs.bigmodel.cn/api-reference",
        temperatureMin = 0f,
        temperatureMax = 1f,
        reasoningMode = ReasoningMode.THINKING_TYPE,
        reasoningModels = setOf("glm-4.7", "glm-4.6", "glm-4.5", "glm-4.5-air", "glm-4.5-flash")
    ),

    /** Volcengine (火山引擎) */
    VOLCENGINE(
        id = "volcengine",
        displayNameResId = R.string.llm_vendor_volcengine,
        endpoint = "https://ark.cn-beijing.volces.com/api/v3",
        defaultModel = "doubao-seed-1-6-flash-250828",
        models = listOf(
            "doubao-seed-1-6-lite-251015",
            "doubao-seed-1-6-251015",
            "doubao-seed-1-6-flash-250828",
            "deepseek-v3-1-terminus",
            "deepseek-v3-2-251201"
        ),
        registerUrl = "https://console.volcengine.com/ark",
        guideUrl = "https://www.volcengine.com/docs/82379/1399328",
        temperatureMin = 0f,
        temperatureMax = 1f,
        reasoningMode = ReasoningMode.THINKING_TYPE,
        reasoningModels = setOf(
            "doubao-seed-1-6-lite-251015",
            "doubao-seed-1-6-251015",
            "doubao-seed-1-6-flash-250828",
            "deepseek-v3-1-terminus",
            "deepseek-v3-2-251201"
        )
    ),

    /** Groq - fast inference */
    GROQ(
        id = "groq",
        displayNameResId = R.string.llm_vendor_groq,
        endpoint = "https://api.groq.com/openai/v1",
        defaultModel = "llama-3.3-70b-versatile",
        models = listOf(
            "moonshotai/kimi-k2-instruct-0905",
            "qwen/qwen3-32b",
            "openai/gpt-oss-120b",
            "openai/gpt-oss-20b",
            "llama-3.3-70b-versatile",
            "meta-llama/llama-4-maverick-17b-128e-instruct"
        ),
        registerUrl = "https://console.groq.com/keys",
        guideUrl = "https://console.groq.com/docs/api-reference#chat-create",
        temperatureMin = 0f,
        temperatureMax = 2f,
        reasoningMode = ReasoningMode.REASONING_EFFORT,
        reasoningModels = setOf(
            "qwen/qwen3-32b",
            "openai/gpt-oss-120b",
            "openai/gpt-oss-20b"
        )
    ),

    /** Cerebras - fast inference */
    CEREBRAS(
        id = "cerebras",
        displayNameResId = R.string.llm_vendor_cerebras,
        endpoint = "https://api.cerebras.ai/v1",
        defaultModel = "llama-3.3-70b",
        models = listOf(
            "llama3.1-8b",
            "llama-3.3-70b",
            "qwen-3-32b",
            "qwen-3-235b-a22b-instruct-2507",
            "gpt-oss-120b",
            "zai-glm-4.6"
        ),
        registerUrl = "https://cloud.cerebras.ai/platform",
        guideUrl = "https://inference-docs.cerebras.ai/api-reference/chat-completions",
        temperatureMin = 0f,
        temperatureMax = 1.5f,
        reasoningMode = ReasoningMode.REASONING_EFFORT,
        reasoningModels = setOf("gpt-oss-120b")
    ),

    /** OhMyGPT - multi-provider relay */
    OHMYGPT(
        id = "ohmygpt",
        displayNameResId = R.string.llm_vendor_ohmygpt,
        endpoint = "https://cn2us02.opapi.win/v1",
        defaultModel = "gpt-4o-mini",
        models = listOf(
            "gpt-4o-mini",
            "gpt-4o",
            "gpt-4.1-mini",
            "gpt-4.1-nano",
            "gpt-4.1",
            "gpt-5-mini",
            "gpt-5-nano",
            "gemini-2.0-flash",
            "gemini-2.5-flash-lite",
            "gemini-2.5-flash",
            "claude-haiku-4-5",
            "claude-sonnet-4-5"
        ),
        registerUrl = "https://x.dogenet.win/i/CXuHm49s",
        guideUrl = "https://docs.ohmygpt.com/zh",
        temperatureMin = 0f,
        temperatureMax = 2f,
        reasoningMode = ReasoningMode.REASONING_EFFORT,
        reasoningModels = setOf(
            "gemini-2.5-flash-lite",
            "gemini-2.5-flash",
            "claude-haiku-4-5",
            "claude-sonnet-4-5",
            "gpt-5-mini",
            "gpt-5-nano"
        )
    ),

    /** Fireworks AI - fast inference with multiple models */
    FIREWORKS(
        id = "fireworks",
        displayNameResId = R.string.llm_vendor_fireworks,
        endpoint = "https://api.fireworks.ai/inference/v1",
        defaultModel = "accounts/fireworks/models/deepseek-v3p2",
        models = listOf(
            // DeepSeek models
            "accounts/fireworks/models/deepseek-v3p2",
            "accounts/fireworks/models/deepseek-v3p1-terminus",
            // Kimi models
            "accounts/fireworks/models/kimi-k2-instruct-0905",
            "accounts/fireworks/models/kimi-k2-thinking",
            // GPT-OSS models (Harmony)
            "accounts/fireworks/models/gpt-oss-120b",
            "accounts/fireworks/models/gpt-oss-20b",
            // GLM models
            "accounts/fireworks/models/glm-4p6",
            "accounts/fireworks/models/glm-4p7"
        ),
        registerUrl = "https://fireworks.ai/login",
        guideUrl = "https://docs.fireworks.ai/",
        temperatureMin = 0f,
        temperatureMax = 2f,
        reasoningMode = ReasoningMode.REASONING_EFFORT,
        reasoningModels = setOf(
            // DeepSeek V3.1/V3.2: binary on/off, default off
            "accounts/fireworks/models/deepseek-v3p1-terminus",
            "accounts/fireworks/models/deepseek-v3p2",
            // GLM 4.5/4.6: binary on/off, default on
            "accounts/fireworks/models/glm-4p6",
            "accounts/fireworks/models/glm-4p7",
            // GPT-OSS: only low/medium/high, no 'none' support
            "accounts/fireworks/models/gpt-oss-120b",
            "accounts/fireworks/models/gpt-oss-20b"
        )
    ),

    /** Alibaba DashScope - 阿里云百炼 */
    DASHSCOPE(
        id = "dashscope",
        displayNameResId = R.string.llm_vendor_dashscope,
        endpoint = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        defaultModel = "qwen3.5-plus",
        models = listOf(
            "qwen3.5-plus",
            "qwen3.5-397b-a17b",
            "qwen3-max"
        ),
        registerUrl = "https://dashscope.aliyun.com/",
        guideUrl = "https://help.aliyun.com/zh/dashscope/",
        temperatureMin = 0f,
        temperatureMax = 2f,
        reasoningMode = ReasoningMode.ENABLE_THINKING,
        reasoningModels = setOf(
            "qwen3.5-plus",
            "qwen3.5-397b-a17b",
            "qwen3-max"
        )
    ),

    /** TypeSafe Jev decision model; available only to automatic prompt selection. */
    TYPESAFE(
        id = "typesafe",
        displayNameResId = R.string.llm_vendor_typesafe,
        endpoint = "",
        defaultModel = "jev-latest",
        models = listOf("jev-latest"),
        registerUrl = "https://typesafe.ai/",
        guideUrl = "https://typesafe.ai/",
        temperatureMin = 0f,
        temperatureMax = 0f
    ),

    /** Custom - user-defined OpenAI-compatible API */
    CUSTOM(
        id = "custom",
        displayNameResId = R.string.llm_vendor_custom,
        endpoint = "",
        defaultModel = "",
        models = emptyList(),
        registerUrl = "",
        guideUrl = ""
    );

    /** Whether this vendor requires an API key */
    val requiresApiKey: Boolean
        get() = this != SF_FREE

    /** Whether this vendor uses built-in endpoint (not user-configurable) */
    val hasBuiltinEndpoint: Boolean
        get() = this != CUSTOM && endpoint.isNotBlank()

    val classifierOnly: Boolean
        get() = this == TYPESAFE

    /** Check if the current model supports reasoning control */
    fun supportsReasoningControl(model: String): Boolean = when (reasoningMode) {
        ReasoningMode.NONE -> false
        ReasoningMode.MODEL_SELECTION -> false // Controlled via model selection, no switch needed
        else -> reasoningModels.isEmpty() || reasoningModels.contains(model)
    }

    companion object {
        /** Get vendor by ID, defaulting to SF_FREE if not found */
        fun fromId(id: String?): LlmVendor = when (id?.lowercase()) {
            OPENAI.id -> OPENAI
            GEMINI.id -> GEMINI
            DEEPSEEK.id -> DEEPSEEK
            MOONSHOT.id -> MOONSHOT
            ZHIPU.id -> ZHIPU
            VOLCENGINE.id -> VOLCENGINE
            GROQ.id -> GROQ
            CEREBRAS.id -> CEREBRAS
            OHMYGPT.id -> OHMYGPT
            FIREWORKS.id -> FIREWORKS
            DASHSCOPE.id -> DASHSCOPE
            TYPESAFE.id -> TYPESAFE
            CUSTOM.id -> CUSTOM
            else -> SF_FREE
        }

        /**
         * Get all vendors for UI selection.
         * Ordered by: Free tier -> Domestic (China) -> International -> Custom
         */
        fun allVendors(): List<LlmVendor> = listOf(
            SF_FREE, // 1. Free service
            DEEPSEEK, // 2. Domestic - popular
            ZHIPU, // 3. Domestic
            MOONSHOT, // 4. Domestic
            VOLCENGINE, // 5. Domestic
            DASHSCOPE, // 6. Domestic - Alibaba
            OPENAI, // 7. International
            GEMINI, // 8. International
            GROQ, // 9. International - free tier
            CEREBRAS, // 10. International - free tier
            FIREWORKS, // 11. International - fast inference
            OHMYGPT, // 12. Relay platform
            TYPESAFE, // 13. Decision-only classifier
            CUSTOM // 13. Custom
        )

        /** Get built-in vendors (excluding custom) */
        fun builtinVendors(): List<LlmVendor> = allVendors().filter { it != CUSTOM }
    }
}
