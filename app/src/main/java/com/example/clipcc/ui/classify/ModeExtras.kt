package com.example.clipcc.ui.classify

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.clipcc.ui.charts.TimelineChart
import com.example.clipcc.ui.charts.TimelineBand
import com.example.clipcc.ui.charts.TimelineSeries

@Composable
fun ModeExtras(r: RunResult) {
    val agg = r.result
    when {
        agg.temporal != null -> TemporalExtras(r)
        agg.contrast != null -> ContrastExtras(agg.contrast!!)
        else -> {}
    }
}

@Composable
private fun TemporalExtras(r: RunResult) {
    val t = r.result.temporal!!
    val shown = ScoreView.topActiveLabels(t.labelSummaries, ScoreView.TIMELINE_SERIES)
    if (shown.isEmpty()) {
        Text("No segments met threshold ${ScoreView.pct(t.effectiveThreshold)}",
            style = MaterialTheme.typography.bodyMedium)
        return
    }
    val palette = listOf(Color(0xFF1565C0), Color(0xFFEF6C00), Color(0xFF2E7D32),
        Color(0xFFC62828), Color(0xFF6A1B9A), Color(0xFF00838F))
    val colorOf = shown.withIndex().associate { (i, lbl) -> lbl to palette[i % palette.size] }
    val series = shown.map { lbl ->
        TimelineSeries(lbl, colorOf.getValue(lbl), t.timeline.map { fr -> (fr.scores[lbl] ?: 0.0).toFloat() })
    }
    val total = (t.timeline.lastOrNull()?.timestamp ?: 1.0).coerceAtLeast(1e-6)
    val bands = t.segments.filter { it.label in colorOf }.map { seg ->
        TimelineBand(colorOf.getValue(seg.label),
            (seg.startTime / total).toFloat(), (seg.endTime / total).toFloat())
    }

    Text("Timeline", style = MaterialTheme.typography.labelLarge)
    Text("threshold ${ScoreView.pct(t.effectiveThreshold)}${if (t.thresholdWasDefaulted) " (default)" else ""}",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    TimelineChart(series, threshold = t.effectiveThreshold.toFloat(), bands = bands)

    Text("Segments", style = MaterialTheme.typography.labelLarge)
    t.segments.sortedByDescending { it.peakConfidence }.take(ScoreView.SEGMENT_ROWS).forEach { seg ->
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(seg.label, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text("${ScoreView.secs(seg.startTime)}–${ScoreView.secs(seg.endTime)} · ${ScoreView.secs(seg.duration)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { MeterBar(seg.peakConfidence.toFloat()) }
                Text(ScoreView.pct(seg.peakConfidence), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (t.segments.size > ScoreView.SEGMENT_ROWS) {
        Text("… ${t.segments.size - ScoreView.SEGMENT_ROWS} more segments",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }

    Text("Label summaries", style = MaterialTheme.typography.labelLarge)
    ScoreView.topSummaries(t.labelSummaries, ScoreView.SUMMARY_ROWS).forEach { ls ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(ls.label, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("${ls.segmentCount} seg · active ${ScoreView.secs(ls.totalActiveDuration)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.width(80.dp)) { MeterBar(ls.durationWeightedConfidence.toFloat()) }
            Text(ScoreView.pct(ls.durationWeightedConfidence), style = MaterialTheme.typography.bodySmall)
        }
    }
    val activeCount = t.labelSummaries.count { it.segmentCount > 0 }
    if (activeCount > ScoreView.SUMMARY_ROWS) {
        Text("… ${activeCount - ScoreView.SUMMARY_ROWS} more labels",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun ContrastExtras(c: com.example.clipcc.engine.ContrastResult) {
    val color = when (c.verdict) {
        "positive" -> Color(0xFF2E7D32); "negative" -> Color(0xFFC62828); else -> Color(0xFF757575)
    }
    Surface(color = color) {
        Text("  Verdict: ${c.verdict.uppercase()}  (margin ${"%.3f".format(c.difference)})  ",
            modifier = Modifier.padding(8.dp), color = Color.White)
    }
    Text("Positive group mean ${"%.3f".format(c.positive.meanGroupScore)} · " +
        "Negative group mean ${"%.3f".format(c.negative.meanGroupScore)}")
    c.dominantLabel?.let { Text("Dominant: $it") }
    Text("threshold ${"%.3f".format(c.threshold)} (${c.thresholdSource}), ${c.calibrationStatus}",
        style = MaterialTheme.typography.bodySmall)
}
