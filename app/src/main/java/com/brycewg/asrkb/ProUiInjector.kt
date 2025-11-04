package com.brycewg.asrkb

import android.app.Activity
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub

/**
 * 将 Pro 专属 UI 动态注入到主工程预留的插槽中。
 * 注意：不得在此处引用仅存在于 pro 源集的资源常量或类。
 */
object ProUiInjector {
  private const val TAG = "ProUiInjector"

  /**
   * 备份设置页注入：
   * - main 布局提供 ViewStub：@id/pro_inject_stub_backup
   * - pro 侧提供布局：res/layout/pro_backup_auto.xml（包含自定义 View/逻辑）
   */
  fun injectIntoBackupSettings(activity: Activity, root: View) {
    if (!Edition.isPro) return
    val res = activity.resources
    val pkg = activity.packageName
    try {
      val layoutId = res.getIdentifier("pro_backup_auto", "layout", pkg)
      if (layoutId == 0) return

      val stubId = res.getIdentifier("pro_inject_stub_backup", "id", pkg)
      val stub = if (stubId != 0) root.findViewById<ViewStub?>(stubId) else null
      val inflater = LayoutInflater.from(activity)

      if (stub != null) {
        stub.layoutResource = layoutId
        stub.inflate()
      } else {
        val containerId = res.getIdentifier("pro_inject_slot_backup", "id", pkg)
        val container = if (containerId != 0) root.findViewById<ViewGroup?>(containerId) else null
        if (container != null) {
          inflater.inflate(layoutId, container, true)
        } else if (root is ViewGroup) {
          inflater.inflate(layoutId, root, true)
        }
      }
    } catch (t: Throwable) {
      if (BuildConfig.DEBUG) Log.d(TAG, "skip pro ui inject: ${t.message}")
    }
  }

  /**
   * ASR 设置页注入：
   * - main 布局提供 ViewStub：@id/pro_inject_stub_asr
   * - pro 侧提供布局：res/layout/pro_asr_settings_extra.xml（包含入口按钮）
   * - 按钮 ID 约定：@id/btn_pro_asr_context，点击触发隐式 Action：com.brycewg.asrkb.pro.SHOW_ASR_CONTEXT
   */
  fun injectIntoAsrSettings(activity: Activity, root: View) {
    if (!Edition.isPro) return
    val res = activity.resources
    val pkg = activity.packageName
    try {
      val layoutId = res.getIdentifier("pro_asr_settings_extra", "layout", pkg)
      if (layoutId == 0) return

      val stubId = res.getIdentifier("pro_inject_stub_asr", "id", pkg)
      val stub = if (stubId != 0) root.findViewById<ViewStub?>(stubId) else null
      val inflater = LayoutInflater.from(activity)

      val inflatedRoot: View? = if (stub != null) {
        stub.layoutResource = layoutId
        stub.inflate()
      } else {
        val containerId = res.getIdentifier("pro_inject_slot_asr", "id", pkg)
        val container = if (containerId != 0) root.findViewById<ViewGroup?>(containerId) else null
        if (container != null) {
          inflater.inflate(layoutId, container, true)
          container
        } else if (root is ViewGroup) {
          inflater.inflate(layoutId, root, true)
          root
        } else {
          null
        }
      }

      // 绑定按钮点击 -> 隐式跳转至 Pro 页面
      val btnId = res.getIdentifier("btn_pro_asr_context", "id", pkg)
      val host = inflatedRoot ?: root
      val btn = if (btnId != 0) host.findViewById<View?>(btnId) else null
      btn?.setOnClickListener {
        try {
          val intent = android.content.Intent("com.brycewg.asrkb.pro.SHOW_ASR_CONTEXT")
          activity.startActivity(intent)
        } catch (t: Throwable) {
          if (BuildConfig.DEBUG) Log.d(TAG, "Failed to start pro ASR context activity: ${t.message}")
        }
      }
    } catch (t: Throwable) {
      if (BuildConfig.DEBUG) Log.d(TAG, "skip pro ui inject(asr): ${t.message}")
    }
  }

  /**
   * 火山引擎流式设置区注入：
   * - main 布局提供 ViewStub：@id/pro_inject_stub_volc_streaming
   * - pro 侧提供布局：res/layout/pro_volc_streaming_extras.xml（包含自定义 View/逻辑）
   */
  fun injectIntoVolcStreamingExtras(activity: Activity, root: View) {
    if (!Edition.isPro) return
    val res = activity.resources
    val pkg = activity.packageName
    try {
      val layoutId = res.getIdentifier("pro_volc_streaming_extras", "layout", pkg)
      if (layoutId == 0) return

      val stubId = res.getIdentifier("pro_inject_stub_volc_streaming", "id", pkg)
      val stub = if (stubId != 0) root.findViewById<ViewStub?>(stubId) else null
      val inflater = LayoutInflater.from(activity)
      if (stub != null) {
        stub.layoutResource = layoutId
        stub.inflate()
      } else if (root is ViewGroup) {
        inflater.inflate(layoutId, root, true)
      }
    } catch (t: Throwable) {
      if (BuildConfig.DEBUG) Log.d(TAG, "skip pro ui inject(volc streaming): ${t.message}")
    }
  }

  /**
   * 构建备份 JSON（包含 Pro 变体的额外键）。
   * - OSS：直接返回 Prefs.exportJsonString()
   * - Pro：在不引用 Pro 源集类的前提下，直接从同名 SP 读取并合并额外键
   */
  fun buildBackupJson(context: android.content.Context, prefs: com.brycewg.asrkb.store.Prefs): String {
    val base = try { prefs.exportJsonString() } catch (_: Throwable) { "{}" }
    if (!Edition.isPro) return base
    return try {
      val sp = context.getSharedPreferences("asr_prefs", android.content.Context.MODE_PRIVATE)
      val o = org.json.JSONObject(base)
      // Pro: 个性化 ASR 上下文与热词
      val ctx = sp.getString("asr_context_text", null)
      val hot = sp.getString("asr_hotwords", null)
      if (!ctx.isNullOrEmpty()) o.put("asr_context_text", ctx)
      if (!hot.isNullOrEmpty()) o.put("asr_hotwords", hot)
      // Pro: 自动备份配置（可选）
      if (sp.contains("pro_auto_backup_enabled")) o.put("pro_auto_backup_enabled", sp.getBoolean("pro_auto_backup_enabled", false))
      if (sp.contains("pro_auto_backup_interval_hours")) o.put("pro_auto_backup_interval_hours", sp.getInt("pro_auto_backup_interval_hours", 24))
      // Pro: 火山引擎双重识别开关
      if (sp.contains("pro_volc_dual_stream_enabled")) o.put("pro_volc_dual_stream_enabled", sp.getBoolean("pro_volc_dual_stream_enabled", false))
      o.toString()
    } catch (t: Throwable) {
      if (BuildConfig.DEBUG) Log.d(TAG, "buildBackupJson merge(pro) failed: ${t.message}")
      base
    }
  }

  /**
   * 应用导入的 JSON 中的 Pro 额外键（若存在）。
   * 不依赖 Pro 源集类，直接写入统一的 asr_prefs。
   */
  fun applyProImport(context: android.content.Context, json: String) {
    if (!Edition.isPro) return
    try {
      val o = org.json.JSONObject(json)
      val sp = context.getSharedPreferences("asr_prefs", android.content.Context.MODE_PRIVATE)
      val edit = sp.edit()
      if (o.has("asr_context_text")) edit.putString("asr_context_text", o.optString("asr_context_text"))
      if (o.has("asr_hotwords")) edit.putString("asr_hotwords", o.optString("asr_hotwords"))
      if (o.has("pro_auto_backup_enabled")) edit.putBoolean("pro_auto_backup_enabled", o.optBoolean("pro_auto_backup_enabled"))
      if (o.has("pro_auto_backup_interval_hours")) edit.putInt("pro_auto_backup_interval_hours", o.optInt("pro_auto_backup_interval_hours", 24))
      if (o.has("pro_volc_dual_stream_enabled")) edit.putBoolean("pro_volc_dual_stream_enabled", o.optBoolean("pro_volc_dual_stream_enabled", false))
      edit.apply()

      // 触发 Pro 侧自动备份调度刷新（仅 Pro 变体会有接收者）
      try {
        val intent = android.content.Intent("com.brycewg.asrkb.pro.REFRESH_AUTO_BACKUP")
        context.sendBroadcast(intent)
      } catch (t: Throwable) {
        if (BuildConfig.DEBUG) Log.d(TAG, "broadcast pro refresh failed: ${t.message}")
      }
    } catch (t: Throwable) {
      if (BuildConfig.DEBUG) Log.d(TAG, "applyProImport failed: ${t.message}")
    }
  }

  /**
   * 输入设置页注入：
   * - main 布局提供 ViewStub：@id/pro_inject_stub_input
   * - pro 侧提供布局：res/layout/pro_input_settings_extra.xml（包含开关等）
   */
  fun injectIntoInputSettings(activity: Activity, root: View) {
    if (!Edition.isPro) return
    val res = activity.resources
    val pkg = activity.packageName
    try {
      val layoutId = res.getIdentifier("pro_input_settings_extra", "layout", pkg)
      if (layoutId == 0) return
      val stubId = res.getIdentifier("pro_inject_stub_input", "id", pkg)
      val stub = if (stubId != 0) root.findViewById<ViewStub?>(stubId) else null
      val inflater = LayoutInflater.from(activity)
      if (stub != null) {
        stub.layoutResource = layoutId
        stub.inflate()
      } else if (root is ViewGroup) {
        inflater.inflate(layoutId, root, true)
      }
    } catch (t: Throwable) {
      if (BuildConfig.DEBUG) Log.d(TAG, "skip pro ui inject(input): ${t.message}")
    }
  }

  /**
   * 键盘视图注入：
   * - main 布局提供 ViewStub：@id/pro_inject_stub_ime
   * - pro 侧提供布局：res/layout/pro_ime_injector.xml（包含自注册 Hook View）
   */
  fun injectIntoImeKeyboard(context: android.content.Context, root: View) {
    if (!Edition.isPro) return
    val res = context.resources
    val pkg = context.packageName
    try {
      val layoutId = res.getIdentifier("pro_ime_injector", "layout", pkg)
      if (layoutId == 0) return
      val stubId = res.getIdentifier("pro_inject_stub_ime", "id", pkg)
      val stub = if (stubId != 0) root.findViewById<ViewStub?>(stubId) else null
      val inflater = LayoutInflater.from(context)
      if (stub != null) {
        stub.layoutResource = layoutId
        stub.inflate()
      } else if (root is ViewGroup) {
        inflater.inflate(layoutId, root, true)
      }
    } catch (t: Throwable) {
      if (BuildConfig.DEBUG) Log.d(TAG, "skip pro ime inject: ${t.message}")
    }
  }
}
