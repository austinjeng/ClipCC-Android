package com.example.clipcc.ui.classify

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun RunStatus(run: RunState, onCancel: () -> Unit) {
    when (run) {
        is RunState.Running -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val label = when (run.stage) {
                Stage.LOADING_MODEL -> "Loading model…"
                Stage.ENCODING_TEXT -> "Encoding labels…"
                Stage.DECODING -> "Decoding video…"
                Stage.ENCODING_VISION -> "Encoding frames (chunk ${run.chunkDone}/${run.chunkTotal})…"
                Stage.AGGREGATING -> "Aggregating…"
            }
            Text(label)
            if (run.stage == Stage.ENCODING_VISION && run.chunkTotal > 0)
                LinearProgressIndicator(progress = { run.chunkDone.toFloat() / run.chunkTotal },
                    modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
        is RunState.Cancelling -> Row(Modifier.padding(16.dp)) {
            CircularProgressIndicator(); Spacer(Modifier.width(12.dp)); Text("Cancelling…")
        }
        else -> {}
    }
}
