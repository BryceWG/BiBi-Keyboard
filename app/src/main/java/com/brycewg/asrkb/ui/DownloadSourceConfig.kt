package com.brycewg.asrkb.ui

import android.content.Context
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs

internal data class DownloadSourceOption(
    val label: String,
    val url: String,
    val fallbackUrls: List<String> = listOf(url)
)

internal object DownloadSourceConfig {
    private const val GITHUB_PREFIX = "https://github.com/"

    private data class Mirror(val labelRes: Int, val prefix: String)

    private val mirrors = listOf(
        Mirror(R.string.download_source_mirror_1, "https://ghproxy.net/"),
        Mirror(R.string.download_source_mirror_2, "https://cdn.akaere.online/"),
        Mirror(R.string.download_source_mirror_3, "https://fastgit.cc/")
    )

    fun buildOptions(
        context: Context,
        officialUrl: String,
        officialLabelRes: Int = R.string.download_source_github_official
    ): List<DownloadSourceOption> = buildOptions(context, listOf(officialUrl), officialLabelRes)

    fun buildOptions(
        context: Context,
        officialUrls: List<String>,
        officialLabelRes: Int = R.string.download_source_github_official
    ): List<DownloadSourceOption> = buildList {
        add(DownloadSourceOption(context.getString(officialLabelRes), officialUrls.firstOrNull().orEmpty(), officialUrls))
        if (officialUrls.none { it.startsWith(GITHUB_PREFIX) }) return@buildList

        fun addMirror(label: String, prefix: String) {
            val urls = officialUrls.map { url -> if (url.startsWith(GITHUB_PREFIX)) prefix + url else url }
            add(DownloadSourceOption(label, urls.first(), urls))
        }

        mirrors.forEach { (labelRes, prefix) -> addMirror(context.getString(labelRes), prefix) }
        Prefs(context).customDownloadMirrors.forEachIndexed { index, prefix ->
            if (!isBuiltInMirror(prefix)) {
                addMirror(context.getString(R.string.download_source_custom_mirror, index + 1), prefix)
            }
        }
    }

    fun isBuiltInMirror(prefix: String): Boolean = mirrors.any { it.prefix == prefix }
}
