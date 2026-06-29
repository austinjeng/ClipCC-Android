package com.example.clipcc.ui.classify

import android.graphics.Bitmap
import com.example.clipcc.data.ModelInfo
import com.example.clipcc.engine.AggregationResult
import com.example.clipcc.engine.ScoringPolicy

enum class AggMode { MEAN, MAX, TEMPORAL, CONTRAST }
enum class LabelTarget { POSITIVE, NEGATIVE }

data class TemporalOptions(
    val threshold: Double = ScoringPolicy.THRESHOLD,
    val gap: Double = ScoringPolicy.GAP_TOLERANCE,
    val minDuration: Double = ScoringPolicy.MIN_DURATION,
    val thresholdWasDefaulted: Boolean = true,
)
data class ContrastOptions(
    val threshold: Double = ScoringPolicy.CONTRAST_THRESHOLD,
    val reduce: String = ScoringPolicy.CONTRAST_REDUCE,
    val thresholdWasDefaulted: Boolean = true,
)

data class ClassifyRequest(
    val modelDir: String, val modelId: String, val backend: UiBackend, val videoUriString: String,
    val labels: List<String>, val posCount: Int, val mode: AggMode,
    val temporal: TemporalOptions, val contrast: ContrastOptions,
)
data class RunMeta(
    val modelId: String, val requestedBackend: UiBackend,
    val frameCount: Int, val elapsedMs: Long, val scoreSemantics: String,
)
data class RunResult(
    val result: AggregationResult, val thumbnails: Map<Int, Bitmap>,
    val timestamps: DoubleArray, val meta: RunMeta,
)

sealed interface RunState {
    data object Idle : RunState
    data class Running(val stage: Stage, val chunkDone: Int, val chunkTotal: Int) : RunState
    data object Cancelling : RunState
    data class Success(val result: RunResult) : RunState
    data class Error(val message: String) : RunState
    data object Cancelled : RunState
}
enum class Stage { LOADING_MODEL, ENCODING_TEXT, DECODING, ENCODING_VISION, AGGREGATING }

data class SetupState(
    val availableModels: List<ModelInfo> = emptyList(),
    val selectedModelId: String? = null,
    val backend: UiBackend = UiBackend.CPU_XNNPACK,
    val videoUriString: String? = null, val videoName: String? = null, val grantPersisted: Boolean = false,
    val positives: List<String> = ScoringPolicy.DEFAULT_LABELS,
    val negatives: List<String> = emptyList(),
    val mode: AggMode = AggMode.MEAN,
    val temporal: TemporalOptions = TemporalOptions(),
    val contrast: ContrastOptions = ContrastOptions(),
    val validationError: String? = null,
    val etaPerFrameMs: Long? = null,
) {
    val selectedModel: ModelInfo? get() = availableModels.firstOrNull { it.id == selectedModelId }
    val canRun: Boolean get() =
        selectedModel?.ready == true && videoUriString != null && validationError == null
}

data class ClassifyUiState(
    val setup: SetupState = SetupState(),
    val run: RunState = RunState.Idle,
) {
    val keepAwake: Boolean get() = run is RunState.Running || run is RunState.Cancelling
}
