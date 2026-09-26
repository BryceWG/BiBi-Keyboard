package com.brycewg.asrkb.ui.settings.compose.screens

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AsrOnlineSettingsFieldsTest {
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("asr_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        prefs = Prefs(context)
    }

    @Test
    fun constructorNormalizesAndPersistsCohereLanguage() {
        prefs.cohereAsrModel = Prefs.COHERE_ARABIC_ASR_MODEL
        prefs.cohereAsrLanguage = "zh"

        val fields = AsrOnlineSettingsFields(prefs)

        assertEquals("ar", fields.cohereLanguage)
        assertEquals("ar", prefs.cohereAsrLanguage)
    }

    @Test
    fun refreshNormalizesAndPersistsCohereLanguage() {
        val fields = AsrOnlineSettingsFields(prefs)
        prefs.cohereAsrModel = Prefs.COHERE_ARABIC_ASR_MODEL
        prefs.cohereAsrLanguage = "zh"

        fields.refreshFromPrefs()

        assertEquals("ar", fields.cohereLanguage)
        assertEquals("ar", prefs.cohereAsrLanguage)
    }

    @Test
    fun tokenPlanRegionSurvivesSettingsConstructionAndRefresh() {
        for (region in listOf("token-plan-cn", "token-plan-intl")) {
            prefs.dashRegion = region

            val fields = AsrOnlineSettingsFields(prefs)

            assertEquals(region, fields.dashRegion)
            fields.dashRegion = "cn"
            fields.refreshFromPrefs()
            assertEquals(region, fields.dashRegion)
            assertEquals(region, prefs.dashRegion)
        }
    }

    @Test
    fun tokenPlanRegionIsSelectableAndHasItsOwnLabel() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val options = dashRegionOptions(context)
        assertEquals(listOf("cn", "intl", "token-plan-cn", "token-plan-intl"), options.map { it.value })
        assertEquals(
            context.getString(R.string.dash_region_token_plan_cn),
            dashRegionLabel(context, "TOKEN-PLAN-CN")
        )
        val option = options.single { it.value == "token-plan-intl" }

        assertEquals(context.getString(R.string.dash_region_token_plan_intl), option.label)
        assertEquals(option.label, dashRegionLabel(context, "TOKEN-PLAN-INTL"))
    }

    @Test
    fun selectingCustomModelKeepsCurrentModelUntilDraftIsNonBlank() {
        prefs.cohereApiKey = "test-key"
        prefs.cohereAsrModel = Prefs.DEFAULT_COHERE_ASR_MODEL
        val fields = AsrOnlineSettingsFields(prefs)

        fields.showCohereCustomModelInput()
        fields.updateCohereCustomModelDraft("")

        assertEquals(Prefs.DEFAULT_COHERE_ASR_MODEL, fields.cohereModel)
        assertEquals(Prefs.DEFAULT_COHERE_ASR_MODEL, prefs.cohereAsrModel)
        assertTrue(prefs.hasCohereKeys())

        fields.updateCohereCustomModelDraft("custom-transcribe-model")

        assertEquals("custom-transcribe-model", fields.cohereModel)
        assertEquals("custom-transcribe-model", prefs.cohereAsrModel)
    }
}
