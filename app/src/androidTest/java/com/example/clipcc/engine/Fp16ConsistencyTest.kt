package com.example.clipcc.engine

import ai.onnxruntime.OrtEnvironment
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class Fp16ConsistencyTest {
    private val models = listOf(
        "siglip2-base-patch16-256" to 16, "siglip2-base-patch16-384" to 16,
        "siglip2-large-patch16-384" to 8, "siglip2-so400m-patch14-384" to 4,
    )
    private val labels = listOf("Car", "texting while driving", "a dog")

    @Test fun xnnpack_vs_cpuep_cosine_consistency_per_model() {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val env = OrtEnvironment.getEnvironment()
        val frames = listOf("frame_000.png", "frame_001.png").map { n ->
            ctx.assets.open("fixtures/$n").use { BitmapFactory.decodeStream(it) }
        }
        for ((modelId, batch) in models) {
            val dir = "/data/local/tmp/clipcc_models/$modelId"
            val manifest = ModelBundleManifest.parse(File("$dir/manifest.json").readText())
            val xnn = Engine(dir, manifest, env, Backend.CPU_XNNPACK).scoreFrames(frames, labels)
            val cpu = Engine(dir, manifest, env, Backend.CPU_EP, visionBatch = batch).scoreFrames(frames, labels)
            var maxAbs = 0f
            for (f in xnn.cosine.indices) for (l in labels.indices)
                maxAbs = maxOf(maxAbs, abs(xnn.cosine[f][l] - cpu.cosine[f][l]))
            println("FP16CONSIST $modelId max_abs=$maxAbs")
            assertTrue("$modelId XNNPACK vs CPU_EP cosine ($maxAbs)", maxAbs <= 1e-2f)
        }
    }
}
