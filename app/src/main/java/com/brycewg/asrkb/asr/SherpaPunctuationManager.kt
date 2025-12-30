package com.brycewg.asrkb.asr

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.brycewg.asrkb.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * sherpa-onnx 文本标点管理器（Offline/Online Punctuation）。
 *
 * - 通过反射调用 sherpa-onnx Kotlin API（OfflinePunctuation/OnlinePunctuation）；
 * - 统一管理标点模型的文件路径，供 TeleSpeech/Paraformer 共用；
 * - 线程数固定为 1，不暴露配置项；
 * - 当前仅提供能力封装，不在任意 ASR 引擎中自动接入。
 */
class SherpaPunctuationManager private constructor() {

  companion object {
    private const val TAG = "SherpaPunctuationManager"

    // 统一目录/文件约定：三个本地模型共用同一套标点模型
    private const val MODEL_DIR_NAME = "punctuation"
    private const val MODEL_FILE_NAME = "model.int8.onnx"

    // 兼容：官方压缩包解压后的默认目录名
    private const val LEGACY_MODEL_PARENT =
      "sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8"

    @Volatile
    private var hasWarnedMissingModel: Boolean = false

    @Volatile
    private var instance: SherpaPunctuationManager? = null

    fun getInstance(): SherpaPunctuationManager {
      return instance ?: synchronized(this) {
        instance ?: SherpaPunctuationManager().also { instance = it }
      }
    }

    /**
     * 统一查找标点模型所在目录：
     * - 优先 externalFilesDir/punctuation 下的 model.int8.onnx；
     * - 次选 externalFilesDir/LEGACY_MODEL_PARENT；
     * - 最后在 punctuation/ 下遍历一级子目录寻找 model.int8.onnx。
     */
    fun findPunctuationModelDir(context: Context): java.io.File? {
      val base = try {
        context.getExternalFilesDir(null)
      } catch (t: Throwable) {
        Log.w(TAG, "Failed to get external files dir", t)
        null
      } ?: context.filesDir

      val root = java.io.File(base, MODEL_DIR_NAME)
      val direct = java.io.File(root, MODEL_FILE_NAME)
      if (direct.exists()) return root

      val legacyRoot = java.io.File(base, LEGACY_MODEL_PARENT)
      val legacyFile = java.io.File(legacyRoot, MODEL_FILE_NAME)
      if (legacyFile.exists()) return legacyRoot

      if (root.exists()) {
        val subs = root.listFiles() ?: emptyArray()
        for (dir in subs) {
          if (!dir.isDirectory) continue
          val f = java.io.File(dir, MODEL_FILE_NAME)
          if (f.exists()) return dir
        }
      }
      return null
    }

    /**
     * 返回离线标点模型路径（model.int8.onnx），若未找到则返回 null。
     */
    fun findOfflineModelPath(context: Context): String? {
      val dir = findPunctuationModelDir(context) ?: return null
      val file = java.io.File(dir, MODEL_FILE_NAME)
      return if (file.exists()) file.absolutePath else null
    }

    /**
     * 是否已安装本地标点模型。
     */
    fun isModelInstalled(context: Context): Boolean {
      return findOfflineModelPath(context) != null
    }

    /**
     * 清除已安装的标点模型（通用），并释放缓存实例。
     * 返回是否实际删除了任何文件。
     */
    fun clearInstalledModel(context: Context): Boolean {
      // 先尝试释放运行时实例
      try {
        getInstance().unloadOffline()
      } catch (t: Throwable) {
        Log.e(TAG, "Failed to unload offline punctuation", t)
      }
      try {
        getInstance().unloadOnline()
      } catch (t: Throwable) {
        Log.e(TAG, "Failed to unload online punctuation", t)
      }

      val base = try {
        context.getExternalFilesDir(null)
      } catch (t: Throwable) {
        Log.w(TAG, "Failed to get external files dir for clearInstalledModel", t)
        null
      } ?: context.filesDir

      var deleted = false
      val primary = java.io.File(base, MODEL_DIR_NAME)
      val legacy = java.io.File(base, LEGACY_MODEL_PARENT)

      fun clearDir(dir: java.io.File) {
        if (!dir.exists()) return
        try {
          if (dir.deleteRecursively()) {
            deleted = true
          } else {
            Log.w(TAG, "Failed to delete punctuation dir: ${dir.path}")
          }
        } catch (t: Throwable) {
          Log.w(TAG, "Error deleting punctuation dir: ${dir.path}", t)
        }
      }

      clearDir(primary)
      clearDir(legacy)
      // 清理过程中重置提示标志，使后续可再次提示
      hasWarnedMissingModel = false
      return deleted
    }

    /**
     * 缺少标点模型时给出一次性 Toast 提示（当前进程仅提示一次）。
     */
    fun maybeWarnModelMissing(context: Context) {
      if (isModelInstalled(context)) return
      if (hasWarnedMissingModel) return
      hasWarnedMissingModel = true
      try {
        Toast.makeText(
          context.applicationContext,
          context.getString(R.string.toast_punct_model_missing),
          Toast.LENGTH_SHORT
        ).show()
      } catch (t: Throwable) {
        Log.e(TAG, "Failed to show punctuation missing toast", t)
      }
    }
  }

  private val mutex = Mutex()

  // OfflinePunctuation 反射类与缓存实例
  @Volatile
  private var clsOfflinePunctuation: Class<*>? = null
  @Volatile
  private var clsOfflinePuncConfig: Class<*>? = null
  @Volatile
  private var clsOfflinePuncModelConfig: Class<*>? = null
  @Volatile
  private var offline: ReflectiveOfflinePunctuation? = null
  @Volatile
  private var offlineModelPath: String? = null

  // OnlinePunctuation 反射类与缓存实例（为流式模型预留）
  @Volatile
  private var clsOnlinePunctuation: Class<*>? = null
  @Volatile
  private var clsOnlinePuncConfig: Class<*>? = null
  @Volatile
  private var clsOnlinePuncModelConfig: Class<*>? = null
  @Volatile
  private var online: ReflectiveOnlinePunctuation? = null
  @Volatile
  private var onlineModelKey: OnlineModelKey? = null

  private data class OnlineModelKey(
    val cnnBilstm: String,
    val bpeVocab: String?
  )

  fun isOfflineSupported(): Boolean {
    return try {
      Class.forName("com.k2fsa.sherpa.onnx.OfflinePunctuation")
      true
    } catch (t: Throwable) {
      Log.d(TAG, "OfflinePunctuation not available", t)
      false
    }
  }

  fun isOnlineSupported(): Boolean {
    return try {
      Class.forName("com.k2fsa.sherpa.onnx.OnlinePunctuation")
      true
    } catch (t: Throwable) {
      Log.d(TAG, "OnlinePunctuation not available", t)
      false
    }
  }

  /**
   * 确保 OfflinePunctuation 已按当前文件路径加载。
   * 不触发任何下载，仅根据现有文件构建实例。
   */
  suspend fun ensureOfflineLoaded(context: Context): Boolean = mutex.withLock {
    if (!isOfflineSupported()) return@withLock false
    val modelPath = findOfflineModelPath(context) ?: return@withLock false
    if (offline != null && offlineModelPath == modelPath) return@withLock true

    initOfflineClasses()
    val inst = createOfflineInstance(modelPath) ?: return@withLock false
    try {
      offline?.release()
    } catch (t: Throwable) {
      Log.e(TAG, "Failed to release previous OfflinePunctuation", t)
    }
    offline = inst
    offlineModelPath = modelPath
    true
  }

  /**
   * 通过指定的模型文件路径准备 OnlinePunctuation（流式标点）。
   * - cnnBilstm: CNN-BiLSTM 模型 onnx 路径；
   * - bpeVocab: 词表路径，可为空。
   */
  suspend fun ensureOnlineLoaded(
    cnnBilstm: String,
    bpeVocab: String?
  ): Boolean = mutex.withLock {
    if (!isOnlineSupported()) return@withLock false
    val key = OnlineModelKey(cnnBilstm, bpeVocab)
    if (online != null && onlineModelKey == key) return@withLock true

    initOnlineClasses()
    val inst = createOnlineInstance(cnnBilstm, bpeVocab) ?: return@withLock false
    try {
      online?.release()
    } catch (t: Throwable) {
      Log.e(TAG, "Failed to release previous OnlinePunctuation", t)
    }
    online = inst
    onlineModelKey = key
    true
  }

  /**
   * 对文本执行离线标点（ct-transformer），失败时返回原文。
   */
  suspend fun addOfflinePunctuation(context: Context, text: String): String {
    if (text.isEmpty()) return text
    val ok = ensureOfflineLoaded(context)
    val p = offline
    if (!ok || p == null) return text

    return withContext(Dispatchers.Default) {
      try {
        p.addPunctuation(text) ?: text
      } catch (t: Throwable) {
        Log.e(TAG, "Offline punctuation failed", t)
        text
      }
    }
  }

  /**
   * 对文本执行在线标点（CNN-BiLSTM），失败时返回原文。
   * 调用方需事先通过 ensureOnlineLoaded 准备好模型。
   */
  suspend fun addOnlinePunctuation(text: String): String {
    if (text.isEmpty()) return text
    val p = online ?: return text
    return withContext(Dispatchers.Default) {
      try {
        p.addPunctuation(text) ?: text
      } catch (t: Throwable) {
        Log.e(TAG, "Online punctuation failed", t)
        text
      }
    }
  }

  fun unloadOffline() {
    try {
      val inst = offline
      offline = null
      offlineModelPath = null
      inst?.release()
    } catch (t: Throwable) {
      Log.e(TAG, "unloadOffline failed", t)
    }
  }

  fun unloadOnline() {
    try {
      val inst = online
      online = null
      onlineModelKey = null
      inst?.release()
    } catch (t: Throwable) {
      Log.e(TAG, "unloadOnline failed", t)
    }
  }

  private fun initOfflineClasses() {
    if (clsOfflinePunctuation == null) {
      clsOfflinePunctuation = Class.forName("com.k2fsa.sherpa.onnx.OfflinePunctuation")
      clsOfflinePuncConfig = Class.forName("com.k2fsa.sherpa.onnx.OfflinePunctuationConfig")
      clsOfflinePuncModelConfig = Class.forName("com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig")
      Log.d(TAG, "Initialized OfflinePunctuation reflection classes")
    }
  }

  private fun initOnlineClasses() {
    if (clsOnlinePunctuation == null) {
      clsOnlinePunctuation = Class.forName("com.k2fsa.sherpa.onnx.OnlinePunctuation")
      clsOnlinePuncConfig = Class.forName("com.k2fsa.sherpa.onnx.OnlinePunctuationConfig")
      clsOnlinePuncModelConfig = Class.forName("com.k2fsa.sherpa.onnx.OnlinePunctuationModelConfig")
      Log.d(TAG, "Initialized OnlinePunctuation reflection classes")
    }
  }

  private fun trySetField(target: Any, name: String, value: Any?): Boolean {
    return try {
      val f = target.javaClass.getDeclaredField(name)
      f.isAccessible = true
      f.set(target, value)
      true
    } catch (t: Throwable) {
      try {
        val methodName = "set" + name.replaceFirstChar {
          if (it.isLowerCase()) it.titlecase() else it.toString()
        }
        val m = if (value == null) {
          target.javaClass.getMethod(methodName, Any::class.java)
        } else {
          target.javaClass.getMethod(methodName, value.javaClass)
        }
        m.invoke(target, value)
        true
      } catch (t2: Throwable) {
        Log.w(TAG, "Failed to set field '$name'", t2)
        false
      }
    }
  }

  private fun createOfflineInstance(modelPath: String): ReflectiveOfflinePunctuation? {
    return try {
      val modelCfg = clsOfflinePuncModelConfig!!.getDeclaredConstructor().newInstance()
      trySetField(modelCfg, "ctTransformer", modelPath)
      trySetField(modelCfg, "numThreads", 1)
      trySetField(modelCfg, "debug", false)
      trySetField(modelCfg, "provider", "cpu")

      val cfgCtor = clsOfflinePuncConfig!!.getDeclaredConstructor(clsOfflinePuncModelConfig)
      val cfg = cfgCtor.newInstance(modelCfg)

      val ctor = clsOfflinePunctuation!!.getDeclaredConstructor(
        android.content.res.AssetManager::class.java,
        clsOfflinePuncConfig
      )
      val inst = ctor.newInstance(null, cfg)
      ReflectiveOfflinePunctuation(inst, clsOfflinePunctuation!!)
    } catch (t: Throwable) {
      Log.e(TAG, "Failed to create OfflinePunctuation instance", t)
      null
    }
  }

  private fun createOnlineInstance(
    cnnBilstm: String,
    bpeVocab: String?
  ): ReflectiveOnlinePunctuation? {
    return try {
      val modelCfg = clsOnlinePuncModelConfig!!.getDeclaredConstructor().newInstance()
      trySetField(modelCfg, "cnnBilstm", cnnBilstm)
      if (!bpeVocab.isNullOrBlank()) {
        trySetField(modelCfg, "bpeVocab", bpeVocab)
      }
      trySetField(modelCfg, "numThreads", 1)
      trySetField(modelCfg, "debug", false)
      trySetField(modelCfg, "provider", "cpu")

      val cfgCtor = clsOnlinePuncConfig!!.getDeclaredConstructor(clsOnlinePuncModelConfig)
      val cfg = cfgCtor.newInstance(modelCfg)

      val ctor = clsOnlinePunctuation!!.getDeclaredConstructor(
        android.content.res.AssetManager::class.java,
        clsOnlinePuncConfig
      )
      val inst = ctor.newInstance(null, cfg)
      ReflectiveOnlinePunctuation(inst, clsOnlinePunctuation!!)
    } catch (t: Throwable) {
      Log.e(TAG, "Failed to create OnlinePunctuation instance", t)
      null
    }
  }
}

private class ReflectiveOfflinePunctuation(
  private val instance: Any,
  private val cls: Class<*>
) {

  fun addPunctuation(text: String): String? {
    return try {
      cls.getMethod("addPunctuation", String::class.java)
        .invoke(instance, text) as? String
    } catch (t: Throwable) {
      Log.e("ReflectiveOfflinePunc", "addPunctuation failed", t)
      null
    }
  }

  fun release() {
    try {
      cls.getMethod("release").invoke(instance)
    } catch (t: Throwable) {
      Log.e("ReflectiveOfflinePunc", "release failed", t)
    }
  }
}

private class ReflectiveOnlinePunctuation(
  private val instance: Any,
  private val cls: Class<*>
) {

  fun addPunctuation(text: String): String? {
    return try {
      cls.getMethod("addPunctuation", String::class.java)
        .invoke(instance, text) as? String
    } catch (t: Throwable) {
      Log.e("ReflectiveOnlinePunc", "addPunctuation failed", t)
      null
    }
  }

  fun release() {
    try {
      cls.getMethod("release").invoke(instance)
    } catch (t: Throwable) {
      Log.e("ReflectiveOnlinePunc", "release failed", t)
    }
  }
}
