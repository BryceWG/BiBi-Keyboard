/**
 * 外部 ASR 设置入口的兼容跳转页。
 *
 * 归属模块：ui/settings/asr
 */
package com.brycewg.asrkb.ui.settings.asr

import android.content.Intent
import android.os.Bundle
import com.brycewg.asrkb.ui.BaseActivity
import com.brycewg.asrkb.ui.SettingsHostActivity
import com.brycewg.asrkb.ui.settings.compose.core.BibiSettingsRoute

class AsrRecognitionSettingsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openComposeAsrSettings()
        finish()
    }

    private fun openComposeAsrSettings() {
        startActivity(
            Intent(this, SettingsHostActivity::class.java)
                .putExtra(SettingsHostActivity.EXTRA_INITIAL_ROUTE, BibiSettingsRoute.Asr.id)
        )
    }
}
