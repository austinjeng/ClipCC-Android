package com.example.clipcc.ui.classify

import com.example.clipcc.data.ModelInfo
import com.example.clipcc.engine.AggregationResult
import com.example.clipcc.engine.BestMatch
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
}
