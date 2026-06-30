package com.example.clipcc.ui.benchmark

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun Pixel9aScreen() {
    val ctx = LocalContext.current
    val result = remember {
        fun read(name: String): () -> String? = {
            runCatching { ctx.assets.open(name).bufferedReader().use { it.readText() } }.getOrNull()
        }
        BenchmarkCompare.load(
            read7a = read("phase2-benchmark-result.json"),
            read9a = read("phase2-benchmark-result-9a.json"),
        )
    }
    when (result) {
        is CompareResult.Empty -> EmptyState(result.reason)
        is CompareResult.Ok -> Comparison9a(result.comparison)
    }
}

@Composable
private fun EmptyState(reason: EmptyReason) {
    val msg = when (reason) {
        EmptyReason.NO_9A -> "Pixel 9a snapshot not bundled."
        EmptyReason.NO_7A -> "Benchmark (Pixel 7a) snapshot unavailable."
        EmptyReason.NO_OVERLAP -> "No models common to both snapshots."
    }
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(msg, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun Comparison9a(c: Comparison) {
    Column(
        Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(c.header, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        ElevatedCard {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("⚡ +${"%.0f".format(Locale.US, c.avgPctFaster)}% faster",
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
                Text("protocol-matched (XNNPACK) · avg over ${c.heroLaneCount} models · per-model " +
                    "+${"%.0f".format(Locale.US, c.minPctFaster)}–${"%.0f".format(Locale.US, c.maxPctFaster)}%",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        c.models.forEach { ModelCard(it) }
        Text("Spike A was not a sustained-load test; band-separation bounds 3-run repeatability, not thermal drift.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun ModelCard(m: ModelDelta) {
    ElevatedCard {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(m.modelId, style = MaterialTheme.typography.titleMedium)
            Text("frames: 7a ${m.frames7a ?: "?"} → 9a ${m.frames9a ?: "?"}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            val matched = m.lanes.filter { it.protocolMatched }
            val unmatched = m.lanes.filter { !it.protocolMatched }
            matched.forEach { LaneRow(it, bold = it == m.headline) }
            if (unmatched.isNotEmpty()) {
                Text("— not protocol-matched (frame counts differ) —",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                unmatched.forEach { LaneRow(it, bold = false) }
            }
        }
    }
}

@Composable
private fun LaneRow(l: LaneDelta, bold: Boolean) {
    fun f(x: Double) = "%.0f".format(Locale.US, x)
    fun signed(x: Double) = (if (x >= 0) "+" else "") + f(x)
    val tail = if (l.bandSeparated)
        "${signed(l.pctFaster)}% (${"%.2f".format(Locale.US, l.speedup)}×)"
    else
        "≈ ${signed(l.pctFaster)}% (within 3-run spread)"
    Text(
        "${l.backend}: ${f(l.ms7a)} [${f(l.ms7aMin)}–${f(l.ms7aMax)}] → " +
            "${f(l.ms9a)} [${f(l.ms9aMin)}–${f(l.ms9aMax)}] ms/frame   $tail",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    )
}
