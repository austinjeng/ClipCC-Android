package com.example.clipcc.ui.classify

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                value = state.selectedModel?.let { "${it.displayName} · ${it.resolution}px · ${it.precision}" }
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
                        text = { Text("${m.displayName} · ${m.resolution}px · ${m.precision}" +
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
        ImportCsvButton(LabelTarget.POSITIVE, vm, running)
        Text("Negative labels", style = MaterialTheme.typography.labelMedium)
        EditableList(state.negatives, running) { vm.setLabels(state.positives, it) }
        ImportCsvButton(LabelTarget.NEGATIVE, vm, running)
    } else {
        Text("Labels", style = MaterialTheme.typography.labelMedium)
        EditableList(state.positives, running) { vm.setLabels(it, state.negatives) }
        ImportCsvButton(LabelTarget.POSITIVE, vm, running)
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

private data class ImportPreview(
    val read: LabelCsv.Read,
    val parsed: LabelCsv.Parsed,
    val append: LabelCsv.Merged,
    val existingCount: Int,
)

@Composable
private fun ImportCsvButton(target: LabelTarget, vm: ClassifyViewModel, running: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                try {
                    val read = ctx.contentResolver.openInputStream(uri)?.use { LabelCsv.read(it) }
                        ?: error("null stream")
                    Result.success(read to LabelCsv.parse(read.text))
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    Result.failure(t)
                }
            }
            res.fold(
                onSuccess = { (read, parsed) ->
                    if (parsed.labels.isEmpty()) {
                        notice = LabelCsv.zeroNotice(read, parsed); preview = null
                    } else {
                        val s = vm.state.value.setup
                        val current = if (target == LabelTarget.POSITIVE) s.positives else s.negatives
                        notice = null
                        preview = ImportPreview(
                            read, parsed,
                            LabelCsv.merge(current, parsed.labels, replace = false), current.size)
                    }
                },
                onFailure = { t ->
                    notice = if (t is java.nio.charset.CharacterCodingException)
                        "Couldn't read file (not valid text)" else "Couldn't open file"
                    preview = null
                },
            )
        }
    }

    TextButton(
        onClick = {
            notice = null
            picker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "text/*"))
        },
        enabled = !running,
    ) { Text("Import CSV") }

    notice?.let {
        Text(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    preview?.let { p ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("Import ${p.parsed.labels.size} labels") },
            text = {
                val extra = (if (p.read.byteTruncated || p.parsed.labelTruncated) " File was truncated." else "") +
                    (if (p.parsed.dropped > 0) " ${p.parsed.dropped} row(s) too long, skipped." else "") +
                    (if (p.append.truncated) " The label list is full; some imported labels won't fit on Append." else "")
                Text("Append to the current ${p.existingCount}, or replace them?$extra")
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        vm.setLabelList(target, p.append.labels)
                        notice = LabelCsv.appendNotice(p.read, p.parsed, p.append); preview = null
                    }) {
                        Text("Append (${p.append.inserted} new" +
                            (if (p.append.duplicates > 0) ", ${p.append.duplicates} dup" else "") + ")")
                    }
                    TextButton(onClick = {
                        vm.setLabelList(target, p.parsed.labels)
                        notice = LabelCsv.replaceNotice(p.read, p.parsed); preview = null
                    }) { Text("Replace") }
                }
            },
            dismissButton = { TextButton(onClick = { preview = null }) { Text("Cancel") } },
        )
    }
}
