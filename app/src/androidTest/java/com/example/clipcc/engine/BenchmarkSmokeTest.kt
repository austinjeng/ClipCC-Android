package com.example.clipcc.engine

import ai.onnxruntime.OrtEnvironment
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@UnstableApi
@RunWith(AndroidJUnit4::class)
class BenchmarkSmokeTest {
    @Test fun base256_cpuep_two_frames_smoke() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val env = OrtEnvironment.getEnvironment()
        val dir = "/data/local/tmp/clipcc_models/siglip2-base-patch16-256"
        val manifest = ModelBundleManifest.parse(File("$dir/manifest.json").readText())
        val bench = Benchmark(ctx, env)
        val (prep, bitmaps, holder) = bench.prepFrames(dir, manifest, "/data/local/tmp/clipcc_bench/test.mp4")
        holder.decodeMs = prep.decodeMs; holder.preMs = prep.preprocessMs
        val run = bench.timeLane(dir, manifest, Backend.CPU_EP, holder, prep.frames,
            listOf("Car", "a dog"), runOrder = 0)
        println("SMOKE ${run.modelId} vision_ms=${run.visionMsMedian} fps=${run.fps} batch=${run.effectiveBatch} thermal=${run.meta.thermalStatus}")
        assertTrue("vision timed", run.visionMsMedian >= 0)
        assertTrue("decode timed", prep.decodeMs >= 0)
    }

    @Test fun writes_and_reads_result_json_and_emits_path() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val env = OrtEnvironment.getEnvironment()
        val dir = "/data/local/tmp/clipcc_models/siglip2-base-patch16-256"
        val manifest = ModelBundleManifest.parse(File("$dir/manifest.json").readText())
        val bench = Benchmark(ctx, env)
        val (prep, _, holder) = bench.prepFrames(dir, manifest, "/data/local/tmp/clipcc_bench/test.mp4")
        holder.decodeMs = prep.decodeMs; holder.preMs = prep.preprocessMs
        val run = bench.timeLane(dir, manifest, Backend.CPU_EP, holder, prep.frames, listOf("Car", "a dog"), 0)
        val path = bench.writeResults(listOf(prep), listOf(run), emptyList())
        // emit the absolute path to the instrumentation runner (survives, pullable)
        val b = android.os.Bundle(); b.putString("benchmark_result_path", path); inst.sendStatus(0, b)
        println("RESULT_PATH $path")
        val back = File(path).readText()
        org.junit.Assert.assertTrue("json has runs", back.contains("\"visionMsMedian\""))
    }
}
