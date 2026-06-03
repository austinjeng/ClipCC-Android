package com.example.clipcc.ui.benchmark

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private const val HEADER =
    "Pixel 7a · Tensor G2 · CPU-only · median-of-3 (1 warm-up discarded) · " +
        "clip: test.mp4 (720×1280 SDR, 5.9 s) → 7 frames @1 fps; so400m capped at 4 · " +
        "vision-encode ms/frame · Media3 1.10.1 · captured 2026-06-03"

@Composable
fun BenchmarkScreen() {
    val ctx = LocalContext.current
    val groups = remember {
        BenchmarkData.parse(ctx.assets.open("phase2-benchmark-result.json").bufferedReader().use { it.readText() })
    }
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(HEADER, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        groups.forEach { g ->
            ElevatedCard {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(g.modelId, style = MaterialTheme.typography.titleMedium)
                    g.timed.forEach { t ->
                        Text("${t.backend}: ${"%.0f".format(t.msPerFrame)} ms/frame · ${"%.3f".format(t.fps)} fps · " +
                            "load ${t.loadMs} ms" +
                            (t.visionDelegatedPct?.let { " · ${"%.1f".format(it)}% delegated" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    g.capabilityOnly.forEach { c ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AssistChip(onClick = {}, label = { Text("experimental") }, enabled = false)
                            Text("${c.backend}: not timed · ${"%.0f".format(c.visionDelegatedPct)}% delegated",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
