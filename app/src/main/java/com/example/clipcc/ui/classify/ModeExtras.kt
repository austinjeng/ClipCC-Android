package com.example.clipcc.ui.classify

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.example.clipcc.engine.ScoreItem
import com.example.clipcc.ui.charts.TimelineChart
import com.example.clipcc.ui.charts.TimelineBand
import com.example.clipcc.ui.charts.TimelineSeries

@Composable
fun ModeExtras(r: RunResult) {
    val agg = r.result
    when {
        agg.temporal != null -> TemporalExtras(r)
        agg.contrast != null -> ContrastExtras(agg.contrast!!)
        agg.scores.any { it.peakFrameIndex != null } -> MaxExtras(r)
        else -> {}
    }
}

@Composable
private fun MaxExtras(r: RunResult) {
    Text("Peak frames", style = MaterialTheme.typography.labelLarge)
    r.result.scores.forEach { s: ScoreItem ->
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            s.peakFrameIndex?.let { idx ->
                r.thumbnails[idx]?.let { Image(it.asImageBitmap(), contentDescription = "${s.label} peak frame",
                    modifier = Modifier.size(72.dp)) }
            }
            Text("${s.label} @ ${"%.1f".format(s.approxTimestampSeconds ?: 0.0)}s")
        }
    }
}

@Composable
private fun TemporalExtras(r: RunResult) {
    val t = r.result.temporal!!
    val labels = r.result.scores.map { it.label }
    val palette = listOf(Color(0xFF1565C0), Color(0xFFEF6C00), Color(0xFF2E7D32), Color(0xFFC62828))
    val series = labels.mapIndexed { i, lbl ->
        TimelineSeries(lbl, palette[i % palette.size],
            t.timeline.map { fr -> (fr.scores[lbl] ?: 0.0).toFloat() })
    }
    val bands = t.segments.map { seg ->
        val li = labels.indexOf(seg.label).coerceAtLeast(0)
        val total = (t.timeline.lastOrNull()?.timestamp ?: 1.0).coerceAtLeast(1e-6)
        TimelineBand(palette[li % palette.size], (seg.startTime / total).toFloat(), (seg.endTime / total).toFloat())
    }
    Text("Timeline", style = MaterialTheme.typography.labelLarge)
    TimelineChart(series, threshold = t.effectiveThreshold.toFloat(), bands = bands)
    Text("Segments", style = MaterialTheme.typography.labelLarge)
    t.segments.forEach { seg ->
        Text("${seg.label}: ${"%.1f".format(seg.startTime)}–${"%.1f".format(seg.endTime)}s " +
            "(${"%.1f".format(seg.duration)}s, peak ${"%.3f".format(seg.peakConfidence)})",
            style = MaterialTheme.typography.bodySmall)
    }
    Text("Label summaries", style = MaterialTheme.typography.labelLarge)
    t.labelSummaries.forEach { ls ->
        Text("${ls.label}: ${ls.segmentCount} seg, active ${"%.1f".format(ls.totalActiveDuration)}s, " +
            "DWC ${"%.3f".format(ls.durationWeightedConfidence)}", style = MaterialTheme.typography.bodySmall)
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
