/**
 * LLM 功能模型引用的通用 JSON 读写。
 *
 * 归属模块：store
 */
package com.brycewg.asrkb.store

import android.util.Log
import kotlinx.serialization.json.Json

internal object LlmFeatureModelRefStore {
    private const val TAG = "LlmFeatureModelRefStore"

    fun read(raw: String, json: Json): LlmFeatureModelRef {
        if (raw.isBlank()) return LlmFeatureModelRef.FollowDefault
        return decode(raw, json) ?: LlmFeatureModelRef.FollowDefault
    }

    fun encode(ref: LlmFeatureModelRef, json: Json): String = try {
        json.encodeToString(LlmFeatureModelRef.serializer(), ref)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to serialize LLM feature model ref", e)
        ""
    }

    fun validatedRawOrNull(raw: String?, json: Json): String? {
        if (raw.isNullOrBlank()) return null
        return decode(raw, json)?.let { encode(it, json) }?.takeIf { it.isNotBlank() }
    }

    private fun decode(raw: String, json: Json): LlmFeatureModelRef? = try {
        json.decodeFromString(LlmFeatureModelRef.serializer(), raw)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to parse LLM feature model ref", e)
        null
    }
}
