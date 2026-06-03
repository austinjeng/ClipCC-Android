package com.example.clipcc.engine

import ai.onnxruntime.OrtEnvironment
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Benchmarks ONE model per invocation (driven by `-e model <id>`), each in a FRESH process.
 * Rationale: a single process benchmarking all 4 models OOM-crashes ("Process crashed") once it
 * reaches so400m on top of ~30 min of accumulated native ORT memory — a native death a Kotlin
 * try/catch cannot catch. One am-instrument run per model gives each a clean ~7.6 GB and isolates
 * any single-model failure. The host driver runs this 4x and merges the per-model JSON.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class BenchmarkMatrixTest {
    private val labels = listOf("Car", "texting while driving", "a dog")
    private val video = "/data/local/tmp/clipcc_bench/test.mp4"
    private val allModels = listOf(
        "siglip2-base-patch16-256", "siglip2-base-patch16-384",
        "siglip2-large-patch16-384", "siglip2-so400m-patch14-384",
    )

    @Test fun bench_one_model() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val modelId = InstrumentationRegistry.getArguments().getString("model")
            ?: error("pass -e model <id>")
        require(modelId in allModels) { "unknown model $modelId" }
        val env = OrtEnvironment.getEnvironment()
        val bench = Benchmark(ctx, env)
        val dir = "/data/local/tmp/clipcc_models/$modelId"
        val manifest = ModelBundleManifest.parse(File("$dir/manifest.json").readText())

        // capability (untimed): vision tower for all 4 backends; text tower for the 2 CPU lanes
        val caps = ArrayList<BackendCapabilityReport>()
        for (b in listOf(Backend.CPU_XNNPACK, Backend.CPU_EP, Backend.NNAPI_DEFAULT, Backend.NNAPI_CPU_DISABLED))
            caps.add(BackendCapability.probeVision(dir, modelId, manifest.resolution, b, env, ctx.cacheDir,
                BackendCapability.dummyFrame(manifest.resolution)))
        for (b in listOf(Backend.CPU_XNNPACK, Backend.CPU_EP))
            caps.add(BackendCapability.probeText(dir, modelId, manifest.maxLength, b, env, ctx.cacheDir))

        // prep once, then both timed CPU lanes over the SAME cached tensors
        val (prep, _, holder) = bench.prepFrames(dir, manifest, video)
        holder.decodeMs = prep.decodeMs; holder.preMs = prep.preprocessMs
        val runs = ArrayList<TimedRun>()
        var order = 0
        for (b in listOf(Backend.CPU_XNNPACK, Backend.CPU_EP))
            runs.add(bench.timeLane(dir, manifest, b, holder, prep.frames, labels, order++))

        val path = bench.writeResults(listOf(prep), runs, caps, "benchmark_$modelId.json")
        val bundle = android.os.Bundle(); bundle.putString("benchmark_result_path", path); inst.sendStatus(0, bundle)
        println("MATRIX_ONE model=$modelId runs=${runs.size} caps=${caps.size} path=$path")

        // gate (this model): both CPU lanes timed + all 4 vision caps + both text caps
        assertTrue("xnnpack timed", runs.any { it.backend == Backend.CPU_XNNPACK })
        assertTrue("cpu_ep timed", runs.any { it.backend == Backend.CPU_EP })
        for (b in Backend.values())
            assertTrue("$modelId vision cap $b", caps.any { it.tower == "vision" && it.backend == b })
        for (b in listOf(Backend.CPU_XNNPACK, Backend.CPU_EP))
            assertTrue("$modelId text cap $b", caps.any { it.tower == "text" && it.backend == b })
    }
}
