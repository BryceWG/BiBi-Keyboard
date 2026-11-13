/**
 * Compose 关于页的数据构建与格式化。
 *
 * 归属模块：ui/settings/compose/screens
 */
package com.brycewg.asrkb.ui.settings.compose.screens

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.brycewg.asrkb.LocaleHelper
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.store.ApiLogStore
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.UsageStats
import com.brycewg.asrkb.store.debug.DebugLogManager
import com.brycewg.asrkb.ui.AsrVendorUi
import java.text.DateFormat
import java.text.NumberFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

internal data class AboutInfo(
    val appName: String,
    val version: String,
    val packageName: String
)

internal data class AboutUsageInfo(
    val daysWithYou: Long,
    val totalAudioFormatted: String,
    val totalCharsFormatted: String,
    val totalSessionsFormatted: String,
    val avgAudioFormatted: String,
    val avgCharsFormatted: String,
    val avgSpeedFormatted: String,
    val dailyAvg7dFormatted: String,
    val weeklyAvg4wFormatted: String,
    val vendorItems: List<AboutProgressItem>,
    val failureItems: List<AboutProgressItem>,
    val dailyItems: List<AboutProgressItem>
)

internal data class AboutProgressItem(
    val title: String,
    val value: String,
    val ratio: Double,
    val isError: Boolean = false,
    val valueBelowTitle: Boolean = false
)

internal fun buildAboutInfo(context: Context): AboutInfo {
    val pInfo = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
    } catch (_: Exception) {
        null
    }
    val versionName = pInfo?.versionName ?: ""
    val versionCodeLong = if (pInfo == null) {
        0L
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        pInfo.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        pInfo.versionCode.toLong()
    }
    return AboutInfo(
        appName = context.getString(R.string.about_app_name, context.getString(R.string.app_name)),
        version = context.getString(R.string.about_version, "$versionName ($versionCodeLong)"),
        packageName = context.getString(R.string.about_package, context.packageName)
    )
}

internal fun buildUsageInfo(context: Context, prefs: Prefs): AboutUsageInfo {
    val stats = prefs.getUsageStats()
    val sessions = stats.totalSessions.coerceAtLeast(0)
    val totalChars = stats.totalChars.coerceAtLeast(0)
    val totalAudioMs = stats.totalAudioMs.coerceAtLeast(0)
    val hasSessionAverages = sessions > 0
    val avgAudioFormatted = if (hasSessionAverages) {
        context.formatDurationMs(totalAudioMs / sessions)
    } else {
        context.getString(R.string.about_empty_stats_placeholder)
    }
    val avgCharsFormatted = if (hasSessionAverages) {
        formatInt(context, totalChars / sessions)
    } else {
        context.getString(R.string.about_empty_stats_placeholder)
    }
    val avgSpeedFormatted = if (hasSessionAverages) {
        val avgSpeed = if (totalAudioMs > 0) {
            totalChars * 60_000.0 / totalAudioMs.toDouble()
        } else {
            0.0
        }
        context.getString(
            R.string.about_stats_avg_speed_value,
            String.format(LocaleHelper.locale(context), "%.1f", avgSpeed)
        )
    } else {
        context.getString(R.string.about_empty_stats_placeholder)
    }

    val daily7 = stats.dailySum(7)
    val daily28 = stats.dailySum(28)
    return AboutUsageInfo(
        daysWithYou = prefs.getDaysSinceFirstUse(),
        totalAudioFormatted = context.formatDurationMs(totalAudioMs),
        totalCharsFormatted = formatInt(context, totalChars),
        totalSessionsFormatted = formatInt(context, sessions),
        avgAudioFormatted = avgAudioFormatted,
        avgCharsFormatted = avgCharsFormatted,
        avgSpeedFormatted = avgSpeedFormatted,
        dailyAvg7dFormatted = context.getString(
            R.string.about_stats_chars_with_audio,
            formatInt(context, daily7.second / 7),
            context.formatDurationMs(daily7.first / 7)
        ),
        weeklyAvg4wFormatted = context.getString(
            R.string.about_stats_chars_with_audio,
            formatInt(context, daily28.second / 4),
            context.formatDurationMs(daily28.first / 4)
        ),
        vendorItems = buildVendorProgressItems(context, stats),
        failureItems = buildFailureProgressItems(context),
        dailyItems = buildDailyProgressItems(context, stats, 7)
    )
}

internal fun buildLatestExitInfo(context: Context): String? {
    val info = DebugLogManager.getLatestExitInfo(context) ?: return null
    val formattedTime = try {
        DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT,
            LocaleHelper.locale(context)
        ).format(Date(info.timestamp))
    } catch (_: Throwable) {
        info.timestamp.toString()
    }
    val firstLine = context.getString(
        R.string.about_debug_last_exit_prefix,
        "${info.reasonLabel} · $formattedTime"
    )
    val detail = info.description?.takeIf { it.isNotBlank() }
    return if (detail != null) "$firstLine\n$detail" else firstLine
}

private fun buildVendorProgressItems(context: Context, stats: UsageStats): List<AboutProgressItem> {
    val vendorPairs = stats.perVendor.map { it.key to it.value }.sortedByDescending { it.second.chars }
    val maxChars = vendorPairs.maxOfOrNull { it.second.chars } ?: 0L
    if (vendorPairs.isEmpty() || maxChars <= 0) return emptyList()
    return vendorPairs.map { (id, agg) ->
        val name = AsrVendorUi.name(context, AsrVendor.fromId(id))
        val value = buildString {
            append(formatInt(context, agg.chars)).append(" ").append(context.getString(R.string.unit_chars))
            append(" · ")
            append(context.formatDurationMs(agg.audioMs))
            if (agg.procMs > 0) {
                append(" · ")
                append(context.getString(R.string.about_proc_prefix)).append(" ")
                append(context.formatDurationMs(agg.procMs))
            }
        }
        AboutProgressItem(
            title = name,
            value = value,
            ratio = agg.chars.toDouble() / maxChars.toDouble(),
            valueBelowTitle = true
        )
    }
}

private fun buildFailureProgressItems(context: Context): List<AboutProgressItem> {
    val cutoffMs = LocalDate.now()
        .minusDays(29)
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
    val records = try {
        ApiLogStore.listAll()
            .asSequence()
            .filter { it.category.equals("ASR", ignoreCase = true) }
            .filter { it.timestamp >= cutoffMs }
            .mapNotNull { record ->
                resolveOnlineAsrVendor(record.vendor)?.let { vendor -> vendor to record }
            }
            .groupBy({ it.first }, { it.second })
    } catch (_: Throwable) {
        emptyMap()
    }
    return records.map { (vendor, list) ->
        val total = list.size
        val failed = list.count { !it.success }
        val rate = if (total > 0) failed.toDouble() / total.toDouble() else 0.0
        val value = buildString {
            append(String.format(LocaleHelper.locale(context), "%.1f%%", rate * 100.0))
            append(" (")
            append(formatInt(context, failed.toLong()))
            append("/")
            append(formatInt(context, total.toLong()))
            append(")")
        }
        AboutProgressItem(
            title = AsrVendorUi.name(context, vendor),
            value = value,
            ratio = rate,
            isError = true
        )
    }.sortedWith(
        compareByDescending<AboutProgressItem> { it.ratio }
            .thenByDescending { it.title }
    )
}

private fun buildDailyProgressItems(
    context: Context,
    stats: UsageStats,
    days: Int
): List<AboutProgressItem> {
    val fmt = DateTimeFormatter.BASIC_ISO_DATE
    val labelFmt = DateTimeFormatter.ofPattern("MM-dd", LocaleHelper.locale(context))
    val values = ArrayList<Pair<String, Long>>()
    var d = LocalDate.now()
    repeat(days) {
        val key = d.format(fmt)
        values.add(d.format(labelFmt) to (stats.daily[key]?.chars ?: 0L))
        d = d.minusDays(1)
    }
    val max = values.maxOfOrNull { it.second } ?: 0L
    if (max <= 0) return emptyList()
    return values.map { (label, value) ->
        AboutProgressItem(
            title = label,
            value = "${formatInt(context, value)} ${context.getString(R.string.unit_chars)}",
            ratio = value.toDouble() / max.toDouble()
        )
    }
}

private fun resolveOnlineAsrVendor(rawId: String): AsrVendor? = when (rawId.trim().lowercase()) {
    "volc", "volcengine" -> AsrVendor.Volc
    "siliconflow" -> AsrVendor.SiliconFlow
    "elevenlabs" -> AsrVendor.ElevenLabs
    "openai" -> AsrVendor.OpenAI
    "openrouter", "open_router" -> AsrVendor.OpenRouter
    "dashscope" -> AsrVendor.DashScope
    "gemini" -> AsrVendor.Gemini
    "soniox" -> AsrVendor.Soniox
    "stepaudio", "step_audio", "stepfun" -> AsrVendor.StepAudio
    "zhipu" -> AsrVendor.Zhipu
    "cohere" -> AsrVendor.Cohere
    "deepseek" -> AsrVendor.DeepSeek
    else -> null
}

private fun UsageStats.dailySum(days: Int): Pair<Long, Long> {
    val fmt = DateTimeFormatter.BASIC_ISO_DATE
    var sumAudio = 0L
    var sumChars = 0L
    var d = LocalDate.now()
    repeat(days) {
        val agg = daily[d.format(fmt)]
        if (agg != null) {
            sumAudio += agg.audioMs
            sumChars += agg.chars
        }
        d = d.minusDays(1)
    }
    return sumAudio to sumChars
}

private fun Context.formatDurationMs(ms: Long): String {
    if (ms <= 0) return getString(R.string.unit_0_min)
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    val hr = min / 60
    return when {
        hr > 0 -> getString(R.string.fmt_h_m, hr, (min % 60))
        min > 0 -> getString(R.string.fmt_m_s, min, sec)
        else -> getString(R.string.fmt_s, sec)
    }
}

private fun formatInt(context: Context, v: Long): String = NumberFormat.getIntegerInstance(LocaleHelper.locale(context)).format(v)
