/**
 * 零配置输入法语音接口（豆包输入法 / 微信输入法）设置区独立组件。
 * 单独成文件以降低与上游 AsrOnlineVendorConfigs.kt 的合并冲突面。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.asr.DoubaoImeIdentityStore
import com.brycewg.asrkb.asr.WeTypeIdentityStore
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode

@Composable
internal fun ZeroConfigStreamVendorConfig(
    uiMode: BibiUiMode,
    vendor: AsrVendor,
    primaryIndexOffset: Int,
    primaryGroupCount: Int?
) {
    val context = LocalContext.current
    val itemCount = primaryGroupCount ?: 1
    val (actionId, actionTitleRes, descriptionRes, toastMessageRes) = when (vendor) {
        AsrVendor.DoubaoIme -> Quadruple(
            "doubao_ime_reset_identity",
            R.string.btn_doubao_ime_reset_identity,
            R.string.doubao_ime_description,
            R.string.toast_doubao_ime_identity_reset
        )
        AsrVendor.WeType -> Quadruple(
            "wetype_reset_identity",
            R.string.btn_wetype_reset_identity,
            R.string.wetype_description,
            R.string.toast_wetype_identity_reset
        )
        else -> return
    }
    AsrActionPreference(
        id = actionId,
        titleRes = actionTitleRes,
        index = primaryIndexOffset,
        count = itemCount,
        onClick = {
            if (vendor == AsrVendor.DoubaoIme) {
                DoubaoImeIdentityStore.resetIdentity(context)
            } else {
                WeTypeIdentityStore.resetIdentity(context)
            }
            Toast.makeText(context, context.getString(toastMessageRes), Toast.LENGTH_SHORT).show()
        }
    )
    AsrBodyText(uiMode = uiMode, textRes = descriptionRes)
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)
