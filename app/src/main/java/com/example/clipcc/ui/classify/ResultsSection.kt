package com.example.clipcc.ui.classify

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.clipcc.engine.ScoreItem

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResultsSection(success: RunState.Success) {
    val r = success.result
    val agg = r.result
    val rows = remember(success) { ScoreView.ranked(agg.scores) }
    var showAll by remember(success) { mutableStateOf(false) }
    var expanded by remember(success) { mutableStateOf(emptySet<String>()) }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ElevatedCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("BEST MATCH", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Text(agg.bestMatch.label, style = MaterialTheme.typography.headlineSmall)
                Text(ScoreView.pct(agg.bestMatch.confidence), style = MaterialTheme.typography.titleLarge)
                MeterBar(agg.bestMatch.confidence.toFloat())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(r.meta.modelId, r.meta.requestedBackend.label, "${r.meta.frameCount}f",
                        "${r.meta.elapsedMs} ms", r.meta.scoreSemantics).forEach { chip ->
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp)) {
                            Text(chip, style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                    }
                }
                Text("live node coverage not profiled — see Benchmark",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("All labels (${rows.size})", style = MaterialTheme.typography.labelLarge)
            Text("by confidence", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline)
        }
        rows.take(if (showAll) ScoreView.MAX_ROWS else ScoreView.COLLAPSED).forEachIndexed { i, item ->
            ScoreRow(i + 1, item, r.thumbnails, item.label in expanded) {
                expanded = if (item.label in expanded) expanded - item.label else expanded + item.label
            }
        }
        if (rows.size > ScoreView.COLLAPSED) {
            TextButton(onClick = { showAll = !showAll }) {
                Text(if (showAll) "⌃ show less"
                     else "⌄ show ${minOf(rows.size, ScoreView.MAX_ROWS) - ScoreView.COLLAPSED} more")
            }
        }
        if (showAll && rows.size > ScoreView.MAX_ROWS) {
            Text("Top ${ScoreView.MAX_ROWS} of ${rows.size} — import fewer labels for full detail",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }

        ModeExtras(r)
    }
}

@Composable
private fun ScoreRow(rank: Int, item: ScoreItem, thumbnails: Map<Int, Bitmap>,
                     expanded: Boolean, onToggle: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { onToggle() },
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$rank", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline, modifier = Modifier.width(28.dp))
            Text(item.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f))
            Text(ScoreView.pct(item.confidence), style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium)
        }
        MeterBar(item.confidence.toFloat())
        if (expanded) {
            Text("cosine ${ScoreView.signedCos(item.rawSimilarity)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            item.peakFrameIndex?.let { idx ->
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    thumbnails[idx]?.let {
                        Image(it.asImageBitmap(), contentDescription = "${item.label} peak frame",
                            modifier = Modifier.size(72.dp))
                    }
                    item.approxTimestampSeconds?.let { ts ->
                        Text("peak @ ${ScoreView.secs(ts)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
