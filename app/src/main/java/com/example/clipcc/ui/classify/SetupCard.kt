package com.example.clipcc.ui.classify

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.clipcc.engine.AdviceLevel
import com.example.clipcc.engine.Precision

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupCard(state: SetupState, vm: ClassifyViewModel, running: Boolean) {
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val granted = try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                true
            } catch (t: Throwable) { false }
            val name = uri.lastPathSegment ?: "video"
            vm.setVideo(uri.toString(), name, granted)
        }
    }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        var modelMenu by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = modelMenu, onExpandedChange = { modelMenu = it }) {
            OutlinedTextField(
                value = state.selectedModel?.let { "${it.displayName} · ${it.resolution}px" }
                    ?: "Select model",
                onValueChange = {}, readOnly = true, label = { Text("Model") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelMenu) },
                modifier = Modifier.menuAnchor().fillMaxWidth(), enabled = !running,
            )
            ExposedDropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                if (state.availableModels.isEmpty()) {
                    DropdownMenuItem(text = { Text("No models provisioned — adb push to files/models/") },
                        onClick = {}, enabled = false)
                }
                state.availableModels.forEach { m ->
                    DropdownMenuItem(
                        text = { Text("${m.displayName} · ${m.resolution}px" +
                            if (m.ready) "" else "  (${m.reason})") },
                        enabled = m.ready,
                        onClick = { vm.selectModel(m.id); modelMenu = false })
                }
            }
        }

        Text("Backend", style = MaterialTheme.typography.labelMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            UiBackend.entries.forEachIndexed { i, b ->
                SegmentedButton(
                    selected = state.backend == b, onClick = { vm.setBackend(b) },
                    enabled = !running,
                    shape = SegmentedButtonDefaults.itemShape(i, UiBackend.entries.size),
                ) { Text(b.label) }
            }
        }
        if (state.backend.experimental) {
            Text(state.backend.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Button(onClick = { picker.launch(arrayOf("video/*")) }, enabled = !running) {
            Text(state.videoName?.let { "Video: $it" } ?: "Pick video")
        }

        Text("Mode", style = MaterialTheme.typography.labelMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            AggMode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = state.mode == m, onClick = { vm.setMode(m) }, enabled = !running,
                    shape = SegmentedButtonDefaults.itemShape(i, AggMode.entries.size),
                ) { Text(m.name.lowercase()) }
            }
        }

        if (state.availablePrecisions.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Precision", style = MaterialTheme.typography.labelMedium)
                if (state.precisionOverridden) {
                    TextButton(onClick = { vm.resetPrecision() }, enabled = !running) {
                        Text("↺ recommended")
                    }
                }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Precision.entries.forEachIndexed { i, p ->
                    SegmentedButton(
                        selected = state.precision == p, onClick = { vm.setPrecision(p) },
                        enabled = !running && p in state.availablePrecisions,
                        shape = SegmentedButtonDefaults.itemShape(i, Precision.entries.size),
                    ) { Text(p.key) }
                }
            }
            // Disclaimer only when the user has deviated from the recommendation (cosmetic when on it).
            if (state.precisionOverridden && state.precisionAdvice.level != AdviceLevel.NONE) {
                Text(
                    state.precisionAdvice.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.precisionAdvice.level == AdviceLevel.WARN)
                        MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        LabelEditor(state, vm, running)

        when (state.mode) {
            AggMode.TEMPORAL -> TemporalOptionsEditor(state.temporal, vm, running)
            AggMode.CONTRAST -> ContrastOptionsEditor(state.contrast, vm, running)
            else -> {}
        }

        state.validationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.etaPerFrameMs?.let { Text("≈ ${it} ms/frame on this model+backend (captured estimate)",
            style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun LabelEditor(state: SetupState, vm: ClassifyViewModel, running: Boolean) {
    if (state.mode == AggMode.CONTRAST) {
        Text("Positive labels", style = MaterialTheme.typography.labelMedium)
        EditableList(state.positives, running) { vm.setLabels(it, state.negatives) }
        Text("Negative labels", style = MaterialTheme.typography.labelMedium)
        EditableList(state.negatives, running) { vm.setLabels(state.positives, it) }
    } else {
        Text("Labels", style = MaterialTheme.typography.labelMedium)
        EditableList(state.positives, running) { vm.setLabels(it, state.negatives) }
    }
}

@Composable
private fun EditableList(items: List<String>, running: Boolean, onChange: (List<String>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEachIndexed { i, v ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(value = v, onValueChange = { nv ->
                    onChange(items.toMutableList().also { it[i] = nv })
                }, modifier = Modifier.weight(1f), enabled = !running, singleLine = true)
                TextButton(onClick = { onChange(items.toMutableList().also { it.removeAt(i) }) },
                    enabled = !running) { Text("×") }
            }
        }
        TextButton(onClick = { onChange(items + "") }, enabled = !running) { Text("+ Add label") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemporalOptionsEditor(o: TemporalOptions, vm: ClassifyViewModel, running: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        NumberField("Threshold", o.threshold, running) { vm.setTemporal(o.copy(threshold = it, thresholdWasDefaulted = false)) }
        NumberField("Gap tolerance (s)", o.gap, running) { vm.setTemporal(o.copy(gap = it)) }
        NumberField("Min duration (s)", o.minDuration, running) { vm.setTemporal(o.copy(minDuration = it)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContrastOptionsEditor(o: ContrastOptions, vm: ClassifyViewModel, running: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        var menu by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = menu, onExpandedChange = { menu = it }) {
            OutlinedTextField(value = o.reduce, onValueChange = {}, readOnly = true,
                label = { Text("Reduce") }, modifier = Modifier.menuAnchor().fillMaxWidth(), enabled = !running)
            ExposedDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                com.example.clipcc.engine.ScoringPolicy.CONTRAST_REDUCE_MODES.forEach { r ->
                    DropdownMenuItem(text = { Text(r) }, onClick = { vm.setContrast(o.copy(reduce = r)); menu = false })
                }
            }
        }
        NumberField("Threshold", o.threshold, running) { vm.setContrast(o.copy(threshold = it, thresholdWasDefaulted = false)) }
    }
}

@Composable
private fun NumberField(label: String, value: Double, running: Boolean, onChange: (Double) -> Unit) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { it.toDoubleOrNull()?.let(onChange) },
        label = { Text(label) }, enabled = !running, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}
