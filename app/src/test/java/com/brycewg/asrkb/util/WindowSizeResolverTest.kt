package com.brycewg.asrkb.util

import org.junit.Assert.assertEquals
import org.junit.Test

class WindowSizeResolverTest {
    @Test
    fun subtractsInsetsFromEveryEdge() {
        assertEquals(
            WindowSizePx(width = 2240, height = 1540),
            calculateUsableWindowSize(
                boundsWidth = 2364,
                boundsHeight = 1672,
                insetLeft = 48,
                insetTop = 60,
                insetRight = 76,
                insetBottom = 72
            )
        )
    }

    @Test
    fun clampsDimensionsWhenInsetsExceedBounds() {
        assertEquals(
            WindowSizePx(width = 0, height = 0),
            calculateUsableWindowSize(
                boundsWidth = 100,
                boundsHeight = 80,
                insetLeft = 60,
                insetTop = 50,
                insetRight = 60,
                insetBottom = 50
            )
        )
    }
}
