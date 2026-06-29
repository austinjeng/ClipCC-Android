package com.example.clipcc.ui.classify

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.clipcc.data.ModelInfo
import com.example.clipcc.engine.AdviceLevel
import com.example.clipcc.engine.Precision
import com.example.clipcc.engine.PrecisionAdvice
import com.example.clipcc.engine.PrecisionPolicy
import com.example.clipcc.engine.RunCancelledException
import com.example.clipcc.engine.ScoringPolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class ClassifyViewModel(
    private val classifier: Classifier,
    private val models: List<ModelInfo>,
    private val benchmarkMsPerFrame: (String, UiBackend) -> Double?,
    private val runDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val savedState: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {

    private val _state = MutableStateFlow(ClassifyUiState())
    val state: StateFlow<ClassifyUiState> = _state.asStateFlow()

    private var job: Job? = null
    private val cancelFlag = AtomicBoolean(false)

    init {
        val restored = SetupState(
            availableModels = models,
            selectedModelId = savedState["selectedModelId"],
            backend = savedState.get<String>("backend")
                ?.let { runCatching { UiBackend.valueOf(it) }.getOrNull() } ?: UiBackend.CPU_XNNPACK,
            videoUriString = savedState["videoUriString"],
            videoName = savedState["videoName"],
            grantPersisted = savedState["grantPersisted"] ?: false,
            mode = savedState.get<String>("mode")
                ?.let { runCatching { AggMode.valueOf(it) }.getOrNull() } ?: AggMode.MEAN,
            positives = savedState.get<ArrayList<String>>("positives") ?: ArrayList(ScoringPolicy.DEFAULT_LABELS),
            negatives = savedState.get<ArrayList<String>>("negatives") ?: arrayListOf(),
            precision = savedState.get<String>("precision")
                ?.let { runCatching { Precision.valueOf(it) }.getOrNull() } ?: Precision.INT8,
            precisionUserSet = savedState["precisionUserSet"] ?: false,
        )
        _state.value = ClassifyUiState(setup = withDerived(restored))
    }

    private fun updateSetup(block: (SetupState) -> SetupState) {
        val next = withDerived(block(_state.value.setup))
        persist(next)
        _state.value = _state.value.copy(setup = next)
    }

    private fun persist(s: SetupState) {
        savedState["selectedModelId"] = s.selectedModelId
        savedState["backend"] = s.backend.name
        savedState["videoUriString"] = s.videoUriString
        savedState["videoName"] = s.videoName
        savedState["grantPersisted"] = s.grantPersisted
        savedState["mode"] = s.mode.name
        savedState["positives"] = ArrayList(s.positives)
        savedState["negatives"] = ArrayList(s.negatives)
        savedState["precision"] = s.precision.name
        savedState["precisionUserSet"] = s.precisionUserSet
    }

    private fun withDerived(s: SetupState): SetupState {
        val check = LabelValidation.validate(s.positives, s.negatives, s.mode == AggMode.CONTRAST)
        val eta = s.selectedModel?.let { benchmarkMsPerFrame(it.id, s.backend)?.toLong() }
        // Precision tracks the recommendation (by mode) until the user overrides; a manual choice
        // sticks while it remains provisioned, else it falls back to the recommendation.
        val effRec = effectiveRecommended(s)
        val precision = if (s.precisionUserSet && s.precision in s.availablePrecisions) s.precision else effRec
        val overridden = precision != effRec
        val advice = s.selectedModel?.let { PrecisionPolicy.advise(it.id, s.mode.isThresholdMode, precision) }
            ?: PrecisionAdvice(AdviceLevel.NONE, "")
        return s.copy(
            validationError = check.error, etaPerFrameMs = eta,
            precision = precision, precisionOverridden = overridden, precisionAdvice = advice,
        )
    }

    fun selectModel(id: String) = updateSetup { it.copy(selectedModelId = id) }
    fun setBackend(b: UiBackend) = updateSetup { it.copy(backend = b) }
    fun setVideo(uriString: String, name: String, granted: Boolean) =
        updateSetup { it.copy(videoUriString = uriString, videoName = name, grantPersisted = granted) }
    fun setMode(m: AggMode) = updateSetup { it.copy(mode = m) }
    fun setLabels(positives: List<String>, negatives: List<String>) =
        updateSetup { it.copy(positives = positives, negatives = negatives) }
    /** Routes a committed list to the correct field (single tested seam for CSV-import targeting). */
    fun setLabelList(target: LabelTarget, list: List<String>) {
        val s = _state.value.setup
        return when (target) {
            LabelTarget.POSITIVE -> setLabels(list, s.negatives)
            LabelTarget.NEGATIVE -> setLabels(s.positives, list)
        }
    }

    fun setTemporal(o: TemporalOptions) = updateSetup { it.copy(temporal = o) }
    fun setContrast(o: ContrastOptions) = updateSetup { it.copy(contrast = o) }
    // Picking the recommended value is NOT an override — it resumes tracking (clears the reset affordance).
    fun setPrecision(p: Precision) = updateSetup { it.copy(precision = p, precisionUserSet = p != effectiveRecommended(it)) }
    fun resetPrecision() = updateSetup { it.copy(precisionUserSet = false) }

    /** The mode's recommendation, clamped to what the selected model provisions. */
    private fun effectiveRecommended(s: SetupState): Precision {
        val rec = PrecisionPolicy.recommended(s.mode.isThresholdMode)
        return rec.takeIf { it in s.availablePrecisions } ?: s.availablePrecisions.firstOrNull() ?: rec
    }

    fun run() {
        val s = _state.value.setup
        val model = s.selectedModel ?: return
        val check = LabelValidation.validate(s.positives, s.negatives, s.mode == AggMode.CONTRAST)
        if (!s.canRun || check.error != null) return
        val req = ClassifyRequest(
            modelDir = model.dir, modelId = model.id, backend = s.backend,
            videoUriString = s.videoUriString!!, labels = check.cleaned, posCount = check.posCount,
            mode = s.mode, temporal = s.temporal, contrast = s.contrast,
            precision = s.precision,   // derived: recommendation-by-mode unless manually overridden
        )
        cancelFlag.set(false)
        _state.value = _state.value.copy(run = RunState.Running(Stage.LOADING_MODEL, 0, 0))
        job = viewModelScope.launch(runDispatcher) {
            try {
                val result = classifier.classify(
                    req,
                    onProgress = { stage, done, total -> postProgress(stage, done, total) },
                    isCancelled = { cancelFlag.get() },
                )
                _state.value = _state.value.copy(run = RunState.Success(result))
            } catch (c: RunCancelledException) {
                _state.value = _state.value.copy(run = RunState.Cancelled)
            } catch (t: Throwable) {
                _state.value = _state.value.copy(run = RunState.Error(t.message ?: t.javaClass.simpleName))
            }
        }
    }

    private fun postProgress(stage: Stage, done: Int, total: Int) {
        if (cancelFlag.get()) { _state.value = _state.value.copy(run = RunState.Cancelling); return }
        _state.value = _state.value.copy(run = RunState.Running(stage, done, total))
    }

    fun cancel() {
        cancelFlag.set(true)
        if (_state.value.run is RunState.Running) _state.value = _state.value.copy(run = RunState.Cancelling)
    }

    fun reset() { _state.value = _state.value.copy(run = RunState.Idle) }
}
