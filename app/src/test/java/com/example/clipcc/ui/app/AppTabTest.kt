package com.example.clipcc.ui.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AppTabTest {
    @Test fun titlesInDisplayOrder() {
        assertEquals(listOf("Classify", "Benchmark", "Pixel 9a"), AppTab.entries.map { it.title })
    }

    @Test fun pixel9aIsThirdTab() {
        assertEquals(2, AppTab.PIXEL9A.ordinal)
    }
}
