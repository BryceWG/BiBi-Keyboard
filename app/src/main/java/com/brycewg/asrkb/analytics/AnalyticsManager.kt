package com.brycewg.asrkb.analytics

import android.content.Context
import android.os.Build
import android.util.Log
import com.brycewg.asrkb.BuildConfig
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.AnalyticsStore
import com.brycewg.asrkb.store.Prefs
import java.time.LocalDate
import java.time.ZoneId
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 轻量匿名统计（PocketBase）。
 *
 * - 仅在用户同意且开启开关后生效
 * - 本地缓存事件，按用户随机的每日时间上传一次
 */
object AnalyticsManager {
  private const val TAG = "AnalyticsManager"
  private const val COLLECTION_DAILY_REPORT = "daily_reports"
  private const val COLLECTION_CONSENT = "device_consents"
  private const val DAY_MS = 24 * 60 * 60 * 1000L
  private const val RETRY_INTERVAL_MS = 3 * 60 * 1000L
  private const val MAX_RETRIES = 3

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
  }

  private var scheduleJob: Job? = null
  @Volatile private var uploading = false

  @Serializable
  private data class DailyReport(
    val userId: String,
    val appVersion: String,
    val language: String,
    val reportAt: Long,
    val randomMinuteOfDay: Int,
    val eventCount: Int,
    val audioMsTotal: Long,
    val appStartCount: Int,
    val asrEvents: List<AnalyticsStore.AsrEvent>
  )

  @Serializable
  private data class ConsentRecord(
    val userId: String,
    val enabled: Boolean,
    val channel: String,
    val firstSeen: Long,
    val sdkInt: Int,
    val deviceModel: String,
    val timestamp: Long,
    val appVersion: String,
    val language: String
  )

  @Serializable
  private data class PbListResponse<T>(
    val items: List<T> = emptyList()
  )

  @Serializable
  private data class ConsentItem(
    val id: String
  )

  fun init(context: Context) {
    val appContext = context.applicationContext
    val prefs = Prefs(appContext)
    if (!prefs.dataCollectionEnabled) return
    ensureUserId(prefs)
    ensureRandomMinute(prefs)
    recordAppStart(appContext)
    maybeUploadIfDue(appContext)
    scheduleLoop(appContext)
  }

  fun recordAppStart(context: Context) {
    val appContext = context.applicationContext
    val prefs = Prefs(appContext)
    if (!prefs.dataCollectionEnabled) return
    ensureUserId(prefs)
    ensureRandomMinute(prefs)

    scope.launch {
      try {
        AnalyticsStore(appContext).addAppStart(
          AnalyticsStore.AppStartEvent(timestamp = System.currentTimeMillis())
        )
      } catch (t: Throwable) {
        Log.w(TAG, "Failed to add app start event", t)
      }
    }
  }

  fun recordAsrEvent(
    context: Context,
    vendorId: String,
    audioMs: Long,
    procMs: Long,
    source: String,
    aiProcessed: Boolean,
    charCount: Int
  ) {
    if (charCount <= 0 && audioMs <= 0L) return
    val appContext = context.applicationContext
    val prefs = Prefs(appContext)
    if (!prefs.dataCollectionEnabled) return
    ensureUserId(prefs)
    ensureRandomMinute(prefs)

    scope.launch {
      try {
        AnalyticsStore(appContext).addAsrEvent(
          AnalyticsStore.AsrEvent(
            timestamp = System.currentTimeMillis(),
            vendorId = vendorId,
            audioMs = audioMs,
            procMs = procMs,
            source = source,
            aiProcessed = aiProcessed,
            charCount = charCount
          )
        )
      } catch (t: Throwable) {
        Log.w(TAG, "Failed to add ASR event", t)
      }
      maybeUploadIfDue(appContext)
    }
  }

  private fun ensureUserId(prefs: Prefs): String {
    val cur = prefs.analyticsUserId
    if (cur.isNotBlank()) return cur
    val id = UUID.randomUUID().toString()
    prefs.analyticsUserId = id
    return id
  }

  private fun ensureRandomMinute(prefs: Prefs): Int {
    val cur = prefs.analyticsReportMinuteOfDay
    if (cur in 0..1439) return cur
    val minute = Random.nextInt(0, 1440)
    prefs.analyticsReportMinuteOfDay = minute
    return minute
  }

  private fun maybeUploadIfDue(context: Context) {
    val prefs = Prefs(context)
    if (!prefs.dataCollectionEnabled) return

    val now = System.currentTimeMillis()
    val reportMinute = ensureRandomMinute(prefs)
    val cal = Calendar.getInstance()
    cal.timeInMillis = now
    val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    val todayEpochDay = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
    if (prefs.analyticsLastUploadEpochDay == todayEpochDay) return
    if (prefs.analyticsLastAttemptEpochDay == todayEpochDay) return
    if (minuteOfDay < reportMinute) return

    scope.launch { uploadOnce(context.applicationContext) }
  }

  private suspend fun uploadOnce(context: Context) {
    val prefs = Prefs(context)
    if (!prefs.dataCollectionEnabled) return
    val todayEpochDay = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
    if (uploading) return
    uploading = true
    prefs.analyticsLastAttemptEpochDay = todayEpochDay
    try {
      val baseUrl = try {
        context.getString(R.string.pocketbase_base_url).trim().trimEnd('/')
      } catch (t: Throwable) {
        Log.w(TAG, "Failed to read PocketBase base url", t)
        ""
      }
      if (baseUrl.isBlank()) {
        Log.w(TAG, "PocketBase base url empty, skip upload")
        return
      }

      val userId = ensureUserId(prefs)
      val windowDays = if (prefs.analyticsLastUploadEpochDay >= 0L) {
        (todayEpochDay - prefs.analyticsLastUploadEpochDay).toInt().coerceAtLeast(1)
      } else {
        1
      }
      val cutoff = System.currentTimeMillis() - (windowDays * DAY_MS)
      val store = AnalyticsStore(context)
      val asrEvents = try { store.listAsrEventsSince(cutoff) } catch (t: Throwable) {
        Log.w(TAG, "Failed to read ASR events", t)
        emptyList()
      }
      val appStarts = try { store.listAppStartsSince(cutoff) } catch (t: Throwable) {
        Log.w(TAG, "Failed to read app starts", t)
        emptyList()
      }

      val language = resolveLanguage(prefs)
      val report = DailyReport(
        userId = userId,
        appVersion = BuildConfig.VERSION_NAME,
        language = language,
        reportAt = System.currentTimeMillis(),
        randomMinuteOfDay = prefs.analyticsReportMinuteOfDay.coerceIn(0, 1439),
        eventCount = asrEvents.size,
        audioMsTotal = asrEvents.sumOf { it.audioMs.coerceAtLeast(0L) },
        appStartCount = appStarts.size,
        asrEvents = asrEvents
      )

      val bodyText = try { json.encodeToString(report) } catch (t: Throwable) {
        Log.w(TAG, "Failed to encode daily report", t)
        return
      }

      val reqBody = bodyText.toRequestBody("application/json; charset=utf-8".toMediaType())
      val url = "$baseUrl/api/collections/$COLLECTION_DAILY_REPORT/records"
      val req = Request.Builder().url(url).post(reqBody).build()

      val ok = postWithRetries(req)
      if (ok) {
        prefs.analyticsLastUploadEpochDay = todayEpochDay
        store.deleteAsrEventsBefore(cutoff)
        store.deleteAppStartsBefore(cutoff)
        Log.i(TAG, "Daily report uploaded, days=$windowDays, events=${asrEvents.size}, starts=${appStarts.size}")
      }
    } finally {
      uploading = false
    }
  }

  /**
   * 用户选择匿名统计开关时上报一次（即使未开启统计也会上报 enabled=false）。
   */
  fun sendConsentChoice(context: Context, enabled: Boolean) {
    val appContext = context.applicationContext
    val prefs = Prefs(appContext)
    val baseUrl = try {
      appContext.getString(R.string.pocketbase_base_url).trim().trimEnd('/')
    } catch (t: Throwable) {
      Log.w(TAG, "Failed to read PocketBase base url for consent", t)
      ""
    }
    if (baseUrl.isBlank()) {
      Log.w(TAG, "PocketBase base url empty, skip consent upload")
      return
    }

    val userId = ensureUserId(prefs)
    val language = resolveLanguage(prefs)
    val channel = resolveInstallChannel(appContext)
    val firstSeen = resolveFirstSeenEpochMs(prefs)
    val sdkInt = resolveSdkInt()
    val deviceModel = resolveDeviceModel()
    val record = ConsentRecord(
      userId = userId,
      enabled = enabled,
      channel = channel,
      firstSeen = firstSeen,
      sdkInt = sdkInt,
      deviceModel = deviceModel,
      timestamp = System.currentTimeMillis(),
      appVersion = BuildConfig.VERSION_NAME,
      language = language
    )
    val bodyText = try { json.encodeToString(record) } catch (t: Throwable) {
      Log.w(TAG, "Failed to encode consent record", t)
      return
    }
    val reqBody = bodyText.toRequestBody("application/json; charset=utf-8".toMediaType())
    scope.launch {
      val existingId = fetchLatestConsentId(baseUrl, userId)
      val req = if (existingId != null) {
        val url = "$baseUrl/api/collections/$COLLECTION_CONSENT/records/$existingId"
        Request.Builder().url(url).patch(reqBody).build()
      } else {
        val url = "$baseUrl/api/collections/$COLLECTION_CONSENT/records"
        Request.Builder().url(url).post(reqBody).build()
      }
      postWithRetries(req)
    }
  }

  private suspend fun fetchLatestConsentId(baseUrl: String, userId: String): String? {
    val filterRaw = "userId=\"$userId\""
    val filterEncoded = try {
      URLEncoder.encode(filterRaw, "UTF-8")
    } catch (t: Throwable) {
      Log.w(TAG, "Failed to encode consent filter", t)
      return null
    }
    val url =
      "$baseUrl/api/collections/$COLLECTION_CONSENT/records?filter=$filterEncoded&sort=-timestamp&perPage=1"
    val req = Request.Builder().url(url).get().build()
    return try {
      OkHttpClient.Builder().build().newCall(req).execute().use { res ->
        if (!res.isSuccessful) {
          Log.w(TAG, "Fetch consent list failed: code=${res.code}")
          return null
        }
        val body = res.body?.string().orEmpty()
        val parsed = json.decodeFromString<PbListResponse<ConsentItem>>(body)
        parsed.items.firstOrNull()?.id
      }
    } catch (t: Throwable) {
      Log.w(TAG, "Fetch consent list error", t)
      null
    }
  }

  private fun resolveLanguage(prefs: Prefs): String {
    val tag = prefs.appLanguageTag.trim()
    return if (tag.isNotBlank()) tag else Locale.getDefault().toLanguageTag()
  }

  private fun scheduleLoop(context: Context) {
    scheduleJob?.cancel()
    scheduleJob = scope.launch {
      while (isActive) {
        val prefs = Prefs(context)
        if (!prefs.dataCollectionEnabled) break
        val minute = ensureRandomMinute(prefs)
        val delayMs = computeDelayToNextReport(minute)
        delay(delayMs)
        maybeUploadIfDue(context)
      }
    }
  }

  private fun computeDelayToNextReport(reportMinute: Int): Long {
    val now = System.currentTimeMillis()
    val cal = Calendar.getInstance()
    cal.timeInMillis = now
    val next = cal.clone() as Calendar
    next.set(Calendar.HOUR_OF_DAY, reportMinute / 60)
    next.set(Calendar.MINUTE, reportMinute % 60)
    next.set(Calendar.SECOND, 0)
    next.set(Calendar.MILLISECOND, 0)
    if (next.timeInMillis <= now) {
      next.add(Calendar.DAY_OF_YEAR, 1)
    }
    return (next.timeInMillis - now).coerceAtLeast(60_000L)
  }

  private fun resolveInstallChannel(context: Context): String {
    if (BuildConfig.DEBUG) return "debug"
    return try {
      val pm = context.packageManager
      val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        pm.getInstallSourceInfo(context.packageName).installingPackageName
      } else {
        @Suppress("DEPRECATION")
        pm.getInstallerPackageName(context.packageName)
      }
      installer?.takeIf { it.isNotBlank() } ?: "unknown"
    } catch (t: Throwable) {
      Log.w(TAG, "Failed to resolve install channel", t)
      "unknown"
    }
  }

  private fun resolveFirstSeenEpochMs(prefs: Prefs): Long {
    val fud = prefs.firstUseDate.ifBlank {
      val today = LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
      prefs.firstUseDate = today
      today
    }
    return try {
      val date = LocalDate.parse(fud, java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
      date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch (t: Throwable) {
      Log.w(TAG, "Failed to parse firstUseDate '$fud' for firstSeen", t)
      System.currentTimeMillis()
    }
  }

  private fun resolveSdkInt(): Int = Build.VERSION.SDK_INT

  private fun resolveDeviceModel(): String {
    val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
    val model = Build.MODEL?.trim().orEmpty()
    val combined = listOf(manufacturer, model).filter { it.isNotBlank() }.joinToString(" ")
    return combined.ifBlank { "unknown" }
  }

  private suspend fun postWithRetries(req: Request): Boolean {
    try {
      val client = OkHttpClient.Builder().build()
      for (attempt in 1..MAX_RETRIES) {
        try {
          client.newCall(req).execute().use { res ->
            if (res.isSuccessful) {
              return true
            }
            Log.w(TAG, "POST failed (attempt=$attempt): code=${res.code}")
          }
        } catch (t: Throwable) {
          Log.w(TAG, "POST error (attempt=$attempt)", t)
        }
        if (attempt < MAX_RETRIES) {
          delay(RETRY_INTERVAL_MS)
        }
      }
    } catch (t: Throwable) {
      Log.w(TAG, "postWithRetries unexpected error", t)
    }
    return false
  }
}
