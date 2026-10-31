/** Translation snapshot stored with ASR history, separate from recognition and AI output. */
package com.brycewg.asrkb.store

import kotlinx.serialization.Serializable

@Serializable
data class TranslationRecord(
    val inputText: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val vendorId: String,
    val elapsedMs: Long
)
