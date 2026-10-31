/** Translation module: fixed Foxlet language directions and automatic model routing. */
package com.brycewg.asrkb.translation

import android.content.Context
import java.util.Locale
import org.json.JSONObject

internal class TranslationModelCatalog(context: Context) {
    data class Model(val source: String, val target: String, val bytes: Long) {
        val id: String get() = "$source-$target"
    }

    val models: List<Model> = context.assets.open("translation/foxlet-models.json").bufferedReader().use {
        val entries = JSONObject(it.readText()).getJSONArray("models")
        List(entries.length()) { index ->
            val entry = entries.getJSONObject(index)
            val files = entry.getJSONArray("files")
            Model(
                entry.getString("from"),
                entry.getString("to"),
                (0 until files.length()).sumOf { file -> files.getJSONObject(file).getLong("size") }
            )
        }
    }

    val languages: List<String> = models.flatMap { listOf(it.source, it.target) }.distinct()
    val sources: List<String> = models.map { it.source }.distinct()

    fun route(source: String, target: String): List<Model> {
        if (source == target) return emptyList()
        models.find { it.source == source && it.target == target }?.let { return listOf(it) }
        val first = models.find { it.source == source && it.target == "en" } ?: return emptyList()
        val second = models.find { it.source == "en" && it.target == target } ?: return emptyList()
        return listOf(first, second)
    }

    fun targets(source: String): List<String> = languages.filter { route(source, it).isNotEmpty() }

    fun languageName(tag: String, locale: Locale): String = Locale.forLanguageTag(tag).getDisplayName(locale)
}
