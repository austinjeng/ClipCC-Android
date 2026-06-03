package com.example.clipcc.ui.charts

import com.example.clipcc.engine.ScoreItem
import org.junit.Assert.assertEquals
import org.junit.Test

class ChartDataTest {
    private val items = listOf(
        ScoreItem("a", confidence = 0.9, rawSimilarity = 0.12),
        ScoreItem("b", confidence = 0.2, rawSimilarity = -0.05),
    )

    @Test fun confidence_bars_use_unit_scale() {
        val bars = ChartData.confidenceBars(items)
        assertEquals(listOf("a", "b"), bars.map { it.label })
        assertEquals(listOf(0.9f, 0.2f), bars.map { it.value })
        assertEquals(1f, ChartData.UNIT_MAX, 0f)
    }

    @Test fun cosine_axis_is_symmetric_to_max_abs() {
        assertEquals(0.15f, ChartData.symmetricMax(items.map { it.rawSimilarity.toFloat() }), 1e-6f)
    }

    @Test fun cosine_axis_floor_is_nonzero_for_tiny_values() {
        assertEquals(0.05f, ChartData.symmetricMax(listOf(0.001f, -0.002f)), 1e-6f)
    }
}
