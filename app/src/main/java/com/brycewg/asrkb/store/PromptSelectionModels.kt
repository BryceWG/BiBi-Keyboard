package com.brycewg.asrkb.store

import com.brycewg.asrkb.asr.LlmVendor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val PROMPT_SELECTION_SKIP_POLISH_ID = "candidate.skip_polish"

const val JEV_MODEL_ID = "jev-latest"

/** OpenRouter 渠道的默认分类模型 ID。 */
const val JEV_OPENROUTER_MODEL_ID = "~typesafe/jev-latest"

/** Cloudflare Workers AI 渠道的默认分类模型 ID。 */
const val JEV_CLOUDFLARE_MODEL_ID = "typesafe/jev"

enum class JevClassifierProvider(val id: String) {
    TYPESAFE("typesafe"),
    OPENROUTER("openrouter"),
    CLOUDFLARE("cloudflare"),
    CUSTOM("custom");

    companion object {
        fun fromId(id: String): JevClassifierProvider? = entries.firstOrNull { it.id == id }
    }

    fun displayName(): String = when (this) {
        TYPESAFE -> "TypeSafe"
        OPENROUTER -> "OpenRouter"
        CLOUDFLARE -> "Cloudflare"
        CUSTOM -> "Custom (TypeSafe-compatible)"
    }

    /** 各渠道的默认分类模型；CUSTOM 渠道由用户自行填写。 */
    fun defaultModelId(): String = when (this) {
        TYPESAFE -> JEV_MODEL_ID
        OPENROUTER -> JEV_OPENROUTER_MODEL_ID
        CLOUDFLARE -> JEV_CLOUDFLARE_MODEL_ID
        CUSTOM -> ""
    }
}

/** 当前渠道生效的分类模型 ID：未自定义时回退渠道默认值。 */
fun jevModelId(prefs: Prefs, provider: JevClassifierProvider): String = when (provider) {
    JevClassifierProvider.TYPESAFE ->
        prefs.getLlmVendorModel(LlmVendor.TYPESAFE).trim().ifBlank { JEV_MODEL_ID }

    JevClassifierProvider.OPENROUTER ->
        prefs.jevOpenRouterModel.trim().ifBlank { JEV_OPENROUTER_MODEL_ID }

    JevClassifierProvider.CLOUDFLARE ->
        prefs.jevCloudflareModel.trim().ifBlank { JEV_CLOUDFLARE_MODEL_ID }

    JevClassifierProvider.CUSTOM -> prefs.jevCustomModel.trim()
}

/** 写入渠道分类模型；CUSTOM 渠道复用自定义 endpoint 的模型字段。 */
fun setJevModelId(prefs: Prefs, provider: JevClassifierProvider, model: String) {
    val value = model.trim()
    when (provider) {
        JevClassifierProvider.TYPESAFE -> prefs.setLlmVendorModel(LlmVendor.TYPESAFE, value)
        JevClassifierProvider.OPENROUTER -> prefs.jevOpenRouterModel = value
        JevClassifierProvider.CLOUDFLARE -> prefs.jevCloudflareModel = value
        JevClassifierProvider.CUSTOM -> prefs.jevCustomModel = value
    }
}

fun isPromptSelectionSkipPolishId(id: String): Boolean = id == PROMPT_SELECTION_SKIP_POLISH_ID

/** A selectable prompt preset or the special action that keeps the transcript unchanged. */
sealed interface PromptSelectionCandidate {
    val id: String
    val skill: String
    val displayTitle: String
    val skipsPolish: Boolean

    data class Preset(val preset: PromptPreset) : PromptSelectionCandidate {
        override val id: String get() = preset.id
        override val skill: String get() = preset.skill
        override val displayTitle: String get() = preset.title
        override val skipsPolish: Boolean get() = false
        val contentForPolish: String get() = preset.content
    }

    data class SkipPolish(
        override val skill: String,
        override val displayTitle: String
    ) : PromptSelectionCandidate {
        override val id: String get() = PROMPT_SELECTION_SKIP_POLISH_ID
        override val skipsPolish: Boolean get() = true
    }
}

/** Persisted identity of the model used by an LLM feature. Provider settings stay live. */
@Serializable
sealed interface LlmFeatureModelRef {
    @Serializable
    @SerialName("follow_default")
    data object FollowDefault : LlmFeatureModelRef

    @Serializable
    @SerialName("builtin")
    data class Builtin(val vendorId: String, val model: String) : LlmFeatureModelRef

    @Serializable
    @SerialName("custom")
    data class Custom(val providerId: String, val model: String) : LlmFeatureModelRef
}

@Serializable
enum class PromptSelectionFailReason(val code: String) {
    INVALID_CONFIG("invalid_config"),
    MODEL_UNAVAILABLE("model_unavailable"),
    TIMEOUT("timeout"),
    REQUEST_FAILED("request_failed"),
    INVALID_OUTPUT("invalid_output"),
    CANCELLED("cancelled");

    companion object {
        fun fromCode(code: String?): PromptSelectionFailReason? = entries.firstOrNull { it.code == code }
    }
}

/** Snapshot of one selection attempt, persisted with history records. */
@Serializable
data class PromptSelectionStatus(
    @SerialName("ok") val ok: Boolean,
    @SerialName("requestSent") val requestSent: Boolean = false,
    @SerialName("failReason") val failReason: String? = null,
    @SerialName("usedPresetId") val usedPresetId: String? = null,
    @SerialName("usedPresetTitle") val usedPresetTitle: String? = null,
    @SerialName("skippedPolish") val skippedPolish: Boolean = false,
    @SerialName("vendorId") val vendorId: String? = null,
    @SerialName("customProviderId") val customProviderId: String? = null,
    @SerialName("model") val model: String? = null,
    @SerialName("elapsedMs") val elapsedMs: Long = 0
) {
    val failReasonEnum: PromptSelectionFailReason?
        get() = PromptSelectionFailReason.fromCode(failReason)

    companion object {
        fun success(
            presetId: String,
            presetTitle: String,
            vendorId: String?,
            customProviderId: String?,
            model: String?,
            elapsedMs: Long
        ) = PromptSelectionStatus(
            ok = true,
            requestSent = true,
            usedPresetId = presetId,
            usedPresetTitle = presetTitle,
            vendorId = vendorId,
            customProviderId = customProviderId,
            model = model,
            elapsedMs = elapsedMs.coerceAtLeast(0L)
        )

        fun failure(
            reason: PromptSelectionFailReason,
            requestSent: Boolean,
            vendorId: String? = null,
            customProviderId: String? = null,
            model: String? = null,
            elapsedMs: Long = 0L
        ) = PromptSelectionStatus(
            ok = false,
            requestSent = requestSent,
            failReason = reason.code,
            vendorId = vendorId,
            customProviderId = customProviderId,
            model = model,
            elapsedMs = elapsedMs.coerceAtLeast(0L)
        )

        fun skippedPolishSuccess(
            vendorId: String? = null,
            customProviderId: String? = null,
            model: String? = null,
            elapsedMs: Long = 0L
        ) = PromptSelectionStatus(
            ok = true,
            requestSent = true,
            usedPresetId = PROMPT_SELECTION_SKIP_POLISH_ID,
            skippedPolish = true,
            vendorId = vendorId,
            customProviderId = customProviderId,
            model = model,
            elapsedMs = elapsedMs.coerceAtLeast(0L)
        )
    }
}
