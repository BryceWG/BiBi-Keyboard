/** Translation module: process-wide offline client, model installation and serialized inference. */
package com.brycewg.asrkb.translation

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager
import io.github.yinvoker.foxlet.DownloadProgress
import io.github.yinvoker.foxlet.Foxlet
import io.github.yinvoker.foxlet.LanguagePair
import io.github.yinvoker.foxlet.ModelRetention
import io.github.yinvoker.foxlet.PreparePolicy
import io.github.yinvoker.foxlet.Threading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@RequiresApi(28)
internal object FoxletTranslationService {
    private val clientLock = Mutex()
    private val inferenceLock = Mutex()
    private var client: Foxlet? = null
    private val residencyScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var unloadJob: Job? = null

    private suspend fun client(context: Context): Foxlet = clientLock.withLock {
        client ?: Foxlet.create(context.applicationContext) {
            translation {
                threading = Threading.Fixed(1)
                // The application controls residency so settings changes do not recreate the client.
                retention = ModelRetention.UntilShutdown
            }
        }.also { client = it }
    }

    suspend fun installed(context: Context): Set<String> = client(context).models.listInstalled().map {
        "${it.pair.source}-${it.pair.target}"
    }.toSet()

    data class InstalledPack(val source: String, val target: String, val version: String, val bytes: Long) {
        val id: String get() = "$source-$target"
    }

    suspend fun installedPacks(context: Context): List<InstalledPack> = client(context).models.listInstalled().map {
        InstalledPack(it.pair.source, it.pair.target, it.version, it.sizeBytes)
    }

    suspend fun download(context: Context, model: TranslationModelCatalog.Model, progress: (DownloadProgress) -> Unit) {
        val sdk = client(context)
        val descriptor = requireNotNull(sdk.models.findBundled(LanguagePair(model.source, model.target)))
        sdk.models.download(descriptor, onProgress = progress)
    }

    suspend fun delete(context: Context, route: List<TranslationModelCatalog.Model>) = inferenceLock.withLock {
        unloadJob?.cancel()
        val sdk = client(context)
        check(sdk.translator.unloadModels().allReleased)
        route.forEach { model ->
            val report = sdk.models.deleteAll(LanguagePair(model.source, model.target))
            report.results.forEach { result -> result.failure?.let { throw it } }
        }
    }

    suspend fun translate(context: Context, text: String, route: List<TranslationModelCatalog.Model>): String = inferenceLock.withLock {
        unloadJob?.cancel()
        val sdk = client(context)
        try {
            val models = route.map {
                sdk.models.prepare(LanguagePair(it.source, it.target), PreparePolicy.LocalOnly)
            }
            if (models.size == 1) {
                sdk.translator.translate(text, models.single())
            } else {
                sdk.translator.translatePivot(text, models[0], models[1])
            }
        } finally {
            withContext(NonCancellable) { scheduleUnload(context, afterPreload = false) }
        }
    }

    suspend fun preload(context: Context, route: List<TranslationModelCatalog.Model>) = inferenceLock.withLock {
        val sdk = client(context)
        val installed = route.map { sdk.models.findUsable(LanguagePair(it.source, it.target)) }
        if (installed.any { it == null }) return@withLock
        unloadJob?.cancel()
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            installed.forEach { sdk.translator.preload(requireNotNull(it)) }
            DebugLogManager.logBase("asr", "translation_preload_ready", mapOf("packages" to route.size, "elapsedMs" to android.os.SystemClock.elapsedRealtime() - started))
        } finally {
            withContext(NonCancellable) { scheduleUnload(context, afterPreload = true) }
        }
    }

    suspend fun updateResidency(context: Context) = inferenceLock.withLock {
        if (client != null) scheduleUnload(context, afterPreload = false)
    }

    private suspend fun scheduleUnload(context: Context, afterPreload: Boolean) {
        unloadJob?.cancel()
        val minutes = Prefs(context).translationKeepAliveMinutes
        if (minutes == -1) return
        if (minutes == 0 && !afterPreload) {
            unloadModels()
            return
        }
        // Immediate release applies after translation. A cancelled recording must not leave
        // its speculative preload resident forever; retain it for the bounded recording window.
        val delayMs = if (minutes == 0) 60_000L else minutes * 60_000L
        unloadJob = residencyScope.launch {
            delay(delayMs)
            inferenceLock.withLock { unloadModels() }
        }
    }

    private suspend fun unloadModels() {
        val report = client?.translator?.unloadModels() ?: return
        DebugLogManager.logBase("asr", "translation_models_unloaded", mapOf("count" to report.unloadedModelCount, "released" to report.allReleased))
    }
}

internal fun supportsFoxletTranslation(): Boolean = Build.VERSION.SDK_INT >= 28 &&
    Build.SUPPORTED_ABIS.contains("arm64-v8a") &&
    android.os.Process.is64Bit()
