package com.example.clipcc.ui.classify

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.clipcc.ui.charts.BarChart
import com.example.clipcc.ui.charts.ChartData

@Composable
fun ResultsSection(success: RunState.Success) {
    val r = success.result
    val agg = r.result
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ElevatedCard {
            Column(Modifier.padding(16.dp)) {
                Text("Best match", style = MaterialTheme.typography.labelMedium)
                Text(agg.bestMatch.label, style = MaterialTheme.typography.headlineSmall)
                Text("confidence ${"%.3f".format(agg.bestMatch.confidence)}")
                Text("${r.meta.modelId} · ${r.meta.requestedBackend.label} · ${r.meta.frameCount} frames · " +
                    "${r.meta.elapsedMs} ms · ${r.meta.scoreSemantics}",
                    style = MaterialTheme.typography.bodySmall)
                Text("live node coverage not profiled — see Benchmark",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }

        Text("Confidence", style = MaterialTheme.typography.labelLarge)
        BarChart(ChartData.confidenceBars(agg.scores), max = ChartData.UNIT_MAX,
            barColor = MaterialTheme.colorScheme.primary, thresholdLine = ChartData.UNIT_MAX * 0.5f)

        Text("Raw similarity (cosine)", style = MaterialTheme.typography.labelLarge)
        val cos = ChartData.cosineBars(agg.scores)
        BarChart(cos, max = ChartData.symmetricMax(cos.map { it.value }),
            barColor = Color(0xFF7E57C2), zeroAtCenter = true)

        agg.scores.forEach {
            Text("${it.label}: conf ${"%.3f".format(it.confidence)}, cos ${"%.3f".format(it.rawSimilarity)}",
                style = MaterialTheme.typography.bodySmall)
        }

        ModeExtras(r)
    }
}
