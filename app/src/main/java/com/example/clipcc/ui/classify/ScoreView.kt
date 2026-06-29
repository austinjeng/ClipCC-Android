package com.example.clipcc.ui.classify

import com.example.clipcc.engine.LabelSummary
import com.example.clipcc.engine.ScoreItem
import java.util.Locale

/** Pure, Android-free formatting / sorting / cap helpers for the Classify results UI. */
object ScoreView {
    const val COLLAPSED = 5
    const val MAX_ROWS = 50
    const val TIMELINE_SERIES = 6
    const val SUMMARY_ROWS = 20
    const val SEGMENT_ROWS = 50

    /** Confidence desc; Kotlin's sort is stable so ties keep input order. */
    fun ranked(scores: List<ScoreItem>): List<ScoreItem> = scores.sortedByDescending { it.confidence }

    fun pct(v: Double): String = String.format(Locale.US, "%.1f%%", v * 100)
    fun signedCos(v: Double): String = String.format(Locale.US, "%+.3f", v)
    fun secs(v: Double): String = String.format(Locale.US, "%.1f s", v)

    fun topSummaries(list: List<LabelSummary>, k: Int): List<LabelSummary> =
        list.filter { it.segmentCount > 0 }.sortedByDescending { it.durationWeightedConfidence }.take(k)

    fun topActiveLabels(summaries: List<LabelSummary>, k: Int): List<String> =
        topSummaries(summaries, k).map { it.label }

    fun visibleModes(unlocked: Boolean, current: AggMode): List<AggMode> =
        AggMode.entries.filter { it != AggMode.CONTRAST || unlocked || current == AggMode.CONTRAST }
}
