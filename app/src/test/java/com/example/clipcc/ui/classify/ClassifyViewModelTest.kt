package com.example.clipcc.ui.classify

import com.example.clipcc.data.ModelInfo
import com.example.clipcc.engine.AdviceLevel
import com.example.clipcc.engine.AggregationResult
import com.example.clipcc.engine.BestMatch
import com.example.clipcc.engine.Precision
import com.example.clipcc.engine.ScoreItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClassifyViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private val readyModel = ModelInfo(
        "siglip2-base-patch16-256", "Base", 256, "fp32",
        "siglip2_pairwise_sigmoid", ready = true, reason = null, dir = "/tmp/m")

    private fun okResult() = AggregationResult(
        scores = listOf(ScoreItem("a", 0.9, 0.1)), bestMatch = BestMatch("a", 0.9))

    private class FakeClassifier(
        val result: AggregationResult,
        val onClassify: suspend (ClassifyRequest, ProgressSink, () -> Boolean) -> Unit = { _, _, _ -> },
    ) : Classifier {
        override suspend fun classify(req: ClassifyRequest, onProgress: ProgressSink, isCancelled: () -> Boolean): RunResult {
            onClassify(req, onProgress, isCancelled)
            return RunResult(result, emptyMap(), DoubleArray(0),
                RunMeta(req.modelId, req.backend, 0, 0, "siglip2_pairwise_sigmoid"))
        }
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(classifier: Classifier) = ClassifyViewModel(
        classifier = classifier, models = listOf(readyModel),
        benchmarkMsPerFrame = { _, _ -> 1202.0 }, runDispatcher = dispatcher,
    ).apply {
        selectModel(readyModel.id)
        setVideo("content://v/1", "clip.mp4", granted = true)
    }

    @Test fun run_disabled_until_model_and_video_set() {
        val v = ClassifyViewModel(FakeClassifier(okResult()), listOf(readyModel), { _, _ -> null }, dispatcher)
        assertFalse(v.state.value.setup.canRun)
        v.selectModel(readyModel.id); v.setVideo("content://v/1", "c.mp4", true)
        assertTrue(v.state.value.setup.canRun)
    }

    @Test fun successful_run_reaches_Success() = runTest(dispatcher) {
        val v = vm(FakeClassifier(okResult()))
        v.run()
        advanceUntilIdle()
        val run = v.state.value.run
        assertTrue(run is RunState.Success)
        assertEquals("a", (run as RunState.Success).result.result.bestMatch.label)
    }

    @Test fun classifier_error_reaches_Error() = runTest(dispatcher) {
        val v = vm(FakeClassifier(okResult()) { _, _, _ -> throw IllegalStateException("boom") })
        v.run(); advanceUntilIdle()
        assertTrue(v.state.value.run is RunState.Error)
        assertEquals("boom", (v.state.value.run as RunState.Error).message)
    }

    @Test fun cancel_reaches_Cancelled() = runTest(dispatcher) {
        val v = vm(FakeClassifier(okResult()) { _, _, isCancelled ->
            if (isCancelled()) throw com.example.clipcc.engine.RunCancelledException()
        })
        v.run()
        v.cancel()
        advanceUntilIdle()
        assertTrue(v.state.value.run is RunState.Cancelled)
    }

    @Test fun contrast_mode_sets_posCount_and_concatenates() = runTest(dispatcher) {
        var captured: ClassifyRequest? = null
        val v = vm(FakeClassifier(okResult()) { req, _, _ -> captured = req })
        v.setMode(AggMode.CONTRAST)
        v.setLabels(positives = listOf("yes"), negatives = listOf("no"))
        v.run(); advanceUntilIdle()
        assertEquals(listOf("yes", "no"), captured!!.labels)
        assertEquals(1, captured!!.posCount)
    }

    @Test fun eta_per_frame_comes_from_benchmark_lookup() {
        val v = vm(FakeClassifier(okResult()))
        assertEquals(1202L, v.state.value.setup.etaPerFrameMs)
    }

    @Test fun invalid_labels_block_run() {
        val v = vm(FakeClassifier(okResult()))
        v.setLabels(positives = listOf("dup", "dup"), negatives = emptyList())
        assertFalse(v.state.value.setup.canRun)
        assertEquals("Duplicate label: dup", v.state.value.setup.validationError)
    }

    @Test fun setup_restores_from_saved_state() {
        val handle = androidx.lifecycle.SavedStateHandle()
        val v1 = ClassifyViewModel(FakeClassifier(okResult()), listOf(readyModel), { _, _ -> null }, dispatcher, handle)
        v1.selectModel(readyModel.id); v1.setMode(AggMode.MAX)
        val v2 = ClassifyViewModel(FakeClassifier(okResult()), listOf(readyModel), { _, _ -> null }, dispatcher, handle)
        assertEquals(readyModel.id, v2.state.value.setup.selectedModelId)
        assertEquals(AggMode.MAX, v2.state.value.setup.mode)
    }

    // ---- precision policy wiring (T4) ----
    private val multiModel = ModelInfo(
        "siglip2-so400m-patch14-384", "So400m", 384, "fp16",
        "siglip2_pairwise_sigmoid", ready = true, reason = null, dir = "/tmp/so",
        availablePrecisions = listOf(Precision.FP32, Precision.FP16, Precision.INT8))

    private fun vmMulti() = ClassifyViewModel(
        FakeClassifier(okResult()), listOf(multiModel), { _, _ -> null }, dispatcher,
    ).apply { selectModel(multiModel.id); setVideo("content://v/1", "c.mp4", true) }

    @Test fun precision_defaults_to_recommended_by_mode() {
        val v = vmMulti()
        assertEquals(Precision.INT8, v.state.value.setup.precision)           // MEAN → int8
        assertEquals(AdviceLevel.NONE, v.state.value.setup.precisionAdvice.level)
        v.setMode(AggMode.TEMPORAL)
        assertEquals(Precision.FP16, v.state.value.setup.precision)           // tracks → fp16
        assertFalse(v.state.value.setup.precisionUserSet)
    }

    @Test fun manual_override_sticks_across_mode_change() {
        val v = vmMulti()                                                    // MEAN, recommends int8
        v.setPrecision(Precision.FP32)                                       // fp32 is never recommended
        assertTrue(v.state.value.setup.precisionOverridden)
        v.setMode(AggMode.TEMPORAL)
        assertEquals(Precision.FP32, v.state.value.setup.precision)           // override sticks
        assertTrue(v.state.value.setup.precisionOverridden)
    }

    @Test fun int8_in_threshold_mode_is_overridden_and_warns() {
        val v = vmMulti()
        v.setMode(AggMode.TEMPORAL)                                           // recommends fp16
        v.setPrecision(Precision.INT8)
        assertTrue(v.state.value.setup.precisionOverridden)
        assertEquals(AdviceLevel.WARN, v.state.value.setup.precisionAdvice.level)
    }

    // ---- the reported bug: re-picking the recommended value must clear the reset affordance ----
    @Test fun picking_recommended_precision_directly_is_not_overridden() {
        val v = vmMulti()                                                    // MEAN, recommends int8
        v.setPrecision(Precision.FP32)
        assertTrue(v.state.value.setup.precisionOverridden)
        v.setPrecision(Precision.INT8)                                       // recommended value, not via reset
        assertFalse("recommended pick must not show the reset", v.state.value.setup.precisionOverridden)
        assertFalse(v.state.value.setup.precisionUserSet)                    // resumes tracking
    }

    @Test fun mode_change_making_choice_equal_recommendation_clears_override() {
        val v = vmMulti()
        v.setMode(AggMode.TEMPORAL)                                           // recommends fp16
        v.setPrecision(Precision.INT8)                                        // override
        assertTrue(v.state.value.setup.precisionOverridden)
        v.setMode(AggMode.MEAN)                                               // int8 IS recommended here
        assertFalse(v.state.value.setup.precisionOverridden)                  // coincidence → no reset shown
    }

    @Test fun reset_precision_returns_to_recommendation() {
        val v = vmMulti()
        v.setMode(AggMode.TEMPORAL); v.setPrecision(Precision.INT8)
        v.resetPrecision()
        assertFalse(v.state.value.setup.precisionUserSet)
        assertEquals(Precision.FP16, v.state.value.setup.precision)
    }

    @Test fun so400m_fp32_override_warns_ram() {
        val v = vmMulti()
        v.setPrecision(Precision.FP32)
        val advice = v.state.value.setup.precisionAdvice
        assertEquals(AdviceLevel.WARN, advice.level)
        assertTrue(advice.text.contains("RAM"))
    }

    @Test fun recommendation_clamps_to_available_precisions() {
        val fp16Only = multiModel.copy(availablePrecisions = listOf(Precision.FP16))
        val v = ClassifyViewModel(FakeClassifier(okResult()), listOf(fp16Only), { _, _ -> null }, dispatcher)
            .apply { selectModel(fp16Only.id) }
        assertEquals(Precision.FP16, v.state.value.setup.precision)           // MEAN→int8 clamps to fp16
    }

    @Test fun run_passes_selected_precision_to_request() = runTest(dispatcher) {
        var captured: ClassifyRequest? = null
        val v = ClassifyViewModel(
            FakeClassifier(okResult()) { req, _, _ -> captured = req }, listOf(multiModel),
            { _, _ -> null }, dispatcher,
        ).apply { selectModel(multiModel.id); setVideo("content://v/1", "c.mp4", true) }
        v.setPrecision(Precision.FP32)
        v.run(); advanceUntilIdle()
        assertEquals(Precision.FP32, captured!!.precision)
    }

    @Test fun setLabelList_positive_changes_only_positives() {
        val v = vm(FakeClassifier(okResult()))
        v.setMode(AggMode.CONTRAST)
        v.setLabels(positives = listOf("p1"), negatives = listOf("n1"))
        v.setLabelList(LabelTarget.POSITIVE, listOf("x", "y"))
        assertEquals(listOf("x", "y"), v.state.value.setup.positives)
        assertEquals(listOf("n1"), v.state.value.setup.negatives)
    }
    @Test fun setLabelList_negative_changes_only_negatives() {
        val v = vm(FakeClassifier(okResult()))
        v.setMode(AggMode.CONTRAST)
        v.setLabels(positives = listOf("p1"), negatives = listOf("n1"))
        v.setLabelList(LabelTarget.NEGATIVE, listOf("z"))
        assertEquals(listOf("p1"), v.state.value.setup.positives)
        assertEquals(listOf("z"), v.state.value.setup.negatives)
    }

    @Test fun temporal_taps_unlock_contrast_at_threshold() {
        val v = vm(FakeClassifier(okResult()))
        repeat(ClassifyViewModel.CONTRAST_UNLOCK_TAPS - 1) { v.setMode(AggMode.TEMPORAL) }
        assertFalse(v.state.value.setup.contrastUnlocked)
        v.setMode(AggMode.TEMPORAL)
        assertTrue(v.state.value.setup.contrastUnlocked)
    }
    @Test fun non_temporal_taps_do_not_unlock() {
        val v = vm(FakeClassifier(okResult()))
        repeat(30) { v.setMode(AggMode.MAX); v.setMode(AggMode.MEAN) }
        assertFalse(v.state.value.setup.contrastUnlocked)
    }
    @Test fun contrast_unlock_restored_from_saved_state() {
        val handle = androidx.lifecycle.SavedStateHandle()
        val v1 = ClassifyViewModel(FakeClassifier(okResult()), listOf(readyModel), { _, _ -> null }, dispatcher, handle)
        repeat(ClassifyViewModel.CONTRAST_UNLOCK_TAPS) { v1.setMode(AggMode.TEMPORAL) }
        val v2 = ClassifyViewModel(FakeClassifier(okResult()), listOf(readyModel), { _, _ -> null }, dispatcher, handle)
        assertTrue(v2.state.value.setup.contrastUnlocked)
    }
    @Test fun dedupeLabels_drops_later_dups_keeps_blanks_and_clears_error() {
        val v = vm(FakeClassifier(okResult()))
        v.setMode(AggMode.CONTRAST)
        v.setLabels(positives = listOf("cat", "dog", ""), negatives = listOf("cat", "bird"))
        assertEquals("Duplicate label: cat", v.state.value.setup.validationError)
        v.dedupeLabels()
        assertEquals(listOf("cat", "dog", ""), v.state.value.setup.positives)  // blank kept, first cat kept
        assertEquals(listOf("bird"), v.state.value.setup.negatives)            // later cat dropped
        assertNull(v.state.value.setup.validationError)
    }
}
