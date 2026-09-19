package com.brycewg.asrkb.ui.settings.compose.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderSectionsTest {
    @Test
    fun advancedParamsVisibilityDependsOnAvailableControls() {
        assertFalse(llmAdvancedParamsVisible(hasTemperature = false, hasReasoning = false))
        assertTrue(llmAdvancedParamsVisible(hasTemperature = true, hasReasoning = false))
        assertTrue(llmAdvancedParamsVisible(hasTemperature = false, hasReasoning = true))
        assertTrue(llmAdvancedParamsVisible(hasTemperature = true, hasReasoning = true))
    }

    @Test
    fun primaryItemCountsReplaceAdvancedControlsWithOneEntry() {
        assertEquals(
            2,
            sfFreeLlmPrimaryItemCount(
                presetModels = emptyList(),
                staticModels = emptyList(),
                sfUseFreeService = true,
                sfModel = "",
                customModelInputVisible = false
            )
        )
        assertEquals(
            4,
            sfFreeLlmPrimaryItemCount(
                presetModels = emptyList(),
                staticModels = emptyList(),
                sfUseFreeService = false,
                sfModel = "",
                customModelInputVisible = false
            )
        )
        assertEquals(6, customLlmPrimaryItemCount(customModelInputVisible = false))
        assertEquals(7, customLlmPrimaryItemCount(customModelInputVisible = true))
    }
}
