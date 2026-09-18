/**
 * 录音重识别路由：根据当前供应商与模型给出本次请求的文件或流式回放决策。
 *
 * 归属模块：asr
 */
package com.brycewg.asrkb.asr

import android.content.Context
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.DashScopePrefsCompat
import com.brycewg.asrkb.store.Prefs

internal enum class AsrRecordedAudioRouteKind {
    DirectFile,
    MappedFallback,
    ReplayStream,
    Unsupported,
    Unavailable
}

internal data class AsrRequestModelOverride(
    val volcAsrModel: String? = null,
    val dashAsrModel: String? = null
)

internal data class AsrRecordedAudioRouteDecision(
    val kind: AsrRecordedAudioRouteKind,
    val reasonCode: String,
    val canContinue: Boolean,
    val noticeKey: String,
    val vendor: AsrVendor,
    val currentModelKey: String?,
    val fallbackModelKey: String?,
    val currentEngineLabel: String,
    val fallbackEngineLabel: String?,
    val modePreferences: AsrEngineModePreferences,
    val modelOverride: AsrRequestModelOverride,
    val backupPolicy: AsrBackupPolicyDecision
)

internal object AsrRecordedAudioRouteResolver {
    const val REASON_DIRECT_FILE = "direct_file"
    const val REASON_MAPPED_FALLBACK = "mapped_fallback"
    const val REASON_REPLAY_STREAM = "replay_stream"
    const val REASON_UNSUPPORTED_OPENAI_STREAMING = "unsupported_openai_streaming"
    const val REASON_UNSUPPORTED_XASR = "unsupported_xasr"
    const val REASON_UNSUPPORTED_UNKNOWN_MODEL = "unsupported_unknown_model"
    const val REASON_UNSUPPORTED_NO_FILE_FALLBACK = "unsupported_no_file_fallback"
    const val REASON_UNAVAILABLE_CREDENTIALS = "unavailable_credentials"

    fun resolve(
        context: Context,
        prefs: Prefs,
        vendor: AsrVendor = prefs.asrVendor
    ): AsrRecordedAudioRouteDecision {
        val backupPolicy = resolveBackupAsrDecision(
            context = context,
            prefs = prefs,
            primaryVendor = vendor,
            backupVendor = prefs.backupAsrVendor
        )
        val snapshot = prefs.asrEngineModePreferencesSnapshot()
        val decision = when (vendor) {
            AsrVendor.Volc -> resolveVolc(context, prefs, snapshot, backupPolicy)
            AsrVendor.DashScope -> resolveDash(context, prefs, snapshot, backupPolicy)
            AsrVendor.OpenAI -> resolveOpenAi(context, prefs, snapshot, backupPolicy)
            AsrVendor.XAsr -> resolveXAsr(context, snapshot, backupPolicy)
            else -> resolveGenericFile(context, vendor, snapshot, backupPolicy)
        }
        if (decision.canContinue && !isAsrVendorConfigured(context, prefs, vendor)) {
            return decision.copy(
                kind = AsrRecordedAudioRouteKind.Unavailable,
                reasonCode = REASON_UNAVAILABLE_CREDENTIALS,
                canContinue = false,
                noticeKey = NOTICE_UNAVAILABLE_CREDENTIALS
            )
        }
        return decision
    }

    private fun resolveVolc(
        context: Context,
        prefs: Prefs,
        snapshot: AsrEngineModePreferences,
        backupPolicy: AsrBackupPolicyDecision
    ): AsrRecordedAudioRouteDecision {
        val raw = prefs.volcAsrModelStored
        if (raw.isNotEmpty() && VolcAsrModelCatalog.fromId(raw) == null) {
            return unsupported(
                vendor = AsrVendor.Volc,
                reasonCode = REASON_UNSUPPORTED_UNKNOWN_MODEL,
                noticeKey = NOTICE_UNSUPPORTED_UNKNOWN_MODEL,
                currentModelKey = raw,
                currentEngineLabel = raw,
                snapshot = snapshot,
                backupPolicy = backupPolicy
            )
        }
        val current = if (raw.isEmpty()) {
            VolcAsrModelCatalog.fromId(VolcAsrModelCatalog.DEFAULT_ID)
        } else {
            VolcAsrModelCatalog.fromId(raw)
        } ?: return unsupported(
            vendor = AsrVendor.Volc,
            reasonCode = REASON_UNSUPPORTED_UNKNOWN_MODEL,
            noticeKey = NOTICE_UNSUPPORTED_UNKNOWN_MODEL,
            currentModelKey = raw,
            currentEngineLabel = raw,
            snapshot = snapshot,
            backupPolicy = backupPolicy
        )
        val currentLabel = context.getString(current.displayNameRes)
        if (current.protocol == VolcAsrProtocolKind.Stream) {
            val fallback = VolcAsrModelCatalog.fileFallback(current.id)
                ?: return unsupported(
                    vendor = AsrVendor.Volc,
                    reasonCode = REASON_UNSUPPORTED_NO_FILE_FALLBACK,
                    noticeKey = NOTICE_UNSUPPORTED_NO_FILE_FALLBACK,
                    currentModelKey = current.id,
                    currentEngineLabel = currentLabel,
                    snapshot = snapshot,
                    backupPolicy = backupPolicy
                )
            return AsrRecordedAudioRouteDecision(
                kind = AsrRecordedAudioRouteKind.MappedFallback,
                reasonCode = REASON_MAPPED_FALLBACK,
                canContinue = true,
                noticeKey = volcStreamingNoticeKey(current),
                vendor = AsrVendor.Volc,
                currentModelKey = current.id,
                fallbackModelKey = fallback.id,
                currentEngineLabel = currentLabel,
                fallbackEngineLabel = context.getString(fallback.displayNameRes),
                modePreferences = fileModePreferences(snapshot, AsrVendor.Volc, fallback),
                modelOverride = AsrRequestModelOverride(volcAsrModel = fallback.id),
                backupPolicy = backupPolicy
            )
        }
        return AsrRecordedAudioRouteDecision(
            kind = AsrRecordedAudioRouteKind.DirectFile,
            reasonCode = REASON_DIRECT_FILE,
            canContinue = true,
            noticeKey = NOTICE_VOLC_FILE,
            vendor = AsrVendor.Volc,
            currentModelKey = current.id,
            fallbackModelKey = null,
            currentEngineLabel = currentLabel,
            fallbackEngineLabel = currentLabel,
            modePreferences = fileModePreferences(snapshot, AsrVendor.Volc, current),
            modelOverride = AsrRequestModelOverride(volcAsrModel = current.id),
            backupPolicy = backupPolicy
        )
    }

    private fun resolveDash(
        context: Context,
        prefs: Prefs,
        snapshot: AsrEngineModePreferences,
        backupPolicy: AsrBackupPolicyDecision
    ): AsrRecordedAudioRouteDecision {
        val normalized = DashScopePrefsCompat.normalizeDashAsrModel(prefs.dashAsrModelStored)
        val currentLabel = dashModelLabel(context, normalized)
        if (!DashScopePrefsCompat.isKnownAsrModel(normalized)) {
            return unsupported(
                vendor = AsrVendor.DashScope,
                reasonCode = REASON_UNSUPPORTED_UNKNOWN_MODEL,
                noticeKey = NOTICE_UNSUPPORTED_UNKNOWN_MODEL,
                currentModelKey = normalized,
                currentEngineLabel = currentLabel,
                snapshot = snapshot,
                backupPolicy = backupPolicy
            )
        }
        if (DashScopePrefsCompat.isStreamingModel(normalized)) {
            val fallback = DashScopePrefsCompat.fileFallbackModel(normalized)
                ?: return unsupported(
                    vendor = AsrVendor.DashScope,
                    reasonCode = REASON_UNSUPPORTED_NO_FILE_FALLBACK,
                    noticeKey = NOTICE_UNSUPPORTED_NO_FILE_FALLBACK,
                    currentModelKey = normalized,
                    currentEngineLabel = currentLabel,
                    snapshot = snapshot,
                    backupPolicy = backupPolicy
                )
            return AsrRecordedAudioRouteDecision(
                kind = AsrRecordedAudioRouteKind.MappedFallback,
                reasonCode = REASON_MAPPED_FALLBACK,
                canContinue = true,
                noticeKey = dashStreamingNoticeKey(normalized),
                vendor = AsrVendor.DashScope,
                currentModelKey = normalized,
                fallbackModelKey = fallback,
                currentEngineLabel = currentLabel,
                fallbackEngineLabel = dashModelLabel(context, fallback),
                modePreferences = fileModePreferences(snapshot, AsrVendor.DashScope),
                modelOverride = AsrRequestModelOverride(dashAsrModel = fallback),
                backupPolicy = backupPolicy
            )
        }
        return AsrRecordedAudioRouteDecision(
            kind = AsrRecordedAudioRouteKind.DirectFile,
            reasonCode = REASON_DIRECT_FILE,
            canContinue = true,
            noticeKey = NOTICE_DASH_FILE,
            vendor = AsrVendor.DashScope,
            currentModelKey = normalized,
            fallbackModelKey = null,
            currentEngineLabel = currentLabel,
            fallbackEngineLabel = currentLabel,
            modePreferences = fileModePreferences(snapshot, AsrVendor.DashScope),
            modelOverride = AsrRequestModelOverride(dashAsrModel = normalized),
            backupPolicy = backupPolicy
        )
    }

    private fun resolveOpenAi(
        context: Context,
        prefs: Prefs,
        snapshot: AsrEngineModePreferences,
        backupPolicy: AsrBackupPolicyDecision
    ): AsrRecordedAudioRouteDecision {
        val vendorLabel = vendorLabel(context, AsrVendor.OpenAI)
        if (prefs.isOpenAiStreamingEffective()) {
            return unsupported(
                vendor = AsrVendor.OpenAI,
                reasonCode = REASON_UNSUPPORTED_OPENAI_STREAMING,
                noticeKey = NOTICE_UNSUPPORTED_OPENAI_STREAMING,
                currentModelKey = null,
                currentEngineLabel = "$vendorLabel ${context.getString(R.string.label_openai_streaming)}",
                snapshot = snapshot,
                backupPolicy = backupPolicy
            )
        }
        return AsrRecordedAudioRouteDecision(
            kind = AsrRecordedAudioRouteKind.DirectFile,
            reasonCode = REASON_DIRECT_FILE,
            canContinue = true,
            noticeKey = NOTICE_GENERIC_FILE,
            vendor = AsrVendor.OpenAI,
            currentModelKey = null,
            fallbackModelKey = null,
            currentEngineLabel = vendorLabel,
            fallbackEngineLabel = vendorLabel,
            modePreferences = fileModePreferences(snapshot, AsrVendor.OpenAI),
            modelOverride = AsrRequestModelOverride(),
            backupPolicy = backupPolicy
        )
    }

    private fun resolveXAsr(
        context: Context,
        snapshot: AsrEngineModePreferences,
        backupPolicy: AsrBackupPolicyDecision
    ): AsrRecordedAudioRouteDecision {
        val label = context.getString(R.string.vendor_x_asr)
        return AsrRecordedAudioRouteDecision(
            kind = AsrRecordedAudioRouteKind.ReplayStream,
            reasonCode = REASON_REPLAY_STREAM,
            canContinue = true,
            noticeKey = NOTICE_SUPPORTED_XASR_STREAM,
            vendor = AsrVendor.XAsr,
            currentModelKey = null,
            fallbackModelKey = null,
            currentEngineLabel = label,
            fallbackEngineLabel = label,
            // X-ASR 本身始终走 ReplayStream；其余开关关掉，避免 backup 仍按实时流式构造。
            modePreferences = fileModePreferences(snapshot, AsrVendor.XAsr),
            modelOverride = AsrRequestModelOverride(),
            backupPolicy = backupPolicy
        )
    }

    private fun resolveGenericFile(
        context: Context,
        vendor: AsrVendor,
        snapshot: AsrEngineModePreferences,
        backupPolicy: AsrBackupPolicyDecision
    ): AsrRecordedAudioRouteDecision {
        val label = vendorLabel(context, vendor)
        return AsrRecordedAudioRouteDecision(
            kind = AsrRecordedAudioRouteKind.DirectFile,
            reasonCode = REASON_DIRECT_FILE,
            canContinue = true,
            noticeKey = NOTICE_GENERIC_FILE,
            vendor = vendor,
            currentModelKey = null,
            fallbackModelKey = null,
            currentEngineLabel = label,
            fallbackEngineLabel = label,
            modePreferences = fileModePreferences(snapshot, vendor),
            modelOverride = AsrRequestModelOverride(),
            backupPolicy = backupPolicy
        )
    }

    private fun unsupported(
        vendor: AsrVendor,
        reasonCode: String,
        noticeKey: String,
        currentModelKey: String?,
        currentEngineLabel: String,
        snapshot: AsrEngineModePreferences,
        backupPolicy: AsrBackupPolicyDecision
    ): AsrRecordedAudioRouteDecision = AsrRecordedAudioRouteDecision(
        kind = AsrRecordedAudioRouteKind.Unsupported,
        reasonCode = reasonCode,
        canContinue = false,
        noticeKey = noticeKey,
        vendor = vendor,
        currentModelKey = currentModelKey,
        fallbackModelKey = null,
        currentEngineLabel = currentEngineLabel,
        fallbackEngineLabel = null,
        modePreferences = fileModePreferences(snapshot, vendor),
        modelOverride = AsrRequestModelOverride(),
        backupPolicy = backupPolicy
    )

    // 并行/lazy backup 与主引擎共用同一份 preferences，必须关掉所有 vendor 的流式开关。
    private fun fileModePreferences(
        snapshot: AsrEngineModePreferences,
        vendor: AsrVendor,
        volcFileModel: VolcAsrModel? = null
    ): AsrEngineModePreferences = snapshot.copy(
        volcStreamingEnabled = false,
        volcStandardFileEnabled = if (vendor == AsrVendor.Volc) {
            volcFileModel?.protocol == VolcAsrProtocolKind.StandardFile
        } else {
            snapshot.volcStandardFileEnabled
        },
        elevenStreamingEnabled = false,
        openAiStreamingEnabled = false,
        dashScopeStreamingEnabled = false,
        sonioxStreamingEnabled = false,
        senseVoicePseudoStreamEnabled = false,
        fireRedPseudoStreamEnabled = false
    )

    private fun volcStreamingNoticeKey(current: VolcAsrModel): String = if (current.modelV2) NOTICE_VOLC_STREAMING_V2 else NOTICE_VOLC_STREAMING_V1

    private fun dashStreamingNoticeKey(model: String): String = if (model.equals(Prefs.DASH_MODEL_FUN_ASR_REALTIME, ignoreCase = true)) {
        NOTICE_DASH_FUN_REALTIME
    } else {
        NOTICE_DASH_QWEN_REALTIME
    }

    private fun dashModelLabel(context: Context, model: String): String {
        val resId = when {
            model.equals(Prefs.DASH_MODEL_FUN_ASR_FLASH, ignoreCase = true) ->
                R.string.dash_model_fun_flash
            model.equals(Prefs.DASH_MODEL_QWEN_AUDIO_FLASH, ignoreCase = true) ->
                R.string.dash_model_qwen_audio_flash
            model.equals(Prefs.DASH_MODEL_QWEN3_FLASH, ignoreCase = true) ->
                R.string.dash_model_qwen3_flash
            model.equals(Prefs.DASH_MODEL_QWEN35_OMNI_FLASH, ignoreCase = true) ->
                R.string.dash_model_qwen35_omni_flash
            model.equals(Prefs.DASH_MODEL_QWEN38_OMNI_FLASH, ignoreCase = true) ->
                R.string.dash_model_qwen38_omni_flash
            model.equals(Prefs.DASH_MODEL_QWEN35_OMNI_PLUS, ignoreCase = true) ->
                R.string.dash_model_qwen35_omni_plus
            model.equals(Prefs.DASH_MODEL_FUN_ASR_REALTIME, ignoreCase = true) ->
                R.string.dash_model_fun_realtime
            model.equals(Prefs.DASH_MODEL_QWEN_AUDIO_REALTIME, ignoreCase = true) ->
                R.string.dash_model_qwen_audio_realtime
            model.equals(Prefs.DASH_MODEL_QWEN3_REALTIME, ignoreCase = true) ->
                R.string.dash_model_qwen3_realtime
            else -> null
        }
        return resId?.let { context.getString(it) } ?: model
    }

    private fun vendorLabel(context: Context, vendor: AsrVendor): String = context.getString(AsrVendorRegistry.descriptorFor(vendor).displayNameResId)
}

private const val NOTICE_VOLC_STREAMING_V2 = "history_rerecognition_supported_volc_streaming_v2_v1"
private const val NOTICE_VOLC_STREAMING_V1 = "history_rerecognition_supported_volc_streaming_v1_v1"
private const val NOTICE_VOLC_FILE = "history_rerecognition_supported_volc_file_v1"
private const val NOTICE_DASH_FUN_REALTIME = "history_rerecognition_supported_dash_fun_realtime_v1"
private const val NOTICE_DASH_QWEN_REALTIME = "history_rerecognition_supported_dash_qwen_realtime_v1"
private const val NOTICE_DASH_FILE = "history_rerecognition_supported_dash_file_v1"
private const val NOTICE_UNSUPPORTED_OPENAI_STREAMING =
    "history_rerecognition_unsupported_openai_streaming_v1"
private const val NOTICE_SUPPORTED_XASR_STREAM = "history_rerecognition_supported_xasr_stream_v1"
private const val NOTICE_UNSUPPORTED_UNKNOWN_MODEL =
    "history_rerecognition_unsupported_unknown_model_v1"
private const val NOTICE_GENERIC_FILE = "history_rerecognition_supported_generic_file_v1"
private const val NOTICE_UNAVAILABLE_CREDENTIALS =
    "history_rerecognition_unavailable_credentials_v1"
private const val NOTICE_UNSUPPORTED_NO_FILE_FALLBACK =
    "history_rerecognition_unsupported_no_file_fallback_v1"
