package com.brycewg.asrkb.util

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.asr.LlmPostProcessor
import com.brycewg.asrkb.store.Prefs

/**
 * 识别结果末处理：统一封装去尾处理与可选 AI 后处理
 */
object AsrFinalFilters {
  private const val TAG = "AsrFinalFilters"

  /**
   * 执行基础过滤：去除句末标点/emoji，并处理预置替换
   */
  fun applySimple(context: Context, prefs: Prefs, input: String): String {
    var out = input
    try {
      if (prefs.trimFinalTrailingPunct) {
        out = TextSanitizer.trimTrailingPunctAndEmoji(out)
      }
    } catch (t: Throwable) {
      Log.w(TAG, "trimTrailingPunct failed", t)
    }

    // 预置替换（最高优先级，直接返回替换文案）
    return try {
      val rep = prefs.findSpeechPresetReplacement(out)
      if (!rep.isNullOrEmpty()) rep else out
    } catch (t: Throwable) {
      Log.w(TAG, "speech preset replacement failed", t)
      out
    }
  }

  /**
   * 可选 AI 后处理：
   * - 先按需要去除句末标点；
   * - 若开启 LLM 且配置完整，调用 LLM 后处理；
   * - 结束后再次按需要去除句末标点
   * 返回值沿用 LlmPostProcessor 的结果结构，text 字段为最终可提交文本。
   */
  suspend fun applyWithAi(
    context: Context,
    prefs: Prefs,
    input: String,
    postProcessor: LlmPostProcessor = LlmPostProcessor(),
    promptOverride: String? = null,
    forceAi: Boolean = false,
    onStreamingUpdate: ((String) -> Unit)? = null
  ): LlmPostProcessor.LlmProcessResult {
    // 预修剪
    val base = try {
      if (prefs.trimFinalTrailingPunct) TextSanitizer.trimTrailingPunctAndEmoji(input) else input
    } catch (t: Throwable) {
      Log.w(TAG, "pre-trim failed", t)
      input
    }

    // 语音预设替换：若命中则跳过 LLM 与全部其他处理（含正则/繁体），直接返回
    try {
      val rep = prefs.findSpeechPresetReplacement(base)
      if (!rep.isNullOrEmpty()) {
        return LlmPostProcessor.LlmProcessResult(
          ok = true,
          text = rep,
          errorMessage = null,
          httpCode = null,
          usedAi = false
        )
      }
    } catch (t: Throwable) {
      Log.w(TAG, "speech preset replacement failed (ai branch)", t)
    }

    var processed = base
    var ok = true
    var http: Int? = null
    var err: String? = null
    var aiAttempted = false

    // 少于阈值时自动跳过 AI 后处理（forceAi 时不跳过）
    val skipForShort = try {
      if (forceAi || prefs.postprocSkipUnderChars <= 0) {
        false
      } else {
        TextSanitizer.countEffectiveChars(base) < prefs.postprocSkipUnderChars
      }
    } catch (t: Throwable) {
      Log.w(TAG, "skip threshold calculation failed", t)
      false
    }

    if (!skipForShort && (forceAi || prefs.postProcessEnabled) && prefs.hasLlmKeys()) {
      try {
        val res = postProcessor.processWithStatus(
          base,
          prefs,
          promptOverride,
          onStreamingUpdate = onStreamingUpdate
        )
        ok = res.ok
        processed = res.text
        http = res.httpCode
        err = res.errorMessage
        aiAttempted = true
      } catch (t: Throwable) {
        Log.e(TAG, "LLM post-processing threw", t)
        ok = false
        processed = base
        err = t.message
        aiAttempted = true
      }
    }

    // 后修剪
    processed = try {
      if (prefs.trimFinalTrailingPunct) TextSanitizer.trimTrailingPunctAndEmoji(processed) else processed
    } catch (t: Throwable) {
      Log.w(TAG, "post-trim failed", t)
      processed
    }

    val usedAi = aiAttempted && ok
    return LlmPostProcessor.LlmProcessResult(
      ok = ok,
      text = processed,
      errorMessage = err,
      httpCode = http,
      usedAi = usedAi
    )
  }
}
