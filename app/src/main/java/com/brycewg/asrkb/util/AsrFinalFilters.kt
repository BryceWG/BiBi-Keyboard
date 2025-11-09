package com.brycewg.asrkb.util

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.asr.LlmPostProcessor
import com.brycewg.asrkb.store.Prefs

/**
 * 识别结果后处理的小工具：统一封装常用过滤与可选 AI 后处理。
 *
 * 约定：
 * - 简单路径（applySimple）：仅执行“去除句末标点/emoji”和（Pro）繁体转换。
 * - 完整路径（applyWithAi）：在简单过滤基础上可选执行 LLM 后处理，并在结束后再次修剪句末标点；最后进行繁体转换。
 *
 * 注意：
 * - Pro 繁体转换通过 ProTradFacade 门面；OSS 变体为 no-op。
 * - 调用方可根据业务选择同步（简单）或异步（含 LLM）。
 */
object AsrFinalFilters {
  private const val TAG = "AsrFinalFilters"

  /**
   * 仅执行本地过滤：去除句末标点/emoji + （Pro）繁体转换。
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

    // Pro 门面：正则表达式后处理（若开启）
    out = try { ProRegexFacade.applyIfEnabled(context, out) } catch (t: Throwable) {
      Log.w(TAG, "regex post-processing failed", t)
      out
    }

    out = try { ProTradFacade.maybeToTraditional(context, out) } catch (t: Throwable) {
      Log.w(TAG, "traditional convert failed", t)
      out
    }
    return out
  }

  /** 仅在开启时执行去除句末标点/emoji；否则原样返回。 */
  fun trimIfEnabled(prefs: Prefs, input: String): String {
    return try {
      if (prefs.trimFinalTrailingPunct) TextSanitizer.trimTrailingPunctAndEmoji(input) else input
    } catch (t: Throwable) {
      Log.w(TAG, "trimIfEnabled failed", t)
      input
    }
  }

  /** 仅在 Pro 变体/开关允许时进行繁体转换；OSS 变体为 no-op。 */
  fun toTraditionalIfEnabled(context: Context, input: String): String {
    return try {
      ProTradFacade.maybeToTraditional(context, input)
    } catch (t: Throwable) {
      Log.w(TAG, "toTraditionalIfEnabled failed", t)
      input
    }
  }

  /**
   * 可选 AI 后处理：
   * - 先按需要去除句末标点；
   * - 若开启 LLM 且配置完整，调用 LLM 后处理；
   * - 结束后再次按需要去除句末标点；
   * - 最后执行（Pro）繁体转换。
   * 返回值沿用 LlmPostProcessor 的结果结构，text 字段为最终可提交文本。
   */
  suspend fun applyWithAi(
    context: Context,
    prefs: Prefs,
    input: String,
    postProcessor: LlmPostProcessor = LlmPostProcessor(),
    promptOverride: String? = null,
    forceAi: Boolean = false
  ): LlmPostProcessor.LlmProcessResult {
    // 预修剪
    val base = try {
      if (prefs.trimFinalTrailingPunct) TextSanitizer.trimTrailingPunctAndEmoji(input) else input
    } catch (t: Throwable) {
      Log.w(TAG, "pre-trim failed", t)
      input
    }

    var processed = base
    var ok = true
    var http: Int? = null
    var err: String? = null

    if ((forceAi || prefs.postProcessEnabled) && prefs.hasLlmKeys()) {
      try {
        val res = postProcessor.processWithStatus(base, prefs, promptOverride)
        ok = res.ok
        processed = res.text
        http = res.httpCode
        err = res.errorMessage
      } catch (t: Throwable) {
        Log.e(TAG, "LLM post-processing threw", t)
        ok = false
        processed = base
        err = t.message
      }
    }

    // 后修剪
    processed = try {
      if (prefs.trimFinalTrailingPunct) TextSanitizer.trimTrailingPunctAndEmoji(processed) else processed
    } catch (t: Throwable) {
      Log.w(TAG, "post-trim failed", t)
      processed
    }

    // 正则表达式后处理（Pro 门面；OSS 为 no-op）
    processed = try { ProRegexFacade.applyIfEnabled(context, processed) } catch (t: Throwable) {
      Log.w(TAG, "regex post-processing failed", t)
      processed
    }

    // 繁体转换（Pro/OSS 门面）
    processed = try {
      ProTradFacade.maybeToTraditional(context, processed)
    } catch (t: Throwable) {
      Log.w(TAG, "traditional convert failed", t)
      processed
    }

    return LlmPostProcessor.LlmProcessResult(ok = ok, text = processed, errorMessage = err, httpCode = http)
  }

  // 正则后处理逻辑已移至 ProRegexFacade（pro/oss 双实现），避免在 main 泄露 Pro 逻辑。
}
