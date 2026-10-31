/** Translation module: shared final-text stage for keyboard, floating and external speech. */
package com.brycewg.asrkb.translation

import android.content.Context
import android.os.SystemClock
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal object TranslationPipeline {
    private val requests = AtomicLong()

    suspend fun translate(
        context: Context,
        prefs: Prefs,
        text: String,
        timing: com.brycewg.asrkb.store.AsrHistoryTimingRecorder? = null,
        onTranslated: (com.brycewg.asrkb.store.TranslationRecord) -> Unit = {}
    ): String {
        if (!prefs.translationEnabled || text.isBlank()) return text
        val id = requests.incrementAndGet()
        val started = SystemClock.elapsedRealtime()
        val data = mapOf(
            "request" to id,
            "vendor" to prefs.translationVendor,
            "source" to prefs.translationSourceLanguage,
            "target" to prefs.translationTargetLanguage
        )
        DebugLogManager.logBase("asr", "translation_start", data)
        timing?.end(com.brycewg.asrkb.store.AsrHistoryTimingStage.POSTPROCESS)
        timing?.end(com.brycewg.asrkb.store.AsrHistoryTimingStage.TEXT_DELIVERY)
        timing?.begin(com.brycewg.asrkb.store.AsrHistoryTimingStage.TRANSLATION)
        try {
            check(supportsFoxletTranslation()) { context.getString(R.string.translation_unsupported_device) }
            check(prefs.translationVendor == "foxlet")
            val output = withContext(Dispatchers.Default) {
                val route = TranslationModelCatalog(context).route(
                    prefs.translationSourceLanguage,
                    prefs.translationTargetLanguage
                )
                check(route.isNotEmpty()) { context.getString(R.string.translation_choose_languages) }
                FoxletTranslationService.translate(context, text, route)
            }
            currentCoroutineContext().ensureActive()
            check(output.isNotBlank()) { context.getString(R.string.translation_empty_result) }
            DebugLogManager.logBase(
                "asr",
                "translation_final",
                data + mapOf("elapsedMs" to SystemClock.elapsedRealtime() - started)
            )
            onTranslated(
                com.brycewg.asrkb.store.TranslationRecord(
                    text,
                    prefs.translationSourceLanguage,
                    prefs.translationTargetLanguage,
                    prefs.translationVendor,
                    SystemClock.elapsedRealtime() - started
                )
            )
            return if (com.brycewg.asrkb.util.AsrFinalFilters.shouldTrimTrailingPunctAndEmoji(prefs, output)) {
                com.brycewg.asrkb.util.TextSanitizer.trimTrailingPunctAndEmoji(output)
            } else {
                output
            }
        } catch (e: CancellationException) {
            DebugLogManager.logBase("asr", "translation_cancel", data)
            throw e
        } catch (e: Exception) {
            DebugLogManager.logError("asr", "translation_error", data = data + mapOf("errorType" to e.javaClass.simpleName))
            throw TranslationFailure(context.getString(R.string.translation_failed), e)
        } finally {
            timing?.end(com.brycewg.asrkb.store.AsrHistoryTimingStage.TRANSLATION)
            timing?.begin(com.brycewg.asrkb.store.AsrHistoryTimingStage.POSTPROCESS)
        }
    }
}

internal class TranslationFailure(message: String, cause: Throwable) : Exception(message, cause)
