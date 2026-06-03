package com.example.clipcc.ui.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.util.UnstableApi
import com.example.clipcc.data.ModelRepository
import com.example.clipcc.ui.benchmark.BenchmarkData
import com.example.clipcc.ui.benchmark.BenchmarkScreen
import com.example.clipcc.ui.classify.*
import java.io.File

@OptIn(UnstableApi::class)
@Composable
fun ClipCCApp(onKeepAwake: (Boolean) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val titles = listOf("Classify", "Benchmark")
    Scaffold(modifier = Modifier.fillMaxSize()) { pad ->
        Column(Modifier.padding(pad)) {
            TabRow(selectedTabIndex = tab) {
                titles.forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            if (tab == 0) ClassifyTab(onKeepAwake) else BenchmarkScreen()
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun ClassifyTab(onKeepAwake: (Boolean) -> Unit) {
    val appCtx = LocalContext.current.applicationContext
    val vm: ClassifyViewModel = viewModel(factory = viewModelFactory {
        initializer {
            val models = ModelRepository(File(appCtx.filesDir, "models")).scan()
            val groups = BenchmarkData.parse(
                appCtx.assets.open("phase2-benchmark-result.json").bufferedReader().use { it.readText() })
            val lookup: (String, UiBackend) -> Double? = { id, backend ->
                val g = groups.firstOrNull { it.modelId == id }
                (g?.timed?.firstOrNull { it.backend == backend.engine.name } ?: g?.timed?.firstOrNull())?.msPerFrame
            }
            ClassifyViewModel(RealClassifier(appCtx), models, lookup, savedState = createSavedStateHandle())
        }
    })
    val ui by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(ui.keepAwake) { onKeepAwake(ui.keepAwake) }
    val running = ui.run is RunState.Running || ui.run is RunState.Cancelling

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SetupCard(ui.setup, vm, running)
        Button(onClick = { vm.run() }, enabled = ui.setup.canRun && !running,
            modifier = Modifier.padding(horizontal = 16.dp)) { Text("Run") }
        RunStatus(ui.run, onCancel = { vm.cancel() })
        (ui.run as? RunState.Success)?.let { ResultsSection(it) }
        (ui.run as? RunState.Error)?.let {
            Column(Modifier.padding(16.dp)) {
                Text("Error: ${it.message}", color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { vm.reset() }) { Text("Back") }
            }
        }
    }
}
