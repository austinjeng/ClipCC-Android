package com.example.clipcc.ui.charts

import com.example.clipcc.engine.ScoreItem
import kotlin.math.abs
import kotlin.math.ceil

data class Bar(val label: String, val value: Float)

/** Pure preparation for the Canvas charts. Confidence bars share a [0,1] scale; cosine bars use a
 *  symmetric axis sized to their own magnitude (so a tiny cosine isn't drawn as if on a 0..1 scale). */
object ChartData {
    const val UNIT_MAX = 1f
    private const val STEP = 0.05f

    fun confidenceBars(items: List<ScoreItem>): List<Bar> =
        items.map { Bar(it.label, it.confidence.toFloat()) }

    fun cosineBars(items: List<ScoreItem>): List<Bar> =
        items.map { Bar(it.label, it.rawSimilarity.toFloat()) }

    /** Smallest STEP-aligned half-range that covers max|value|, with a non-zero floor of one STEP. */
    fun symmetricMax(values: List<Float>): Float {
        val maxAbs = values.maxOfOrNull { abs(it) } ?: 0f
        val steps = ceil(maxAbs / STEP).toInt().coerceAtLeast(1)
        return steps * STEP
    }
}
