/** Translation model warmup overlaps recording; no model downloads or placeholder translation. */
package com.brycewg.asrkb.translation

import android.content.Context
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal object TranslationPreload {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    @Synchronized
    fun onRecordingStarted(context: Context, prefs: Prefs) {
        if (prefs.translationVendor != "foxlet" || !prefs.translationEnabled || !prefs.translationPreloadEnabled || !supportsFoxletTranslation()) return
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        val source = prefs.translationSourceLanguage
        val target = prefs.translationTargetLanguage
        job = scope.launch {
            try {
                val route = TranslationModelCatalog(appContext).route(source, target)
                if (route.isNotEmpty()) FoxletTranslationService.preload(appContext, route)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLogManager.logError("asr", "translation_preload_error", data = mapOf("errorType" to e.javaClass.simpleName))
            }
        }
    }
}
