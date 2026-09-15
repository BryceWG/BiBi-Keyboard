package com.brycewg.asrkb.store

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.asr.LEGACY_X_ASR_VENDOR_ID
import com.brycewg.asrkb.asr.VolcAsrModelCatalog
import com.brycewg.asrkb.asr.VolcLegacyFlags
import com.brycewg.asrkb.clipboard.ClipboardSyncReceiveMode
import com.brycewg.asrkb.store.debug.DebugLogManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Prefs 初始化阶段的“迁移/清理/调试监听”任务（从 [Prefs] 中拆出）。
 *
 * 说明：
 * - 当前仍保持与历史行为一致：构造 Prefs 即会触发这些任务。
 * - 后续若要消除 init 副作用，可将 [run] 改为由显式入口（如 App 启动/升级流程）调用。
 */
internal object PrefsInitTasks {
    private const val TAG = "Prefs"

    @Volatile private var toggleListenerRegistered: Boolean = false

    @Volatile private var fnLegacyCleanupStarted: Boolean = false

    @Volatile private var frLegacyTelespeechCleanupStarted: Boolean = false

    private val globalToggleListener = SharedPreferences.OnSharedPreferenceChangeListener {
            prefs,
            key
        ->
        try {
            if (!prefs.contains(key)) return@OnSharedPreferenceChangeListener
            val v = try {
                prefs.getBoolean(key, false)
            } catch (_: ClassCastException) {
                return@OnSharedPreferenceChangeListener
            }
            DebugLogManager.log("prefs", "toggle", mapOf("key" to key, "value" to v))
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to log pref toggle", t)
        }
    }

    fun run(appContext: Context, sp: SharedPreferences) {
        registerGlobalToggleListenerIfNeeded(sp)
        migrateRecordingAutoStopModeIfNeeded(sp)
        migrateOnboardingGuideStateIfNeeded(sp)
        migrateTeleSpeechToFireRedAsrIfNeeded(sp)
        normalizeFireRedVariantIfNeeded(sp)
        cleanupLegacyTelespeechModelsIfNeeded(appContext, sp)
        normalizeBackupAsrTimeoutSensitivityIfNeeded(sp)
        migrateLegacyLocalStreamingVendorToXAsrIfNeeded(sp)
        migrateLegacyXAsrPrefsIfNeeded(sp)
        normalizeXAsrVariantIfNeeded(sp)
        migrateVolcAsrModelIfNeeded(sp)
        normalizeStepAudioModelIfNeeded(sp)
        migrateFunAsrFromSenseVoiceIfNeeded(sp)
        ensureFunAsrItnDefaultIfMissing(sp)
        normalizeFunAsrVariantIfNeeded(sp)
        cleanupLegacyFunAsrModelsIfNeeded(appContext, sp)
        migrateSyncClipboardReceiveModeIfNeeded(sp)
    }

    /** 保留已存在的 StepAudio 内置模型，避免升级后被 UI 当作自定义模型。 */
    private fun normalizeStepAudioModelIfNeeded(sp: SharedPreferences) {
        val stored = sp.getString(KEY_STEPAUDIO_MODEL, null)?.trim()
        if (stored.isNullOrEmpty()) {
            sp.edit { putString(KEY_STEPAUDIO_MODEL, Prefs.DEFAULT_STEPAUDIO_ASR_MODEL) }
        } else if (stored == Prefs.DEFAULT_STEPAUDIO_ASR_MODEL ||
            stored == Prefs.STEPAUDIO_ASR_MODEL_MAX
        ) {
            // Keep the canonical ID exactly as stored; both IDs are built-in choices.
            if (stored != sp.getString(KEY_STEPAUDIO_MODEL, null)) {
                sp.edit { putString(KEY_STEPAUDIO_MODEL, stored) }
            }
        }
    }

    /**
     * 将旧 realtime/auto-pull 组合迁移为互斥 Receive Mode。
     * keepBackgroundRealtime 对所有升级用户默认 false。
     */
    internal fun migrateSyncClipboardReceiveModeIfNeeded(sp: SharedPreferences) {
        try {
            if (sp.contains(KEY_SC_RECEIVE_MODE)) {
                if (!sp.contains(KEY_SC_KEEP_BACKGROUND_REALTIME)) {
                    sp.edit { putBoolean(KEY_SC_KEEP_BACKGROUND_REALTIME, false) }
                }
                return
            }
            val realtime = try {
                sp.getBoolean(KEY_SC_REALTIME, false)
            } catch (_: ClassCastException) {
                false
            }
            val autoPull = try {
                sp.getBoolean(KEY_SC_AUTO_PULL, false)
            } catch (_: ClassCastException) {
                false
            }
            val mode = ClipboardSyncReceiveMode.fromLegacy(realtime, autoPull)
            sp.edit {
                putString(KEY_SC_RECEIVE_MODE, mode.id)
                putBoolean(KEY_SC_KEEP_BACKGROUND_REALTIME, false)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to migrate SyncClipboard receive mode", t)
        }
    }

    private fun migrateRecordingAutoStopModeIfNeeded(sp: SharedPreferences) {
        try {
            if (sp.contains(KEY_RECORDING_AUTO_STOP_MODE)) return
            val legacyEnabled = try {
                sp.getBoolean(KEY_AUTO_STOP_ON_SILENCE_ENABLED, false)
            } catch (_: ClassCastException) {
                false
            }
            val mode = resolveRecordingAutoStopMode(
                storedModeId = null,
                legacySilenceEnabled = legacyEnabled
            )
            sp.edit { putString(KEY_RECORDING_AUTO_STOP_MODE, mode.id) }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to migrate recording auto-stop mode", t)
        }
    }

    private fun migrateTeleSpeechToFireRedAsrIfNeeded(sp: SharedPreferences) {
        try {
            val currentVendor = sp.getString(KEY_ASR_VENDOR, null)
            val backupVendor = sp.getString(KEY_BACKUP_ASR_VENDOR, null)
            val needsVendorMigration =
                currentVendor == "telespeech" || backupVendor == "telespeech"
            val needsPrefMigration =
                !sp.contains(KEY_FR_MODEL_VARIANT) &&
                    (
                        sp.contains(KEY_TS_MODEL_VARIANT) ||
                            sp.contains(KEY_TS_NUM_THREADS) ||
                            sp.contains(KEY_TS_KEEP_ALIVE_MINUTES) ||
                            sp.contains(KEY_TS_PRELOAD_ENABLED) ||
                            sp.contains(KEY_TS_USE_ITN) ||
                            sp.contains(KEY_TS_PSEUDO_STREAM_ENABLED)
                        )
            if (!needsVendorMigration && !needsPrefMigration) return

            sp.edit {
                if (currentVendor == "telespeech") {
                    putString(KEY_ASR_VENDOR, AsrVendor.FireRedAsr.id)
                }
                if (backupVendor == "telespeech") {
                    putString(KEY_BACKUP_ASR_VENDOR, AsrVendor.FireRedAsr.id)
                }
                if (!sp.contains(KEY_FR_MODEL_VARIANT) && sp.contains(KEY_TS_MODEL_VARIANT)) {
                    putString(KEY_FR_MODEL_VARIANT, "ctc-int8")
                }
                if (!sp.contains(KEY_FR_NUM_THREADS) && sp.contains(KEY_TS_NUM_THREADS)) {
                    putInt(KEY_FR_NUM_THREADS, sp.getInt(KEY_TS_NUM_THREADS, 2).coerceIn(1, 8))
                }
                if (!sp.contains(KEY_FR_KEEP_ALIVE_MINUTES) &&
                    sp.contains(KEY_TS_KEEP_ALIVE_MINUTES)
                ) {
                    putInt(KEY_FR_KEEP_ALIVE_MINUTES, sp.getInt(KEY_TS_KEEP_ALIVE_MINUTES, -1))
                }
                if (!sp.contains(KEY_FR_PRELOAD_ENABLED) && sp.contains(KEY_TS_PRELOAD_ENABLED)) {
                    putBoolean(KEY_FR_PRELOAD_ENABLED, sp.getBoolean(KEY_TS_PRELOAD_ENABLED, true))
                }
                if (!sp.contains(KEY_FR_USE_ITN) && sp.contains(KEY_TS_USE_ITN)) {
                    putBoolean(KEY_FR_USE_ITN, sp.getBoolean(KEY_TS_USE_ITN, true))
                }
                if (!sp.contains(KEY_FR_PSEUDO_STREAM_ENABLED) &&
                    sp.contains(KEY_TS_PSEUDO_STREAM_ENABLED)
                ) {
                    putBoolean(
                        KEY_FR_PSEUDO_STREAM_ENABLED,
                        sp.getBoolean(KEY_TS_PSEUDO_STREAM_ENABLED, false)
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to migrate TeleSpeech prefs to FireRedASR", t)
        }
    }

    private fun normalizeFireRedVariantIfNeeded(sp: SharedPreferences) {
        try {
            if (!sp.contains(KEY_FR_MODEL_VARIANT)) return
            val variant = sp.getString(KEY_FR_MODEL_VARIANT, "ctc-int8") ?: "ctc-int8"
            if (variant == "ctc-int8") return
            sp.edit { putString(KEY_FR_MODEL_VARIANT, "ctc-int8") }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to normalize FireRedASR variant", t)
        }
    }

    private fun cleanupLegacyTelespeechModelsIfNeeded(appContext: Context, sp: SharedPreferences) {
        try {
            if (sp.getBoolean(KEY_FR_LEGACY_TELESPEECH_MODEL_CLEANED, false)) return
            if (frLegacyTelespeechCleanupStarted) return
            frLegacyTelespeechCleanupStarted = true
            sp.edit { putBoolean(KEY_FR_LEGACY_TELESPEECH_MODEL_CLEANED, true) }

            val externalBase = try {
                appContext.getExternalFilesDir(null)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to get external files dir for TeleSpeech cleanup", t)
                null
            }
            val targets = buildList {
                add(File(appContext.filesDir, "telespeech"))
                if (externalBase != null) {
                    add(File(externalBase, "telespeech"))
                }
            }.distinctBy { it.absolutePath }

            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                targets.forEach { dir ->
                    if (!dir.exists()) return@forEach
                    try {
                        if (!dir.deleteRecursively()) {
                            Log.w(TAG, "Failed to delete legacy TeleSpeech dir: ${dir.path}")
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "Error deleting legacy TeleSpeech dir: ${dir.path}", t)
                    }
                }
                frLegacyTelespeechCleanupStarted = false
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to cleanup legacy TeleSpeech models", t)
        }
    }

    private fun normalizeBackupAsrTimeoutSensitivityIfNeeded(sp: SharedPreferences) {
        try {
            if (!sp.contains(KEY_BACKUP_ASR_TIMEOUT_SENSITIVITY)) return

            val currentInt = try {
                sp.getInt(KEY_BACKUP_ASR_TIMEOUT_SENSITIVITY, 0)
            } catch (_: ClassCastException) {
                null
            }

            if (currentInt != null) {
                val clamped = currentInt.coerceIn(0, 2)
                if (clamped != currentInt) {
                    sp.edit { putInt(KEY_BACKUP_ASR_TIMEOUT_SENSITIVITY, clamped) }
                }
                return
            }

            val currentLong = try {
                sp.getLong(KEY_BACKUP_ASR_TIMEOUT_SENSITIVITY, 0L)
            } catch (_: ClassCastException) {
                null
            }
            val fromLong: Int? =
                currentLong?.takeIf {
                    it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
                }?.toInt()

            val fromString = if (fromLong == null) {
                val s = try {
                    sp.getString(KEY_BACKUP_ASR_TIMEOUT_SENSITIVITY, null)
                } catch (_: ClassCastException) {
                    null
                }
                s?.trim()?.toIntOrNull()
            } else {
                null
            }

            val normalized = (fromLong ?: fromString)?.coerceIn(0, 2)
            if (normalized == null) {
                sp.edit { remove(KEY_BACKUP_ASR_TIMEOUT_SENSITIVITY) }
                return
            }
            sp.edit { putInt(KEY_BACKUP_ASR_TIMEOUT_SENSITIVITY, normalized) }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to normalize backup ASR timeout sensitivity", t)
        }
    }

    private fun migrateLegacyLocalStreamingVendorToXAsrIfNeeded(sp: SharedPreferences) {
        try {
            val currentVendor = sp.getString(KEY_ASR_VENDOR, null)
            val backupVendor = sp.getString(KEY_BACKUP_ASR_VENDOR, null)
            if (
                currentVendor != LEGACY_X_ASR_VENDOR_ID &&
                backupVendor != LEGACY_X_ASR_VENDOR_ID
            ) {
                return
            }

            sp.edit {
                if (currentVendor == LEGACY_X_ASR_VENDOR_ID) {
                    putString(KEY_ASR_VENDOR, AsrVendor.XAsr.id)
                }
                if (backupVendor == LEGACY_X_ASR_VENDOR_ID) {
                    putString(KEY_BACKUP_ASR_VENDOR, AsrVendor.XAsr.id)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to migrate legacy local streaming vendor to X-ASR", t)
        }
    }

    private fun migrateLegacyXAsrPrefsIfNeeded(sp: SharedPreferences) {
        try {
            val hasLegacyPrefs =
                sp.contains(LEGACY_KEY_X_ASR_MODEL_VARIANT) ||
                    sp.contains(LEGACY_KEY_X_ASR_NUM_THREADS) ||
                    sp.contains(LEGACY_KEY_X_ASR_KEEP_ALIVE_MINUTES) ||
                    sp.contains(LEGACY_KEY_X_ASR_PRELOAD_ENABLED) ||
                    sp.contains(LEGACY_KEY_X_ASR_USE_ITN)
            if (!hasLegacyPrefs) return

            sp.edit {
                if (!sp.contains(KEY_X_ASR_MODEL_VARIANT) &&
                    sp.contains(LEGACY_KEY_X_ASR_MODEL_VARIANT)
                ) {
                    putString(
                        KEY_X_ASR_MODEL_VARIANT,
                        sp.getString(LEGACY_KEY_X_ASR_MODEL_VARIANT, "x-asr-480ms")
                    )
                }
                if (!sp.contains(KEY_X_ASR_NUM_THREADS) &&
                    sp.contains(LEGACY_KEY_X_ASR_NUM_THREADS)
                ) {
                    putInt(
                        KEY_X_ASR_NUM_THREADS,
                        sp.getInt(LEGACY_KEY_X_ASR_NUM_THREADS, 2).coerceIn(1, 8)
                    )
                }
                if (!sp.contains(KEY_X_ASR_KEEP_ALIVE_MINUTES) &&
                    sp.contains(LEGACY_KEY_X_ASR_KEEP_ALIVE_MINUTES)
                ) {
                    putInt(
                        KEY_X_ASR_KEEP_ALIVE_MINUTES,
                        sp.getInt(LEGACY_KEY_X_ASR_KEEP_ALIVE_MINUTES, -1)
                    )
                }
                if (!sp.contains(KEY_X_ASR_PRELOAD_ENABLED) &&
                    sp.contains(LEGACY_KEY_X_ASR_PRELOAD_ENABLED)
                ) {
                    putBoolean(
                        KEY_X_ASR_PRELOAD_ENABLED,
                        sp.getBoolean(LEGACY_KEY_X_ASR_PRELOAD_ENABLED, true)
                    )
                }
                if (!sp.contains(KEY_X_ASR_USE_ITN) &&
                    sp.contains(LEGACY_KEY_X_ASR_USE_ITN)
                ) {
                    putBoolean(KEY_X_ASR_USE_ITN, sp.getBoolean(LEGACY_KEY_X_ASR_USE_ITN, true))
                }
                remove(LEGACY_KEY_X_ASR_MODEL_VARIANT)
                remove(LEGACY_KEY_X_ASR_NUM_THREADS)
                remove(LEGACY_KEY_X_ASR_KEEP_ALIVE_MINUTES)
                remove(LEGACY_KEY_X_ASR_PRELOAD_ENABLED)
                remove(LEGACY_KEY_X_ASR_USE_ITN)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to migrate legacy X-ASR prefs", t)
        }
    }

    private fun normalizeXAsrVariantIfNeeded(sp: SharedPreferences) {
        try {
            val variant = sp.getString(KEY_X_ASR_MODEL_VARIANT, "x-asr-480ms") ?: "x-asr-480ms"
            if (variant == "x-asr-480ms") return
            sp.edit { putString(KEY_X_ASR_MODEL_VARIANT, "x-asr-480ms") }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to normalize X-ASR variant", t)
        }
    }

    private fun migrateOnboardingGuideStateIfNeeded(sp: SharedPreferences) {
        try {
            if (sp.getBoolean(KEY_SHOWN_ONBOARDING_GUIDE_V2_ONCE, false)) return

            val shownQuickGuide = sp.getBoolean(KEY_SHOWN_QUICK_GUIDE_ONCE, false)
            val shownModelGuide = sp.getBoolean(KEY_SHOWN_MODEL_GUIDE_ONCE, false)
            if (shownQuickGuide || shownModelGuide) {
                sp.edit { putBoolean(KEY_SHOWN_ONBOARDING_GUIDE_V2_ONCE, true) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to migrate onboarding guide state", t)
        }
    }

    private fun registerGlobalToggleListenerIfNeeded(sp: SharedPreferences) {
        if (!toggleListenerRegistered) {
            try {
                sp.registerOnSharedPreferenceChangeListener(globalToggleListener)
                toggleListenerRegistered = true
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to register global toggle listener", t)
            }
        }
    }

    fun migrateVolcAsrModelIfNeeded(sp: SharedPreferences) {
        try {
            val raw = sp.getString(KEY_VOLC_ASR_MODEL, null)?.trim().orEmpty()
            val known = if (raw.isNotEmpty()) VolcAsrModelCatalog.fromId(raw) else null
            val (model, reason) = when {
                known != null -> known to "canonical"
                raw.isNotEmpty() -> VolcAsrModelCatalog.fromIdOrDefault(raw) to "unknown"
                else -> {
                    val streaming = sp.getBoolean(KEY_VOLC_STREAMING_ENABLED, true)
                    val fileStandard = sp.getBoolean(KEY_VOLC_FILE_STANDARD_ENABLED, true)
                    val modelV2 = sp.getBoolean(KEY_VOLC_MODEL_V2_ENABLED, true)
                    VolcAsrModelCatalog.fromLegacyFlags(streaming, fileStandard, modelV2) to "legacy"
                }
            }
            val flags = VolcAsrModelCatalog.legacyFlags(model.id)
            val flagsMatch = storedVolcLegacyFlagsMatch(sp, flags)
            if (raw == model.id && flagsMatch && sp.contains(KEY_VOLC_ASR_MODEL)) {
                return
            }
            sp.edit {
                putString(KEY_VOLC_ASR_MODEL, model.id)
                putBoolean(KEY_VOLC_STREAMING_ENABLED, flags.streamingEnabled)
                putBoolean(KEY_VOLC_FILE_STANDARD_ENABLED, flags.fileStandardEnabled)
                putBoolean(KEY_VOLC_MODEL_V2_ENABLED, flags.modelV2Enabled)
            }
            val logReason = when {
                reason != "canonical" -> reason
                raw != model.id -> "normalize"
                else -> "sync"
            }
            DebugLogManager.logBase(
                "asr",
                "volc_model_migrated",
                mapOf(
                    "raw" to raw,
                    "canonical" to model.id,
                    "reason" to logReason
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to migrate Volc ASR model prefs", t)
        }
    }

    private fun storedVolcLegacyFlagsMatch(sp: SharedPreferences, flags: VolcLegacyFlags): Boolean {
        if (!sp.contains(KEY_VOLC_STREAMING_ENABLED) ||
            !sp.contains(KEY_VOLC_FILE_STANDARD_ENABLED) ||
            !sp.contains(KEY_VOLC_MODEL_V2_ENABLED)
        ) {
            return false
        }
        return sp.getBoolean(KEY_VOLC_STREAMING_ENABLED, true) == flags.streamingEnabled &&
            sp.getBoolean(KEY_VOLC_FILE_STANDARD_ENABLED, true) == flags.fileStandardEnabled &&
            sp.getBoolean(KEY_VOLC_MODEL_V2_ENABLED, true) == flags.modelV2Enabled
    }

    fun migrateFunAsrFromSenseVoiceIfNeeded(sp: SharedPreferences) {
        try {
            if (sp.contains(KEY_FN_MODEL_VARIANT)) return
            val svVariant = sp.getString(KEY_SV_MODEL_VARIANT, "") ?: ""
            if (!svVariant.startsWith("nano-")) return

            sp.edit {
                putString(KEY_FN_MODEL_VARIANT, svVariant)
                putInt(KEY_FN_NUM_THREADS, sp.getInt(KEY_SV_NUM_THREADS, 4).coerceIn(1, 8))
                // FunASR Nano：支持内建 ITN，默认开启
                putBoolean(KEY_FN_USE_ITN, sp.getBoolean(KEY_SV_USE_ITN, true))
                putBoolean(KEY_FN_PRELOAD_ENABLED, sp.getBoolean(KEY_SV_PRELOAD_ENABLED, true))
                putInt(KEY_FN_KEEP_ALIVE_MINUTES, sp.getInt(KEY_SV_KEEP_ALIVE_MINUTES, -1))
                // 若当前选择为 SenseVoice 且使用 nano 变体，自动迁移到 FunASR Nano
                val currentVendor =
                    sp.getString(KEY_ASR_VENDOR, AsrVendor.SiliconFlow.id)
                        ?: AsrVendor.SiliconFlow.id
                if (currentVendor == AsrVendor.SenseVoice.id) {
                    putString(KEY_ASR_VENDOR, AsrVendor.FunAsrNano.id)
                    putString(KEY_SV_MODEL_VARIANT, "small-int8")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to migrate FunASR Nano prefs from SenseVoice", t)
        }
    }

    private fun ensureFunAsrItnDefaultIfMissing(sp: SharedPreferences) {
        try {
            if (sp.contains(KEY_FN_USE_ITN)) return
            sp.edit { putBoolean(KEY_FN_USE_ITN, true) }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to initialize FunASR Nano ITN default", t)
        }
    }

    private fun normalizeFunAsrVariantIfNeeded(sp: SharedPreferences) {
        try {
            if (!sp.contains(KEY_FN_MODEL_VARIANT)) return
            val variant = sp.getString(KEY_FN_MODEL_VARIANT, "nano-int8") ?: "nano-int8"
            val normalized = com.brycewg.asrkb.asr.normalizeFunAsrNanoVariant(variant)
            if (variant == normalized) return
            sp.edit { putString(KEY_FN_MODEL_VARIANT, normalized) }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to normalize FunASR Nano variant", t)
        }
    }

    private fun cleanupLegacyFunAsrModelsIfNeeded(appContext: Context, sp: SharedPreferences) {
        try {
            if (sp.getBoolean(KEY_FN_LEGACY_MODEL_CLEANED, false)) return
            if (fnLegacyCleanupStarted) return
            fnLegacyCleanupStarted = true
            sp.edit { putBoolean(KEY_FN_LEGACY_MODEL_CLEANED, true) }
            val base = try {
                appContext.getExternalFilesDir(null)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to get external files dir for FunASR cleanup", t)
                null
            } ?: appContext.filesDir

            val legacyTargets = listOf(
                File(File(base, "sensevoice"), "nano-int8"),
                File(File(base, "sensevoice"), "nano-full")
            )

            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                legacyTargets.forEach { dir ->
                    if (!dir.exists()) return@forEach
                    try {
                        if (!dir.deleteRecursively()) {
                            Log.w(TAG, "Failed to delete legacy FunASR dir: ${dir.path}")
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "Error deleting legacy FunASR dir: ${dir.path}", t)
                    }
                }
                fnLegacyCleanupStarted = false
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to cleanup legacy FunASR models", t)
        }
    }
}
